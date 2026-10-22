package com.duozhi.outbound.ai.service;

import com.duozhi.outbound.agent.controller.Handoffs;
import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.call.controller.VoiceCallbacks;
import com.duozhi.outbound.call.service.CallLifecycle;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.dial.service.DialService;
import com.duozhi.outbound.integration.service.TelephonyClient;
import com.duozhi.outbound.job.service.Outbox;
import com.duozhi.outbound.job.worker.TaskWorker;
import com.duozhi.outbound.script.controller.ScriptFlows;
import com.duozhi.outbound.system.service.Settings;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.*;

/** Coordinates rules, flow canvas and embedded Spring AI while fencing all business effects. */
@RestController
public class AiRuntime {
    private final Store store;
    private final Transactions tx;
    private final JsonSupport json;
    private final Config config;
    private final Settings settings;
    private final ConversationPolicy product;
    private final ScriptFlows flows;
    private final TelephonyClient client;
    private final EmbeddedAiTransport embeddedAgent;
    private final VoiceCallbacks voice;
    private final CallLifecycle lifecycle;
    private final Outbox outbox;
    private final Handoffs handoffs;
    private final DialService dial;

    public AiRuntime(
            Store store,
            Transactions tx,
            JsonSupport json,
            Config config,
            Settings settings,
            ConversationPolicy product,
            ScriptFlows flows,
            TelephonyClient client,
            VoiceCallbacks voice,
            CallLifecycle lifecycle,
            Outbox outbox,
            Handoffs handoffs,
            DialService dial,
            EmbeddedAiTransport embeddedAgent) {
        this.embeddedAgent = embeddedAgent;
        this.store = store;
        this.tx = tx;
        this.json = json;
        this.config = config;
        this.settings = settings;
        this.product = product;
        this.flows = flows;
        this.client = client;
        this.voice = voice;
        this.lifecycle = lifecycle;
        this.outbox = outbox;
        this.handoffs = handoffs;
        this.dial = dial;
    }

