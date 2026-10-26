package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.support.Support;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Independent semantic moderation. Only an explicit risk-free result can authorize audio. */
public final class ContentSafety {
    public static final class Failure extends IOException {
        public final String code;

        public Failure(String code) {
            super(code);
            this.code = code;
        }
    }

    @FunctionalInterface
    public interface Reviewer {
        Map<String, Object> review(String text, ResponseScope scope) throws Exception;
    }

    private final Settings settings;
    private final HttpClient http = Support.client();
    private final Semaphore capacity;

    public ContentSafety(Settings settings) {
        this.settings = settings;
        capacity =
                new Semaphore(
                        (int) settings.number("VOICE_AI_SAFETY_MAX_CONCURRENT", 64, 1, 10000));
    }

    public static Map<String, Object> policy(Settings settings) {
        return Map.of(
                "provider",
                "aliyun-green-plus",
                "endpoint",
                settings.get(
                        "VOICE_AI_SAFETY_ENDPOINT", "https://green-cip.cn-shanghai.aliyuncs.com/"),
                "region",
                settings.get("VOICE_AI_SAFETY_REGION", "cn-shanghai"),
                "timeout_ms",
                settings.number("VOICE_AI_SAFETY_TIMEOUT_MS", 1200, 100, 5000),
                "max_pending_ms",
                settings.number("VOICE_AI_SAFETY_MAX_PENDING_MS", 6000, 1000, 10000),
                "revision",
                settings.get("VOICE_AI_SAFETY_POLICY_REVISION", "sentence-v1"));
    }

    public static String withContext(List<String> history, String output) {
        String context = String.join("\n", history);
        if (context.length() > 2000) context = context.substring(context.length() - 2000);
        return context.isBlank() ? output : "已确认对话上下文：\n" + context + "\n当前助手待播回复：\n" + output;
    }

    public void requireReady(Map<String, Object> policy) throws Failure {
        endpoint(policy);
        if (settings.secret("VOICE_AI_SAFETY_ACCESS_KEY_ID").isBlank()
                || settings.secret("VOICE_AI_SAFETY_ACCESS_KEY_SECRET").isBlank())
            throw new Failure("S2S_SAFETY_NOT_CONFIGURED");
    }

    private URI endpoint(Map<String, Object> policy) throws Failure {
        try {
            URI uri = URI.create(Support.str(policy, "endpoint", ""));
            String region = Support.str(policy, "region", "");
            boolean official =
                    Set.of("cn-shanghai", "cn-beijing", "cn-hangzhou").contains(region)
                            && Set.of(
                                            "green-cip." + region + ".aliyuncs.com",
                                            "green-cip-vpc." + region + ".aliyuncs.com")
                                    .contains(uri.getHost())
                            && "https".equals(uri.getScheme())
                            && (uri.getPort() == -1 || uri.getPort() == 443);
            boolean local =
                    !settings.production()
                            && "http".equals(uri.getScheme())
                            && Set.of("127.0.0.1", "localhost").contains(uri.getHost());
            if ((!official && !local)
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !Set.of("", "/").contains(uri.getPath())
                    || !"aliyun-green-plus".equals(policy.get("provider")))
                throw new IllegalArgumentException();
            return uri;
        } catch (Exception invalid) {
            throw new Failure("S2S_SAFETY_ENDPOINT_INVALID");
        }
    }

