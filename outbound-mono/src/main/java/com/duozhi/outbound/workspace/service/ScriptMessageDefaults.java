package com.duozhi.outbound.workspace.service;

import com.duozhi.outbound.common.exception.ApiError;
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
        for (String k : List.of("product", "template", "sender")) {
            String id = Objects.toString(m.get(k + "_id"), "");
            var r =
                    docs.one(
                            "SELECT payload FROM msg_resource WHERE tenant_id=? AND id=? AND kind=? AND state='APPROVED'",
                            tenant,
                            id,
                            k);
            if (r == null
                    || (!k.equals("sender")
                            && !"sms"
                                    .equals(
                                            docs.json
                                                    .object(r.get("payload").toString())
                                                    .get("form"))))
                throw new ApiError(422, "请选择本企业已审核的普通短信资源");
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
                    || !"sms".equals(docs.json.object(tpl.get("payload").toString()).get("form")))
                throw new ApiError(422, "请选择已批准的通知短信模板");
            rule.put("notify", Map.of("contact_id", target, "template_id", template));
        }
        return rule;
    }
}
