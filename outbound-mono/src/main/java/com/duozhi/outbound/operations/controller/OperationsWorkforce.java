package com.duozhi.outbound.operations.controller;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.call.service.CallLifecycle;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.operations.dao.OperationsStore;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class OperationsWorkforce {
    private final OperationsStore db;
    private final JsonSupport json;
    private final Store store;

    public OperationsWorkforce(OperationsStore db, JsonSupport json, Store store) {
        this.db = db;
        this.json = json;
        this.store = store;
    }

    public Map<String, Object> effective(long tenant, Object user) {
        var p = JsonSupport.map("tenantId", tenant, "userId", user, "name", "user:" + user);
        var teams = db.rows("workforceTeams", p);
        var result = Operations.workspace(Map.of());
        boolean personal = true;
        long timeout = 600;
        for (var team : teams) {
            var conf = json.object(team.get("settings_json").toString());
            result.put(
                    "after_call_seconds",
                    Math.max(
                            ((Number) result.get("after_call_seconds")).longValue(),
                            ((Number) conf.getOrDefault("after_call_seconds", 0)).longValue()));
            timeout =
                    Math.min(
                            timeout,
                            ((Number) conf.getOrDefault("queue_timeout_seconds", 60)).longValue());
            personal = personal && Boolean.TRUE.equals(conf.get("personal_override"));
        }
        if (!teams.isEmpty()) result.put("queue_timeout_seconds", timeout);
        var own = db.one("workspacePersonal", p);
        if (personal && own != null) result.putAll(json.object(own.get("config_json").toString()));
        result.put("personal_allowed", personal);
        var wrap = db.one("wrapupTime", p);
        Object until = wrap == null ? null : wrap.get("available_after");
        LocalDateTime at =
                until instanceof java.sql.Timestamp stamp
                        ? stamp.toLocalDateTime()
                        : until instanceof LocalDateTime local ? local : null;
        result.put(
                "remaining_seconds",
                at == null ? 0 : Math.max(0, Duration.between(Store.now(), at).getSeconds()));
        result.put("personal_revision", own == null ? 0 : own.get("revision"));
        result.put("configured", !teams.isEmpty() || own != null);
        return result;
    }

    public void release(long tenant, Object user) {
        long seconds = ((Number) effective(tenant, user).get("after_call_seconds")).longValue();
        if (seconds > 0) {
            db.write(
                    "wrapup",
                    JsonSupport.map(
                            "tenantId",
                            tenant,
                            "userId",
                            user,
                            "available_after",
                            Store.now().plusSeconds(seconds)));
            store.update(
                    "user",
                    tenant,
                    user,
                    JsonSupport.map("agent_status", "busy", "last_seen_at", Store.now()));
        } else
            store.update(
                    "user",
                    tenant,
                    user,
                    JsonSupport.map("agent_status", "ready", "last_seen_at", Store.now()));
    }

    public void ready(Identity i) {
        if (((Number) effective(i.tenant(), i.userId()).get("remaining_seconds")).longValue() > 0)
            throw new ApiError(409, "仍在通话后整理时间");
    }

    public int timeout(Map<String, Object> call, int fallback) {
        if (call.get("human_agent_id") == null) return fallback;
        var conf = effective(CallLifecycle.tenant(call), call.get("human_agent_id"));
        return Boolean.TRUE.equals(conf.get("configured"))
                ? ((Number) conf.get("queue_timeout_seconds")).intValue()
                : fallback;
    }

    public Object callbackOwner(Map<String, Object> call, Map<String, Object> policy) {
        var p =
                JsonSupport.map(
                        "tenantId",
                        CallLifecycle.tenant(call),
                        "contactId",
                        call.get("contact_id"),
                        "preferred",
                        call.get("human_agent_id"),
                        "teams",
                        policy.getOrDefault("handoff_team_ids", List.of()),
                        "allowed",
                        policy.getOrDefault("handoff_agent_ids", List.of()),
                        "skills",
                        policy.getOrDefault("handoff_skills", List.of()),
                        "skillsJson",
                        json.json(policy.getOrDefault("handoff_skills", List.of())));
        var owner = db.one("routingCallbackOwner", p);
        return owner == null ? null : owner.get("id");
    }
}
