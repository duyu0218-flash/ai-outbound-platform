package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.auth.dao.SysUserMapper;
import com.duozhi.outbound.auth.dao.TenantMapper;
import com.duozhi.outbound.auth.domain.SysUser;
import com.duozhi.outbound.auth.domain.Tenant;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.call.service.CallLifecycle;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.job.worker.TaskWorker;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FrontendBrowserIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "frontend-http-synthetic-secret-over-thirty-two-characters");
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.flyway.url", MYSQL::getJdbcUrl);
        p.add("spring.flyway.user", () -> "root");
        p.add("spring.flyway.password", MYSQL::getPassword);
        p.add("spring.data.redis.host", () -> "127.0.0.1");
        p.add("spring.data.redis.port", () -> 9);
        p.add("REDIS_URL", () -> "");
        p.add("JAVA_DISPATCH_ENABLED", () -> "true");
        p.add("JAVA_WORKERS_ENABLED", () -> "false");
        p.add("OUTBOUND_PLATFORM_MAX_CONCURRENT", () -> 200);
        p.add("spring.ai.model.chat", () -> "none");
        p.add("RATE_LIMIT_BURST", () -> 2000);
        p.add("RATE_LIMIT_RPS", () -> 1000);
    }

    @LocalServerPort int port;
    @Autowired Store store;
    @Autowired SysUserMapper users;
    @Autowired TenantMapper tenants;
    @Autowired Crypto crypto;
    @Autowired CallLifecycle lifecycle;
    @Autowired TaskWorker worker;
    @Autowired org.springframework.boot.resttestclient.TestRestTemplate http;

    @Test
    void allVueEntrancesFormsSessionBoundariesAndMockEvidenceViewsWorkInChromium()
            throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(1);
        tenant.setCode("browser-tenant");
        tenant.setName("Browser tenant");
        tenants.insert(tenant);
        for (String role : List.of("admin", "agent", "supervisor")) {
            SysUser user = new SysUser();
            user.setTenantId(1);
            user.setUsername("browser-" + role);
            user.setFullName("浏览器" + role);
            user.setRole(role.equals("supervisor") ? "agent" : role);
            user.setIsSupervisor(role.equals("supervisor"));
            user.setPasswordHash(crypto.password("SyntheticBrowserPass123!"));
            users.insert(user);
        }
        store.insert(
                "adminsetting",
                Values.map(
                        "tenant_id",
                        1,
                        "section",
                        "compliance",
                        "data_json",
                        "{\"allowed_start_hour\":0,\"allowed_end_hour\":0}"));
        var call =
                store.insert(
                        "callsession",
                        Values.map(
                                "tenant_id",
                                1,
                                "phone",
                                "13100000001",
                                "mode",
                                "ai_only",
                                "status",
                                "in_ai",
                                "attempts",
                                1,
                                "max_attempts",
                                1));
        lifecycle.status(
                Values.map(
                        "call_id",
                        call.get("id"),
                        "kind",
                        "status",
                        "payload",
                        Values.map(
                                "event_id",
                                "browser-complete",
                                "status",
                                "completed",
                                "attempt",
                                1,
                                "billable_duration_sec",
                                120)));
        assertThat(worker.processLane(1L, worker.analysisLane())).isTrue();
        store.insert(
                "contact",
                Values.map(
                        "tenant_id",
                        1,
                        "phone",
                        "13100000004",
                        "name",
                        "Browser retry contact",
                        "consent_state",
                        "consented",
                        "consented_by",
                        "browser-test"));
        var retryCall =
                store.insert(
                        "callsession",
                        Values.map(
                                "tenant_id",
                                1,
                                "phone",
                                "13100000004",
                                "mode",
                                "ai_only",
                                "status",
                                "completed",
                                "attempts",
                                1,
                                "max_attempts",
                                2));
        var loginPage = http.getForEntity("/admin/login", String.class);
        assertThat(loginPage.getStatusCode().value()).as(String.valueOf(loginPage)).isEqualTo(200);
        assertThat(loginPage.getBody()).contains("id=\"app\"");
        Path log = Path.of("target/frontend-browser.log");
        var process =
                new ProcessBuilder("sh", "tools/test-frontend.sh")
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
        process.environment().put("MONO_BROWSER_URL", "http://127.0.0.1:" + port);
        process.environment().put("MONO_BROWSER_CALL_ID", call.get("id").toString());
        Process running = process.start();
        boolean finished = running.waitFor(180, TimeUnit.SECONDS);
        if (!finished) running.destroyForcibly();
        assertThat(finished).as("browser test timeout; %s", Files.readString(log)).isTrue();
        assertThat(running.exitValue()).as(Files.readString(log)).isZero();
        assertThat(Files.exists(Path.of("target/frontend-browser-result.json"))).isTrue();
    }
}
