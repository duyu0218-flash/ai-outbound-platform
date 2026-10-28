package com.duozhi.outbound.voiceai;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.auth.dao.*;
import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.auth.support.UserRecords;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.voiceai.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BailianTrialIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    static final VoiceAiBackendsTest.Fixture SUITE, ASR;
    static final HttpServer SAFETY;
    static volatile String risk = "none";
    static final java.util.concurrent.atomic.AtomicInteger reviews =
            new java.util.concurrent.atomic.AtomicInteger();

    static {
        try {
            SUITE = new VoiceAiBackendsTest.Fixture("aliyun", "suite");
            ASR = new VoiceAiBackendsTest.Fixture("aliyun", "asr");
            ASR.transcript = "好的，请问您方便了解吗？";
            SAFETY = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SAFETY.createContext(
                    "/",
                    e -> {
                        reviews.incrementAndGet();
                        e.getRequestBody().readAllBytes();
                        byte[] body =
                                Support.json(
                                        Map.of(
                                                "Code",
                                                200,
                                                "RequestId",
                                                "fixture-safety",
                                                "Data",
                                                Map.of(
                                                        "RiskLevel",
                                                        risk,
                                                        "Result",
                                                        List.of(
                                                                Map.of(
                                                                        "Label",
                                                                        risk.equals("none")
                                                                                ? "nonLabel"
                                                                                : "contraband_act")))));
                        e.sendResponseHeaders(200, body.length);
                        e.getResponseBody().write(body);
                        e.close();
                    });
            SAFETY.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.flyway.url", MYSQL::getJdbcUrl);
        p.add("spring.flyway.user", () -> "root");
        p.add("spring.flyway.password", MYSQL::getPassword);
        p.add("JWT_SECRET", () -> "synthetic-bailian-trial-secret-more-than-32");
        p.add("VOICE_AI_CONFIG_MASTER_KEY", () -> Base64.getEncoder().encodeToString(new byte[32]));
        p.add("spring.data.redis.host", () -> "127.0.0.1");
        p.add("spring.data.redis.port", () -> 9);
        p.add("REDIS_URL", () -> "");
        p.add("JAVA_WORKERS_ENABLED", () -> "false");
        p.add("JAVA_DISPATCH_ENABLED", () -> "false");
        p.add("spring.ai.model.chat", () -> "none");
        p.add("RATE_LIMIT_BURST", () -> 2000);
        p.add("RATE_LIMIT_RPS", () -> 1000);
        p.add("DASHSCOPE_API_KEY", () -> "synthetic-bailian-key");
        p.add("VOICE_AI_BAILIAN_WORKSPACE_ID", () -> "workspace-fixture");
        p.add("VOICE_AI_BAILIAN_APP_ID", () -> "app-fixture");
        p.add("VOICE_AI_BAILIAN_TOOL_TARGET", () -> "plugin-fixture");
        p.add("VOICE_AI_BAILIAN_APP_REVISION", () -> "fixture-v1");
        p.add("VOICE_AI_BAILIAN_TOOL_API_KEY", () -> "synthetic-tool-key");
        p.add("VOICE_AI_BAILIAN_URL", SUITE::url);
        p.add("VOICE_AI_ALI_ASR_URL", ASR::url);
        p.add(
                "VOICE_AI_SAFETY_ENDPOINT",
                () -> "http://127.0.0.1:" + SAFETY.getAddress().getPort() + "/");
        p.add("VOICE_AI_SAFETY_ACCESS_KEY_ID", () -> "test-id");
        p.add("VOICE_AI_SAFETY_ACCESS_KEY_SECRET", () -> "test-key");
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    Store store;
    @Autowired
    SysUserMapper users;
    @Autowired
    TenantMapper tenants;
    @Autowired
    Crypto crypto;
    @Autowired
    VoiceAiCredentials credentials;
    @Autowired
    AuthIdentityMapper identities;
    @LocalServerPort
    int port;
    String admin, agent, other;
    long contact;
    static int tenantCounter = 200;
    long tenant;
    String username;

    @BeforeEach
    void setup() {
        tenant = ++tenantCounter;
        for (long id : List.of(tenant, tenant + 1000)) {
            var t = new Tenant();
            t.setId((int) id);
            t.setCode("trial-" + id);
            t.setName("Trial fixture");
            tenants.insert(t);
        }
        username = "bailian-admin-" + tenant;
        admin = user(tenant, "admin", username);
        agent = user(tenant, "agent", "bailian-agent-" + tenant);
        other = user(tenant + 1000, "admin", "bailian-other-" + tenant);
        contact =
                ((Number)
                                store.insert(
                                                "contact",
                                                Values.map(
                                                        "tenant_id",
                                                        tenant,
                                                        "phone",
                                                        "13900000" + tenant,
                                                        "name",
                                                        "百炼测试客户",
                                                        "consent_state",
                                                        "consented"))
                                        .get("id"))
                        .longValue();
        risk = "none";
        SUITE.suiteBlockResponse = false;
    }

    String user(long tenant, String role, String name) {
        var u = new SysUser();
        u.setTenantId((int) tenant);
        u.setRole(role);
        u.setUsername(name);
        u.setFullName(name);
        u.setPasswordHash(crypto.password("SyntheticTrialPass123!"));
        users.insert(u);
        return crypto.token(UserRecords.map(u));
    }

    ResponseEntity<JsonNode> api(HttpMethod method, String path, String token, Object body) {
        var h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    String base() {
        return "/api/v1/admin/voice-ai/bailian";
    }

    Map<String, Object> createBody(String key) {
        return Map.of(
                "client_key",
                key,
                "contact_id",
                contact,
                "script",
                "请询问意向，保存草稿后等待人工确认。",
                "consent",
                true);
    }

    JsonNode create() {
        return create("native");
    }

    JsonNode create(String mode) {
        var body = new LinkedHashMap<>(createBody(UUID.randomUUID().toString()));
        body.put("review_mode", mode);
        var response = api(HttpMethod.POST, base() + "/trials", admin, body);
        assertThat(response.getStatusCode().value()).as(response.toString()).isEqualTo(200);
        return response.getBody();
    }

    static void until(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(20);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    class Client implements WebSocket.Listener, AutoCloseable {
        List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        StringBuilder pending = new StringBuilder();
        WebSocket ws;

        Client(JsonNode created) throws Exception {
            ws =
                    HttpClient.newHttpClient()
                            .newWebSocketBuilder()
                            .buildAsync(
                                    URI.create(
                                            "ws://127.0.0.1:"
                                                    + port
                                                    + "/api/v1/voice-trial/socket"),
                                    this)
                            .get(5, TimeUnit.SECONDS);
            send(
                    Map.of(
                            "type",
                            "attach",
                            "id",
                            created.get("id").asText(),
                            "ticket",
                            created.get("ticket").asText()));
            await("provider_connected");
            await("state");
        }

        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        public CompletionStage<?> onText(WebSocket ws, CharSequence chars, boolean last) {
            pending.append(chars);
            if (last) {
                events.add(Support.object(pending.toString().getBytes(StandardCharsets.UTF_8)));
                pending.setLength(0);
            }
            ws.request(1);
            return null;
        }

        void send(Map<String, Object> body) throws Exception {
            ws.sendText(new String(Support.json(body), StandardCharsets.UTF_8), true)
                    .get(5, TimeUnit.SECONDS);
        }

        Map<String, Object> await(String type) throws Exception {
            until(() -> events.stream().anyMatch(e -> type.equals(e.get("type"))));
            return events.stream()
                    .filter(e -> type.equals(e.get("type")))
                    .reduce((a, b) -> b)
                    .orElseThrow();
        }

        void acknowledge() throws Exception {
            long epoch = ((Number) await("response_done").get("epoch")).longValue();
            send(Map.of("type", "played_start", "epoch", epoch));
            send(Map.of("type", "played_end", "epoch", epoch));
            await("playback_ended");
        }

        public void close() {
            ws.abort();
        }
    }

    Map<String, Object> toolBody(String action) {
        var params =
                Support.map(Support.map(SUITE.suitePayload.get("parameters")).get("biz_params"));
        var body =
                new LinkedHashMap<String, Object>(
                        Support.map(
                                Support.map(params.get("user_defined_params"))
                                        .get("plugin-fixture")));
        body.put("action", action);
        return body;
    }

    ResponseEntity<JsonNode> tool(Map<String, Object> body, String key) {
        var h = new HttpHeaders();
        h.set("X-API-KEY", key);
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
                "/api/v1/webhooks/bailian/trial-tool",
                HttpMethod.POST,
                new HttpEntity<>(body, h),
                JsonNode.class);
    }

    @Test
    void multiTurnAudioToolsConfirmationPersistencePermissionsAndNoDialing() throws Exception {
        assertThat(api(HttpMethod.GET, base() + "/readiness", null, null).getStatusCode().value())
                .isEqualTo(401);
        assertThat(api(HttpMethod.GET, base() + "/readiness", agent, null).getStatusCode().value())
                .isEqualTo(403);
        var body = createBody("idempotent-request");
        var created = api(HttpMethod.POST, base() + "/trials", admin, body).getBody();
        String id = created.get("id").asText();
        assertThat(
                        api(HttpMethod.POST, base() + "/trials", admin, body)
                                .getBody()
                                .get("replayed")
                                .asBoolean())
                .isTrue();
        assertThat(
                        api(HttpMethod.GET, base() + "/trials/" + id, other, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        try (var client = new Client(created)) {
            client.send(Map.of("type", "prompt", "text", "我对服务有兴趣"));
            client.await("audio");
            client.acknowledge();
            var ctx = tool(toolBody("business_context"), "synthetic-tool-key");
            assertThat(ctx.getStatusCode().value()).isEqualTo(200);
            long evidence = ctx.getBody().get("user_evidence").get(0).get("seq").asLong();
            var save = toolBody("save_intent");
            save.putAll(
                    Map.of(
                            "event_id",
                            "result-1",
                            "intent",
                            "interested",
                            "note",
                            "希望了解服务，人工确认后跟进",
                            "evidence_seq",
                            evidence));
            assertThat(tool(save, "bad-key").getStatusCode().value()).isEqualTo(401);
            assertThat(tool(save, "synthetic-tool-key").getBody().get("saved").asBoolean())
                    .isTrue();
            assertThat(tool(save, "synthetic-tool-key").getBody().get("replayed").asBoolean())
                    .isTrue();
            save.put("note", "conflicting");
            assertThat(tool(save, "synthetic-tool-key").getStatusCode().value()).isEqualTo(409);
            client.events.clear();
            client.send(Map.of("type", "begin"));
            client.ws.sendBinary(ByteBuffer.wrap(new byte[3200]), true).join();
            client.send(Map.of("type", "end"));
            client.await("user_text");
            client.acknowledge();
            client.send(Map.of("type", "interrupt"));
            client.await("interrupted");
            client.send(Map.of("type", "close"));
            client.await("closed");
        }
        var detail = api(HttpMethod.GET, base() + "/trials/" + id, admin, null).getBody();
        assertThat(detail.get("status").asText()).isEqualTo("completed");
        assertThat(detail.get("audio").size()).isGreaterThanOrEqualTo(3);
        assertThat(detail.toString())
                .doesNotContain("synthetic-bailian-key", "trial_token", "synthetic-tool-key");
        var result =
                Map.of("confirmed", true, "intent", "interested", "note", "测试意向已确认", "next_at", "");
        assertThat(
                        api(HttpMethod.POST, base() + "/trials/" + id + "/publish", admin, result)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(
                        api(HttpMethod.POST, base() + "/trials/" + id + "/publish", admin, result)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        var lead = api(HttpMethod.GET, "/api/v1/ops/leads/" + contact, admin, null).getBody();
        assertThat(lead.get("stage").asText()).isEqualTo("in_progress");
        assertThat(lead.get("history").toString()).contains("测试意向已确认");
        assertThat(tool(toolBody("business_context"), "synthetic-tool-key").getStatusCode().value())
                .isEqualTo(401);
        assertThat(api(HttpMethod.GET, "/api/v1/calls", admin, null).getBody().size()).isZero();
        assertThat(SUITE.problem.get()).isNull();
        assertThat(ASR.problem.get()).isNull();
    }

    @Test
    void missingCredentialsDoNotStartProviderAndFailedModerationNeverPlays() throws Exception {
        credentials.save(tenant, Map.of("VOICE_AI_BAILIAN_APP_ID", ""));
        int before = SUITE.connections.get();
        assertThat(
                        api(HttpMethod.POST, base() + "/trials", admin, createBody("missing"))
                                .getStatusCode()
                                .value())
                .isEqualTo(503);
        assertThat(SUITE.connections.get()).isEqualTo(before);
        credentials.save(tenant, Map.of("VOICE_AI_BAILIAN_APP_ID", "app-fixture"));
        risk = "high";
        var created = create("secondary_review");
        try (var client = new Client(created)) {
            client.send(Map.of("type", "prompt", "text", "测试审核失败"));
            client.await("closed");
            assertThat(client.events).noneMatch(e -> "audio".equals(e.get("type")));
        }
    }

    @Test
    void logoutRevokesOpenTrial() throws Exception {
        var created = create();
        try (var client = new Client(created)) {
            api(HttpMethod.POST, "/api/v1/auth/logout", admin, Map.of());
            client.await("closed");
            assertThat(
                            tool(toolBody("business_context"), "synthetic-tool-key")
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(401);
        }
    }

    @Test
    void browserEntryMicrophoneMultiTurnHistoryAndLeadWrite() throws Exception {
        Path out = Path.of("target/bailian-browser.log");
        String node =
                System.getenv()
                        .getOrDefault(
                                "NODE_BINARY",
                                System.getProperty("user.home")
                                        + "/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node");
        if (!Files.isExecutable(Path.of(node))) node = "node";
        var builder = new ProcessBuilder(node, "frontend/tests/bailian-browser.mjs");
        builder.environment().put("MONO_BROWSER_URL", "http://127.0.0.1:" + port);
        builder.environment().put("BAILIAN_TEST_USER", username);
        builder.environment().put("BAILIAN_TEST_CONTACT", Long.toString(contact));
        builder.redirectErrorStream(true).redirectOutput(out.toFile());
        var process = builder.start();
        assertThat(process.waitFor(100, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(Files.readString(out)).isZero();
    }

    @Test
    void oneUseTicketInvalidToolEvidenceAndDisconnectedBrowserAreRejected() throws Exception {
        var created = create();
        String id = created.get("id").asText();
        class Probe implements WebSocket.Listener {
            final CompletableFuture<Integer> closed = new CompletableFuture<>();

            public void onOpen(WebSocket ws) {
                ws.request(1);
            }

            public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                closed.complete(status);
                return null;
            }
        }
        try (var client = new Client(created)) {
            var probe = new Probe();
            var replay =
                    HttpClient.newHttpClient()
                            .newWebSocketBuilder()
                            .buildAsync(
                                    URI.create(
                                            "ws://127.0.0.1:"
                                                    + port
                                                    + "/api/v1/voice-trial/socket"),
                                    probe)
                            .get(5, TimeUnit.SECONDS);
            replay.sendText(
                            new String(
                                    Support.json(
                                            Map.of(
                                                    "type",
                                                    "attach",
                                                    "id",
                                                    id,
                                                    "ticket",
                                                    created.get("ticket").asText())),
                                    StandardCharsets.UTF_8),
                            true)
                    .join();
            assertThat(probe.closed.get(5, TimeUnit.SECONDS)).isNotNull();
            var invalid = toolBody("save_intent");
            invalid.putAll(
                    Map.of(
                            "event_id",
                            "missing-evidence",
                            "intent",
                            "interested",
                            "note",
                            "invalid",
                            "evidence_seq",
                            999));
            assertThat(tool(invalid, "synthetic-tool-key").getStatusCode().value()).isEqualTo(400);
            var publish = Map.of("confirmed", true, "intent", "interested", "note", "premature");
            assertThat(
                            api(
                                            HttpMethod.POST,
                                            base() + "/trials/" + id + "/publish",
                                            admin,
                                            publish)
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(409);
            client.send(Map.of("type", "prompt", "text", "合法会话仍可继续"));
            client.await("audio");
            client.acknowledge();
        }
        until(
                () ->
                        "interrupted"
                                .equals(
                                        api(HttpMethod.GET, base() + "/trials/" + id, admin, null)
                                                .getBody()
                                                .get("status")
                                                .asText()));
        assertThat(tool(toolBody("business_context"), "synthetic-tool-key").getStatusCode().value())
                .isEqualTo(401);
        var detail = api(HttpMethod.GET, base() + "/trials/" + id, admin, null).getBody();
        String audio = detail.get("audio").get(0).get("audio_id").asText();
        assertThat(
                        api(
                                        HttpMethod.GET,
                                        base() + "/trials/" + id + "/audio/" + audio,
                                        other,
                                        null)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        var replacement = create();
        api(
                HttpMethod.POST,
                base() + "/trials/" + replacement.get("id").asText() + "/end",
                admin,
                Map.of());
    }

    @Test
    void nativeDefaultBypassesSecondaryServicesAndFreezesModePricesAndMetrics() throws Exception {
        int asrBefore = ASR.connections.get(), reviewBefore = reviews.get();
        risk = "high"; // Native must never call this independently configured moderation fixture.
        var body = new LinkedHashMap<>(createBody("native-default"));
        body.put("prices", Map.of("suite_per_1000_turns", 5.45));
        var created = api(HttpMethod.POST, base() + "/trials", admin, body).getBody();
        String id = created.get("id").asText();
        var changed = new LinkedHashMap<>(body);
        changed.put("review_mode", "secondary_review");
        assertThat(api(HttpMethod.POST, base() + "/trials", admin, changed).getStatusCode().value())
                .isEqualTo(409);
        changed.remove("review_mode");
        changed.put("prices", Map.of("suite_per_1000_turns", 9));
        assertThat(api(HttpMethod.POST, base() + "/trials", admin, changed).getStatusCode().value())
                .isEqualTo(409);
        try (var client = new Client(created)) {
            client.send(Map.of("type", "prompt", "text", "原生试聊"));
            client.await("audio");
            assertThat(client.await("assistant_text").get("reviewed")).isEqualTo(false);
            client.acknowledge();
            client.send(Map.of("type", "close"));
            client.await("closed");
        }
        assertThat(ASR.connections.get()).isEqualTo(asrBefore);
        assertThat(reviews.get()).isEqualTo(reviewBefore);
        var detail = api(HttpMethod.GET, base() + "/trials/" + id, admin, null).getBody();
        var profile =
                Support.object(
                        detail.get("profile_json").asText().getBytes(StandardCharsets.UTF_8));
        assertThat(profile.get("review_mode")).isEqualTo("native");
        assertThat(Support.map(profile.get("safety")).get("enabled")).isEqualTo(false);
        var metrics =
                Support.object(
                        detail.get("metrics_json").asText().getBytes(StandardCharsets.UTF_8));
        assertThat(Support.map(metrics.get("usage")).get("secondary_review_requests")).isEqualTo(0);
        assertThat(Support.map(metrics.get("cost")).get("actual_total")).isNull();
        assertThat(((Number) Support.map(metrics.get("cost")).get("suite_estimate")).doubleValue())
                .isEqualTo(0.00545);
        assertThat(detail.get("audio").size()).isEqualTo(1);
        var downstream =
                Support.map(Support.map(SUITE.suitePayload.get("parameters")).get("downstream"));
        assertThat(downstream.get("intermediate_text")).isEqualTo("transcript,dialog");
        assertThat(downstream.get("incremental_response")).isEqualTo(false);
        assertThat(api(HttpMethod.GET, "/api/v1/calls", admin, null).getBody().size()).isZero();
    }

    @Test
    void optionalReviewRecordsExtraUsageAndSeparateLatency() throws Exception {
        int asrBefore = ASR.connections.get(), reviewBefore = reviews.get();
        var body = new LinkedHashMap<>(createBody("review-comparison"));
        body.put("review_mode", "secondary_review");
        body.put(
                "prices",
                Map.of(
                        "suite_per_1000_turns",
                        5.45,
                        "asr_per_minute",
                        0.0132,
                        "review_per_10000_calls",
                        15));
        var created = api(HttpMethod.POST, base() + "/trials", admin, body).getBody();
        String id = created.get("id").asText();
        try (var client = new Client(created)) {
            client.send(Map.of("type", "prompt", "text", "二次审核试聊"));
            client.await("audio");
            assertThat(client.await("assistant_text").get("reviewed")).isEqualTo(true);
            client.acknowledge();
            client.send(Map.of("type", "close"));
            client.await("closed");
        }
        assertThat(ASR.connections.get()).isGreaterThan(asrBefore);
        assertThat(reviews.get()).isGreaterThan(reviewBefore);
        var detail = api(HttpMethod.GET, base() + "/trials/" + id, admin, null).getBody();
        var metrics =
                Support.object(
                        detail.get("metrics_json").asText().getBytes(StandardCharsets.UTF_8));
        var usage = Support.map(metrics.get("usage"));
        assertThat(((Number) usage.get("secondary_asr_audio_seconds")).doubleValue())
                .isGreaterThan(0);
        assertThat(((Number) usage.get("secondary_review_requests")).intValue()).isGreaterThan(0);
        var cost = Support.map(metrics.get("cost"));
        assertThat(((Number) cost.get("secondary_asr_estimate")).doubleValue()).isGreaterThan(0);
        assertThat(((Number) cost.get("secondary_review_estimate")).doubleValue()).isGreaterThan(0);
        var turn = Support.map(((List<?>) metrics.get("turns")).getFirst());
        assertThat(((Number) turn.get("provider_first_audio_ms")).doubleValue())
                .isGreaterThanOrEqualTo(0);
        assertThat(((Number) turn.get("platform_release_wait_ms")).doubleValue())
                .isGreaterThanOrEqualTo(0);
        assertThat(((Number) turn.get("browser_playback_ack_ms")).doubleValue())
                .isGreaterThanOrEqualTo(
                        ((Number) turn.get("provider_first_audio_ms")).doubleValue());
    }

    @AfterAll
    static void closeFixtures() throws Exception {
        SUITE.close();
        ASR.close();
        SAFETY.stop(0);
    }
}
