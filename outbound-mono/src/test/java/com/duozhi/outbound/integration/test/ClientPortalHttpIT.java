package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.auth.dao.SysUserMapper;
import com.duozhi.outbound.auth.dao.TenantMapper;
import com.duozhi.outbound.auth.domain.SysUser;
import com.duozhi.outbound.auth.domain.Tenant;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.auth.support.UserRecords;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.support.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ClientPortalHttpIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "client-portal-synthetic-secret-at-least-thirty-two-chars");
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
        p.add("TENANT_API_KEYS_JSON", () -> "{\"1\":\"client-portal-machine-key\"}");
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
    JdbcTemplate jdbc;
    static final AtomicInteger SEQUENCE = new AtomicInteger(900);
    int tenant;
    String admin;
    SysUser agent;

    @BeforeEach
    void setup() {
        tenant = SEQUENCE.getAndIncrement();
        Tenant t = new Tenant();
        t.setId(tenant);
        t.setCode("client-test-" + tenant);
        t.setName("Client synthetic");
        tenants.insert(t);
        admin = crypto.token(UserRecords.map(user("admin")));
        agent = user("agent");
    }

    SysUser user(String role) {
        SysUser u = new SysUser();
        u.setTenantId(tenant);
        u.setUsername("client-staff-" + UUID.randomUUID());
        u.setFullName("平台" + role);
        u.setRole(role);
        users.insert(u);
        return u;
    }

    ResponseEntity<JsonNode> req(String token, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    JsonNode ok(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        return r.getBody();
    }

    int status(ResponseEntity<?> r) {
        return r.getStatusCode().value();
    }

    JsonNode staff(HttpMethod method, String path, Object body) {
        return ok(req(admin, method, "/api/v1/ops" + path, body));
    }

    long org(String code) {
        return staff(HttpMethod.POST, "/clients", Map.of("code", code, "name", "企业" + code))
                .path("id")
                .asLong();
    }

    int campaign() {
        return ((Number)
                        store.insert(
                                        "campaign",
                                        Map.of(
                                                "tenant_id",
                                                tenant,
                                                "name",
                                                "任务" + UUID.randomUUID(),
                                                "mode",
                                                "ai_only",
                                                "status",
                                                "stopped",
                                                "concurrency",
                                                1))
                                .get("id"))
                .intValue();
    }

    void bind(int campaign, Long client) {
        staff(
                HttpMethod.PUT,
                "/campaigns/" + campaign + "/client",
                JsonSupport.map("client_id", client));
    }

    Map<String, Object> contact(String phone) {
        return store.insert(
                "contact",
                JsonSupport.map(
                        "tenant_id",
                        tenant,
                        "phone",
                        phone,
                        "name",
                        "王先生",
                        "consent_state",
                        "consented"));
    }

    String phone() {
        return "139" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode() % 100000000));
    }

    Object call(int campaign, Map<String, Object> contact) {
        return store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                tenant,
                                "campaign_id",
                                campaign,
                                "contact_id",
                                contact.get("id"),
                                "phone",
                                contact.get("phone"),
                                "mode",
                                "ai_only",
                                "status",
                                "completed",
                                "attempts",
                                1))
                .get("id");
    }

    void review(Object call, String result) {
        ok(
                req(
                        admin,
                        HttpMethod.PUT,
                        "/api/v1/calls/" + call + "/analysis",
                        Map.of("result_code", result, "summary", "客户 13912345678 有购买意向")));
    }

    /** Opens an owner account, replaces the one-time password and returns a usable token. */
    String owner(long client, String username) {
        String initial =
                staff(
                                HttpMethod.POST,
                                "/clients/" + client + "/users",
                                Map.of("username", username, "full_name", "企业管理员"))
                        .path("initial_password")
                        .asText();
        String token = login(username, initial);
        assertThat(status(req(token, HttpMethod.GET, "/api/v1/client/leads", null))).isEqualTo(403);
        return ok(req(
                        token,
                        HttpMethod.POST,
                        "/api/v1/client/auth/password",
                        Map.of("old_password", initial, "new_password", "NewPassw0rd2026")))
                .path("access_token")
                .asText();
    }

    String login(String username, String password) {
        return ok(req(
                        null,
                        HttpMethod.POST,
                        "/api/v1/client/auth/login",
                        Map.of("username", username, "password", password)))
                .path("access_token")
                .asText();
    }

    JsonNode leads(String token, String query) {
        return ok(req(token, HttpMethod.GET, "/api/v1/client/leads?" + query, null));
    }

    @Test
    void principalsAreIsolatedInBothDirections() {
        long a = org("iso-a-" + tenant);
        long b = org("iso-b-" + tenant);
        String tokenA = owner(a, "iso-a-" + tenant);
        String tokenB = owner(b, "iso-b-" + tenant);
        int campaign = campaign();
        bind(campaign, a);
        Object call = call(campaign, contact(phone()));
        review(call, "qualified_lead");
        long lead = leads(tokenA, "").path("items").get(0).path("id").asLong();

        assertThat(status(req(tokenB, HttpMethod.GET, "/api/v1/client/leads/" + lead, null)))
                .isEqualTo(404);
        assertThat(leads(tokenB, "").path("total").asInt()).isZero();
        for (String path :
                List.of(
                        "/api/v1/calls",
                        "/api/v1/ops/clients",
                        "/api/v1/admin/users",
                        "/api/v1/auth/me"))
            assertThat(status(req(tokenA, HttpMethod.GET, path, null))).as(path).isEqualTo(401);
        assertThat(status(req(admin, HttpMethod.GET, "/api/v1/client/me", null))).isEqualTo(403);
        HttpHeaders key = new HttpHeaders();
        key.set("x-api-key", "client-portal-machine-key");
        assertThat(
                        http.exchange(
                                        "/api/v1/client/leads",
                                        HttpMethod.GET,
                                        new HttpEntity<>(key),
                                        JsonNode.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        assertThat(status(req(null, HttpMethod.GET, "/api/v1/client/leads", null))).isEqualTo(401);
    }

    @Test
    void onlyReviewedQualifiedResultsAreDeliveredAndQcChangesWithdraw() {
        long client = org("life-" + tenant);
        String token = owner(client, "life-" + tenant);
        int campaign = campaign();
        bind(campaign, client);
        var contact = contact(phone());
        Object call = call(campaign, contact);
        ok(req(admin, HttpMethod.GET, "/api/v1/calls/" + call + "/analysis", null));
        assertThat(leads(token, "").path("total").asInt()).as("automatic only").isZero();

        review(call, "qualified_lead");
        review(call, "qualified_lead");
        JsonNode page = leads(token, "");
        assertThat(page.path("total").asInt()).isEqualTo(1);
        JsonNode item = page.path("items").get(0);
        assertThat(item.path("phone").asText())
                .isEqualTo(
                        contact.get("phone").toString().substring(0, 3)
                                + "****"
                                + contact.get("phone").toString().substring(7));
        assertThat(item.path("summary").asText())
                .contains("139****5678")
                .doesNotContain("13912345678");
        assertThat(item.has("call_session_id")).isFalse();
        assertThat(item.path("unread").asBoolean()).isTrue();

        review(call, "rejected");
        JsonNode withdrawn = leads(token, "status=withdrawn").path("items").get(0);
        assertThat(withdrawn.path("withdraw_reason").asText()).isEqualTo("qc_changed");
        assertThat(leads(token, "status=delivered").path("total").asInt()).isZero();

        review(call, "qualified_lead");
        assertThat(leads(token, "status=delivered").path("total").asInt()).isEqualTo(1);

        // Same phone in the dedup window: a second reviewed call is recorded but not delivered.
        Object again = call(campaign, contact);
        review(again, "qualified_lead");
        assertThat(leads(token, "").path("total").asInt()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM client_lead_event WHERE tenant_id=? AND action='dup_hit'",
                                Long.class,
                                tenant))
                .isEqualTo(1);

        long leadId = leads(token, "").path("items").get(0).path("id").asLong();
        staff(
                HttpMethod.POST,
                "/clients/" + client + "/leads/" + leadId + "/withdraw",
                Map.of("note", "客户重复采购"));
        review(call, "qualified_lead");
        assertThat(
                        leads(token, "status=withdrawn")
                                .path("items")
                                .get(0)
                                .path("withdraw_reason")
                                .asText())
                .as("manual withdrawal is not revived by a later review")
                .isEqualTo("manual");

        JsonNode detail =
                staff(
                        HttpMethod.GET,
                        "/clients/" + client + "/leads/" + item.path("id").asLong(),
                        null);
        assertThat(detail.path("events").toString()).contains("withdrawn", "redelivered");
    }

    @Test
    void bindingFollowsOnlyLaterDeliveriesAndIsAudited() {
        long a = org("bind-a-" + tenant);
        long b = org("bind-b-" + tenant);
        String tokenA = owner(a, "bind-a-" + tenant);
        String tokenB = owner(b, "bind-b-" + tenant);
        int campaign = campaign();
        Object early = call(campaign, contact(phone()));
        review(early, "qualified_lead");
        bind(campaign, a);
        assertThat(leads(tokenA, "").path("total").asInt()).as("no history by default").isZero();

        Object first = call(campaign, contact(phone()));
        review(first, "qualified_lead");
        bind(campaign, b);
        Object second = call(campaign, contact(phone()));
        review(second, "qualified_lead");
        assertThat(leads(tokenA, "").path("total").asInt()).isEqualTo(1);
        assertThat(leads(tokenB, "").path("total").asInt()).isEqualTo(1);

        JsonNode backfill =
                staff(
                        HttpMethod.POST,
                        "/clients/" + b + "/backfill",
                        Map.of("campaign_id", campaign));
        assertThat(backfill.path("processed").asInt()).isEqualTo(3);
        // The earliest call becomes B's; the first stays with A and is not duplicated to B.
        assertThat(leads(tokenB, "").path("total").asInt()).isEqualTo(2);
        assertThat(leads(tokenA, "").path("total").asInt()).isEqualTo(1);

        bind(campaign, null);
        review(call(campaign, contact(phone())), "qualified_lead");
        assertThat(leads(tokenB, "").path("total").asInt()).isEqualTo(2);
        assertThat(staff(HttpMethod.GET, "/campaigns/" + campaign + "/client/log", null))
                .hasSize(3);
        assertThat(staff(HttpMethod.GET, "/clients/bindings?campaign_ids=" + campaign, null))
                .isEmpty();

        JsonNode org = staff(HttpMethod.GET, "/clients/" + a, null);
        staff(
                HttpMethod.PUT,
                "/clients/" + a,
                Map.of("name", org.path("name").asText(), "status", "disabled", "revision", 0));
        assertThat(
                        status(
                                req(
                                        admin,
                                        HttpMethod.PUT,
                                        "/api/v1/ops/campaigns/" + campaign + "/client",
                                        Map.of("client_id", a))))
                .isEqualTo(422);
    }

    @Test
    void accountsAreRevokedByDisableExpiryAndPasswordChange() {
        long client = org("acct-" + tenant);
        String username = "acct-" + tenant;
        String token = owner(client, username);
        ok(req(token, HttpMethod.GET, "/api/v1/client/me", null));
        String fresh = login(username, "NewPassw0rd2026");
        assertThat(
                        status(
                                req(
                                        null,
                                        HttpMethod.POST,
                                        "/api/v1/client/auth/login",
                                        Map.of(
                                                "username",
                                                username,
                                                "password",
                                                "wrong-password1"))))
                .isEqualTo(401);

        long uid =
                staff(HttpMethod.GET, "/clients/" + client + "/users", null)
                        .get(0)
                        .path("id")
                        .asLong();
        staff(HttpMethod.PUT, "/clients/" + client + "/users/" + uid, Map.of("action", "revoke"));
        assertThat(status(req(fresh, HttpMethod.GET, "/api/v1/client/me", null))).isEqualTo(401);

        String again = login(username, "NewPassw0rd2026");
        staff(
                HttpMethod.PUT,
                "/clients/" + client,
                Map.of("name", "企业", "contract_end", "2020-01-01", "revision", 0));
        assertThat(status(req(again, HttpMethod.GET, "/api/v1/client/me", null))).isEqualTo(401);
        assertThat(
                        status(
                                req(
                                        null,
                                        HttpMethod.POST,
                                        "/api/v1/client/auth/login",
                                        Map.of(
                                                "username",
                                                username,
                                                "password",
                                                "NewPassw0rd2026"))))
                .isEqualTo(403);
        assertThat(
                        status(
                                req(
                                        admin,
                                        HttpMethod.PUT,
                                        "/api/v1/ops/clients/" + client,
                                        Map.of("name", "企业", "revision", 0))))
                .as("stale revision")
                .isEqualTo(409);
    }

    @Test
    void followDisputeMembersPhoneAndExport() {
        long client = org("work-" + tenant);
        String owner = owner(client, "work-" + tenant);
        int campaign = campaign();
        bind(campaign, client);
        var contact = contact(phone());
        review(call(campaign, contact), "qualified_lead");
        review(call(campaign, contact(phone())), "qualified_lead");
        JsonNode first = leads(owner, "").path("items").get(0);
        long id = first.path("id").asLong();

        JsonNode detail = ok(req(owner, HttpMethod.GET, "/api/v1/client/leads/" + id, null));
        assertThat(detail.path("unread").asBoolean()).isFalse();
        assertThat(
                        status(
                                req(
                                        owner,
                                        HttpMethod.PUT,
                                        "/api/v1/client/leads/" + id + "/follow",
                                        Map.of(
                                                "follow_state",
                                                "invalid",
                                                "revision",
                                                detail.path("revision").asInt()))))
                .as("reason required")
                .isEqualTo(422);
        JsonNode followed =
                ok(
                        req(
                                owner,
                                HttpMethod.PUT,
                                "/api/v1/client/leads/" + id + "/follow",
                                Map.of(
                                        "follow_state",
                                        "invalid",
                                        "invalid_reason",
                                        "wrong_person",
                                        "revision",
                                        detail.path("revision").asInt())));
        assertThat(
                        status(
                                req(
                                        owner,
                                        HttpMethod.PUT,
                                        "/api/v1/client/leads/" + id + "/follow",
                                        Map.of(
                                                "follow_state",
                                                "contacted",
                                                "revision",
                                                detail.path("revision").asInt()))))
                .as("stale revision")
                .isEqualTo(409);
        ok(
                req(
                        owner,
                        HttpMethod.POST,
                        "/api/v1/client/leads/" + id + "/dispute",
                        Map.of("note", "接听人不是本人")));
        assertThat(
                        status(
                                req(
                                        owner,
                                        HttpMethod.POST,
                                        "/api/v1/client/leads/" + id + "/dispute",
                                        Map.of("note", "again"))))
                .isEqualTo(409);
        JsonNode decided =
                staff(
                        HttpMethod.PUT,
                        "/clients/" + client + "/leads/" + id + "/dispute",
                        Map.of("decision", "accepted", "note", "核实为非本人"));
        assertThat(decided.path("dispute_state").asText()).isEqualTo("accepted");
        assertThat(followed.path("follow_state").asText()).isEqualTo("invalid");

        // Phone reveal is off by default, then audited when the platform enables it.
        assertThat(
                        status(
                                req(
                                        owner,
                                        HttpMethod.POST,
                                        "/api/v1/client/leads/" + id + "/phone",
                                        null)))
                .isEqualTo(403);
        JsonNode settings = staff(HttpMethod.GET, "/clients/" + client + "/settings", null);
        staff(
                HttpMethod.PUT,
                "/clients/" + client + "/settings",
                Map.of(
                        "revision",
                        settings.path("revision").asInt(),
                        "phone_reveal",
                        true,
                        "export",
                        Map.of("enabled", true, "daily_limit", 2)));
        long other = leads(owner, "").path("items").get(1).path("id").asLong();
        String full =
                ok(req(owner, HttpMethod.POST, "/api/v1/client/leads/" + id + "/phone", null))
                        .path("phone")
                        .asText();
        assertThat(full).hasSize(11).doesNotContain("*");
        jdbc.update("UPDATE contact SET dnc=TRUE WHERE tenant_id=? AND phone=?", tenant, full);
        assertThat(
                        status(
                                req(
                                        owner,
                                        HttpMethod.POST,
                                        "/api/v1/client/leads/" + id + "/phone",
                                        null)))
                .isEqualTo(403);

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(owner);
        var csv =
                http.exchange(
                        "/api/v1/client/leads/export",
                        HttpMethod.GET,
                        new HttpEntity<>(h),
                        String.class);
        assertThat(csv.getStatusCode().value()).isEqualTo(200);
        assertThat(csv.getBody()).contains("交付时间").doesNotContain(full);
        assertThat(
                        http.exchange(
                                        "/api/v1/client/leads/export",
                                        HttpMethod.GET,
                                        new HttpEntity<>(h),
                                        String.class)
                                .getStatusCode()
                                .value())
                .as("daily limit")
                .isEqualTo(429);

        // Members see only what is assigned to them and cannot manage the organisation.
        String initial =
                ok(req(
                                owner,
                                HttpMethod.POST,
                                "/api/v1/client/members",
                                Map.of("username", "member-" + tenant, "full_name", "销售小李")))
                        .path("initial_password")
                        .asText();
        long memberId =
                ok(req(owner, HttpMethod.GET, "/api/v1/client/members", null))
                        .get(1)
                        .path("id")
                        .asLong();
        String member = login("member-" + tenant, initial);
        member =
                ok(req(
                                member,
                                HttpMethod.POST,
                                "/api/v1/client/auth/password",
                                Map.of("old_password", initial, "new_password", "MemberPass2026")))
                        .path("access_token")
                        .asText();
        assertThat(leads(member, "").path("total").asInt()).isZero();
        ok(
                req(
                        owner,
                        HttpMethod.POST,
                        "/api/v1/client/leads/assign",
                        Map.of("lead_ids", List.of(other), "assignee_id", memberId)));
        assertThat(leads(member, "").path("total").asInt()).isEqualTo(1);
        assertThat(status(req(member, HttpMethod.GET, "/api/v1/client/leads/" + id, null)))
                .isEqualTo(404);
        assertThat(
                        status(
                                req(
                                        member,
                                        HttpMethod.POST,
                                        "/api/v1/client/members",
                                        Map.of("username", "x-" + tenant, "full_name", "x"))))
                .isEqualTo(403);
        assertThat(
                        status(
                                req(
                                        member,
                                        HttpMethod.POST,
                                        "/api/v1/client/leads/" + other + "/phone",
                                        null)))
                .as("member without grant")
                .isEqualTo(403);
        assertThat(
                        ok(req(owner, HttpMethod.GET, "/api/v1/client/stats?days=7", null))
                                .path("summary")
                                .path("total")
                                .asInt())
                .isEqualTo(2);
    }

    @Test
    void deliverySpecialistsOnlyManageAssignedClients() {
        long mine = org("spec-a-" + tenant);
        long others = org("spec-b-" + tenant);
        String agentToken = crypto.token(UserRecords.map(agent));
        assertThat(ok(req(agentToken, HttpMethod.GET, "/api/v1/ops/clients", null))).isEmpty();
        staff(
                HttpMethod.PUT,
                "/clients/" + mine + "/staff",
                Map.of("user_ids", List.of(agent.getId())));
        JsonNode visible = ok(req(agentToken, HttpMethod.GET, "/api/v1/ops/clients", null));
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).path("id").asLong()).isEqualTo(mine);
        assertThat(status(req(agentToken, HttpMethod.GET, "/api/v1/ops/clients/" + others, null)))
                .isEqualTo(404);
        assertThat(
                        status(
                                req(
                                        agentToken,
                                        HttpMethod.POST,
                                        "/api/v1/ops/clients",
                                        Map.of("code", "nope-" + tenant, "name", "x"))))
                .isEqualTo(403);
        int campaign = campaign();
        ok(
                req(
                        agentToken,
                        HttpMethod.PUT,
                        "/api/v1/ops/campaigns/" + campaign + "/client",
                        Map.of("client_id", mine)));
        assertThat(
                        status(
                                req(
                                        agentToken,
                                        HttpMethod.PUT,
                                        "/api/v1/ops/campaigns/" + campaign + "/client",
                                        Map.of("client_id", others))))
                .isEqualTo(404);
        assertThat(
                        ok(req(
                                        agentToken,
                                        HttpMethod.POST,
                                        "/api/v1/ops/clients/" + mine + "/users",
                                        Map.of(
                                                "username",
                                                "spec-owner-" + tenant,
                                                "full_name",
                                                "x")))
                                .path("initial_password")
                                .asText())
                .hasSizeGreaterThan(10);
    }
}
