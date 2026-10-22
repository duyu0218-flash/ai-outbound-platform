package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import com.auth0.jwt.algorithms.Algorithm;
import com.duozhi.outbound.auth.dao.AuthIdentityMapper;
import com.duozhi.outbound.auth.dao.SysUserMapper;
import com.duozhi.outbound.auth.dao.TenantMapper;
import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.auth.domain.SysUser;
import com.duozhi.outbound.auth.domain.Tenant;
import com.duozhi.outbound.auth.service.AuthService;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.auth.support.UserRecords;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.service.ExpiringValues;
import com.duozhi.outbound.system.dao.AuditLogMapper;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthHttpIT {
    static final String SECRET = "auth-http-original-jwt-secret-at-least-thirty-two-characters";
    static final String PASSWORD = "pass-for-original-auth";
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> SECRET);
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.flyway.url", MYSQL::getJdbcUrl);
        p.add("spring.flyway.user", () -> "root");
        p.add("spring.flyway.password", MYSQL::getPassword);
        p.add("spring.data.redis.host", REDIS::getHost);
        p.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        p.add("REDIS_URL", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        p.add("spring.ai.model.chat", () -> "none");
        p.add("TENANT_API_KEYS_JSON", () -> "{\"1\":\"tenant-one-key\",\"2\":\"tenant-two-key\"}");
    }

    @Autowired
    SqlSessionFactory sessions;
    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper json;
    @Autowired
    Config config;
    @Autowired
    ConfigurableEnvironment environment;
    @Autowired
    SysUserMapper users;
    @Autowired
    TenantMapper tenants;
    @Autowired
    AuthIdentityMapper identities;
    @Autowired
    AuditLogMapper audits;
    @Autowired
    AuthService auth;
    @Autowired
    Crypto crypto;
    @Autowired
    ExpiringValues media;
    @Autowired
    StringRedisTemplate redis;
    static final Map<String, Object> overrides = new HashMap<>();
    static SysUser admin, agent, other;
    static String adminToken, agentToken;

    @BeforeEach
    void seed() throws Exception {
        if (admin != null) return;
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("auth-http-scenario", overrides));
        for (int id : List.of(1, 2)) {
            Tenant t = new Tenant();
            t.setId(id);
            t.setCode("test-tenant-" + id);
            t.setName("Tenant " + id);
            tenants.insert(t);
        }
        admin = user(1, "admin");
        agent = user(1, "agent");
        other = user(2, "agent");
        adminToken = token(admin);
        agentToken = token(agent);
    }

    @BeforeEach
    void defaults() {
        overrides.clear();
        overrides.put("TENANT_API_SCOPES_JSON", "{}");
        overrides.put("ENV", "dev");
        overrides.put("AUTH_MAX_FAILED_ATTEMPTS", "5");
        overrides.put("AUTH_LOCKOUT_SECONDS", "900");
        overrides.put("JWT_TTL_SECONDS", "43200");
        overrides.put("WEBRTC_ENABLED", "false");
    }

    SysUser user(int tenant, String role) throws Exception {
        SysUser u = new SysUser();
        u.setTenantId(tenant);
        u.setUsername(role + UUID.randomUUID().toString().replace("-", ""));
        u.setFullName("Original " + role);
        u.setRole(role);
        u.setPasswordHash(
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                (SECRET + ":" + PASSWORD)
                                                        .getBytes(StandardCharsets.UTF_8))));
        users.insert(u);
        return u;
    }

    String token(SysUser u) {
        return crypto.token(UserRecords.map(users.findById(u.getId(), u.getTenantId())));
    }

    HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    HttpHeaders key() {
        HttpHeaders h = new HttpHeaders();
        h.set("x-api-key", "tenant-one-key");
        return h;
    }

    ResponseEntity<JsonNode> request(
            HttpMethod method, String path, HttpHeaders headers, Object body) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        var r = http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(r.getHeaders().get("Set-Cookie")).isNull();
        return r;
    }

    ResponseEntity<JsonNode> get(String path, HttpHeaders h) {
        return request(HttpMethod.GET, path, h, null);
    }

    ResponseEntity<JsonNode> login(SysUser u, String password) {
        return request(
                HttpMethod.POST,
                "/api/v1/auth/login",
                new HttpHeaders(),
                Map.of("username", u.getUsername(), "password", password));
    }

    void status(ResponseEntity<JsonNode> response, int expected) {
        assertThat(response.getStatusCode().value())
                .as(String.valueOf(response.getBody()))
                .isEqualTo(expected);
    }

    void grant() {
        overrides.put("TENANT_API_SCOPES_JSON", "{\"1\":[\"admin:users\"]}");
    }

    @Test
    void apiKeyWithoutPermissionReturns403() {
        status(get("/api/v1/admin/users", key()), 403);
    }

    @Test
    void apiKeyWithExplicitPermissionReturns200() {
        grant();
        status(get("/api/v1/admin/users", key()), 200);
        status(get("/api/v1/auth/me", key()), 401);
    }

    @Test
    void adminUserReturns200() {
        status(get("/api/v1/admin/users", bearer(adminToken)), 200);
    }

    @Test
    void agentUserReturns403() {
        status(get("/api/v1/admin/users", bearer(agentToken)), 403);
    }

    @Test
    void wildcardOrOtherPermissionDoesNotGrantAccess() {
        overrides.put("TENANT_API_SCOPES_JSON", "{\"1\":[\"admin:*\",\"settings:write\"]}");
        status(get("/api/v1/admin/users", key()), 403);
    }

    @Test
    void crossTenantUpdateReturns404WithoutData() {
        var response =
                request(
                        HttpMethod.PUT,
                        "/api/v1/admin/users/" + other.getId(),
                        bearer(adminToken),
                        Map.of("full_name", "should-not-save"));
        status(response, 404);
        assertThat(response.getBody().fieldNames()).toIterable().containsExactly("detail");
        assertThat(users.findById(other.getId(), 2).getFullName()).isEqualTo(other.getFullName());
        grant();
        status(
                request(
                        HttpMethod.PUT,
                        "/api/v1/admin/users/" + other.getId(),
                        key(),
                        Map.of("full_name", "key-cannot-save")),
                404);
    }

    @Test
    void databaseTenantAndRoleAreTheOnlyContextSource() {
        var claims = UserRecords.map(admin);
        claims.put("tenant_id", 2);
        claims.put("role", "agent");
        String misleading = crypto.token(claims);
        var response = get("/api/v1/admin/users", bearer(misleading));
        status(response, 200);
        for (JsonNode row : response.getBody())
            assertThat(row.get("tenant_id").asInt()).isEqualTo(1);
        HttpHeaders h = bearer(adminToken);
        h.set("x-tenant-id", "2");
        status(get("/api/v1/auth/me", h), 403);
        HttpHeaders invalid = bearer("bad-token");
        invalid.set("x-api-key", "tenant-one-key");
        grant();
        status(get("/api/v1/admin/users", invalid), 401);
    }

    @Test
    void sixJwtClaimsAndOldTokenWithoutTvArePreserved() throws Exception {
        var me = get("/api/v1/auth/me", bearer(adminToken));
        status(me, 200);
        assertThat(me.getBody().fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "id",
                        "tenant_id",
                        "username",
                        "full_name",
                        "role",
                        "is_supervisor",
                        "agent_status",
                        "last_seen_at",
                        "enabled");
        var signed = crypto.claims(adminToken);
        assertThat(signed.keySet())
                .containsExactlyInAnyOrder("sub", "uid", "tenant_id", "role", "tv", "exp");
        var old = new LinkedHashMap<String, Object>(signed);
        old.remove("tv");
        status(get("/api/v1/auth/me", bearer(sign(old))), 200);
        old.put("exp", Instant.now().getEpochSecond());
        status(get("/api/v1/auth/me", bearer(sign(old))), 401);
        old.put("exp", Instant.now().getEpochSecond() + 3600);
        old.put("nbf", Instant.now().getEpochSecond() + 60);
        status(get("/api/v1/auth/me", bearer(sign(old))), 401);
    }

    String sign(Map<String, Object> claims) throws Exception {
        var b64 = Base64.getUrlEncoder().withoutPadding();
        String body =
                b64.encodeToString(
                                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
                                        .getBytes(StandardCharsets.UTF_8))
                        + "."
                        + b64.encodeToString(json.writeValueAsBytes(claims));
        return body
                + "."
                + b64.encodeToString(
                        Algorithm.HMAC256(SECRET).sign(body.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void wrongPasswordCountersCommitAndDefaultLockoutIsPreserved() throws Exception {
        SysUser u = user(1, "agent");
        for (int i = 1; i <= 5; i++) {
            status(login(u, "wrong"), 401);
            assertThat(users.findById(u.getId(), 1).getFailedLoginAttempts()).isEqualTo(i);
        }
        var locked = users.findById(u.getId(), 1);
        assertThat(locked.getLockedUntil())
                .isBetween(AuthService.now().plusSeconds(890), AuthService.now().plusSeconds(901));
        status(login(u, PASSWORD), 401);
        assertThat(users.findById(u.getId(), 1).getFailedLoginAttempts()).isEqualTo(5);
        identities.updateUser(
                u.getId(),
                1,
                Map.of("locked_until", AuthService.now().minusSeconds(1)),
                AuthService.now());
        var success = login(u, PASSWORD);
        status(success, 200);
        assertThat(success.getBody().fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "access_token", "token_type", "username", "role", "tenant_id");
        var fresh = users.findById(u.getId(), 1);
        assertThat(fresh.getFailedLoginAttempts()).isZero();
        assertThat(fresh.getLockedUntil()).isNull();
        assertThat(fresh.getPasswordHash()).startsWith("pbkdf2_sha256$600000$");
        assertThat(fresh.getAgentStatus()).isEqualTo("ready");
    }

    @Test
    void configuredLockoutAndPresenceRulesArePreserved() throws Exception {
        overrides.put("AUTH_MAX_FAILED_ATTEMPTS", "2");
        overrides.put("AUTH_LOCKOUT_SECONDS", "60");
        SysUser u = user(1, "agent");
        status(login(u, "wrong"), 401);
        status(login(u, "wrong"), 401);
        assertThat(users.findById(u.getId(), 1).getLockedUntil())
                .isBetween(AuthService.now().plusSeconds(50), AuthService.now().plusSeconds(61));
        overrides.put("WEBRTC_ENABLED", "true");
        SysUser ready = user(1, "agent");
        var logged = login(ready, PASSWORD);
        status(logged, 200);
        assertThat(users.findById(ready.getId(), 1).getAgentStatus()).isEqualTo("offline");
        String t = logged.getBody().get("access_token").asText();
        status(
                request(
                        HttpMethod.PUT,
                        "/api/v1/auth/presence",
                        bearer(t),
                        Map.of("status", "ready")),
                409);
        status(
                request(
                        HttpMethod.PUT,
                        "/api/v1/auth/presence",
                        bearer(t),
                        Map.of("status", "busy")),
                200);
        status(
                request(
                        HttpMethod.PUT,
                        "/api/v1/auth/presence",
                        bearer(adminToken),
                        Map.of("status", "busy")),
                409);
    }

    @Test
    void registeredBrowserCanBecomeReadyAndExpiredMediaCannot() throws Exception {
        overrides.put("WEBRTC_ENABLED", "true");
        var u = user(1, "agent");
        String token = token(u), key = "ai-outbound:agent-media:1:" + u.getId();
        status(
                request(
                        HttpMethod.PUT,
                        "/api/v1/auth/presence",
                        bearer(token),
                        Map.of("status", "ready")),
                409);
        media.set(
                key,
                "{\"registration_state\":\"registered\",\"microphone_permission\":\"denied\"}",
                60);
        status(
                request(
                        HttpMethod.PUT,
                        "/api/v1/auth/presence",
                        bearer(token),
                        Map.of("status", "ready")),
                409);
        media.set(
                key,
                "{\"registration_state\":\"registered\",\"microphone_permission\":\"granted\"}",
                60);
        status(
                request(
                        HttpMethod.PUT,
                        "/api/v1/auth/presence",
                        bearer(token),
                        Map.of("status", "ready")),
                200);
        assertThat(users.findById(u.getId(), 1).getAgentStatus()).isEqualTo("ready");
        media.delete(key);
        status(
                request(
                        HttpMethod.PUT,
                        "/api/v1/auth/presence",
                        bearer(token),
                        Map.of("status", "ready")),
                409);
    }

    @Test
    void logoutClearsOnlyMediaAndRevokesJwt() throws Exception {
        SysUser u = user(1, "agent");
        String t = token(u),
                mediaKey = "ai-outbound:agent-media:1:" + u.getId(),
                sipKey = "ai-outbound:sip-extension:agent_" + u.getId();
        media.set(mediaKey, "registered", 60);
        redis.opsForValue().set(sipKey, "credential", Duration.ofSeconds(60));
        status(request(HttpMethod.POST, "/api/v1/auth/logout", bearer(t), null), 200);
        assertThat(redis.hasKey(mediaKey)).isFalse();
        assertThat(redis.hasKey(sipKey)).isTrue();
        var row = users.findById(u.getId(), 1);
        assertThat(row.getTokenVersion()).isEqualTo(1);
        assertThat(row.getAgentStatus()).isEqualTo("offline");
        status(get("/api/v1/auth/me", bearer(t)), 401);
    }

    @Test
    void concurrentLogoutUsesAtomicVersionIncrement() throws Exception {
        SysUser u = user(1, "agent");
        var identity = new Identity(1, UserRecords.map(u), Set::of);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> auth.logout(identity));
            var b = executor.submit(() -> auth.logout(identity));
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(users.findById(u.getId(), 1).getTokenVersion()).isEqualTo(2);
    }

    @Test
    void userManagementLifecycleAndContractFields() throws Exception {
        String username = "created" + UUID.randomUUID().toString().replace("-", "");
        var body = Map.of("username", username, "password", PASSWORD, "full_name", "New Agent");
        var created = request(HttpMethod.POST, "/api/v1/admin/users", bearer(adminToken), body);
        status(created, 200);
        long id = created.getBody().get("id").asLong();
        assertThat(created.getBody().fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "id",
                        "tenant_id",
                        "username",
                        "full_name",
                        "role",
                        "is_supervisor",
                        "agent_status",
                        "last_seen_at",
                        "enabled",
                        "phone",
                        "failed_login_attempts",
                        "locked_until",
                        "last_login_at",
                        "created_at",
                        "updated_at");
        status(request(HttpMethod.POST, "/api/v1/admin/users", bearer(adminToken), body), 409);
        SysUser u = users.findById((int) id, 1);
        String old = token(u);
        status(
                request(
                        HttpMethod.PUT,
                        "/api/v1/admin/users/" + id,
                        bearer(adminToken),
                        Map.of("is_supervisor", true)),
                200);
        status(get("/api/v1/auth/me", bearer(old)), 401);
        old = token(u);
        status(
                request(
                        HttpMethod.POST,
                        "/api/v1/admin/users/" + id + "/reset-password",
                        bearer(adminToken),
                        Map.of("password", "changed-password")),
                200);
        status(get("/api/v1/auth/me", bearer(old)), 401);
        status(login(u, PASSWORD), 401);
        status(login(u, "changed-password"), 200);
        old = token(u);
        status(
                request(
                        HttpMethod.POST,
                        "/api/v1/admin/users/" + id + "/unlock",
                        bearer(adminToken),
                        null),
                200);
        status(get("/api/v1/auth/me", bearer(old)), 401);
        old = token(u);
        status(
                request(HttpMethod.DELETE, "/api/v1/admin/users/" + id, bearer(adminToken), null),
                200);
        status(get("/api/v1/auth/me", bearer(old)), 401);
        assertThat(users.findById((int) id, 1).getEnabled()).isFalse();
        assertThat(
                        audits.list(1, 0, 200).stream()
                                .filter(a -> String.valueOf(id).equals(a.getResourceId()))
                                .count())
                .isEqualTo(5);
    }

    @Test
    void currentAndLastAdministratorProtectionsRemain() {
        status(
                request(
                        HttpMethod.PUT,
                        "/api/v1/admin/users/" + admin.getId(),
                        bearer(adminToken),
                        Map.of("enabled", false)),
                409);
        grant();
        var last = request(HttpMethod.DELETE, "/api/v1/admin/users/" + admin.getId(), key(), null);
        status(last, 409);
        assertThat(last.getBody().get("detail").asText())
                .isEqualTo("at least one enabled administrator is required");
    }

    @Test
    void usernameEqualityAndLikeRemainCaseSensitive() throws Exception {
        SysUser u = user(1, "agent");
        identities.updateUser(
                u.getId(), 1, Map.of("username", "Case" + u.getId()), AuthService.now());
        var wrong =
                request(
                        HttpMethod.POST,
                        "/api/v1/auth/login",
                        new HttpHeaders(),
                        Map.of("username", "case" + u.getId(), "password", PASSWORD));
        status(wrong, 401);
        var filtered = get("/api/v1/admin/users?keyword=case" + u.getId(), bearer(adminToken));
        status(filtered, 200);
        assertThat(filtered.getBody()).isEmpty();
    }

    @Test
    void invalidInputsAndDuplicateCredentialHeadersRetainErrors() {
        status(
                request(
                        HttpMethod.POST,
                        "/api/v1/auth/login",
                        new HttpHeaders(),
                        Map.of("username", "", "password", PASSWORD)),
                400);
        HttpHeaders h = bearer(adminToken);
        h.add("Authorization", "Bearer duplicate");
        status(get("/api/v1/auth/me", h), 400);
    }

    @Test
    void disabledTenantRoleChangesAndClientCookiesCannotBypassChecks() throws Exception {
        SysUser u = user(1, "agent");
        String old = token(u);
        status(
                request(
                        HttpMethod.PUT,
                        "/api/v1/admin/users/" + u.getId(),
                        bearer(adminToken),
                        Map.of("role", "admin")),
                200);
        status(get("/api/v1/auth/me", bearer(old)), 401);
        String promoted = token(u);
        status(get("/api/v1/admin/users", bearer(promoted)), 200);
        status(
                request(
                        HttpMethod.DELETE,
                        "/api/v1/admin/users/" + u.getId(),
                        bearer(adminToken),
                        null),
                200);
        status(get("/api/v1/auth/me", bearer(promoted)), 401);
        Tenant tenant = tenants.findById(2);
        tenant.setEnabled(false);
        tenants.updateById(tenant);
        try {
            status(get("/api/v1/auth/me", bearer(token(other))), 403);
        } finally {
            tenant.setEnabled(true);
            tenants.updateById(tenant);
        }
        HttpHeaders cookies = new HttpHeaders();
        cookies.set("Cookie", "JSESSIONID=old-session; rememberMe=old-cookie");
        status(get("/api/v1/auth/me", cookies), 401);
    }

    @Test
    void criticalColumnCollationsRetainPostgresCaseAndSpaceComparisons() throws Exception {
        String[][] columns = {
            {"sys_user", "username"},
            {"tenant", "code"},
            {"contact", "phone"},
            {"phonesuppression", "phone"},
            {"taskoutbox", "idempotency_key"},
            {"taskreceipt", "idempotency_key"},
            {"webhookeventingest", "provider_event_key"},
            {"callbackinbox", "receipt_key"},
            {"campaign", "name"},
            {"scripttemplate", "name"},
            {"knowledgeitem", "title"},
            {"knowledgeitem", "keywords"},
            {"scenarioversion", "policy_json"},
            {"conversationstate", "policy_json"},
            {"conversationstate", "data_json"},
            {"adminsetting", "data_json"},
            {"taskoutbox", "payload_json"},
            {"scriptflowversion", "graph_json"}
        };
        try (var session = sessions.openSession()) {
            for (String[] column : columns) {
                String collation;
                try (var q =
                        session.getConnection()
                                .prepareStatement(
                                        "SELECT COLLATION_NAME FROM information_schema.COLUMNS"
                                                + " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND"
                                                + " COLUMN_NAME=?")) {
                    q.setString(1, column[0]);
                    q.setString(2, column[1]);
                    try (var r = q.executeQuery()) {
                        assertThat(r.next()).as(Arrays.toString(column)).isTrue();
                        collation = r.getString(1);
                    }
                }
                assertThat(collation).isEqualTo("utf8mb4_0900_bin");
                for (String[] pair :
                        new String[][] {
                            {"ABC", "abc"}, {"value", "value "}, {"{\"k\":\"A\"}", "{\"k\":\"a\"}"}
                        }) {
                    try (var q =
                            session.getConnection()
                                    .prepareStatement(
                                            "SELECT CONVERT(? USING utf8mb4) COLLATE "
                                                    + collation
                                                    + " = CONVERT(? USING utf8mb4) COLLATE "
                                                    + collation)) {
                        q.setString(1, pair[0]);
                        q.setString(2, pair[1]);
                        try (var r = q.executeQuery()) {
                            assertThat(r.next()).isTrue();
                            assertThat(r.getBoolean(1)).as(column[0] + "." + column[1]).isFalse();
                        }
                    }
                }
            }
        }
    }

    @Test
    void productionRedisClearFailureDoesNotRevokeToken() throws Exception {
        SysUser u = user(1, "agent");
        String t = token(u);
        overrides.put("ENV", "production");
        overrides.put("TRUSTED_HOSTS", "localhost,127.0.0.1");
        overrides.put("REDIS_URL", "rediss://127.0.0.1:9");
        status(request(HttpMethod.POST, "/api/v1/auth/logout", bearer(t), null), 503);
        assertThat(users.findById(u.getId(), 1).getTokenVersion()).isZero();
    }
}
