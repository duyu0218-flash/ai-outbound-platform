package com.duozhi.outbound.workspace.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ScriptShares {
    private final WorkspaceDocuments docs;

    public ScriptShares(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    private Identity editor(HttpServletRequest request) {
        var i = Identity.from(request);
        i.requireUser();
        i.admin("script-templates:write");
        return i;
    }

    @GetMapping("/api/v1/workspace/scripts/{id}/share")
    public Object settings(@PathVariable
    String id, HttpServletRequest request) {
        var i = editor(request);
        docs.require(i.tenant(), "script", id);
        var doc = docs.find(i.tenant(), "script_share", id);
        if (doc == null)
            return Map.of(
                    "revision", 0, "draft", Map.of("usernames", List.of()), "assigned_count", 0);
        var output = new LinkedHashMap<>(doc);
        var cursor = docs.find(i.tenant(), "share_cursor", id);
        output.put(
                "assigned_count",
                cursor == null
                        ? 0
                        : com.duozhi.outbound.workspace.service.WorkspaceDocuments.map(
                                        com.duozhi.outbound.workspace.service.WorkspaceDocuments
                                                .map(cursor.get("draft"))
                                                .get("counts"))
                                .values()
                                .stream()
                                .mapToLong(v -> ((Number) v).longValue())
                                .sum());
        return output;
    }

    @PutMapping("/api/v1/workspace/scripts/{id}/share")
    public Object save(
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest request) {
        var i = editor(request);
        var script = docs.require(i.tenant(), "script", id);
        if (!(body.get("usernames") instanceof List<?> names) || names.size() > 100)
            throw new ApiError(422, "最多分享给100个本租户账号");
        if (!names.isEmpty()
                && (script.get("published") == null || "disabled".equals(script.get("state"))))
            throw new ApiError(409, "请先发布话术");
        Set<String> targets = new LinkedHashSet<>();
        List<Integer> ids = new ArrayList<>();
        for (Object name : names) {
            if (!(name instanceof String value) || value.isBlank() || value.length() > 160)
                throw new ApiError(422, "账号格式无效");
            if (!targets.add(value.trim())) continue;
            var user =
                    docs.one(
                            "SELECT id FROM sys_user WHERE tenant_id=? AND username=? AND enabled=1",
                            i.tenant(),
                            value.trim());
            if (user == null) throw new ApiError(422, "接收人不是本租户启用账号");
            ids.add(((Number) user.get("id")).intValue());
        }
        String reference = Objects.toString(body.get("reference"), "grade");
        if (!Set.of("grade", "intent", "result_code").contains(reference))
            throw new ApiError(422, "任务分配参考无效");
        if (!(body.getOrDefault("intention_groups", List.of()) instanceof List<?> groups)
                || groups.size() > 30) throw new ApiError(422, "最多30个意向分组");
        var clean = new ArrayList<Map<String, Object>>();
        for (var raw : (List<?>) body.getOrDefault("intention_groups", List.of())) {
            var group = WorkspaceDocuments.map(raw);
            String name = WorkspaceDocuments.text(group, "name", 100, true);
            if (!(group.get("values") instanceof List<?> values)
                    || values.size() > 50
                    || values.stream()
                            .anyMatch(v -> !(v instanceof String) || v.toString().length() > 100))
                throw new ApiError(422, "意向分组条件无效");
            if (!(group.get("user_ids") instanceof List<?> members)
                    || members.isEmpty()
                    || members.stream()
                            .anyMatch(
                                    v ->
                                            ids.stream()
                                                    .noneMatch(
                                                            x ->
                                                                    x.toString()
                                                                            .equals(v.toString()))))
                throw new ApiError(422, "分组接收人须在分享账号中");
            clean.add(
                    Map.of(
                            "name",
                            name,
                            "values",
                            values,
                            "user_ids",
                            members.stream().distinct().toList()));
        }
        boolean apply = Boolean.TRUE.equals(body.get("apply_existing"));
        return docs.tx.serialized(
                () -> {
                    var config =
                            JsonSupport.map(
                                    "name",
                                    script.get("name"),
                                    "version",
                                    script.get("published_version"),
                                    "usernames",
                                    targets,
                                    "ids",
                                    ids,
                                    "reference",
                                    reference,
                                    "type",
                                    "script",
                                    "intention_groups",
                                    clean);
                    var saved =
                            docs.save(
                                    i,
                                    "script_share",
                                    id,
                                    "",
                                    config,
                                    WorkspaceDocuments.number(body.get("revision")));
                    config.put("revision", saved.get("revision"));
                    docs.jdbc.update(
                            "DELETE FROM platform_document WHERE tenant_id=? AND kind='share_cursor' AND object_id=?",
                            i.tenant(),
                            id);
                    if (apply) {
                        for (var task :
                                docs.jdbc.queryForList(
                                        "SELECT campaign_id,snapshot_json FROM platform_campaign_snapshot WHERE tenant_id=? AND snapshot_json->>'$.script_id'=?",
                                        i.tenant(),
                                        id)) {
                            var snapshot = docs.json.object(task.get("snapshot_json").toString());
                            snapshot.put("share_policy", config);
                            docs.jdbc.update(
                                    "UPDATE platform_campaign_snapshot SET snapshot_json=? WHERE tenant_id=? AND campaign_id=?",
                                    docs.json.json(snapshot),
                                    i.tenant(),
                                    task.get("campaign_id"));
                        }
                    }
                    return saved;
                });
    }

    @GetMapping("/api/v1/ops/shared-scripts")
    public Object received(HttpServletRequest request) {
        var i = Identity.from(request);
        i.requireUser();
        return docs.jdbc.queryForList(
                "SELECT s.object_id,s.name,p.version,JSON_UNQUOTE(JSON_EXTRACT(p.content_json,'$.compiled_content')) AS content FROM platform_document s JOIN platform_document d ON d.tenant_id=s.tenant_id AND d.kind='script' AND d.object_id=s.object_id AND d.state NOT IN ('disabled','archived') JOIN platform_publication p ON p.tenant_id=s.tenant_id AND p.kind='script' AND p.object_id=s.object_id AND p.version=CAST(s.draft_json->>'$.version' AS UNSIGNED) WHERE s.kind='script_share' AND s.tenant_id=? AND JSON_CONTAINS(s.draft_json,CAST(? AS JSON),'$.ids') ORDER BY s.updated_at DESC,s.object_id",
                i.tenant(),
                i.userId().toString());
    }
}
