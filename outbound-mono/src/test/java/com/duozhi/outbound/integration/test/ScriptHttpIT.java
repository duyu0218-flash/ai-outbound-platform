package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.auth.dao.SysUserMapper;
import com.duozhi.outbound.auth.dao.TenantMapper;
import com.duozhi.outbound.auth.domain.SysUser;
import com.duozhi.outbound.auth.domain.Tenant;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.auth.support.UserRecords;
import com.duozhi.outbound.call.service.CallPolicy;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.job.service.Outbox;
import com.duozhi.outbound.script.controller.ScriptFlows;
import com.duozhi.outbound.system.dao.AuditLogMapper;
import com.duozhi.outbound.system.service.DataRetention;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.*;
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
class ScriptHttpIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "customer-http-secret-at-least-thirty-two-characters");
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
        p.add("TENANT_API_KEYS_JSON", () -> "{\"1\":\"customer-test-key\"}");
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper json;
    @Autowired
    Crypto crypto;
    @Autowired
    Store store;
    @Autowired
    SysUserMapper users;
    @Autowired
    TenantMapper tenants;
    @Autowired
    AuditLogMapper audits;
    @Autowired
    ConfigurableEnvironment environment;
    @Autowired
    CallPolicy policy;
    @Autowired
    Transactions tx;
    @Autowired
    DataRetention retention;
    @Autowired
    Outbox outbox;
    private final Map<String, Object> overrides = new HashMap<>();
    private String token;
    private SysUser user;
    @Autowired
    ScriptFlows flows;

    @BeforeEach
    void seed() {
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("script-scenario", overrides));
        overrides.put("ENV", "dev");
        overrides.put("TENANT_API_SCOPES_JSON", "{}");
        for (int id : List.of(1, 2))
            if (tenants.findById(id) == null) {
                Tenant t = new Tenant();
                t.setId(id);
                t.setCode("customer-tenant-" + id);
                t.setName("Customer tenant " + id);
                tenants.insert(t);
            }
        user = new SysUser();
        user.setTenantId(1);
        user.setUsername("customer" + UUID.randomUUID().toString().replace("-", ""));
        user.setFullName("Customer test admin");
        user.setRole("admin");
        users.insert(user);
        token = crypto.token(UserRecords.map(user));
    }

    @AfterEach
    void reset() {
        environment.getPropertySources().remove("script-scenario");
    }

    HttpHeaders auth() {
        var h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    ResponseEntity<JsonNode> request(HttpMethod method, String path, Object body) {
        var h = auth();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    JsonNode ok(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        return r.getBody();
    }

    String phone() {
        return "13"
                + String.format(
                        Locale.ROOT,
                        "%011d",
                        Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000000000L));
    }

    JsonNode create(String number) {
        return ok(
                request(
                        HttpMethod.POST,
                        "/api/v1/contacts",
                        Map.of("phone", number, "name", "客户Case", "consent_state", "consented")));
    }

    Map<String, Object> contact(String number) {
        return store.insert(
                "contact",
                Values.map("tenant_id", 1, "phone", number, "consent_state", "consented"));
    }

    String at(String number) {
        return policy.checkAt(1, number, true, null, Instant.parse("2026-10-19T04:00:00Z"));
    }

    JsonNode template() {
        return ok(
                request(
                        HttpMethod.POST,
                        "/api/v1/script-templates",
                        Map.of(
                                "name",
                                "话术Case",
                                "content",
                                "您好，欢迎了解服务",
                                "category",
                                "caseCategory")));
    }

    Map<String, Object> node(String id, String type) {
        return JsonSupport.map(
                "id", id, "type", type, "label", id, "position", Map.of("x", 100, "y", 100));
    }

    Map<String, Object> edge(String id, String from, String to) {
        return JsonSupport.map("id", id, "source", from, "target", to, "condition", "always");
    }

    Map<String, Object> graph() {
        return JsonSupport.map(
                "nodes",
                new ArrayList<>(
                        List.of(
                                node("start", "start"),
                                node("message", "message"),
                                node("end", "hangup"))),
                "edges",
                new ArrayList<>(
                        List.of(edge("a", "start", "message"), edge("b", "message", "end"))));
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> parts(Map<String, Object> graph, String kind) {
        return (List<Map<String, Object>>) graph.get(kind);
    }

    @Test
    void templateCrudPreservesNullFilteringVersionsAndSoftDelete() {
        var created = template();
        long id = created.get("id").asLong();
        assertThat(created.get("version").asInt()).isEqualTo(1);
        assertThat(ok(request(HttpMethod.GET, "/api/v1/script-templates/" + id, null)))
                .isEqualTo(created);
        var changes = JsonSupport.map("name", null, "content", "更新后的话术");
        var updated = ok(request(HttpMethod.PUT, "/api/v1/script-templates/" + id, changes));
        assertThat(updated.get("name")).isEqualTo(created.get("name"));
        assertThat(updated.get("version").asInt()).isEqualTo(2);
        assertThat(updated.get("content").asText()).isEqualTo("更新后的话术");
        assertThat(
                        ok(request(
                                        HttpMethod.GET,
                                        "/api/v1/script-templates?category=caseCategory",
                                        null))
                                .findValues("id"))
                .contains(updated.get("id"));
        assertThat(
                        ok(
                                request(
                                        HttpMethod.GET,
                                        "/api/v1/script-templates?category=casecategory",
                                        null)))
                .isEmpty();
        ok(request(HttpMethod.DELETE, "/api/v1/script-templates/" + id, null));
        assertThat(
                        ok(request(HttpMethod.GET, "/api/v1/script-templates/" + id, null))
                                .get("is_active")
                                .asBoolean())
                .isFalse();
        assertThat(
                        ok(request(
                                        HttpMethod.GET,
                                        "/api/v1/script-templates?active_only=true",
                                        null))
                                .findValues("id"))
                .doesNotContain(updated.get("id"));
    }

    @Test
    void flowCreateEditPublishCloneAndSimulateUseStoredGraph() {
        var t = template();
        String path = "/api/v1/script-templates/" + t.get("id").asLong() + "/flows";
        var created = ok(request(HttpMethod.POST, path, Map.of()));
        long id = created.get("id").asLong();
        assertThat(created.get("version").asInt()).isEqualTo(1);
        assertThat(created.get("status").asText()).isEqualTo("draft");
        assertThat(created.get("graph").get("nodes")).hasSize(4);
        var g = graph();
        parts(g, "nodes").get(1).put("prompt", "你好 {name}");
        var updated =
                ok(request(HttpMethod.PUT, path + "/" + id, Map.of("name", "正式流程", "graph", g)));
        assertThat(updated.get("name").asText()).isEqualTo("正式流程");
        var simulation =
                ok(
                        request(
                                HttpMethod.POST,
                                path + "/" + id + "/simulate",
                                Map.of("variables", Map.of("name", "李女士"))));
        assertThat(simulation.get("prompt").asText()).isEqualTo("你好 李女士");
        assertThat(simulation.get("action").asText()).isEqualTo("speak");
        var published = ok(request(HttpMethod.POST, path + "/" + id + "/publish", null));
        assertThat(published.get("status").asText()).isEqualTo("published");
        assertThat(published.get("published_at").isTextual()).isTrue();
        assertThat(ok(request(HttpMethod.POST, path + "/" + id + "/publish", null)))
                .isEqualTo(published);
        assertThat(
                        request(HttpMethod.PUT, path + "/" + id, Map.of("graph", g))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        var cloned = ok(request(HttpMethod.POST, path, Map.of("clone_version_id", id)));
        assertThat(cloned.get("version").asInt()).isEqualTo(2);
        assertThat(cloned.get("graph")).isEqualTo(published.get("graph"));
        assertThat(cloned.get("status").asText()).isEqualTo("draft");
        var versions = ok(request(HttpMethod.GET, path, null));
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(ok(request(HttpMethod.GET, path + "/" + id, null))).isEqualTo(published);
    }

    @Test
    void graphValidatorRejectsInvalidEdgesNodesAndAutomaticCycles() {
        var duplicate = graph();
        parts(duplicate, "nodes").add(node("start", "start"));
        rejected(duplicate, "duplicate node id");
        var noStart = graph();
        parts(noStart, "nodes").get(0).put("type", "message");
        rejected(noStart, "flow must contain exactly one start node");
        var duplicateEdge = graph();
        parts(duplicateEdge, "edges").add(edge("a", "message", "end"));
        rejected(duplicateEdge, "duplicate edge id");
        var missing = graph();
        parts(missing, "edges").get(0).put("target", "missing");
        rejected(missing, "invalid edge endpoint");
        var terminal = graph();
        parts(terminal, "edges").add(edge("bad", "end", "message"));
        rejected(terminal, "terminal node cannot have outgoing edges");
        var fallback = graph();
        parts(fallback, "edges").add(edge("bad", "start", "end"));
        rejected(fallback, "duplicate default edge");
        var notConnected = graph();
        parts(notConnected, "nodes").add(node("unreachable", "hangup"));
        rejected(notConnected, "unreachable nodes");
        var noExit = graph();
        parts(noExit, "edges").remove(1);
        rejected(noExit, "non-terminal node requires an outgoing edge");
        var variable = graph();
        parts(variable, "nodes").get(1).put("type", "collect");
        rejected(variable, "node requires a variable");
        var keyword = graph();
        parts(keyword, "edges").get(0).put("condition", "keyword");
        rejected(keyword, "edge requires keywords");
        var cycle = graph();
        parts(cycle, "nodes").get(1).putAll(Map.of("type", "set", "variable", "x"));
        parts(cycle, "nodes").add(node("branch", "branch"));
        parts(cycle, "edges").get(1).put("target", "branch");
        parts(cycle, "edges").add(edge("c", "branch", "message"));
        rejected(cycle, "automatic nodes contain a cycle");
    }

    void rejected(Map<String, Object> g, String message) {
        assertThatThrownBy(() -> flows.graph(g))
                .isInstanceOfSatisfying(
                        ApiError.class,
                        e -> {
                            assertThat(e.status).isEqualTo(400);
                            assertThat(e.getMessage()).isEqualTo(message);
                        });
    }

    @Test
    void collectNodeQuestionsDoNotCaptureAndRealAnswersRenderVariables() {
        var g = graph();
        parts(g, "nodes")
                .get(1)
                .putAll(Map.of("type", "collect", "variable", "name", "prompt", "请问称呼"));
        parts(g, "nodes").get(2).put("prompt", "感谢 {name}");
        var question = flows.simulate(g, "message", "你是谁？", false, Map.of());
        assertThat(question.get("next_node_id")).isNull();
        assertThat(question.get("action")).isEqualTo("speak");
        assertThat(question.get("variables")).isEqualTo(Map.of());
        var answer = flows.simulate(g, "message", "小李", false, Map.of());
        assertThat(answer.get("action")).isEqualTo("hangup");
        assertThat(answer.get("prompt")).isEqualTo("感谢 小李");
        assertThat(answer.get("variables")).isEqualTo(Map.of("name", "小李"));
    }

    @Test
    void flowKeywordMatchingHonorsNegationAndSilenceBeforeDefault() {
        var g = graph();
        parts(g, "nodes").add(node("human", "handoff"));
        var special = edge("human-edge", "message", "human");
        special.put("condition", "keyword");
        special.put("keywords", List.of("人工"));
        parts(g, "edges").add(special);
        assertThat(flows.simulate(g, "message", "请转人工", false, Map.of()).get("action"))
                .isEqualTo("handoff");
        assertThat(flows.simulate(g, "message", "不要人工", false, Map.of()).get("action"))
                .isEqualTo("hangup");
        parts(g, "edges").get(1).put("condition", "silence");
        assertThat(flows.simulate(g, "message", "请转人工", true, Map.of()).get("action"))
                .isEqualTo("hangup");
    }

    @Test
    void flowAndKnowledgeCannotReadOrMutateAnotherTenant() {
        var foreign =
                store.insert(
                        "scripttemplate",
                        Values.map("tenant_id", 2, "name", "foreign", "content", "private"));
        assertThat(
                        request(
                                        HttpMethod.GET,
                                        "/api/v1/script-templates/" + foreign.get("id"),
                                        null)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        assertThat(
                        request(
                                        HttpMethod.POST,
                                        "/api/v1/script-templates/" + foreign.get("id") + "/flows",
                                        Map.of())
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        var knowledge =
                store.insert(
                        "knowledgeitem",
                        Values.map("tenant_id", 2, "title", "foreign", "content", "private"));
        assertThat(
                        request(
                                        HttpMethod.PUT,
                                        "/api/v1/knowledge/" + knowledge.get("id"),
                                        Map.of("title", "wrong"))
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
    }

    @Test
    void knowledgeCrudKeepsUserReadAccessAndVersions() {
        var created =
                ok(
                        request(
                                HttpMethod.POST,
                                "/api/v1/knowledge",
                                Map.of(
                                        "title",
                                        "FAQCase",
                                        "content",
                                        "FAQ 内容",
                                        "keywords",
                                        "词一,词二")));
        long id = created.get("id").asLong();
        assertThat(created.get("version").asInt()).isEqualTo(1);
        assertThat(created.get("created_by").asInt()).isEqualTo(user.getId());
        var updated =
                ok(request(HttpMethod.PUT, "/api/v1/knowledge/" + id, Map.of("content", "更新内容")));
        assertThat(updated.get("version").asInt()).isEqualTo(2);
        assertThat(updated.get("title")).isEqualTo(created.get("title"));
        assertThat(updated.get("content").asText()).isEqualTo("更新内容");
        user.setRole("agent");
        users.updateById(user, 1);
        token = crypto.token(UserRecords.map(user));
        assertThat(ok(request(HttpMethod.GET, "/api/v1/knowledge", null)).findValues("id"))
                .contains(created.get("id"));
        assertThat(
                        request(
                                        HttpMethod.PUT,
                                        "/api/v1/knowledge/" + id,
                                        Map.of("content", "deny"))
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        user.setRole("admin");
        users.updateById(user, 1);
        token = crypto.token(UserRecords.map(user));
        var deleted = ok(request(HttpMethod.DELETE, "/api/v1/knowledge/" + id, null));
        assertThat(deleted.get("is_active").asBoolean()).isFalse();
        assertThat(deleted.get("version").asInt()).isEqualTo(3);
    }

    @Test
    void templateAndKnowledgeMachineAdminRequiresExactPermission() {
        var h = new HttpHeaders();
        h.set("x-api-key", "customer-test-key");
        h.setContentType(MediaType.APPLICATION_JSON);
        assertThat(
                        http.exchange(
                                        "/api/v1/script-templates",
                                        HttpMethod.GET,
                                        new HttpEntity<>(h),
                                        JsonNode.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        assertThat(
                        http.exchange(
                                        "/api/v1/knowledge",
                                        HttpMethod.GET,
                                        new HttpEntity<>(h),
                                        JsonNode.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        var body = Map.of("title", "Scoped", "content", "Scoped");
        assertThat(
                        http.exchange(
                                        "/api/v1/knowledge",
                                        HttpMethod.POST,
                                        new HttpEntity<>(body, h),
                                        JsonNode.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        overrides.put(
                "TENANT_API_SCOPES_JSON",
                "{\"1\":[\"knowledge:write\",\"script-templates:write\"]}");
        assertThat(
                        http.exchange(
                                        "/api/v1/script-templates",
                                        HttpMethod.GET,
                                        new HttpEntity<>(h),
                                        JsonNode.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(
                        http.exchange(
                                        "/api/v1/knowledge",
                                        HttpMethod.POST,
                                        new HttpEntity<>(body, h),
                                        JsonNode.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
    }
}
