package com.duozhi.outbound;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DialService {
    private final Store store;
    private final Transactions tx;
    private final Config config;
    private final Settings settings;
    private final CallPolicy policy;
    private final CallLifecycle lifecycle;
    private final TelephonyClient client;
    private final Outbox outbox;
    private final JsonSupport json;
    private final AgentMedia media;
    private final GatewayCluster gateways;
    private final CallbackInbox inbox;
    private final ConversationPolicy conversation;
    public DialService(Store store, Transactions tx, Config config, Settings settings, CallPolicy policy,
            CallLifecycle lifecycle, TelephonyClient client, Outbox outbox, JsonSupport json, AgentMedia media, GatewayCluster gateways, CallbackInbox inbox, ConversationPolicy conversation) {
        this.store = store; this.tx = tx; this.config = config; this.settings = settings; this.policy = policy;
        this.lifecycle = lifecycle; this.client = client; this.outbox = outbox; this.json = json; this.media = media; this.gateways = gateways; this.inbox = inbox; this.conversation = conversation;
    }
    public void available() {
        if (!config.flag("JAVA_DISPATCH_ENABLED", false)) throw new ApiError(503, "Java dispatch is not enabled");
    }
    public Map<String, Object> create(Identity identity, Map<String, Object> input) {
        available();
        Map<String, Object> call = tx.run(() -> {
            tx.platform(); tx.tenant(identity.tenant());
            Map<String, Object> campaign = input.get("campaign_id") == null ? null : store.require("campaign", identity.tenant(), input.get("campaign_id"));
            Map<String, Object> contact = input.get("contact_id") == null ? null : store.require("contact", identity.tenant(), input.get("contact_id"));
            Object rawPhone = input.get("phone");
            if ((rawPhone == null || rawPhone.toString().isEmpty()) && contact != null) rawPhone = contact.get("phone");
            String phone = Contacts.phone(rawPhone), reason = policy.check(identity.tenant(), phone, campaign != null, null);
            if (!reason.isEmpty()) throw new ApiError(403, reason);
            if (identity.user() != null && "agent".equals(identity.user().get("role")) && "human_only".equals(input.get("mode")) && config.flag("WEBRTC_ENABLED", false) && !media.registered(identity.tenant(), identity.userId()))
                throw new ApiError(409, "agent browser SIP endpoint is not registered");
            Map<String, Object> row = JsonSupport.map("tenant_id", identity.tenant(), "phone", phone, "mode", input.get("mode"), "campaign_id", input.get("campaign_id"),
                    "contact_id", input.get("contact_id"), "max_attempts", input.getOrDefault("max_attempts", 1), "status", "queued", "voice_ai_pipeline", "pending");
            if (identity.user() != null && "agent".equals(identity.user().get("role"))) row.put("human_agent_id", identity.userId());
            if (campaign != null && campaign.get("script_flow_version_id") != null) {
                Map<String, Object> flow = store.require("scriptflowversion", identity.tenant(), campaign.get("script_flow_version_id"));
                if ("published".equals(flow.get("status"))) {
                    row.put("script_flow_version_id", flow.get("id"));
                    for (Object raw : (List<?>)json.object(flow.get("graph_json").toString()).get("nodes")) {
                        Map<?, ?> node = (Map<?, ?>)raw;
                        if ("start".equals(node.get("type"))) { row.put("flow_node_key", node.get("id")); break; }
                    }
                }
            }
            return store.insert("callsession", row);
        });
        dispatch(call.get("id"));
        return store.require("callsession", identity.tenant(), call.get("id"));
    }
    private long active(String extra, Object... args) { return store.count("callsession", "WHERE status IN " + CallLifecycle.ACTIVE_SQL + extra, args); }
    private boolean productHours(Map<String, Object> call) {
        Map<String, Object> version = store.one("scenarioversion", "WHERE tenant_id=? AND (campaign_id=? OR campaign_id IS NULL) ORDER BY (campaign_id IS NOT NULL) DESC,id DESC LIMIT 1", CallLifecycle.tenant(call), call.get("campaign_id"));
        if (version == null) return true;
        Map<String, Object> value = json.object(version.get("policy_json").toString());
        ZonedDateTime local = ZonedDateTime.now(ZoneId.of(Objects.toString(value.get("timezone"), "Asia/Shanghai")));
        Collection<?> weekdays = (Collection<?>)value.getOrDefault("weekdays", Arrays.asList(0, 1, 2, 3, 4, 5, 6));
        Collection<?> holidays = (Collection<?>)value.getOrDefault("holidays", Collections.emptyList());
        return weekdays.contains(local.getDayOfWeek().getValue() - 1) && !holidays.contains(local.toLocalDate().toString())
                && local.getHour() >= JsonSupport.integer(value.get("start_hour"), 9) && local.getHour() < JsonSupport.integer(value.get("end_hour"), 20);
    }
    private Map<String, Object> claim(Object id) {
        return tx.run(() -> {
            tx.platform();
            Map<String, Object> initial = store.one("callsession", "WHERE id=?", store.encode("callsession", "id", id));
            if (initial == null) return null;
            long tenant = CallLifecycle.tenant(initial);
            Map<String, Object> tenantRow = tx.tenant(tenant), call = tx.call(id);
            if (!Boolean.TRUE.equals(tenantRow.get("enabled")) || !Values.set("created", "queued", "failed").contains(call.get("status"))) return null;
            if (CallLifecycle.number(call, "attempts") >= CallLifecycle.number(call, "max_attempts")) {
                store.update("callsession", tenant, id, JsonSupport.map("status", "failed", "next_attempt_at", null, "last_error", "reach max attempts")); lifecycle.completeCampaign(call); return null;
            }
            if (!productHours(call) || active("") >= Math.max(1, config.number("OUTBOUND_PLATFORM_MAX_CONCURRENT", 20))) return null;
            if (config.flag("CALLBACK_INBOX_ENABLED", false) && !Boolean.TRUE.equals(inbox.snapshot().get("ready"))) return null;
            String reason = policy.check(tenant, call.get("phone").toString(), call.get("campaign_id") != null, id);
            Map<String, Object> compliance = settings.get(tenant, "compliance");
            ZonedDateTime local = ZonedDateTime.now(ZoneId.of(Objects.toString(compliance.get("timezone"), "Asia/Shanghai")));
            Object day = store.encode("callsession", "started_at", LocalDateTime.ofInstant(local.toLocalDate().atStartOfDay(local.getZone()).toInstant(), ZoneOffset.UTC));
            long legacy = store.jdbc().queryForObject("SELECT COALESCE(SUM(attempts),0) FROM callsession WHERE tenant_id=? AND started_at>=?", Long.class, tenant, day);
            long recorded = store.jdbc().queryForObject("SELECT COUNT(*) FROM callevent e JOIN callsession c ON c.id=e.call_session_id WHERE c.tenant_id=? AND e.event_type='dial_attempt_claimed' AND e.created_at>=?", Long.class, tenant, day);
            int daily = Math.max(1, Math.min(config.number("OUTBOUND_DAILY_CALL_LIMIT", 10000), JsonSupport.integer(compliance.get("max_calls_per_day"), 10000)));
            if (Math.max(legacy, recorded) >= daily) reason = "tenant_daily_call_limit";
            if (!reason.isEmpty()) { store.update("callsession", tenant, id, JsonSupport.map("status", "failed", "next_attempt_at", null, "last_error", "precheck failed: " + reason)); lifecycle.completeCampaign(call); return null; }
            int tenantLimit = Math.min(Math.max(1, config.number("OUTBOUND_PLATFORM_MAX_CONCURRENT", 20)), Math.max(1, JsonSupport.integer(settings.get(tenant, "capacity").get("max_concurrent_calls"), 20)));
            List<Map<String, Object>> lines = store.list("telephonyline", "WHERE tenant_id=? AND enabled=? ORDER BY priority,created_at,id", tenant, true);
            Map<String, Object> selected = null; double best = Double.MAX_VALUE; int priority = Integer.MAX_VALUE, lineCapacity = 0;
            for (Map<String, Object> line : lines) {
                int capacity = Math.max(1, CallLifecycle.number(line, "max_concurrency")); lineCapacity += capacity;
                long occupied = active(" AND tenant_id=? AND telephony_line_id=?", tenant, line.get("id"));
                double load = occupied / (double)(capacity * Math.max(1, CallLifecycle.number(line, "weight")));
                int linePriority = CallLifecycle.number(line, "priority");
                if (occupied < capacity && (linePriority < priority || linePriority == priority && load < best)) { selected = line; priority = linePriority; best = load; }
            }
            if (!lines.isEmpty()) { if (selected == null) return null; tenantLimit = Math.min(tenantLimit, lineCapacity); }
            else if ("tenant".equals(config.get("TELEPHONY_PROVIDER", "mock"))) return null;
            if (active(" AND tenant_id=?", tenant) >= tenantLimit) return null;
            Map<String, Object> campaign = null;
            if (call.get("campaign_id") != null) {
                campaign = store.require("campaign", tenant, call.get("campaign_id"));
                if (!"running".equals(campaign.get("status")) || !Boolean.TRUE.equals(campaign.get("dispatch_enabled")) || active(" AND campaign_id=?", campaign.get("id")) >= Math.max(1, CallLifecycle.number(campaign, "concurrency"))) return null;
            }
            Map<String, Object> changes = JsonSupport.map("status", "dialing", "attempts", CallLifecycle.number(call, "attempts") + 1, "started_at", Store.now(), "finished_at", null,
                    "next_attempt_at", null, "last_error", null, "telephony_line_id", selected == null ? null : selected.get("id"), "gateway_node_id", null, "gateway_endpoint", null, "telephony_call_id", null, "ai_session_id", null);
            Map<String,Object> gateway;
            try { gateway = gateways.choose(tenant, selected == null ? null : selected.get("id"), 1.0); }
            catch (TaskWorker.Deferred wait) { return null; }
            if (gateway != null) { changes.put("gateway_node_id", gateway.get("id")); changes.put("gateway_endpoint", gateway.get("endpoint")); }
            String pipeline = Objects.toString(call.get("voice_ai_pipeline"), "pending");
            if (!Values.set("legacy", "pipecat").contains(pipeline)) {
                Map<String, Object> ai = settings.get(tenant, "ai");
                String override = campaign == null ? "inherit" : Objects.toString(campaign.get("voice_ai_pipeline"), "inherit");
                if (Values.set("legacy", "pipecat").contains(override)) pipeline = override;
                else {
                    long bucket = (java.nio.ByteBuffer.wrap(JsonSupport.digest(call.get("id").toString())).getInt() & 0xffffffffL) % 100;
                    pipeline = "pipecat".equals(ai.get("voice_ai_pipeline")) || bucket < Math.max(0, Math.min(100, JsonSupport.integer(ai.get("pipecat_canary_percent"), 0))) ? "pipecat" : "legacy";
                }
            }
            changes.put("voice_ai_pipeline", pipeline);
            Map<String, Object> probe = new LinkedHashMap<>(call); probe.putAll(changes); client.validate(probe);
            call = store.update("callsession", tenant, id, changes);
            conversation.state(call);
            store.insert("callevent", JsonSupport.map("call_session_id", id, "event_type", "dial_attempt_claimed", "source", "platform", "payload", json.json(JsonSupport.map("attempt", call.get("attempts")))));
            return call;
        });
    }
    public boolean dispatch(Object id) {
        available();
        Map<String, Object> call = claim(id);
        if (call == null) return false;
        long tenant = CallLifecycle.tenant(call); int attempt = CallLifecycle.number(call, "attempts");
        Map<String, Object> ai = settings.get(tenant, "ai"), compliance = settings.get(tenant, "compliance");
        String base = config.get("TELEPHONY_WEBHOOK_BASE", "http://localhost:8000").replaceAll("/+$", "");
        Map<String, Object> metadata = JsonSupport.map("tenant_id", tenant, "campaign_id", call.get("campaign_id"), "contact_id", call.get("contact_id"), "mode", call.get("mode"), "human_agent_id", call.get("human_agent_id"), "attempt", attempt, "gateway_node_id", call.get("gateway_node_id"));
        for (String kind : Arrays.asList("transcript", "speech", "media", "recording", "dtmf")) metadata.put(kind + "_webhook_url", base + "/api/v1/webhooks/telephony/" + kind);
        for (String key : Arrays.asList("asr_provider", "tts_provider", "voice", "language")) metadata.put(key, ai.get(key));
        metadata.put("recording_notice", compliance.get("recording_notice")); metadata.put("recording_notice_text", compliance.get("recording_notice_text"));
        Map<String,Object> scenario = tx.run(() -> { tx.platform(); return conversation.policy(conversation.state(call)); });
        scenario.keySet().removeIf(key -> key.startsWith("_"));
        metadata.put("scenario_policy", scenario);
        metadata.put("voice_ai_pipeline", call.get("voice_ai_pipeline"));
        if (call.get("telephony_line_id") != null) metadata.put("caller_id", store.require("telephonyline", tenant, call.get("telephony_line_id")).get("caller_id"));
        if (call.get("campaign_id") != null) {
            Map<String, Object> campaign = store.require("campaign", tenant, call.get("campaign_id"));
            metadata.put("recording_enabled", campaign.get("recording_enabled")); metadata.put("hangup_sms_enabled", campaign.get("hangup_sms_enabled"));
        }
        try {
            Map<String, Object> response = client.command(call, "dial", JsonSupport.map("phone", call.get("phone"), "caller_id", metadata.getOrDefault("caller_id", ""), "webhook_url", base + "/api/v1/webhooks/telephony/status", "metadata", metadata));
            tx.run(() -> {
                tx.platform(); Map<String, Object> latest = tx.call(id);
                if (latest != null && CallLifecycle.number(latest, "attempts") == attempt)
                    store.update("callsession", tenant, id, JsonSupport.map("telephony_call_id", response.get("provider_call_id"), "ai_session_id", response.get("provider_call_id"), "voice_ai_pipeline", metadata.get("voice_ai_pipeline")));
                return null;
            });
            if (client.mock(call)) {
                tx.run(() -> {
                    for (String state : Arrays.asList("answered", "completed")) {
                        Map<String, Object> task = outbox.enqueue(tenant, "mock_status", id, "mock:" + id + ":" + attempt + ":" + state,
                                JsonSupport.map("call_id", id, "kind", "status", "payload", JsonSupport.map("status", state, "attempt", attempt, "event_id", "mock:" + id + ":" + attempt + ":" + state)));
                        store.update("taskoutbox", tenant, task.get("id"), JsonSupport.map("available_at", Store.now().plusSeconds("answered".equals(state) ? 1 : 3)));
                    }
                    return null;
                });
            }
            return true;
        } catch (TelephonyClient.Failure e) {
            if (e.rejectedBeforeAdmission) {
                mutateAttempt(call, JsonSupport.map("status", "queued", "attempts", attempt - 1, "started_at", null, "telephony_call_id", null, "ai_session_id", null, "last_error", "gateway capacity/CPS deferred before admission")); return false;
            }
            if (!e.definitive()) { mutateAttempt(call, JsonSupport.map("last_error", "dial outcome unknown; awaiting PBX reconciliation")); return true; }
            lifecycle.status(JsonSupport.map("call_id", id, "kind", "status", "payload", JsonSupport.map("attempt", attempt, "event_id", "dial-rejected:" + id + ":" + attempt, "status", "failed", "hangup_reason", e.getMessage())));
            return true;
        }
    }
    private void mutateAttempt(Map<String, Object> call, Map<String, Object> changes) {
        tx.run(() -> { tx.platform(); Map<String, Object> latest = tx.call(call.get("id"));
            if (latest != null && "dialing".equals(latest.get("status")) && CallLifecycle.number(latest, "attempts") == CallLifecycle.number(call, "attempts"))
                store.update("callsession", CallLifecycle.tenant(call), call.get("id"), changes);
            return null;
        });
    }
    public Map<String, Object> retry(Map<String, Object> call) {
        available();
        tx.run(() -> { tx.platform(); Map<String, Object> latest = tx.call(call.get("id"));
            if (!CallLifecycle.TERMINAL.contains(latest.get("status"))) throw new ApiError(409, "call is not in terminal state");
            if (CallLifecycle.number(latest, "attempts") >= CallLifecycle.number(latest, "max_attempts")) throw new ApiError(409, "reach max attempts");
            store.update("callsession", CallLifecycle.tenant(call), call.get("id"), JsonSupport.map("status", "queued", "next_attempt_at", null, "last_error", null)); return null;
        });
        dispatch(call.get("id")); return store.require("callsession", CallLifecycle.tenant(call), call.get("id"));
    }
    public Map<String, Object> hangup(Map<String, Object> call, String reason) {
        if (CallLifecycle.TERMINAL.contains(call.get("status"))) return call;
        Map<String, Object> result;
        try { result = client.command(call, "hangup", JsonSupport.map("reason", reason)); }
        catch (TelephonyClient.Failure e) { throw new ApiError(e.status == 409 ? 409 : 502, "telephony hangup failed"); }
        if (!Boolean.TRUE.equals(result.get("ended"))) throw new ApiError(503, "hangup requested; PBX termination is not yet confirmed");
        lifecycle.status(JsonSupport.map("call_id", call.get("id"), "kind", "status", "payload", JsonSupport.map("status", "completed", "attempt", call.get("attempts"), "event_id", "manual-hangup:" + call.get("id") + ":" + call.get("attempts"), "hangup_reason", reason)));
        return store.require("callsession", CallLifecycle.tenant(call), call.get("id"));
    }
}
