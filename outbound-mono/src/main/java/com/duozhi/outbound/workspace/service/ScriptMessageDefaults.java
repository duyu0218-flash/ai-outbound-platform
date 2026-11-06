package com.duozhi.outbound.workspace.service;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.messaging.service.MessageContent;
import java.util.*;
import org.springframework.stereotype.Service;

/** Defaults are inert until an administrator confirms a task's rule and budget. */
@Service
public class ScriptMessageDefaults {
    private final WorkspaceDocuments docs;

    public ScriptMessageDefaults(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    public Map<String, Object> validate(long tenant, Object value) {
        var m = WorkspaceDocuments.map(value);
        if (m.isEmpty()) return Map.of();
        var rule = new LinkedHashMap<String, Object>();
        String form = null;
        for (String k : List.of("product", "template", "sender")) {
            String id = Objects.toString(m.get(k + "_id"), "");
            var r =
                    docs.one(
                            "SELECT payload FROM msg_resource WHERE tenant_id=? AND id=? AND kind=? AND state='APPROVED'",
                            tenant,
                            id,
                            k);
            if (r == null) throw new ApiError(422, "请选择本企业已审核的消息资源");
            if (!k.equals("sender")) {
                String current =
                        Objects.toString(
                                docs.json.object(r.get("payload").toString()).get("form"), "");
                if (!MessageContent.FORMS.contains(current)
                        || form != null && !form.equals(current))
                    throw new ApiError(422, "产品与模板的消息类型须一致");
                form = current;
            }
            rule.put(k + "_id", id);
        }
        String condition = Objects.toString(m.get("condition"), "completed");
        if (!Set.of("completed", "qualified_lead").contains(condition))
            throw new ApiError(422, "短信触发条件无效");
        long budget;
        try {
            budget = new java.math.BigDecimal(m.get("budget").toString()).longValueExact();
        } catch (Exception e) {
            throw new ApiError(422, "短信预算应为整数微元");
        }
        if (budget < 1 || budget > 1000000000000L) throw new ApiError(422, "短信预算越界");
        rule.put("condition", condition);
        rule.put("budget", budget);
        if (m.get("notify") instanceof Map<?, ?> n && !n.isEmpty()) {
            long target = WorkspaceDocuments.number(n.get("contact_id"));
            String template = Objects.toString(n.get("template_id"), "");
            if (target <= 0
                    || docs.one(
                                    "SELECT id FROM contact WHERE tenant_id=? AND id=? AND dnc=0 AND consent_state='CONSENTED'",
                                    tenant,
                                    target)
                            == null) throw new ApiError(422, "请选择本企业已授权的通知联系人");
            var tpl =
                    docs.one(
                            "SELECT payload FROM msg_resource WHERE tenant_id=? AND kind='template' AND state='APPROVED' AND id=?",
                            tenant,
                            template);
            if (tpl == null
                    || !form.equals(docs.json.object(tpl.get("payload").toString()).get("form")))
                throw new ApiError(422, "请选择已批准的通知短信模板");
            rule.put("notify", Map.of("contact_id", target, "template_id", template));
        }
        if (!(m.getOrDefault("notifications", List.of()) instanceof List<?> notifications)
                || notifications.size() > 100) throw new ApiError(422, "通知短信最多100条");
        var notices = new ArrayList<Map<String, Object>>();
        for (var raw : (List<?>) m.getOrDefault("notifications", List.of())) {
            var n = WorkspaceDocuments.map(raw);
            String title = WorkspaceDocuments.text(n, "title", 160, true),
                    template = WorkspaceDocuments.text(n, "template_id", 64, true);
            long target = WorkspaceDocuments.number(n.get("contact_id")),
                    owner = WorkspaceDocuments.number(n.getOrDefault("owner_id", 0));
            if (!(n.get("auto_send") instanceof Boolean)) throw new ApiError(422, "通知自动发送标记无效");
            if (docs.one(
                            "SELECT id FROM contact WHERE tenant_id=? AND id=? AND dnc=0 AND consent_state='CONSENTED'",
                            tenant,
                            target)
                    == null) throw new ApiError(422, "通知联系人不存在或未授权");
            var tpl =
                    docs.one(
                            "SELECT payload FROM msg_resource WHERE tenant_id=? AND id=? AND kind='template' AND state='APPROVED'",
                            tenant,
                            template);
            if (tpl == null
                    || !MessageContent.FORMS.contains(
                            docs.json.object(tpl.get("payload").toString()).get("form")))
                throw new ApiError(422, "通知模板须为已审核消息模板");
            if (owner > 0
                    && docs.one(
                                    "SELECT id FROM sys_user WHERE tenant_id=? AND id=? AND enabled=1",
                                    tenant,
                                    owner)
                            == null) throw new ApiError(422, "短信所属账号无效");
            var parameters = WorkspaceDocuments.map(n.getOrDefault("variables", Map.of()));
            if (parameters.size() > 32 || docs.json.json(parameters).length() > 8000)
                throw new ApiError(422, "通知参数过多");
            var assets = new LinkedHashMap<String, Object>();
            for (String asset : List.of("product", "sender")) {
                String selected = Objects.toString(n.get(asset + "_id"), "");
                if (selected.isBlank()) selected = rule.get(asset + "_id").toString();
                var resource =
                        docs.one(
                                "SELECT payload FROM msg_resource WHERE tenant_id=? AND id=? AND kind=? AND state='APPROVED'",
                                tenant,
                                selected,
                                asset);
                if (resource == null
                        || asset.equals("product")
                                && !docs.json
                                        .object(tpl.get("payload").toString())
                                        .get("form")
                                        .equals(
                                                docs.json
                                                        .object(resource.get("payload").toString())
                                                        .get("form")))
                    throw new ApiError(422, "通知通道或签名未审核");
                assets.put(asset + "_id", selected);
            }
            var notice =
                    new LinkedHashMap<String, Object>(
                            Map.of(
                                    "title",
                                    title,
                                    "template_id",
                                    template,
                                    "contact_id",
                                    target,
                                    "owner_id",
                                    owner,
                                    "auto_send",
                                    n.get("auto_send"),
                                    "variables",
                                    parameters));
            notice.putAll(assets);
            notices.add(notice);
        }
        rule.put("notifications", notices);
        return rule;
    }
}
