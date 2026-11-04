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
        return doc == null ? Map.of("revision", 0, "draft", Map.of("usernames", List.of())) : doc;
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
        return docs.save(
                i,
                "script_share",
                id,
                "",
                JsonSupport.map(
                        "name",
                        script.get("name"),
                        "version",
                        script.get("published_version"),
                        "usernames",
                        targets,
                        "ids",
                        ids),
                WorkspaceDocuments.number(body.get("revision")));
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
