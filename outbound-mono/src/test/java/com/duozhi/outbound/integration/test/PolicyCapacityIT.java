package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.auth.dao.SysUserMapper;
import com.duozhi.outbound.auth.dao.TenantMapper;
import com.duozhi.outbound.auth.domain.SysUser;
import com.duozhi.outbound.auth.domain.Tenant;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.auth.support.UserRecords;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.service.PlatformLock;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PolicyCapacityIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "policy-capacity-isolated-fixture-more-than-32-characters");
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.flyway.url", MYSQL::getJdbcUrl);
        p.add("spring.flyway.user", () -> "root");
        p.add("spring.flyway.password", MYSQL::getPassword);
        p.add("REDIS_URL", () -> "");
        p.add("spring.data.redis.host", () -> "127.0.0.1");
        p.add("spring.data.redis.port", () -> 9);
        p.add("spring.datasource.druid.max-active", () -> 2);
        p.add("JAVA_WORKERS_ENABLED", () -> "false");
        p.add("spring.ai.model.chat", () -> "none");
    }

    @Autowired TestRestTemplate http;
    @Autowired Crypto crypto;
    @Autowired Transactions tx;
    @Autowired TenantMapper tenants;
    @Autowired SysUserMapper users;
    @Autowired Store store;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean PlatformLock locks;
    private static final AtomicInteger IDS = new AtomicInteger(9000);
    int tenant;
    String token;

    @BeforeEach
    void seed() {
        tenant = IDS.incrementAndGet();
        var row = new Tenant();
        row.setId(tenant);
        row.setCode("policy-" + tenant);
        row.setName("Isolated policy fixture");
        tenants.insert(row);
        var user = new SysUser();
        user.setTenantId(tenant);
        user.setUsername("policy-" + tenant);
        user.setFullName("Policy test admin");
        user.setRole("admin");
        users.insert(user);
        token = crypto.token(UserRecords.map(user));
    }

    ResponseEntity<JsonNode> request(HttpMethod method, String path, Object body) {
        var h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    Map<String, Object> active(Object line, Object campaign) {
        return store.insert(
                "callsession",
                JsonSupport.map(
                        "tenant_id",
                        tenant,
                        "phone",
                        "139" + String.format(Locale.ROOT, "%08d", IDS.incrementAndGet()),
                        "mode",
                        "ai_only",
                        "status",
                        "dialing",
                        "attempts",
                        1,
                        "telephony_line_id",
                        line,
                        "campaign_id",
                        campaign));
    }

    @Test
    void tenantLimitCannotFallBelowLiveReservationsAndEqualLimitIsSaved() {
        active(null, null);
        active(null, null);
        assertThat(
                        request(
                                        HttpMethod.PUT,
                                        "/api/v1/admin/settings/capacity",
                                        Map.of("data", Map.of("max_concurrent_calls", 1)))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        assertThat(
                        request(HttpMethod.GET, "/api/v1/admin/settings/capacity", null)
                                .getBody()
                                .path("data")
                                .path("max_concurrent_calls")
                                .asInt())
                .isEqualTo(20);
        assertThat(
                        request(
                                        HttpMethod.PUT,
                                        "/api/v1/admin/settings/capacity",
                                        Map.of("data", Map.of("max_concurrent_calls", 2)))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(
                        request(HttpMethod.GET, "/api/v1/admin/settings/capacity", null)
                                .getBody()
                                .path("data")
                                .path("max_concurrent_calls")
                                .asInt())
                .isEqualTo(2);
    }

    @Test
    void lineReductionRejectsWithoutChangingTheSavedLimit() {
        var line =
                store.insert(
                        "telephonyline",
                        Map.of(
                                "tenant_id",
                                tenant,
                                "name",
                                "Capacity line",
                                "provider",
                                "mock",
                                "max_concurrency",
                                5));
        Object id = line.get("id");
        active(id, null);
        active(id, null);
        assertThat(
                        request(
                                        HttpMethod.PUT,
                                        "/api/v1/admin/lines/" + id,
                                        Map.of("max_concurrency", 1))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        assertThat(store.require("telephonyline", tenant, id).get("max_concurrency")).isEqualTo(5);
        assertThat(
                        request(
                                        HttpMethod.PUT,
                                        "/api/v1/admin/lines/" + id,
                                        Map.of("max_concurrency", 2))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(store.require("telephonyline", tenant, id).get("max_concurrency")).isEqualTo(2);
    }

    @Test
    void editingAStoppedCampaignPreservesItsCallsAndRejectsAnUndersizedLimit() {
        var campaign =
                store.insert(
                        "campaign",
                        Map.of(
                                "tenant_id",
                                tenant,
                                "name",
                                "Capacity campaign",
                                "mode",
                                "ai_only",
                                "status",
                                "stopped",
                                "concurrency",
                                5));
        Object id = campaign.get("id");
        var a = active(null, id);
        var b = active(null, id);
        var historical =
                store.insert(
                        "callsession",
                        Map.of(
                                "tenant_id",
                                tenant,
                                "phone",
                                "13900000999",
                                "mode",
                                "ai_only",
                                "status",
                                "completed",
                                "attempts",
                                1,
                                "campaign_id",
                                id));
        var body =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "name",
                                "Edited campaign",
                                "mode",
                                "ai_only",
                                "contact_ids",
                                List.of(),
                                "concurrency",
                                1));
        assertThat(request(HttpMethod.PUT, "/api/v1/campaigns/" + id, body).getStatusCode().value())
                .isEqualTo(409);
        assertThat(store.require("campaign", tenant, id).get("concurrency")).isEqualTo(5);
        body.put("concurrency", 2);
        assertThat(request(HttpMethod.PUT, "/api/v1/campaigns/" + id, body).getStatusCode().value())
                .isEqualTo(200);
        assertThat(store.require("campaign", tenant, id).get("concurrency")).isEqualTo(2);
        for (var call : List.of(a, b))
            assertThat(store.require("callsession", tenant, call.get("id")))
                    .containsEntry("status", "dialing")
                    .containsEntry("attempts", 1);
        assertThat(store.require("callsession", tenant, historical.get("id")))
                .containsEntry("status", "completed")
                .containsEntry("attempts", 1);
    }

    @Test
    void waitingPolicyWriterSeesTheAdmissionThatCommitsBeforeItsLock() throws Exception {
        active(null, null);
        var held = new CountDownLatch(1);
        var policyAtLock = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var threads = Executors.newFixedThreadPool(2);
        var writer =
                threads.submit(
                        () ->
                                tx.serialized(
                                        () -> {
                                            tx.tenant(tenant);
                                            held.countDown();
                                            try {
                                                if (!commit.await(10, TimeUnit.SECONDS))
                                                    throw new IllegalStateException(
                                                            "test barrier timed out");
                                            } catch (InterruptedException stopped) {
                                                Thread.currentThread().interrupt();
                                                throw new IllegalStateException(stopped);
                                            }
                                            active(null, null);
                                            return null;
                                        }));
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            // Signal at the real shared compatibility boundary. A tenant's
            // FOR SHARE lock alone cannot exclude a concurrent policy writer.
            doAnswer(
                            invocation -> {
                                policyAtLock.countDown();
                                return invocation.callRealMethod();
                            })
                    .when(locks)
                    .lock(Transactions.PLATFORM_KEY);
            var policy =
                    threads.submit(
                            () ->
                                    request(
                                            HttpMethod.PUT,
                                            "/api/v1/admin/settings/capacity",
                                            Map.of("data", Map.of("max_concurrent_calls", 1))));
            assertThat(policyAtLock.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(policy.isDone()).isFalse();
            commit.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThat(policy.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(409);
            assertThat(
                            request(HttpMethod.GET, "/api/v1/admin/settings/capacity", null)
                                    .getBody()
                                    .path("data")
                                    .path("max_concurrent_calls")
                                    .asInt())
                    .isEqualTo(20);
        } finally {
            commit.countDown();
            threads.shutdownNow();
            assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void blockedPolicyRequestsDoNotBorrowAllBusinessConnections() throws Exception {
        var atLock = new CountDownLatch(2);
        var threads = Executors.newFixedThreadPool(3);
        var requests = new ArrayList<Future<ResponseEntity<JsonNode>>>();
        try {
            try (var held = locks.lock(Transactions.PLATFORM_KEY)) {
                doAnswer(
                                invocation -> {
                                    atLock.countDown();
                                    return invocation.callRealMethod();
                                })
                        .when(locks)
                        .lock(Transactions.PLATFORM_KEY);
                for (int i = 0; i < 2; i++)
                    requests.add(
                            threads.submit(
                                    () ->
                                            request(
                                                    HttpMethod.PUT,
                                                    "/api/v1/admin/settings/capacity",
                                                    Map.of(
                                                            "data",
                                                            Map.of("max_concurrent_calls", 1)))));
                assertThat(atLock.await(10, TimeUnit.SECONDS)).isTrue();
                // There are only two connections. If both policy requests
                // borrowed before waiting, this independent read would time out.
                var read =
                        threads.submit(
                                () ->
                                        store.scalar(
                                                "callsession",
                                                "activeDialCount",
                                                JsonSupport.map(
                                                        "tenantId",
                                                        tenant,
                                                        "line",
                                                        null,
                                                        "campaign",
                                                        null)));
                assertThat(read.get(3, TimeUnit.SECONDS)).isZero();
                assertThat(requests).allMatch(future -> !future.isDone());
            }
            for (var response : requests)
                assertThat(response.get(10, TimeUnit.SECONDS).getStatusCode().value())
                        .isEqualTo(200);
        } finally {
            threads.shutdownNow();
            assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void anotherTenantsReservationsDoNotPreventAnUnoccupiedTenantFromReducing() {
        int foreign = IDS.incrementAndGet();
        var row = new Tenant();
        row.setId(foreign);
        row.setCode("foreign-" + foreign);
        row.setName("Other tenant");
        tenants.insert(row);
        for (int i = 0; i < 2; i++)
            store.insert(
                    "callsession",
                    Map.of(
                            "tenant_id",
                            foreign,
                            "phone",
                            "1390000000" + i,
                            "mode",
                            "ai_only",
                            "status",
                            "dialing",
                            "attempts",
                            1));
        assertThat(
                        request(
                                        HttpMethod.PUT,
                                        "/api/v1/admin/settings/capacity",
                                        Map.of("data", Map.of("max_concurrent_calls", 1)))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(
                        request(HttpMethod.GET, "/api/v1/admin/settings/capacity", null)
                                .getBody()
                                .path("data")
                                .path("max_concurrent_calls")
                                .asInt())
                .isEqualTo(1);
    }
}
