package com.duozhi.outbound.operations.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.operations.service.OperationsAccess;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ops/wecom")
public class WecomWorkspace {
    private final WorkspaceDocuments docs;
    private final OperationsAccess access;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.operations.service.WecomAutomation automation;

    public WecomWorkspace(WorkspaceDocuments docs, OperationsAccess access) {
        this.docs = docs;
        this.access = access;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.requireUser();
        return i;
    }

    private void own(Identity i, long id) {
        if (!Objects.toString(i.userId()).equals("" + id)) access.admin(i);
        if (docs.one("SELECT id FROM sys_user WHERE tenant_id=? AND id=?", i.tenant(), id) == null)
            throw new ApiError(404, "账号不存在");
    }

    @GetMapping("/accounts")
    public Object accounts(HttpServletRequest r) {
        return automation.accounts(actor(r).tenant());
    }

    @PutMapping("/accounts/{id}")
    public Object account(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> b, HttpServletRequest r) {
        var i = actor(r);
        access.admin(i);
        return automation.account(i, id, b);
    }

    @DeleteMapping("/accounts/{id}")
    public Object removeAccount(
            @PathVariable
            String id, @RequestParam
            int revision, HttpServletRequest r) {
        var i = actor(r);
        access.admin(i);
        return docs.tx.serialized(
                () -> {
                    var d = docs.require(i.tenant(), "wecom_account", id);
                    if (WorkspaceDocuments.number(d.get("revision")) != revision)
                        throw new ApiError(409, "配置已被修改");
                    docs.jdbc.update(
                            "DELETE FROM platform_document WHERE tenant_id=? AND kind='wecom_account' AND object_id=?",
                            i.tenant(),
                            id);
                    return Map.of("deleted", true);
                });
    }

    @GetMapping("/providers")
    public Object providers(HttpServletRequest r) {
        var i = actor(r);
        access.admin(i);
        return automation.providers(i.tenant());
    }

    @PutMapping("/providers/{id}")
    public Object provider(
            @PathVariable
            String id, @RequestBody
            Map<String, Object> b, HttpServletRequest r) {
        var i = actor(r);
        access.admin(i);
        return automation.provider(i, id, b);
    }

    @GetMapping("/requests")
    public Object requests(@RequestParam
    Map<String, String> query, HttpServletRequest r) {
        var i = actor(r);
        access.admin(i);
        // Preserve the old configuration-client shape; the UI uses explicit pagination.
        if (query.isEmpty()) {
            long total =
                    docs.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM platform_document WHERE tenant_id=? AND kind='wecom_request'",
                            Long.class,
                            i.tenant());
            return org.springframework.http.ResponseEntity.ok()
                    .header("X-Total-Count", Long.toString(total))
                    .header("X-Result-Truncated", Boolean.toString(total > 1000))
                    .header("Link", "</api/v1/ops/wecom/requests?page=1&size=200>; rel=\"first\"")
                    .body(docs.list(i.tenant(), "wecom_request", null));
        }
        return docs.historyPage(i.tenant(), "wecom_request", query);
    }

    @GetMapping("/profiles/{id}")
    public Object get(@PathVariable
    long id, HttpServletRequest r) {
        var i = actor(r);
        own(i, id);
        var doc = docs.find(i.tenant(), "wecom_profile", "" + id);
        return doc == null
                ? Map.of(
                        "revision",
                        0,
                        "draft",
                        Map.of("name", "企微配置", "corp_id", "", "member_id", "", "contact_url", ""))
                : doc;
    }

    @PutMapping("/profiles/{id}")
    public Object save(
            @PathVariable
            long id, @RequestBody
            Map<String, Object> b, HttpServletRequest r) {
        var i = actor(r);
        own(i, id);
        var v = WorkspaceDocuments.map(b.get("config"));
        String corp = WorkspaceDocuments.text(v, "corp_id", 160, true),
                member = WorkspaceDocuments.text(v, "member_id", 160, true),
                link = WorkspaceDocuments.text(v, "contact_url", 1000, false);
        if (!link.isBlank())
            try {
                var u = URI.create(link);
                if (!"https".equals(u.getScheme())
                        || u.getUserInfo() != null
                        || !Set.of("work.weixin.qq.com", "wecom.qq.com", "weixin.qq.com")
                                .contains(u.getHost())) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new ApiError(422, "请填写企业微信提供的HTTPS联系链接");
            }
        return docs.save(
                i,
                "wecom_profile",
                "" + id,
                "",
                Map.of("name", "企微配置", "corp_id", corp, "member_id", member, "contact_url", link),
                WorkspaceDocuments.number(b.get("revision")));
    }

    @GetMapping("/statistics")
    public Object statistics(@RequestParam
    Map<String, String> q, HttpServletRequest r) {
        var i = actor(r);
        var users = access.users(i).stream().map(u -> u.get("id")).toList();
        String selected = q.getOrDefault("actor_id", "");
        if (!selected.isBlank()) {
            users = users.stream().filter(u -> u.toString().equals(selected)).toList();
            if (users.isEmpty()) throw new ApiError(403, "用户不在授权范围");
        }
        if (users.isEmpty()) return List.of();
        String
                from =
                        q.getOrDefault(
                                "from",
                                java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(30)
                                        + "T00:00:00Z"),
                until = q.getOrDefault("until", java.time.Instant.now().toString());
        var start = Operations.time(from);
        var end = Operations.time(until);
        if (start.isAfter(end) || java.time.Duration.between(start, end).toDays() > 366)
            throw new ApiError(422, "请选择一年内的有效日期");
        var args = new ArrayList<Object>();
        args.add(i.tenant());
        args.add(start);
        args.add(end);
        args.addAll(users);
        return docs.jdbc.queryForList(
                "SELECT DATE(TIMESTAMPADD(HOUR,8,h.created_at)) AS date,h.actor_id,MAX(u.full_name) AS agent,COUNT(DISTINCT h.contact_id) AS requested,COUNT(DISTINCT CASE WHEN h.detail_json->>'$.wecom_status'='added' THEN h.contact_id END) AS added,ROUND(100*COUNT(DISTINCT CASE WHEN h.detail_json->>'$.wecom_status'='added' THEN h.contact_id END)/NULLIF(COUNT(DISTINCT h.contact_id),0),2) AS approval_rate FROM ops_history h JOIN sys_user u ON u.tenant_id=h.tenant_id AND u.id=h.actor_id WHERE h.tenant_id=? AND h.action='wecom' AND h.created_at BETWEEN ? AND ? AND h.actor_id IN ("
                        + String.join(",", Collections.nCopies(users.size(), "?"))
                        + ") GROUP BY date,h.actor_id ORDER BY date DESC",
                args.toArray());
    }
}
