package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.agent.controller.Handoffs;
import com.duozhi.outbound.ai.service.ConversationPolicy;
import com.duozhi.outbound.auth.dao.SysUserMapper;
import com.duozhi.outbound.auth.dao.TenantMapper;
import com.duozhi.outbound.auth.domain.SysUser;
import com.duozhi.outbound.auth.domain.Tenant;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.auth.support.UserRecords;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.operations.controller.Operations;
import com.duozhi.outbound.operations.controller.OperationsWorkforce;
import com.duozhi.outbound.operations.dao.OperationsStore;
import com.fasterxml.jackson.databind.*;
import java.util.*;
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
class OperationsHttpIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add(
                "JWT_SECRET",
                () -> "operations-http-synthetic-secret-at-least-thirty-two-characters");
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
    }

    @Autowired TestRestTemplate http;
    @Autowired Crypto crypto;
    @Autowired Store store;
    @Autowired OperationsStore operations;
    @Autowired OperationsWorkforce workforce;
    @Autowired Handoffs handoffs;
    @Autowired ConversationPolicy conversation;
    @Autowired Transactions tx;
    @Autowired SysUserMapper users;
    @Autowired TenantMapper tenants;
    @Autowired ObjectMapper json;
    static final AtomicInteger SEQUENCE = new AtomicInteger(500);
    int tenant;
    String adminToken, token;
    SysUser admin, agent, other, lead;

    @BeforeEach
    void setup() {
        tenant = SEQUENCE.getAndAdd(2);
        for (int id : List.of(tenant, tenant + 1)) {
            Tenant t = new Tenant();
            t.setId(id);
            t.setCode("ops-test-" + id);
            t.setName("Ops synthetic");
            tenants.insert(t);
        }
        admin = user("admin");
        agent = user("agent");
        other = user("agent");
        lead = user("agent");
        adminToken = crypto.token(UserRecords.map(admin));
        token = adminToken;
    }

    SysUser user(String role) {
        SysUser u = new SysUser();
        u.setTenantId(tenant);
        u.setUsername("ops-" + UUID.randomUUID());
        u.setFullName("运营测试" + role);
        u.setRole(role);
        users.insert(u);
        return u;
    }

    ResponseEntity<JsonNode> req(HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    JsonNode ok(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        return r.getBody();
    }

    JsonNode get(String path) {
        return ok(req(HttpMethod.GET, "/api/v1/ops" + path, null));
    }

    JsonNode post(String path, Object b) {
        return ok(req(HttpMethod.POST, "/api/v1/ops" + path, b));
    }

    Map<String, Object> contact(String name) {
        return store.insert(
                "contact",
                JsonSupport.map(
                        "tenant_id",
                        tenant,
                        "phone",
                        "131"
                                + String.format(
                                        "%08d", Math.abs(UUID.randomUUID().hashCode() % 100000000)),
                        "name",
                        name,
                        "consent_state",
                        "consented"));
    }

    Map<String, Object> call(Object contact, Object human) {
        var c = store.require("contact", tenant, contact);
        return store.insert(
                "callsession",
                JsonSupport.map(
                        "tenant_id",
                        tenant,
                        "contact_id",
                        contact,
                        "phone",
                        c.get("phone"),
                        "human_agent_id",
                        human,
                        "mode",
                        "ai_only",
                        "status",
                        "completed",
                        "attempts",
                        1));
    }

    Map<String, Object> change(String action, int revision) {
        return JsonSupport.map(
                "action", action, "revision", revision, "event_key", UUID.randomUUID().toString());
    }

    JsonNode patch(Object id, Map<String, Object> b) {
        return ok(req(HttpMethod.PATCH, "/api/v1/ops/leads/" + id, b));
    }

    JsonNode team() {
        return post(
                "/teams",
                Map.of(
                        "name",
                        "运营一组",
                        "members",
                        List.of(
                                Map.of("user_id", lead.getId(), "is_lead", true),
                                Map.of("user_id", agent.getId(), "is_lead", false)),
                        "settings",
                        Map.of(
                                "after_call_seconds",
                                30,
                                "queue_timeout_seconds",
                                45,
                                "personal_override",
                                false)));
    }

    void assign(Object id, Object owner) {
        var b = change("assign", 0);
        b.put("owner_id", owner);
        patch(id, b);
    }

    @Test
    void searchCountExportUseSameFiltersAndRejectBadRanges() {
        var a = contact("A");
        var b = contact("B");
        call(a.get("id"), agent.getId());
        call(b.get("id"), other.getId());
        String q = "phone=" + a.get("phone") + "&status=completed&agent_id=" + agent.getId();
        var data = get("/calls?" + q);
        assertThat(data.path("total").asInt()).isEqualTo(1);
        assertThat(data.path("rows")).hasSize(1);
        assertThat(data.path("rows").get(0).path("status").asText()).isEqualTo("completed");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        var csv =
                http.exchange(
                        "/api/v1/ops/calls/export?" + q,
                        HttpMethod.GET,
                        new HttpEntity<>(h),
                        String.class);
        assertThat(csv.getStatusCode().value()).isEqualTo(200);
        assertThat(csv.getBody())
                .contains(a.get("phone").toString())
                .doesNotContain(b.get("phone").toString());
        assertThat(
                        req(HttpMethod.GET, "/api/v1/ops/calls?min_seconds=50&max_seconds=2", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        assertThat(
                        req(HttpMethod.GET, "/api/v1/ops/calls?from=invalid", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        assertThat(get("/calls?phone=999999999999999").path("total").asInt()).isZero();
    }

    @Test
    void claimIsVersionedIdempotentAndCannotBeReusedForDifferentPayload() {
        Object id = contact("领取").get("id");
        token = crypto.token(UserRecords.map(agent));
        var b = change("claim", 0);
        patch(id, b);
        assertThat(patch(id, b).path("replayed").asBoolean()).isTrue();
        assertThat(get("/leads/" + id).path("history")).hasSize(1);
        var different = new LinkedHashMap<>(b);
        different.put("note", "different");
        assertThat(
                        req(HttpMethod.PATCH, "/api/v1/ops/leads/" + id, different)
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        token = crypto.token(UserRecords.map(other));
        assertThat(
                        req(HttpMethod.PATCH, "/api/v1/ops/leads/" + id, change("claim", 0))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        assertThat(req(HttpMethod.GET, "/api/v1/ops/leads/" + id, null).getStatusCode().value())
                .isEqualTo(403);
    }

    @Test
    void teamLeaderSeesOnlyTeamAndRevocationDoesNotRestoreTenantAccess() {
        lead.setIsSupervisor(true);
        users.updateById(lead, tenant);
        JsonNode t = team();
        Object a = contact("组内").get("id"), b = contact("组外").get("id");
        assign(a, agent.getId());
        assign(b, other.getId());
        var ca = call(a, agent.getId());
        var cb = call(b, other.getId());
        token = crypto.token(UserRecords.map(lead));
        assertThat(get("/leads?view=all").path("total").asInt()).isEqualTo(1);
        assertThat(get("/calls").path("total").asInt()).isEqualTo(1);
        assertThat(
                        req(HttpMethod.GET, "/api/v1/calls/" + ca.get("id"), null)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(
                        req(HttpMethod.GET, "/api/v1/calls/" + cb.get("id") + "/recordings", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        assertThat(req(HttpMethod.GET, "/api/v1/quality/reviews", null).getStatusCode().value())
                .isEqualTo(403);
        assertThat(req(HttpMethod.POST, "/api/v1/ops/teams", Map.of()).getStatusCode().value())
                .isEqualTo(403);
        token = adminToken;
        post(
                "/teams",
                Map.of(
                        "id",
                        t.path("id").asLong(),
                        "revision",
                        0,
                        "name",
                        "运营一组",
                        "members",
                        List.of(),
                        "settings",
                        Map.of()));
        token = crypto.token(UserRecords.map(lead));
        assertThat(get("/calls").path("total").asInt()).isZero();
    }

    @Test
    void maskingExportPermissionAndRecordPermissionAreEnforcedServerSide() {
        var c = contact("脱敏");
        assign(c.get("id"), agent.getId());
        var call = call(c.get("id"), agent.getId());
        store.update(
                "callsession",
                tenant,
                call.get("id"),
                Map.of("recording_url", "https://recording.example/synthetic"));
        token = crypto.token(UserRecords.map(agent));
        String masked = get("/calls").path("rows").get(0).path("phone").asText();
        assertThat(masked).contains("*").isNotEqualTo(c.get("phone"));
        assertThat(req(HttpMethod.GET, "/api/v1/ops/calls/export", null).getStatusCode().value())
                .isEqualTo(403);
        token = adminToken;
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/permissions/" + agent.getId(),
                        Map.of("can_export", true, "can_unmask", false, "can_record", false)));
        token = crypto.token(UserRecords.map(agent));
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        assertThat(
                        http.exchange(
                                        "/api/v1/ops/calls/export",
                                        HttpMethod.GET,
                                        new HttpEntity<>(h),
                                        String.class)
                                .getBody())
                .contains(masked)
                .doesNotContain(c.get("phone").toString());
        assertThat(
                        req(HttpMethod.GET, "/api/v1/calls/" + call.get("id") + "/recordings", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        assertThat(
                        ok(req(HttpMethod.GET, "/api/v1/calls/" + call.get("id"), null))
                                .path("recording_url")
                                .isNull())
                .isTrue();
    }

    @Test
    void batchAssignmentRollsBackOnStaleRowAndFollowHistoryNotifiesOwner() {
        Object a = contact("分配A").get("id"), b = contact("分配B").get("id");
        var batch =
                JsonSupport.map(
                        "owner_id",
                        agent.getId(),
                        "items",
                        List.of(
                                JsonSupport.map(
                                        "contact_id",
                                        a,
                                        "revision",
                                        0,
                                        "event_key",
                                        UUID.randomUUID().toString()),
                                JsonSupport.map(
                                        "contact_id",
                                        b,
                                        "revision",
                                        9,
                                        "event_key",
                                        UUID.randomUUID().toString())));
        assertThat(req(HttpMethod.POST, "/api/v1/ops/leads/batch", batch).getStatusCode().value())
                .isEqualTo(409);
        assertThat(get("/leads/" + a).path("owner_id").isNull()).isTrue();
        assign(a, agent.getId());
        token = crypto.token(UserRecords.map(agent));
        var follow = change("follow", 1);
        follow.put("stage", "in_progress");
        follow.put("note", "已联系，下次回访");
        follow.put(
                "next_at", Store.now().plusDays(1).atOffset(java.time.ZoneOffset.UTC).toString());
        patch(a, follow);
        var detail = get("/leads/" + a);
        assertThat(detail.path("stage").asText()).isEqualTo("in_progress");
        assertThat(detail.path("history")).hasSize(2);
        var notices = get("/notices");
        assertThat(notices).hasSize(1);
        post("/notices/" + notices.get(0).path("id").asLong() + "/read", null);
        assertThat(get("/notices").get(0).path("read_at").isNull()).isFalse();
    }

    @Test
    void formVersionsRequiredFieldsAndTagsArePersistedWithManualProvenance() {
        Object id = contact("补录").get("id");
        assign(id, agent.getId());
        var conf =
                Map.of(
                        "fields",
                        List.of(
                                Map.of(
                                        "key",
                                        "city",
                                        "label",
                                        "城市",
                                        "type",
                                        "choice",
                                        "required",
                                        true,
                                        "choices",
                                        List.of("北京", "上海"))));
        post(
                "/configs",
                Map.of("kind", "form", "name", "客户补录", "config", conf, "expected_revision", 0));
        var form = get("/configs?kind=form").get(0);
        var body = change("form", 1);
        body.put("form_id", form.path("id").asLong());
        body.put("fields", Map.of());
        assertThat(req(HttpMethod.PATCH, "/api/v1/ops/leads/" + id, body).getStatusCode().value())
                .isEqualTo(422);
        body.put("fields", Map.of("city", "北京"));
        patch(id, body);
        post(
                "/configs",
                Map.of("kind", "form", "name", "客户补录", "config", conf, "expected_revision", 1));
        assertThat(get("/leads/" + id).path("form_revision").asInt()).isEqualTo(1);
        assertThat(get("/configs?kind=form")).hasSize(2);
        post(
                "/configs",
                Map.of(
                        "kind",
                        "tag",
                        "name",
                        "重点",
                        "config",
                        Map.of("target", "contact"),
                        "expected_revision",
                        0));
        var tag = change("tags", 2);
        tag.put("tags", List.of("重点"));
        patch(id, tag);
        assertThat(get("/leads?tag=重点&view=all").path("total").asInt()).isEqualTo(1);
        assertThat(Operations.csvCell("=1+1")).isEqualTo("'=1+1");
    }

    @Test
    void personalAndTeamWorkspaceControlsActualReadyAdmission() {
        team();
        token = crypto.token(UserRecords.map(agent));
        assertThat(get("/workspace").path("after_call_seconds").asInt()).isEqualTo(30);
        assertThat(
                        req(
                                        HttpMethod.POST,
                                        "/api/v1/ops/workspace",
                                        Map.of("expected_revision", 0, "after_call_seconds", 1))
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        tx.run(
                () -> {
                    workforce.release(tenant, agent.getId());
                    return null;
                });
        assertThat(get("/workspace").path("remaining_seconds").asInt()).isPositive();
        assertThat(
                        req(HttpMethod.PUT, "/api/v1/auth/presence", Map.of("status", "ready"))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
    }

    @Test
    void reportsViewsAndForeignTenantBoundaries() {
        Object a = contact("报表").get("id");
        call(a, agent.getId());
        assertThat(
                        get("/reports?dimension=agent&granularity=all")
                                .path("rows")
                                .get(0)
                                .path("sessions")
                                .asInt())
                .isEqualTo(1);
        post(
                "/views",
                Map.of(
                        "kind",
                        "reports",
                        "name",
                        "按坐席",
                        "config",
                        Map.of(
                                "filters",
                                Map.of("dimension", "agent"),
                                "columns",
                                List.of("sessions"))));
        assertThat(get("/views")).hasSize(1);
        token = crypto.token(UserRecords.map(agent));
        assertThat(get("/views")).isEmpty();
        token = adminToken;
        var foreign =
                store.insert(
                        "contact",
                        JsonSupport.map("tenant_id", tenant + 1, "phone", "13100009999"));
        assertThat(
                        req(
                                        HttpMethod.PATCH,
                                        "/api/v1/ops/leads/" + foreign.get("id"),
                                        change("claim", 0))
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
    }

    @Test
    void failedDeliveryCanRetryButLiveOrWrongKindCannot() {
        var task =
                store.insert(
                        "taskoutbox",
                        JsonSupport.map(
                                "tenant_id",
                                tenant,
                                "task_type",
                                "business_callback",
                                "aggregate_id",
                                UUID.randomUUID().toString(),
                                "idempotency_key",
                                UUID.randomUUID().toString(),
                                "payload_json",
                                "{}",
                                "state",
                                "failed",
                                "attempts",
                                3));
        assertThat(get("/deliveries")).hasSize(1);
        post("/deliveries/" + task.get("id") + "/retry", null);
        assertThat(store.require("taskoutbox", tenant, task.get("id")).get("state"))
                .isEqualTo("pending");
        assertThat(
                        req(
                                        HttpMethod.POST,
                                        "/api/v1/ops/deliveries/" + task.get("id") + "/retry",
                                        null)
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
    }

    @Test
    void objectTagFiltersAndLookupScopeMatchCalls() {
        Object id = contact("通话标签").get("id");
        var c = call(id, agent.getId());
        post(
                "/configs",
                Map.of(
                        "kind",
                        "tag",
                        "name",
                        "通话重点",
                        "config",
                        Map.of("target", "call"),
                        "expected_revision",
                        0));
        post("/tags", Map.of("target", "call", "object_id", c.get("id"), "tags", List.of("通话重点")));
        assertThat(get("/calls?tag=通话重点").path("total").asInt()).isEqualTo(1);
        assertThat(get("/tags?target=call&object_id=" + c.get("id"))).hasSize(1);
        assertThat(get("/lookups").path("teams")).isEmpty();
        token = crypto.token(UserRecords.map(other));
        assertThat(get("/calls?tag=通话重点").path("total").asInt()).isZero();
        assertThat(
                        req(
                                        HttpMethod.POST,
                                        "/api/v1/ops/tags",
                                        Map.of(
                                                "target",
                                                "call",
                                                "object_id",
                                                c.get("id"),
                                                "tags",
                                                List.of()))
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    @Test
    void teamQueueTimeoutArmsActualConversationTimerAndMultipleGroupsUseStrictPrecedence() {
        post(
                "/teams",
                Map.of(
                        "name",
                        "计时组",
                        "members",
                        List.of(Map.of("user_id", agent.getId(), "is_lead", false)),
                        "settings",
                        Map.of(
                                "after_call_seconds",
                                20,
                                "queue_timeout_seconds",
                                33,
                                "personal_override",
                                false)));
        post(
                "/teams",
                Map.of(
                        "name",
                        "次要组",
                        "members",
                        List.of(Map.of("user_id", agent.getId(), "is_lead", false)),
                        "settings",
                        Map.of(
                                "after_call_seconds",
                                40,
                                "queue_timeout_seconds",
                                80,
                                "personal_override",
                                true)));
        agent.setAgentStatus("ready");
        agent.setLastSeenAt(Store.now());
        users.updateById(agent, tenant);
        var call =
                store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                tenant,
                                "phone",
                                "13100007777",
                                "mode",
                                "ai_handoff",
                                "status",
                                "in_ai",
                                "attempts",
                                1));
        tx.run(() -> handoffs.queue(call, "operations test"));
        var state = conversation.state(store.require("callsession", tenant, call.get("id")));
        long wait =
                java.time.Duration.between(
                                Store.now(),
                                java.time.LocalDateTime.parse(state.get("deadline").toString()))
                        .getSeconds();
        assertThat(wait).isBetween(30L, 33L);
        var effective = workforce.effective(tenant, agent.getId());
        assertThat(((Number) effective.get("after_call_seconds")).longValue()).isEqualTo(40);
        assertThat(effective.get("personal_allowed")).isEqualTo(false);
    }

    @Test
    void assignedWorkItemsAreScopedToCurrentOwnerAndTeamAndCanComplete() {
        team();
        var item =
                store.insert(
                        "productworkitem",
                        JsonSupport.map(
                                "tenant_id",
                                tenant,
                                "event_key",
                                UUID.randomUUID().toString(),
                                "kind",
                                "human_followup",
                                "phone",
                                "13100006666",
                                "detail_json",
                                "{}"));
        ok(
                req(
                        HttpMethod.PATCH,
                        "/api/v1/ops/work-items/" + item.get("id"),
                        JsonSupport.map("state", "open", "assigned_to", agent.getId())));
        token = crypto.token(UserRecords.map(other));
        assertThat(get("/work-items")).isEmpty();
        assertThat(
                        req(
                                        HttpMethod.PATCH,
                                        "/api/v1/ops/work-items/" + item.get("id"),
                                        Map.of("state", "completed"))
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        token = crypto.token(UserRecords.map(lead));
        assertThat(get("/work-items")).hasSize(1);
        ok(
                req(
                        HttpMethod.PATCH,
                        "/api/v1/ops/work-items/" + item.get("id"),
                        Map.of("state", "in_progress")));
        token = crypto.token(UserRecords.map(agent));
        ok(
                req(
                        HttpMethod.PATCH,
                        "/api/v1/ops/work-items/" + item.get("id"),
                        Map.of("state", "completed")));
        assertThat(get("/work-items").get(0).path("state").asText()).isEqualTo("completed");
    }
}
