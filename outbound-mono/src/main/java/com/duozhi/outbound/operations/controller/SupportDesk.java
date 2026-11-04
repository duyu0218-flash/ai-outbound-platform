package com.duozhi.outbound.operations.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ops/support")
public class SupportDesk {
    private final WorkspaceDocuments docs;

    public SupportDesk(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.requireUser();
        return i;
    }

    @GetMapping
    public Object list(HttpServletRequest r) {
        var i = actor(r);
        return docs.jdbc.queryForList(
                "SELECT object_id,name,revision,updated_at,JSON_UNQUOTE(JSON_EXTRACT(draft_json,'$.body')) AS body,JSON_UNQUOTE(JSON_EXTRACT(draft_json,'$.reply')) AS reply FROM platform_document WHERE tenant_id=? AND kind='support_ticket' AND (? OR parent_id=?) ORDER BY updated_at DESC LIMIT 100",
                i.tenant(),
                "admin".equals(i.user().get("role")),
                i.userId().toString());
    }

    private String text(Map<String, Object> b, String key, int max) {
        String v = Objects.toString(b.get(key), "").trim();
        if (v.isEmpty() || v.length() > max) throw new ApiError(422, "请填写有效的标题或内容");
        return v;
    }

    @PostMapping
    public Object create(@RequestBody
    Map<String, Object> b, HttpServletRequest r) {
        var i = actor(r);
        return docs.save(
                i,
                "support_ticket",
                UUID.randomUUID().toString(),
                i.userId().toString(),
                Map.of("name", text(b, "title", 120), "body", text(b, "body", 2000), "reply", ""),
                0);
    }

    @PutMapping("/{id}")
    public Object reply(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> b, HttpServletRequest r) {
        var i = actor(r);
        i.admin(null);
        var doc = docs.require(i.tenant(), "support_ticket", id);
        var draft = new LinkedHashMap<>(WorkspaceDocuments.map(doc.get("draft")));
        draft.put("reply", text(b, "reply", 2000));
        return docs.save(
                i,
                "support_ticket",
                id,
                doc.get("parent_id").toString(),
                draft,
                WorkspaceDocuments.number(b.get("revision")));
    }
}
