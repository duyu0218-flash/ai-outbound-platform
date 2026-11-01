package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Journey lifecycle: drafts, immutable published versions, the start estimate and second-admin
 * approval, enrolment of a snapshot, pause / resume / stop and the run-state view.
 */
@Service
public class JourneyService {
    static final String NO_MEMBERS =
            JourneyEngine.NOT_CONNECTED + "：人群快照（SnapshotMembers，P1）尚未接入，暂不能预估或启动活动";
    static final String NO_SENDER =
            JourneyEngine.NOT_CONNECTED + "：发送通道（ChannelSender，P1）尚未接入，含短信或外呼节点的活动暂不能发布或启动";

    private final MarketingStore db;
    private final JsonSupport json;
    private final MarketingClock clock;
    private final PolicyConfig policies;
    private final FrequencyRules frequency;
    private final SuppressionContactPolicy suppression;
    private final PhoneIdentity phones;
    private final JourneyEngine engine;
    private final ObjectProvider<SnapshotMembers> snapshots;
    private final ObjectProvider<ChannelSender> senders;
    private final TransactionTemplate tx;

    public JourneyService(
            MarketingStore db,
            JsonSupport json,
            MarketingClock clock,
            PolicyConfig policies,
            FrequencyRules frequency,
            SuppressionContactPolicy suppression,
            PhoneIdentity phones,
            JourneyEngine engine,
            ObjectProvider<SnapshotMembers> snapshots,
            ObjectProvider<ChannelSender> senders,
            PlatformTransactionManager manager) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.policies = policies;
        this.frequency = frequency;
        this.suppression = suppression;
        this.phones = phones;
        this.engine = engine;
        this.snapshots = snapshots;
        this.senders = senders;
        this.tx = new TransactionTemplate(manager);
    }

    public Map<String, Object> capabilities() {
        return Map.of(
                "snapshot_members",
                snapshots.getIfAvailable() != null,
                "channel_sender",
                senders.getIfAvailable() != null);
    }

    public List<Map<String, Object>> list(long tenant) {
        return db.rows(
                "SELECT j.id,j.name,j.description,j.status,j.latest_version,j.snapshot_id,j.created_at,j.updated_at,j.started_at,j.ended_at,"
                        + "(SELECT v.version FROM mk_journey_version v WHERE v.tenant_id=j.tenant_id AND v.id=j.run_version_id) run_version,"
                        + "(SELECT COUNT(*) FROM mk_journey_member m WHERE m.tenant_id=j.tenant_id AND m.journey_id=j.id) members,"
                        + "(SELECT COUNT(*) FROM mk_journey_member m WHERE m.tenant_id=j.tenant_id AND m.journey_id=j.id AND m.state IN ('ready','waiting','running')) active_members "
                        + "FROM mk_journey j WHERE j.tenant_id=? ORDER BY j.created_at DESC LIMIT 500",
                tenant);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> create(long tenant, long actor, Map<String, Object> body) {
        String name = name(body);
        Map<String, Object> def = definitionBody(body);
        String id = MarketingStore.id("jr");
        var now = clock.appNow();
        db.write(
                "INSERT INTO mk_journey(id,tenant_id,name,description,status,draft,created_by,created_at,updated_at) VALUES(?,?,?,?,'draft',?,?,?,?)",
                id,
                tenant,
                name,
                description(body),
                json.json(def),
                actor,
                now,
                now);
        return get(tenant, id);
    }

    public Map<String, Object> update(long tenant, String id, Map<String, Object> body) {
        Map<String, Object> j = journey(tenant, id);
        String name = body.containsKey("name") ? name(body) : (String) j.get("name");
        String desc =
                body.containsKey("description") ? description(body) : (String) j.get("description");
        String draft = body.containsKey("definition") ? json.json(definitionBody(body)) : null;
        db.write(
                "UPDATE mk_journey SET name=?,description=?,draft=COALESCE(?,draft),updated_at=? WHERE tenant_id=? AND id=?",
                name,
                desc,
                draft,
                clock.appNow(),
                tenant,
                id);
        return get(tenant, id);
    }

    public Map<String, Object> get(long tenant, String id) {
        Map<String, Object> j = new LinkedHashMap<>(journey(tenant, id));
        j.put("draft", json.object(j.get("draft").toString()));
        if (j.get("estimate") != null) j.put("estimate", json.object(j.get("estimate").toString()));
        j.put(
                "versions",
                db.rows(
                        "SELECT id,version,definition_hash,published_by,published_at FROM mk_journey_version WHERE tenant_id=? AND journey_id=? ORDER BY version DESC",
                        tenant,
                        id));
        Map<String, Object> run =
                j.get("run_version_id") == null
                        ? null
                        : db.one(
                                "SELECT version FROM mk_journey_version WHERE tenant_id=? AND id=?",
                                tenant,
                                j.get("run_version_id"));
        j.put("run_version", run == null ? null : run.get("version"));
        return j;
    }

    public Map<String, Object> version(long tenant, String id, int version) {
        Map<String, Object> v =
                db.one(
                        "SELECT id,journey_id,version,definition,definition_hash,published_by,published_at FROM mk_journey_version WHERE tenant_id=? AND journey_id=? AND version=?",
                        tenant,
                        id,
                        version);
        if (v == null) throw new ApiError(404, "版本不存在");
        Map<String, Object> out = new LinkedHashMap<>(v);
        out.put("definition", json.object(v.get("definition").toString()));
        return out;
    }

    /**
     * Freezes the draft as a new version. Published versions are never modified; publishing an
     * unchanged draft returns the latest version instead of creating a duplicate.
     */
    public Map<String, Object> publish(long tenant, long actor, String id) {
        return tx.execute(
                s -> {
                    Map<String, Object> j =
                            db.one(
                                    "SELECT * FROM mk_journey WHERE tenant_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    id);
                    if (j == null) throw new ApiError(404, "活动不存在");
                    if ("stopped".equals(j.get("status")) || "completed".equals(j.get("status")))
                        throw new ApiError(409, "活动已结束，请复制为新活动后再发布");
                    Map<String, Object> raw = json.object(j.get("draft").toString());
                    JourneyDefinition def = JourneyDefinition.parse(raw);
                    if (def.sends() && senders.getIfAvailable() == null)
                        throw new ApiError(409, NO_SENDER);
                    String canonical = json.canonical(raw, false);
                    String hash = HexFormat.of().formatHex(JsonSupport.digest(canonical));
                    int latest = ((Number) j.get("latest_version")).intValue();
                    Map<String, Object> last =
                            latest == 0
                                    ? null
                                    : db.one(
                                            "SELECT definition_hash FROM mk_journey_version WHERE tenant_id=? AND journey_id=? AND version=?",
                                            tenant,
                                            id,
                                            latest);
                    boolean created = last == null || !hash.equals(last.get("definition_hash"));
                    if (created) {
                        latest++;
                        db.write(
                                "INSERT INTO mk_journey_version(id,tenant_id,journey_id,version,definition,definition_hash,published_by,published_at) VALUES(?,?,?,?,?,?,?,?)",
                                MarketingStore.id("jrv"),
                                tenant,
                                id,
                                latest,
                                canonical,
                                hash,
                                actor,
                                clock.appNow());
                        db.write(
                                "UPDATE mk_journey SET latest_version=?,status=IF(status='draft','published',status),updated_at=? WHERE tenant_id=? AND id=?",
                                latest,
                                clock.appNow(),
                                tenant,
                                id);
                    }
                    Map<String, Object> out = new LinkedHashMap<>(version(tenant, id, latest));
                    out.put("created", created);
                    return out;
                });
    }

    private record Latest(String id, int version, JourneyDefinition def) {}

    private Latest latest(long tenant, String id) {
        Map<String, Object> v =
                db.one(
                        "SELECT v.id,v.version,v.definition FROM mk_journey_version v JOIN mk_journey j ON j.tenant_id=v.tenant_id AND j.id=v.journey_id AND j.latest_version=v.version WHERE v.tenant_id=? AND v.journey_id=?",
                        tenant,
                        id);
        if (v == null) throw new ApiError(409, "请先发布活动");
        return new Latest(
                (String) v.get("id"),
                ((Number) v.get("version")).intValue(),
                JourneyDefinition.parse(json.object(v.get("definition").toString())));
    }

    /**
     * Admission estimate for the latest version over a snapshot: suppression and frequency caps now
     * (the contact window only defers sends, so it is not counted), and the worst-path cost.
     */
    public Map<String, Object> estimate(long tenant, String id, String snapshotId) {
        journey(tenant, id);
        SnapshotMembers members = snapshots.getIfAvailable();
        if (members == null) throw new ApiError(409, NO_MEMBERS);
        if (snapshotId == null || snapshotId.isBlank()) throw new ApiError(422, "请填写人群快照编号");
        Latest v = latest(tenant, id);
        PolicyConfig.Settings s = policies.get(tenant);
        String channel = v.def().firstChannel();
        long total = 0, test = 0, control = 0, reachable = 0, filtered = 0;
        Map<String, Long> reasons = new TreeMap<>();
        long after = 0;
        var now = clock.now();
        while (true) {
            List<SnapshotMembers.Member> page = members.page(tenant, snapshotId, after, 1000);
            if (page.isEmpty()) break;
            for (SnapshotMembers.Member m : page) {
                after = Math.max(after, m.sequence());
                total++;
                if ("control".equals(m.group())) {
                    control++;
                    continue;
                }
                test++;
                String reason = null;
                if (channel != null) {
                    String e164 = null;
                    try {
                        e164 = phones.decrypt(tenant, m.phoneCipher());
                    } catch (RuntimeException e) {
                        reason = "号码无法解密";
                    }
                    if (e164 != null) {
                        var a = suppression.admit(tenant, e164, m.phoneHash(), channel);
                        if (!a.allowed()) reason = a.reason();
                        else {
                            var d = frequency.check(tenant, m.phoneHash(), channel, now, false);
                            if (!d.allowed()) reason = d.reason();
                        }
                    }
                }
                if (reason == null) reachable++;
                else {
                    filtered++;
                    reasons.merge(reason, 1L, Long::sum);
                }
            }
            if (page.size() < 1000) break;
        }
        BigDecimal perMember = v.def().worstPathCost(s.smsUnitPrice(), s.callUnitPrice());
        BigDecimal cost =
                perMember.multiply(BigDecimal.valueOf(reachable)).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot_id", snapshotId);
        out.put("version", v.version());
        out.put("total", total);
        out.put("test", test);
        out.put("control", control);
        out.put("reachable", reachable);
        out.put("filtered", filtered);
        out.put("filtered_reasons", reasons);
        out.put("first_channel", channel);
        out.put("cost_per_member", perMember);
        out.put("estimated_cost", cost);
        out.put("approval_threshold", s.approvalThreshold());
        out.put("needs_approval", cost.compareTo(s.approvalThreshold()) > 0);
        return out;
    }

    /** Starts at once, or parks the journey for a second administrator above the threshold. */
    public Map<String, Object> start(long tenant, long actor, String id, String snapshotId) {
        Map<String, Object> j = journey(tenant, id);
        String status = (String) j.get("status");
        if ("pending_approval".equals(status)) throw new ApiError(409, "活动正在等待另一位管理员审批");
        if (!"published".equals(status))
            throw new ApiError(409, "draft".equals(status) ? "请先发布活动" : "活动已启动过，不能重复启动");
        Latest v = latest(tenant, id);
        if (v.def().sends() && senders.getIfAvailable() == null) throw new ApiError(409, NO_SENDER);
        Map<String, Object> estimate = estimate(tenant, id, snapshotId);
        if (((Number) estimate.get("total")).longValue() == 0)
            throw new ApiError(422, "人群快照为空或不存在");
        if (Boolean.TRUE.equals(estimate.get("needs_approval"))) {
            int n =
                    db.write(
                            "UPDATE mk_journey SET status='pending_approval',snapshot_id=?,run_version_id=?,estimate=?,requested_by=?,approved_by=NULL,updated_at=? WHERE tenant_id=? AND id=? AND status='published'",
                            snapshotId,
                            v.id(),
                            json.json(estimate),
                            actor,
                            clock.appNow(),
                            tenant,
                            id);
            if (n == 0) throw new ApiError(409, "活动状态已变化，请刷新后重试");
            return get(tenant, id);
        }
        int n =
                db.write(
                        "UPDATE mk_journey SET status='running',snapshot_id=?,run_version_id=?,estimate=?,requested_by=?,started_at=?,updated_at=? WHERE tenant_id=? AND id=? AND status='published'",
                        snapshotId,
                        v.id(),
                        json.json(estimate),
                        actor,
                        clock.appNow(),
                        clock.appNow(),
                        tenant,
                        id);
        if (n == 0) throw new ApiError(409, "活动状态已变化，请刷新后重试");
        enroll(tenant, id, v.id(), v.def().start(), snapshotId);
        return get(tenant, id);
    }

    public Map<String, Object> approve(long tenant, long actor, String id) {
        Map<String, Object> j = journey(tenant, id);
        if (!"pending_approval".equals(j.get("status"))) throw new ApiError(409, "活动不在待审批状态");
        Object requester = j.get("requested_by");
        if (actor == 0 || (requester != null && ((Number) requester).longValue() == actor))
            throw new ApiError(403, "需要另一位管理员审批");
        SnapshotMembers members = snapshots.getIfAvailable();
        if (members == null) throw new ApiError(409, NO_MEMBERS);
        String versionId = (String) j.get("run_version_id");
        JourneyDefinition def = engine.definition(tenant, versionId);
        if (def.sends() && senders.getIfAvailable() == null) throw new ApiError(409, NO_SENDER);
        int n =
                db.write(
                        "UPDATE mk_journey SET status='running',approved_by=?,started_at=?,updated_at=? WHERE tenant_id=? AND id=? AND status='pending_approval'",
                        actor,
                        clock.appNow(),
                        clock.appNow(),
                        tenant,
                        id);
        if (n == 0) throw new ApiError(409, "活动状态已变化，请刷新后重试");
        enroll(tenant, id, versionId, def.start(), (String) j.get("snapshot_id"));
        return get(tenant, id);
    }

    public Map<String, Object> reject(long tenant, long actor, String id) {
        int n =
                db.write(
                        "UPDATE mk_journey SET status='published',run_version_id=NULL,snapshot_id=NULL,requested_by=NULL,updated_at=? WHERE tenant_id=? AND id=? AND status='pending_approval'",
                        clock.appNow(),
                        tenant,
                        id);
        if (n == 0) throw new ApiError(409, "活动不在待审批状态");
        return get(tenant, id);
    }

    /** Idempotent: a member sequence joins a journey once (unique key), even if start repeats. */
    private void enroll(
            long tenant, String id, String versionId, String startNode, String snapshotId) {
        SnapshotMembers members = snapshots.getIfAvailable();
        if (members == null) throw new ApiError(409, NO_MEMBERS);
        long after = 0;
        while (true) {
            List<SnapshotMembers.Member> page = members.page(tenant, snapshotId, after, 1000);
            if (page.isEmpty()) break;
            var now = clock.appNow();
            List<Object[]> rows = new ArrayList<>();
            for (SnapshotMembers.Member m : page) {
                after = Math.max(after, m.sequence());
                rows.add(
                        new Object[] {
                            MarketingStore.id("jm"),
                            tenant,
                            id,
                            versionId,
                            snapshotId,
                            m.sequence(),
                            m.phoneHash(),
                            m.phoneCipher(),
                            m.contactId(),
                            "control".equals(m.group()) ? "control" : "test",
                            startNode,
                            now,
                            now,
                            now,
                            now
                        });
            }
            db.jdbc()
                    .batchUpdate(
                            "INSERT IGNORE INTO mk_journey_member(id,tenant_id,journey_id,version_id,snapshot_id,member_sequence,phone_hash,phone_cipher,contact_id,grp,node_id,node_entered_at,state,next_run_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'ready',?,?,?)",
                            rows);
            if (page.size() < 1000) break;
        }
    }

    public Map<String, Object> pause(long tenant, String id) {
        transition(tenant, id, "running", "paused", "只有运行中的活动可以暂停");
        return get(tenant, id);
    }

    public Map<String, Object> resume(long tenant, String id) {
        transition(tenant, id, "paused", "running", "只有已暂停的活动可以继续");
        return get(tenant, id);
    }

    public Map<String, Object> stop(long tenant, String id) {
        tx.executeWithoutResult(
                s -> {
                    int n =
                            db.write(
                                    "UPDATE mk_journey SET status='stopped',ended_at=?,updated_at=? WHERE tenant_id=? AND id=? AND status IN ('running','paused','pending_approval')",
                                    clock.appNow(),
                                    clock.appNow(),
                                    tenant,
                                    id);
                    if (n == 0) throw new ApiError(409, "活动未在运行，无需停止");
                    db.write(
                            "UPDATE mk_journey_member SET state='stopped',next_run_at=NULL,last_result='活动已停止',updated_at=? WHERE tenant_id=? AND journey_id=? AND state IN ('ready','waiting')",
                            clock.appNow(),
                            tenant,
                            id);
                });
        return get(tenant, id);
    }

    private void transition(long tenant, String id, String from, String to, String error) {
        int n =
                db.write(
                        "UPDATE mk_journey SET status=?,updated_at=? WHERE tenant_id=? AND id=? AND status=?",
                        to,
                        clock.appNow(),
                        tenant,
                        id,
                        from);
        if (n == 0) throw new ApiError(409, error);
    }

    /** Members per node and state, step results per node, and touch outcomes of the journey. */
    public Map<String, Object> runtime(long tenant, String id) {
        Map<String, Object> j = journey(tenant, id);
        String versionId = (String) j.get("run_version_id");
        Map<String, Object> def;
        if (versionId != null) {
            def =
                    json.object(
                            db.one(
                                            "SELECT definition FROM mk_journey_version WHERE tenant_id=? AND id=?",
                                            tenant,
                                            versionId)
                                    .get("definition")
                                    .toString());
        } else def = json.object(j.get("draft").toString());
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        if (def.get("nodes") instanceof List<?> list)
            for (Object o : list)
                if (o instanceof Map<?, ?> n) {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", n.get("id"));
                    view.put("type", n.get("type"));
                    view.put("name", n.get("name"));
                    view.put("members", new TreeMap<String, Long>());
                    view.put("steps", new TreeMap<String, Long>());
                    nodes.put(String.valueOf(n.get("id")), view);
                }
        for (Map<String, Object> r :
                db.rows(
                        "SELECT node_id,state,COUNT(*) n FROM mk_journey_member WHERE tenant_id=? AND journey_id=? GROUP BY node_id,state",
                        tenant,
                        id)) {
            Map<String, Object> n = nodes.get(String.valueOf(r.get("node_id")));
            if (n != null) counts(n, "members").put((String) r.get("state"), number(r.get("n")));
        }
        for (Map<String, Object> r :
                db.rows(
                        "SELECT node_id,action,COUNT(*) n FROM mk_journey_step WHERE tenant_id=? AND journey_id=? GROUP BY node_id,action",
                        tenant,
                        id)) {
            Map<String, Object> n = nodes.get(String.valueOf(r.get("node_id")));
            if (n != null) counts(n, "steps").put((String) r.get("action"), number(r.get("n")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("journey", get(tenant, id));
        out.put("nodes", new ArrayList<>(nodes.values()));
        out.put(
                "groups",
                db.rows(
                        "SELECT grp,state,COUNT(*) n FROM mk_journey_member WHERE tenant_id=? AND journey_id=? GROUP BY grp,state ORDER BY grp,state",
                        tenant,
                        id));
        out.put(
                "outcomes",
                db.rows(
                        "SELECT code,COUNT(*) n FROM mk_touch_event WHERE tenant_id=? AND journey_id=? GROUP BY code ORDER BY n DESC",
                        tenant,
                        id));
        out.put(
                "recent",
                db.rows(
                        "SELECT s.node_id,s.node_type,s.action,s.reason,s.created_at,m.grp FROM mk_journey_step s JOIN mk_journey_member m ON m.id=s.member_id WHERE s.tenant_id=? AND s.journey_id=? ORDER BY s.id DESC LIMIT 50",
                        tenant,
                        id));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> counts(Map<String, Object> node, String key) {
        return (Map<String, Long>) node.get(key);
    }

    private static long number(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    Map<String, Object> journey(long tenant, String id) {
        Map<String, Object> j =
                db.one("SELECT * FROM mk_journey WHERE tenant_id=? AND id=?", tenant, id);
        if (j == null) throw new ApiError(404, "活动不存在");
        return j;
    }

    private static String name(Map<String, Object> body) {
        String s = Objects.toString(body.get("name"), "").trim();
        if (s.isEmpty()) throw new ApiError(422, "活动名称不能为空");
        if (s.length() > 128) throw new ApiError(422, "活动名称过长");
        return s;
    }

    private static String description(Map<String, Object> body) {
        String s = Objects.toString(body.get("description"), "").trim();
        if (s.length() > 512) throw new ApiError(422, "活动说明过长");
        return s.isEmpty() ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> definitionBody(Map<String, Object> body) {
        Object d = body.get("definition");
        if (!(d instanceof Map<?, ?> m)) throw new ApiError(422, "活动流程不能为空");
        JourneyDefinition.parse((Map<String, Object>) m);
        return (Map<String, Object>) m;
    }
}
