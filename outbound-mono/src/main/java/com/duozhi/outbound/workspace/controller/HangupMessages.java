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
        return snap == null ? Map.of() : map(snap.getOrDefault("sms_rule", Map.of()));
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
                    String condition = text(body, "condition", 32, true);
                    if (!Set.of("completed", "qualified_lead").contains(condition))
                        throw new ApiError(422, "不支持的触发条件");
                    long budget;
                    try {
                        budget =
                                new java.math.BigDecimal(body.get("budget").toString())
                                        .longValueExact();
                    } catch (Exception e) {
                        throw new ApiError(422, "预算须为整数微元");
                    }
                    if (budget < 1 || budget > 1_000_000_000_000L)
                        throw new ApiError(422, "预算超出范围");
                    var payload =
                            JsonSupport.<String, Object>map(
                                    "call_campaign_id",
                                    task,
                                    "workspace",
                                    true,
                                    "condition",
                                    condition,
                                    "budget",
                                    budget,
                                    "variables",
                                    Map.of());
                    for (String k : List.of("product_id", "template_id", "sender_id"))
                        payload.put(k, text(body, k, 64, true));
                    for (String kind : List.of("product", "template")) {
                        var asset =
                                docs.one(
                                        "SELECT payload FROM msg_resource WHERE tenant_id=? AND id=? AND kind=? AND state='APPROVED'",
                                        i.tenant(),
                                        payload.get(kind + "_id"),
                                        kind);
                        if (asset == null
                                || !"sms"
                                        .equals(
                                                docs.json
                                                        .object(asset.get("payload").toString())
                                                        .get("form")))
                            throw new ApiError(422, "请选择已审核的普通短信资源");
                    }
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
