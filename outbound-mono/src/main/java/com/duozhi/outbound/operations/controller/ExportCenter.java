package com.duozhi.outbound.operations.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.runtime.ScheduledWork;
import com.duozhi.outbound.operations.service.OperationsAccess;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

/** Persisted export requests survive restart; only the requesting user can retrieve the file. */
@RestController
@RequestMapping("/api/v1/ops/exports")
public class ExportCenter {
    private final WorkspaceDocuments docs;
    private final Operations operations;
    private final OperationsAccess access;
    private final Store store;
    private final ScheduledWork scheduled;
    private final CompetitiveWorkflows workflows;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.operations.service.ExportStorage files;

    private long lastTenant;
    private long lastCleanup;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.customer.controller.ContactFiles contactFiles;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.auth.service.AccountSecurity accountSecurity;

    @PostMapping("/phones")
    public Object phoneFile(
            @RequestParam
            org.springframework.web.multipart.MultipartFile file,
            HttpServletRequest r)
            throws java.io.IOException {
        permitted(actor(r));
        if (file.isEmpty() || file.getSize() > 2 * 1024 * 1024)
            throw new ApiError(422, "号码文件须小于2MB");
        var phones = new LinkedHashSet<String>();
        try (var csv =
                org.apache.commons.csv.CSVFormat.DEFAULT.parse(
                        new java.io.StringReader(contactFiles.readContent(file)))) {
            for (var row : csv)
                for (String field : row) {
                    String phone = field.trim().replaceFirst("^\\uFEFF", "");
                    if (phone.matches("\\+?[0-9]{6,15}")) phones.add(phone);
                    if (phones.size() > 1000) throw new ApiError(422, "批量号码最多1000个");
                }
        }
        if (phones.isEmpty()) throw new ApiError(422, "文件中未找到有效号码");
        return Map.of("phones", phones);
    }

    public ExportCenter(
            WorkspaceDocuments docs,
            Operations operations,
            OperationsAccess access,
            Store store,
            ScheduledWork scheduled,
            CompetitiveWorkflows workflows) {
        this.docs = docs;
        this.operations = operations;
        this.access = access;
        this.store = store;
        this.scheduled = scheduled;
        this.workflows = workflows;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.requireUser();
        return i;
    }

