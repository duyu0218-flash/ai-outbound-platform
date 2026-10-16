package com.duozhi.outbound;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CallLifecycle {
    public static final Set<String> TERMINAL = Values.set("completed", "failed", "no_answer", "busy", "voicemail");
    public static final String ACTIVE_SQL = "('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN')";
    private static final List<String> ORDER = Arrays.asList("created", "queued", "dialing", "answered", "in_ai", "waiting_human", "in_human");
    private final Store store;
    private final Transactions tx;
    private final Outbox outbox;
    private final JsonSupport json;
    private final Config config;
    private final ConversationPolicy conversation;
    public CallLifecycle(Store store, Transactions tx, Outbox outbox, JsonSupport json, Config config, ConversationPolicy conversation) {
        this.store = store; this.tx = tx; this.outbox = outbox; this.json = json; this.config = config; this.conversation = conversation;
    }
    public static int number(Map<String, Object> row, String key) { return JsonSupport.integer(row.get(key), 0); }
    public static long tenant(Map<String, Object> call) { return ((Number)call.get("tenant_id")).longValue(); }
    private int rank(String value) { return TERMINAL.contains(value) ? 7 : "handoff_transferring".equals(value) ? 4 : ORDER.indexOf(value); }
    private String mapped(Object status) {
        String value = Objects.toString(status, "").trim().toLowerCase(Locale.ROOT);
        for (String kind : Arrays.asList("no_answer", "busy", "voicemail")) {
            String fallback = "no_answer".equals(kind) ? "[\"NOANSWER\",\"NO_ANSWER\"]" : "[\"" + kind.toUpperCase(Locale.ROOT) + "\"]";
            String raw = config.get(kind.toUpperCase(Locale.ROOT) + "_CODES", fallback);
            try {
                Object values = json.object("{\"values\":" + raw + "}").get("values");
                for (Object code : (List<?>)values) if (value.equals(code.toString().trim().toLowerCase(Locale.ROOT))) return kind;
            } catch (RuntimeException e) { throw new ApiError(503, "invalid provider status mapping"); }
        }
        if ("answering".equals(value)) return "answered";
        if ("human_connected".equals(value)) return "in_human";
        if ("ended".equals(value)) return "completed";
        return Values.set("dialing", "answered", "in_ai", "in_human", "completed", "failed", "busy", "no_answer", "voicemail").contains(value) ? value : null;
    }
    public boolean event(Map<String, Object> call, String type, String source, Map<String, Object> payload) {
        String encoded = json.canonical(payload, true);
        String explicit = Objects.toString(payload.get("event_id"), "");
        String key = JsonSupport.sha(call.get("id") + ":" + (explicit.isEmpty() ? type + ":" + source + ":" + encoded : source + ":event_id:" + explicit));
        Map<String, Object> existing = store.one("webhookeventingest", "WHERE call_session_id=? AND provider_event_key=?", store.encode("webhookeventingest", "call_session_id", call.get("id")), key);
        if (existing != null) {
            store.updateInternal("webhookeventingest", existing.get("id"), JsonSupport.map("repeat_count", number(existing, "repeat_count") + 1));
            return true;
        }
        store.insert("callevent", JsonSupport.map("call_session_id", call.get("id"), "event_type", type, "source", source, "payload", encoded));
        store.insert("webhookeventingest", JsonSupport.map("call_session_id", call.get("id"), "event_type", type, "source", source, "provider_event_key", key));
        return false;
    }
    public void completeCampaign(Map<String, Object> call) {
        Object campaignId = call.get("campaign_id");
        if (campaignId == null) return;
        Map<String, Object> campaign = store.one("campaign", "WHERE tenant_id=? AND id=?", tenant(call), campaignId);
        if (campaign == null || !"running".equals(campaign.get("status"))) return;
        long active = store.count("callsession", "WHERE tenant_id=? AND campaign_id=? AND (status IN " + ACTIVE_SQL + " OR status='QUEUED' OR next_attempt_at IS NOT NULL)", tenant(call), campaignId);
        if (active == 0) {
            boolean prepared = store.count("callsession", "WHERE tenant_id=? AND campaign_id=? AND status='CREATED'", tenant(call), campaignId) > 0;
            store.update("campaign", tenant(call), campaignId, JsonSupport.map("status", prepared ? "prepared" : "completed", "dispatch_enabled", false));
        }
    }
    public LocalDateTime retryAt(Map<String, Object> call, String status) {
        if (!Values.set("failed", "no_answer", "busy", "voicemail").contains(status) || call.get("campaign_id") == null || number(call, "attempts") >= number(call, "max_attempts")) return null;
        if (store.count("phonesuppression", "WHERE tenant_id=? AND phone=?", tenant(call), call.get("phone")) > 0) return null;
        Map<String, Object> campaign = store.one("campaign", "WHERE tenant_id=? AND id=?", tenant(call), call.get("campaign_id"));
        if (campaign == null || !Values.set("running", "paused").contains(campaign.get("status"))) return null;
        Map<String, Object> version = store.one("scenarioversion", "WHERE tenant_id=? AND (campaign_id=? OR campaign_id IS NULL) ORDER BY (campaign_id IS NOT NULL) DESC,id DESC LIMIT 1", tenant(call), call.get("campaign_id"));
        Map<String, Object> policy = version == null ? Collections.emptyMap() : json.object(version.get("policy_json").toString());
        Collection<?> causes = (Collection<?>)policy.getOrDefault("permanent_causes", Arrays.asList("UNALLOCATED_NUMBER", "INVALID_NUMBER_FORMAT", "NUMBER_CHANGED", "CALL_REJECTED"));
        for (Object cause : causes) if (Objects.toString(call.get("last_error"), "").contains(cause.toString())) return null;
        int delay = number(campaign, "failed".equals(status) ? "retry_interval_sec" : "attempt_interval_sec");
        if (version != null) {
            Map<?, ?> delays = (Map<?, ?>)policy.getOrDefault("retry_seconds", JsonSupport.map("busy", 1800, "no_answer", 3600, "voicemail", 86400));
            delay = JsonSupport.integer(delays.get(status), delay);
        }
        return Store.now().plusSeconds(Math.max(1, delay));
    }
    private void usage(Map<String, Object> call, String state, Map<String, Object> payload) {
        Map<String, Object> record = store.one("callusage", "WHERE call_session_id=? AND attempt=?", store.encode("callusage", "call_session_id", call.get("id")), call.get("attempts"));
        Map<String, Object> changes = record == null ? store.defaults("callusage") : new LinkedHashMap<>(record);
        LocalDateTime now = Store.now();
        if (Values.set("answered", "in_ai").contains(state) && changes.get("answered_at") == null && changes.get("ended_at") == null) changes.put("answered_at", now);
        if ("in_human".equals(state) || TERMINAL.contains(state)) {
            if (changes.get("ai_ended_at") == null) changes.put("ai_ended_at", now);
            if ("human_only".equals(call.get("mode"))) changes.put("ai_seconds", 0.0);
            else if (changes.get("answered_at") != null) changes.put("ai_seconds", seconds(changes.get("answered_at"), changes.get("ai_ended_at")));
        }
        if (TERMINAL.contains(state)) {
            if (changes.get("ended_at") == null) changes.put("ended_at", now);
            Object raw = payload.get("billable_duration_sec");
            if (raw instanceof Number && Double.isFinite(((Number)raw).doubleValue()) && ((Number)raw).doubleValue() >= 0) {
                changes.put("telephony_seconds", ((Number)raw).doubleValue()); changes.put("duration_source", "provider_cdr");
            } else if (changes.get("telephony_seconds") == null && changes.get("answered_at") != null) {
                changes.put("telephony_seconds", seconds(changes.get("answered_at"), changes.get("ended_at"))); changes.put("duration_source", "observed_estimate");
            } else if (changes.get("telephony_seconds") == null && Values.set("busy", "no_answer").contains(state)) {
                changes.put("telephony_seconds", 0.0); changes.put("ai_seconds", 0.0); changes.put("duration_source", "unanswered");
            }
        }
        if (record == null) { changes.put("tenant_id", tenant(call)); changes.put("call_session_id", call.get("id")); changes.put("attempt", call.get("attempts")); store.insert("callusage", changes); }
        else store.update("callusage", tenant(call), record.get("id"), changes);
    }
    private double seconds(Object start, Object end) {
        return Math.max(0, Duration.between(LocalDateTime.parse(start.toString()), LocalDateTime.parse(end.toString())).toMillis() / 1000.0);
    }
    private void releaseAgent(Map<String, Object> call) {
        Object agentId = call.get("human_agent_id");
        if (agentId == null) return;
        Map<String, Object> agent = store.one("user", "WHERE tenant_id=? AND id=?" + store.lock(), tenant(call), agentId);
        long active = store.count("callsession", "WHERE human_agent_id=? AND id<>? AND status IN " + ACTIVE_SQL, agentId, store.encode("callsession", "id", call.get("id")));
        if (agent != null && "busy".equals(agent.get("agent_status")) && active == 0) store.update("user", tenant(call), agentId, JsonSupport.map("agent_status", "ready", "last_seen_at", Store.now()));
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> status(Map<String, Object> body) {
        return tx.run(() -> {
            tx.platform();
            Map<String, Object> call = tx.call(body.get("call_id"));
            if (call == null) return JsonSupport.map("result", "ignore");
            Map<String, Object> payload = (Map<String, Object>)body.getOrDefault("payload", Collections.emptyMap());
            if (event(call, "status", "telephony", payload)) return JsonSupport.map("result", "ok");
            if (payload.get("attempt") != null && JsonSupport.integer(payload.get("attempt"), -1) != number(call, "attempts")) return JsonSupport.map("result", "ignored", "reason", "stale_attempt");
            String current = call.get("status").toString(), target = mapped(payload.get("status"));
            if ("human_unavailable".equals(Objects.toString(payload.get("status"), "").trim().toLowerCase(Locale.ROOT))) {
                Map<String, Object> handoff = store.one("handoffrequest", "WHERE tenant_id=? AND call_session_id=? AND state IN ('ACCEPTING','ACCEPTED') ORDER BY updated_at DESC LIMIT 1", tenant(call), store.encode("handoffrequest", "call_session_id", call.get("id")));
                if (!"handoff_transferring".equals(current) || handoff == null) return JsonSupport.map("result", "ignored", "reason", "inactive_handoff");
                store.update("handoffrequest", tenant(call), handoff.get("id"), JsonSupport.map("state", "waiting", "assigned_agent_id", null, "responded_at", null));
                Map<String, Object> agentCall = new LinkedHashMap<>(call);
                if (agentCall.get("human_agent_id") == null) agentCall.put("human_agent_id", handoff.get("assigned_agent_id"));
                releaseAgent(agentCall);
                store.update("callsession", tenant(call), call.get("id"), JsonSupport.map("status", "waiting_human", "human_agent_id", null, "last_error", payload.getOrDefault("hangup_reason", "agent did not answer")));
                Map<String,Object> waiting=store.require("callsession",tenant(call),call.get("id"));
                Map<String,Object> state=conversation.state(waiting);
                state.putAll(JsonSupport.map("deadline",null,"timer_kind",""));
                conversation.save(state,json.object(state.get("data_json").toString()));
                conversation.arm(waiting,"handoff");
                return JsonSupport.map("result", "ok", "requeued", true);
            }
            boolean applied = target != null && (current.equals(target) || !TERMINAL.contains(current) && rank(target) >= rank(current));
            Map<String, Object> changes = new LinkedHashMap<>();
            if (applied) changes.put("status", target);
            if (payload.get("telephony_call_id") != null) changes.put("telephony_call_id", payload.get("telephony_call_id").toString());
            if (payload.get("hangup_reason") != null && (applied || call.get("last_error") == null)) changes.put("last_error", payload.get("hangup_reason").toString());
            if (payload.get("summary") != null) changes.put("summary", Objects.toString(call.get("summary"), "") + "\n" + payload.get("summary"));
            if (applied && TERMINAL.contains(target)) {
                changes.put("finished_at", Store.now()); releaseAgent(call);
                for (Map<String, Object> handoff : store.list("handoffrequest", "WHERE call_session_id=? AND state IN ('WAITING','ACCEPTING','ACCEPTED')", store.encode("handoffrequest", "call_session_id", call.get("id"))))
                    store.update("handoffrequest", tenant(call), handoff.get("id"), JsonSupport.map("state", "accepted".equals(handoff.get("state")) ? "completed" : "expired", "completed_at", Store.now()));
            }
            Map<String, Object> updated = new LinkedHashMap<>(call); updated.putAll(changes);
            if (applied) changes.put("next_attempt_at", retryAt(updated, target));
            updated = store.update("callsession", tenant(call), call.get("id"), changes);
            if (applied) usage(updated, target, payload);
            if (target != null && TERMINAL.contains(target)) {
                completeCampaign(updated);
                outbox.enqueue(tenant(call), "call_analysis", call.get("id"), "analysis:" + call.get("id") + ":" + call.get("attempts"), JsonSupport.map("call_id", call.get("id"), "attempt", call.get("attempts")));
            }
            if (applied && "answered".equals(target) && !"human_only".equals(call.get("mode")))
                outbox.enqueue(tenant(call), "ai_turn", call.get("id"), "ai:" + call.get("id") + ":answered:" + call.get("attempts"), JsonSupport.map("call_id", call.get("id"), "attempt", call.get("attempts"), "transcript", body.getOrDefault("transcript", "")));
            if (applied) outbox.callback(updated, "call.status", JsonSupport.map("status", target, "hangup_reason", payload.get("hangup_reason")), "callback:status:" + call.get("id") + ":" + call.get("attempts") + ":" + target);
            return JsonSupport.map("result", "ok");
        });
    }
}
