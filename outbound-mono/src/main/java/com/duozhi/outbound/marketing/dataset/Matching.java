package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Partner matching. Requests are reserved when a job is submitted; the reserved -> pending
 * compare-and-set is the only permission to send, so a request is charged at most once. Pending and
 * uncertain requests are never sent again automatically. A success or no-data answer is cached for
 * 30 days per partner + product + number and reused without another charge.
 */
@Service
public class Matching {
    static final int STEP = 100, CACHE_DAYS = 30;
    static final Set<String> ANSWERED = Set.of("success", "no_data", "cached");

    private final MarketingStore db;
    private final PhoneIdentity phones;
    private final JsonSupport json;
    private final Config config;
    private final TransactionTemplate tx;
    private final AntDasMatchAdapter das;
    private final MatchFlights flights;
    private final Map<String, MatchAdapter> adapters = new LinkedHashMap<>();

    public Matching(
            MarketingStore db,
            PhoneIdentity phones,
            JsonSupport json,
            Config config,
            TransactionTemplate tx,
            List<MatchAdapter> adapters,
            AntDasMatchAdapter das,
            MatchFlights flights) {
        this.db = db;
        this.phones = phones;
        this.json = json;
        this.config = config;
        this.tx = tx;
        this.das = das;
        this.flights = flights;
        for (var a : adapters)
            if (this.adapters.put(a.id(), a) != null)
                throw new IllegalStateException("duplicate adapter");
    }

    MatchAdapter adapter(String id) {
        var a = adapters.get(id);
        if (a == null) throw new ApiError(422, "未知的撞库方式");
        return a;
    }