    public Map<String, Object> review(Map<String, Object> policy, String text, ResponseScope scope)
            throws Exception {
        scope.check();
        requireReady(policy);
        if (text.isBlank() || text.length() > 5000) throw new Failure("S2S_SAFETY_TEXT_INVALID");
        if (!capacity.tryAcquire()) throw new Failure("S2S_SAFETY_OVERLOADED");
        try {
            int timeout = (int) Support.integer(policy, "timeout_ms", 1200);
            if (timeout < 100 || timeout > 5000) throw new Failure("S2S_SAFETY_POLICY_INVALID");
            var params = new TreeMap<String, String>();
            params.put("AccessKeyId", settings.secret("VOICE_AI_SAFETY_ACCESS_KEY_ID"));
            params.put("Action", "TextModerationPlus");
            params.put("Format", "JSON");
            params.put("Version", "2022-03-02");
            params.put("RegionId", Support.str(policy, "region", ""));
            params.put(
                    "Timestamp",
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                            .withZone(ZoneOffset.UTC)
                            .format(Instant.now()));
            params.put("SignatureMethod", "HMAC-SHA1");
            params.put("SignatureVersion", "1.0");
            params.put("SignatureNonce", UUID.randomUUID().toString());
            params.put("Service", "llm_response_moderation");
            params.put(
                    "ServiceParameters",
                    new String(Support.json(Map.of("content", text)), StandardCharsets.UTF_8));
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(
                    new SecretKeySpec(
                            (settings.secret("VOICE_AI_SAFETY_ACCESS_KEY_SECRET") + "&")
                                    .getBytes(StandardCharsets.UTF_8),
                            "HmacSHA1"));
            params.put(
                    "Signature",
                    Base64.getEncoder()
                            .encodeToString(
                                    mac.doFinal(
                                            ("POST&%2F&" + encode(form(params)))
                                                    .getBytes(StandardCharsets.UTF_8))));
            var request =
                    HttpRequest.newBuilder(endpoint(policy))
                            .timeout(Duration.ofMillis(timeout))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                            .build();
            var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            scope.own(() -> pending.cancel(true));
            pending.thenAccept(
                    response -> {
                        if (scope.cancelled())
                            try {
                                response.body().close();
                            } catch (IOException ignored) {
                            }
                    });
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            try {
                var response = pending.get(timeout, TimeUnit.MILLISECONDS);
                try (var input = scope.own(response.body())) {
                    if (response.statusCode() != 200) throw new Failure("S2S_SAFETY_UNAVAILABLE");
                    var body = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    for (; ; ) {
                        long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                        if (left < 1) throw new Failure("S2S_SAFETY_TIMEOUT");
                        int n = Support.timed(input, left).read(buffer);
                        if (n < 0) break;
                        body.write(buffer, 0, n);
                        if (body.size() > 32768) throw new Failure("S2S_SAFETY_RESULT_INVALID");
                    }
                    byte[] bytes = body.toByteArray();
                    if (bytes.length > 32768 || System.nanoTime() > deadline)
                        throw new Failure("S2S_SAFETY_TIMEOUT");
                    var result = Support.object(bytes);
                    if (Support.integer(result, "Code", -1) != 200)
                        throw new Failure("S2S_SAFETY_UNAVAILABLE");
                    var data = Support.map(result.get("Data"));
                    if (!Set.of("none", "low", "medium", "high")
                            .contains(Support.str(data, "RiskLevel", "")))
                        throw new Failure("S2S_SAFETY_RESULT_INVALID");
                    if (!"none".equals(data.get("RiskLevel")))
                        throw new Failure("S2S_CONTENT_BLOCKED");
                    if (!(data.get("Result") instanceof List<?> labels)
                            || labels.isEmpty()
                            || labels.stream()
                                    .anyMatch(v -> !"nonLabel".equals(Support.map(v).get("Label"))))
                        throw new Failure("S2S_SAFETY_RESULT_INVALID");
                    String id = Support.str(result, "RequestId", "");
                    if (id.isBlank() || id.length() > 128)
                        throw new Failure("S2S_SAFETY_RESULT_INVALID");
                    scope.check();
                    return Map.of(
                            "provider",
                            "aliyun-green-plus",
                            "request_id",
                            id,
                            "risk_level",
                            "none",
                            "policy_revision",
                            Support.str(policy, "revision", ""));
                }
            } catch (Failure blocked) {
                throw blocked;
            } catch (Exception unavailable) {
                scope.check();
                throw new Failure("S2S_SAFETY_UNAVAILABLE");
            } finally {
                pending.cancel(true);
            }
        } finally {
            capacity.release();
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String form(Map<String, String> params) {
        var out = new StringJoiner("&");
        params.forEach((k, v) -> out.add(encode(k) + "=" + encode(v)));
        return out.toString();
    }
}