    private void permitted(Identity i) {
        if (!access.flag(i, "can_export")) throw new ApiError(403, "没有导出权限");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private SeatBehavior seatBehavior;

    private String accessSnapshot(Identity i) {
        return docs.json.json(
                Map.of(
                        "visibility",
                        com.duozhi.outbound.common.support.JsonSupport.sha(
                                docs.json.json(
                                        docs.jdbc.queryForList(
                                                "SELECT campaign_id,JSON_EXTRACT(snapshot_json,'$.task_options.mask_numbers') AS hidden,JSON_EXTRACT(snapshot_json,'$.policy.flow_settings.handoff_hide_dialogue') AS private_dialogue FROM platform_campaign_snapshot WHERE tenant_id=? AND (snapshot_json->>'$.task_options.mask_numbers'='true' OR snapshot_json->>'$.policy.flow_settings.handoff_hide_dialogue'='true') ORDER BY campaign_id",
                                                i.tenant()))),
                        "success_phone",
                        seatBehavior.effective(i.tenant(), i.userId()).get("success_phone"),
                        "export_verification",
                        accountSecurity.settings(i.tenant(), i.userId()).get("export_verification"),
                        "all",
                        access.all(i),
                        "unmask",
                        access.flag(i, "can_unmask"),
                        "role",
                        i.user().get("role"),
                        "users",
                        access.users(i).stream()
                                .map(u -> u.get("id").toString())
                                .sorted()
                                .toList()));
    }

    @GetMapping
    public Object list(HttpServletRequest r) {
        var i = actor(r);
        return docs.jdbc.queryForList(
                "SELECT j.id,j.kind,CASE WHEN j.expires_at<=UTC_TIMESTAMP(3) AND j.state='ready' THEN 'expired' ELSE j.state END AS state,j.error,j.created_at,j.updated_at,j.expires_at,j.content_bytes,CASE WHEN j.state='queued' THEN (SELECT COUNT(*) FROM ops_export_job q WHERE q.tenant_id=j.tenant_id AND q.state IN ('queued','processing') AND (q.created_at<j.created_at OR q.created_at=j.created_at AND q.id<=j.id)) ELSE NULL END AS queue_position FROM ops_export_job j WHERE j.tenant_id=? AND j.user_id=? ORDER BY j.created_at DESC LIMIT 200",
                i.tenant(),
                i.userId());
    }

    @PostMapping
    public Object create(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        var i = actor(r);
        permitted(i);
        accountSecurity.export(i, Objects.toString(b.get("verification_code"), ""));
        String kind = Objects.toString(b.get("kind"), "");
        if (!Set.of(
                        "calls",
                        "calls-custom",
                        "reports",
                        "intents",
                        "dataset",
                        "tasks",
                        "benchmark:intercept",
                        "benchmark:follow",
                        "benchmark:sms",
                        "benchmark:recharge",
                        "benchmark:routes")
                .contains(kind)) throw new ApiError(422, "导出类型无效");
        String key = WorkspaceDocuments.text(b, "request_key", 64, true);
        var query = WorkspaceDocuments.map(b.get("query"));
        if (query.size() > 50 || docs.json.json(query).length() > 12000)
            throw new ApiError(422, "筛选条件过多");
        var q = new LinkedHashMap<String, String>();
        query.forEach(
                (k, v) -> {
                    if (v != null) q.put(k, v.toString());
                });
        if (kind.equals("calls-custom")) {
            if (!Set.of("calls", "phones").contains(q.getOrDefault("export_fields", "calls"))
                    || !Set.of("both", "customer", "none")
                            .contains(q.getOrDefault("export_dialogue", "both"))
                    || !Set.of("day", "count").contains(q.getOrDefault("export_split", "day")))
                throw new ApiError(422, "导出配置无效");
            com.duozhi.outbound.operations.service.OutboundRules.integer(
                    q.getOrDefault("export_count", "1000"), 1, 10000);
            if (!Set.of("all", "undialed", "answered", "unanswered")
                    .contains(q.getOrDefault("export_type", "all")))
                throw new ApiError(422, "查询类型无效");
            if (!q.getOrDefault("export_types", "").isBlank()
                    && !Set.of("undialed", "answered", "unanswered")
                            .containsAll(Arrays.asList(q.get("export_types").split(","))))
                throw new ApiError(422, "查询类型无效");
            if (q.getOrDefault("export_phones", "").split(",").length > 1000)
                throw new ApiError(422, "批量查询最多1000个号码");
        }
        // Validate filters and enforce the same access predicate before putting work in the queue.
        if (kind.equals("calls") || kind.equals("calls-custom")) {
            var sample = new LinkedHashMap<>(q);
            sample.put("size", "1");
            sample.put("page", "1");
            var data = operations.callsFor(sample, i);
            if (((Number) data.get("total")).longValue() > 10000)
                throw new ApiError(422, "每份最多一万条，请缩小筛选范围");
        } else if (kind.equals("reports")) operations.reportsFor(q, i);
        else if (kind.equals("intents")) operations.intentsFor(q, i);
        else if (Set.of("tasks", "dataset").contains(kind)) {
            var data =
                    WorkspaceDocuments.map(
                            kind.equals("tasks")
                                    ? workflows.tasksFor(q, i)
                                    : workflows.datasetFor(q, i));
            if (((Number) data.get("total")).longValue() > 10000)
                throw new ApiError(422, "每份最多一万条，请缩小筛选范围");
        } else workflows.reportFor(kind.substring(10), q, i);
        return docs.tx.run(
                () -> {
                    // Tenant row locking makes the pending limit and idempotency atomic, including
                    // simultaneous requests from different users in the same tenant.
                    docs.one("SELECT id FROM tenant WHERE id=? FOR UPDATE", i.tenant());
                    var prior =
                            docs.one(
                                    "SELECT id,kind,query_json FROM ops_export_job WHERE tenant_id=? AND user_id=? AND request_key=?",
                                    i.tenant(),
                                    i.userId(),
                                    key);
                    String encoded = docs.json.json(q);
                    if (prior != null) {
                        if (!kind.equals(prior.get("kind"))
                                || !encoded.equals(prior.get("query_json")))
                            throw new ApiError(409, "重复请求内容不同");
                        return Map.of("id", prior.get("id"));
                    }
                    var pending =
                            docs.jdbc.queryForMap(
                                    "SELECT COUNT(*) AS tenant_pending,COALESCE(SUM(user_id=?),0) AS user_pending FROM ops_export_job WHERE tenant_id=? AND state IN ('queued','processing')",
                                    i.userId(),
                                    i.tenant());
                    if (((Number) pending.get("tenant_pending")).intValue() >= 20
                            || ((Number) pending.get("user_pending")).intValue() >= 5)
                        throw new ApiError(429, "待处理导出过多，请待已有任务完成后重试");
                    String id = UUID.randomUUID().toString();
                    docs.jdbc.update(
                            "INSERT INTO ops_export_job(id,tenant_id,user_id,request_key,kind,query_json,created_at,updated_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                            id,
                            i.tenant(),
                            i.userId(),
                            key,
                            kind,
                            encoded);
                    return Map.of("id", id);
                });
    }

    @Scheduled(fixedDelay = 1500)
    public void tick() {
        scheduled.submit(
                ScheduledWork.Job.REPORT_EXPORT,
                () -> {
                    cleanupExpired();
                    for (int n = 0; n < 4 && ScheduledWork.withinBudget(); n++) processNext();
                });
    }

    /** Bounded cleanup retains failed deletions for retry and never exposes expired files. */
    public synchronized void cleanupExpired() {
        if (System.nanoTime() - lastCleanup < java.util.concurrent.TimeUnit.SECONDS.toNanos(30))
            return;
        lastCleanup = System.nanoTime();
        try {
            files.cleanupPartials();
        } catch (java.io.IOException ignored) {
        }
        for (var row :
                docs.jdbc.queryForList(
                        "SELECT id,storage_key FROM ops_export_job WHERE state IN ('ready','failed') AND expires_at<=UTC_TIMESTAMP(3) AND (cleanup_after IS NULL OR cleanup_after<=UTC_TIMESTAMP(3)) ORDER BY COALESCE(cleanup_after,expires_at),id LIMIT 25")) {
            if (!ScheduledWork.withinBudget()) break;
            try {
                if (row.get("storage_key") != null) files.delete(row.get("storage_key").toString());
                docs.jdbc.update(
                        "UPDATE ops_export_job SET error=CASE WHEN state='ready' THEN '文件已过期，请重新导出' ELSE error END,state=CASE WHEN state='ready' THEN 'expired' ELSE state END,content=NULL,storage_key=NULL,access_json=NULL,expires_at=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND expires_at<=UTC_TIMESTAMP(3)",
                        row.get("id"));
            } catch (Exception retryLater) {
                // Preserve expiry for download denial, but move failed deletions behind untouched
                // expired objects so a dead object store cannot starve the cleanup queue.
                docs.jdbc.update(
                        "UPDATE ops_export_job SET cleanup_after=UTC_TIMESTAMP(3)+INTERVAL 5 MINUTE WHERE id=?",
                        row.get("id"));
                if (retryLater instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
    }

    public synchronized void processNext() {
        var job =
                docs.tx.run(
                        () -> {
                            docs.jdbc.update(
                                    "UPDATE ops_export_job SET state='queued' WHERE state='processing' AND updated_at<UTC_TIMESTAMP(3)-INTERVAL 2 MINUTE");
                            var row =
                                    docs.one(
                                            "SELECT * FROM ops_export_job WHERE state='queued' AND tenant_id>? ORDER BY tenant_id,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                                            lastTenant);
                            if (row == null)
                                row =
                                        docs.one(
                                                "SELECT * FROM ops_export_job WHERE state='queued' ORDER BY tenant_id,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
                            if (row != null) {
                                lastTenant = ((Number) row.get("tenant_id")).longValue();
                                String key =
                                        row.get("storage_key") == null
                                                ? files.key(lastTenant, row.get("id").toString())
                                                : row.get("storage_key").toString();
                                row.put("storage_key", key);
                                docs.jdbc.update(
                                        "UPDATE ops_export_job SET state='processing',storage_key=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                                        key,
                                        row.get("id"));
                            }
                            return row;
                        });
        if (job == null) return;
        java.nio.file.Path temporary = null;
        try {
            long tenant = ((Number) job.get("tenant_id")).longValue();
            var user = store.require("user", tenant, job.get("user_id"));
            if (!Boolean.TRUE.equals(user.get("enabled"))) throw new ApiError(403, "账号已冻结");
            var i = new Identity(tenant, user, Set::of);
            permitted(i);
            String snapshot = accessSnapshot(i);
            var q = new LinkedHashMap<String, String>();
            docs.json
                    .object(job.get("query_json").toString())
                    .forEach((k, v) -> q.put(k, v.toString()));
            String kind = job.get("kind").toString();
            temporary = files.temporary();
            try (var target = files.bounded(temporary)) {
                if (kind.equals("calls-custom")) customExport(q, i, target);
                else
                    try (var writer =
                            new java.io.OutputStreamWriter(
                                    target, java.nio.charset.StandardCharsets.UTF_8)) {
                        if (kind.equals("calls")) operations.writeCalls(q, i, writer, 10000);
                        else if (kind.equals("reports")) operations.writeReport(q, i, writer);
                        else writeRows(kind, q, i, writer);
                    }
            }
            long bytes = java.nio.file.Files.size(temporary);
            // A privacy change while generating must never bless an older file with newer rights.
            if (!snapshot.equals(accessSnapshot(i))) throw new ApiError(403, "数据权限已变化，请重新导出");
            files.put(
                    job.get("storage_key").toString(),
                    temporary,
                    kind.equals("calls-custom") ? "application/zip" : "text/csv;charset=UTF-8");
            docs.tx.run(
                    () -> {
                        docs.jdbc.update(
                                "UPDATE ops_export_job SET state='ready',content=NULL,content_bytes=?,expires_at=TIMESTAMPADD(HOUR,?,UTC_TIMESTAMP(3)),access_json=?,error=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                                bytes,
                                files.retentionHours(),
                                snapshot,
                                job.get("id"));
                        operations.notice(
                                i,
                                i.userId(),
                                "download:" + job.get("id"),
                                "导出文件已就绪",
                                "/operations/notices?tab=downloads");
                        return null;
                    });
        } catch (Exception failed) {
            boolean deleted = false;
            try {
                files.delete(job.get("storage_key").toString());
                deleted = true;
            } catch (Exception ignored) {
            }
            docs.jdbc.update(
                    "UPDATE ops_export_job SET state='failed',content=NULL,storage_key=?,expires_at=CASE WHEN ? IS NULL THEN NULL ELSE UTC_TIMESTAMP(3) END,error=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                    deleted ? null : job.get("storage_key"),
                    deleted ? null : job.get("storage_key"),
                    failed instanceof ApiError
                            ? Objects.toString(failed.getMessage(), "导出失败")
                                    .substring(
                                            0,
                                            Math.min(
                                                    300,
                                                    Objects.toString(failed.getMessage(), "导出失败")
                                                            .length()))
                            : "导出失败，请缩小范围后重试",
                    job.get("id"));
            if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (temporary != null)
                try {
                    java.nio.file.Files.deleteIfExists(temporary);
                } catch (java.io.IOException ignored) {
                }
        }
    }

    @SuppressWarnings("unchecked")
    private void writeRows(String kind, Map<String, String> q, Identity i, java.io.Writer writer)
            throws java.io.IOException {
        writer.write('\ufeff');
        var csv =
                new org.apache.commons.csv.CSVPrinter(
                        writer, org.apache.commons.csv.CSVFormat.DEFAULT);
        if (Set.of("tasks", "dataset").contains(kind)) {
            var query = new LinkedHashMap<>(q);
            query.put("size", "200");
            List<String> keys = null;
            int written = 0;
            for (int page = 1; page <= 50; page++) {
                if (!ScheduledWork.withinBudget()) throw new ApiError(422, "导出超时，请缩小范围");
                query.put("page", "" + page);
                var data =
                        WorkspaceDocuments.map(
                                kind.equals("tasks")
                                        ? workflows.tasksFor(query, i)
                                        : workflows.datasetFor(query, i));
                if (((Number) data.get("total")).longValue() > 10000)
                    throw new ApiError(422, "每份最多一万条，请缩小筛选范围");
                var rows = (List<Map<String, Object>>) data.get("rows");
                if (keys == null && !rows.isEmpty()) {
                    keys = new ArrayList<>(rows.getFirst().keySet());
                    csv.printRecord(keys);
                }
                for (var row : rows) {
                    var values = new ArrayList<Object>();
                    for (String key : keys) values.add(Operations.csvCell(row.get(key)));
                    csv.printRecord(values);
                }
                written += rows.size();
                if (rows.isEmpty() || written >= ((Number) data.get("total")).longValue()) {
                    csv.flush();
                    return;
                }
            }
            throw new ApiError(422, "导出超过一万条，请缩小范围");
        }
        var rows =
                (List<Map<String, Object>>)
                        (kind.equals("intents")
                                ? operations.intentsFor(q, i)
                                : workflows.reportFor(kind.substring(10), q, i));
        if (rows.size() > 10000) throw new ApiError(422, "每份最多一万条，请缩小筛选范围");
        if (!rows.isEmpty()) {
            var keys = new ArrayList<>(rows.getFirst().keySet());
            csv.printRecord(keys);
            for (var row : rows)
                csv.printRecord(keys.stream().map(k -> Operations.csvCell(row.get(k))).toList());
        }
        csv.flush();
    }

    private java.io.InputStream content(Map<String, Object> row) throws java.io.IOException {
        if (row.get("storage_key") != null) return files.open(row.get("storage_key").toString());
        if (row.get("content") instanceof byte[] legacy)
            return new java.io.ByteArrayInputStream(legacy);
        throw new ApiError(409, "导出文件缺失，请重新导出");
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<
                    org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            download(@PathVariable
            String id, HttpServletRequest r) throws java.io.IOException {
        var i = actor(r);
        permitted(i);
        var row =
                docs.one(
                        "SELECT kind,state,content,storage_key,content_bytes,access_json,expires_at<=UTC_TIMESTAMP(3) AS expired FROM ops_export_job WHERE tenant_id=? AND user_id=? AND id=?",
                        i.tenant(),
                        i.userId(),
                        id);
        if (row == null) throw new ApiError(404, "导出文件不存在");
        if ("expired".equals(row.get("state"))
                || Boolean.TRUE.equals(row.get("expired"))
                || row.get("expired") instanceof Number n && n.intValue() != 0)
            throw new ApiError(410, "文件已过期，请重新导出");
        if (!"ready".equals(row.get("state"))) throw new ApiError(409, "文件尚未就绪");
        if (!Objects.equals(row.get("access_json"), accessSnapshot(i)))
            throw new ApiError(403, "数据权限已变化，请按当前权限重新导出");
        if ("calls-custom".equals(row.get("kind"))) {
            try (var zip = new java.util.zip.ZipInputStream(content(row))) {
                java.util.zip.ZipEntry entry;
                boolean validated = false;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.getName().equals("manifest.csv")) continue;
                    var reader =
                            new java.io.BufferedReader(
                                    new java.io.InputStreamReader(
                                            zip, java.nio.charset.StandardCharsets.UTF_8));
                    var ids = reader.lines().filter(line -> !line.isBlank()).limit(10001).toList();
                    if (ids.size() > 10000) throw new ApiError(409, "导出索引无效，请重新导出");
                    operations.authorizeExportCalls(i, ids);
                    validated = true;
                    break;
                }
                if (!validated) throw new ApiError(409, "导出索引缺失，请重新导出");
            }
        } else if ("calls".equals(row.get("kind"))) {
            try (var input = content(row);
                    var csv =
                            org.apache.commons.csv.CSVFormat.DEFAULT.parse(
                                    new java.io.InputStreamReader(
                                            input, java.nio.charset.StandardCharsets.UTF_8))) {
                var ids = new ArrayList<String>();
                boolean header = true;
                for (var record : csv) {
                    if (header) {
                        header = false;
                        continue;
                    }
                    ids.add(record.get(0));
                    if (ids.size() == 250) {
                        operations.authorizeExportCalls(i, ids);
                        ids.clear();
                    }
                }
                operations.authorizeExportCalls(i, ids);
            }
        }
        // Only bounded chunks are retained while transferring the private object to the caller.
        return ResponseEntity.ok()
                .header(
                        "Content-Type",
                        "calls-custom".equals(row.get("kind"))
                                ? "application/zip"
                                : "text/csv;charset=UTF-8")
                .header("Cache-Control", "no-store")
                .header(
                        "Content-Disposition",
                        "attachment; filename="
                                + (row.get("kind").equals("calls-custom")
                                        ? "custom-calls.zip"
                                        : row.get("kind").equals("calls")
                                                ? "filtered-calls.csv"
                                                : "operations-report.csv"))
                .body(
                        output -> {
                            try (var stream = content(row)) {
                                stream.transferTo(output);
                            }
                        });
    }

    private void customExport(Map<String, String> q, Identity i, java.io.OutputStream target)
            throws Exception {
        var source = files.temporary();
        var rows = new LinkedHashMap<String, java.nio.file.Path>();
        var budget =
                new com.duozhi.outbound.operations.service.ExportStorage.Budget(
                        com.duozhi.outbound.operations.service.ExportStorage.MAX_BYTES);
        try {
            try (var out = files.bounded(source);
                    var writer =
                            new java.io.OutputStreamWriter(
                                    out, java.nio.charset.StandardCharsets.UTF_8)) {
                operations.writeCalls(q, i, writer, 10000);
            }
            var ids = new ArrayList<String>();
            var phones =
                    new HashSet<>(Arrays.asList(q.getOrDefault("export_phones", "").split(",")));
            boolean phonesOnly = "phones".equals(q.get("export_fields"));
            String dialogue = q.getOrDefault("export_dialogue", "both"),
                    type = q.getOrDefault("export_type", "all");
            int perFile = Integer.parseInt(q.getOrDefault("export_count", "1000")), accepted = 0;
            List<String> header;
            List<String> columns;
            try (var parser =
                    org.apache.commons.csv.CSVFormat.DEFAULT
                            .builder()
                            .setHeader()
                            .setSkipHeaderRecord(true)
                            .get()
                            .parse(bomReader(source))) {
                header = new ArrayList<>(parser.getHeaderNames());
                columns = new ArrayList<>(phonesOnly ? List.of("phone") : header);
                if (!phonesOnly && !dialogue.equals("none")) columns.add("dialogue");
                if (!phonesOnly) columns.add("variables");
                for (var record : parser) {
                    if (!ScheduledWork.withinBudget()) throw new ApiError(422, "导出超时，请缩小范围");
                    String id = record.get("id");
                    var fact =
                            docs.jdbc.queryForMap(
                                    "SELECT c.phone,c.campaign_id,c.attempts,EXISTS(SELECT 1 FROM callusage u WHERE u.tenant_id=c.tenant_id AND u.call_session_id=c.id AND u.answered_at IS NOT NULL) AS answered FROM callsession c WHERE c.tenant_id=? AND c.id=?",
                                    i.tenant(),
                                    id);
                    if (!q.getOrDefault("export_phones", "").isBlank()
                            && !phones.contains(fact.get("phone").toString())) continue;
                    boolean answered =
                            Boolean.TRUE.equals(fact.get("answered"))
                                    || fact.get("answered") instanceof Number n && n.intValue() > 0;
                    String category =
                            answered
                                    ? "answered"
                                    : ((Number) fact.get("attempts")).intValue() == 0
                                            ? "undialed"
                                            : "unanswered";
                    var types =
                            q.getOrDefault("export_types", "").isBlank()
                                    ? Set.of(type)
                                    : new HashSet<>(
                                            Arrays.asList(q.get("export_types").split(",")));
                    if (!types.contains("all") && !types.contains(category)) continue;
                    var snapshot = docs.campaignSnapshot(i.tenant(), fact.get("campaign_id"));
                    boolean hide =
                            !access.flag(i, "can_unmask")
                                    || !Boolean.TRUE.equals(
                                                    seatBehavior
                                                            .effective(i.tenant(), i.userId())
                                                            .get("success_phone"))
                                            && record.isMapped("stage")
                                            && record.get("stage").equals("completed")
                                    || snapshot != null
                                            && Boolean.TRUE.equals(
                                                    WorkspaceDocuments.map(
                                                                    snapshot.getOrDefault(
                                                                            "task_options",
                                                                            Map.of()))
                                                            .get("mask_numbers"));
                    boolean privateDialogue =
                            snapshot != null
                                    && !"admin".equals(i.requireUser().get("role"))
                                    && Boolean.TRUE.equals(
                                            com.duozhi.outbound.ai.service.FlowSettings.from(
                                                            WorkspaceDocuments.map(
                                                                    snapshot.getOrDefault(
                                                                            "policy", Map.of())))
                                                    .get("handoff_hide_dialogue"));
                    if (privateDialogue && !dialogue.equals("none"))
                        throw new ApiError(403, "该话术已隐藏对话内容，请选择不导出对话");
                    var values = new ArrayList<Object>();
                    if (phonesOnly) values.add(record.get("phone"));
                    else for (String key : header) values.add(record.get(key));
                    if (!phonesOnly && !dialogue.equals("none")) {
                        var text = new StringBuilder();
                        for (var turn :
                                docs.jdbc.queryForList(
                                        "SELECT speaker_role,transcript FROM speechturn WHERE tenant_id=? AND call_session_id=? AND is_final=1"
                                                + (dialogue.equals("customer")
                                                        ? " AND speaker_role='customer'"
                                                        : "")
                                                + " ORDER BY attempt,turn_index,id LIMIT 2001",
                                        i.tenant(),
                                        id)) {
                            if (text.length()
                                            + Objects.toString(turn.get("transcript"), "").length()
                                            + 32
                                    > 100000) throw new ApiError(422, "单通对话过长，请分开导出");
                            text.append(turn.get("speaker_role"))
                                    .append(": ")
                                    .append(turn.get("transcript"))
                                    .append('\n');
                        }
                        String transcript = text.toString();
                        if (hide)
                            transcript =
                                    transcript.replaceAll(
                                            "(?<![0-9])([0-9]{3})[0-9]{4}([0-9]{4})(?![0-9])",
                                            "$1****$2");
                        values.add(Operations.csvCell(transcript));
                    }
                    if (!phonesOnly) {
                        var state =
                                docs.one(
                                        "SELECT policy_json,data_json FROM conversationstate WHERE tenant_id=? AND call_id=? ORDER BY attempt DESC LIMIT 1",
                                        i.tenant(),
                                        id);
                        values.add(
                                state == null
                                        ? "{}"
                                        : docs.json.json(
                                                com.duozhi.outbound.ai.service.FlowVariables
                                                        .exported(
                                                                docs.json.object(
                                                                        state.get("policy_json")
                                                                                .toString()),
                                                                WorkspaceDocuments.map(
                                                                        docs.json
                                                                                .object(
                                                                                        state.get(
                                                                                                        "data_json")
                                                                                                .toString())
                                                                                .getOrDefault(
                                                                                        "flow_variables",
                                                                                        Map
                                                                                                .of())))));
                    }
                    if (hide) {
                        for (int n = 0; n < values.size(); n++)
                            if (values.get(n) instanceof String value)
                                values.set(
                                        n,
                                        value.replaceAll(
                                                "(?<![0-9])([0-9]{3})[0-9]{4}([0-9]{4})(?![0-9])",
                                                "$1****$2"));
                    }
                    String group =
                            "count".equals(q.get("export_split"))
                                    ? String.format("part-%03d", accepted / perFile + 1)
                                    : record.get("created_at").substring(0, 10);
                    var part = rows.get(group);
                    boolean first = part == null;
                    if (first) {
                        part = files.temporary();
                        rows.put(group, part);
                    }
                    try (var output =
                                    budget.wrap(
                                            java.nio.file.Files.newOutputStream(
                                                    part,
                                                    java.nio.file.StandardOpenOption.APPEND));
                            var writer =
                                    new java.io.OutputStreamWriter(
                                            output, java.nio.charset.StandardCharsets.UTF_8);
                            var csv =
                                    new org.apache.commons.csv.CSVPrinter(
                                            writer, org.apache.commons.csv.CSVFormat.DEFAULT)) {
                        if (first) {
                            writer.write('\ufeff');
                            csv.printRecord(columns);
                        }
                        csv.printRecord(values);
                    }
                    ids.add(id);
                    accepted++;
                }
            }
            try (var zip = new java.util.zip.ZipOutputStream(target)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("manifest.csv"));
                // The index and all CSV members share one uncompressed byte budget.
                var manifest =
                        budget.wrap(
                                new java.io.FilterOutputStream(zip) {
                                    @Override
                                    public void close() throws java.io.IOException {
                                        flush();
                                    }
                                });
                manifest.write(
                        String.join("\n", ids).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                manifest.flush();
                zip.closeEntry();
                if (rows.isEmpty()) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("empty.csv"));
                    var writer =
                            new java.io.OutputStreamWriter(
                                    zip, java.nio.charset.StandardCharsets.UTF_8);
                    writer.write('\ufeff');
                    var csv =
                            new org.apache.commons.csv.CSVPrinter(
                                    writer, org.apache.commons.csv.CSVFormat.DEFAULT);
                    csv.printRecord(columns);
                    csv.flush();
                    zip.closeEntry();
                }
                for (var group : rows.entrySet()) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(group.getKey() + ".csv"));
                    java.nio.file.Files.copy(group.getValue(), zip);
                    zip.closeEntry();
                }
            }
        } finally {
            java.nio.file.Files.deleteIfExists(source);
            for (var part : rows.values()) java.nio.file.Files.deleteIfExists(part);
        }
    }

    private java.io.Reader bomReader(java.nio.file.Path path) throws java.io.IOException {
        var reader =
                new java.io.PushbackReader(
                        java.nio.file.Files.newBufferedReader(
                                path, java.nio.charset.StandardCharsets.UTF_8),
                        1);
        int first = reader.read();
        if (first >= 0 && first != '\ufeff') reader.unread(first);
        return reader;
    }
}