    public List<Map<String, Object>> adapterList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var a : adapters.values())
            out.add(
                    JsonSupport.map(
                            "id", a.id(),
                            "label", a.label(),
                            "configured", a.configured(),
                            "online", a.online(),
                            "reason", a.unavailableReason(),
                            "qps", a.online() ? a.qps() : 0));
        return out;
    }

    // ---- partners

    public List<Map<String, Object>> partners(long tenant) {
        return db.rows("SELECT * FROM mk_partner WHERE tenant_id=? ORDER BY created_at", tenant);
    }

    public Map<String, Object> partner(long tenant, String id) {
        var row = db.one("SELECT * FROM mk_partner WHERE tenant_id=? AND id=?", tenant, id);
        if (row == null) throw new ApiError(404, "合作方不存在");
        return row;
    }

    public Map<String, Object> savePartner(long tenant, String id, Map<String, Object> input) {
        String name = str(input.get("name"));
        String code = str(input.get("code"));
        String adapter = str(input.get("adapter"));
        adapter(adapter);
        String product = str(input.get("product"));
        if (product.isEmpty())
            product = MatchAdapter.ANT_DAS.equals(adapter) ? "intention" : "default";
        if (name.isEmpty() || name.length() > 128) throw new ApiError(422, "合作方名称应为 1 至 128 个字符");
        if (!product.matches("[A-Za-z0-9_.-]{1,64}")) throw new ApiError(422, "产品编码格式不正确");
        boolean enabled = !Boolean.FALSE.equals(input.get("enabled"));
        Object cfg = input.getOrDefault("config", Map.of());
        if (id == null) {
            if (!code.matches("[A-Za-z0-9_-]{2,64}"))
                throw new ApiError(422, "合作方编码应为 2 至 64 位字母、数字、_ 或 -");
            if (db.one("SELECT id FROM mk_partner WHERE tenant_id=? AND code=?", tenant, code)
                    != null) throw new ApiError(409, "合作方编码已存在");
            id = MarketingStore.id("ptn");
            db.write(
                    "INSERT INTO mk_partner(id,tenant_id,code,name,adapter,product,config,enabled) VALUES(?,?,?,?,?,?,?,?)",
                    id,
                    tenant,
                    code,
                    name,
                    adapter,
                    product,
                    json.json(cfg),
                    enabled);
        } else {
            partner(tenant, id);
            db.write(
                    "UPDATE mk_partner SET name=?,adapter=?,product=?,config=?,enabled=? WHERE tenant_id=? AND id=?",
                    name,
                    adapter,
                    product,
                    json.json(cfg),
                    enabled,
                    tenant,
                    id);
        }
        return partner(tenant, id);
    }

    // ---- jobs

    public Map<String, Object> job(long tenant, String id) {
        var row =
                db.one(
                        "SELECT j.*,p.name AS partner_name,p.code AS partner_code FROM mk_match_job j JOIN mk_partner p ON p.id=j.partner_id AND p.tenant_id=j.tenant_id WHERE j.tenant_id=? AND j.id=?",
                        tenant,
                        id);
        if (row == null) throw new ApiError(404, "撞库任务不存在");
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("counts", counts(tenant, id));
        return out;
    }

    Map<String, Object> counts(long tenant, String job) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s :
                List.of(
                        "reserved",
                        "pending",
                        "waiting",
                        "success",
                        "no_data",
                        "cached",
                        "failed",
                        "uncertain")) counts.put(s, 0L);
        for (var r :
                db.rows(
                        "SELECT state,COUNT(*) n FROM mk_match_request WHERE tenant_id=? AND job_id=? GROUP BY state",
                        tenant,
                        job))
            counts.put((String) r.get("state"), ((Number) r.get("n")).longValue());
        counts.put(
                "charged",
                db.count(
                        "SELECT COUNT(*) FROM mk_match_request WHERE tenant_id=? AND job_id=? AND charged=TRUE",
                        tenant,
                        job));
        return counts;
    }

    public List<Map<String, Object>> jobs(long tenant, String versionId) {
        var rows =
                db.rows(
                        "SELECT j.*,p.name AS partner_name FROM mk_match_job j JOIN mk_partner p ON p.id=j.partner_id AND p.tenant_id=j.tenant_id WHERE j.tenant_id=?"
                                + (versionId == null ? "" : " AND j.version_id=?")
                                + " ORDER BY j.created_at DESC LIMIT 200",
                        versionId == null
                                ? new Object[] {tenant}
                                : new Object[] {tenant, versionId});
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            var m = new LinkedHashMap<>(r);
            m.put("counts", counts(tenant, (String) r.get("id")));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> submit(
            long tenant, String partnerId, String versionId, Integer topN, long actor) {
        var partner = partner(tenant, partnerId);
        if (!Boolean.TRUE.equals(partner.get("enabled"))) throw new ApiError(409, "合作方已停用");
        var adapter = adapter((String) partner.get("adapter"));
        if (!adapter.configured()) throw new ApiError(503, adapter.unavailableReason());
        var version =
                db.one(
                        "SELECT * FROM mk_dataset_version WHERE tenant_id=? AND id=?",
                        tenant,
                        versionId);
        if (version == null) throw new ApiError(404, "数据包版本不存在");
        if (!"ready".equals(version.get("state"))) throw new ApiError(409, "数据包版本尚未导入完成");
        String auth = (String) version.get("authorization_ref");
        if (auth == null || auth.isBlank()) throw new ApiError(409, "该版本缺少授权凭证编号，不能提交撞库");
        int n = topN == null ? 3 : topN;
        if (n < 1 || n > 3) throw new ApiError(422, "推荐时段数量必须为 1、2 或 3");
        String id = MarketingStore.id("mj");
        return tx.execute(
                s -> {
                    db.write(
                            "INSERT INTO mk_match_job(id,tenant_id,partner_id,adapter,product,version_id,authorization_ref,origin,top_n,state,created_by) VALUES(?,?,?,?,?,?,?,'dataset',?,?,?)",
                            id,
                            tenant,
                            partnerId,
                            adapter.id(),
                            partner.get("product"),
                            versionId,
                            auth,
                            n,
                            adapter.online() ? "queued" : "awaiting_file",
                            actor);
                    // Blocked rows cannot be contacted, so they are never sent to a paid partner.
                    int total =
                            db.write(
                                    "INSERT INTO mk_match_request(id,tenant_id,job_id,position,row_id,phone_hash,phone_cipher,phone_mask,state)"
                                            + " SELECT CONCAT(?,'-',r.id),r.tenant_id,?,ROW_NUMBER() OVER (ORDER BY r.id),r.id,r.phone_hash,r.phone_cipher,r.phone_mask,'reserved'"
                                            + " FROM mk_dataset_row r WHERE r.tenant_id=? AND r.version_id=? AND r.status='valid' AND r.blocked_reason IS NULL",
                                    id,
                                    id,
                                    tenant,
                                    versionId);
                    if (total == 0) throw new ApiError(422, "该版本没有可提交的有效号码");
                    db.write(
                            "UPDATE mk_match_job SET total=? WHERE tenant_id=? AND id=?",
                            total,
                            tenant,
                            id);
                    return job(tenant, id);
                });
    }

    public Map<String, Object> resume(long tenant, String id) {
        var job = job(tenant, id);
        if (!"paused".equals(job.get("state"))) throw new ApiError(409, "只有已暂停的任务可以继续");
        var adapter = adapter((String) job.get("adapter"));
        if (!adapter.configured()) throw new ApiError(503, adapter.unavailableReason());
        db.write(
                "UPDATE mk_match_job SET state='queued',pause_reason=NULL WHERE tenant_id=? AND id=? AND state='paused'",
                tenant,
                id);
        return job(tenant, id);
    }

    /** Runs up to {@code steps} single steps for one job (page button and tests). */
    public Map<String, Object> run(long tenant, String id, int steps) {
        job(tenant, id);
        for (int i = 0; i < steps && step(id); i++) {}
        return job(tenant, id);
    }

    /**
     * Single step: sends up to {@value #STEP} reserved requests of one online job (or of the oldest
     * queued job when {@code jobId} is null). Returns false when there is nothing to do.
     */
    public boolean step(String jobId) {
        // Worker scan across tenants; the job row's tenant_id binds every following statement.
        var job =
                jobId == null
                        ? db.one(
                                "SELECT * FROM mk_match_job WHERE state IN ('queued','running') AND adapter IN ('"
                                        + String.join(
                                                "','",
                                                adapters.values().stream()
                                                        .filter(MatchAdapter::online)
                                                        .map(MatchAdapter::id)
                                                        .toList())
                                        + "') ORDER BY created_at LIMIT 1")
                        : db.one(
                                "SELECT * FROM mk_match_job WHERE id=? AND state IN ('queued','running')",
                                jobId);
        if (job == null) return false;
        long tenant = ((Number) job.get("tenant_id")).longValue();
        String id = (String) job.get("id");
        var adapter = adapter((String) job.get("adapter"));
        if (!adapter.online()) return false;
        if (!adapter.configured()) {
            pause(tenant, id, adapter.unavailableReason());
            return false;
        }
        db.write(
                "UPDATE mk_match_job SET state='running' WHERE tenant_id=? AND id=? AND state='queued'",
                tenant,
                id);
        flights.refresh(job);
        var batch =
                db.rows(
                        "SELECT * FROM mk_match_request WHERE tenant_id=? AND job_id=? AND state='reserved' ORDER BY position LIMIT ?",
                        tenant,
                        id,
                        STEP);
        if (batch.isEmpty()) {
            finish(tenant, id);
            return false;
        }
        group(job, adapter, batch);
        return true;
    }

    record Done(boolean sentNow, String state, String code) {}

    /**
     * Port of BatchQueries.group: bounded concurrency, pause on critical codes or too many errors.
     */
    private void group(
            Map<String, Object> job, MatchAdapter adapter, List<Map<String, Object>> batch) {
        long tenant = ((Number) job.get("tenant_id")).longValue();
        String id = (String) job.get("id");
        int concurrency = Math.max(1, Math.min(32, config.number("DAS_CONCURRENCY", 4)));
        int sent = 0, errors = 0, consecutive = 0, inFlight = 0;
        boolean abort = false;
        String reason = "";
        var plans = batch.iterator();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var completion = new ExecutorCompletionService<Done>(pool);
            while (true) {
                while (inFlight < concurrency && !abort && plans.hasNext()) {
                    var request = plans.next();
                    completion.submit(() -> perform(job, adapter, request));
                    inFlight++;
                }
                if (inFlight == 0) break;
                Done done;
                try {
                    done = completion.take().get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    abort = true;
                    reason = "处理被中断，已暂停新增调用，请核对流水。";
                    inFlight--;
                    continue;
                } catch (ExecutionException e) {
                    // A persistence failure is never a permission to repeat a charged call.
                    abort = true;
                    reason = "结果保存异常，已暂停新增调用，请核对流水。";
                    inFlight--;
                    continue;
                }
                inFlight--;
                if (!done.sentNow()) continue;
                sent++;
                boolean failed = !Set.of("success", "no_data").contains(done.state());
                errors += failed ? 1 : 0;
                consecutive = failed ? consecutive + 1 : 0;
                boolean critical = adapter.criticalCodes().contains(done.code());
                boolean excessive = consecutive >= 5 || (sent >= 20 && errors * 10 >= sent);
                if (!abort && (critical || excessive)) {
                    abort = true;
                    reason = "上游限流、权限异常或错误过多，已暂停新增调用；已发请求未重试，请核对。";
                }
            }
        }
        if (abort) pause(tenant, id, reason);
    }

    private void pause(long tenant, String id, String reason) {
        db.write(
                "UPDATE mk_match_job SET state='paused',pause_reason=? WHERE tenant_id=? AND id=? AND state IN ('queued','running')",
                reason,
                tenant,
                id);
    }

    private void finish(long tenant, String id) {
        try {
            finishDatabaseState(tenant, id);
        } catch (CannotAcquireLockException e) {
            // Completion is database-only, with no permission to send a supplier request.
            // A concurrent claim may deadlock this scan; leave the job for the next poll.
            LoggerFactory.getLogger(Matching.class)
                    .debug(
                            "matching completion deferred after lock contention tenant={} job={}",
                            tenant,
                            id);
        }
    }

    private void finishDatabaseState(long tenant, String id) {
        // A pending request whose send never reported back stays unresolved: never resent.
        db.write(
                "UPDATE mk_match_request SET state='uncertain',code='PENDING_REVIEW',message='原调用结果待核对，未重复发送。' WHERE tenant_id=? AND job_id=? AND state='pending' AND sent_at < NOW(6) - INTERVAL 5 MINUTE",
                tenant,
                id);
        if (db.count(
                        "SELECT COUNT(*) FROM mk_match_request WHERE tenant_id=? AND job_id=? AND state IN ('reserved','pending','waiting')",
                        tenant,
                        id)
                == 0)
            db.write(
                    "UPDATE mk_match_job SET state='finished',finished_at=NOW(6) WHERE tenant_id=? AND id=? AND state IN ('queued','running')",
                    tenant,
                    id);
    }

    private Done perform(
            Map<String, Object> job, MatchAdapter adapter, Map<String, Object> request) {
        long tenant = ((Number) job.get("tenant_id")).longValue();
        String rid = (String) request.get("id");
        String e164 = phones.decrypt(tenant, (String) request.get("phone_cipher"));
        if (MatchAdapter.ANT_DAS.equals(adapter.id()) && AntDasMatchAdapter.local(e164) == null) {
            db.write(
                    "UPDATE mk_match_request SET state='failed',code='INVALID_PHONE',message='仅支持中国大陆 11 位手机号码。',finished_at=NOW(6) WHERE id=? AND state='reserved'",
                    rid);
            return new Done(false, "failed", "INVALID_PHONE");
        }
        // The database flight and request CAS commit together before any network activity.
        if (!flights.claim(job, request)) return new Done(false, "shared", "");
        var outcome =
                adapter.query(
                        new MatchAdapter.Query(
                                e164,
                                ((Number) job.get("top_n")).intValue(),
                                (String) job.get("authorization_ref"),
                                rid));
        save(
                tenant,
                (String) job.get("partner_id"),
                (String) job.get("product"),
                rid,
                (String) request.get("phone_hash"),
                outcome,
                "api",
                "pending");
        return new Done(true, outcome.state(), outcome.code());
    }

    Map<String, Object> cached(long tenant, String partner, String product, String hash) {
        return db.one(
                "SELECT * FROM mk_match_result WHERE tenant_id=? AND partner_id=? AND product=? AND phone_hash=? AND expires_at>NOW(6)",
                tenant,
                partner,
                product,
                hash);
    }

    private void save(
            long tenant,
            String partner,
            String product,
            String requestId,
            String hash,
            MatchAdapter.Outcome o,
            String source,
            String fromState) {
        var r = o.result();
        tx.executeWithoutResult(
                s -> {
                    if ("api".equals(source)) flights.lockOwner(requestId);
                    int changed =
                            db.write(
                                    "UPDATE mk_match_request SET state=?,code=?,message=?,request_id=?,hit=?,score=?,grade=?,best_hours=?,labels=?,finished_at=NOW(6) WHERE id=? AND (state=? OR (?='pending' AND state='uncertain'))",
                                    o.state(),
                                    trim(o.code(), 32),
                                    trim(o.message(), 255),
                                    trim(o.requestId(), 128),
                                    r == null ? null : r.hit(),
                                    r == null ? null : r.score(),
                                    r == null ? null : r.grade(),
                                    r == null ? null : json.json(r.bestHours()),
                                    r == null ? null : json.json(r.labels()),
                                    requestId,
                                    fromState,
                                    fromState);
                    if (changed != 1) return;
                    if ("api".equals(source)) flights.complete(requestId, o.state());
                    if (r != null && Set.of("success", "no_data").contains(o.state()))
                        cache(tenant, partner, product, hash, r, source, o.requestId());
                });
    }

    void cache(
            long tenant,
            String partner,
            String product,
            String hash,
            MatchAdapter.Normalized r,
            String source,
            String requestId) {
        db.write(
                "INSERT INTO mk_match_result(id,tenant_id,partner_id,product,phone_hash,hit,score,grade,best_hours,labels,raw,source,request_id,matched_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(6),NOW(6) + INTERVAL "
                        + CACHE_DAYS
                        + " DAY)"
                        + " ON DUPLICATE KEY UPDATE hit=VALUES(hit),score=VALUES(score),grade=VALUES(grade),best_hours=VALUES(best_hours),labels=VALUES(labels),raw=VALUES(raw),source=VALUES(source),request_id=VALUES(request_id),matched_at=VALUES(matched_at),expires_at=VALUES(expires_at)",
                MarketingStore.id("mr"),
                tenant,
                partner,
                product,
                hash,
                r.hit(),
                r.score(),
                r.grade(),
                json.json(r.bestHours()),
                json.json(r.labels()),
                json.json(r.raw() == null ? Map.of() : r.raw()),
                source,
                trim(requestId, 128));
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    // ---- hash file exchange

    /** Applies the cache, then returns one SHA-256 per line for every request still unanswered. */
    public String exportHashes(long tenant, String id) {
        var job = job(tenant, id);
        if (!MatchAdapter.HASH_FILE.equals(job.get("adapter")))
            throw new ApiError(409, "该任务不是哈希文件交换方式");
        if (!Set.of("awaiting_file").contains(job.get("state")))
            throw new ApiError(409, "任务已完成，不能再次导出");
        StringBuilder out = new StringBuilder("sha256\n");
        long after = 0;
        while (true) {
            var rows =
                    db.rows(
                            "SELECT * FROM mk_match_request WHERE tenant_id=? AND job_id=? AND state IN ('reserved','pending') AND position>? ORDER BY position LIMIT 1000",
                            tenant,
                            id,
                            after);
            if (rows.isEmpty()) break;
            for (var r : rows) {
                after = ((Number) r.get("position")).longValue();
                var c =
                        cached(
                                tenant,
                                (String) job.get("partner_id"),
                                (String) job.get("product"),
                                (String) r.get("phone_hash"));
                if (c != null && "reserved".equals(r.get("state"))) {
                    db.write(
                            "UPDATE mk_match_request SET state='cached',code='CACHED',message='复用 30 天内结果，未再次计费。',hit=?,score=?,grade=?,best_hours=?,labels=?,finished_at=NOW(6) WHERE id=? AND state='reserved'",
                            c.get("hit"),
                            c.get("score"),
                            c.get("grade"),
                            c.get("best_hours"),
                            c.get("labels"),
                            r.get("id"));
                    continue;
                }
                out.append(
                                HashFileMatchAdapter.exchangeHash(
                                        phones.decrypt(tenant, (String) r.get("phone_cipher"))))
                        .append('\n');
                db.write(
                        "UPDATE mk_match_request SET state='pending',charged=TRUE,code='EXPORTED',sent_at=COALESCE(sent_at,NOW(6)) WHERE id=? AND state='reserved'",
                        r.get("id"));
            }
        }
        return out.toString();
    }

    /**
     * Imports a partner hit file (sha256[,hit,score,grade,labels,best_hours]); unlisted = no hit.
     */
    public Map<String, Object> importHits(long tenant, String id, byte[] content) {
        var job = job(tenant, id);
        if (!MatchAdapter.HASH_FILE.equals(job.get("adapter")))
            throw new ApiError(409, "该任务不是哈希文件交换方式");
        if (!"awaiting_file".equals(job.get("state"))) throw new ApiError(409, "任务已完成");
        if (db.count(
                        "SELECT COUNT(*) FROM mk_match_request WHERE tenant_id=? AND job_id=? AND state='reserved'",
                        tenant,
                        id)
                > 0) throw new ApiError(409, "请先导出哈希文件并交给合作方");
        var records = TextFiles.csv(TextFiles.decode(content).text());
        if (records.isEmpty()) throw new ApiError(422, "文件为空");
        var head =
                records.getFirst().fields().stream()
                        .map(s -> s.trim().toLowerCase(Locale.ROOT))
                        .toList();
        int hashCol = indexOf(head, "sha256", "hash", "哈希");
        boolean header = hashCol >= 0;
        if (!header) hashCol = 0;
        int hitCol = indexOf(head, "hit", "命中"),
                scoreCol = indexOf(head, "score", "分值", "分数"),
                gradeCol = indexOf(head, "grade", "等级"),
                labelCol = indexOf(head, "labels", "label", "标签"),
                hourCol = indexOf(head, "best_hours", "hours", "时段");
        Map<String, MatchAdapter.Normalized> hits = new HashMap<>();
        for (var rec : header ? records.subList(1, records.size()) : records) {
            var f = rec.fields();
            String h = TextFiles.cell(f, hashCol).toLowerCase(Locale.ROOT);
            if (!h.matches("[0-9a-f]{64}")) continue;
            String hitText = TextFiles.cell(f, hitCol);
            boolean hit =
                    hitText.isEmpty()
                            || Set.of("1", "true", "y", "yes", "是", "命中")
                                    .contains(hitText.toLowerCase(Locale.ROOT));
            BigDecimal score = null;
            try {
                String s = TextFiles.cell(f, scoreCol);
                if (!s.isEmpty()) score = MatchAdapter.percent(new BigDecimal(s));
            } catch (NumberFormatException ignored) {
            }
            String grade = TextFiles.cell(f, gradeCol).toUpperCase(Locale.ROOT);
            if (!grade.matches("[ABCD]")) grade = MatchAdapter.grade(score);
            List<String> labels = new ArrayList<>();
            for (String l : TextFiles.cell(f, labelCol).split("[|;、]"))
                if (!l.isBlank() && labels.size() < 20) labels.add(trim(l.trim(), 64));
            List<Integer> hours = new ArrayList<>();
            for (String part : TextFiles.cell(f, hourCol).split("[|;、 ]")) {
                String p = part.trim().replaceAll(":00$", "");
                if (p.matches("[01]?[0-9]|2[0-3]") && hours.size() < 24)
                    hours.add(Integer.parseInt(p));
            }
            hits.put(
                    h,
                    hit
                            ? new MatchAdapter.Normalized(
                                    true, score, grade, hours, labels, Map.of("file", true))
                            : MatchAdapter.Normalized.miss(Map.of("file", true)));
        }
        int matched = 0, missed = 0;
        Set<String> used = new HashSet<>();
        long after = 0;
        while (true) {
            var rows =
                    db.rows(
                            "SELECT * FROM mk_match_request WHERE tenant_id=? AND job_id=? AND state='pending' AND position>? ORDER BY position LIMIT 1000",
                            tenant,
                            id,
                            after);
            if (rows.isEmpty()) break;
            for (var r : rows) {
                after = ((Number) r.get("position")).longValue();
                String h =
                        HashFileMatchAdapter.exchangeHash(
                                phones.decrypt(tenant, (String) r.get("phone_cipher")));
                var n = hits.get(h);
                if (n != null) used.add(h);
                if (n != null && n.hit()) matched++;
                else missed++;
                var outcome =
                        n != null && n.hit()
                                ? new MatchAdapter.Outcome(
                                        "success", "FILE_HIT", "合作方文件命中。", "", 200, n)
                                : new MatchAdapter.Outcome(
                                        "no_data",
                                        "FILE_MISS",
                                        "合作方文件未命中。",
                                        "",
                                        200,
                                        MatchAdapter.Normalized.miss(Map.of("file", true)));
                save(
                        tenant,
                        (String) job.get("partner_id"),
                        (String) job.get("product"),
                        (String) r.get("id"),
                        (String) r.get("phone_hash"),
                        outcome,
                        "hash_file",
                        "pending");
            }
        }
        db.write(
                "UPDATE mk_match_job SET state='finished',finished_at=NOW(6) WHERE tenant_id=? AND id=?",
                tenant,
                id);
        return JsonSupport.map(
                "matched", matched, "missed", missed, "unknown", hits.size() - used.size());
    }

    private static int indexOf(List<String> head, String... names) {
        for (String n : names) {
            int i = head.indexOf(n);
            if (i >= 0) return i;
        }
        return -1;
    }

    // ---- ant-platform result CSV

    static final Map<String, String> ANT_STATES = Map.of("成功", "success", "无数据", "no_data");

    /**
     * Imports a result CSV exported by ant-platform batches.py (批次ID, 原始行号, 手机号, 推荐序号, 时间（小时）, 分值,
     * 查询状态, 结果码, 上游请求ID, 本次流水). Rows with 成功 / 无数据 become results and cache entries; unconfirmed
     * rows (失败, 待核对, ...) are skipped, never resent.
     */
    public Map<String, Object> importAntPlatform(
            long tenant, String partnerId, String versionId, byte[] content, long actor) {
        var partner = partner(tenant, partnerId);
        if (versionId != null && !versionId.isBlank())
            if (db.one(
                            "SELECT id FROM mk_dataset_version WHERE tenant_id=? AND id=?",
                            tenant,
                            versionId)
                    == null) throw new ApiError(404, "数据包版本不存在");
        var records = TextFiles.csv(TextFiles.decode(content).text());
        if (records.isEmpty()) throw new ApiError(422, "文件为空");
        var head = records.getFirst().fields().stream().map(String::trim).toList();
        int phoneCol = head.indexOf("手机号"),
                hourCol = head.indexOf("时间（小时）"),
                scoreCol = head.indexOf("分值"),
                stateCol = head.indexOf("查询状态"),
                codeCol = head.indexOf("结果码"),
                ridCol = head.indexOf("上游请求ID"),
                rankCol = head.indexOf("推荐序号");
        if (phoneCol < 0 || stateCol < 0 || scoreCol < 0 || hourCol < 0)
            throw new ApiError(422, "不是 ant-platform 导出的结果文件（缺少 手机号 / 时间（小时） / 分值 / 查询状态 列）");
        record Item(
                String e164,
                String state,
                String code,
                String rid,
                List<AntDasMatchAdapter.HourScore> scores) {}
        Map<String, Item> items = new LinkedHashMap<>();
        int invalid = 0, skipped = 0;
        for (var rec : records.subList(1, records.size())) {
            var f = rec.fields();
            String e164 = PhoneIdentity.normalize(TextFiles.cell(f, phoneCol));
            if (e164 == null) {
                invalid++;
                continue;
            }
            String state = ANT_STATES.get(TextFiles.cell(f, stateCol));
            if (state == null) {
                skipped++;
                continue;
            }
            var item =
                    items.computeIfAbsent(
                            e164,
                            k ->
                                    new Item(
                                            k,
                                            state,
                                            TextFiles.cell(f, codeCol),
                                            TextFiles.cell(f, ridCol),
                                            new ArrayList<>()));
            String hour = TextFiles.cell(f, hourCol).replaceAll(":00$", ""),
                    score = TextFiles.cell(f, scoreCol);
            if ("success".equals(item.state())
                    && !TextFiles.cell(f, rankCol).isEmpty()
                    && hour.matches("[01]?[0-9]|2[0-3]")
                    && !score.isEmpty()) {
                try {
                    int h = Integer.parseInt(hour);
                    if (item.scores().stream().noneMatch(s -> s.hour() == h))
                        item.scores()
                                .add(new AntDasMatchAdapter.HourScore(h, new BigDecimal(score)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (items.isEmpty()) throw new ApiError(422, "文件中没有可导入的成功或无数据结果");
        String id = MarketingStore.id("mj");
        int position = 0;
        final int skippedRows = skipped, invalidRows = invalid;
        db.write(
                "INSERT INTO mk_match_job(id,tenant_id,partner_id,adapter,product,version_id,origin,top_n,state,total,created_by,finished_at) VALUES(?,?,?,?,?,?,'import',3,'finished',?,?,NOW(6))",
                id,
                tenant,
                partnerId,
                partner.get("adapter"),
                partner.get("product"),
                versionId == null || versionId.isBlank() ? null : versionId,
                items.size(),
                actor);
        for (var item : items.values()) {
            position++;
            String hash = phones.hash(tenant, item.e164());
            Long rowId = null;
            if (versionId != null && !versionId.isBlank()) {
                var row =
                        db.one(
                                "SELECT id FROM mk_dataset_row WHERE tenant_id=? AND version_id=? AND unique_hash=?",
                                tenant,
                                versionId,
                                hash);
                if (row != null) rowId = ((Number) row.get("id")).longValue();
            }
            item.scores().sort((a, b) -> b.score().compareTo(a.score()));
            boolean hit = "success".equals(item.state()) && !item.scores().isEmpty();
            var normalized =
                    hit
                            ? AntDasMatchAdapter.normalize(item.scores())
                            : MatchAdapter.Normalized.miss(Map.of("imported", true));
            String rid = item.rid().matches("[\\w-]{0,128}") ? item.rid() : "";
            String code =
                    item.code().matches("[A-Z0-9_]{1,32}") ? item.code() : (hit ? "OK" : "10029");
            String requestRow = id + "-" + position;
            db.write(
                    "INSERT INTO mk_match_request(id,tenant_id,job_id,position,row_id,phone_hash,phone_cipher,phone_mask,state,code,message,request_id,charged,hit,score,grade,best_hours,labels,finished_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,FALSE,?,?,?,?,?,NOW(6))",
                    requestRow,
                    tenant,
                    id,
                    position,
                    rowId,
                    hash,
                    phones.encrypt(tenant, item.e164()),
                    PhoneIdentity.mask(item.e164()),
                    hit ? "success" : "no_data",
                    code,
                    "导入 ant-platform 已有结果，未再次调用。",
                    rid,
                    normalized.hit(),
                    normalized.score(),
                    normalized.grade(),
                    json.json(normalized.bestHours()),
                    json.json(normalized.labels()));
            cache(
                    tenant,
                    partnerId,
                    (String) partner.get("product"),
                    hash,
                    normalized,
                    "import",
                    rid);
            flights.reconcile(
                    tenant,
                    partnerId,
                    (String) partner.get("product"),
                    hash,
                    requestRow,
                    hit ? "success" : "no_data");
        }
        var out = new LinkedHashMap<>(job(tenant, id));
        out.put("imported", items.size());
        out.put("skipped_rows", skippedRows);
        out.put("invalid_rows", invalidRows);
        return out;
    }

    // ---- analysis

    public Map<String, Object> analysis(long tenant, String id) {
        var job = job(tenant, id);
        @SuppressWarnings("unchecked")
        var counts = (Map<String, Object>) job.get("counts");
        long answered = 0;
        for (String s : ANSWERED) answered += ((Number) counts.get(s)).longValue();
        long hits =
                db.count(
                        "SELECT COUNT(*) FROM mk_match_request WHERE tenant_id=? AND job_id=? AND hit=TRUE",
                        tenant,
                        id);
        List<Map<String, Object>> buckets = new ArrayList<>();
        long[] b = new long[10];
        for (var r :
                db.rows(
                        "SELECT LEAST(9,FLOOR(score/10)) AS b,COUNT(*) AS n FROM mk_match_request WHERE tenant_id=? AND job_id=? AND hit=TRUE AND score IS NOT NULL GROUP BY b",
                        tenant,
                        id))
            b[((Number) r.get("b")).intValue()] = ((Number) r.get("n")).longValue();
        for (int i = 0; i < 10; i++)
            buckets.add(
                    JsonSupport.map(
                            "range", (i * 10) + "-" + (i == 9 ? 100 : i * 10 + 10), "count", b[i]));
        Map<String, Object> grades = new LinkedHashMap<>();
        for (String g : List.of("A", "B", "C", "D")) grades.put(g, 0L);
        for (var r :
                db.rows(
                        "SELECT grade,COUNT(*) AS n FROM mk_match_request WHERE tenant_id=? AND job_id=? AND hit=TRUE AND grade IS NOT NULL GROUP BY grade",
                        tenant,
                        id)) grades.put((String) r.get("grade"), ((Number) r.get("n")).longValue());
        long[] hours = new long[24];
        for (var r :
                db.rows(
                        "SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(best_hours,'$[0]')) AS UNSIGNED) AS h,COUNT(*) AS n FROM mk_match_request WHERE tenant_id=? AND job_id=? AND hit=TRUE AND JSON_LENGTH(best_hours)>0 GROUP BY h",
                        tenant,
                        id)) {
            int h = ((Number) r.get("h")).intValue();
            if (h >= 0 && h < 24) hours[h] = ((Number) r.get("n")).longValue();
        }
        List<Map<String, Object>> hourList = new ArrayList<>();
        for (int h = 0; h < 24; h++) hourList.add(JsonSupport.map("hour", h, "count", hours[h]));
        var labels =
                db.rows(
                        "SELECT t.label,COUNT(*) AS count FROM mk_match_request r, JSON_TABLE(r.labels,'$[*]' COLUMNS(label VARCHAR(64) PATH '$')) t"
                                + " WHERE r.tenant_id=? AND r.job_id=? AND r.hit=TRUE GROUP BY t.label ORDER BY count DESC LIMIT 20",
                        tenant,
                        id);
        Object created = job.get("created_at");
        long overlap =
                db.count(
                        "SELECT COUNT(*) FROM mk_match_request r WHERE r.tenant_id=? AND r.job_id=? AND EXISTS("
                                + "SELECT 1 FROM mk_match_request o JOIN mk_match_job j ON j.id=o.job_id AND j.tenant_id=o.tenant_id"
                                + " WHERE o.tenant_id=r.tenant_id AND o.phone_hash=r.phone_hash AND o.job_id<>r.job_id AND j.created_at<?)",
                        tenant,
                        id,
                        created);
        var byJob =
                db.rows(
                        "SELECT o.job_id,j.created_at,p.name AS partner_name,COUNT(*) AS overlap,SUM(o.hit=TRUE) AS overlap_hits"
                                + " FROM mk_match_request r JOIN mk_match_request o ON o.tenant_id=r.tenant_id AND o.phone_hash=r.phone_hash AND o.job_id<>r.job_id"
                                + " JOIN mk_match_job j ON j.id=o.job_id AND j.tenant_id=o.tenant_id JOIN mk_partner p ON p.id=j.partner_id AND p.tenant_id=j.tenant_id"
                                + " WHERE r.tenant_id=? AND r.job_id=? AND j.created_at<? GROUP BY o.job_id,j.created_at,p.name ORDER BY overlap DESC LIMIT 10",
                        tenant,
                        id,
                        created);
        return JsonSupport.map(
                "job", job,
                "answered", answered,
                "hits", hits,
                "hit_rate", answered == 0 ? 0 : Math.round(hits * 10000.0 / answered) / 100.0,
                "score_distribution", buckets,
                "grades", grades,
                "best_hours", hourList,
                "labels", labels,
                "overlap", overlap,
                "overlap_rate",
                        ((Number) job.get("total")).longValue() == 0
                                ? 0
                                : Math.round(
                                                overlap
                                                        * 10000.0
                                                        / ((Number) job.get("total")).longValue())
                                        / 100.0,
                "overlap_jobs", byJob);
    }

    /** For tests and the DAS status card. */
    AntDasMatchAdapter das() {
        return das;
    }
}
