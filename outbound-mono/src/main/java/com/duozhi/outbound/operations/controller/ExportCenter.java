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
                "SELECT j.id,j.kind,j.state,j.error,j.created_at,j.updated_at,CASE WHEN j.state='queued' THEN (SELECT COUNT(*) FROM ops_export_job q WHERE q.tenant_id=j.tenant_id AND q.state IN ('queued','processing') AND (q.created_at<j.created_at OR q.created_at=j.created_at AND q.id<=j.id)) ELSE NULL END AS queue_position FROM ops_export_job j WHERE j.tenant_id=? AND j.user_id=? ORDER BY j.created_at DESC LIMIT 200",
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
        scheduled.submit(ScheduledWork.Job.REPORT_EXPORT, this::processNext);
    }

    public void processNext() {
        var job =
                docs.tx.run(
                        () -> {
                            docs.jdbc.update(
                                    "UPDATE ops_export_job SET state='queued' WHERE state='processing' AND updated_at<UTC_TIMESTAMP(3)-INTERVAL 2 MINUTE");
                            var row =
                                    docs.one(
                                            "SELECT * FROM ops_export_job WHERE state='queued' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
                            if (row != null)
                                docs.jdbc.update(
                                        "UPDATE ops_export_job SET state='processing',updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                                        row.get("id"));
                            return row;
                        });
        if (job == null) return;
        try {
            long tenant = ((Number) job.get("tenant_id")).longValue();
            var user = store.require("user", tenant, job.get("user_id"));
            if (!Boolean.TRUE.equals(user.get("enabled"))) throw new ApiError(403, "账号已冻结");
            var i = new Identity(tenant, user, Set::of);
            permitted(i);
            var q = new LinkedHashMap<String, String>();
            docs.json
                    .object(job.get("query_json").toString())
                    .forEach((k, v) -> q.put(k, v.toString()));
            String kind = job.get("kind").toString();
            byte[] bytes;
            if (kind.equals("calls-custom")) bytes = customExport(q, i);
            else if (kind.startsWith("benchmark:")
                    || Set.of("intents", "dataset", "tasks").contains(kind)) {
                @SuppressWarnings("unchecked")
                var rows =
                        kind.equals("intents")
                                ? (List<Map<String, Object>>) operations.intentsFor(q, i)
                                : kind.startsWith("benchmark:")
                                        ? (List<Map<String, Object>>)
                                                workflows.reportFor(kind.substring(10), q, i)
                                        : pagedRows(kind, q, i);
                var writer = new java.io.StringWriter();
                writer.write('\ufeff');
                var csv =
                        new org.apache.commons.csv.CSVPrinter(
                                writer, org.apache.commons.csv.CSVFormat.DEFAULT);
                if (!rows.isEmpty()) {
                    var keys = new ArrayList<>(rows.getFirst().keySet());
                    csv.printRecord(keys);
                    for (var row : rows)
                        csv.printRecord(
                                keys.stream().map(k -> Operations.csvCell(row.get(k))).toList());
                }
                bytes = writer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else
                bytes =
                        (kind.equals("calls")
                                        ? operations.exportFor(q, i)
                                        : operations.reportExportFor(q, i))
                                .getBody();
            if (bytes == null || bytes.length > 12 * 1024 * 1024)
                throw new ApiError(422, "文件超过12MB，请缩小范围");
            docs.tx.run(
                    () -> {
                        docs.jdbc.update(
                                "UPDATE ops_export_job SET state='ready',content=?,access_json=?,error=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                                bytes,
                                accessSnapshot(i),
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
            docs.jdbc.update(
                    "UPDATE ops_export_job SET state='failed',content=NULL,error=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
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
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pagedRows(String kind, Map<String, String> q, Identity i) {
        var all = new ArrayList<Map<String, Object>>();
        var p = new LinkedHashMap<>(q);
        p.put("size", "200");
        for (int page = 1; page <= 50; page++) {
            if (!ScheduledWork.withinBudget()) throw new ApiError(422, "导出超时，请缩小范围");
            p.put("page", "" + page);
            var data =
                    WorkspaceDocuments.map(
                            kind.equals("tasks")
                                    ? workflows.tasksFor(p, i)
                                    : workflows.datasetFor(p, i));
            var rows = (List<Map<String, Object>>) data.get("rows");
            all.addAll(rows);
            if (all.size() >= ((Number) data.get("total")).longValue()) return all;
        }
        throw new ApiError(422, "导出超过一万条，请缩小范围");
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable
    String id, HttpServletRequest r)
            throws java.io.IOException {
        var i = actor(r);
        permitted(i);
        var row =
                docs.one(
                        "SELECT kind,state,content,access_json FROM ops_export_job WHERE tenant_id=? AND user_id=? AND id=?",
                        i.tenant(),
                        i.userId(),
                        id);
        if (row == null) throw new ApiError(404, "导出文件不存在");
        if (!"ready".equals(row.get("state"))) throw new ApiError(409, "文件尚未就绪");
        if (!Objects.equals(row.get("access_json"), accessSnapshot(i)))
            throw new ApiError(403, "数据权限已变化，请按当前权限重新导出");
        if ("calls-custom".equals(row.get("kind"))) {
            try (var zip =
                    new java.util.zip.ZipInputStream(
                            new java.io.ByteArrayInputStream((byte[]) row.get("content")))) {
                java.util.zip.ZipEntry entry;
                boolean validated = false;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.getName().equals("manifest.csv")) continue;
                    var ids =
                            new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                                    .lines()
                                    .filter(s -> !s.isBlank())
                                    .toList();
                    operations.authorizeExportCalls(i, ids);
                    validated = true;
                    break;
                }
                if (!validated) throw new ApiError(409, "导出索引缺失，请重新导出");
            }
        }
        if ("calls".equals(row.get("kind"))) {
            String content =
                    new String(
                            (byte[]) row.get("content"), java.nio.charset.StandardCharsets.UTF_8);
            try (var csv =
                    org.apache.commons.csv.CSVFormat.DEFAULT.parse(
                            new java.io.StringReader(content))) {
                var ids = new ArrayList<String>();
                boolean header = true;
                for (var record : csv) {
                    if (header) {
                        header = false;
                        continue;
                    }
                    ids.add(record.get(0));
                }
                operations.authorizeExportCalls(i, ids);
            }
        }
        return ResponseEntity.ok()
                .header(
                        "Content-Type",
                        "calls-custom".equals(row.get("kind"))
                                ? "application/zip"
                                : "text/csv;charset=UTF-8")
                .header(
                        "Content-Disposition",
                        "attachment; filename="
                                + (row.get("kind").equals("calls-custom")
                                        ? "custom-calls.zip"
                                        : row.get("kind").equals("calls")
                                                ? "filtered-calls.csv"
                                                : "operations-report.csv"))
                .body((byte[]) row.get("content"));
    }

    private byte[] customExport(Map<String, String> q, Identity i) throws Exception {
        byte[] source = operations.exportFor(q, i).getBody();
        if (source == null) throw new ApiError(422, "没有可导出数据");
        var rows = new LinkedHashMap<String, List<List<Object>>>();
        var ids = new ArrayList<String>();
        var phones = new HashSet<>(Arrays.asList(q.getOrDefault("export_phones", "").split(",")));
        boolean phonesOnly = "phones".equals(q.get("export_fields"));
        String dialogue = q.getOrDefault("export_dialogue", "both"),
                type = q.getOrDefault("export_type", "all");
        int perFile = Integer.parseInt(q.getOrDefault("export_count", "1000")),
                accepted = 0,
                bytes = 0;
        List<String> header;
        try (var parser =
                org.apache.commons.csv.CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .get()
                        .parse(
                                new java.io.StringReader(
                                        new String(source, java.nio.charset.StandardCharsets.UTF_8)
                                                .replaceFirst("^\\uFEFF", "")))) {
            header = new ArrayList<>(parser.getHeaderNames());
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
                                : new HashSet<>(Arrays.asList(q.get("export_types").split(",")));
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
                                                                        "task_options", Map.of()))
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
                        if (text.length() > 100000) throw new ApiError(422, "单通对话过长，请分开导出");
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
                                            com.duozhi.outbound.ai.service.FlowVariables.exported(
                                                    docs.json.object(
                                                            state.get("policy_json").toString()),
                                                    WorkspaceDocuments.map(
                                                            docs.json
                                                                    .object(
                                                                            state.get("data_json")
                                                                                    .toString())
                                                                    .getOrDefault(
                                                                            "flow_variables",
                                                                            Map.of())))));
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
                bytes += values.toString().length() * 3;
                if (bytes > 12 * 1024 * 1024) throw new ApiError(422, "解压内容超过12MB，请缩小范围");
                String group =
                        "count".equals(q.get("export_split"))
                                ? String.format("part-%03d", accepted / perFile + 1)
                                : record.get("created_at").substring(0, 10);
                rows.computeIfAbsent(group, k -> new ArrayList<>()).add(values);
                ids.add(id);
                accepted++;
            }
        }
        var target = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(target)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("manifest.csv"));
            zip.write(String.join("\n", ids).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            var columns = new ArrayList<String>(phonesOnly ? List.of("phone") : header);
            if (!phonesOnly && !dialogue.equals("none")) columns.add("dialogue");
            if (!phonesOnly) columns.add("variables");
            if (rows.isEmpty()) rows.put("empty", List.of());
            for (var group : rows.entrySet()) {
                var writer = new java.io.StringWriter();
                writer.write('\ufeff');
                try (var csv =
                        new org.apache.commons.csv.CSVPrinter(
                                writer, org.apache.commons.csv.CSVFormat.DEFAULT)) {
                    csv.printRecord(columns);
                    for (var row : group.getValue()) csv.printRecord(row);
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(group.getKey() + ".csv"));
                zip.write(writer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return target.toByteArray();
    }
}
