package com.duozhi.outbound.integration.service;

import static org.junit.jupiter.api.Assertions.*;

import com.duozhi.outbound.ai.service.AccountQuota;
import com.duozhi.outbound.ai.service.AgentPolicy;
import com.duozhi.outbound.ai.service.AgentTracing;
import com.duozhi.outbound.ai.service.EmbeddedAgent;
import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.recording.service.RecordingStorage;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ServicesTest {
    @TempDir Path directory;
    private HttpServer server;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final AtomicBoolean cleanup = new AtomicBoolean();
    private final List<byte[]> received = new CopyOnWriteArrayList<>();
    private static final byte[] AUDIO = "RIFF-local-audio-data".getBytes(StandardCharsets.UTF_8);
    private String origin;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] request = exchange.getRequestBody().readAllBytes();
                    String path = exchange.getRequestURI().getPath(),
                            method = exchange.getRequestMethod();
                    byte[] body = new byte[0];
                    int status = 200;
                    if (path.startsWith("/bucket")) {
                        String auth = exchange.getRequestHeaders().getFirst("Authorization");
                        if (auth == null
                                || !auth.startsWith("AWS4-HMAC-SHA256 Credential=local-access/"))
                            status = 403;
                        else if (method.equals("PUT")) objects.put(path, request);
                        else if (method.equals("DELETE")) {
                            objects.remove(path);
                            status = 204;
                        } else if (method.equals("GET")) {
                            body = objects.get(path);
                            if (body == null) {
                                status = 404;
                                body = new byte[0];
                            }
                        }
                    } else if (path.equals("/v1/recordings/test.wav")) {
                        body = AUDIO;
                        exchange.getResponseHeaders().add("Content-Type", "audio/wav");
                    } else if (path.equals("/v1/recording-cleanup/test.wav")) {
                        var data = Support.object(request);
                        if (!"Bearer independent-cleanup-token-00000000000"
                                        .equals(
                                                exchange.getRequestHeaders()
                                                        .getFirst("Authorization"))
                                || !Support.hex(Support.sha(AUDIO))
                                        .equals(data.get("checksum_sha256"))) status = 403;
                        else {
                            cleanup.set(true);
                            body = Support.json(Map.of("deleted", true));
                        }
                    } else if (path.equals("/v1/chat/completions")) {
                        received.add(request);
                        body =
                                Support.json(
                                        Map.of(
                                                "id",
                                                "services-test",
                                                "object",
                                                "chat.completion",
                                                "created",
                                                1700000000,
                                                "model",
                                                "fixture-model",
                                                "choices",
                                                List.of(
                                                        Map.of(
                                                                "index",
                                                                0,
                                                                "finish_reason",
                                                                "stop",
                                                                "message",
                                                                Map.of(
                                                                        "role",
                                                                        "assistant",
                                                                        "content",
                                                                        "approved reply")))));
                    } else if (path.equals("/api/public/otel/v1/traces")) {
                        received.add(request);
                        body = "{}".getBytes(StandardCharsets.UTF_8);
                    } else status = 404;
                    if (method.equals("HEAD")) exchange.sendResponseHeaders(status, -1);
                    else {
                        exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
                        if (status != 204) exchange.getResponseBody().write(body);
                    }
                    exchange.close();
                });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Settings recording(Map<String, String> extra) {
        var values =
                new HashMap<>(
                        Map.of(
                                "S3_ENDPOINT_URL",
                                origin,
                                "S3_BUCKET",
                                "bucket",
                                "S3_ACCESS_KEY_ID",
                                "local-access",
                                "S3_SECRET_ACCESS_KEY",
                                "local-secret",
                                "RECORDING_SOURCE_ALLOWED_HOSTS",
                                "127.0.0.1",
                                "RECORDING_SPOOL_DIR",
                                directory.toString(),
                                "RECORDING_MAX_BYTES",
                                "1024",
                                "RECORDING_SPOOL_RESERVE_BYTES",
                                "0"));
        values.putAll(extra);
        return new Settings(values);
    }

    private Map<String, Object> recordingRequest() {
        return new HashMap<>(
                Map.of(
                        "tenant_id",
                        1,
                        "recording_asset_id",
                        7,
                        "call_id",
                        "call-a",
                        "provider_url",
                        origin + "/v1/recordings/test.wav"));
    }

    @Test
    void uploadVerifyCleanupAndRestartTombstone() throws Exception {
        var config =
                recording(
                        Map.of(
                                "RECORDING_SOURCE_CLEANUP_ORIGIN",
                                origin,
                                "RECORDING_SOURCE_CLEANUP_TOKEN",
                                "independent-cleanup-token-00000000000"));
        var storage = new RecordingStorage(config);
        storage.initialize();
        assertTrue(storage.ready());
        var payload = recordingRequest();
        var result = storage.ingest(payload);
        assertEquals(
                "s3://bucket/recordings/tenant-1/call-call-a/asset-7.wav",
                result.get("storage_uri"));
        assertEquals(Support.hex(Support.sha(AUDIO)), result.get("checksum_sha256"));
        assertEquals((long) AUDIO.length, result.get("size_bytes"));
        assertTrue(cleanup.get());
        assertArrayEquals(Support.json(result), Support.json(storage.ingest(payload)));
        payload.put("storage_uri", result.get("storage_uri"));
        assertTrue(storage.delete(payload));
        assertTrue(objects.isEmpty());
        var restarted = new RecordingStorage(config);
        assertEquals(
                503,
                assertThrows(Support.Failure.class, () -> restarted.ingest(recordingRequest()))
                        .status);
    }

    @Test
    void rejectSourceCredentialsRedirectAndCrossTenantDelete() throws Exception {
        var storage = new RecordingStorage(recording(Map.of()));
        assertThrows(
                Support.Failure.class,
                () -> storage.source("http://user:secret@127.0.0.1/source.wav"));
        assertThrows(Support.Failure.class, () -> storage.source("http://localhost/source.wav"));
        var payload = recordingRequest();
        payload.put("storage_uri", "s3://bucket/recordings/tenant-2/private.wav");
        assertEquals(
                503, assertThrows(Support.Failure.class, () -> storage.delete(payload)).status);
    }

    @Test
    void cleanupMismatchRetainsSource() throws Exception {
        var storage =
                new RecordingStorage(
                        recording(
                                Map.of(
                                        "RECORDING_SOURCE_CLEANUP_ORIGIN",
                                        origin,
                                        "RECORDING_SOURCE_CLEANUP_TOKEN",
                                        "independent-cleanup-token-00000000000")));
        var result = storage.ingest(recordingRequest());
        cleanup.set(false);
        objects.replaceAll((key, value) -> new byte[] {1});
        assertThrows(Support.Failure.class, () -> storage.ingest(recordingRequest()));
        assertFalse(cleanup.get());
        assertNotNull(result.get("storage_uri"));
    }

    @Test
    void negativeHandoffAndLanguageCompatibility() {
        var policy = new AgentPolicy(new Settings(Map.of()));
        assertFalse(AgentPolicy.handoff("不用转人工", policy.keywords("zh-CN")));
        assertFalse(AgentPolicy.handoff("I don't need a human agent", policy.keywords("en")));
        assertTrue(AgentPolicy.handoff("请帮我转人工", policy.keywords("zh-CN")));
        var result =
                policy.turn(
                        Map.of(
                                "call_id",
                                "a",
                                "phone",
                                "123456",
                                "mode",
                                "ai_with_sms",
                                "transcript",
                                "urgent human",
                                "context",
                                Map.of("language", "en")),
                        false);
        assertEquals("handoff", result.get("action"));
        assertNull(result.get("hangup_sms"));
        assertEquals(2, result.get("escalate_priority"));
    }

    @Test
    void llmApprovalRedactionHistoryAndActualHttp() throws Exception {
        var config =
                new Settings(
                        LedgerTestDatabase.settings(
                                Map.of(
                                        "SERVICE_TOKEN",
                                        "service-token",
                                        "OPENAI_BASE_URL",
                                        origin + "/v1",
                                        "OPENAI_API_KEY",
                                        "test-key",
                                        "LLM_ALLOWED_HOSTS",
                                        "127.0.0.1",
                                        "LLM_PROVIDER",
                                        "openai-compatible")));
        var controller = new EmbeddedAgent(config);
        try {
            var request =
                    new HashMap<String, Object>(
                            Map.of(
                                    "call_id",
                                    "private-call",
                                    "phone",
                                    "13800138000",
                                    "mode",
                                    "ai_only",
                                    "transcript",
                                    "email test@example.com phone 13800138000",
                                    "context",
                                    Map.of()));
            assertEquals(
                    409,
                    assertThrows(
                                    Support.Failure.class,
                                    () -> controller.turn(request, "Bearer service-token"))
                            .status);
            assertTrue(received.isEmpty());
            request.put("context", Map.of("external_llm_enabled", true));
            assertEquals(
                    "approved reply",
                    controller.turn(request, "Bearer service-token").get("tts_text"));
            String sent = new String(received.get(0), StandardCharsets.UTF_8);
            assertFalse(sent.contains("13800138000"));
            assertFalse(sent.contains("test@example.com"));
            assertFalse(sent.contains("private-call"));
            assertTrue(sent.contains("REDACTED"));
        } finally {
            controller.close();
        }
    }

    @Test
    void unconfiguredQuotaPreservesDisabledWindowAndConnectionLimit() throws Exception {
        var values =
                LedgerTestDatabase.settings(
                        Map.of(
                                "LLM_QUOTA_SCOPE",
                                "disabled-account",
                                "LLM_QUOTA_RPM",
                                "1",
                                "LLM_QUOTA_TPM",
                                "1",
                                "LLM_QUOTA_RPS",
                                "1",
                                "LLM_MAX_CONNECTIONS",
                                "1"));
        try (var quota = new AccountQuota(new Settings(values))) {
            assertEquals(0, quota.reserve(100, 1000, true));
            assertEquals(0, quota.reserve(100, 1000, true));
            quota.block(60);
            assertEquals(0, quota.reserve(100, System.currentTimeMillis() / 1000.0, true));
            quota.acquire(100);
            assertEquals(429, assertThrows(Support.Failure.class, () -> quota.acquire(100)).status);
            quota.release();
            assertEquals(0, quota.inflight());
        }
        try (var db =
                        java.sql.DriverManager.getConnection(
                                values.get("MYSQL_JDBC_URL"),
                                values.get("MYSQL_USER"),
                                values.get("MYSQL_PASSWORD"));
                var q = db.createStatement();
                var rows =
                        q.executeQuery(
                                "SELECT COUNT(*) FROM voice_model_budgets WHERE"
                                        + " scope='disabled-account'")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void sharedQuotaCommitsSurviveRestartAndRejectConfigDrift() throws Exception {
        var values =
                LedgerTestDatabase.settings(
                        Map.of(
                                "LLM_QUOTA_ENABLED",
                                "true",
                                "LLM_QUOTA_SCOPE",
                                "account",
                                "LLM_QUOTA_RPS",
                                "2",
                                "LLM_QUOTA_RPM",
                                "2",
                                "LLM_QUOTA_TPM",
                                "100"));
        try (var first = new AccountQuota(new Settings(values));
                var second = new AccountQuota(new Settings(values))) {
            assertEquals(0, first.reserve(40, 1000, true));
            assertEquals(0, second.reserve(40, 1000, true));
            assertEquals(1, first.reserve(1, 1000, true));
        }
        try (var restarted = new AccountQuota(new Settings(values))) {
            assertEquals(1, restarted.reserve(1, 1001, true));
            assertEquals(0, restarted.reserve(1, 1061, true));
        }
        var changed = new HashMap<>(values);
        changed.put("LLM_QUOTA_RPM", "3");
        assertThrows(IllegalArgumentException.class, () -> new AccountQuota(new Settings(changed)));
    }

    @Test
    void quotaHasOneBudgetAcrossConcurrentInstances() throws Exception {
        var settings =
                new Settings(
                        LedgerTestDatabase.settings(
                                Map.of(
                                        "LLM_QUOTA_ENABLED",
                                        "true",
                                        "LLM_QUOTA_RPM",
                                        "20",
                                        "LLM_QUOTA_RPS",
                                        "20")));
        try (var first = new AccountQuota(settings);
                var second = new AccountQuota(settings)) {
            var pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Integer>> tasks = new ArrayList<>();
                for (int i = 0; i < 60; i++) {
                    var quota = i % 2 == 0 ? first : second;
                    tasks.add(() -> quota.reserve(1, 1000, true));
                }
                long admitted = 0;
                for (var result : pool.invokeAll(tasks)) if (result.get() == 0) admitted++;
                assertEquals(20, admitted);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void tracingExportsMetadataWithoutCustomerData() throws Exception {
        var settings =
                new Settings(
                        Map.of(
                                "LANGFUSE_ENABLED",
                                "true",
                                "LANGFUSE_SAMPLE_RATE",
                                "1",
                                "LANGFUSE_BASE_URL",
                                origin,
                                "LANGFUSE_PUBLIC_KEY",
                                "public-key",
                                "LANGFUSE_SECRET_KEY",
                                "private-project-key"));
        try (var tracing = new AgentTracing(settings)) {
            try (var span = tracing.span("agent.start", "private-call")) {}
            tracing.flush();
            assertEquals(1, received.size());
            String body = new String(received.get(0), StandardCharsets.UTF_8);
            assertTrue(body.contains("agent.start"));
            assertFalse(body.contains("private-call"));
            assertFalse(body.contains("private-project-key"));
            assertTrue(Support.object(received.get(0)).containsKey("resourceSpans"));
        }
    }

    @Test
    void authAndProductionMisconfigurationFailClosed() {
        assertThrows(
                Support.Failure.class,
                () ->
                        Support.auth(
                                new Settings(Map.of("SERVICE_TOKEN", "a")),
                                "SERVICE_TOKEN",
                                "Bearer b",
                                true));
        assertThrows(Exception.class, () -> new EmbeddedAgent(new Settings(Map.of("ENV", "prod"))));
        assertThrows(Exception.class, () -> new RecordingStorage(recording(Map.of("ENV", "prod"))));
    }
}
