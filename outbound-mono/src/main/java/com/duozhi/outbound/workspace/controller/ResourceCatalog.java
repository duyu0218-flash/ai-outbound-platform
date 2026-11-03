package com.duozhi.outbound.workspace.controller;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.workspace.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace/catalog")
@AdminPermission("product:write")
public class ResourceCatalog {
    private static Map<String, Object> draft(Map<String, Object> d) {
        return map(d.get("draft"));
    }

    private final WorkspaceDocuments docs;
    private final Config config;

    public ResourceCatalog(WorkspaceDocuments docs, Config config) {
        this.docs = docs;
        this.config = config;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.admin("product:write");
        return i;
    }

    private String kind(String k) {
        if (!Set.of("skill", "connection").contains(k)) throw new ApiError(404, "目录不存在");
        return k;
    }

    @GetMapping("/{kind}")
    public Object list(@PathVariable
    String kind, HttpServletRequest r) {
        return docs.list(actor(r).tenant(), kind(kind), null).stream()
                .map(
                        row -> {
                            var d = draft(row);
                            row.put("enabled", !Boolean.FALSE.equals(d.get("enabled")));
                            if (kind.equals("connection")) {
                                String ref = Objects.toString(d.get("credential_ref"), "");
                                row.put(
                                        "credential_configured",
                                        !ref.isBlank()
                                                && !config.get("TELEPHONY_SECRET_" + ref, "")
                                                        .isBlank());
                            }
                            return row;
                        })
                .toList();
    }

    @PutMapping("/{kind}/{id}")
    public Object save(
            @PathVariable
            String kind,
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> body,
            HttpServletRequest r) {
        var i = actor(r);
        kind(kind);
        return docs.tx.serialized(
                () -> {
                    var raw = map(body.get("draft"));
                    String name = text(raw, "name", 80, true);
                    var old = docs.find(i.tenant(), kind, id);
                    if (docs.list(i.tenant(), kind, null).stream()
                            .anyMatch(
                                    row ->
                                            !row.get("object_id").equals(id)
                                                    && row.get("name").equals(name)))
                        throw new ApiError(409, "名称已存在");
                    if (old != null
                            && !old.get("name").equals(name)
                            && !references(i.tenant(), kind, id).isEmpty())
                        throw new ApiError(409, "资源已被引用，请新建替代项并调整引用后再改名");
                    var value =
                            JsonSupport.<String, Object>map(
                                    "name",
                                    name,
                                    "enabled",
                                    !Boolean.FALSE.equals(raw.get("enabled")));
                    if (kind.equals("skill")) {
                        String group = text(raw, "group", 16, true);
                        if (!Set.of("business", "region", "language").contains(group))
                            throw new ApiError(422, "技能分组无效");
                        value.put("group", group);
                        value.put("description", text(raw, "description", 1000, false));
                    } else {
                        String ref = text(raw, "credential_ref", 128, true);
                        if (!ref.matches("[A-Za-z0-9_]+"))
                            throw new ApiError(422, "凭据引用只能包含字母数字和下划线");
                        String url = text(raw, "gateway_url", 1000, true);
                        com.duozhi.outbound.system.service.Settings.endpoint(url, false);
                        value.put("credential_ref", ref);
                        value.put("gateway_url", url);
                    }
                    return docs.save(i, kind, id, "", value, number(body.get("expected_revision")));
                });
    }

    @GetMapping("/{kind}/{id}/references")
    public Object refs(@PathVariable
    String kind, @PathVariable
    String id, HttpServletRequest r) {
        return references(actor(r).tenant(), kind(kind), id);
    }

    public List<Map<String, Object>> references(long tenant, String kind, String id) {
        var doc = docs.require(tenant, kind, id);
        String name = doc.get("name").toString();
        var result = new ArrayList<Map<String, Object>>();
        if (kind.equals("skill")) {
            for (var team :
                    docs.jdbc.queryForList(
                            "SELECT id,name,enabled,settings_json FROM ops_team WHERE tenant_id=?",
                            tenant))
                if (((List<?>)
                                docs.json
                                        .object(team.get("settings_json").toString())
                                        .getOrDefault("routing_skills", List.of()))
                        .contains(name)) {
                    team.remove("settings_json");
                    team.put("kind", "team");
                    result.add(team);
                }
            for (var script : docs.list(tenant, "script", null)) {
                boolean draft =
                        ((List<?>)
                                        map(draft(script).get("policy"))
                                                .getOrDefault("handoff_skills", List.of()))
                                .contains(name);
                boolean pub =
                        script.get("published") != null
                                && ((List<?>)
                                                map(map(script.get("published")).get("policy"))
                                                        .getOrDefault("handoff_skills", List.of()))
                                        .contains(name);
                if (draft || pub)
                    result.add(
                            JsonSupport.map(
                                    "id",
                                    script.get("object_id"),
                                    "name",
                                    script.get("name"),
                                    "kind",
                                    "script",
                                    "draft",
                                    draft,
                                    "published",
                                    pub));
            }
        } else {
            String ref = Objects.toString(draft(doc).get("credential_ref"), "");
            result.addAll(
                    docs.jdbc.queryForList(
                            "SELECT id,name,enabled,'line' AS kind FROM telephonyline WHERE tenant_id=? AND credential_ref=?",
                            tenant,
                            ref));
        }
        return result;
    }

    @GetMapping("/skill-status")
    public Object status(HttpServletRequest r) {
        long tenant = actor(r).tenant();
        var result = new ArrayList<Map<String, Object>>();
        for (var team :
                docs.jdbc.queryForList(
                        "SELECT id,name,enabled FROM ops_team WHERE tenant_id=?", tenant)) {
            Long members =
                    docs.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ops_member m JOIN sys_user u ON u.id=m.user_id AND u.tenant_id=m.tenant_id WHERE m.tenant_id=? AND m.team_id=? AND u.enabled=TRUE",
                            Long.class,
                            tenant,
                            team.get("id"));
            team.put("enabled_members", members);
            team.put(
                    "reason",
                    !Boolean.TRUE.equals(team.get("enabled"))
                            ? "团队已停用"
                            : members == 0 ? "团队没有启用成员" : "已有成员；当前在线/忙碌/整理状态仍在实际转接时检查");
            result.add(team);
        }
        return result;
    }
}
