package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.PlatformLock;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.dial.service.DialAuthorizationService;
import com.duozhi.outbound.dial.service.DialCommandRecovery;
import com.duozhi.outbound.dial.service.DialService;
import com.duozhi.outbound.integration.service.TelephonyClient;
import com.duozhi.outbound.job.service.Outbox;
import com.duozhi.outbound.job.worker.TaskWorker;
import com.duozhi.outbound.system.service.Settings;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DialAuthorizationIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "v5-isolated-business-authorization-test-secret-32-plus");
        p.add("VOICE_COMMAND_SECRET", () -> "v5-isolated-wire-authorization-test-secret-32-plus");
        p.add(
                "VOICE_SECURITY_ADMIN_TOKEN",
                () -> "v5-independent-global-maintenance-token-32-plus");
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.flyway.url", MYSQL::getJdbcUrl);
        p.add("spring.flyway.user", () -> "root");
        p.add("spring.flyway.password", MYSQL::getPassword);
        p.add("spring.data.redis.host", REDIS::getHost);
        p.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        p.add("REDIS_URL", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        p.add("JAVA_WORKERS_ENABLED", () -> "false");
        p.add("JAVA_DISPATCH_ENABLED", () -> "true");
        p.add("JAVA_DIAL_AUTH_PROTOCOL", () -> "2");
        p.add("JAVA_DIAL_BATCH_SIZE", () -> "10");
        p.add("TELEPHONY_PROVIDER", () -> "mock");
        p.add("VOICE_GATEWAY_DRIVER", () -> "mock");
        p.add("VOICE_CPS", () -> "100");
        p.add("VOICE_MOCK_OCCUPANCY_SEC", () -> "300");
        p.add("spring.ai.model.chat", () -> "none");
    }

    @Autowired Store store;
    @Autowired Settings settings;
    @Autowired JsonSupport json;
    @Autowired DialService dial;
    @Autowired TelephonyClient client;
    @Autowired DialAuthorizationService authorizations;
    @Autowired DialCommandRecovery recovery;
    @Autowired TaskWorker worker;
    @Autowired Outbox outbox;
    @Autowired PlatformLock platformLocks;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired ConfigurableEnvironment environment;
    @LocalServerPort int port;
    @Autowired org.springframework.boot.resttestclient.TestRestTemplate http;

    @BeforeEach
    void seed() {
        String base = "http://127.0.0.1:" + port;
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "v5-test-callback",
                                Map.of(
                                        "TELEPHONY_WEBHOOK_BASE",
                                        base,
                                        "VOICE_CALLBACK_BASE_URL",
                                        base)));
        if (store.one("tenant", "findById", Map.of("id", 1)) == null)
            store.insert(
                    "tenant",
                    JsonSupport.map(
                            "id", 1, "code", "auth2", "name", "Isolated authorization protocol"));
        var policy = new LinkedHashMap<>(settings.get(1, "compliance"));
        policy.put("allowed_start_hour", 0);
        policy.put("allowed_end_hour", 0);
        policy.put("timezone", "UTC");
        policy.put("require_explicit_consent", false);
        policy.put("require_explicit_consent_for_direct_calls", false);
        var row =
                store.one(
                        "adminsetting",
                        "findBySection",
                        JsonSupport.map("tenantId", 1, "section", "compliance"));
        if (row == null)
            store.insert(
                    "adminsetting",
                    JsonSupport.map(
                            "tenant_id",
                            1,
                            "section",
                            "compliance",
                            "data_json",
                            json.json(policy)));
        else
            store.update(
                    "adminsetting",
                    1,
                    row.get("id"),
                    JsonSupport.map("data_json", json.json(policy)));
    }

    @AfterEach
    void isolateRecovery() {
        jdbc.update(
                "UPDATE dial_command_journal SET state='TEST_CLOSED' WHERE state IN"
                        + " ('PREPARED','MANUAL_HOLD')");
    }

    Map<String, Object> queued() {
        return store.insert(
                "callsession",
                JsonSupport.map(
                        "tenant_id",
                        1,
                        "phone",
                        "+86139"
                                + String.format(
                                        "%08d",
                                        Math.abs(
                                                UUID.randomUUID().getLeastSignificantBits()
                                                        % 100000000L)),
                        "mode",
                        "ai_only",
                        "status",
                        "queued",
                        "max_attempts",
                        5));
    }

    Map<String, Object> claim(Map<String, Object> call) {
        return ReflectionTestUtils.invokeMethod(dial, "claim", call.get("id"));
    }

    Map<String, Object> fields(Map<String, Object> call) {
        var metadata =
                (Map<String, Object>) ReflectionTestUtils.invokeMethod(dial, "metadataFor", call);
        return JsonSupport.map(
                "phone",
                call.get("phone"),
                "caller_id",
                metadata.getOrDefault("caller_id", ""),
                "webhook_url",
                metadata.get("status_webhook_url"),
                "metadata",
                metadata);
    }

    @Test
    void businessInstallsFenceBeforeSendingAndReplaysOneOriginalExecution() {
        var call = claim(queued());
        assertThat(call).isNotNull();
        assertThat(dial.sendClaimed(call, (Map<String, Object>) fields(call).get("metadata")))
                .isTrue();
        assertThat(dial.sendClaimed(call, (Map<String, Object>) fields(call).get("metadata")))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM voice_attempts WHERE call_id=?",
                                Long.class,
                                call.get("id")))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT a.state,a.fence,v.send_fence,v.protocol_version FROM"
                                        + " voice_dial_authorization a JOIN voice_attempts v ON"
                                        + " v.call_id=a.call_id AND v.attempt=a.attempt WHERE"
                                        + " a.call_id=?",
                                call.get("id")))
                .containsEntry("state", "INSTALLED")
                .containsEntry("protocol_version", 2);
        var journal =
                jdbc.queryForMap(
                        "SELECT state,provider_uuid,current_fence FROM dial_command_journal WHERE"
                                + " call_id=?",
                        call.get("id"));
        assertThat(journal).containsEntry("state", "ACCEPTED");
        assertThat(((Number) journal.get("current_fence")).longValue()).isPositive();
    }

    @Test
    void onlyDurableProofRollsBackAndReadmissionPreservesKAndFrozenSemantics() {
        var call = claim(queued());
        var fields = fields(call);
        var proof = client.command(call, "dial-cancel", fields);
        assertThat(proof).containsEntry("result", "rejected_before_dispatch");
        var forged = new LinkedHashMap<>(proof);
        forged.put("call_id", UUID.randomUUID().toString());
        assertThatThrownBy(() -> authorizations.rejected(call, forged))
                .isInstanceOf(ApiError.class);
        assertThat(store.require("callsession", 1, call.get("id")))
                .containsEntry("status", "dialing");
        assertThat(authorizations.rejected(call, proof)).isTrue();
        assertThat(store.require("callsession", 1, call.get("id")))
                .containsEntry("status", "queued")
                .containsEntry("attempts", 0)
                .containsEntry("started_at", null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM dial_reservation WHERE call_id=? AND"
                                        + " state='RESERVED'",
                                Long.class,
                                call.get("id")))
                .isZero();
        assertThat(claim(store.require("callsession", 1, call.get("id")))).isNull();
        store.update("callsession", 1, call.get("id"), JsonSupport.map("next_attempt_at", null));
        var again = claim(store.require("callsession", 1, call.get("id")));
        assertThat(again).containsEntry("attempts", 1);
        assertThat(dial.sendClaimed(again, (Map<String, Object>) fields(again).get("metadata")))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM dial_command_journal WHERE call_id=?",
                                Long.class,
                                call.get("id")))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM dial_reservation WHERE call_id=?",
                                Long.class,
                                call.get("id")))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM callevent WHERE call_session_id=? AND"
                                        + " event_type='dial_attempt_claimed'",
                                Long.class,
                                call.get("id")))
                .isEqualTo(2);
        assertThat(authorizations.rejected(call, proof)).isTrue();
        assertThat(store.require("callsession", 1, call.get("id")))
                .containsEntry("status", "dialing")
                .containsEntry("attempts", 1);
    }

    @Test
    void abandonedSynchronousClaimRecoversWithoutAnOriginalTask() {
        var call = claim(queued());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM taskoutbox WHERE aggregate_id=?",
                                Long.class,
                                call.get("id")))
                .isZero();
        jdbc.update(
                "UPDATE dial_command_journal SET created_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 6"
                        + " SECOND) WHERE call_id=?",
                call.get("id"));
        assertThat(recovery.processOne()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM dial_command_journal WHERE call_id=?",
                                String.class,
                                call.get("id")))
                .isEqualTo("ACCEPTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM voice_attempts WHERE call_id=?",
                                Long.class,
                                call.get("id")))
                .isEqualTo(1);
        assertThat(recovery.processOne()).isFalse();
    }

    @Test
    void maintenanceHttpUsesTheIndependentGlobalCredential() {
        String path = "/v1/admin/dial-control/status";
        assertThat(http.postForEntity(path, Map.of(), String.class).getStatusCode().value())
                .isEqualTo(401);
        var wrong = new org.springframework.http.HttpHeaders();
        wrong.setBearerAuth("tenant-admin-token-is-not-a-global-credential");
        assertThat(
                        http.exchange(
                                        path,
                                        org.springframework.http.HttpMethod.POST,
                                        new org.springframework.http.HttpEntity<>(Map.of(), wrong),
                                        String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        var auth = new org.springframework.http.HttpHeaders();
        auth.setBearerAuth("v5-independent-global-maintenance-token-32-plus");
        var result =
                http.exchange(
                        path,
                        org.springframework.http.HttpMethod.POST,
                        new org.springframework.http.HttpEntity<>(Map.of(), auth),
                        Map.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody())
                .containsEntry("phase", "RUNNING")
                .containsEntry("certificate_issued", false);
        assertThat(
                        http.exchange(
                                        "/v1/admin/dial-counters/verify?generation=0",
                                        org.springframework.http.HttpMethod.POST,
                                        new org.springframework.http.HttpEntity<>(Map.of(), wrong),
                                        String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
    }

    @Test
    void aFreshDurableCohortExecutesAndAcknowledgesOnItsFirstProcessingPass() {
        var calls = List.of(queued(), queued());
        for (var call : calls)
            outbox.enqueue(
                    1,
                    "dial_call",
                    call.get("id"),
                    "dial:" + call.get("id") + ":1",
                    JsonSupport.map("call_id", call.get("id"), "attempt", 0));
        assertThat(worker.processLane(1L, 0)).isTrue();
        for (var call : calls) {
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT state FROM dial_command_journal WHERE call_id=?",
                                    String.class,
                                    call.get("id")))
                    .isEqualTo("ACCEPTED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM voice_attempts WHERE call_id=?",
                                    Long.class,
                                    call.get("id")))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT state FROM taskoutbox WHERE aggregate_id=? AND"
                                            + " task_type='dial_call'",
                                    String.class,
                                    call.get("id")))
                    .isEqualTo("COMPLETED");
        }
    }

    @Test
    void authorizationInstallationDoesNotHoldOrBorrowTheGlobalAdmissionMutex() throws Exception {
        var call = claim(queued());
        var fields = fields(call);
        var thread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var held = platformLocks.lock(718492061L)) {
            var sent = thread.submit(() -> client.command(call, "dial", fields));
            assertThat(sent.get(3, java.util.concurrent.TimeUnit.SECONDS))
                    .containsEntry("result", "pbx_accepted");
        } finally {
            thread.shutdownNow();
        }
        assertThat(dial.confirmClaimed(call, (Map<String, Object>) fields.get("metadata")))
                .isTrue();
    }

    @Test
    void competingBusinessOwnersAllocateOneLiveFenceWithoutTheGlobalMutex() throws Exception {
        var call = claim(queued());
        var start = new java.util.concurrent.CountDownLatch(1);
        var threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        var owners = new ArrayList<Map<String, Object>>();
        var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
        try {
            for (int index = 0; index < 2; index++) {
                var owned = new LinkedHashMap<>(call);
                owned.put("_dial_owner", "request:race-" + index);
                owners.add(owned);
                var payload = new LinkedHashMap<>(fields(call));
                payload.put("call_id", call.get("id"));
                payload.put("tenant_id", 1);
                payload.put("protocol_version", 2);
                futures.add(
                        threads.submit(
                                () -> {
                                    start.await();
                                    try {
                                        authorizations.prepare(
                                                List.of(
                                                        new DialAuthorizationService.Request(
                                                                owned, payload)));
                                        return true;
                                    } catch (TaskWorker.Deferred alive) {
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            int winner = -1, total = 0;
            for (int index = 0; index < 2; index++)
                if (futures.get(index).get(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    winner = index;
                    total++;
                }
            assertThat(total).isEqualTo(1);
            var selected = owners.get(winner);
            assertThat(client.command(selected, "dial", fields(call)))
                    .containsEntry("result", "pbx_accepted");
            assertThat(
                            dial.confirmClaimed(
                                    call, (Map<String, Object>) fields(call).get("metadata")))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT current_fence FROM dial_command_journal WHERE"
                                            + " call_id=?",
                                    Long.class,
                                    call.get("id")))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            threads.shutdownNow();
        }
    }
}
