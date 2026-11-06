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
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperationsHttpIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

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

    @Autowired
    TestRestTemplate http;
    @Autowired
    Crypto crypto;
    @Autowired
    Store store;
    @Autowired
    OperationsStore operations;
    @Autowired
    OperationsWorkforce workforce;
    @Autowired
    Handoffs handoffs;
    @Autowired
    ConversationPolicy conversation;
    @Autowired
    Transactions tx;
    @Autowired
    SysUserMapper users;
    @Autowired
    TenantMapper tenants;
    @Autowired
    ObjectMapper json;
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
        follow.put("next_at", Store.now().plusDays(1).atOffset(ZoneOffset.UTC).toString());
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
        var session = call(a, agent.getId());
        for (int attempt = 1; attempt <= 2; attempt++)
            store.insert(
                    "callusage",
                    JsonSupport.map(
                            "tenant_id",
                            tenant,
                            "call_session_id",
                            session.get("id"),
                            "attempt",
                            attempt,
                            "answered_at",
                            Store.now(),
                            "telephony_seconds",
                            12.0,
                            "ai_seconds",
                            6.0,
                            "duration_source",
                            "provider"));
        assertThat(get("/reports?ai_ranges=1-3,12-12&total_ranges=24-24").path("rows")).hasSize(1);
        assertThat(get("/reports?human_ranges=0-86400").path("rows")).isEmpty();
        assertThat(
                        req(HttpMethod.GET, "/api/v1/ops/reports?ai_ranges=24-12", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        var totalBucket = get("/reports/durations?kind=total").get(0);
        assertThat(totalBucket.path("duration_bucket").asText()).isEqualTo("medium");
        assertThat(totalBucket.path("calls").asInt()).isEqualTo(1);
        assertThat(get("/reports/durations?kind=ai").get(0).path("duration_bucket").asText())
                .isEqualTo("medium");
        assertThat(get("/reports/durations?kind=human").get(0).path("duration_bucket").asText())
                .isEqualTo("unknown");
        var usage =
                get("/reports?dimension=flow_version&granularity=minute&flow_version_id=0")
                        .path("rows")
                        .get(0);
        assertThat(usage.path("first_answered_attempts").asInt()).isEqualTo(1);
        assertThat(usage.path("repeat_answered_attempts").asInt()).isEqualTo(1);
        assertThat(usage.path("answered_attempts").asInt()).isEqualTo(2);
        assertThat(usage.path("telephony_seconds").asDouble()).isEqualTo(24.0);
        assertThat(usage.path("ai_seconds").asDouble()).isEqualTo(12.0);
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
                Duration.between(Store.now(), LocalDateTime.parse(state.get("deadline").toString()))
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

    @Autowired
    com.duozhi.outbound.operations.service.OutboundRules outboundRules;
    @Autowired
    com.duozhi.outbound.operations.controller.ExportCenter exportCenter;
    @Autowired
    com.duozhi.outbound.workspace.service.WorkspaceDocuments documents;

    @Autowired
    com.duozhi.outbound.operations.service.TaskStrategies taskStrategies;
    @Autowired
    com.duozhi.outbound.auth.service.AccountSecurity accountSecurity;
    @Autowired
    com.duozhi.outbound.job.service.Outbox outbox;

    @Test
    void rollingFrequencyRulesEnforceFortyEightHoursAndDefaultSelectionIsUnique() {
        var c = contact("滚动呼频");
        var session = call(c.get("id"), null);
        store.insert(
                "callevent",
                JsonSupport.map(
                        "call_session_id",
                        session.get("id"),
                        "event_type",
                        "dial_attempt_claimed",
                        "source",
                        "test",
                        "payload",
                        "{\"attempt\":1}",
                        "created_at",
                        Store.now().minusHours(25)));
        var rule =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "name",
                                "48小时一次",
                                "hours",
                                48,
                                "limit",
                                1,
                                "condition",
                                "all",
                                "enabled",
                                true,
                                "default_rule",
                                true));
        var saved =
                ok(
                        req(
                                HttpMethod.PUT,
                                "/api/v1/ops/benchmark/strategies/frequency/rolling",
                                Map.of("revision", 0, "config", rule)));
        assertThat(
                        taskStrategies.frequency(
                                tenant, c.get("phone").toString(), null, java.time.Instant.now()))
                .isEqualTo("frequency_rule_limit");
        assertThat(
                        taskStrategies.frequency(
                                tenant + 1,
                                c.get("phone").toString(),
                                null,
                                java.time.Instant.now()))
                .isEmpty();
        assertThat(
                        taskStrategies.frequency(
                                tenant,
                                c.get("phone").toString(),
                                null,
                                java.time.Instant.now().plusSeconds(86400)))
                .isEmpty();
        rule.put("hours", 24);
        rule.put("name", "24小时一次");
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/benchmark/strategies/frequency/daily",
                        Map.of("revision", 0, "config", rule)));
        assertThat(get("/benchmark/strategies/frequency").toString()).contains("24小时一次");
        assertThat(
                        WorkspaceDocumentsMap(
                                        documents
                                                .require(tenant, "frequency_rule", "rolling")
                                                .get("published"))
                                .get("default_rule"))
                .isEqualTo(false);
        assertThat(
                        taskStrategies.frequency(
                                tenant, c.get("phone").toString(), null, java.time.Instant.now()))
                .isEmpty();
        assertThat(
                        req(
                                        HttpMethod.DELETE,
                                        "/api/v1/ops/benchmark/strategies/frequency/rolling?revision="
                                                + saved.path("revision").asInt(),
                                        null)
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        token = crypto.token(UserRecords.map(agent));
        assertThat(
                        req(HttpMethod.GET, "/api/v1/ops/benchmark/strategies/frequency", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    private Map<String, Object> WorkspaceDocumentsMap(Object value) {
        return com.duozhi.outbound.workspace.service.WorkspaceDocuments.map(value);
    }

    @Test
    void intentGuardPausesTaskWithoutDiscardingQueuedCallsAndExpiredBatchBlocksDial() {
        var campaign =
                store.insert(
                        "campaign",
                        Map.of(
                                "tenant_id",
                                tenant,
                                "name",
                                "止损任务",
                                "status",
                                "running",
                                "created_by",
                                admin.getId()));
        var c = contact("低意向样本");
        var prior = call(c.get("id"), null);
        store.update(
                "callsession", tenant, prior.get("id"), Map.of("campaign_id", campaign.get("id")));
        store.insert(
                "callusage",
                JsonSupport.map(
                        "tenant_id",
                        tenant,
                        "call_session_id",
                        prior.get("id"),
                        "attempt",
                        1,
                        "answered_at",
                        Store.now(),
                        "telephony_seconds",
                        10.0,
                        "duration_source",
                        "provider"));
        store.insert(
                "callanalysis",
                Map.of(
                        "tenant_id",
                        tenant,
                        "call_session_id",
                        prior.get("id"),
                        "result_code",
                        "completed"));
        var queued =
                store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                tenant,
                                "phone",
                                "13100000098",
                                "mode",
                                "ai_only",
                                "campaign_id",
                                campaign.get("id"),
                                "status",
                                "queued"));
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/benchmark/strategies/achievement/stoploss",
                        Map.of(
                                "revision",
                                0,
                                "config",
                                Map.of(
                                        "name",
                                        "低于10%暂停",
                                        "minimum_connected",
                                        1,
                                        "rate",
                                        10,
                                        "campaign_id",
                                        campaign.get("id"),
                                        "enabled",
                                        true))));
        documents.tx.serialized(
                () -> {
                    assertThat(taskStrategies.guard(queued)).isFalse();
                    return null;
                });
        assertThat(store.require("campaign", tenant, campaign.get("id")).get("status"))
                .isEqualTo("paused");
        assertThat(store.require("callsession", tenant, queued.get("id")).get("status"))
                .isEqualTo("queued");
        String batch = UUID.randomUUID().toString();
        documents.jdbc.update(
                "INSERT INTO platform_contact_batch(tenant_id,id,request_key,name,file_sha,result_json,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)",
                tenant,
                batch,
                "expiry:" + batch,
                "过期批次",
                "test-sha",
                "{\"expires_at\":\"2020-01-01T00:00:00Z\"}",
                admin.getId(),
                Store.now());
        documents.jdbc.update(
                "INSERT INTO platform_contact_batch_member(tenant_id,batch_id,contact_id,variables_json) VALUES(?,?,?,'{}')",
                tenant,
                batch,
                c.get("id"));
        assertThat(outboundRules.check(tenant, c.get("phone").toString(), java.time.Instant.now()))
                .isEqualTo("dataset_expired");
    }

    @Test
    void customExportSplitsZipAndIncludesOnlyCustomerDialogue() throws Exception {
        var c = contact("自定义导出");
        var session = call(c.get("id"), null);
        for (String speaker : List.of("customer", "assistant"))
            store.insert(
                    "speechturn",
                    JsonSupport.map(
                            "tenant_id",
                            tenant,
                            "call_session_id",
                            session.get("id"),
                            "provider_event_key",
                            UUID.randomUUID().toString(),
                            "turn_index",
                            speaker.equals("customer") ? 1 : 2,
                            "attempt",
                            1,
                            "speaker_role",
                            speaker,
                            "channel_id",
                            speaker,
                            "transcript",
                            speaker.equals("customer") ? "客户导出证据" : "坐席不应被导出",
                            "normalized_transcript",
                            "test",
                            "is_final",
                            true,
                            "asr_provider",
                            "mock"));
        var job =
                post(
                        "/exports",
                        Map.of(
                                "kind",
                                "calls-custom",
                                "query",
                                Map.of(
                                        "phone",
                                        c.get("phone"),
                                        "export_fields",
                                        "calls",
                                        "export_dialogue",
                                        "customer",
                                        "export_split",
                                        "count",
                                        "export_count",
                                        1),
                                "request_key",
                                UUID.randomUUID().toString()));
        String id = job.path("id").asText();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            exportCenter.processNext();
                            assertThat(
                                            documents.one(
                                                    "SELECT state,error FROM ops_export_job WHERE id=?",
                                                    id))
                                    .containsEntry("state", "ready");
                        });
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        var file =
                http.exchange(
                        "/api/v1/ops/exports/" + id + "/download",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        byte[].class);
        assertThat(file.getStatusCode().value()).isEqualTo(200);
        var contents = new StringBuilder();
        var names = new ArrayList<String>();
        try (var zip =
                new java.util.zip.ZipInputStream(
                        new java.io.ByteArrayInputStream(file.getBody()))) {
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                names.add(entry.getName());
                contents.append(
                        new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        assertThat(names).contains("manifest.csv", "part-001.csv");
        assertThat(contents).contains("客户导出证据").doesNotContain("坐席不应被导出");
        var campaign =
                store.insert(
                        "campaign",
                        Map.of("tenant_id", tenant, "name", "导出隐藏变更", "status", "draft"));
        documents.jdbc.update(
                "INSERT INTO platform_campaign_snapshot(tenant_id,campaign_id,snapshot_json,created_at) VALUES(?,?,?,?)",
                tenant,
                campaign.get("id"),
                "{\"task_options\":{\"mask_numbers\":true}}",
                Store.now());
        assertThat(
                        req(HttpMethod.GET, "/api/v1/ops/exports/" + id + "/download", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        token = crypto.token(UserRecords.map(other));
        assertThat(
                        req(HttpMethod.GET, "/api/v1/ops/exports/" + id + "/download", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    @Test
    void personalKeysRotateAndPasswordDisableAndIpWhitelistAreActuallyEnforced() {
        String password = "SyntheticPass123!";
        documents.tx.run(
                () -> {
                    store.update(
                            "user",
                            tenant,
                            admin.getId(),
                            Map.of("password_hash", crypto.password(password)));
                    return null;
                });
        String key =
                ok(req(
                                HttpMethod.POST,
                                "/api/v1/auth/security/key",
                                Map.of("current_password", password)))
                        .path("api_key")
                        .asText();
        var current = ok(req(HttpMethod.GET, "/api/v1/auth/security", null));
        assertThat(current.toString()).doesNotContain(key).doesNotContain("key_hash");
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/auth/security",
                        Map.of(
                                "current_password",
                                password,
                                "revision",
                                current.path("revision").asInt(),
                                "config",
                                Map.of(
                                        "ips",
                                        List.of("127.0.0.1"),
                                        "export_verification",
                                        false,
                                        "password_disabled",
                                        true))));
        assertThat(
                        req(
                                        HttpMethod.POST,
                                        "/api/v1/auth/login",
                                        Map.of(
                                                "username",
                                                admin.getUsername(),
                                                "password",
                                                password))
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        token = key;
        assertThat(get("/me").path("administrator").asBoolean()).isTrue();
        String next =
                ok(req(
                                HttpMethod.POST,
                                "/api/v1/auth/security/key",
                                Map.of("current_password", password)))
                        .path("api_key")
                        .asText();
        assertThat(req(HttpMethod.GET, "/api/v1/auth/me", null).getStatusCode().value())
                .isEqualTo(401);
        token = next;
        assertThat(
                        ok(req(HttpMethod.POST, "/api/v1/auth/key-session", Map.of()))
                                .path("access_token")
                                .asText())
                .isNotEmpty();
        current = ok(req(HttpMethod.GET, "/api/v1/auth/security", null));
        assertThat(
                        req(
                                        HttpMethod.PUT,
                                        "/api/v1/auth/security",
                                        Map.of(
                                                "current_password",
                                                password,
                                                "revision",
                                                current.path("revision").asInt(),
                                                "config",
                                                Map.of(
                                                        "ips",
                                                        List.of("192.0.2.1"),
                                                        "export_verification",
                                                        false,
                                                        "password_disabled",
                                                        true)))
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        ok(req(HttpMethod.POST, "/api/v1/auth/logout", Map.of()));
        assertThat(get("/me").path("administrator").asBoolean()).isTrue();
        ok(
                req(
                        HttpMethod.POST,
                        "/api/v1/admin/users/" + admin.getId() + "/reset-password",
                        Map.of("password", "ResetKeyPass123!")));
        assertThat(req(HttpMethod.GET, "/api/v1/auth/me", null).getStatusCode().value())
                .isEqualTo(401);
        assertThat(
                        req(
                                        HttpMethod.POST,
                                        "/api/v1/auth/login",
                                        Map.of(
                                                "username",
                                                admin.getUsername(),
                                                "password",
                                                "ResetKeyPass123!"))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
    }

    @Test
    void encryptedPushConfigRedactsSecretsAndUsesSignedCiphertext() throws Exception {
        String secret = "SyntheticPushKeyAtLeastThirtyTwoCharacters";
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/benchmark/push/calls",
                        Map.of(
                                "revision",
                                0,
                                "secret_key",
                                secret,
                                "config",
                                Map.of(
                                        "enabled",
                                        true,
                                        "url",
                                        "https://example.com/events",
                                        "algorithm",
                                        "aes_gcm",
                                        "call_type",
                                        "all",
                                        "parameters",
                                        Map.of("source", "crm")))));
        assertThat(get("/benchmark/push/calls").toString())
                .doesNotContain(secret)
                .doesNotContain("secret_enc");
        var bodies = new ArrayList<byte[]>();
        var headers = new ArrayList<Map<String, String>>();
        var push =
                new com.duozhi.outbound.operations.service.OperationalPush(
                        documents,
                        accountSecurity,
                        (uri, h, body) -> {
                            headers.add(h);
                            bodies.add(body);
                            return new com.duozhi.outbound.messaging.service.WebhookTransport.Reply(
                                    200, "");
                        },
                        outbox,
                        null,
                        null);
        push.deliver(
                Map.of("tenant_id", tenant, "id", "delivery-test"),
                Map.of("push_type", "calls", "data", Map.of("phone", "13100001234")));
        String body = new String(bodies.getFirst(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("AES-256-GCM").doesNotContain("13100001234");
        String stamp = headers.getFirst().get("X-Webhook-Timestamp");
        byte[] prefix = (stamp + ".").getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                signed = Arrays.copyOf(prefix, prefix.length + bodies.getFirst().length);
        System.arraycopy(bodies.getFirst(), 0, signed, prefix.length, bodies.getFirst().length);
        assertThat(headers.getFirst().get("X-Webhook-Signature"))
                .isEqualTo("sha256=" + JsonSupport.hmac(secret, signed));
    }

    @Test
    void totpEnrollmentProtectsLoginAndExportsAndPreventsCodeReplay() {
        String password = "SyntheticPass123!";
        documents.tx.run(
                () -> {
                    store.update(
                            "user",
                            tenant,
                            admin.getId(),
                            Map.of("password_hash", crypto.password(password)));
                    return null;
                });
        var enrollment =
                ok(
                        req(
                                HttpMethod.POST,
                                "/api/v1/auth/security/totp",
                                Map.of("current_password", password)));
        String secret = enrollment.path("secret").asText();
        long at = java.time.Instant.now().getEpochSecond() / 30;
        ok(
                req(
                        HttpMethod.POST,
                        "/api/v1/auth/security/totp/confirm",
                        Map.of(
                                "code",
                                com.duozhi.outbound.auth.service.AccountSecurity.totp(
                                        secret, at - 1))));
        assertThat(
                        req(
                                        HttpMethod.POST,
                                        "/api/v1/auth/login",
                                        Map.of(
                                                "username",
                                                admin.getUsername(),
                                                "password",
                                                password))
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        String code = com.duozhi.outbound.auth.service.AccountSecurity.totp(secret, at);
        assertThat(
                        ok(req(
                                        HttpMethod.POST,
                                        "/api/v1/auth/login",
                                        Map.of(
                                                "username",
                                                admin.getUsername(),
                                                "password",
                                                password,
                                                "code",
                                                code)))
                                .path("access_token")
                                .asText())
                .isNotEmpty();
        assertThat(
                        req(
                                        HttpMethod.POST,
                                        "/api/v1/auth/login",
                                        Map.of(
                                                "username",
                                                admin.getUsername(),
                                                "password",
                                                password,
                                                "code",
                                                code))
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        var current = ok(req(HttpMethod.GET, "/api/v1/auth/security", null));
        assertThat(current.toString()).doesNotContain(secret).doesNotContain("totp_secret");
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/auth/security",
                        Map.of(
                                "current_password",
                                password,
                                "code",
                                com.duozhi.outbound.auth.service.AccountSecurity.totp(
                                        secret, at + 1),
                                "revision",
                                current.path("revision").asInt(),
                                "config",
                                Map.of(
                                        "ips",
                                        List.of(),
                                        "export_verification",
                                        true,
                                        "password_disabled",
                                        false))));
        assertThat(
                        req(
                                        HttpMethod.POST,
                                        "/api/v1/ops/exports",
                                        Map.of(
                                                "kind",
                                                "calls",
                                                "query",
                                                Map.of(),
                                                "request_key",
                                                UUID.randomUUID().toString()))
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        for (String path :
                List.of(
                        "/api/v1/contacts/export",
                        "/api/v1/admin/calls/export",
                        "/api/v1/ops/reports/export"))
            assertThat(req(HttpMethod.GET, path, null).getStatusCode().value()).isEqualTo(401);
        assertThat(
                        req(HttpMethod.POST, "/api/v1/workspace/audiences/export", Map.of())
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
    }

    @Test
    void nodeInterfaceRunsOutsideWriterLockMapsResponseAndResumesOnlyLiveAttempt()
            throws Exception {
        var session =
                store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                tenant,
                                "phone",
                                "13100002222",
                                "mode",
                                "ai_only",
                                "status",
                                "in_ai",
                                "attempts",
                                1,
                                "last_transcript",
                                "继续"));
        var def =
                Map.<String, Object>of(
                        "id",
                        "crm",
                        "name",
                        "CRM查询",
                        "url",
                        "https://example.com/lookup",
                        "method",
                        "POST",
                        "content_type",
                        "application/json",
                        "body",
                        Map.of("value", "{counter}"),
                        "response_variables",
                        Map.of("counter", "data.value"));
        var policy =
                Map.<String, Object>of(
                        "http_interfaces",
                        com.duozhi.outbound.ai.service.BusinessInterfaces.normalize(List.of(def)),
                        "custom_variables",
                        List.of(
                                Map.of(
                                        "name", "counter", "type", "NUM", "initial", 0, "export",
                                        true)));
        var data = new LinkedHashMap<String, Object>();
        data.put("flow_variables", Map.of("counter", 0));
        var state =
                store.insert(
                        "conversationstate",
                        JsonSupport.map(
                                "tenant_id",
                                tenant,
                                "call_id",
                                session.get("id"),
                                "attempt",
                                1,
                                "policy_json",
                                documents.json.json(policy),
                                "data_json",
                                documents.json.json(data)));
        var transport =
                org.mockito.Mockito.mock(
                        com.duozhi.outbound.messaging.service.PinnedWebhookTransport.class);
        org.mockito.Mockito.when(
                        transport.request(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyMap(),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            com.duozhi.outbound.common.service.JvmPlatformLock
                                                    .heldByCurrentThread())
                                    .isFalse();
                            assertThat(
                                            new String(
                                                    invocation.getArgument(2),
                                                    java.nio.charset.StandardCharsets.UTF_8))
                                    .contains("\"value\":0");
                            return new com.duozhi.outbound.messaging.service.PinnedWebhookTransport
                                    .Exchange(
                                    200,
                                    "",
                                    "{\"data\":{\"value\":7}}"
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        });
        var interfaces =
                new com.duozhi.outbound.ai.service.BusinessInterfaces(documents, outbox, transport);
        documents.tx.serialized(
                () -> {
                    assertThat(
                                    interfaces.pending(
                                            session,
                                            state,
                                            data,
                                            Map.of("id", "lookup", "interface_id", "crm")))
                            .isTrue();
                    return null;
                });
        var task =
                documents.one(
                        "SELECT * FROM taskoutbox WHERE tenant_id=? AND task_type='business_callback' AND aggregate_id=?",
                        tenant,
                        session.get("id"));
        interfaces.execute(task, documents.json.object(task.get("payload_json").toString()));
        var after =
                documents.json.object(
                        documents
                                .one(
                                        "SELECT data_json FROM conversationstate WHERE tenant_id=? AND id=?",
                                        tenant,
                                        state.get("id"))
                                .get("data_json")
                                .toString());
        assertThat(WorkspaceDocumentsMap(after.get("flow_variables"))).containsEntry("counter", 7);
        assertThat(after).doesNotContainKey("pending_interface");
        assertThat(
                        documents.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM taskoutbox WHERE tenant_id=? AND task_type='ai_turn' AND aggregate_id=?",
                                Long.class,
                                tenant,
                                session.get("id")))
                .isEqualTo(1);
        interfaces.execute(task, documents.json.object(task.get("payload_json").toString()));
        org.mockito.Mockito.verify(transport, org.mockito.Mockito.times(1))
                .request(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyMap(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void seatUniformScopeOverridesPersonalAndFormSubmissionsRotateReviewers() {
        var settings = com.duozhi.outbound.operations.controller.SeatBehavior.defaults();
        var scopes = new LinkedHashMap<>(WorkspaceDocumentsMap(settings.get("scopes")));
        scopes.put("success_invalid", "uniform");
        settings.put("scopes", scopes);
        settings.put("success_invalid", false);
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/seat-behavior?global=true",
                        Map.of("revision", 0, "config", settings)));
        token = crypto.token(UserRecords.map(agent));
        var effective = ok(req(HttpMethod.GET, "/api/v1/ops/seat-behavior", null));
        assertThat(effective.path("config").path("success_invalid").asBoolean()).isFalse();
        var personal =
                json.convertValue(
                        effective.path("config"),
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {});
        personal.put("success_invalid", true);
        assertThat(
                        ok(req(
                                        HttpMethod.PUT,
                                        "/api/v1/ops/seat-behavior",
                                        Map.of("revision", 0, "config", personal)))
                                .path("config")
                                .path("success_invalid")
                                .asBoolean())
                .isFalse();
        token = adminToken;
        var form =
                post(
                        "/configs",
                        Map.of(
                                "kind",
                                "form",
                                "name",
                                "轮询质检表",
                                "config",
                                Map.of(
                                        "fields",
                                        List.of(
                                                Map.of(
                                                        "key",
                                                        "note",
                                                        "label",
                                                        "备注",
                                                        "type",
                                                        "text",
                                                        "required",
                                                        false)))));
        int formId = get("/configs?kind=form").get(0).path("id").asInt();
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/benchmark/catalog/forms/rotate",
                        Map.of(
                                "revision",
                                0,
                                "config",
                                Map.of(
                                        "name",
                                        "质检轮询",
                                        "form_id",
                                        formId,
                                        "user_ids",
                                        List.of(admin.getId()),
                                        "reviewer_ids",
                                        List.of(agent.getId(), other.getId())))));
        for (int index = 0; index < 2; index++) {
            var c = contact("质检" + index);
            assign(c.get("id"), admin.getId());
            var body = change("form", 1);
            body.put("form_id", formId);
            body.put("fields", Map.of("note", "采集结果"));
            patch(c.get("id"), body);
        }
        var assigned =
                documents.jdbc.queryForList(
                        "SELECT assigned_to FROM productworkitem WHERE tenant_id=? AND kind='form_review' ORDER BY created_at,id",
                        Integer.class,
                        tenant);
        assertThat(assigned).containsExactlyInAnyOrder(agent.getId(), other.getId());
    }

    @Test
    void competitiveCalendarPauseWindowsRevisionAndTenantIsolation() {
        var conf = com.duozhi.outbound.operations.service.OutboundRules.defaults();
        conf.put("windows", List.of(Map.of("start", "09:00", "end", "18:00")));
        conf.put("pauses", List.of(Map.of("start", "12:00", "end", "13:00")));
        var saved =
                ok(
                        req(
                                HttpMethod.PUT,
                                "/api/v1/ops/benchmark/task-settings",
                                Map.of("revision", 0, "config", conf)));
        assertThat(saved.path("published").path("pauses")).hasSize(1);
        assertThat(outboundRules.open(tenant, java.time.Instant.parse("2026-11-05T03:59:59Z")))
                .isTrue();
        assertThat(outboundRules.open(tenant, java.time.Instant.parse("2026-11-05T04:00:00Z")))
                .isFalse();
        assertThat(outboundRules.open(tenant, java.time.Instant.parse("2026-11-05T05:00:00Z")))
                .isTrue();
        assertThat(
                        req(
                                        HttpMethod.PUT,
                                        "/api/v1/ops/benchmark/task-settings",
                                        Map.of("revision", 0, "config", conf))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        assertThat(outboundRules.open(tenant + 1, java.time.Instant.parse("2026-11-05T04:00:00Z")))
                .isTrue();
        conf.put("windows", List.of(Map.of("start", "18:00", "end", "09:00")));
        assertThat(
                        req(
                                        HttpMethod.PUT,
                                        "/api/v1/ops/benchmark/task-settings",
                                        Map.of("revision", 1, "config", conf))
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        token = crypto.token(UserRecords.map(agent));
        assertThat(
                        req(
                                        HttpMethod.PUT,
                                        "/api/v1/ops/benchmark/task-settings",
                                        Map.of("revision", 1, "config", conf))
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    @Test
    void filterGroupsActuallyBlockAndDisableWithoutDeletingAndTagGroupsFeedDictionary() {
        var body =
                Map.of(
                        "name",
                        "前缀策略",
                        "enabled",
                        true,
                        "rules",
                        List.of(Map.of("type", "prefix", "value", "139")));
        var d =
                ok(
                        req(
                                HttpMethod.PUT,
                                "/api/v1/ops/benchmark/catalog/filters/test",
                                Map.of("revision", 0, "config", body)));
        assertThat(outboundRules.check(tenant, "13900000001", java.time.Instant.now()))
                .contains("filter_group:test:prefix");
        assertThat(outboundRules.check(tenant + 1, "13900000001", java.time.Instant.now()))
                .isEmpty();
        var disabled = new LinkedHashMap<String, Object>(body);
        disabled.put("enabled", false);
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/benchmark/catalog/filters/test",
                        Map.of("revision", d.path("revision").asInt(), "config", disabled)));
        assertThat(outboundRules.check(tenant, "13900000001", java.time.Instant.now())).isEmpty();
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/benchmark/catalog/tags/test",
                        Map.of(
                                "revision",
                                0,
                                "config",
                                Map.of("name", "重点客户", "tags", List.of("对标测试标签")))));
        assertThat(get("/configs?kind=tag").toString()).contains("对标测试标签");
    }

    @Test
    void exportQueueMatchesFilterAndDownloadIsPrivateAndRechecksPermission() {
        var c = contact("异步导出");
        call(c.get("id"), agent.getId());
        var job =
                post(
                        "/exports",
                        Map.of(
                                "kind",
                                "calls",
                                "query",
                                Map.of("phone", c.get("phone")),
                                "request_key",
                                UUID.randomUUID().toString()));
        String id = job.path("id").asText();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            exportCenter.processNext();
                            assertThat(
                                            documents
                                                    .one(
                                                            "SELECT state FROM ops_export_job WHERE id=?",
                                                            id)
                                                    .get("state"))
                                    .isEqualTo("ready");
                        });
        var h = new HttpHeaders();
        h.setBearerAuth(token);
        var file =
                http.exchange(
                        "/api/v1/ops/exports/" + id + "/download",
                        HttpMethod.GET,
                        new HttpEntity<>(h),
                        byte[].class);
        assertThat(file.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(file.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .contains(c.get("phone").toString());
        token = crypto.token(UserRecords.map(other));
        assertThat(
                        req(HttpMethod.GET, "/api/v1/ops/exports/" + id + "/download", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        assertThat(get("/exports")).isEmpty();
        token = adminToken;
        assertThat(get("/reports?dimension=account&granularity=month").path("rows")).isNotEmpty();
        assertThat(get("/benchmark/dataset?phone=" + c.get("phone")).path("total").asInt())
                .isEqualTo(1);
        for (String kind : List.of("intercept", "follow", "sms", "routes", "recharge"))
            assertThat(get("/benchmark/reports/" + kind).isArray()).isTrue();
        assertThat(get("/benchmark/tasks?history=true").path("rows").isArray()).isTrue();
    }

    @Test
    void formAssignmentRestrictsAvailableVersionsAndDirectSubmission() {
        post(
                "/configs",
                Map.of(
                        "kind",
                        "form",
                        "name",
                        "账号专用表单",
                        "expected_revision",
                        0,
                        "config",
                        Map.of(
                                "fields",
                                List.of(
                                        Map.of(
                                                "key",
                                                "note",
                                                "label",
                                                "记录",
                                                "type",
                                                "text",
                                                "required",
                                                false,
                                                "choices",
                                                List.of())))));
        var form = get("/configs?kind=form").get(0);
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/benchmark/catalog/forms/test",
                        Map.of(
                                "revision",
                                0,
                                "config",
                                Map.of(
                                        "name",
                                        "专用分配",
                                        "form_id",
                                        form.path("id").asInt(),
                                        "user_ids",
                                        List.of(agent.getId())))));
        token = crypto.token(UserRecords.map(agent));
        assertThat(get("/configs?kind=form")).hasSize(1);
        token = crypto.token(UserRecords.map(other));
        assertThat(get("/configs?kind=form")).isEmpty();
        token = adminToken;
        assertThat(get("/configs?kind=form")).hasSize(1);
    }

    @Autowired
    com.duozhi.outbound.operations.service.AccountResources accountResources;

    @Test
    void accountExpiryBlocksAdmissionAndResourceChangesStayInTenant() {
        var past =
                Map.of(
                        "name",
                        "账号资源",
                        "expires_at",
                        "2020-01-01T00:00:00Z",
                        "balance_enabled",
                        false);
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/benchmark/accounts/" + agent.getId(),
                        Map.of("revision", 0, "config", past)));
        assertThat(accountResources.available(tenant, agent.getId())).isFalse();
        assertThat(get("/benchmark/accounts").toString()).contains("2020-01-01");
        assertThat(
                        req(
                                        HttpMethod.POST,
                                        "/api/v1/ops/benchmark/accounts/"
                                                + agent.getId()
                                                + "/allocate",
                                        Map.of(
                                                "request_key",
                                                UUID.randomUUID().toString(),
                                                "amount",
                                                10,
                                                "note",
                                                "测试分配"))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        token = crypto.token(UserRecords.map(other));
        assertThat(
                        req(HttpMethod.GET, "/api/v1/ops/benchmark/accounts", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    @Test
    void inspectionEvidenceAndKeywordsAdoptionStayDraftUntilPublished() {
        var c = contact("巡检客户");
        var call = call(c.get("id"), agent.getId());
        String gap = UUID.randomUUID().toString();
        documents.jdbc.update(
                "INSERT INTO platform_knowledge_gap(tenant_id,id,source_type,source_id,question_sha,question,context_json,created_at,updated_at) VALUES(?,?,'call',?,?,?, ?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                tenant,
                gap,
                call.get("id").toString(),
                "0".repeat(64),
                "怎么收费？",
                "{\"node_id\":\"opening\"}");
        var result = get("/benchmark/inspection?phone=" + c.get("phone") + "&node=opening");
        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("rows").get(0).path("regex").asText()).isEqualTo("\\Q怎么收费？\\E");
        var adopted = post("/benchmark/inspection/" + gap + "/adopt", Map.of("revision", 1));
        assertThat(adopted.path("kind").asText()).isEqualTo("keyword_scene");
        assertThat(
                        post("/benchmark/inspection/" + gap + "/adopt", Map.of("revision", 1))
                                .path("object_id")
                                .asText())
                .isEqualTo(adopted.path("object_id").asText());
        assertThat(get("/benchmark/inspection?state=adopted").path("total").asInt()).isEqualTo(1);
        var script =
                ok(req(HttpMethod.POST, "/api/v1/workspace/scripts", Map.of("name", "巡检目标话术")));
        var rule = Map.of("name", "费用问题", "target", "question", "keywords", "收费", "regex", "");
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/benchmark/catalog/keywords/test",
                        Map.of(
                                "revision",
                                0,
                                "config",
                                Map.of("name", "费用场景", "rules", List.of(rule)))));
        var draft =
                post(
                        "/benchmark/keywords/test/apply/" + script.path("id").asText(),
                        Map.of("revision", script.path("revision").asInt()));
        assertThat(draft.path("draft").path("policy").path("intent_rules")).hasSize(1);
        assertThat(draft.path("published").isNull()).isTrue();
        var user2 = new SysUser();
        user2.setTenantId(tenant + 1);
        user2.setUsername("other-" + UUID.randomUUID());
        user2.setFullName("其他租户");
        user2.setRole("admin");
        users.insert(user2);
        token = crypto.token(UserRecords.map(user2));
        assertThat(get("/benchmark/inspection").path("total").asInt()).isZero();
    }

    @Test
    void wecomProfilesArePrivateAndPasswordChangeRevokesExistingToken() {
        String path = "/api/v1/ops/wecom/profiles/" + agent.getId();
        token = crypto.token(UserRecords.map(agent));
        ok(
                req(
                        HttpMethod.PUT,
                        path,
                        Map.of(
                                "revision",
                                0,
                                "config",
                                Map.of(
                                        "corp_id",
                                        "test-corp",
                                        "member_id",
                                        "test-member",
                                        "contact_url",
                                        "https://work.weixin.qq.com/example"))));
        assertThat(ok(req(HttpMethod.GET, path, null)).path("draft").path("member_id").asText())
                .isEqualTo("test-member");
        token = crypto.token(UserRecords.map(other));
        assertThat(req(HttpMethod.GET, path, null).getStatusCode().value()).isEqualTo(403);
        token = adminToken;
        assertThat(ok(req(HttpMethod.GET, "/api/v1/ops/wecom/statistics", null)).isArray())
                .isTrue();
        var changes =
                Map.<String, Object>of("password_hash", crypto.password("SyntheticOldPass123!"));
        documents.tx.run(
                () -> {
                    store.update("user", tenant, admin.getId(), changes);
                    return null;
                });
        assertThat(
                        req(
                                        HttpMethod.POST,
                                        "/api/v1/auth/password",
                                        Map.of(
                                                "old_password",
                                                "wrong",
                                                "new_password",
                                                "SyntheticNewPass123!",
                                                "confirm_password",
                                                "SyntheticNewPass123!"))
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        ok(
                req(
                        HttpMethod.POST,
                        "/api/v1/auth/password",
                        Map.of(
                                "old_password",
                                "SyntheticOldPass123!",
                                "new_password",
                                "SyntheticNewPass123!",
                                "confirm_password",
                                "SyntheticNewPass123!")));
        assertThat(req(HttpMethod.GET, "/api/v1/ops/me", null).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    void accountAllocationIsIdempotentAndImportedChargesReduceItsSpendableBalance() {
        documents.jdbc.update(
                "INSERT INTO voice_statement_entry(tenant_id,source_mode,reference_no,kind,currency,signed_amount,settlement_source,posted_at,created_by,row_hash) VALUES(?,'simulation','synthetic-opening','opening','CNY',100,'synthetic',UTC_TIMESTAMP(3),?,?)",
                tenant,
                admin.getId(),
                "0".repeat(64));
        String path = "/benchmark/accounts/" + agent.getId() + "/allocate";
        String key = UUID.randomUUID().toString();
        var first = post(path, Map.of("request_key", key, "amount", 40, "note", "分配测试额度"));
        assertThat(first.path("balance").decimalValue()).isEqualByComparingTo("40");
        assertThat(
                        post(path, Map.of("request_key", key, "amount", 40, "note", "分配测试额度"))
                                .path("replayed")
                                .asBoolean())
                .isTrue();
        assertThat(
                        post(
                                        path,
                                        Map.of(
                                                "request_key",
                                                UUID.randomUUID().toString(),
                                                "amount",
                                                -10,
                                                "note",
                                                "回收额度"))
                                .path("balance")
                                .decimalValue())
                .isEqualByComparingTo("30");
        assertThat(
                        req(
                                        HttpMethod.POST,
                                        "/api/v1/ops" + path,
                                        Map.of(
                                                "request_key",
                                                UUID.randomUUID().toString(),
                                                "amount",
                                                80,
                                                "note",
                                                "超过可分配金额"))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/benchmark/accounts/" + agent.getId(),
                        Map.of(
                                "revision",
                                0,
                                "config",
                                Map.of("expires_at", "", "balance_enabled", true))));
        assertThat(accountResources.available(tenant, agent.getId())).isTrue();
        var c = call(contact("额度客户").get("id"), agent.getId());
        documents.jdbc.update(
                "INSERT INTO voice_statement_entry(tenant_id,source_mode,reference_no,kind,currency,signed_amount,settlement_source,call_id,attempt,posted_at,created_by,row_hash) VALUES(?,'simulation','synthetic-charge','charge','CNY',-35,'synthetic',?,1,UTC_TIMESTAMP(3),?,?)",
                tenant,
                c.get("id").toString(),
                admin.getId(),
                "1".repeat(64));
        assertThat(accountResources.available(tenant, agent.getId())).isFalse();
        assertThat(accountResources.balance(tenant, agent.getId())).isEqualByComparingTo("-5");
    }

    @Test
    void archivedTaskWithNoCallsKeepsUnknownRatesAndCanBeFilteredAndExported() {
        var task =
                store.insert(
                        "campaign",
                        Map.of(
                                "tenant_id",
                                tenant,
                                "name",
                                "无拨打归档任务",
                                "mode",
                                "ai_only",
                                "status",
                                "stopped",
                                "created_by",
                                admin.getId()));
        var result = get("/benchmark/tasks?history=true&name=无拨打归档任务&operation_type=stopped");
        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("rows").get(0).path("answer_rate").isNull()).isTrue();
        assertThat(result.path("rows").get(0).path("evaluation_result").asText()).isEqualTo("数据不足");
        assertThat(
                        get("/benchmark/tasks?history=true&name=无拨打归档任务&suggestion=insufficient")
                                .path("total")
                                .asInt())
                .isEqualTo(1);
        assertThat(
                        get("/benchmark/tasks?history=true&name=无拨打归档任务&suggestion=improve")
                                .path("total")
                                .asInt())
                .isZero();
        assertThat(
                        get("/benchmark/tasks?history=true&evaluation_rule=不存在的规则")
                                .path("total")
                                .asInt())
                .isZero();
        post(
                "/exports",
                Map.of(
                        "kind",
                        "tasks",
                        "query",
                        Map.of("history", true),
                        "request_key",
                        UUID.randomUUID().toString()));
        assertThat(get("/reports/intents?dimension=agent").isArray()).isTrue();
        for (String path :
                List.of("/dashBoard/home", "/tasks/current", "/scene/intent", "/account/center"))
            assertThat(http.getForEntity(path, String.class).getStatusCode().value())
                    .isEqualTo(200);
    }

    @Test
    void generatedCallFileCannotOutliveRevokedLeadOwnership() {
        var contact = contact("撤销归属导出");
        call(contact.get("id"), null);
        assign(contact.get("id"), agent.getId());
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/permissions/" + agent.getId(),
                        Map.of("can_export", true, "can_unmask", true, "can_record", true)));
        token = crypto.token(UserRecords.map(agent));
        var job =
                post(
                        "/exports",
                        Map.of(
                                "kind",
                                "calls",
                                "query",
                                Map.of("contact_id", contact.get("id")),
                                "request_key",
                                UUID.randomUUID().toString()));
        String id = job.path("id").asText();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            exportCenter.processNext();
                            assertThat(
                                            documents
                                                    .one(
                                                            "SELECT state FROM ops_export_job WHERE id=?",
                                                            id)
                                                    .get("state"))
                                    .isEqualTo("ready");
                        });
        token = adminToken;
        var body = change("assign", 1);
        body.put("owner_id", other.getId());
        patch(contact.get("id"), body);
        token = crypto.token(UserRecords.map(agent));
        assertThat(
                        req(HttpMethod.GET, "/api/v1/ops/exports/" + id + "/download", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    @Test
    void dailyAndMonthlyReportBucketsUseShanghaiCalendarAtUtcDayBoundary() {
        store.insert(
                "callsession",
                Map.of(
                        "tenant_id",
                        tenant,
                        "phone",
                        "13100009999",
                        "mode",
                        "ai_only",
                        "status",
                        "completed",
                        "created_at",
                        LocalDateTime.of(2026, 11, 5, 16, 30)));
        String query = "?dimension=all&from=2026-11-05T16:00:00Z&until=2026-11-06T15:59:59Z";
        var daily = get("/reports" + query + "&granularity=day");
        assertThat(daily.path("rows").get(0).path("bucket").asText()).isEqualTo("2026-11-06");
        assertThat(
                        get("/reports" + query + "&granularity=month")
                                .path("rows")
                                .get(0)
                                .path("bucket")
                                .asText())
                .isEqualTo("2026-11");
    }

    @Test
    void wecomPoolUsesRedactedSecretsAndOneStableSignedRequest() throws Exception {
        assertThat(
                        req(
                                        HttpMethod.PUT,
                                        "/api/v1/ops/wecom/accounts/not-a-number",
                                        Map.of("revision", 0, "config", Map.of("name", "非法账号")))
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/wecom/accounts/13100008888",
                        Map.of("revision", 0, "config", Map.of("name", "服务微信"))));
        String secret = "SyntheticWecomSecret123456789";
        var provider =
                ok(
                        req(
                                HttpMethod.PUT,
                                "/api/v1/ops/wecom/providers/vendor",
                                Map.of(
                                        "revision",
                                        0,
                                        "app_secret",
                                        secret,
                                        "config",
                                        Map.of(
                                                "name",
                                                "测试桥接",
                                                "app_key",
                                                "synthetic-key",
                                                "rule_id",
                                                "rule-1",
                                                "version",
                                                "1",
                                                "enabled",
                                                true,
                                                "endpoint",
                                                "https://example.com/wecom"))));
        assertThat(provider.toString()).doesNotContain(secret).doesNotContain("secret_enc");
        assertThat(get("/wecom/providers").toString())
                .doesNotContain(secret)
                .contains("secret_configured");
        var campaign =
                store.insert(
                        "campaign", Map.of("tenant_id", tenant, "name", "企微任务", "status", "draft"));
        documents.jdbc.update(
                "INSERT INTO platform_campaign_snapshot(tenant_id,campaign_id,snapshot_json,created_at) VALUES(?,?,?,?)",
                tenant,
                campaign.get("id"),
                "{\"task_options\":{\"auto_wecom\":true}}",
                Store.now());
        var c = contact("企微客户");
        var session = call(c.get("id"), null);
        store.update(
                "callsession",
                tenant,
                session.get("id"),
                Map.of("campaign_id", campaign.get("id"), "attempts", 1));
        var transport =
                org.mockito.Mockito.mock(
                        com.duozhi.outbound.messaging.service.PinnedWebhookTransport.class);
        org.mockito.Mockito.when(
                        transport.request(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyMap(),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            com.duozhi.outbound.common.service.JvmPlatformLock
                                                    .heldByCurrentThread())
                                    .isFalse();
                            Map<String, String> headers = invocation.getArgument(1);
                            byte[] body = invocation.getArgument(2);
                            assertThat(headers.get("Idempotency-Key")).startsWith("wecom:");
                            assertThat(headers.get("X-Webhook-Signature"))
                                    .isEqualTo(
                                            "sha256="
                                                    + JsonSupport.hmac(
                                                            secret,
                                                            (headers.get("X-Webhook-Timestamp")
                                                                            + "."
                                                                            + new String(
                                                                                    body,
                                                                                    java.nio.charset
                                                                                            .StandardCharsets
                                                                                            .UTF_8))
                                                                    .getBytes(
                                                                            java.nio.charset
                                                                                    .StandardCharsets
                                                                                    .UTF_8)));
                            assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                                    .contains("13100008888")
                                    .doesNotContain(secret);
                            return new com.duozhi.outbound.messaging.service.PinnedWebhookTransport
                                    .Exchange(202, "", new byte[0]);
                        });
        var automation =
                new com.duozhi.outbound.operations.service.WecomAutomation(
                        documents, accountSecurity, transport);
        var event =
                Map.<String, Object>of(
                        "call_id",
                        session.get("id"),
                        "campaign_id",
                        campaign.get("id"),
                        "attempt",
                        1);
        automation.execute(Map.of("tenant_id", tenant), event);
        automation.execute(Map.of("tenant_id", tenant), event);
        org.mockito.Mockito.verify(transport, org.mockito.Mockito.times(1))
                .request(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyMap(),
                        org.mockito.ArgumentMatchers.any());
        assertThat(get("/wecom/requests").get(0).path("draft").path("state").asText())
                .isEqualTo("accepted");
        token = crypto.token(UserRecords.map(agent));
        assertThat(req(HttpMethod.GET, "/api/v1/ops/wecom/providers", null).getStatusCode().value())
                .isEqualTo(403);
    }

    @Test
    void phoneVerificationRequiresDeliveredCodeIsBoundToPhoneAndCannotBeReplayed() {
        String password = "PhoneTestPass123!";
        store.update(
                "user",
                tenant,
                admin.getId(),
                Map.of("phone", "13100006666", "password_hash", crypto.password(password)));
        var identity =
                new com.duozhi.outbound.auth.domain.Identity(
                        tenant, store.require("user", tenant, admin.getId()), Set::of);
        var sender =
                org.mockito.Mockito.mock(com.duozhi.outbound.job.service.IntegrationTasks.class);
        var codes = new ArrayList<String>();
        org.mockito.Mockito.when(
                        sender.send(
                                org.mockito.ArgumentMatchers.anyLong(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(
                        invocation -> {
                            assertThat(
                                            com.duozhi.outbound.common.service.JvmPlatformLock
                                                    .heldByCurrentThread())
                                    .isFalse();
                            var match =
                                    java.util.regex.Pattern.compile("[0-9]{6}")
                                            .matcher(invocation.<String>getArgument(2));
                            assertThat(match.find()).isTrue();
                            codes.add(match.group());
                            return Map.of("state", "accepted");
                        });
        var verification =
                new com.duozhi.outbound.auth.service.PhoneVerification(
                        documents, accountSecurity, sender);
        assertThat(
                        verification
                                .send(identity, admin.getId(), Map.of("current_password", password))
                                .toString())
                .doesNotContain(codes.getFirst());
        assertThatThrownBy(
                        () ->
                                verification.send(
                                        identity,
                                        admin.getId(),
                                        Map.of("current_password", password)))
                .isInstanceOf(com.duozhi.outbound.common.exception.ApiError.class);
        store.update("user", tenant, admin.getId(), Map.of("phone", "13100007777"));
        assertThatThrownBy(
                        () ->
                                verification.confirm(
                                        identity, admin.getId(), Map.of("code", codes.getFirst())))
                .isInstanceOf(com.duozhi.outbound.common.exception.ApiError.class);
        store.update("user", tenant, admin.getId(), Map.of("phone", "13100006666"));
        verification.confirm(identity, admin.getId(), Map.of("code", codes.getFirst()));
        assertThat(verification.verified(tenant, admin.getId(), "13100006666")).isTrue();
        assertThatThrownBy(
                        () ->
                                verification.confirm(
                                        identity, admin.getId(), Map.of("code", codes.getFirst())))
                .isInstanceOf(com.duozhi.outbound.common.exception.ApiError.class);
        assertThat(verification.verified(tenant, admin.getId(), "13100007777")).isFalse();
    }

    @Autowired
    com.duozhi.outbound.call.controller.VoiceCallbacks voiceCallbacks;

    @Test
    void transferPrivacyHidesAgentDialogueAndDiscardsDisabledRecognition() {
        var c = contact("隐私客户");
        assign(c.get("id"), agent.getId());
        var session = call(c.get("id"), agent.getId());
        store.update(
                "callsession",
                tenant,
                session.get("id"),
                Map.of("status", "in_human", "attempts", 1));
        store.insert(
                "conversationstate",
                JsonSupport.map(
                        "tenant_id",
                        tenant,
                        "call_id",
                        session.get("id"),
                        "attempt",
                        1,
                        "policy_json",
                        documents.json.json(
                                Map.of(
                                        "flow_settings",
                                        Map.of(
                                                "handoff_hide_dialogue",
                                                true,
                                                "handoff_disable_agent_recognition",
                                                true,
                                                "handoff_disable_customer_recognition",
                                                true))),
                        "data_json",
                        "{}"));
        store.insert(
                "speechturn",
                Map.of(
                        "tenant_id",
                        tenant,
                        "call_session_id",
                        session.get("id"),
                        "attempt",
                        1,
                        "provider_event_key",
                        "before-transfer",
                        "speaker_role",
                        "customer",
                        "transcript",
                        "转接前隐私内容",
                        "is_final",
                        true));
        for (String role : List.of("agent", "customer")) {
            var result =
                    voiceCallbacks.handle(
                            "telephony_speech",
                            JsonSupport.map(
                                    "call_id",
                                    session.get("id"),
                                    "attempt",
                                    1,
                                    "event_id",
                                    "disabled-" + role,
                                    "speaker_role",
                                    role,
                                    "transcript",
                                    "不应新增识别内容",
                                    "is_final",
                                    true));
            assertThat(result.get("reason")).isEqualTo("handoff_recognition_disabled");
        }
        token = crypto.token(UserRecords.map(agent));
        assertThat(
                        ok(req(
                                        HttpMethod.GET,
                                        "/api/v1/calls/" + session.get("id") + "/speech-turns",
                                        null))
                                .toString())
                .doesNotContain("转接前隐私内容");
        token = adminToken;
        assertThat(
                        ok(req(
                                        HttpMethod.GET,
                                        "/api/v1/calls/" + session.get("id") + "/speech-turns",
                                        null))
                                .toString())
                .contains("转接前隐私内容")
                .doesNotContain("不应新增识别内容");
    }

    @Autowired
    com.duozhi.outbound.operations.service.SeatPacing seatPacing;

    @Test
    void groupMultiplierUsesSignedInSeatsAndStopsAboveCapacityAndWaitLimit() {
        var group = team();
        long id = group.path("id").asLong();
        for (var user : List.of(agent, lead))
            store.update(
                    "user",
                    tenant,
                    user.getId(),
                    Map.of("agent_status", "ready", "last_seen_at", Store.now()));
        var saved =
                ok(
                        req(
                                HttpMethod.PUT,
                                "/api/v1/ops/seats/pacing/" + id,
                                Map.of(
                                        "revision",
                                        0,
                                        "config",
                                        Map.of("multiplier", 0.5, "average_wait_seconds", 5))));
        var report = get("/seats/pacing").get(0);
        assertThat(report.path("signed_in").asInt()).isEqualTo(2);
        assertThat(report.path("maximum_concurrency").asInt()).isEqualTo(1);
        var policy = Map.<String, Object>of("handoff_team_ids", List.of(id));
        assertThat(seatPacing.permits(tenant, policy)).isTrue();
        var c = contact("倍速占用");
        var session = call(c.get("id"), agent.getId());
        store.update(
                "callsession",
                tenant,
                session.get("id"),
                Map.of("mode", "ai_handoff", "status", "in_ai"));
        assertThat(seatPacing.permits(tenant, policy)).isFalse();
        saved =
                ok(
                        req(
                                HttpMethod.PUT,
                                "/api/v1/ops/seats/pacing/" + id,
                                Map.of(
                                        "revision",
                                        saved.path("revision").asInt(),
                                        "config",
                                        Map.of("multiplier", 2, "average_wait_seconds", 5))));
        assertThat(seatPacing.permits(tenant, policy)).isTrue();
        store.update("callsession", tenant, session.get("id"), Map.of("status", "waiting_human"));
        store.insert(
                "handoffrequest",
                JsonSupport.map(
                        "tenant_id",
                        tenant,
                        "call_session_id",
                        session.get("id"),
                        "state",
                        "waiting",
                        "requested_at",
                        Store.now().minusSeconds(20)));
        assertThat(seatPacing.permits(tenant, policy)).isFalse();
        ok(
                req(
                        HttpMethod.PUT,
                        "/api/v1/ops/seats/pacing/" + id,
                        Map.of(
                                "revision",
                                saved.path("revision").asInt(),
                                "config",
                                Map.of("multiplier", 0, "average_wait_seconds", 5))));
        assertThat(seatPacing.permits(tenant, policy)).isTrue();
    }

    @Test
    void interceptionDetailSeparatesStagesAndRecordingLinksRespectCallOwnership() {
        var c = contact("拦截明细");
        var first = call(c.get("id"), agent.getId());
        var second = call(c.get("id"), other.getId());
        outboundRules.blocked(tenant, first.get("id"), c.get("phone").toString(), "contact_dnc");
        outboundRules.blockedDuring(second, "customer_withdrew_consent");
        var reports = get("/benchmark/reports/intercept");
        assertThat(reports.findValuesAsText("phase")).containsExactlyInAnyOrder("before", "during");
        assertThat(get("/benchmark/reports/intercept/details")).hasSize(2);
        var asset =
                store.insert(
                        "recordingasset",
                        JsonSupport.map(
                                "tenant_id",
                                tenant,
                                "call_session_id",
                                first.get("id"),
                                "state",
                                "available",
                                "provider_url",
                                "https://example.com/synthetic-recording.wav"));
        assertThat(
                        ok(req(
                                        HttpMethod.GET,
                                        "/api/v1/calls/"
                                                + first.get("id")
                                                + "/recordings/"
                                                + asset.get("id")
                                                + "/link",
                                        null))
                                .path("url")
                                .asText())
                .isEqualTo("https://example.com/synthetic-recording.wav");
        assertThat(
                        req(
                                        HttpMethod.GET,
                                        "/api/v1/calls/"
                                                + second.get("id")
                                                + "/recordings/"
                                                + asset.get("id")
                                                + "/link",
                                        null)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
    }
}
