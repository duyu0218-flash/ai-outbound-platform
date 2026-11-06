package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.auth.dao.SysUserMapper;
import com.duozhi.outbound.auth.dao.TenantMapper;
import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.auth.support.UserRecords;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.runtime.ScheduledWork;
import com.duozhi.outbound.operations.controller.ExportCenter;
import com.duozhi.outbound.operations.service.ExportStorage;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExportHistoryIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    static final Path FILES = temporaryDirectory();

    static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("export-history-it-");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "export-history-tests-only-secret-over-thirty-two-characters");
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.flyway.url", MYSQL::getJdbcUrl);
        p.add("spring.flyway.user", () -> "root");
        p.add("spring.flyway.password", MYSQL::getPassword);
        p.add("spring.data.redis.host", () -> "127.0.0.1");
        p.add("spring.data.redis.port", () -> 9);
        p.add("REDIS_URL", () -> "");
        p.add("spring.ai.model.chat", () -> "none");
        p.add("JAVA_WORKERS_ENABLED", () -> "false");
        p.add("EXPORT_STORAGE_MODE", () -> "local");
        p.add("EXPORT_STORAGE_DIR", FILES::toString);
        p.add("RATE_LIMIT_RPS", () -> 1000);
        p.add("RATE_LIMIT_BURST", () -> 2000);
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    Crypto crypto;
    @Autowired
    Store store;
    @Autowired
    SysUserMapper users;
    @Autowired
    TenantMapper tenants;
    @Autowired
    WorkspaceDocuments docs;
    @Autowired
    ExportCenter exports;
    @Autowired
    ExportStorage storage;
    @Autowired
    ScheduledWork scheduled;
    static final AtomicInteger IDS = new AtomicInteger(8000);
    int tenant, neighbor;
    SysUser user, other;
    String token;

    @BeforeEach
    void setup() {
        scheduled.close(); // Tests drive workers explicitly, so queue-limit assertions are
        // deterministic.
        docs.jdbc.update("DELETE FROM ops_export_job");
        ReflectionTestUtils.setField(exports, "lastTenant", 0L);
        ReflectionTestUtils.setField(exports, "lastCleanup", 0L);
        tenant = IDS.getAndAdd(2);
        neighbor = tenant + 1;
        for (int id : List.of(tenant, neighbor)) {
            var value = new Tenant();
            value.setId(id);
            value.setCode("exports-" + id);
            value.setName("Synthetic");
            tenants.insert(value);
        }
        user = user(tenant, "admin");
        other = user(neighbor, "admin");
        token = crypto.token(UserRecords.map(user));
    }

    SysUser user(int tenant, String role) {
        var value = new SysUser();
        value.setTenantId(tenant);
        value.setRole(role);
        value.setUsername("export-" + UUID.randomUUID());
        value.setFullName("Synthetic export user");
        users.insert(value);
        return value;
    }

    HttpHeaders headers(String value) {
        var h = new HttpHeaders();
        h.setBearerAuth(value);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    ResponseEntity<JsonNode> get(String path, String bearer) {
        return http.exchange(
                "/api/v1/ops" + path,
                HttpMethod.GET,
                new HttpEntity<>(headers(bearer)),
                JsonNode.class);
    }

    ResponseEntity<byte[]> download(String id, String bearer) {
        return http.exchange(
                "/api/v1/ops/exports/" + id + "/download",
                HttpMethod.GET,
                new HttpEntity<>(headers(bearer)),
                byte[].class);
    }

    String queued(int tenant, SysUser owner) {
        String id = UUID.randomUUID().toString();
        docs.jdbc.update(
                "INSERT INTO ops_export_job(id,tenant_id,user_id,request_key,kind,query_json,created_at,updated_at) VALUES(?,?,?,?,'calls','{}',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                id,
                tenant,
                owner.getId(),
                id);
        return id;
    }

    MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setAttribute("identity", new Identity(tenant, UserRecords.map(user), Set::of));
        return request;
    }

    @Test
    void pagedRequestsReachPastOneThousandAndRemainTenantBound() {
        var batch = new ArrayList<Object[]>();
        for (int n = 0; n < 1005; n++)
            batch.add(
                    new Object[] {
                        tenant,
                        String.format("history-%04d", n),
                        "{\"name\":\"request\",\"state\":\""
                                + (n % 2 == 0 ? "accepted" : "pending")
                                + "\"}"
                    });
        batch.add(
                new Object[] {
                    neighbor, "neighbor-only", "{\"name\":\"request\",\"state\":\"accepted\"}"
                });
        docs.jdbc.batchUpdate(
                "INSERT INTO platform_document(tenant_id,kind,object_id,name,parent_id,draft_json,updated_at) VALUES(?,'wecom_request',?,'request','',?,'2026-11-06 00:00:00')",
                batch);
        var legacy = get("/wecom/requests", token);
        assertThat(legacy.getBody().isArray()).isTrue();
        assertThat(legacy.getHeaders().getFirst("X-Total-Count")).isEqualTo("1005");
        assertThat(legacy.getHeaders().getFirst("X-Result-Truncated")).isEqualTo("true");
        var first = get("/wecom/requests?page=1&size=20", token);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody().path("total").asInt()).isEqualTo(1005);
        assertThat(first.getBody().path("rows").get(0).path("object_id").asText())
                .isEqualTo("history-0000");
        var last = get("/wecom/requests?page=51&size=20", token).getBody();
        assertThat(last.path("rows").size()).isEqualTo(5);
        assertThat(last.path("rows").get(4).path("object_id").asText()).isEqualTo("history-1004");
        assertThat(
                        get("/wecom/requests?page=1&state=accepted", token)
                                .getBody()
                                .path("total")
                                .asInt())
                .isEqualTo(503);
        assertThat(
                        get("/wecom/requests?page=1", crypto.token(UserRecords.map(other)))
                                .getBody()
                                .path("total")
                                .asInt())
                .isEqualTo(1);
        assertThat(get("/wecom/requests?page=0", token).getStatusCode().value()).isEqualTo(422);
        assertThat(
                        get(
                                        "/wecom/requests?page=1",
                                        crypto.token(UserRecords.map(user(tenant, "agent"))))
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    @Test
    void tenantRoundRobinPersistsFilesOutsideMysqlAndPreservesDownloadIsolation() throws Exception {
        String first = queued(tenant, user);
        for (int n = 0; n < 5; n++) queued(tenant, user);
        String nextTenant = queued(neighbor, other);
        exports.processNext();
        exports.processNext();
        assertThat(
                        docs.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ops_export_job WHERE tenant_id=? AND state='ready'",
                                Integer.class,
                                tenant))
                .isEqualTo(1);
        var ready =
                docs.one(
                        "SELECT * FROM ops_export_job WHERE tenant_id=? AND state='ready'", tenant);
        assertThat(ready.get("content")).isNull();
        assertThat(ready.get("storage_key").toString()).startsWith("local:exports/" + tenant + "/");
        assertThat(docs.one("SELECT state FROM ops_export_job WHERE id=?", nextTenant))
                .containsEntry("state", "ready");
        String id = ready.get("id").toString();
        var file = download(id, token);
        assertThat(file.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(file.getBody(), StandardCharsets.UTF_8))
                .contains("id,phone,campaign_name");
        assertThat(download(id, crypto.token(UserRecords.map(other))).getStatusCode().value())
                .isEqualTo(404);
        user(
                tenant,
                "agent"); // A change in the effective visibility snapshot invalidates old files.
        assertThat(download(id, token).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void pendingLimitsAreAtomicAndIdempotentRetriesStillWorkAtTheLimit() throws Exception {
        var accepted = new CopyOnWriteArrayList<String>();
        var denied = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<?>>();
            for (int n = 0; n < 10; n++) {
                String key = UUID.randomUUID().toString();
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        exports.create(
                                                Map.of(
                                                        "kind",
                                                        "calls",
                                                        "query",
                                                        Map.of(),
                                                        "request_key",
                                                        key),
                                                request());
                                        accepted.add(key);
                                    } catch (ApiError limit) {
                                        assertThat(limit.getMessage()).contains("待处理导出过多");
                                        denied.incrementAndGet();
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        assertThat(accepted).hasSize(5);
        assertThat(denied.get()).isEqualTo(5);
        exports.create(
                Map.of("kind", "calls", "query", Map.of(), "request_key", accepted.getFirst()),
                request());
        assertThat(
                        docs.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ops_export_job WHERE tenant_id=?",
                                Integer.class,
                                tenant))
                .isEqualTo(5);
    }

    @Test
    void expiryBlocksDownloadBeforeCleanupAndClearsExternalAndLegacyContents() throws Exception {
        String id = queued(tenant, user);
        exports.processNext();
        var ready = docs.one("SELECT * FROM ops_export_job WHERE id=?", id);
        String key = ready.get("storage_key").toString();
        String legacy = UUID.randomUUID().toString();
        docs.jdbc.update(
                "INSERT INTO ops_export_job(id,tenant_id,user_id,request_key,kind,query_json,state,content,access_json,expires_at,created_at,updated_at) VALUES(?,?,?,?,'calls','{}','ready',?,?,UTC_TIMESTAMP(3)+INTERVAL 1 DAY,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                legacy,
                tenant,
                user.getId(),
                legacy,
                "id,phone\r\n".getBytes(StandardCharsets.UTF_8),
                ready.get("access_json"));
        assertThat(download(legacy, token).getStatusCode().value()).isEqualTo(200);
        docs.jdbc.update(
                "UPDATE ops_export_job SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id IN (?,?)",
                id,
                legacy);
        assertThat(download(id, token).getStatusCode().value()).isEqualTo(410);
        assertThat(download(legacy, token).getStatusCode().value()).isEqualTo(410);
        exports.cleanupExpired();
        assertThat(docs.one("SELECT state,storage_key,content FROM ops_export_job WHERE id=?", id))
                .containsEntry("state", "expired")
                .containsEntry("storage_key", null)
                .containsEntry("content", null);
        assertThat(docs.one("SELECT state,content FROM ops_export_job WHERE id=?", legacy))
                .containsEntry("state", "expired")
                .containsEntry("content", null);
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void failedDeletionIsRetriedWithoutBlockingLaterExpiredObjects() throws Exception {
        String blocked = queued(tenant, user);
        exports.processNext();
        String key =
                docs.one("SELECT storage_key FROM ops_export_job WHERE id=?", blocked)
                        .get("storage_key")
                        .toString();
        docs.jdbc.update("UPDATE ops_export_job SET expires_at='2020-01-01' WHERE id=?", blocked);
        var flaky = org.mockito.Mockito.spy(storage);
        org.mockito.Mockito.doThrow(new java.io.IOException("synthetic storage outage"))
                .doCallRealMethod()
                .when(flaky)
                .delete(key);
        ReflectionTestUtils.setField(exports, "files", flaky);
        try {
            // A full failed-first page is the regression case: the next batch must advance.
            for (int n = 0; n < 24; n++) {
                String id = UUID.randomUUID().toString();
                docs.jdbc.update(
                        "INSERT INTO ops_export_job(id,tenant_id,user_id,request_key,kind,query_json,state,storage_key,expires_at,created_at,updated_at) VALUES(?,?,?,?,'calls','{}','ready',?,'2020-01-01',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                        id,
                        tenant,
                        user.getId(),
                        id,
                        key);
            }
            // All references to the unavailable object fail until the mock is recovered below.
            org.mockito.Mockito.doThrow(new java.io.IOException("synthetic storage outage"))
                    .when(flaky)
                    .delete(key);
            String later = UUID.randomUUID().toString();
            docs.jdbc.update(
                    "INSERT INTO ops_export_job(id,tenant_id,user_id,request_key,kind,query_json,state,content,expires_at,created_at,updated_at) VALUES(?,?,?,?,'calls','{}','ready',?,'2020-01-02',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    later,
                    tenant,
                    user.getId(),
                    later,
                    new byte[] {65});
            exports.cleanupExpired();
            assertThat(docs.one("SELECT state,storage_key FROM ops_export_job WHERE id=?", blocked))
                    .containsEntry("state", "ready")
                    .containsEntry("storage_key", key);
            assertThat(download(blocked, token).getStatusCode().value()).isEqualTo(410);
            ReflectionTestUtils.setField(exports, "lastCleanup", 0L);
            exports.cleanupExpired();
            assertThat(docs.one("SELECT state,content FROM ops_export_job WHERE id=?", later))
                    .containsEntry("state", "expired")
                    .containsEntry("content", null);
            org.mockito.Mockito.doCallRealMethod().when(flaky).delete(key);
            docs.jdbc.update(
                    "UPDATE ops_export_job SET cleanup_after=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE storage_key=?",
                    key);
            ReflectionTestUtils.setField(exports, "lastCleanup", 0L);
            exports.cleanupExpired();
            assertThat(docs.one("SELECT state,storage_key FROM ops_export_job WHERE id=?", blocked))
                    .containsEntry("state", "expired")
                    .containsEntry("storage_key", null);
        } finally {
            ReflectionTestUtils.setField(exports, "files", storage);
        }
    }

    @Test
    void uploadCommitFailureRetainsObjectReferenceWhenCompensatingDeletionFails() throws Exception {
        String id = queued(tenant, user);
        String key = storage.key(tenant, id);
        var flaky = org.mockito.Mockito.spy(storage);
        org.mockito.Mockito.doThrow(new java.io.IOException("synthetic deletion outage"))
                .doCallRealMethod()
                .when(flaky)
                .delete(key);
        ReflectionTestUtils.setField(exports, "files", flaky);
        try (var connection =
                        java.sql.DriverManager.getConnection(
                                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TRIGGER reject_export_ready BEFORE UPDATE ON ops_export_job FOR EACH ROW BEGIN IF NEW.state='ready' AND NEW.id='"
                            + id
                            + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic commit failure'; END IF; END");
            try {
                exports.processNext();
                var failed =
                        docs.one("SELECT state,storage_key FROM ops_export_job WHERE id=?", id);
                assertThat(failed)
                        .containsEntry("state", "failed")
                        .containsEntry("storage_key", key);
                try (var input = storage.open(key)) {
                    assertThat(input.read()).isNotEqualTo(-1);
                }
                exports.cleanupExpired();
                assertThat(docs.one("SELECT state,storage_key FROM ops_export_job WHERE id=?", id))
                        .containsEntry("state", "failed")
                        .containsEntry("storage_key", null);
                assertThatThrownBy(() -> storage.open(key)).isInstanceOf(java.io.IOException.class);
            } finally {
                statement.execute("DROP TRIGGER IF EXISTS reject_export_ready");
            }
        } finally {
            ReflectionTestUtils.setField(exports, "files", storage);
        }
    }

    @AfterAll
    static void removeTemporaryFiles() throws Exception {
        try (var paths = Files.walk(FILES)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }
}
