package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.auth.dao.*;
import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.auth.support.UserRecords;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.*;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.workspace.service.*;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskCreationScaleIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "task-creation-scale-local-only-secret-32-characters");
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
        p.add("JAVA_WORKERS_ENABLED", () -> "false");
        p.add("MESSAGING_WORKERS_ENABLED", () -> "false");
        p.add("MARKETING_WORKERS_ENABLED", () -> "false");
        p.add("DAS_ENABLED", () -> "false");
        p.add("TELEPHONY_PROVIDER", () -> "mock");
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    Crypto crypto;
    @Autowired
    SysUserMapper users;
    @Autowired
    TenantMapper tenants;
    @Autowired
    WorkspaceDocuments docs;
    @Autowired
    PlatformLock locks;
    @MockitoSpyBean
    WorkspaceAudience audience;
    static final java.util.concurrent.atomic.AtomicLong CONTACT_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong(13000000000L);
    String token;

    @BeforeEach
    void seed() {
        for (int id : List.of(1, 2))
            if (tenants.findById(id) == null) {
                var t = new Tenant();
                t.setId(id);
                t.setCode("scale-" + id);
                t.setName("Scale " + id);
                tenants.insert(t);
            }
        var user = new SysUser();
        user.setTenantId(1);
        user.setUsername("scale-" + UUID.randomUUID());
        user.setFullName("本地名单测试");
        user.setRole("admin");
        users.insert(user);
        token = crypto.token(UserRecords.map(user));
    }

    ResponseEntity<JsonNode> raw(HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
                "/api/v1/workspace" + path,
                method,
                new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    JsonNode api(HttpMethod method, String path, Object body) {
        var result = raw(method, path, body);
        assertThat(result.getStatusCode().value())
                .as(String.valueOf(result.getBody()))
                .isEqualTo(200);
        return result.getBody();
    }

    long script() {
        var created =
                api(
                        HttpMethod.POST,
                        "/scripts",
                        Map.of(
                                "name",
                                "名单验证" + UUID.randomUUID(),
                                "category",
                                "回访",
                                "goal",
                                "确认意向"));
        var draft = docs.json.object(created.path("draft").toString());
        draft.put("opening", "您好，服务回访。");
        var saved =
                api(
                        HttpMethod.PUT,
                        "/scripts/" + created.path("id").asText() + "/draft",
                        Map.of(
                                "expected_revision",
                                created.path("revision").asInt(),
                                "draft",
                                draft));
        return api(
                        HttpMethod.POST,
                        "/scripts/" + created.path("id").asText() + "/publish",
                        Map.of("expected_revision", saved.path("revision").asInt()))
                .path("id")
                .asLong();
    }

    List<Long> contacts(int count) {
        String name = "scale-" + UUID.randomUUID();
        long base = CONTACT_SEQUENCE.getAndAdd(count);
        for (int start = 0; start < count; start += 500) {
            int size = Math.min(500, count - start);
            var args = new ArrayList<Object>();
            for (int i = 0; i < size; i++) {
                args.add(Long.toString(base + start + i));
                args.add(name);
            }
            docs.jdbc.update(
                    "INSERT INTO contact(tenant_id,phone,name,tags,consent_state,dnc,created_at,updated_at) VALUES "
                            + String.join(
                                    ",",
                                    Collections.nCopies(
                                            size, "(1,?,?,'','CONSENTED',false,NOW(3),NOW(3))")),
                    args.toArray());
        }
        var ids =
                docs.jdbc.queryForList(
                        "SELECT id FROM contact WHERE tenant_id=1 AND name=? ORDER BY id",
                        Long.class,
                        name);
        docs.jdbc.update(
                "INSERT INTO platform_contact_value(tenant_id,contact_id,values_json,updated_at) SELECT tenant_id,id,'{\"offer\":\"原始批次变量\"}',NOW(3) FROM contact WHERE tenant_id=1 AND name=?",
                name);
        return ids;
    }

    Map<String, Object> payload(long script, List<Long> ids, int split) {
        return JsonSupport.map(
                "name",
                "批量创建" + UUID.randomUUID(),
                "mode",
                "ai_only",
                "script_template_id",
                script,
                "contact_ids",
                ids,
                "split_size",
                split,
                "request_key",
                UUID.randomUUID().toString());
    }

    @Test
    void creationProgressesWhileDialPlatformMutexIsHeldAndReplaysOnce() throws Exception {
        var body = payload(script(), contacts(2500), 500);
        try (var executor = Executors.newFixedThreadPool(2)) {
            JsonNode first;
            try (var held = locks.lock(Transactions.PLATFORM_KEY)) {
                first =
                        executor.submit(() -> api(HttpMethod.POST, "/tasks", body))
                                .get(5, TimeUnit.SECONDS);
            }
            var one = executor.submit(() -> api(HttpMethod.POST, "/tasks", body));
            var two = executor.submit(() -> api(HttpMethod.POST, "/tasks", body));
            assertThat(one.get(5, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(two.get(5, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(first.path("tasks")).hasSize(5);
            verify(audience, times(1)).resolve(eq(1L), anyMap());
            for (var task : first.path("tasks")) {
                assertThat(task.path("contact_count").asInt()).isEqualTo(500);
                assertThat(task.path("snapshot").path("excluded_contact_ids")).isEmpty();
                assertThat(task.path("snapshot").path("direct_contact_ids")).hasSize(500);
            }
        }
    }

    @Test
    void partialFailureRollsBackEveryChildAndRetryHasNoDuplicate() {
        var body = payload(script(), contacts(4), 2);
        var calls = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (calls.incrementAndGet() == 2) throw new ApiError(409, "模拟第二个子任务失败");
                            return invocation.callRealMethod();
                        })
                .when(audience)
                .freeze(eq(1L), anyString(), any());
        assertThat(raw(HttpMethod.POST, "/tasks", body).getStatusCode().value()).isEqualTo(409);
        assertThat(
                        docs.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM campaign WHERE tenant_id=1 AND name LIKE ?",
                                Long.class,
                                body.get("name") + "%"))
                .isZero();
        assertThat(docs.find(1, "task_create_request", body.get("request_key").toString()))
                .isNull();
        doCallRealMethod().when(audience).freeze(anyLong(), anyString(), any());
        assertThat(api(HttpMethod.POST, "/tasks", body).path("tasks")).hasSize(2);
    }

    @Test
    void editingSplitKeepsFrozenVariablesAndRejectsForeignSource() {
        long script = script();
        var ids = contacts(4);
        var first = api(HttpMethod.POST, "/tasks", payload(script, ids, 2)).path("tasks").get(0);
        docs.jdbc.update(
                "UPDATE platform_contact_value SET values_json='{\"offer\":\"已变化\"}' WHERE tenant_id=1");
        var part = new ArrayList<Long>();
        first.path("snapshot").path("direct_contact_ids").forEach(v -> part.add(v.asLong()));
        var edit = payload(script, part, 0);
        edit.put("frozen_audience_id", first.path("id").asLong());
        var updated = api(HttpMethod.PUT, "/tasks/" + first.path("id").asLong(), edit);
        assertThat(updated.path("contact_count").asInt()).isEqualTo(2);
        assertThat(
                        docs.jdbc.queryForList(
                                "SELECT variables_json FROM platform_audience_member WHERE tenant_id=1 AND snapshot_id=?",
                                String.class,
                                "call:" + first.path("id").asLong()))
                .allMatch(value -> value.contains("原始批次变量"));
        edit.put("frozen_audience_id", Long.MAX_VALUE);
        assertThat(
                        raw(HttpMethod.PUT, "/tasks/" + first.path("id").asLong(), edit)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
    }

    @Test
    void editingSplitAcceptsMatchingBatchVariablesButRejectsRealConflict() {
        long script = script();
        var ids = contacts(4);
        var first = api(HttpMethod.POST, "/tasks", payload(script, ids, 2)).path("tasks").get(0);
        long task = first.path("id").asLong();
        var part = new ArrayList<Long>();
        first.path("snapshot").path("direct_contact_ids").forEach(v -> part.add(v.asLong()));
        String batch = UUID.randomUUID().toString();
        docs.jdbc.update(
                "INSERT INTO platform_contact_batch(tenant_id,id,request_key,name,file_sha,result_json,created_at) VALUES(1,?,?,?,'synthetic','{}',NOW(3))",
                batch,
                batch,
                "拆单同源名单");
        for (long contact : part)
            docs.jdbc.update(
                    "INSERT INTO platform_contact_batch_member(tenant_id,batch_id,contact_id,variables_json) VALUES(1,?,?,?)",
                    batch,
                    contact,
                    "{\"offer\":\"原始批次变量\"}");
        var edit = payload(script, part, 0);
        edit.put("frozen_audience_id", task);
        edit.put("batch_ids", List.of(batch));
        assertThat(api(HttpMethod.PUT, "/tasks/" + task, edit).path("contact_count").asInt())
                .isEqualTo(2);
        var frozen =
                docs.jdbc.queryForList(
                        "SELECT variables_json FROM platform_audience_member WHERE tenant_id=1 AND snapshot_id=? ORDER BY contact_id",
                        String.class,
                        "call:" + task);
        assertThat(frozen).hasSize(2);
        for (var value : frozen)
            assertThat(docs.json.object(value))
                    .containsEntry("offer", "原始批次变量")
                    .containsKeys("name", "phone");
        docs.jdbc.update(
                "UPDATE platform_contact_batch_member SET variables_json='{\"offer\":\"冲突批次变量\"}' WHERE tenant_id=1 AND batch_id=?",
                batch);
        assertThat(raw(HttpMethod.PUT, "/tasks/" + task, edit).getStatusCode().value())
                .isEqualTo(422);
        assertThat(
                        docs.jdbc.queryForList(
                                "SELECT variables_json FROM platform_audience_member WHERE tenant_id=1 AND snapshot_id=? ORDER BY contact_id",
                                String.class,
                                "call:" + task))
                .containsExactlyElementsOf(frozen);
    }

    @Test
    void scaledCreationKeepsExactMembershipAndOrder() throws Exception {
        long script = script();
        var measurements = new ArrayList<Map<String, Object>>();
        for (String size : System.getProperty("task.scale.sizes", "10000").split(",")) {
            int count = Integer.parseInt(size);
            var ids = contacts(count);
            var body = payload(script, ids, Math.max(1, count / 10));
            long start = System.nanoTime();
            var result = api(HttpMethod.POST, "/tasks", body);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            var actual = new ArrayList<Long>();
            for (var task : result.path("tasks"))
                actual.addAll(
                        docs.jdbc.queryForList(
                                "SELECT contact_id FROM campaigncontact WHERE campaign_id=? ORDER BY contact_order",
                                Long.class,
                                task.path("id").asLong()));
            assertThat(actual).containsExactlyElementsOf(ids);
            measurements.add(
                    Map.of(
                            "contacts",
                            count,
                            "children",
                            result.path("tasks").size(),
                            "elapsed_ms",
                            elapsed,
                            "membership_exact",
                            true));
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/task-create-scale.json"), docs.json.json(measurements));
    }
}
