package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.common.config.Config;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Direct AntChain OpenAPI transport, following official openapi-das 1.1.123 and antchain-util 0.1.9
 * wire/signature contracts. Uses the existing JDK/Jackson stack; the SDK itself transitively
 * requires fastjson, which this project prohibits. No redirect, retry loop, or replayable POST
 * body. Success replies must be signed.
 */
@Component
public class HttpDasGateway implements DasGateway {
    static final String DEFAULT_ENDPOINT = "https://openapi.antchain.antgroup.com";
    private final Config config;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public HttpDasGateway(Config config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
    }

    private String value(String key, String fallback) {
        return config.get(key, fallback).trim();
    }

    private URI endpoint() {
        URI uri = URI.create(value("DAS_ENDPOINT", DEFAULT_ENDPOINT));
        if (uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("invalid DAS endpoint");
        boolean local = Set.of("127.0.0.1", "[::1]", "::1").contains(uri.getHost());
        boolean testHttp =
                "test".equals(value("ENV", ""))
                        && !config.production()
                        && config.flag("DAS_ALLOW_LOCAL_HTTP", false)
                        && local
                        && "http".equals(uri.getScheme());
        if (!"https".equals(uri.getScheme()) && !testHttp)
            throw new IllegalArgumentException("DAS requires HTTPS");
        return uri.resolve("/gateway.do");
    }

    @Override
    public boolean configured() {
        if (!config.flag("DAS_ENABLED", false)
                || value("DAS_ACCESS_KEY_ID", "").isEmpty()
                || value("DAS_ACCESS_KEY_SECRET", "").isEmpty()
                || value("DAS_DATASET_ID", "").isEmpty()
                || value("DAS_PRODUCT_INSTANCE_ID", "").isEmpty()) return false;
        try {
            endpoint();
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    @Override
    public Response query(String phone, int topN, String authorizationRef) throws Exception {
        return query(phone, topN, authorizationRef, null);
    }

    @Override
    public Response query(String phone, int topN, String authorizationRef, String operationId)
            throws Exception {
        if (!configured()) throw new IllegalStateException("DAS is not configured or enabled");
        if (phone == null
                || !phone.matches("1[3-9][0-9]{9}")
                || topN < 1
                || topN > 3
                || authorizationRef == null
                || authorizationRef.isBlank())
            throw new IllegalArgumentException("invalid DAS query");
        String secret = value("DAS_ACCESS_KEY_SECRET", "");
        Map<String, String> query = new TreeMap<>();
        query.put("method", "antchain.das.application.unifiedentrance.query");
        query.put("version", "1.0");
        query.put("sign_type", "HmacSHA1");
        query.put("req_time", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        query.put(
                "req_msg_id",
                operationId == null
                        ? UUID.randomUUID().toString().replace("-", "")
                        : HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(
                                                        operationId.getBytes(
                                                                StandardCharsets.UTF_8)))
                                .substring(0, 32));
        query.put("access_key", value("DAS_ACCESS_KEY_ID", ""));
        query.put("base_sdk_version", "TeaSDK-2.0");
        query.put("sdk_version", "1.1.123");
        query.put("_prod_code", "DAS");
        query.put("_prod_channel", "undefined");
        Map<String, String> body = new TreeMap<>();
        body.put("product_instance_id", value("DAS_PRODUCT_INSTANCE_ID", ""));
        body.put("data_set_id", value("DAS_DATASET_ID", ""));
        body.put("user_authed", "true");
        body.put("file_index", authorizationRef);
        body.put(
                "params",
                mapper.writeValueAsString(Map.of("phone", phone, "topN", String.valueOf(topN))));
        Map<String, String> signed = new TreeMap<>(query);
        signed.putAll(body);
        query.put("sign", sign(form(signed), secret));
        URI target = URI.create(endpoint() + "?" + form(query));
        HttpRequest request =
                HttpRequest.newBuilder(target)
                        .timeout(
                                Duration.ofMillis(
                                        Math.max(
                                                100,
                                                Math.min(
                                                        15000,
                                                        config.number(
                                                                "DAS_READ_TIMEOUT_MS", 15000)))))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(once(form(body)))
                        .build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new DasException("", "DAS HTTP failure");
        String raw = response.body();
        if (raw == null || raw.length() > 1_048_576)
            throw new DasException("", "invalid DAS reply");
        JsonNode envelope = mapper.readTree(raw), result = envelope.path("response");
        if (!result.isObject() || !result.path("result_code").isTextual())
            throw new DasException("", "invalid DAS reply");
        String code = result.path("result_code").asText().toUpperCase(Locale.ROOT);
        JsonNode signature = envelope.get("sign");
        if ("OK".equals(code) || signature != null) {
            if (signature == null
                    || !signature.isTextual()
                    || !MessageDigest.isEqual(
                            sign(responseText(raw), secret).getBytes(StandardCharsets.US_ASCII),
                            signature.asText().getBytes(StandardCharsets.US_ASCII)))
                throw new DasException("", "invalid DAS signature");
        }
        JsonNode data = result.get("data");
        return new Response(
                code,
                result.path("req_msg_id").asText(""),
                data == null || data.isNull()
                        ? null
                        : data.isTextual() ? data.asText() : data.toString());
    }

    private String responseText(String raw) throws Exception {
        try (JsonParser p = mapper.createParser(raw)) {
            if (p.nextToken() != JsonToken.START_OBJECT)
                throw new DasException("", "invalid envelope");
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String name = p.currentName();
                p.nextToken();
                if ("response".equals(name)) {
                    int start = (int) p.currentTokenLocation().getCharOffset();
                    p.skipChildren();
                    return raw.substring(start, (int) p.currentLocation().getCharOffset());
                }
                p.skipChildren();
            }
        }
        throw new DasException("", "missing response");
    }

    static String sign(String content, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder()
                .encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }

    static String form(Map<String, String> values) {
        return new TreeMap<>(values)
                .entrySet().stream()
                        .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                        .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    /** Prevent even a transport-level retry from transmitting this billed body a second time. */
    private static HttpRequest.BodyPublisher once(String body) {
        var delegate = HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        var subscribed = new AtomicBoolean();
        return new HttpRequest.BodyPublisher() {
            public long contentLength() {
                return delegate.contentLength();
            }

            public void subscribe(Flow.Subscriber<? super java.nio.ByteBuffer> subscriber) {
                if (subscribed.compareAndSet(false, true)) delegate.subscribe(subscriber);
                else {
                    subscriber.onSubscribe(
                            new Flow.Subscription() {
                                public void request(long n) {}

                                public void cancel() {}
                            });
                    subscriber.onError(
                            new IllegalStateException("DAS request body cannot be replayed"));
                }
            }
        };
    }
}
