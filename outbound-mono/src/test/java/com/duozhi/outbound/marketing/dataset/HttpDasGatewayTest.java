package com.duozhi.outbound.marketing.dataset;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.common.config.Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

/** Wire-level tests: no real endpoint, key, phone lookup, or supplier billing. */
class HttpDasGatewayTest {
    static final String SECRET = "synthetic-das-secret";
    final ObjectMapper mapper = new ObjectMapper();
    HttpServer server;
    ExecutorService pool;
    final AtomicInteger calls = new AtomicInteger(), redirects = new AtomicInteger();
    final AtomicReference<Map<String, String>> received = new AtomicReference<>();
    volatile int status = 200;
    volatile String reply;
    volatile boolean drop;
    volatile long delay;
    MockEnvironment env;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext(
                "/gateway.do",
                e -> {
                    try {
                        calls.incrementAndGet();
                        Map<String, String> params = parse(e.getRequestURI().getRawQuery());
                        params.putAll(
                                parse(
                                        new String(
                                                e.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8)));
                        received.set(params);
                        if (drop) return;
                        if (delay > 0) Thread.sleep(delay);
                        if (status == 302)
                            e.getResponseHeaders().add("Location", "/redirect-target");
                        byte[] body = reply.getBytes(StandardCharsets.UTF_8);
                        e.sendResponseHeaders(status, body.length);
                        e.getResponseBody().write(body);
                    } catch (Exception expectedOnTimeout) {
                        if (expectedOnTimeout instanceof InterruptedException)
                            Thread.currentThread().interrupt();
                    } finally {
                        e.close();
                    }
                });
        server.createContext(
                "/redirect-target",
                e -> {
                    redirects.incrementAndGet();
                    e.sendResponseHeaders(200, -1);
                    e.close();
                });
        server.start();
        env =
                new MockEnvironment()
                        .withProperty("ENV", "test")
                        .withProperty("DAS_ENABLED", "true")
                        .withProperty("DAS_ALLOW_LOCAL_HTTP", "true")
                        .withProperty(
                                "DAS_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort())
                        .withProperty("DAS_ACCESS_KEY_ID", "synthetic-id")
                        .withProperty("DAS_ACCESS_KEY_SECRET", SECRET)
                        .withProperty("DAS_DATASET_ID", "dataset-test")
                        .withProperty("DAS_PRODUCT_INSTANCE_ID", "product-test");
        reply =
                signed(
                        "{ \"result_code\": \"OK\", \"req_msg_id\": \"upstream-1\", \"data\": \"[]\" }");
    }

    @AfterEach
    void stop() {
        server.stop(0);
        pool.shutdownNow();
    }

    HttpDasGateway gateway() {
        return new HttpDasGateway(new Config(env), mapper);
    }

    static String hmac(String value) throws Exception {
        var mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder()
                .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    static String signed(String response) throws Exception {
        // Sign the exact response text, with outer field order different from the SDK's example.
        return "{\"sign\":\"" + hmac(response) + "\",\"response\":" + response + "}";
    }

    static Map<String, String> parse(String value) {
        Map<String, String> m = new TreeMap<>();
        for (String p : value.split("&")) {
            String[] kv = p.split("=", 2);
            m.put(
                    URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return m;
    }

    static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    @Test
    void directGatewayUsesOfficialSignedFormAndValidatesSignedResponse() throws Exception {
        var result = gateway().query("13800138000", 3, "合同 A+&~/2026", "operation-1");
        assertThat(result).isEqualTo(new DasGateway.Response("OK", "upstream-1", "[]"));
        var p = new TreeMap<>(received.get());
        String signature = p.remove("sign");
        String canonical =
                p.entrySet().stream()
                        .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                        .collect(java.util.stream.Collectors.joining("&"));
        assertThat(signature).isEqualTo(hmac(canonical));
        assertThat(p)
                .containsEntry("method", "antchain.das.application.unifiedentrance.query")
                .containsEntry("version", "1.0")
                .containsEntry("sign_type", "HmacSHA1")
                .containsEntry("file_index", "合同 A+&~/2026")
                .containsEntry("data_set_id", "dataset-test")
                .containsEntry("product_instance_id", "product-test")
                .containsEntry("user_authed", "true");
        assertThat(p.get("req_msg_id")).matches("[0-9a-f]{32}");
        assertThat(mapper.readTree(p.get("params")).path("phone").asText())
                .isEqualTo("13800138000");
        assertThat(mapper.readTree(p.get("params")).path("topN").asText()).isEqualTo("3");
        assertThat(calls).hasValue(1);
    }

    @Test
    void explicitEnableAndLocalHttpGateAreRequiredBeforeAnyNetwork() {
        env.setProperty("DAS_ENABLED", "false");
        assertThat(gateway().configured()).isFalse();
        assertThatThrownBy(() -> gateway().query("13800138000", 3, "auth"))
                .isInstanceOf(IllegalStateException.class);
        env.setProperty("DAS_ENABLED", "true");
        env.setProperty("ENV", "production");
        assertThat(gateway().configured()).isFalse();
        env.setProperty("ENV", "test");
        env.setProperty("DAS_ENDPOINT", "http://example.test");
        assertThat(gateway().configured()).isFalse();
        env.setProperty("DAS_ENDPOINT", "https://openapi.antchain.antgroup.com");
        assertThat(gateway().configured()).isTrue(); // configuration only, never queried
        assertThat(calls).hasValue(0);
    }

    @Test
    void tamperingAndMalformedEnvelopesBecomeUncertainWithoutRetries() throws Exception {
        for (String bad :
                List.of(
                        "{\"response\":{\"result_code\":\"OK\",\"data\":\"[]\"},\"sign\":\"wrong\"}",
                        "{\"response\":{\"result_code\":\"OK\"}}",
                        "not json",
                        "{\"response\":{\"result_code\":\"OK\",\"result_code\":\"10029\"}}")) {
            reply = bad;
            var adapter = new AntDasMatchAdapter(gateway(), new Config(env), mapper);
            var result = adapter.query(new MatchAdapter.Query("+8613800138000", 3, "auth"));
            assertThat(result.state()).isEqualTo("uncertain");
            assertThat(result.message()).doesNotContain(SECRET, "13800138000");
        }
        assertThat(calls).hasValue(4);
    }

    @Test
    void errorRedirectDropAndTimeoutNeverRepeatThePost() throws Exception {
        for (int code : List.of(503, 302)) {
            status = code;
            assertThatThrownBy(() -> gateway().query("13800138000", 3, "auth"))
                    .isInstanceOf(Exception.class);
        }
        assertThat(redirects).hasValue(0);
        status = 200;
        drop = true;
        assertThatThrownBy(() -> gateway().query("13800138000", 3, "auth"))
                .isInstanceOf(Exception.class);
        drop = false;
        delay = 500;
        env.setProperty("DAS_READ_TIMEOUT_MS", "100");
        assertThatThrownBy(() -> gateway().query("13800138000", 3, "auth"))
                .isInstanceOf(Exception.class);
        assertThat(calls).hasValue(4);
    }

    @Test
    void documentedNoDataAndErrorCodesAreReturnedWithoutInventedScores() throws Exception {
        reply = "{\"response\":{\"result_code\":\"10029\",\"req_msg_id\":\"none-1\"}}";
        assertThat(gateway().query("13800138000", 1, "auth"))
                .isEqualTo(new DasGateway.Response("10029", "none-1", null));
        reply = "{\"response\":{\"result_code\":\"10032\"}}";
        assertThat(gateway().query("13800138000", 3, "auth").code()).isEqualTo("10032");
        assertThat(calls).hasValue(2);
    }
}
