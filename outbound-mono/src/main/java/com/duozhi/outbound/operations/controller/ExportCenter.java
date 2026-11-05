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

    private String accessSnapshot(Identity i) {
        return docs.json.json(
                Map.of(
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
                "SELECT id,kind,state,error,created_at,updated_at FROM ops_export_job WHERE tenant_id=? AND user_id=? ORDER BY created_at DESC LIMIT 200",
                i.tenant(),
                i.userId());
    }

    @PostMapping
    public Object create(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        var i = actor(r);
        permitted(i);
        String kind = Objects.toString(b.get("kind"), "");
        if (!Set.of(
                        "calls",
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
        // Validate filters and enforce the same access predicate before putting work in the queue.
        if (kind.equals("calls")) {
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
            if (kind.startsWith("benchmark:")
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
                .header("Content-Type", "text/csv;charset=UTF-8")
                .header(
                        "Content-Disposition",
                        "attachment; filename="
                                + (row.get("kind").equals("calls")
                                        ? "filtered-calls.csv"
                                        : "operations-report.csv"))
                .body((byte[]) row.get("content"));
    }
}
