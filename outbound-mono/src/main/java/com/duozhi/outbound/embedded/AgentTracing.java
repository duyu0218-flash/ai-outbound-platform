package com.duozhi.outbound.embedded;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Content-free OTLP/HTTP JSON export. Failures never change the call result. */
final class AgentTracing implements AutoCloseable {
    private final Settings settings;
    private final boolean enabled;
    private final double sampleRate;
    private final URI endpoint;
    private final String authorization;
    private final BlockingQueue<Map<String,Object>> pending = new ArrayBlockingQueue<>(1024);
    private final ScheduledExecutorService exporter;
    private final HttpClient client = Support.client();
    private final ThreadLocal<Span> current = new ThreadLocal<>();
    AgentTracing(Settings settings) {
        this.settings = settings; enabled = settings.flag("LANGFUSE_ENABLED", false); sampleRate = settings.decimal("LANGFUSE_SAMPLE_RATE", 0.1, 0, 1);
        if (enabled) {
            URI base = Support.endpoint(settings.get("LANGFUSE_BASE_URL", ""));
            if (base.getQuery() != null || (settings.production() && !base.getScheme().equals("https"))) throw new IllegalArgumentException("invalid Langfuse endpoint");
            String publicKey = settings.secret("LANGFUSE_PUBLIC_KEY"), secretKey = settings.secret("LANGFUSE_SECRET_KEY");
            if (publicKey.isEmpty() || secretKey.isEmpty()) throw new IllegalArgumentException("Langfuse project keys are required");
            endpoint = URI.create(base.toString().replaceAll("/+$", "") + "/api/public/otel/v1/traces");
            authorization = "Basic " + Base64.getEncoder().encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
            exporter = Executors.newSingleThreadScheduledExecutor(r -> { Thread thread = new Thread(r, "agent-trace-export"); thread.setDaemon(true); return thread; });
            exporter.scheduleWithFixedDelay(this::flush, 5, 5, TimeUnit.SECONDS);
        } else { endpoint = null; authorization = ""; exporter = null; }
    }
    Map<String,Object> status() { return Map.of("enabled", enabled, "initialized", enabled, "sample_rate", sampleRate, "content_capture", false, "connectivity_verified", false); }
    Span span(String name, String callId) {
        Span parent = current.get();
        boolean sampled = enabled && (parent == null ? ThreadLocalRandom.current().nextDouble() < sampleRate : parent.sampled);
        Span span = new Span(name, callId, parent, sampled); current.set(span); return span;
    }
    final class Span implements AutoCloseable {
        private final Span parent;
        private final boolean sampled;
        private final long start = System.currentTimeMillis() * 1_000_000;
        private final String traceId, spanId, name, callId;
        private final Map<String,Object> metadata = new LinkedHashMap<>();
        private String errorType = "";
        private String model = "";
        private Map<String,Object> usage = Map.of();
        Span(String name, String callId, Span parent, boolean sampled) {
            this.name = name; this.callId = callId; this.parent = parent; this.sampled = sampled;
            traceId = parent == null ? UUID.randomUUID().toString().replace("-", "") : parent.traceId;
            spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        void result(Map<String,Object> result) { metadata.put("action", result.get("action")); metadata.put("handoff", result.get("handoff_to_human")); }
        void generation(String name) { if (name.matches("[A-Za-z0-9_.:/-]{1,128}")) model = name; }
        void error(Exception error) { errorType = error.getClass().getSimpleName(); }
        @Override public void close() {
            current.set(parent); if (!sampled) return;
            List<Map<String,Object>> attributes = new ArrayList<>();
            attributes.add(attribute("langfuse.observation.type", name.equals("llm.reply") ? "generation" : "span"));
            attributes.add(attribute("langfuse.environment", settings.get("ENV", "development")));
            if (callId != null) attributes.add(attribute("session.id", Support.hmac(settings.secret("LANGFUSE_SECRET_KEY"), callId.getBytes(StandardCharsets.UTF_8))));
            metadata.forEach((key, value) -> attributes.add(attribute("langfuse.observation.metadata." + key, String.valueOf(value))));
            if (!model.isEmpty()) attributes.add(attribute("langfuse.observation.model.name", model));
            if (name.equals("llm.reply")) attributes.add(attribute("langfuse.observation.model.parameters", new String(Support.json(Map.of("temperature", .3, "max_tokens", settings.number("MAX_OUTPUT_TOKENS", 800, 1, 4096))), StandardCharsets.UTF_8)));
            if (!usage.isEmpty()) attributes.add(attribute("langfuse.observation.usage_details", new String(Support.json(usage), StandardCharsets.UTF_8)));
            if (!errorType.isEmpty()) attributes.add(attribute("langfuse.observation.status_message", errorType));
            Map<String,Object> span = new LinkedHashMap<>(); span.put("traceId", traceId); span.put("spanId", spanId); if (parent != null) span.put("parentSpanId", parent.spanId);
            span.put("name", name); span.put("kind", 1); span.put("startTimeUnixNano", Long.toString(start)); span.put("endTimeUnixNano", Long.toString(Math.max(start, System.currentTimeMillis() * 1_000_000)));
            span.put("attributes", attributes); span.put("status", Map.of("code", errorType.isEmpty() ? 1 : 2)); pending.offer(span);
        }
    }
    void usage(Map<String,Object> data) {
        Span span = current.get(); if (span == null) return;
        Map<String,Object> usage = Support.map(data.get("usage")); Map<String,Object> counts = new LinkedHashMap<>();
        for (var item : Map.of("prompt_tokens", "input", "completion_tokens", "output", "total_tokens", "total").entrySet()) {
            Object value = usage.get(item.getKey()); if (value instanceof Number n && n.longValue() >= 0 && n.doubleValue() == n.longValue()) counts.put(item.getValue(), n.longValue());
        }
        if (!counts.isEmpty()) span.usage = counts;
    }
    private Map<String,Object> attribute(String key, String value) { return Map.of("key", key, "value", Map.of("stringValue", value)); }
    synchronized void flush() {
        if (!enabled) return;
        while (!pending.isEmpty()) {
            List<Map<String,Object>> spans = new ArrayList<>(); pending.drainTo(spans, 64); if (spans.isEmpty()) return;
            var scope = Map.of("scope", Map.of("name", "outbound-java-agent"), "spans", spans);
            var resource = Map.of("resource", Map.of("attributes", List.of(attribute("service.name", settings.get("APP_NAME", "AI-Outbound-Agent")))), "scopeSpans", List.of(scope));
            try {
                var response = client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).header("Content-Type", "application/json").header("Authorization", authorization).header("x-langfuse-ingestion-version", "4").POST(HttpRequest.BodyPublishers.ofByteArray(Support.json(Map.of("resourceSpans", List.of(resource))))).build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() / 100 != 2) return;
            } catch (Exception error) { if (error instanceof InterruptedException) Thread.currentThread().interrupt(); return; }
        }
    }
    @Override public void close() { if (exporter != null) exporter.shutdownNow(); flush(); }
}
