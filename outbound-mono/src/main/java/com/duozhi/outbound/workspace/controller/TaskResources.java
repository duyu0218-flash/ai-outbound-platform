package com.duozhi.outbound.workspace.controller;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.customer.controller.ContactFiles;
import com.duozhi.outbound.workspace.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace/task-resources")
@AdminPermission("campaigns:write")
public class TaskResources {
    private final WorkspaceDocuments docs;
    private final Store store;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.operations.service.TaskStrategies strategies;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.auth.service.AccountSecurity accountSecurity;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.operations.service.OperationsAccess access;

    @GetMapping("/strategies")
    public Object strategies(HttpServletRequest r) {
        long tenant = tenant(r);
        return Map.of(
                "frequency",
                strategies.list(tenant, "frequency"),
                "evaluation",
                strategies.list(tenant, "evaluation"),
                "filters",
                docs.list(tenant, "filter_group", null),
                "users",
                docs.jdbc.queryForList(
                        "SELECT id,username,full_name FROM sys_user WHERE tenant_id=? AND enabled=1",
                        tenant));
    }

    public TaskResources(WorkspaceDocuments docs, Store store) {
        this.docs = docs;
        this.store = store;
    }

    private long tenant(HttpServletRequest r) {
        var i = Identity.from(r);
        i.admin("campaigns:write");
        return i.tenant();
    }

    @GetMapping("/selectable-lines")
    public Object selectableLines(HttpServletRequest request) {
        return docs.jdbc.queryForList(
                "SELECT id,name,enabled,caller_id FROM telephonyline WHERE tenant_id=? AND enabled=1 ORDER BY id",
                tenant(request));
    }

    @GetMapping("/line/{id}/impact")
    public Object impact(@PathVariable
    long id, HttpServletRequest r) {
        long tenant = tenant(r);
        store.require("telephonyline", tenant, id);
        var refs = new ArrayList<Map<String, Object>>();
        for (var row :
                docs.jdbc.queryForList(
                        "SELECT s.snapshot_json,c.id,c.name,c.status FROM platform_campaign_snapshot s JOIN campaign c ON c.tenant_id=s.tenant_id AND c.id=s.campaign_id WHERE s.tenant_id=? AND c.status<>'deleted'",
                        tenant)) {
            var snapshot = docs.json.object(row.remove("snapshot_json").toString());
            if (((List<?>) snapshot.getOrDefault("line_ids", List.of()))
                    .stream().anyMatch(v -> v.toString().equals(String.valueOf(id)))) refs.add(row);
        }
        return JsonSupport.map(
                "tasks",
                refs,
                "active_calls",
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM callsession WHERE tenant_id=? AND telephony_line_id=? AND status IN ('DIALING','ANSWERED','IN_AI','IN_HUMAN','WAITING_HUMAN','HANDOFF_TRANSFERRING')",
                        Long.class,
                        tenant,
                        id),
                "effect",
                "停用后不再分配新通话；已开始的通话沿用其拨号快照。只绑定此线路的任务会等待可用容量，不会自动切换到未选择的线路。");
    }

    @GetMapping("/tasks/{id}/members")
    public Object members(
            @PathVariable
            long id,
            @RequestParam(defaultValue = "1")
            int page,
            @RequestParam(defaultValue = "50")
            int size,
            HttpServletRequest r) {
        long tenant = tenant(r);
        store.require("campaign", tenant, id);
        if (page < 1 || size < 1 || size > 100) throw new ApiError(422, "分页参数不正确");
        return JsonSupport.map(
                "total",
                docs.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM platform_audience_member WHERE tenant_id=? AND snapshot_id=?",
                        Long.class,
                        tenant,
                        "call:" + id),
                "rows",
                docs
                        .jdbc
                        .queryForList(
                                "SELECT contact_id,variables_json FROM platform_audience_member WHERE tenant_id=? AND snapshot_id=? ORDER BY contact_id LIMIT ? OFFSET ?",
                                tenant,
                                "call:" + id,
                                size,
                                (long) (page - 1) * size)
                        .stream()
                        .map(
                                row -> {
                                    row.put(
                                            "variables",
                                            docs.json.object(
                                                    row.remove("variables_json").toString()));
                                    return row;
                                })
                        .toList());
    }

    @GetMapping("/tasks/{id}/export")
    public ResponseEntity<
                    org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            export(@PathVariable
            long id, HttpServletRequest r) {
        long tenant = tenant(r);
        var actor = Identity.from(r);
        actor.requireUser();
        if (!access.flag(actor, "can_export")) throw new ApiError(403, "没有导出权限");
        accountSecurity.export(actor, Objects.toString(r.getHeader("X-Export-Verification"), ""));
        var snapshot = docs.campaignSnapshot(tenant, id);
        boolean masked =
                !access.flag(actor, "can_unmask")
                        || snapshot != null
                                && Boolean.TRUE.equals(
                                        WorkspaceDocuments.map(
                                                        snapshot.getOrDefault(
                                                                "task_options", Map.of()))
                                                .get("mask_numbers"));
        store.require("campaign", tenant, id);
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody stream =
                out -> {
                    var writer =
                            new java.io.OutputStreamWriter(
                                    out, java.nio.charset.StandardCharsets.UTF_8);
                    writer.write('\ufeff');
                    var csv =
                            new org.apache.commons.csv.CSVPrinter(
                                    writer, org.apache.commons.csv.CSVFormat.DEFAULT);
                    csv.printRecord("客户ID", "号码快照", "姓名快照", "业务变量");
                    long after = 0;
                    while (true) {
                        var rows =
                                docs.jdbc.queryForList(
                                        "SELECT contact_id,variables_json FROM platform_audience_member WHERE tenant_id=? AND snapshot_id=? AND contact_id>? ORDER BY contact_id LIMIT 500",
                                        tenant,
                                        "call:" + id,
                                        after);
                        if (rows.isEmpty()) break;
                        for (var row : rows) {
                            var v = docs.json.object(row.get("variables_json").toString());
                            if (masked && v.get("phone") != null)
                                v.put(
                                        "phone",
                                        v.get("phone")
                                                .toString()
                                                .replaceAll("(?<=.{3}).(?=.{4})", "*"));
                            csv.printRecord(
                                    row.get("contact_id"),
                                    ContactFiles.safe(v.get("phone")),
                                    ContactFiles.safe(v.get("name")),
                                    ContactFiles.safe(v.toString()));
                            after = ((Number) row.get("contact_id")).longValue();
                        }
                        csv.flush();
                    }
                };
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv;charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=task-snapshot.csv")
                .body(stream);
    }
}
