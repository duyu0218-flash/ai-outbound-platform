package com.duozhi.services;

import jakarta.annotation.PreDestroy;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="service.role", havingValue="agent")
class AgentController {
    private final Settings settings;
    private final AgentPolicy policy;
    private final AccountQuota quota;
    private final AgentTracing tracing;
    private final HttpClient client = Support.client();
    AgentController(Settings settings) throws Exception {
        this.settings = settings; policy = new AgentPolicy(settings);
        settings.number("MAX_OUTPUT_TOKENS", 800, 1, 4096);
        if (!Set.of("rule", "openai-compatible").contains(settings.get("LLM_PROVIDER", "rule"))) throw new IllegalArgumentException("unsupported LLM provider");
        if (settings.production() && settings.secret("SERVICE_TOKEN").isEmpty()) throw new IllegalArgumentException("SERVICE_TOKEN is required in production");
        if (settings.production() && settings.get("LLM_PROVIDER", "rule").equals("openai-compatible")) endpoint();
        tracing = new AgentTracing(settings);
        quota = new AccountQuota(settings);
    }
    @PreDestroy void close() throws Exception { try { tracing.close(); } finally { quota.close(); } }
    @GetMapping("/health") Map<String,Object> health() { return Map.of("status", "ok", "service", settings.get("APP_NAME", "AI-Outbound-Agent")); }
    @GetMapping("/readyz") Map<String,Object> ready(@RequestHeader(value="Authorization", required=false) String auth) {
        Support.auth(settings, "SERVICE_TOKEN", auth, true);
        if (!quota.ready()) throw new Support.Failure(503, "model account budget unavailable");
        return Map.of("status", "ready", "inflight", quota.inflight(), "model_connectivity_verified", false, "observability", tracing.status());
    }
    @PostMapping("/agent/start") Map<String,Object> start(@RequestBody Map<String,Object> payload, @RequestHeader(value="Authorization", required=false) String auth) {
        Support.auth(settings, "SERVICE_TOKEN", auth, true);
        try (var span = tracing.span("agent.start", Support.str(payload, "call_id", ""))) { return policy.turn(payload, true); }
    }
    @PostMapping("/agent/turn") Map<String,Object> turn(@RequestBody Map<String,Object> payload, @RequestHeader(value="Authorization", required=false) String auth) throws Exception {
        Support.auth(settings, "SERVICE_TOKEN", auth, true);
        try (var span = tracing.span("agent.turn", Support.str(payload, "call_id", ""))) {
            try { var result = turnInternal(payload); span.result(result); return result; }
            catch (Exception error) { span.error(error); throw error; }
        }
    }
    private Map<String,Object> turnInternal(Map<String,Object> payload) throws Exception {
        Map<String,Object> result = policy.turn(payload, false), context = Support.map(payload.get("context"));
        String provider = Support.asciiLower(Support.str(context, "llm_provider", settings.get("LLM_PROVIDER", "rule")).strip());
        if (!Set.of("rule", "openai-compatible").contains(provider)) throw new Support.Failure(422, "unsupported LLM provider");
        if (result.get("action").equals("speak") && provider.equals("openai-compatible")) {
            if (!Boolean.TRUE.equals(context.get("external_llm_enabled"))) throw new Support.Failure(409, "external LLM use is not approved for this tenant");
            result.put("tts_text", generate(payload, context));
        }
        return result;
    }
    private URI endpoint() {
        URI uri = Support.endpoint(settings.get("OPENAI_BASE_URL", ""));
        if (uri.getQuery() != null) throw new Support.Failure(503, "LLM endpoint must not contain a query");
        if (settings.secret("OPENAI_API_KEY").isEmpty()) throw new Support.Failure(503, "external LLM credentials are required");
        if (settings.production() && settings.flag("LLM_REQUIRE_HTTPS", true) && !uri.getScheme().equals("https")) throw new Support.Failure(503, "external LLM endpoint must use HTTPS in production");
        Set<String> allowed = settings.list("LLM_ALLOWED_HOSTS");
        String host = Support.asciiLower(uri.getHost()).replaceAll("\\.$", "");
        if ((settings.production() && allowed.isEmpty()) || (!allowed.isEmpty() && allowed.stream().noneMatch(v -> v.replaceAll("\\.$", "").equals(host)))) throw new Support.Failure(503, "external LLM endpoint host is not allowlisted");
        return URI.create(uri.toString().replaceAll("/+$", "") + "/chat/completions");
    }
    static String redact(String value) {
        value = Pattern.compile("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", Pattern.CASE_INSENSITIVE).matcher(value).replaceAll("[REDACTED_EMAIL]");
        value = Pattern.compile("(?<!\\w)\\d{17}[\\dXx](?!\\w)").matcher(value).replaceAll("[REDACTED_ID]");
        return Pattern.compile("(?<!\\w)\\+?\\d[\\d\\s().-]{5,}\\d(?!\\w)").matcher(value).replaceAll("[REDACTED_PHONE]");
    }
    @SuppressWarnings("unchecked")
    List<Map<String,String>> messages(Map<String,Object> payload, Map<String,Object> context) {
        String language = Support.str(context, "language", "zh-CN"), script = Support.str(payload, "script", ""), transcript = Support.str(payload, "transcript", "");
        List<Map<String,String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "You are an outbound-call voice agent. Reply briefly, naturally, and only with words that can be spoken. Follow the supplied script and do not invent commitments." + (language.toLowerCase(Locale.ROOT).startsWith("zh") ? " Reply in Chinese." : "")));
        messages.add(Map.of("role", "system", "content", script.isEmpty() ? "No approved script was supplied." : "Approved call script:\n" + script));
        if (context.get("knowledge") instanceof List<?> knowledge && !knowledge.isEmpty()) {
            List<String> entries = new ArrayList<>(); for (Object item : knowledge.subList(0, Math.min(3, knowledge.size()))) { var entry = Support.map(item); entries.add("[" + Support.str(entry, "title", "Knowledge") + "]\n" + Support.str(entry, "content", "")); }
            messages.add(Map.of("role", "system", "content", "Approved knowledge for this turn follows. Use only relevant facts and never invent missing prices, commitments, or policies:\n" + String.join("\n\n", entries)));
        }
        List<Map<String,String>> history = new ArrayList<>(); int remaining = (int) settings.number("CONVERSATION_HISTORY_MAX_CHARS", 12000, 0, 1000000);
        if (context.get("conversation") instanceof List<?> conversation) {
            int turns = (int) settings.number("CONVERSATION_HISTORY_TURNS", 12, 1, 1000);
            for (int index = conversation.size() - 1; index >= Math.max(0, conversation.size() - turns); index--) {
                var item = Support.map(conversation.get(index)); String content = Support.str(item, "content", "").strip(); if (content.isEmpty() || remaining <= 0) continue;
                int[] points = content.codePoints().toArray(); if (points.length > remaining) content = new String(points, points.length - remaining, remaining);
                remaining -= content.codePointCount(0, content.length()); String role = Set.of("assistant", "ai", "agent").contains(Support.str(item, "role", "").toLowerCase(Locale.ROOT)) ? "assistant" : "user";
                history.add(Map.of("role", role, "content", content));
            }
            Collections.reverse(history);
            if (!history.isEmpty() && history.get(history.size() - 1).equals(Map.of("role", "user", "content", transcript))) history.remove(history.size() - 1);
        }
        messages.addAll(history); messages.add(Map.of("role", "user", "content", transcript.isEmpty() ? "Begin the call with a short greeting." : transcript));
        if (!settings.flag("LLM_SEND_PII", false)) messages = messages.stream().map(m -> Map.of("role", m.get("role"), "content", redact(m.get("content")))).toList();
        return messages;
    }
    private String generate(Map<String,Object> payload, Map<String,Object> context) throws Exception {
        try (var span = tracing.span("llm.reply", Support.str(payload, "call_id", ""))) {
            span.generation(Support.str(context, "llm_model", settings.get("OPENAI_MODEL", "gpt-4o-mini")));
            try { return generateInternal(payload, context); } catch (Exception error) { span.error(error); throw error; }
        }
    }
    private String generateInternal(Map<String,Object> payload, Map<String,Object> context) throws Exception {
        URI target = endpoint(); List<Map<String,String>> messages = messages(payload, context);
        long outputTokens = settings.number("MAX_OUTPUT_TOKENS", 800, 1, 4096);
        long estimate = outputTokens + 32; for (var message : messages) estimate += message.get("content").getBytes(StandardCharsets.UTF_8).length + 32;
        quota.acquire(estimate);
        try {
            byte[] body = Support.json(Map.of("model", Support.str(context, "llm_model", settings.get("OPENAI_MODEL", "gpt-4o-mini")), "messages", messages, "max_tokens", outputTokens, "temperature", 0.3));
            var response = client.send(HttpRequest.newBuilder(target).timeout(Duration.ofMillis((long)(settings.decimal("OPENAI_TIMEOUT_SEC", 8, 0.1, 300) * 1000))).header("Content-Type", "application/json").header("Authorization", "Bearer " + settings.secret("OPENAI_API_KEY")).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var input = Support.timed(response.body(), (long)(settings.decimal("OPENAI_TIMEOUT_SEC", 8, .1, 300) * 1000))) {
                if (Set.of(429, 503).contains(response.statusCode())) {
                    double delay; try { delay = Double.parseDouble(response.headers().firstValue("Retry-After").orElse("5")); } catch (NumberFormatException invalid) { delay = 5; }
                    if (!Double.isFinite(delay)) delay = 5; quota.block(Math.max(1, Math.min(300, delay)));
                }
                if (response.statusCode() / 100 != 2) throw new Support.Failure(502, "external LLM request failed");
                Map<String,Object> data = Support.object(Support.read(input, 1048576));
                tracing.usage(data);
                if (!(data.get("choices") instanceof List<?> choices) || choices.isEmpty()) throw new Support.Failure(502, "LLM response did not contain a message");
                Object content = Support.map(Support.map(choices.get(0)).get("message")).get("content");
                if (!(content instanceof String text) || text.isBlank()) throw new Support.Failure(502, "LLM returned an empty message");
                return text.strip();
            }
        } finally { quota.release(); }
    }
}
