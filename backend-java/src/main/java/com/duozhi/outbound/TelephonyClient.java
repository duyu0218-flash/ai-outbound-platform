package com.duozhi.outbound;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TelephonyClient {
    public static class Failure extends RuntimeException {
        public final int status;
        public final boolean rejectedBeforeAdmission;
        public Failure(int status, boolean rejectedBeforeAdmission) {
            super("telephony command failed (" + status + ")"); this.status = status; this.rejectedBeforeAdmission = rejectedBeforeAdmission;
        }
        public boolean definitive() { return Values.set(400, 401, 403, 404, 422, 429).contains(status); }
    }
    private final Store store;
    private final Config config;
    private final JsonSupport json;
    public TelephonyClient(Store store, Config config, JsonSupport json) { this.store = store; this.config = config; this.json = json; }

    public boolean mock(Map<String, Object> call) { return "mock".equals(route(call).get("provider")); }
    public void validate(Map<String, Object> call) {
        if (!mock(call) && config.get("VOICE_COMMAND_SECRET", "").isEmpty()) throw new ApiError(503, "real telephony requires a signing secret");
    }
    private Map<String, Object> route(Map<String, Object> call) {
        Map<String, Object> line = call.get("telephony_line_id") == null ? store.one("telephonyline", "WHERE tenant_id=? AND enabled=? ORDER BY priority,created_at,id LIMIT 1", CallLifecycle.tenant(call), true)
                : store.require("telephonyline", CallLifecycle.tenant(call), call.get("telephony_line_id"));
        String provider = line == null ? config.get("TELEPHONY_PROVIDER", "mock").trim().toLowerCase(Locale.ROOT) : line.get("provider").toString().trim().toLowerCase(Locale.ROOT);
        String endpoint = line == null ? config.get("TELEPHONY_PROVIDER_ENDPOINT", config.get("SIP_PROVIDER_ENDPOINT", "")) : Objects.toString(line.get("gateway_url"), "");
        String credential = line == null ? "" : Objects.toString(line.get("credential_ref"), "");
        String bearer = line == null || call.get("gateway_node_id") != null ? config.get("TELEPHONY_SERVICE_TOKEN", "") : "";
        if (call.get("gateway_node_id") != null) { provider = "http"; endpoint = Objects.toString(call.get("gateway_endpoint"), ""); }
        if (provider.equals("tenant")) throw new ApiError(503, "no enabled telephony line configured for tenant");
        if (!provider.equals("mock")) Settings.endpoint(endpoint, false);
        if (!credential.trim().isEmpty()) {
            String ref = credential.trim().replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
            bearer = config.get("TELEPHONY_SECRET_" + ref, "");
            if (bearer.isEmpty()) throw new ApiError(503, "telephony credential reference unavailable");
        }
        return JsonSupport.map("provider", provider, "endpoint", endpoint.replaceAll("/+$", ""), "bearer", bearer);
    }
    public Map<String, Object> command(Map<String, Object> call, String action, Map<String, Object> fields) {
        TaskWorker.permitted();
        Map<String, Object> route = route(call);
        if ("mock".equals(route.get("provider"))) {
            if ("speak".equals(action)) return JsonSupport.map("playback_complete", true);
            if ("hangup".equals(action)) return JsonSupport.map("ended", true, "provider_call_id", "mock-" + call.get("id"));
            return JsonSupport.map("result", "accepted", "provider_call_id", "mock-" + call.get("id"), "state", "accepted");
        }
        String secret = config.get("VOICE_COMMAND_SECRET", "");
        if (secret.isEmpty()) throw new ApiError(503, "real telephony requires a signing secret");
        String path = "/v1/call/" + action;
        Map<String, Object> payload = new LinkedHashMap<>(fields);
        payload.put("call_id", call.get("id")); payload.put("tenant_id", call.get("tenant_id"));
        if (!"dial".equals(action)) { payload.put("expected_attempt", call.get("attempts")); payload.put("provider_call_id", call.get("telephony_call_id")); }
        byte[] body = json.canonical(payload, false).getBytes(StandardCharsets.UTF_8);
        String stamp = Long.toString(Instant.now().getEpochSecond()), nonce = UUID.randomUUID().toString().replace("-", "");
        String signature = JsonSupport.hmac(secret, (stamp + "." + nonce + "." + path + "." + new String(body, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-voice-timestamp", stamp); headers.put("x-voice-nonce", nonce); headers.put("x-voice-signature", signature);
        if (!route.get("bearer").toString().isEmpty()) headers.put("Authorization", "Bearer " + route.get("bearer"));
        return post(route.get("endpoint") + path, body, headers, Math.max(1, config.number("TELEPHONY_TIMEOUT_SEC", 8)) * 1000);
    }
    public Map<String, Object> post(String endpoint, byte[] body, Map<String, String> headers, int timeoutMs) {
        return post(endpoint, body, headers, timeoutMs, false);
    }
    public Map<String, Object> post(String endpoint, byte[] body, Map<String, String> headers, int timeoutMs, boolean allowEmpty) {
        return request(endpoint, body, headers, timeoutMs, allowEmpty);
    }
    public Map<String, Object> get(String endpoint, Map<String, String> headers, int timeoutMs) {
        return request(endpoint, null, headers, timeoutMs, false);
    }
    private Map<String, Object> request(String endpoint, byte[] body, Map<String, String> headers, int timeoutMs, boolean allowEmpty) {
        HttpURLConnection connection = null;
        try {
            Settings.endpoint(endpoint, false); // Reject non-HTTP schemes and URL credentials at the transport boundary.
            connection = (HttpURLConnection)new URL(endpoint).openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(timeoutMs); connection.setReadTimeout(timeoutMs);
            connection.setRequestMethod(body == null ? "GET" : "POST");
            if (body != null) { connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length); }
            connection.setRequestProperty("Content-Type", "application/json");
            for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
            if (body != null) try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new Failure(status, status == 429 && "false".equals(connection.getHeaderField("X-Voice-Dial-Admitted")));
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int size;
                while ((size = in.read(buffer)) != -1) { if (out.size() + size > 1048576) throw new Failure(0, false); out.write(buffer, 0, size); }
                if (allowEmpty && out.size() == 0) return Collections.emptyMap();
                return json.object(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (IOException | ApiError e) { throw new Failure(0, false); }
        finally { if (connection != null) connection.disconnect(); }
    }
}
