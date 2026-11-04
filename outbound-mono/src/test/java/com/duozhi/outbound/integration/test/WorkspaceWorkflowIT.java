package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.auth.dao.*;
import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.auth.support.UserRecords;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.workspace.service.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkspaceWorkflowIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "workspace-workflow-test-secret-thirty-two-characters");
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
        p.add("JAVA_DISPATCH_ENABLED", () -> "true");
        p.add("MESSAGING_WORKERS_ENABLED", () -> "false");
        p.add("MESSAGING_ENABLED", () -> "true");
        p.add("MESSAGING_MASTER_KEY", () -> Base64.getEncoder().encodeToString(new byte[32]));
        p.add("MARKETING_WORKERS_ENABLED", () -> "false");
        p.add("DAS_ENABLED", () -> "false");
        p.add("TELEPHONY_PROVIDER", () -> "mock");
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    ObjectMapper json;
    @Autowired
    Crypto crypto;
    @Autowired
    SysUserMapper users;
    @Autowired
    TenantMapper tenants;
    @Autowired
    WorkspaceDocuments docs;
    @Autowired
    KnowledgeGaps gaps;
    @Autowired
    Store store;
    @Autowired
    com.duozhi.outbound.customer.controller.ContactFiles contactFiles;
    @Autowired
    ContactVariables contactVariables;
    @Autowired
    WorkspaceMessages workspaceMessages;
    @Autowired
    com.duozhi.outbound.messaging.service.Messaging messaging;
    @Autowired
    com.duozhi.outbound.ai.service.ConversationPolicy conversations;
    @Autowired
    com.duozhi.outbound.call.service.CallLifecycle lifecycle;
    @Autowired
    com.duozhi.outbound.job.worker.TaskWorker worker;
    @Autowired
    com.duozhi.outbound.dial.service.DialService dial;
    String token;
    SysUser user;

    @BeforeEach
    void seed() {
        for (int id : List.of(1, 2))
            if (tenants.findById(id) == null) {
                var t = new Tenant();
                t.setId(id);
                t.setCode("workspace-" + id);
                t.setName("Workspace " + id);
                tenants.insert(t);
            }
        user = account(1, "admin");
        token = crypto.token(UserRecords.map(user));
    }

    @AfterEach
    void clean() {
        docs.jdbc.update(
                "UPDATE platform_knowledge_upload SET state='cancelled',file_bytes=NULL WHERE created_by=? AND state IN ('queued','parsing','ready','failed')",
                user.getId());
    }

    SysUser account(int tenant, String role) {
        var u = new SysUser();
        u.setTenantId(tenant);
        u.setUsername("workspace-" + UUID.randomUUID());
        u.setFullName("Workspace test " + role);
        u.setRole(role);
        users.insert(u);
        return u;
    }

    HttpHeaders auth(String value) {
        var h = new HttpHeaders();
        h.setBearerAuth(value);
        return h;
    }

    ResponseEntity<JsonNode> raw(HttpMethod method, String path, Object body) {
        return raw(token, method, path, body);
    }

    ResponseEntity<JsonNode> raw(String who, HttpMethod method, String path, Object body) {
        var h = auth(who);
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
                "/api/v1/workspace" + path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    JsonNode ok(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        return r.getBody();
    }

    JsonNode api(HttpMethod method, String path, Object body) {
        return ok(raw(method, path, body));
    }

    JsonNode library() {
        return api(HttpMethod.POST, "/libraries", Map.of("name", "业务库 " + UUID.randomUUID()));
    }

    JsonNode lib(String id) {
        return api(HttpMethod.GET, "/libraries/" + id, null);
    }

    String id(JsonNode v) {
        return v.path("object_id").asText();
    }

    Map<String, Object> object(JsonNode v) {
        return json.convertValue(
                v, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    JsonNode file(String library, String key, String filename, byte[] bytes) {
        return ok(fileRaw(library, key, filename, bytes));
    }

    ResponseEntity<JsonNode> fileRaw(String library, String key, String filename, byte[] bytes) {
        var body = new LinkedMultiValueMap<String, Object>();
        body.add(
                "file",
                new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                });
        var h = auth(token);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange(
                "/api/v1/workspace/libraries/"
                        + library
                        + "/documents?request_key="
                        + key
                        + "&split_size=400&overlap=40",
                HttpMethod.POST,
                new HttpEntity<>(body, h),
                JsonNode.class);
    }

    @Test
    void faqCsvRoundTripPreservesBusinessFieldsAndRejectsStaleAppend() {
        var created = library();
        var libraryId = id(created);
        String path = "/api/v1/workspace/libraries/" + libraryId + "/faq-file";
        var body = new LinkedMultiValueMap<String, Object>();
        String csv = "\ufeff序号,标题,关键词,回答内容,类型,相似问法,分类\r\n01,办理条件,办理,请预约,bussiness,,服务\r\n";
        body.add(
                "file",
                new ByteArrayResource(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    public String getFilename() {
                        return "faq.csv";
                    }
                });
        body.add("expected_revision", created.path("revision").asText());
        var headers = auth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var upload = new HttpEntity<>(body, headers);
        assertThat(
                        ok(http.exchange(path, HttpMethod.POST, upload, JsonNode.class))
                                .path("imported")
                                .asInt())
                .isEqualTo(1);
        assertThat(
                        http.exchange(path, HttpMethod.POST, upload, JsonNode.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        var item = lib(libraryId).path("items").get(0).path("draft");
        assertThat(item.path("answer_type").asText()).isEqualTo("business");
        assertThat(item.path("display_code").asText()).isEqualTo("01");
        var download =
                http.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(token)), byte[].class);
        assertThat(new String(download.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("01,办理条件,办理,请预约,business,,服务");
    }

    JsonNode waitDocument(String library, String key, String state) throws Exception {
        long end = System.nanoTime() + 15_000_000_000L;
        JsonNode value = null;
        while (System.nanoTime() < end) {
            value = api(HttpMethod.GET, "/libraries/" + library + "/documents/" + key, null);
            if (state.equals(value.path("state").asText())) return value;
            Thread.sleep(80);
        }
        throw new AssertionError("Document state " + value);
    }

    @Test
    void directoryRenameDeleteAndRestoreDoNotRewriteThePublishedLibrary() {
        String library = id(library());
        var current = lib(library);
        api(
                HttpMethod.POST,
                "/libraries/" + library + "/categories",
                Map.of("path", "产品/办理条件", "expected_revision", current.path("revision").asInt()));
        api(
                HttpMethod.PUT,
                "/libraries/" + library + "/items/item-a",
                Map.of(
                        "expected_revision",
                        0,
                        "draft",
                        Map.of(
                                "name",
                                "预约条件",
                                "content",
                                "具体办理条件需要人工确认",
                                "keywords",
                                "预约 条件",
                                "category",
                                "产品/办理条件")));
        current = lib(library);
        var categories = current.path("categories");
        assertThat(categories).hasSize(2);
        assertThat(categories.get(0).path("entry_count").asInt()).isEqualTo(1);
        api(
                HttpMethod.POST,
                "/libraries/" + library + "/publish",
                Map.of("expected_revision", current.path("revision").asInt()));
        current = lib(library);
        int old = current.path("revision").asInt();
        api(
                HttpMethod.PUT,
                "/libraries/" + library + "/categories",
                Map.of("path", "产品", "new_path", "服务", "expected_revision", old));
        current = lib(library);
        assertThat(current.path("items").get(0).path("draft").path("category").asText())
                .isEqualTo("服务/办理条件");
        var published =
                api(HttpMethod.POST, "/libraries/" + library + "/probe", Map.of("query", "预约条件"));
        assertThat(published.path("hits").get(0).path("category").asText()).isEqualTo("产品/办理条件");
        assertThat(
                        raw(
                                        HttpMethod.PUT,
                                        "/libraries/" + library + "/categories",
                                        Map.of(
                                                "path",
                                                "服务",
                                                "new_path",
                                                "过期修改",
                                                "expected_revision",
                                                old))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        api(
                HttpMethod.POST,
                "/libraries/" + library + "/categories/remove",
                Map.of("path", "服务", "expected_revision", current.path("revision").asInt()));
        current = lib(library);
        assertThat(current.path("categories")).isEmpty();
        assertThat(current.path("items").get(0).path("draft").path("category").asText()).isEmpty();
        api(
                HttpMethod.POST,
                "/libraries/" + library + "/restore",
                Map.of("version", 1, "expected_revision", current.path("revision").asInt()));
        current = lib(library);
        assertThat(current.path("categories")).hasSize(2);
        assertThat(current.path("items").get(0).path("draft").path("category").asText())
                .isEqualTo("产品/办理条件");
        assertThat(current.path("published_version").asInt()).isEqualTo(1);
    }

    @Test
    void documentPreviewIsDurableEditableAndConcurrentConfirmationCreatesOnlyOneDraftSet()
            throws Exception {
        String library = id(library()), key = UUID.randomUUID().toString();
        byte[] bytes = ("预约办理条件需要人工确认。\n".repeat(120)).getBytes(StandardCharsets.UTF_8);
        file(library, key, "条件.md", bytes);
        var job = waitDocument(library, key, "ready");
        assertThat(job.has("file_bytes")).isFalse();
        assertThat(lib(library).path("items")).isEmpty();
        var parts = new ArrayList<Map<String, Object>>();
        for (var part : job.path("segments")) parts.add(object(part));
        String original = parts.get(0).get("content").toString();
        parts.get(0).put("content", "人工核对后的文档正文");
        var stale =
                Map.of(
                        "segments",
                        parts,
                        "expected_revision",
                        999,
                        "expected_library_revision",
                        lib(library).path("revision").asInt());
        assertThat(
                        raw(
                                        HttpMethod.POST,
                                        "/libraries/" + library + "/documents/" + key + "/confirm",
                                        stale)
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        assertThat(lib(library).path("items")).isEmpty();
        var body =
                Map.of(
                        "segments",
                        parts,
                        "expected_revision",
                        job.path("revision").asInt(),
                        "expected_library_revision",
                        lib(library).path("revision").asInt());
        String path = "/libraries/" + library + "/documents/" + key + "/confirm";
        var first = CompletableFuture.supplyAsync(() -> api(HttpMethod.POST, path, body));
        var second = CompletableFuture.supplyAsync(() -> api(HttpMethod.POST, path, body));
        var a = first.join();
        var b = second.join();
        assertThat(a.path("state").asText()).isEqualTo("committed");
        assertThat(b.path("id").asText()).isEqualTo(key);
        var current = lib(library);
        assertThat(current.path("items")).hasSize(parts.size());
        assertThat(current.path("published_version").asInt()).isZero();
        assertThat(a.path("original_segments").get(0).path("content").asText()).isEqualTo(original);
        assertThat(a.path("segments").get(0).path("draft").path("content").asText())
                .isEqualTo("人工核对后的文档正文");
        parts.get(0).put("content", "不同的重复确认");
        assertThat(raw(HttpMethod.POST, path, body).getStatusCode().value()).isEqualTo(409);
        assertThat(lib(library).path("items")).hasSize(parts.size());
    }

    @Test
    void failedParsingCanBeRetriedAndRemovedWithoutCreatingKnowledge() throws Exception {
        String library = id(library()), key = UUID.randomUUID().toString();
        byte[] bytes = {(byte) 0xff};
        file(library, key, "错误.txt", bytes);
        var failed = waitDocument(library, key, "failed");
        assertThat(failed.path("error").asText()).contains("UTF-8");
        var same = file(library, key, "错误.txt", bytes);
        assertThat(same.path("id").asText()).isEqualTo(key);
        assertThat(
                        fileRaw(library, key, "错误.txt", "替换文件".getBytes(StandardCharsets.UTF_8))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        api(
                HttpMethod.POST,
                "/libraries/" + library + "/documents/" + key + "/retry",
                Map.of("expected_revision", failed.path("revision").asInt()));
        failed = waitDocument(library, key, "failed");
        var removed =
                api(
                        HttpMethod.POST,
                        "/libraries/" + library + "/documents/" + key + "/cancel",
                        Map.of("expected_revision", failed.path("revision").asInt()));
        assertThat(removed.path("state").asText()).isEqualTo("cancelled");
        assertThat(lib(library).path("items")).isEmpty();
        assertThat(
                        docs.one(
                                        "SELECT file_bytes FROM platform_knowledge_upload WHERE tenant_id=1 AND id=?",
                                        key)
                                .get("file_bytes"))
                .isNull();
    }

    @Test
    void recoveredQuestionNeedsHumanConfirmationAndAdoptionIsIdempotentAndTenantScoped() {
        String library = id(library()), question = "预约估价需要什么材料？" + UUID.randomUUID();
        var call =
                store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                1,
                                "phone",
                                "13100001234",
                                "mode",
                                "ai_only",
                                "status",
                                "completed",
                                "attempts",
                                1,
                                "max_attempts",
                                1));
        store.insert(
                "speechturn",
                JsonSupport.map(
                        "tenant_id",
                        1,
                        "call_session_id",
                        call.get("id"),
                        "provider_event_key",
                        UUID.randomUUID().toString(),
                        "transcript",
                        question,
                        "normalized_transcript",
                        question,
                        "is_final",
                        true,
                        "attempt",
                        1,
                        "turn_index",
                        1,
                        "asr_provider",
                        "mock"));
        gaps.capture(
                1,
                "call",
                call.get("id").toString(),
                null,
                question,
                Map.of("reason", "published_knowledge_miss", "simulated", true));
        gaps.capture(1, "call", call.get("id").toString(), null, question, Map.of());
        var list = api(HttpMethod.GET, "/knowledge-gaps?keyword=" + question, null);
        assertThat(list.path("total").asInt()).isEqualTo(1);
        var row = list.path("rows").get(0);
        String gap = row.path("id").asText();
        assertThat(row.path("context").path("simulated").asBoolean()).isTrue();
        var draft =
                Map.of(
                        "name",
                        "预约材料",
                        "content",
                        "请由人工核对车辆与证件资料后确认",
                        "source",
                        "通话 " + call.get("id"));
        var body =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "library_id",
                                library,
                                "expected_revision",
                                row.path("revision").asInt(),
                                "draft",
                                draft,
                                "confirmed",
                                false));
        assertThat(
                        raw(HttpMethod.POST, "/knowledge-gaps/" + gap + "/adopt", body)
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        body.put("confirmed", true);
        var adopted = api(HttpMethod.POST, "/knowledge-gaps/" + gap + "/adopt", body);
        var replay = api(HttpMethod.POST, "/knowledge-gaps/" + gap + "/adopt", body);
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.path("item_id")).isEqualTo(adopted.path("item_id"));
        assertThat(lib(library).path("items")).hasSize(1);
        assertThat(lib(library).path("published_version").asInt()).isZero();
        var foreign = account(2, "admin");
        var otherToken = crypto.token(UserRecords.map(foreign));
        assertThat(
                        raw(otherToken, HttpMethod.GET, "/knowledge-gaps/" + gap, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        var agent = account(1, "agent");
        assertThat(
                        raw(
                                        crypto.token(UserRecords.map(agent)),
                                        HttpMethod.GET,
                                        "/knowledge-gaps",
                                        null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        String question2 = question + "费用", callId = call.get("id").toString();
        gaps.capture(1, "call", callId, null, question2, Map.of());
        var pending =
                api(HttpMethod.GET, "/knowledge-gaps?keyword=" + question2, null)
                        .path("rows")
                        .get(0);
        String next = pending.path("id").asText();
        var ignored =
                api(
                        HttpMethod.POST,
                        "/knowledge-gaps/" + next + "/dismiss",
                        Map.of(
                                "reason",
                                "已在其他条目覆盖",
                                "expected_revision",
                                pending.path("revision").asInt()));
        assertThat(ignored.path("state").asText()).isEqualTo("dismissed");
        var reopened =
                api(
                        HttpMethod.POST,
                        "/knowledge-gaps/" + next + "/reopen",
                        Map.of(
                                "reason",
                                "需要重新核对",
                                "expected_revision",
                                ignored.path("revision").asInt()));
        assertThat(reopened.path("state").asText()).isEqualTo("pending");
    }

    Identity identity() {
        return new Identity(1, UserRecords.map(user), () -> Set.of());
    }

    JsonNode script(Map<String, Object> changes) {
        var created =
                api(
                        HttpMethod.POST,
                        "/scripts",
                        Map.of(
                                "name",
                                "方案验收" + UUID.randomUUID(),
                                "category",
                                "服务回访",
                                "goal",
                                "确认客户需求"));
        var draft = object(created.path("draft"));
        draft.put("opening", "您好，{offer}服务咨询。");
        draft.putAll(changes);
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
                Map.of("expected_revision", saved.path("revision").asInt()));
    }

    Map<String, Object> contact(String name) {
        return store.insert(
                "contact",
                JsonSupport.map(
                        "tenant_id",
                        1,
                        "phone",
                        "13"
                                + String.format(
                                        "%09d",
                                        Math.abs(
                                                UUID.randomUUID().getMostSignificantBits()
                                                        % 1000000000)),
                        "name",
                        name,
                        "consent_state",
                        "consented",
                        "consented_at",
                        Store.now(),
                        "dnc",
                        false));
    }

    Map<String, Object> imported(String csv, Map<String, Object> schema, boolean upsert)
            throws Exception {
        return contactFiles.process(
                new ContactImportJobs.Upload("vars.csv", csv.getBytes(StandardCharsets.UTF_8)),
                upsert,
                UUID.randomUUID().toString(),
                identity(),
                false,
                Map.of(),
                schema);
    }

    @Test
    void dynamicTemplateAndTypesKeepExistingIdentityButFreezeBatchVariables() throws Exception {
        var script =
                script(
                        Map.of(
                                "opening",
                                "您好，{carModel}服务咨询。",
                                "import_fields",
                                List.of(
                                        Map.of(
                                                "key",
                                                "carModel",
                                                "label",
                                                "车型",
                                                "kind",
                                                "text",
                                                "required",
                                                true),
                                        Map.of(
                                                "key",
                                                "year",
                                                "label",
                                                "年份",
                                                "kind",
                                                "integer",
                                                "required",
                                                true))));
        String id = script.path("id").asText();
        var schema = contactVariables.schema(1, id, "");
        assertThat(schema.get("fields").toString()).contains("carModel", "year");
        try (var book =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                        new java.io.ByteArrayInputStream(contactVariables.workbook(schema)))) {
            assertThat(book.getSheet("外呼名单").getRow(0).getCell(7).getStringCellValue())
                    .isEqualTo("变量:carModel");
            assertThat(book.getSheet("填写说明")).isNotNull();
        }
        var old = contact("原客户");
        String phone = old.get("phone").toString();
        var invalid =
                imported("手机号,姓名,变量:carModel,变量:year\n" + phone + ",新姓名,轿车,不是年份\n", schema, false);
        assertThat(invalid.get("failed")).isEqualTo(1);
        var result =
                imported("手机号,姓名,变量:carModel,变量:year\n" + phone + ",新姓名,轿车,2021\n", schema, false);
        assertThat(result.get("selected_count")).isEqualTo(1);
        assertThat(store.require("contact", 1, old.get("id")).get("name")).isEqualTo("原客户");
        assertThat(contactVariables.current(1, ((Number) old.get("id")).longValue())).isEmpty();
        var member =
                docs.one(
                        "SELECT variables_json FROM platform_contact_batch_member WHERE tenant_id=1 AND batch_id=?",
                        result.get("batch_id"));
        assertThat(member.get("variables_json").toString()).contains("carModel", "轿车", "2021");
        var preview =
                api(
                        HttpMethod.POST,
                        "/audiences/preview",
                        Map.of("batch_ids", List.of(result.get("batch_id")), "script_id", id));
        assertThat(preview.path("total").asInt()).isEqualTo(1);
        assertThat(preview.path("rows").get(0).path("variables").path("carModel").asText())
                .isEqualTo("轿车");
        var excluded =
                api(
                        HttpMethod.POST,
                        "/audiences/preview",
                        Map.of(
                                "batch_ids",
                                List.of(result.get("batch_id")),
                                "excluded_contact_ids",
                                List.of(old.get("id"))));
        assertThat(excluded.path("total").asInt()).isZero();
        assertThat(excluded.path("excluded").asInt()).isEqualTo(1);
    }

    @Test
    void callTaskPersistsLineCallerComboAndPerCustomerVariablesWithoutCrossTenantSources()
            throws Exception {
        var script = script(Map.of());
        String sid = script.path("id").asText();
        var c = contact("客户甲");
        var batch =
                imported(
                        "手机号,变量:offer\n" + c.get("phone") + ",回访权益\n",
                        contactVariables.schema(1, sid, ""),
                        false);
        var line =
                store.insert(
                        "telephonyline",
                        Map.of(
                                "tenant_id",
                                1,
                                "name",
                                "验收线路",
                                "provider",
                                "mock",
                                "caller_id",
                                "02100000000",
                                "enabled",
                                true));
        var task =
                api(
                        HttpMethod.POST,
                        "/tasks",
                        JsonSupport.map(
                                "name",
                                "任务变量验收",
                                "mode",
                                "ai_only",
                                "script_template_id",
                                script.path("id").asLong(),
                                "contact_ids",
                                List.of(),
                                "batch_ids",
                                List.of(batch.get("batch_id")),
                                "line_ids",
                                List.of(line.get("id")),
                                "service_combo",
                                "legacy",
                                "concurrency",
                                1));
        long taskId = task.path("id").asLong();
        assertThat(task.path("snapshot").path("lines").get(0).path("caller_id").asText())
                .isEqualTo("02100000000");
        var frozen =
                contactVariables.frozen(1, "call:" + taskId, ((Number) c.get("id")).longValue());
        assertThat(frozen.get("offer")).isEqualTo("回访权益");
        var call =
                store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                1,
                                "campaign_id",
                                taskId,
                                "contact_id",
                                c.get("id"),
                                "phone",
                                c.get("phone"),
                                "mode",
                                "ai_only",
                                "attempts",
                                1));
        var state = docs.tx.serialized(() -> conversations.state(call));
        var policy = conversations.policy(state);
        assertThat(policy.get("_script").toString()).contains("回访权益").doesNotContain("{offer}");
        assertThat(map(policy.get("_ai")).get("voice_backend")).isEqualTo("legacy");
        var foreign = contact("外租户客户");
        docs.jdbc.update("UPDATE contact SET tenant_id=2 WHERE id=?", foreign.get("id"));
        assertThat(
                        raw(
                                        HttpMethod.POST,
                                        "/audiences/preview",
                                        Map.of("contact_ids", List.of(foreign.get("id"))))
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
    }

    static Map<String, Object> map(Object value) {
        return WorkspaceDocuments.map(value);
    }

    @Test
    void businessDebugFreezesPublishedKnowledgeAndReplaysTurnsAfterReload() throws Exception {
        var script = script(Map.of());
        String sid = script.path("id").asText(), key = UUID.randomUUID().toString();
        var input =
                Map.<String, Object>of(
                        "name",
                        "三栏调试",
                        "script_id",
                        sid,
                        "request_key",
                        key,
                        "variables",
                        Map.of("offer", "专属咨询"));
        var session = api(HttpMethod.POST, "/debug-sessions", input);
        String id = session.path("id").asText();
        assertThat(api(HttpMethod.POST, "/debug-sessions", input).path("id").asText())
                .isEqualTo(id);
        var request =
                Map.of(
                        "request_key",
                        UUID.randomUUID().toString(),
                        "expected_revision",
                        session.path("revision").asInt(),
                        "text",
                        "贷款利息会是多少？");
        var turned = api(HttpMethod.POST, "/debug-sessions/" + id + "/turns", request);
        assertThat(turned.path("turns")).hasSize(1);
        assertThat(api(HttpMethod.POST, "/debug-sessions/" + id + "/turns", request).path("turns"))
                .hasSize(1);
        assertThat(
                        api(HttpMethod.GET, "/debug-sessions/" + id, null)
                                .path("snapshot")
                                .path("variables")
                                .path("offer")
                                .asText())
                .isEqualTo("专属咨询");
        assertThat(
                        api(
                                        HttpMethod.GET,
                                        "/knowledge-gaps?source=debug&state=pending&keyword=贷款利息",
                                        null)
                                .path("rows")
                                .toString())
                .contains(id);
        var ended =
                api(
                        HttpMethod.POST,
                        "/debug-sessions/" + id + "/end",
                        Map.of("expected_revision", turned.path("revision").asInt()));
        assertThat(ended.path("state").asText()).isEqualTo("ended");
        assertThat(
                        raw(
                                        HttpMethod.POST,
                                        "/debug-sessions/" + id + "/turns",
                                        Map.of(
                                                "request_key",
                                                UUID.randomUUID().toString(),
                                                "expected_revision",
                                                ended.path("revision").asInt(),
                                                "text",
                                                "继续"))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
    }

    @Test
    void skillsAndConnectionsAreMaintainedWithRevisionAndReferenceProtection() {
        String id = UUID.randomUUID().toString();
        var skill =
                api(
                        HttpMethod.PUT,
                        "/catalog/skill/" + id,
                        Map.of(
                                "expected_revision",
                                0,
                                "draft",
                                Map.of("name", "北京服务", "group", "region", "enabled", true)));
        docs.jdbc.update(
                "INSERT INTO ops_team(tenant_id,name,settings_json) VALUES(1,?,?)",
                "技能团队" + UUID.randomUUID(),
                docs.json.json(Map.of("routing_skills", List.of("北京服务"))));
        assertThat(api(HttpMethod.GET, "/catalog/skill/" + id + "/references", null)).hasSize(1);
        assertThat(
                        raw(
                                        HttpMethod.PUT,
                                        "/catalog/skill/" + id,
                                        Map.of(
                                                "expected_revision",
                                                skill.path("revision").asInt(),
                                                "draft",
                                                Map.of("name", "上海服务", "group", "region")))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        var disabled =
                api(
                        HttpMethod.PUT,
                        "/catalog/skill/" + id,
                        Map.of(
                                "expected_revision",
                                skill.path("revision").asInt(),
                                "draft",
                                Map.of("name", "北京服务", "group", "region", "enabled", false)));
        assertThat(disabled.path("draft").path("enabled").asBoolean()).isFalse();
        var connection =
                api(
                        HttpMethod.PUT,
                        "/catalog/connection/" + UUID.randomUUID(),
                        Map.of(
                                "expected_revision",
                                0,
                                "draft",
                                Map.of(
                                        "name",
                                        "测试连接",
                                        "credential_ref",
                                        "TEST_PBX",
                                        "gateway_url",
                                        "https://gateway.example.com",
                                        "enabled",
                                        true)));
        assertThat(connection.path("draft").path("credential_ref").asText()).isEqualTo("TEST_PBX");
        assertThat(api(HttpMethod.GET, "/catalog/connection", null).toString())
                .contains("credential_configured")
                .doesNotContain("TELEPHONY_SECRET");
    }

    String asset(String kind, Map<String, Object> payload) {
        var row =
                map(
                        messaging.createResource(
                                1,
                                kind,
                                Map.of("name", kind + UUID.randomUUID(), "payload", payload)));
        messaging.resourceState(1, row.get("id").toString(), "APPROVED");
        return row.get("id").toString();
    }

    @Test
    void smsTaskUsesSharedVariablesAndDurableChunksWithoutDuplicateSubmissions() throws Exception {
        String product = asset("product", Map.of("form", "sms", "unit_price", 1000)),
                template =
                        asset(
                                "template",
                                Map.of("form", "sms", "body", "您好{{name}}，您的权益为{{offer}}。")),
                sender = asset("sender", Map.of("brand", "验收签名"));
        var csv = new StringBuilder("手机号,姓名,联系授权状态,变量:offer\n");
        for (int n = 0; n < 123; n++) {
            var c = contact("客户" + n);
            csv.append(c.get("phone"))
                    .append(',')
                    .append(c.get("name"))
                    .append(",已同意,优惠")
                    .append(n)
                    .append('\n');
        }
        var batch = imported(csv.toString(), contactVariables.schema(1, "", template), false);
        String id = UUID.randomUUID().toString();
        var draft =
                JsonSupport.<String, Object>map(
                        "name",
                        "短信批次验收",
                        "product_id",
                        product,
                        "template_id",
                        template,
                        "sender_id",
                        sender,
                        "batch_ids",
                        List.of(batch.get("batch_id")),
                        "budget",
                        1000000,
                        "expires_at",
                        java.time.Instant.now().plusSeconds(3600).toString());
        var saved =
                api(
                        HttpMethod.PUT,
                        "/sms-tasks/" + id,
                        Map.of("expected_revision", 0, "draft", draft));
        assertThat(saved.path("job").isNull()).isTrue();
        messaging.credit(1, "preflight-credit-" + UUID.randomUUID(), 10000000);
        assertThat(
                        api(HttpMethod.GET, "/sms-tasks/" + id + "/preflight", null)
                                .path("ready")
                                .asBoolean())
                .isTrue();
        messaging.credit(1, "credit-" + UUID.randomUUID(), 10000000);
        var input =
                Map.of(
                        "expected_revision",
                        saved.path("revision").asInt(),
                        "request_key",
                        UUID.randomUUID().toString(),
                        "confirmed",
                        true);
        api(HttpMethod.POST, "/sms-tasks/" + id + "/start", input);
        api(HttpMethod.POST, "/sms-tasks/" + id + "/start", input);
        while (workspaceMessages.processNext()) {}
        ;
        var result = api(HttpMethod.GET, "/sms-tasks/" + id, null);
        assertThat(result.path("job").path("submitted_count").asInt()).isEqualTo(123);
        assertThat(result.path("job").path("chunks")).hasSize(2);
        assertThat(
                        docs.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM msg_batch WHERE tenant_id=1", Integer.class))
                .isEqualTo(2);
        while (messaging.processBatch()) {}
        ;
        assertThat(
                        docs.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM msg_message WHERE tenant_id=1 AND campaign_id=?",
                                Integer.class,
                                result.path("job").path("campaign_id").asText()))
                .isEqualTo(123);
        assertThat(
                        raw(
                                        HttpMethod.PUT,
                                        "/sms-tasks/" + id,
                                        Map.of(
                                                "expected_revision",
                                                saved.path("revision").asInt(),
                                                "draft",
                                                draft))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
    }

    @Test
    void priceUnknownAndZeroRemainDistinctAndSavedMetadataEchoes() {
        var input =
                Map.of(
                        "expected_revision",
                        0,
                        "draft",
                        JsonSupport.map(
                                "telephony",
                                0,
                                "ai",
                                null,
                                "sms",
                                0.1,
                                "source",
                                "内部报价V1",
                                "valid_from",
                                "2026-11-01",
                                "valid_until",
                                "2026-11-30"));
        var saved = api(HttpMethod.PUT, "/cost-assumptions", input);
        assertThat(saved.path("draft").path("telephony").asInt()).isZero();
        assertThat(saved.path("draft").path("ai").isNull()).isTrue();
        assertThat(
                        api(HttpMethod.GET, "/cost-assumptions", null)
                                .path("draft")
                                .path("source")
                                .asText())
                .isEqualTo("内部报价V1");
        assertThat(raw(HttpMethod.PUT, "/cost-assumptions", input).getStatusCode().value())
                .isEqualTo(409);
    }

    @Test
    void confirmedCallCompletionCreatesOneTemplateSmsAndProtectsPublishedTaskVersion()
            throws Exception {
        var script = script(Map.of());
        String sid = script.path("id").asText();
        var c = contact("联动客户");
        var batch =
                imported(
                        "手机号,变量:offer\n" + c.get("phone") + ",服务回访\n",
                        contactVariables.schema(1, sid, ""),
                        false);
        var task =
                api(
                        HttpMethod.POST,
                        "/tasks",
                        JsonSupport.map(
                                "name",
                                "挂机短信验收",
                                "mode",
                                "ai_only",
                                "script_template_id",
                                script.path("id").asLong(),
                                "batch_ids",
                                List.of(batch.get("batch_id")),
                                "contact_ids",
                                List.of()));
        long taskId = task.path("id").asLong();
        String product = asset("product", Map.of("form", "sms", "unit_price", 1000)),
                template =
                        asset(
                                "template",
                                Map.of("form", "sms", "body", "您好{{name}}，关于{{offer}}稍后联系。")),
                sender = asset("sender", Map.of("brand", "服务企业"));
        var rule =
                api(
                        HttpMethod.POST,
                        "/tasks/" + taskId + "/sms-rule",
                        Map.of(
                                "confirmed",
                                true,
                                "condition",
                                "completed",
                                "budget",
                                1000,
                                "product_id",
                                product,
                                "template_id",
                                template,
                                "sender_id",
                                sender));
        messaging.credit(1, "credit-" + UUID.randomUUID(), 1000000);
        var call =
                store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                1,
                                "campaign_id",
                                taskId,
                                "contact_id",
                                c.get("id"),
                                "phone",
                                c.get("phone"),
                                "status",
                                "in_ai",
                                "mode",
                                "ai_only",
                                "attempts",
                                1,
                                "max_attempts",
                                1));
        var callback =
                JsonSupport.map(
                        "call_id",
                        call.get("id"),
                        "payload",
                        Map.of(
                                "event_id",
                                UUID.randomUUID().toString(),
                                "attempt",
                                1,
                                "status",
                                "completed"));
        lifecycle.status(callback);
        lifecycle.status(callback);
        assertThat(
                        docs.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM taskoutbox WHERE tenant_id=1 AND task_type='send_sms' AND aggregate_id=?",
                                Integer.class,
                                call.get("id").toString()))
                .isEqualTo(1);
        for (int n = 0; n < 15 && worker.processLane(1L, 3); n++) {}
        assertThat(messaging.processCallIntent()).isTrue();
        var intent =
                docs.one(
                        "SELECT * FROM msg_call_intent WHERE tenant_id=1 AND call_id=?",
                        call.get("id").toString().replace("-", ""));
        // UUID representation differs between generic call records and JDBC rows.
        if (intent == null)
            intent =
                    docs.one(
                            "SELECT * FROM msg_call_intent WHERE tenant_id=1 AND rule_id=?",
                            rule.path("id").asText());
        assertThat(intent).isNotNull();
        assertThat(intent.get("state")).isEqualTo("CREATED");
        assertThat(intent.get("message_id")).isNotNull();
        assertThat(messaging.processCallIntent()).isFalse();
        var copied = api(HttpMethod.POST, "/scripts/" + sid + "/copy", Map.of());
        assertThat(copied.path("draft").path("goal").asText()).isEqualTo("确认客户需求");
        assertThat(
                        api(HttpMethod.GET, "/task-resources/tasks/" + taskId + "/members", null)
                                .path("rows")
                                .get(0)
                                .path("variables")
                                .path("offer")
                                .asText())
                .isEqualTo("服务回访");
    }

    @Test
    void actualMockAdmissionUsesOnlyBoundLineAndFrozenCallerAndWaitsWhenDisabled()
            throws Exception {
        docs.jdbc.update(
                "INSERT INTO adminsetting(tenant_id,section,data_json,updated_at) VALUES(1,'compliance',?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE data_json=VALUES(data_json)",
                docs.json.json(Map.of("allowed_start_hour", 0, "allowed_end_hour", 0)));
        var script = script(Map.of("opening", "您好，测试服务回访。"));
        var first = contact("绑定客户一");
        var second = contact("绑定客户二");
        var chosen =
                store.insert(
                        "telephonyline",
                        Map.of(
                                "tenant_id",
                                1,
                                "name",
                                "低优先级绑定线路",
                                "provider",
                                "mock",
                                "caller_id",
                                "02100000000",
                                "priority",
                                900,
                                "max_concurrency",
                                3,
                                "enabled",
                                true));
        store.insert(
                "telephonyline",
                Map.of(
                        "tenant_id",
                        1,
                        "name",
                        "更高优先级未选择线路",
                        "provider",
                        "mock",
                        "caller_id",
                        "01000000000",
                        "priority",
                        1,
                        "enabled",
                        true));
        var task =
                api(
                        HttpMethod.POST,
                        "/tasks",
                        JsonSupport.map(
                                "name",
                                "线路真实约束验收",
                                "mode",
                                "ai_only",
                                "script_template_id",
                                script.path("id").asLong(),
                                "contact_ids",
                                List.of(first.get("id"), second.get("id")),
                                "line_ids",
                                List.of(chosen.get("id")),
                                "service_combo",
                                "legacy",
                                "execution_policy",
                                Map.of(
                                        "start_hour",
                                        0,
                                        "end_hour",
                                        24,
                                        "weekdays",
                                        List.of(0, 1, 2, 3, 4, 5, 6))));
        store.update(
                "campaign",
                1,
                task.path("id").asLong(),
                Map.of("status", "running", "dispatch_enabled", true));
        store.update("telephonyline", 1, chosen.get("id"), Map.of("caller_id", "02199999999"));
        var call =
                store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                1,
                                "campaign_id",
                                task.path("id").asLong(),
                                "contact_id",
                                first.get("id"),
                                "phone",
                                first.get("phone"),
                                "mode",
                                "ai_only",
                                "status",
                                "created",
                                "attempts",
                                0,
                                "max_attempts",
                                1));
        boolean admitted = dial.dispatch(call.get("id"));
        assertThat(admitted)
                .as(docs.json.json(store.require("callsession", 1, call.get("id"))))
                .isTrue();
        var dispatched = store.require("callsession", 1, call.get("id"));
        assertThat(dispatched.get("telephony_line_id").toString())
                .isEqualTo(chosen.get("id").toString());
        assertThat(dial.metadataFor(dispatched).get("caller_id")).isEqualTo("02100000000");
        store.update("telephonyline", 1, chosen.get("id"), Map.of("enabled", false));
        var next =
                store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                1,
                                "campaign_id",
                                task.path("id").asLong(),
                                "contact_id",
                                second.get("id"),
                                "phone",
                                second.get("phone"),
                                "mode",
                                "ai_only",
                                "status",
                                "created",
                                "attempts",
                                0,
                                "max_attempts",
                                1));
        assertThat(dial.dispatch(next.get("id"))).isFalse();
        assertThat(store.require("callsession", 1, next.get("id")).get("telephony_line_id"))
                .isNull();
        var outside = contact("未冻结的客户");
        var bad =
                store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                1,
                                "campaign_id",
                                task.path("id").asLong(),
                                "contact_id",
                                outside.get("id"),
                                "phone",
                                outside.get("phone"),
                                "mode",
                                "ai_only",
                                "status",
                                "created",
                                "attempts",
                                0,
                                "max_attempts",
                                1));
        assertThat(dial.dispatch(bad.get("id"))).isFalse();
        assertThat(store.require("callsession", 1, bad.get("id")).get("last_error").toString())
                .contains("not_frozen");
        lifecycle.status(
                JsonSupport.map(
                        "call_id",
                        call.get("id"),
                        "payload",
                        Map.of(
                                "status",
                                "completed",
                                "event_id",
                                UUID.randomUUID().toString(),
                                "attempt",
                                1)));
    }

    @Test
    void preV38TaskDoesNotAcquireNewUnknownAnswerPolicyAfterUpgrade() {
        var c = contact("历史快照客户");
        var campaign =
                store.insert(
                        "campaign",
                        Map.of(
                                "tenant_id",
                                1,
                                "name",
                                "旧快照任务",
                                "mode",
                                "ai_only",
                                "script",
                                "原业务指引"));
        var policy = conversations.current(1, campaign.get("id")).get("policy");
        docs.jdbc.update(
                "INSERT INTO platform_campaign_snapshot(tenant_id,campaign_id,snapshot_json,created_at) VALUES(1,?,?,?)",
                campaign.get("id"),
                docs.json.json(
                        JsonSupport.map(
                                "script",
                                "旧版本话术",
                                "script_id",
                                1,
                                "script_version",
                                1,
                                "policy",
                                policy,
                                "knowledge",
                                List.of())),
                Store.now());
        var call =
                store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                1,
                                "campaign_id",
                                campaign.get("id"),
                                "contact_id",
                                c.get("id"),
                                "phone",
                                c.get("phone"),
                                "mode",
                                "ai_only",
                                "attempts",
                                1));
        var frozen = docs.tx.serialized(() -> conversations.policy(conversations.state(call)));
        assertThat(frozen).doesNotContainKey("_knowledge_fallback");
        assertThat(frozen.get("_script")).isEqualTo("旧版本话术");
    }
}
