package com.duozhi.outbound.ai.service;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.dao.LedgerDatabase;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.voiceai.service.VoiceAiBackends;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public class EmbeddedAgent {
    private final Settings settings;
    private final AgentPolicy policy;
    private final AccountQuota quota;
    private final AgentTracing tracing;
    private final HttpClient client = Support.client();

    public EmbeddedAgent(Settings settings) throws Exception {
        this(settings, null);
    }

    public EmbeddedAgent(Settings settings, LedgerDatabase database) throws Exception {
        this.settings = settings;
        policy = new AgentPolicy(settings);
        settings.number("MAX_OUTPUT_TOKENS", 800, 1, 4096);
        if (!Set.of("rule", "openai-compatible").contains(settings.get("LLM_PROVIDER", "rule")))
            throw new IllegalArgumentException("unsupported LLM provider");
        if (settings.production() && settings.secret("SERVICE_TOKEN").isEmpty())
            throw new IllegalArgumentException("SERVICE_TOKEN is required in production");
        if (settings.production()
                && settings.get("LLM_PROVIDER", "rule").equals("openai-compatible")) endpoint();
        tracing = new AgentTracing(settings);
        quota = new AccountQuota(settings, database);
    }

    @PreDestroy
    public void close() throws Exception {
        try {
            tracing.close();
        } finally {
            quota.close();
        }
    }

    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", settings.get("APP_NAME", "AI-Outbound-Agent"));
    }

    public Map<String, Object> ready(String auth) {
        Support.auth(settings, "SERVICE_TOKEN", auth, true);
        if (!quota.ready()) throw new Support.Failure(503, "model account budget unavailable");
        return Map.of(
                "status",
                "ready",
                "inflight",
                quota.inflight(),
                "model_connectivity_verified",
                false,
                "observability",
                tracing.status());
    }

    public Map<String, Object> start(Map<String, Object> payload, String auth) {
        Support.auth(settings, "SERVICE_TOKEN", auth, true);
        try (var span = tracing.span("agent.start", Support.str(payload, "call_id", ""))) {
            return policy.turn(payload, true);
        }
    }

    public Map<String, Object> turn(Map<String, Object> payload, String auth) throws Exception {
        Support.auth(settings, "SERVICE_TOKEN", auth, true);
        try (var span = tracing.span("agent.turn", Support.str(payload, "call_id", ""))) {
            try {
                var result = turnInternal(payload);
                span.result(result);
                return result;
            } catch (Exception error) {
                span.error(error);
                throw error;
            }
        }
    }

    private Map<String, Object> turnInternal(Map<String, Object> payload) throws Exception {
        Map<String, Object> result = policy.turn(payload, false),
                context = Support.map(payload.get("context"));
        var profile = Support.map(context.get("voice_ai_profile"));
        if (!profile.isEmpty()) {
            if (!Boolean.TRUE.equals(context.get("external_llm_enabled")))
                throw new Support.Failure(409, "external model use is not approved");
            if (result.get("action").equals("speak")) {
                if ("realtime".equals(profile.get("route"))) {
                    result.put("model_reply", true);
                    result.put("tts_text", "按已批准话术回应当前语音");
                } else {
                    long estimate =
                            4096
                                    + Support.str(payload, "script", "").length()
                                    + Support.str(payload, "transcript", "").length();
                    quota.acquire(estimate);
                    try {
                        var messages = messages(payload, context);
                        result.put(
                                "tts_text",
                                new VoiceAiBackends(settings).generate(profile, messages));
                    } finally {
                        quota.release();
                    }
                }
            }
            return result;
        }
        String provider =
                Support.asciiLower(
                        Support.str(context, "llm_provider", settings.get("LLM_PROVIDER", "rule"))
                                .strip());
        if (!Set.of("rule", "openai-compatible").contains(provider))
            throw new Support.Failure(422, "unsupported LLM provider");
        if (result.get("action").equals("speak") && provider.equals("openai-compatible")) {
            if (!Boolean.TRUE.equals(context.get("external_llm_enabled")))
                throw new Support.Failure(409, "external LLM use is not approved for this tenant");
            result.put("tts_text", generate(payload, context));
        }
        return result;
    }

    private URI endpoint() {
        URI uri = Support.endpoint(settings.get("OPENAI_BASE_URL", ""));
        if (uri.getQuery() != null)
            throw new Support.Failure(503, "LLM endpoint must not contain a query");
        if (settings.secret("OPENAI_API_KEY").isEmpty())
            throw new Support.Failure(503, "external LLM credentials are required");
        if (settings.production()
                && settings.flag("LLM_REQUIRE_HTTPS", true)
                && !uri.getScheme().equals("https"))
            throw new Support.Failure(503, "external LLM endpoint must use HTTPS in production");
        Set<String> allowed = settings.list("LLM_ALLOWED_HOSTS");
        String host = Support.asciiLower(uri.getHost()).replaceAll("\\.$", "");
        if ((settings.production() && allowed.isEmpty())
                || (!allowed.isEmpty()
                        && allowed.stream().noneMatch(v -> v.replaceAll("\\.$", "").equals(host))))
            throw new Support.Failure(503, "external LLM endpoint host is not allowlisted");
        return URI.create(uri.toString().replaceAll("/+$", "") + "/chat/completions");
    }

    static String redact(String value) {
        value =
                Pattern.compile(
                                "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
                                Pattern.CASE_INSENSITIVE)
                        .matcher(value)
                        .replaceAll("[REDACTED_EMAIL]");
        value =
                Pattern.compile("(?<!\\w)\\d{17}[\\dXx](?!\\w)")
                        .matcher(value)
                        .replaceAll("[REDACTED_ID]");
        return Pattern.compile("(?<!\\w)\\+?\\d[\\d\\s().-]{5,}\\d(?!\\w)")
                .matcher(value)
                .replaceAll("[REDACTED_PHONE]");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, String>> messages(Map<String, Object> payload, Map<String, Object> context) {
        String language = Support.str(context, "language", "zh-CN"),
                script = Support.str(payload, "script", ""),
                transcript = Support.str(payload, "transcript", "");
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(
                Map.of(
                        "role",
                        "system",
                        "content",
                        "You are an outbound-call voice agent. Reply briefly, naturally, and only"
                                + " with words that can be spoken. Follow the supplied script and do"
                                + " not invent commitments."
                                + (language.toLowerCase(Locale.ROOT).startsWith("zh")
                                        ? " Reply in Chinese."
                                        : "")));
        messages.add(
                Map.of(
                        "role",
                        "system",
                        "content",
                        script.isEmpty()
                                ? "No approved script was supplied."
                                : "Approved call script:\n" + script));
        if (context.get("knowledge") instanceof List<?> knowledge && !knowledge.isEmpty()) {
            List<String> entries = new ArrayList<>();
            for (Object item : knowledge.subList(0, Math.min(3, knowledge.size()))) {
                var entry = Support.map(item);
                entries.add(
                        "["
                                + Support.str(entry, "title", "Knowledge")
                                + "]\n"
                                + Support.str(entry, "content", ""));
            }
            messages.add(
                    Map.of(
                            "role",
                            "system",
                            "content",
                            "Approved knowledge for this turn follows. Use only relevant facts and"
                                    + " never invent missing prices, commitments, or policies:\n"
                                    + String.join("\n\n", entries)));
        }
        List<Map<String, String>> history = new ArrayList<>();
        int remaining = (int) settings.number("CONVERSATION_HISTORY_MAX_CHARS", 12000, 0, 1000000);
        if (context.get("conversation") instanceof List<?> conversation) {
            int turns = (int) settings.number("CONVERSATION_HISTORY_TURNS", 12, 1, 1000);
            for (int index = conversation.size() - 1;
                    index >= Math.max(0, conversation.size() - turns);
                    index--) {
                var item = Support.map(conversation.get(index));
                String content = Support.str(item, "content", "").strip();
                if (content.isEmpty() || remaining <= 0) continue;
                int[] points = content.codePoints().toArray();
                if (points.length > remaining)
                    content = new String(points, points.length - remaining, remaining);
                remaining -= content.codePointCount(0, content.length());
                String role =
                        Set.of("assistant", "ai", "agent")
                                        .contains(
                                                Support.str(item, "role", "")
                                                        .toLowerCase(Locale.ROOT))
                                ? "assistant"
                                : "user";
                history.add(Map.of("role", role, "content", content));
            }
            Collections.reverse(history);
            if (!history.isEmpty()
                    && history.get(history.size() - 1)
                            .equals(Map.of("role", "user", "content", transcript)))
                history.remove(history.size() - 1);
        }
        messages.addAll(history);
        messages.add(
                Map.of(
                        "role",
                        "user",
                        "content",
                        transcript.isEmpty()
                                ? "Begin the call with a short greeting."
                                : transcript));
        if (!settings.flag("LLM_SEND_PII", false))
            messages =
                    messages.stream()
                            .map(
                                    m ->
                                            Map.of(
                                                    "role",
                                                    m.get("role"),
                                                    "content",
                                                    redact(m.get("content"))))
                            .toList();
        return messages;
    }

    private String generate(Map<String, Object> payload, Map<String, Object> context)
            throws Exception {
        try (var span = tracing.span("llm.reply", Support.str(payload, "call_id", ""))) {
            span.generation(
                    Support.str(context, "llm_model", settings.get("OPENAI_MODEL", "gpt-4o-mini")));
            try {
                return generateInternal(payload, context);
            } catch (Exception error) {
                span.error(error);
                throw error;
            }
        }
    }

    private String generateInternal(Map<String, Object> payload, Map<String, Object> context)
            throws Exception {
        URI target = endpoint();
        List<Map<String, String>> messages = messages(payload, context);
        long outputTokens = settings.number("MAX_OUTPUT_TOKENS", 800, 1, 4096);
        long estimate = outputTokens + 32;
        for (var message : messages)
            estimate += message.get("content").getBytes(StandardCharsets.UTF_8).length + 32;
        quota.acquire(estimate);
        try {
            return SpringAiGeneration.generate(
                    settings,
                    client,
                    target,
                    messages,
                    Support.str(context, "llm_model", settings.get("OPENAI_MODEL", "gpt-4o-mini")),
                    (int) outputTokens,
                    quota,
                    tracing);

        } finally {
            quota.release();
        }
    }
}