    private Map<String, Object> current(Map<String, Object> payload, boolean lock) {
        TaskWorker.permitted();
        var parameters =
                JsonSupport.map(
                        "id",
                        store.encode("callsession", "id", payload.get("call_id")),
                        "attempt",
                        JsonSupport.integer(payload.get("attempt"), -1),
                        "turnSequence",
                        payload.get("turn_sequence") == null
                                ? null
                                : JsonSupport.integer(payload.get("turn_sequence"), -1),
                        "generation",
                        payload.get("_generation") == null
                                ? null
                                : JsonSupport.integer(payload.get("_generation"), -1));
        String method = lock ? "currentAiLock" : "currentAiRead";
        // This hint comes only from a claimed durable task, never from an HTTP field.
        // Retain the original cross-tenant locator fallback for stale/corrupt task hints.
        Long hint = TaskWorker.tenantHint();
        if (hint != null) {
            parameters.put("tenantId", hint);
            var row = store.one("callsession", method, parameters);
            if (row != null) return row;
        }
        for (var tenant : store.query("tenant", "allForMaintenance", Map.of())) {
            parameters.put("tenantId", tenant.get("id"));
            var row = store.one("callsession", method, parameters);
            if (row != null) return row;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> flow(
            Map<String, Object> call, Map<String, Object> state, String transcript) {
        if (call.get("script_flow_version_id") == null) return null;
        Map<String, Object> version =
                store.require(
                        "scriptflowversion",
                        CallLifecycle.tenant(call),
                        call.get("script_flow_version_id"));
        if (!"published".equals(version.get("status")))
            throw new ApiError(409, "bound script flow version is unavailable");
        Map<String, Object> graph = json.object(version.get("graph_json").toString()),
                data = json.object(state.get("data_json").toString());
        Map<String, Object> cached =
                data.get("flow_turn") instanceof Map
                        ? (Map<String, Object>) data.get("flow_turn")
                        : Collections.emptyMap();
        if (data.get("sequence") != null
                && Objects.equals(cached.get("sequence"), data.get("sequence"))
                && transcript.equals(cached.get("transcript"))) {
            call.put("flow_node_key", cached.get("node"));
            return (Map<String, Object>) cached.get("result");
        }
        Map<String, Object> d =
                flows.simulate(
                        graph,
                        call.get("flow_node_key"),
                        transcript,
                        transcript.trim().isEmpty(),
                        (Map<String, Object>) data.get("flow_variables"));
        Map<Object, Map<String, Object>> nodes = new HashMap<>();
        for (Map<String, Object> n : (List<Map<String, Object>>) graph.get("nodes"))
            nodes.put(n.get("id"), n);
        Map<String, Object> source = nodes.get(d.get("current_node_id")),
                target = nodes.get(d.get("next_node_id"));
        if (!transcript.trim().isEmpty()
                && source != null
                && Values.set("start", "message").contains(source.get("type"))
                && target != null
                && "listen".equals(target.get("type")))
            d =
                    flows.simulate(
                            graph,
                            target.get("id"),
                            transcript,
                            false,
                            (Map<String, Object>) d.get("variables"));
        Set<Object> visited = new HashSet<>();
        while (d.get("next_node_id") != null
                && Values.set("set", "branch")
                        .contains(nodes.get(d.get("next_node_id")).get("type"))) {
            if (!visited.add(d.get("next_node_id")))
                throw new ApiError(409, "automatic flow cycle");
            d =
                    flows.simulate(
                            graph,
                            d.get("next_node_id"),
                            transcript,
                            false,
                            (Map<String, Object>) d.get("variables"));
        }
        data.put("flow_variables", d.get("variables"));
        Object node =
                d.get("next_node_id") == null ? d.get("current_node_id") : d.get("next_node_id");
        call.put("flow_node_key", node);
        String action = d.get("action").toString();
        if (Values.set("wait", "listen", "continue").contains(action)) action = "continue";
        Map<String, Object> result =
                ConversationPolicy.reply(Objects.toString(d.get("prompt"), ""), action);
        data.put(
                "flow_turn",
                JsonSupport.map(
                        "sequence",
                        data.get("sequence"),
                        "transcript",
                        transcript,
                        "node",
                        node,
                        "result",
                        result));
        product.save(state, data);
        return result;
    }

    private Map<String, Object> validated(Map<String, Object> result) {
        String action = Objects.toString(result.get("action"), "");
        if (!Values.set("continue", "speak", "handoff", "hangup").contains(action))
            throw new ApiError(502, "invalid AI action");
        if (result.get("tts_text") != null && !(result.get("tts_text") instanceof String)
                || result.get("handoff_to_human") != null
                        && !(result.get("handoff_to_human") instanceof Boolean))
            throw new ApiError(502, "invalid AI response");
        return result;
    }

    @SuppressWarnings("unchecked")
    public void turn(Map<String, Object> payload) {
        try {
            runTurn(payload);
        } catch (RuntimeException failure) {
            TaskWorker.permitted();
            failedTurn(payload, failure);
        }
    }

    private void failedTurn(Map<String, Object> payload, RuntimeException failure) {
        Map<String, Object> snapshot =
                tx.serialized(
                        () -> {
                            Map<String, Object> call = current(payload, true);
                            if (call == null) return null;
                            Map<String, Object> state = product.state(call),
                                    data = json.object(state.get("data_json").toString());
                            if (!Boolean.TRUE.equals(data.get("failure_handled"))) {
                                metric(call, "ai.turn", "agent", null, false, "AI_TURN_FAILED");
                                product.work(
                                        call,
                                        "service_failure",
                                        "failure:" + call.get("id") + ":" + call.get("attempts"),
                                        JsonSupport.map(
                                                "error", failure.getClass().getSimpleName()));
                            }
                            data.put("failure_handled", true);
                            data.put("model_pending", false);
                            data.put("outcome", "service_failure");
                            product.save(state, data);
                            store.update(
                                    "callsession",
                                    CallLifecycle.tenant(call),
                                    call.get("id"),
                                    JsonSupport.map(
                                            "last_error",
                                            "AI turn failed: "
                                                    + failure.getClass().getSimpleName()));
                            Map<String, Object> fenced = new LinkedHashMap<>(payload);
                            fenced.put("_generation", state.get("generation"));
                            return JsonSupport.map(
                                    "payload",
                                    fenced,
                                    "result",
                                    ConversationPolicy.reply(
                                            product.policy(state).get("failure_prompt").toString(),
                                            "hangup"));
                        });
        if (snapshot == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> fenced = (Map<String, Object>) snapshot.get("payload"),
                result = (Map<String, Object>) snapshot.get("result");
        try {
            apply(fenced, result);
        } catch (RuntimeException speechFailure) {
            TaskWorker.permitted();
            // Failed TTS must not prevent termination; retain capacity until PBX confirmation.
            finish(fenced, ConversationPolicy.reply("", "hangup"), true);
        }
    }

    @SuppressWarnings("unchecked")
    private void runTurn(Map<String, Object> payload) {
        long started = System.nanoTime();
        Supplier<Map<String, Object>> prepare =
                () -> {
                    Map<String, Object> call = current(payload, true);
                    if (call == null) return null;
                    String transcript = Objects.toString(payload.get("transcript"), "");
                    Map<String, Object> state = product.state(call),
                            result = product.prepare(call, transcript, state),
                            policy = product.policy(state);
                    if (Boolean.TRUE.equals(
                            json.object(state.get("data_json").toString()).get("failure_handled")))
                        result =
                                ConversationPolicy.reply(
                                        policy.get("failure_prompt").toString(), "hangup");
                    if (result == null) result = flow(call, state, transcript);
                    Map<String, Object> ai =
                            policy.get("_ai") instanceof Map
                                    ? (Map<String, Object>) policy.get("_ai")
                                    : settings.get(CallLifecycle.tenant(call), "ai");
                    Map<String, Object> data = json.object(state.get("data_json").toString());
                    data.put("model_pending", result == null);
                    product.save(state, data);
                    return JsonSupport.map(
                            "call",
                            call,
                            "policy",
                            policy,
                            "ai",
                            ai,
                            "result",
                            result,
                            "generation",
                            state.get("generation"),
                            "request",
                            result == null ? agentRequest(call, policy, ai, transcript) : Map.of());
                };
        // Ordinary dialogue touches only the locked call. A shared phone or
        // appointment effect aborts this transaction before the effect, then
        // reruns from a fresh snapshot under the platform lock. Never upgrade
        // from a held call row lock to the platform lock.
        Map<String, Object> snapshot;
        try {
            snapshot = tx.run(() -> product.localPreparation(prepare));
        } catch (ConversationPolicy.SharedPreparationRequired shared) {
            snapshot = tx.serialized(prepare);
        }
        if (snapshot == null) return;
        Map<String, Object> result = (Map<String, Object>) snapshot.get("result"),
                ai = (Map<String, Object>) snapshot.get("ai");
        if (result == null) {
            if (!Boolean.TRUE.equals(ai.getOrDefault("enabled", true)))
                throw new ApiError(503, "AI service is disabled for tenant");
            String approved = config.get("AI_AGENT_URL", "http://localhost:8001");
            if (!Settings.endpoint(approved, false)
                    .equals(
                            Settings.endpoint(
                                    Objects.toString(ai.get("agent_url"), approved), false)))
                throw new ApiError(503, "AI endpoint is managed by the platform");
            Map<String, String> headers = new LinkedHashMap<>();
            String token = config.get("AI_AGENT_SERVICE_TOKEN", "");
            if (!token.isEmpty()) headers.put("Authorization", "Bearer " + token);
            TaskWorker.permitted();
            try {
                result =
                        validated(
                                embeddedAgent.turn(
                                        (Map<String, Object>) snapshot.get("request"),
                                        token.isEmpty() ? null : "Bearer " + token,
                                        config.number("AI_CALLBACK_TIMEOUT_SEC", 10) * 1000));
            } catch (TelephonyClient.Failure failure) {
                throw failure;
            }
        }
        final Map<String, Object> proposed = result, preparedSnapshot = snapshot;
        // This phase changes only the locked call's conversation/metrics. It
        // does not admit calls, allocate agents or release shared capacity.
        Map<String, Object> accepted =
                tx.run(
                        () -> {
                            Map<String, Object> call = current(payload, true);
                            if (call == null) return null;
                            Map<String, Object> state = product.state(call);
                            if (!Objects.equals(
                                    state.get("generation"), preparedSnapshot.get("generation")))
                                return null;
                            Map<String, Object> data =
                                    json.object(state.get("data_json").toString());
                            data.put("model_pending", false);
                            product.save(state, data);
                            Map<String, Object> guarded =
                                    guard(call, new LinkedHashMap<>(proposed), ai);
                            Map<String, Object> original =
                                    (Map<String, Object>) preparedSnapshot.get("call");
                            if (!Objects.equals(
                                    call.get("flow_node_key"), original.get("flow_node_key")))
                                store.update(
                                        "callsession",
                                        CallLifecycle.tenant(call),
                                        call.get("id"),
                                        JsonSupport.map(
                                                "flow_node_key", original.get("flow_node_key")));
                            metric(
                                    call,
                                    "ai.turn",
                                    Objects.toString(ai.get("llm_provider"), "rule"),
                                    (System.nanoTime() - started) / 1000000,
                                    true,
                                    null);
                            return guarded;
                        });
        if (accepted != null) {
            Map<String, Object> fenced = new LinkedHashMap<>(payload);
            fenced.put("_generation", snapshot.get("generation"));
            apply(fenced, accepted);
        }
    }

    private Map<String, Object> guard(
            Map<String, Object> call, Map<String, Object> result, Map<String, Object> ai) {
        String text = Objects.toString(result.get("tts_text"), "").trim(), reason = "";
        if (text.codePointCount(0, text.length())
                > Math.max(20, JsonSupport.integer(ai.get("max_reply_chars"), 240)))
            reason = "reply_too_long";
        for (String phrase :
                Objects.toString(ai.get("forbidden_phrases"), "").replace('\n', ',').split(","))
            if (!phrase.trim().isEmpty()
                    && text.toLowerCase(Locale.ROOT)
                            .contains(phrase.trim().toLowerCase(Locale.ROOT)))
                reason = "forbidden_phrase";
        if (!reason.isEmpty()) {
            metric(call, "ai.output_guard", "policy", null, false, "AI_OUTPUT_BLOCKED");
            boolean human =
                    Values.set("ai_handoff", "mixed_human_first").contains(call.get("mode"));
            result.putAll(
                    ConversationPolicy.reply(
                            Objects.toString(ai.get("fallback_reply"), "抱歉，这个问题需要由人工客服为您确认。"),
                            human ? "handoff" : "speak"));
        }
        return result;
    }

    private void metric(
            Map<String, Object> call,
            String stage,
            String provider,
            Object duration,
            boolean success,
            String error) {
        store.appendMetric(
                JsonSupport.map(
                        "tenant_id",
                        CallLifecycle.tenant(call),
                        "call_session_id",
                        call.get("id"),
                        "stage",
                        stage,
                        "provider",
                        provider,
                        "duration_ms",
                        duration,
                        "success",
                        success,
                        "error_code",
                        error));
    }

    private Map<String, Object> agentRequest(
            Map<String, Object> call,
            Map<String, Object> policy,
            Map<String, Object> ai,
            String transcript) {
        List<Map<String, Object>> history = new ArrayList<>();
        List<Map<String, Object>> turns =
                store.query(
                        "speechturn",
                        "scopedAiRuntime120",
                        Store.scopedParameters(
                                CallLifecycle.tenant(call),
                                store.encode("speechturn", "call_session_id", call.get("id")),
                                call.get("attempts"),
                                true,
                                Math.max(
                                        1,
                                        Math.min(
                                                50,
                                                JsonSupport.integer(
                                                        ai.get("conversation_history_turns"),
                                                        12)))));
        Collections.reverse(turns);
        for (Map<String, Object> t : turns) {
            String text =
                    Objects.toString(
                            t.get("normalized_transcript"),
                            Objects.toString(t.get("transcript"), ""));
            if (!text.trim().isEmpty())
                history.add(
                        JsonSupport.map(
                                "role",
                                Values.set("ai", "agent", "assistant")
                                                .contains(t.get("speaker_role"))
                                        ? "assistant"
                                        : "user",
                                "content",
                                text));
        }
        Map<String, Object> campaign =
                call.get("campaign_id") == null
                        ? Collections.emptyMap()
                        : store.require(
                                "campaign", CallLifecycle.tenant(call), call.get("campaign_id"));
        Map<String, Object> context =
                JsonSupport.map(
                        "campaign_id",
                        call.get("campaign_id"),
                        "tenant_id",
                        call.get("tenant_id"),
                        "language",
                        policy.get("language"),
                        "recording_enabled",
                        campaign.getOrDefault("recording_enabled", true),
                        "hangup_sms_enabled",
                        campaign.getOrDefault("hangup_sms_enabled", true),
                        "llm_provider",
                        ai.get("llm_provider"),
                        "llm_model",
                        ai.get("llm_model"),
                        "external_llm_enabled",
                        ai.get("external_llm_enabled"),
                        "conversation",
                        history,
                        "knowledge",
                        knowledge(call, policy, transcript));
        return JsonSupport.map(
                "call_id",
                call.get("id"),
                "phone",
                call.get("phone"),
                "mode",
                call.get("mode"),
                "script",
                policy.getOrDefault("_script", ""),
                "transcript",
                transcript,
                "context",
                context);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> knowledge(
            Map<String, Object> call, Map<String, Object> policy, String query) {
        List<Map<String, Object>> items =
                policy.get("_knowledge") instanceof List
                        ? (List<Map<String, Object>>) policy.get("_knowledge")
                        : store.query(
                                "knowledgeitem",
                                "sourceAiRuntime127_4",
                                Store.queryParameters(
                                        0,
                                        CallLifecycle.tenant(call),
                                        true,
                                        call.get("campaign_id")));
        Set<String> tokens = new HashSet<>(), grams = new HashSet<>();
        Matcher words =
                Pattern.compile("(?U)[\\w\\u4e00-\\u9fff]+")
                        .matcher(query.toLowerCase(Locale.ROOT));
        while (words.find()) if (words.group().length() >= 2) tokens.add(words.group());
        String chinese = query.replaceAll("[^\\u4e00-\\u9fff]", "");
        for (int i = 0; i + 1 < chinese.length(); i++) grams.add(chinese.substring(i, i + 2));
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (item.get("valid_from") != null
                            && LocalDateTime.parse(item.get("valid_from").toString())
                                    .isAfter(Store.now())
                    || item.get("valid_until") != null
                            && !LocalDateTime.parse(item.get("valid_until").toString())
                                    .isAfter(Store.now())) continue;
            String searchable =
                    (item.get("title") + " " + item.get("keywords") + " " + item.get("content"))
                            .toLowerCase(Locale.ROOT);
            int score = 0, gram = 0;
            for (String token : tokens) if (searchable.contains(token)) score += 4;
            Set<String> terms =
                    new HashSet<>(
                            Arrays.asList(
                                    Objects.toString(item.get("keywords"), "")
                                            .toLowerCase(Locale.ROOT)
                                            .split("[\\s,，;；]+")));
            for (String word : terms)
                if (word.length() >= 2 && query.toLowerCase(Locale.ROOT).contains(word)) score += 8;
            for (String pair : grams) if (searchable.contains(pair)) gram++;
            if (gram >= 2) score += gram;
            if (score > 0) {
                Map<String, Object> row = new LinkedHashMap<>(item);
                row.put("_score", score);
                ranked.add(row);
            }
        }
        ranked.sort(
                (a, b) -> {
                    int compared =
                            Integer.compare(
                                    CallLifecycle.number(b, "_score"),
                                    CallLifecycle.number(a, "_score"));
                    return compared != 0
                            ? compared
                            : Integer.compare(
                                    CallLifecycle.number(b, "version"),
                                    CallLifecycle.number(a, "version"));
                });
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : ranked.subList(0, Math.min(3, ranked.size())))
            result.add(
                    JsonSupport.map(
                            "id",
                            item.get("id").toString(),
                            "title",
                            item.get("title"),
                            "content",
                            item.get("content"),
                            "category",
                            item.get("category"),
                            "source",
                            item.getOrDefault("source", ""),
                            "version",
                            item.get("version").toString()));
        return result;
    }

    @SuppressWarnings("unchecked")
    public void apply(Map<String, Object> payload, Map<String, Object> result) {
        final Map<String, Object> actionPayload = new LinkedHashMap<>(payload);
        // One locked snapshot immediately before dispatch replaces repeated reads
        // of the same fence. Never retain it across the external speech command:
        // the acknowledgement transaction below rechecks attempt/turn/generation.
        Map<String, Object> dispatch =
                tx.run(
                        () -> {
                            Map<String, Object> c = current(actionPayload, true);
                            if (c == null) return null;
                            return JsonSupport.map(
                                    "call",
                                    c,
                                    "state",
                                    product.state(c),
                                    "media_at",
                                    Objects.toString(result.get("tts_text"), "").isEmpty()
                                            ? null
                                            : voice.realtime(c).get("updated_at"));
                        });
        if (dispatch == null) return;
        Map<String, Object> call = (Map<String, Object>) dispatch.get("call"),
                state = (Map<String, Object>) dispatch.get("state");
        long tenant = CallLifecycle.tenant(call);
        Object id = call.get("id");
        Map<String, Object> policy = product.policy(state),
                ai =
                        policy.get("_ai") instanceof Map
                                ? (Map<String, Object>) policy.get("_ai")
                                : settings.get(tenant, "ai");
        String text = Objects.toString(result.get("tts_text"), ""),
                action = result.get("action").toString();
        Map<String, Object> playback = new LinkedHashMap<>();
        if (!text.isEmpty()) {
            Map<String, Object> fields =
                    JsonSupport.map(
                            "text",
                            text,
                            "language",
                            Objects.toString(
                                    policy.get("language"),
                                    Objects.toString(ai.get("language"), "zh-CN")),
                            "voice",
                            Objects.toString(policy.get("voice"), "").isEmpty()
                                    ? ai.get("voice")
                                    : policy.get("voice"),
                            "provider",
                            ai.get("tts_provider"),
                            "expected_speech_event_id",
                            actionPayload.get("speech_event_id"),
                            "command_id",
                            JsonSupport.sha(
                                    id
                                            + ":"
                                            + call.get("attempts")
                                            + ":"
                                            + actionPayload.get("turn_sequence")
                                            + ":"
                                            + text));
            Object beforeMediaAt = dispatch.get("media_at");
            if (beforeMediaAt == null) return;
            long started = System.nanoTime();
            playback = client.command(call, "speak", fields);
            // Media callbacks may advance timers while the command response is in flight.
            // The attempt and transcript sequence still fence all subsequent effects.
            actionPayload.remove("_generation");
            final Map<String, Object> response = playback;
            boolean saved =
                    tx.run(
                            () -> {
                                Map<String, Object> latest = current(actionPayload, true);
                                if (latest == null) return false;
                                Map<String, Object> rt = voice.realtime(latest);
                                if (Objects.equals(beforeMediaAt, rt.get("updated_at")))
                                    store.writeLocked(
                                            "realtimesession",
                                            tenant,
                                            rt.get("id"),
                                            JsonSupport.map(
                                                    "state",
                                                    "speaking",
                                                    "playback_id",
                                                    response.get("playback_id")));
                                String normalized = text.trim().replaceAll("(?U)\\s+", " "),
                                        key =
                                                JsonSupport.sha(
                                                        id
                                                                + ":"
                                                                + latest.get("attempts")
                                                                + ":ai:"
                                                                + rt.get("turn_sequence")
                                                                + ":"
                                                                + normalized);
                                if (store.scalar(
                                                "speechturn",
                                                "scopedAiRuntime160",
                                                Store.scopedParameters(
                                                        CallLifecycle.tenant(call),
                                                        store.encode(
                                                                "speechturn",
                                                                "call_session_id",
                                                                id),
                                                        key))
                                        == 0)
                                    store.insert(
                                            "speechturn",
                                            JsonSupport.map(
                                                    "tenant_id",
                                                    tenant,
                                                    "call_session_id",
                                                    id,
                                                    "provider_event_key",
                                                    key,
                                                    "turn_index",
                                                    rt.get("turn_sequence"),
                                                    "attempt",
                                                    latest.get("attempts"),
                                                    "speaker_role",
                                                    "ai",
                                                    "channel_id",
                                                    "outbound",
                                                    "transcript",
                                                    text,
                                                    "normalized_transcript",
                                                    normalized,
                                                    "is_final",
                                                    true));
                                metric(
                                        latest,
                                        "tts.dispatch",
                                        Objects.toString(ai.get("tts_provider"), "gateway"),
                                        (System.nanoTime() - started) / 1000000,
                                        true,
                                        null);
                                return true;
                            });
            if (!saved) return;
        }
        if ("hangup".equals(action)
                && playback.get("playback_id") != null
                && !Boolean.TRUE.equals(playback.get("playback_complete"))) {
            final Object playbackId = playback.get("playback_id");
            tx.serialized(
                    () -> {
                        Map<String, Object> latest = current(actionPayload, true);
                        if (latest == null) return null;
                        Map<String, Object> continuation = new LinkedHashMap<>(actionPayload);
                        continuation.remove("_generation");
                        continuation.put("result", result);
                        continuation.put("playback_id", playbackId);
                        Map<String, Object> rt = voice.realtime(latest);
                        continuation.put("turn_sequence", rt.get("turn_sequence"));
                        Map<String, Object> task =
                                outbox.enqueue(
                                        tenant,
                                        "after_playback",
                                        id,
                                        "after-playback:"
                                                + id
                                                + ":"
                                                + call.get("attempts")
                                                + ":"
                                                + playbackId,
                                        continuation);
                        product.schedule(
                                task,
                                Objects.equals(rt.get("playback_id"), playbackId)
                                        ? Store.now()
                                                .plusSeconds(
                                                        Math.max(
                                                                1,
                                                                config.number(
                                                                        "TTS_PLAYBACK_TIMEOUT_SEC",
                                                                        30)))
                                        : Store.now());
                        return null;
                    });
            return;
        }
        finish(actionPayload, result, Boolean.TRUE.equals(playback.get("playback_complete")));
    }

    private void finish(
            Map<String, Object> payload, Map<String, Object> result, boolean playbackComplete) {
        Map<String, Object> hangupCall =
                "hangup".equals(result.get("action")) ? current(payload, false) : null;
        if ("hangup".equals(result.get("action"))) {
            if (hangupCall == null) return;
            var call = hangupCall;
            Map<String, Object> response =
                    client.command(
                            call,
                            "hangup",
                            JsonSupport.map(
                                    "reason",
                                    "ai_decision",
                                    "expected_speech_event_id",
                                    payload.get("speech_event_id")));
            tx.serialized(
                    () -> {
                        Map<String, Object> latest = current(payload, true);
                        if (latest == null) return null;
                        if (Boolean.TRUE.equals(response.get("ended")))
                            lifecycle.status(
                                    JsonSupport.map(
                                            "call_id",
                                            call.get("id"),
                                            "payload",
                                            JsonSupport.map(
                                                    "status",
                                                    "completed",
                                                    "attempt",
                                                    call.get("attempts"),
                                                    "event_id",
                                                    "ai-hangup:"
                                                            + call.get("id")
                                                            + ":"
                                                            + call.get("attempts"),
                                                    "hangup_reason",
                                                    "ai_decision")));
                        else
                            store.update(
                                    "callsession",
                                    CallLifecycle.tenant(call),
                                    call.get("id"),
                                    JsonSupport.map(
                                            "last_error",
                                            "hangup requested; awaiting PBX termination"));
                        return null;
                    });
        }
        Supplier<Object> finishWork =
                () -> {
                    TaskWorker.permitted();
                    Map<String, Object> latest =
                            "hangup".equals(result.get("action"))
                                    ? tx.call(hangupCall.get("id"))
                                    : current(payload, true);
                    if (latest == null
                            || hangupCall != null
                                    && CallLifecycle.number(latest, "attempts")
                                            != CallLifecycle.number(hangupCall, "attempts"))
                        return null;
                    var call = latest;
                    if (!"hangup".equals(result.get("action"))) {
                        if ("handoff".equals(result.get("action"))
                                || Boolean.TRUE.equals(result.get("handoff_to_human")))
                            handoffs.queue(latest, "ai_decision");
                        else {
                            latest =
                                    store.patchLoaded(
                                            "callsession",
                                            latest,
                                            JsonSupport.map("status", "in_ai"));
                            if (playbackComplete) {
                                Map<String, Object> rt = voice.realtime(latest);
                                store.writeLocked(
                                        "realtimesession",
                                        CallLifecycle.tenant(call),
                                        rt.get("id"),
                                        JsonSupport.map("state", "listening", "playback_id", null));
                                product.arm(latest, "silence");
                            }
                        }
                    }
                    Object resultingStatus =
                            Values.set("continue", "speak").contains(result.get("action"))
                                            && !Boolean.TRUE.equals(result.get("handoff_to_human"))
                                    ? latest.get("status")
                                    : store.require(
                                                    "callsession",
                                                    CallLifecycle.tenant(call),
                                                    call.get("id"))
                                            .get("status");
                    Map<String, Object> event =
                            store.insert(
                                    "callevent",
                                    JsonSupport.map(
                                            "call_session_id",
                                            call.get("id"),
                                            "event_type",
                                            "ai_decision",
                                            "source",
                                            "dispatcher",
                                            "payload",
                                            json.json(
                                                    JsonSupport.map(
                                                            "action",
                                                            result.get("action"),
                                                            "tts_dispatched",
                                                            !Objects.toString(
                                                                            result.get("tts_text"),
                                                                            "")
                                                                    .isEmpty(),
                                                            "handoff_to_human",
                                                            result.getOrDefault(
                                                                    "handoff_to_human", false),
                                                            "resulting_status",
                                                            resultingStatus))));
                    outbox.callback(
                            latest,
                            "call.ai_decision",
                            json.object(event.get("payload").toString()),
                            "callback:ai-decision:" + event.get("id"));
                    if (result.get("hangup_sms") != null
                            && !result.get("hangup_sms").toString().isEmpty()) {
                        Map<String, Object> campaign =
                                call.get("campaign_id") == null
                                        ? null
                                        : store.require(
                                                "campaign",
                                                CallLifecycle.tenant(call),
                                                call.get("campaign_id"));
                        if (campaign == null
                                || Boolean.TRUE.equals(campaign.get("hangup_sms_enabled")))
                            outbox.enqueue(
                                    CallLifecycle.tenant(call),
                                    "send_sms",
                                    call.get("id"),
                                    "sms:"
                                            + call.get("id")
                                            + ":"
                                            + call.get("attempts")
                                            + ":"
                                            + Objects.toString(payload.get("turn_sequence"), "0"),
                                    JsonSupport.map(
                                            "call_id",
                                            call.get("id"),
                                            "attempt",
                                            call.get("attempts"),
                                            "text",
                                            settings.get(CallLifecycle.tenant(call), "sms")
                                                    .getOrDefault(
                                                            "hangup_template",
                                                            result.get("hangup_sms"))));
                    }
                    return null;
                };
        if (Values.set("hangup", "handoff").contains(result.get("action"))
                || Boolean.TRUE.equals(result.get("handoff_to_human"))) tx.serialized(finishWork);
        else tx.run(finishWork);
    }

    @SuppressWarnings("unchecked")
    public void afterPlayback(Map<String, Object> payload) {
        if ("appointment".equals(payload.get("product_kind"))) {
            appointment(payload);
            return;
        }
        if ("timer".equals(payload.get("product_kind"))) {
            timer(payload);
            return;
        }
        if (current(payload, false) == null) return;
        Map<String, Object> result =
                new LinkedHashMap<>((Map<String, Object>) payload.get("result"));
        result.put("tts_text", null);
        finish(payload, result, true);
    }

    private void timer(Map<String, Object> payload) {
        TaskWorker.permitted();
        Long hint = TaskWorker.tenantHint();
        var identity =
                hint == null
                        ? null
                        : store.one(
                                "callsession",
                                "findById",
                                JsonSupport.map(
                                        "tenantId",
                                        hint,
                                        "id",
                                        store.encode("callsession", "id", payload.get("call_id"))));
        if (identity == null) identity = store.locate("callsession", payload.get("call_id"), false);
        if (identity == null) return;
        long timerTenant = CallLifecycle.tenant(identity);
        Supplier<Map<String, Object>> lockedCall =
                () ->
                        store.one(
                                "callsession",
                                "lockById",
                                JsonSupport.map(
                                        "tenantId",
                                        timerTenant,
                                        "id",
                                        store.encode("callsession", "id", payload.get("call_id"))));
        Supplier<Map<String, Object>> prepare =
                () -> {
                    TaskWorker.permitted();
                    Map<String, Object> call = lockedCall.get();
                    if (call == null
                            || CallLifecycle.number(call, "attempts")
                                    != JsonSupport.integer(payload.get("attempt"), -1)
                            || !Values.set("answered", "in_ai", "waiting_human")
                                    .contains(call.get("status"))) return null;
                    Map<String, Object> state = product.state(call),
                            policy = product.policy(state),
                            data = json.object(state.get("data_json").toString());
                    if (CallLifecycle.number(state, "generation")
                                    != JsonSupport.integer(payload.get("generation"), -1)
                            || !Objects.equals(state.get("timer_kind"), payload.get("kind"))
                            || state.get("deadline") == null
                            || LocalDateTime.parse(state.get("deadline").toString())
                                    .isAfter(Store.now())) return null;
                    Map<String, Object> rt = voice.realtime(call);
                    String kind = payload.get("kind").toString();
                    if ("silence".equals(kind)
                            && !Values.set("listening", "interrupted").contains(rt.get("state")))
                        return null;
                    Map<String, Object> result;
                    Object cached = data.get("timer_action");
                    if (cached instanceof Map
                            && Objects.equals(
                                    ((Map<?, ?>) cached).get("generation"),
                                    state.get("generation"))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> old =
                                (Map<String, Object>) ((Map<?, ?>) cached).get("result");
                        result = old;
                    } else if ("handoff".equals(kind)) {
                        if (!"waiting_human".equals(call.get("status"))) return null;
                        if (!Boolean.FALSE.equals(policy.get("handoff_auto_callback")))
                            product.handoffCallback(call, policy);
                        product.resumeAi(call);
                        result =
                                ConversationPolicy.reply(
                                        policy.get("handoff_timeout_prompt").toString(),
                                        "end".equals(policy.get("handoff_fallback"))
                                                ? "hangup"
                                                : "speak");
                        data.put("outcome", "handoff_timeout");
                    } else if ("failure".equals(kind)) {
                        result =
                                ConversationPolicy.reply(
                                        policy.get("failure_prompt").toString(), "hangup");
                        data.put("outcome", "service_failure");
                        product.work(
                                call,
                                "service_failure",
                                "media-failure:" + call.get("id") + ":" + call.get("attempts"),
                                JsonSupport.map("error", "speech_service_unavailable"));
                    } else {
                        int count = JsonSupport.integer(data.get("silence_count"), 0) + 1;
                        data.put("silence_count", count);
                        boolean exhausted =
                                count > JsonSupport.integer(policy.get("max_clarifications"), 2);
                        result =
                                ConversationPolicy.reply(
                                        exhausted
                                                ? "暂时没有收到您的回复，本次先结束，再见。"
                                                : policy.get("silence_prompt").toString(),
                                        exhausted ? "hangup" : "speak");
                        if (exhausted) data.put("outcome", "no_response");
                    }
                    data.put(
                            "timer_action",
                            JsonSupport.map(
                                    "generation", state.get("generation"), "result", result));
                    product.save(state, data);
                    Map<String, Object> action = new LinkedHashMap<>(payload);
                    action.put("turn_sequence", rt.get("turn_sequence"));
                    action.put("speech_event_id", data.get("speech_event_id"));
                    return JsonSupport.map("payload", action, "result", result);
                };
        // Handoff timers allocate/release shared agent ownership. Silence and
        // media failure preparation change only this locked call's state/work.
        // Any later hangup/handoff action retains apply/finish's resource locks.
        Map<String, Object> snapshot =
                "handoff".equals(payload.get("kind")) ? tx.serialized(prepare) : tx.run(prepare);
        if (snapshot == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) snapshot.get("payload"),
                r = (Map<String, Object>) snapshot.get("result");
        apply(p, r);
        tx.run(
                () -> {
                    TaskWorker.permitted();
                    Map<String, Object> call = lockedCall.get();
                    if (call != null
                            && CallLifecycle.number(call, "attempts")
                                    == JsonSupport.integer(payload.get("attempt"), -1)) {
                        Map<String, Object> state = product.state(call);
                        if (CallLifecycle.number(state, "generation")
                                == JsonSupport.integer(payload.get("generation"), -1)) {
                            state.putAll(
                                    JsonSupport.map(
                                            "generation",
                                            CallLifecycle.number(state, "generation") + 1,
                                            "deadline",
                                            null,
                                            "timer_kind",
                                            ""));
                            product.save(state, json.object(state.get("data_json").toString()));
                        }
                    }
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    private void appointment(Map<String, Object> payload) {
        Map<String, Object> ready =
                tx.serialized(
                        () -> {
                            TaskWorker.permitted();
                            Map<String, Object> a =
                                    store.firstAcross(
                                            "callbackappointment",
                                            "scopedAiRuntime228",
                                            Store.queryParameters(
                                                    -1,
                                                    store.encode(
                                                            "callbackappointment",
                                                            "id",
                                                            payload.get("appointment_id"))));
                            if (a == null
                                    || CallLifecycle.number(a, "revision")
                                            != JsonSupport.integer(payload.get("revision"), -1)
                                    || !Values.set("confirmed", "dispatching")
                                            .contains(a.get("state"))
                                    || LocalDateTime.parse(a.get("scheduled_at").toString())
                                            .isAfter(Store.now())) return null;
                            long tenant = CallLifecycle.tenant(a);
                            Map<String, Object> source =
                                    store.one(
                                            "callsession",
                                            "sourceAiRuntime230_7",
                                            Store.queryParameters(
                                                    0,
                                                    tenant,
                                                    store.encode(
                                                            "callsession",
                                                            "id",
                                                            a.get("source_call_id"))));
                            if (source == null
                                    || store.scalar(
                                                    "phonesuppression",
                                                    "sourceAiRuntime231_8",
                                                    Store.queryParameters(
                                                            0, tenant, source.get("phone")))
                                            > 0) {
                                store.update(
                                        "callbackappointment",
                                        tenant,
                                        a.get("id"),
                                        JsonSupport.map("state", "cancelled"));
                                return null;
                            }
                            Map<String, Object> policy =
                                    (Map<String, Object>)
                                            product.current(tenant, source.get("campaign_id"))
                                                    .get("policy");
                            if (!product.inHours(policy, Store.now())
                                    || LocalDateTime.parse(a.get("scheduled_at").toString())
                                            .plusMinutes(15)
                                            .isBefore(Store.now())) {
                                store.update(
                                        "callbackappointment",
                                        tenant,
                                        a.get("id"),
                                        JsonSupport.map("state", "needs_review"));
                                product.work(
                                        source,
                                        "missed_callback",
                                        "missed:" + a.get("id"),
                                        JsonSupport.map("appointment_id", a.get("id")));
                                return null;
                            }
                            Object cid =
                                    a.get("dial_call_id") == null
                                            ? ConversationPolicy.uuid5(
                                                    "callback-dial:" + a.get("id"))
                                            : a.get("dial_call_id");
                            if ((store.locate(
                                                            "callsession",
                                                            store.encode("callsession", "id", cid),
                                                            false)
                                                    == null
                                            ? 0
                                            : 1)
                                    == 0) {
                                store.insert(
                                        "callsession",
                                        JsonSupport.map(
                                                "id",
                                                cid,
                                                "tenant_id",
                                                tenant,
                                                "phone",
                                                source.get("phone"),
                                                "contact_id",
                                                source.get("contact_id"),
                                                "mode",
                                                source.get("mode"),
                                                "status",
                                                "queued",
                                                "max_attempts",
                                                1));
                                Map<String, Object> original = product.state(source);
                                store.insert(
                                        "conversationstate",
                                        JsonSupport.map(
                                                "tenant_id",
                                                tenant,
                                                "call_id",
                                                cid,
                                                "attempt",
                                                1,
                                                "policy_version_id",
                                                original.get("policy_version_id"),
                                                "policy_json",
                                                original.get("policy_json")));
                            }
                            store.update(
                                    "callbackappointment",
                                    tenant,
                                    a.get("id"),
                                    JsonSupport.map("dial_call_id", cid, "state", "dispatching"));
                            return JsonSupport.map(
                                    "call_id",
                                    cid,
                                    "tenant_id",
                                    tenant,
                                    "appointment_id",
                                    a.get("id"),
                                    "revision",
                                    a.get("revision"));
                        });
        if (ready == null) return;
        Map<String, Object> call =
                store.require("callsession", CallLifecycle.tenant(ready), ready.get("call_id"));
        if ("queued".equals(call.get("status"))
                && !dial.dispatch(call.get("id"))
                && "queued"
                        .equals(
                                store.require(
                                                "callsession",
                                                CallLifecycle.tenant(ready),
                                                call.get("id"))
                                        .get("status"))) throw new TaskWorker.Deferred();
        tx.serialized(
                () -> {
                    Map<String, Object> a =
                            store.require(
                                    "callbackappointment",
                                    CallLifecycle.tenant(ready),
                                    ready.get("appointment_id"));
                    if (Objects.equals(a.get("revision"), ready.get("revision"))
                            && "dispatching".equals(a.get("state")))
                        store.update(
                                "callbackappointment",
                                CallLifecycle.tenant(ready),
                                a.get("id"),
                                JsonSupport.map(
                                        "state",
                                        CallLifecycle.number(
                                                                store.require(
                                                                        "callsession",
                                                                        CallLifecycle.tenant(ready),
                                                                        ready.get("call_id")),
                                                                "attempts")
                                                        > 0
                                                ? "dispatched"
                                                : "needs_review"));
                    return null;
                });
    }

    public void interrupt(Map<String, Object> payload) {
        Map<String, Object> call =
                store.locate(
                        "callsession",
                        store.encode("callsession", "id", payload.get("call_id")),
                        false);
        if (call == null
                || CallLifecycle.TERMINAL.contains(call.get("status"))
                || CallLifecycle.number(call, "attempts")
                        != JsonSupport.integer(payload.get("attempt"), -1)) return;
        Map<String, Object> rt =
                store.one(
                        "realtimesession",
                        "scopedAiRuntime243",
                        Store.scopedParameters(
                                CallLifecycle.tenant(call),
                                store.encode(
                                        "realtimesession", "call_session_id", call.get("id"))));
        if (rt == null
                || payload.containsKey("playback_id")
                        && !Objects.equals(payload.get("playback_id"), rt.get("playback_id"))
                || payload.containsKey("turn_sequence")
                        && JsonSupport.integer(payload.get("turn_sequence"), -1)
                                != CallLifecycle.number(rt, "turn_sequence")) return;
        try {
            client.command(
                    call,
                    "stop-speaking",
                    JsonSupport.map("expected_speech_event_id", payload.get("speech_event_id")));
        } catch (TelephonyClient.Failure e) {
            if (e.status == 409) return;
            throw e;
        }
        tx.serialized(
                () -> {
                    TaskWorker.permitted();
                    Map<String, Object> latest = tx.call(call.get("id")),
                            media = voice.realtime(latest);
                    if (CallLifecycle.number(latest, "attempts")
                                    == CallLifecycle.number(call, "attempts")
                            && !CallLifecycle.TERMINAL.contains(latest.get("status"))
                            && !"closed".equals(media.get("state"))
                            && Objects.equals(media.get("playback_id"), rt.get("playback_id")))
                        store.update(
                                "realtimesession",
                                CallLifecycle.tenant(call),
                                media.get("id"),
                                JsonSupport.map("state", "interrupted", "playback_id", null));
                    return null;
                });
    }

    @PostMapping("/api/v1/calls/{id}/interrupt")
    public Map<String, Object> interruptApi(@PathVariable
    UUID id, HttpServletRequest request) {
        Identity identity = Identity.from(request);
        Map<String, Object> call = store.require("callsession", identity.tenant(), id);
        handoffs.visible(call, identity);
        interrupt(JsonSupport.map("call_id", id, "attempt", call.get("attempts")));
        return JsonSupport.map("result", "ok");
    }
}
