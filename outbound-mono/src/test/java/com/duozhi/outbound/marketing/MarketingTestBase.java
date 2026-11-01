package com.duozhi.outbound.marketing;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.auth.dao.SysUserMapper;
import com.duozhi.outbound.auth.dao.TenantMapper;
import com.duozhi.outbound.auth.domain.SysUser;
import com.duozhi.outbound.auth.domain.Tenant;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.auth.support.UserRecords;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Shared harness for marketing module tests: one MySQL container and one Spring context for every
 * marketing test class, a fresh tenant with an admin and an agent per test.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class MarketingTestBase {
    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    private static final AtomicInteger SEQUENCE = new AtomicInteger(7000);

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "marketing-synthetic-secret-at-least-thirty-two-chars");
        p.add("MESSAGING_MASTER_KEY", () -> Base64.getEncoder().encodeToString(new byte[32]));
        p.add("MESSAGING_ENABLED", () -> "true");
        p.add("MESSAGING_WORKERS_ENABLED", () -> "false");
        p.add("MARKETING_WORKERS_ENABLED", () -> "false");
        // Explicit all-day contact window so admission results never depend on the hour a test
        // runs; window tests configure their tenant's mk_policy_config and a fixed clock.
        p.add("MARKETING_CONTACT_WINDOW", () -> "00:00-24:00");
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
        p.add("JAVA_DISPATCH_ENABLED", () -> "false");
        p.add("RATE_LIMIT_RPS", () -> 1000);
        p.add("RATE_LIMIT_BURST", () -> 2000);
    }

    @Autowired
    protected TestRestTemplate http;
    @Autowired
    protected Crypto crypto;
    @Autowired
    protected SysUserMapper users;
    @Autowired
    protected TenantMapper tenants;
    @Autowired
    protected JdbcTemplate jdbc;

    protected int tenant;
    protected String admin;
    protected String agent;

    @BeforeEach
    void marketingTenant() {
        tenant = SEQUENCE.getAndIncrement();
        Tenant t = new Tenant();
        t.setId(tenant);
        t.setCode("marketing-test-" + tenant);
        t.setName("Marketing synthetic");
        tenants.insert(t);
        admin = crypto.token(UserRecords.map(user("admin")));
        agent = crypto.token(UserRecords.map(user("agent")));
    }

    protected SysUser user(String role) {
        SysUser u = new SysUser();
        u.setTenantId(tenant);
        u.setUsername("marketing-" + role + "-" + UUID.randomUUID());
        u.setFullName("营销" + role);
        u.setRole(role);
        users.insert(u);
        return u;
    }

    protected ResponseEntity<JsonNode> req(
            String token, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    protected JsonNode ok(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        return r.getBody();
    }

    protected JsonNode admin(HttpMethod method, String path, Object body) {
        return ok(req(admin, method, path, body));
    }
}
