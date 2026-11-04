package com.duozhi.outbound.workspace.controller;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.messaging.service.Messaging;
import com.duozhi.outbound.workspace.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace/tasks/{task}/sms-rule")
@AdminPermission("messaging:write")
public class HangupMessages {
    private final WorkspaceDocuments docs;
    private final Messaging messaging;
    private final Store store;
    @org.springframework.beans.factory.annotation.Autowired
    private ScriptMessageDefaults defaults;

    public HangupMessages(WorkspaceDocuments docs, Messaging messaging, Store store) {
        this.docs = docs;
        this.messaging = messaging;
        this.store = store;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.admin("messaging:write");
        return i;
    }

    @GetMapping
    public Object get(@PathVariable
    long task, HttpServletRequest r) {
        var i = actor(r);
        store.require("campaign", i.tenant(), task);
        var snap = docs.campaignSnapshot(i.tenant(), task);
        return snap == null
                ? Map.of()
                : map(snap.getOrDefault("sms_rule", snap.getOrDefault("sms_defaults", Map.of())));
    }

    @PostMapping
    public Object save(
            @PathVariable
            long task, @RequestBody
            Map<String, Object> body, HttpServletRequest r) {
        var i = actor(r);
        i.admin("campaigns:write");
        return docs.tx.serialized(
                () -> {
                    var campaign = store.require("campaign", i.tenant(), task);
                    if (!Set.of("draft", "stopped", "failed").contains(campaign.get("status")))
                        throw new ApiError(409, "仅未运行任务可修改挂机短信规则");
                    if (!Boolean.TRUE.equals(body.get("confirmed")))
                        throw new ApiError(422, "请确认短信模板、签名、触发条件和预算");
                    var snapshot = docs.campaignSnapshot(i.tenant(), task);
                    if (snapshot == null) throw new ApiError(409, "历史任务请先在任务工作区另建版本");
                    var payload =
                            new LinkedHashMap<String, Object>(defaults.validate(i.tenant(), body));
                    if (payload.isEmpty()) throw new ApiError(422, "请配置短信资源");
                    payload.put("call_campaign_id", task);
                    payload.put("workspace", true);
                    payload.put("variables", Map.of());
                    var old = map(snapshot.getOrDefault("sms_rule", Map.of()));
                    if (old.get("id") != null)
                        messaging.resourceState(i.tenant(), old.get("id").toString(), "DISABLED");
                    var rule =
                            map(
                                    messaging.createResource(
                                            i.tenant(),
                                            "call_rule",
                                            JsonSupport.map(
                                                    "name",
                                                    campaign.get("name") + "挂机短信",
                                                    "payload",
                                                    payload)));
                    messaging.resourceState(i.tenant(), rule.get("id").toString(), "APPROVED");
                    payload.put("id", rule.get("id"));
                    snapshot.put("sms_rule", payload);
                    snapshot.put("hangup_sms_enabled", true);
                    docs.jdbc.update(
                            "UPDATE platform_campaign_snapshot SET snapshot_json=? WHERE tenant_id=? AND campaign_id=?",
                            docs.json.json(snapshot),
                            i.tenant(),
                            task);
                    store.update("campaign", i.tenant(), task, Map.of("hangup_sms_enabled", true));
                    docs.recordTransition(i, "task_sms", String.valueOf(task), "configured");
                    return payload;
                });
    }
}
