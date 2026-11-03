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
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.job.service.Outbox;
import com.duozhi.outbound.system.dao.AuditLogMapper;
import com.duozhi.outbound.system.service.DataRetention;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.*;
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
class CustomerHttpIT {
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

    @BeforeEach
    void seed() {
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("customer-scenario", overrides));
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
        environment.getPropertySources().remove("customer-scenario");
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

    @Test
    void contactCreateReadPatchListDeleteKeepsContractAndAudit() {
        String number = phone();
        var created = create("+" + number);
        long id = created.get("id").asLong();
        assertThat(created.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "phone",
                        "name",
                        "tags",
                        "consent_state",
                        "dnc",
                        "dnc_reason",
                        "timezone",
                        "id",
                        "tenant_id",
                        "consented_at",
                        "consented_by",
                        "created_at",
                        "updated_at");
        assertThat(created.get("phone").asText()).isEqualTo(number);
        assertThat(created.get("consented_by").asText()).isEqualTo(user.getUsername());
        assertThat(created.get("dnc_reason").isNull()).isTrue();
        assertThat(ok(request(HttpMethod.GET, "/api/v1/contacts/" + id, null))).isEqualTo(created);
        var patch =
                ok(
                        request(
                                HttpMethod.PATCH,
                                "/api/v1/contacts/" + id,
                                Map.of("name", "改名", "tags", "a,b")));
        assertThat(patch.get("phone").asText()).isEqualTo(number);
        assertThat(patch.get("name").asText()).isEqualTo("改名");
        var list = ok(request(HttpMethod.GET, "/api/v1/contacts?keyword=" + number, null));
        assertThat(list).hasSize(1);
        assertThat(
                        ok(request(HttpMethod.DELETE, "/api/v1/contacts/" + id, null))
                                .get("result")
                                .asText())
                .isEqualTo("deleted");
        assertThat(request(HttpMethod.GET, "/api/v1/contacts/" + id, null).getStatusCode().value())
                .isEqualTo(404);
        assertThat(
                        audits.list(1, 0, 200).stream()
                                .filter(
                                        a ->
                                                String.valueOf(id).equals(a.getResourceId())
                                                        && "contact".equals(a.getResourceType()))
                                .count())
                .isEqualTo(3);
    }

    @Test
    void invalidAndDuplicateContactKeepSourceErrors() {
        String number = phone();
        create(number);
        var duplicate = request(HttpMethod.POST, "/api/v1/contacts", Map.of("phone", number));
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
        assertThat(duplicate.getBody().get("detail").asText())
                .isEqualTo("contact phone already exists");
        assertThat(
                        request(HttpMethod.POST, "/api/v1/contacts", Map.of("phone", "abcdef"))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(
                        request(
                                        HttpMethod.POST,
                                        "/api/v1/contacts",
                                        Map.of("phone", phone(), "timezone", "Invalid/Zone"))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(request(HttpMethod.GET, "/api/v1/contacts?page=0", null).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void partialPatchAndConsentTransitionsPreserveOtherFields() {
        var c = create(phone());
        long id = c.get("id").asLong();
        var changed =
                ok(
                        request(
                                HttpMethod.PATCH,
                                "/api/v1/contacts/" + id,
                                Map.of("consent_state", "revoked")));
        assertThat(changed.get("consented_at").isNull()).isTrue();
        assertThat(changed.get("consented_by").isNull()).isTrue();
        assertThat(changed.get("name")).isEqualTo(c.get("name"));
        var changes = new LinkedHashMap<String, Object>();
        changes.put("name", null);
        var empty = ok(request(HttpMethod.PATCH, "/api/v1/contacts/" + id, changes));
        assertThat(empty.get("name").isNull()).isTrue();
        assertThat(empty.get("phone")).isEqualTo(c.get("phone"));
    }

    @Test
    void batchDncSortsDeduplicatesAndReportsMissing() {
        var c = create(phone());
        long id = c.get("id").asLong();
        var result =
                ok(
                        request(
                                HttpMethod.PATCH,
                                "/api/v1/contacts/batch-dnc",
                                Map.of(
                                        "contact_ids",
                                        List.of(2147483647L, id, id),
                                        "dnc",
                                        true,
                                        "dnc_reason",
                                        "do not contact")));
        assertThat(result.get("total").asInt()).isEqualTo(2);
        assertThat(result.get("updated").asInt()).isEqualTo(1);
        assertThat(result.get("missing_contact_ids").get(0).asLong()).isEqualTo(2147483647L);
        var changed = ok(request(HttpMethod.GET, "/api/v1/contacts/" + id, null));
        assertThat(changed.get("dnc").asBoolean()).isTrue();
        assertThat(changed.get("consented_at").isNull()).isTrue();
        assertThat(changed.get("consent_state").asText()).isEqualTo("consented");
    }

    @Test
    void customerTenantAndExplicitMachinePermissionAreEnforced() {
        var foreign =
                store.insert(
                        "contact", Values.map("tenant_id", 2, "phone", phone(), "name", "foreign"));
        for (var method : List.of(HttpMethod.GET, HttpMethod.PATCH, HttpMethod.DELETE))
            assertThat(
                            request(
                                            method,
                                            "/api/v1/contacts/" + foreign.get("id"),
                                            method == HttpMethod.PATCH
                                                    ? Map.of("name", "wrong")
                                                    : null)
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(404);
        var h = new HttpHeaders();
        h.set("x-api-key", "customer-test-key");
        assertThat(
                        http.exchange(
                                        "/api/v1/contacts",
                                        HttpMethod.GET,
                                        new HttpEntity<>(h),
                                        JsonNode.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        overrides.put("TENANT_API_SCOPES_JSON", "{\"1\":[\"contacts:write\"]}");
        assertThat(
                        http.exchange(
                                        "/api/v1/contacts",
                                        HttpMethod.GET,
                                        new HttpEntity<>(h),
                                        JsonNode.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
    }

    ResponseEntity<JsonNode> upload(byte[] bytes, String key, boolean upsert) {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add(
                "file",
                new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return "contacts.csv";
                    }
                });
        var h = auth();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.set("Idempotency-Key", key);
        return http.exchange(
                "/api/v1/contacts/import?upsert=" + upsert,
                HttpMethod.POST,
                new HttpEntity<>(parts, h),
                JsonNode.class);
    }

    @Test
    void csvImportPreservesIdempotentReplayAndRowOutcomes() {
        String a = phone(), b = phone(), key = UUID.randomUUID().toString();
        var csv =
                "\ufeffphone,name,consent_state,dnc,timezone\n"
                        + a
                        + ",首条,consented,false,Asia/Shanghai\n"
                        + b
                        + ",次条,revoked,true,Asia/Shanghai\n"
                        + a
                        + ",重复,consented,false,Asia/Shanghai\n"
                        + "bad,错误,consented,false,Asia/Shanghai\n";
        var result = ok(upload(csv.getBytes(StandardCharsets.UTF_8), key, true));
        assertThat(result.get("total").asInt()).isEqualTo(4);
        assertThat(result.get("created").asInt()).isEqualTo(2);
        assertThat(result.get("skipped").asInt()).isEqualTo(1);
        assertThat(result.get("failed").asInt()).isEqualTo(1);
        assertThat(result.get("errors")).hasSize(2);
        assertThat(ok(upload(csv.getBytes(StandardCharsets.UTF_8), key, true))).isEqualTo(result);
        assertThat(
                        upload("phone\n000000\n".getBytes(StandardCharsets.UTF_8), key, true)
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        var updated =
                ok(
                        upload(
                                ("phone,name\n" + a + ",新名\n").getBytes(StandardCharsets.UTF_8),
                                UUID.randomUUID().toString(),
                                true));
        assertThat(updated.get("updated").asInt()).isEqualTo(1);
        assertThat(
                        ok(upload(
                                        ("phone,name\n" + a + ",跳过\n")
                                                .getBytes(StandardCharsets.UTF_8),
                                        UUID.randomUUID().toString(),
                                        false))
                                .get("skipped")
                                .asInt())
                .isEqualTo(1);
    }

    @Test
    void csvRejectsEncodingKeyAndLimitsBeforeMutation() {
        assertThat(
                        upload(new byte[] {(byte) 0xff}, UUID.randomUUID().toString(), true)
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(
                        upload("phone\n123456\n".getBytes(StandardCharsets.UTF_8), "bad key", true)
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        overrides.put("CONTACT_IMPORT_MAX_ROWS", "1");
        String first = phone(), second = phone();
        assertThat(
                        upload(
                                        ("phone\n" + first + "\n" + second + "\n")
                                                .getBytes(StandardCharsets.UTF_8),
                                        UUID.randomUUID().toString(),
                                        true)
                                .getStatusCode()
                                .value())
                .isEqualTo(413);
        assertThat(store.one("contact", "findByPhone", Values.map("tenantId", 1, "phone", first)))
                .isNull();
        overrides.put("CONTACT_IMPORT_MAX_BYTES", "2");
        assertThat(
                        upload(
                                        "phone\n123456\n".getBytes(StandardCharsets.UTF_8),
                                        UUID.randomUUID().toString(),
                                        true)
                                .getStatusCode()
                                .value())
                .isEqualTo(413);
    }

    @Test
    void csvExportLoadsActualDataAndEscapesFormulas() {
        var c = create(phone());
        ok(
                request(
                        HttpMethod.PATCH,
                        "/api/v1/contacts/" + c.get("id").asLong(),
                        Map.of("name", "=formula", "tags", "x,y")));
        var result =
                http.exchange(
                        "/api/v1/contacts/export?keyword=" + c.get("phone").asText(),
                        HttpMethod.GET,
                        new HttpEntity<>(auth()),
                        String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getHeaders().getContentType().toString()).contains("text/csv");
        assertThat(result.getBody())
                .startsWith(
                        "\ufeffphone,name,tags,consent_state,dnc,dnc_reason,timezone,created_at,updated_at");
        assertThat(result.getBody())
                .contains(
                        "'=formula", "\"x,y\"", "ConsentState.CONSENTED", c.get("phone").asText());
    }

    @Test
    void callHistoryPreventsDeletion() {
        var c = create(phone());
        store.insert(
                "callsession",
                Values.map(
                        "tenant_id",
                        1,
                        "phone",
                        c.get("phone").asText(),
                        "mode",
                        "ai_only",
                        "contact_id",
                        c.get("id").asInt()));
        var result = request(HttpMethod.DELETE, "/api/v1/contacts/" + c.get("id").asLong(), null);
        assertThat(result.getStatusCode().value()).isEqualTo(409);
        assertThat(result.getBody().get("detail").asText())
                .isEqualTo(
                        "contact is referenced by campaign or call history; mark it DNC instead");
    }

    @Test
    void policyChecksDncConsentWindowAndDestinationInSourceOrder() {
        String number = phone();
        var c = contact(number);
        assertThat(at(number)).isEmpty();
        store.update("contact", 1, c.get("id"), Values.map("consent_state", "revoked"));
        assertThat(at(number)).isEqualTo("consent_revoked");
        store.update(
                "contact",
                1,
                c.get("id"),
                Values.map("consent_state", "not_consented", "dnc", true));
        assertThat(at(number)).isEqualTo("contact_dnc");
        overrides.put("OUTBOUND_ALLOWED_PHONE_PREFIXES", "99");
        assertThat(at(number)).isEqualTo("platform_destination_not_allowed");
        store.insert("phonesuppression", Values.map("tenant_id", 1, "phone", number));
        assertThat(at(number)).isEqualTo("contact_dnc");
        String fresh = phone();
        overrides.remove("OUTBOUND_ALLOWED_PHONE_PREFIXES");
        assertThat(at(fresh)).isEqualTo("explicit_consent_required");
        contact(fresh);
        assertThat(policy.checkAt(1, fresh, true, null, Instant.parse("2026-10-19T15:00:00Z")))
                .isEqualTo("outside_calling_hours");
    }

    @Test
    void policyDailyAttemptsAndRetryIntervalUseActualCalls() {
        String number = phone();
        contact(number);
        var call =
                store.insert(
                        "callsession",
                        Values.map(
                                "tenant_id",
                                1,
                                "phone",
                                number,
                                "mode",
                                "ai_only",
                                "attempts",
                                3,
                                "created_at",
                                LocalDateTime.of(2026, 10, 19, 3, 0)));
        assertThat(at(number)).isEqualTo("daily_attempt_limit");
        store.update(
                "callsession",
                1,
                call.get("id"),
                Values.map(
                        "attempts", 0, "finished_at", LocalDateTime.of(2026, 10, 19, 3, 59, 30)));
        var policy = Values.map("min_attempt_interval_sec", 60);
        store.insert(
                "adminsetting",
                Values.map(
                        "tenant_id",
                        1,
                        "section",
                        "compliance",
                        "data_json",
                        json.valueToTree(policy).toString()));
        try {
            assertThat(at(number)).isEqualTo("retry_interval_not_elapsed");
            assertThat(
                            this.policy.checkAt(
                                    1,
                                    number,
                                    true,
                                    call.get("id"),
                                    Instant.parse("2026-10-19T04:00:00Z")))
                    .isEmpty();
        } finally {
            var setting =
                    store.one(
                            "adminsetting",
                            "findBySection",
                            Values.map("tenantId", 1, "section", "compliance"));
            store.delete("adminsetting", 1, setting.get("id"));
        }
    }

    @Test
    void transactionRollbackReleasesIdentityAndTenantLocks() {
        String number = phone();
        assertThatThrownBy(
                        () ->
                                tx.run(
                                        () -> {
                                            tx.platform();
                                            tx.tenant(1);
                                            store.insert(
                                                    "contact",
                                                    Values.map("tenant_id", 1, "phone", number));
                                            throw new ApiError(400, "rollback");
                                        }))
                .isInstanceOf(ApiError.class);
        assertThat(
                        tx.run(
                                () -> {
                                    tx.platform();
                                    tx.tenant(1);
                                    return store.one(
                                            "contact",
                                            "findByPhone",
                                            Values.map("tenantId", 1, "phone", number));
                                }))
                .isNull();
    }

    @Test
    void retentionRemovesExpiredTextAndRedactsAllLinkedEvidence() {
        LocalDateTime old = Store.now().minusDays(400);
        String number = phone();
        var call =
                store.insert(
                        "callsession",
                        Values.map(
                                "tenant_id",
                                1,
                                "phone",
                                number,
                                "mode",
                                "ai_only",
                                "status",
                                "completed",
                                "finished_at",
                                old,
                                "created_at",
                                old,
                                "last_transcript",
                                "sensitive",
                                "summary",
                                "sensitive",
                                "recording_url",
                                "https://recording.invalid/private"));
        Object id = call.get("id");
        var partial =
                store.insert(
                        "speechturn",
                        Values.map(
                                "tenant_id",
                                1,
                                "call_session_id",
                                id,
                                "provider_event_key",
                                UUID.randomUUID().toString(),
                                "created_at",
                                old,
                                "is_final",
                                false));
        var last =
                store.insert(
                        "speechturn",
                        Values.map(
                                "tenant_id",
                                1,
                                "call_session_id",
                                id,
                                "provider_event_key",
                                UUID.randomUUID().toString(),
                                "created_at",
                                old,
                                "is_final",
                                true));
        var recent =
                store.insert(
                        "speechturn",
                        Values.map(
                                "tenant_id",
                                1,
                                "call_session_id",
                                id,
                                "provider_event_key",
                                UUID.randomUUID().toString(),
                                "is_final",
                                true));
        var state =
                store.insert(
                        "conversationstate",
                        Values.map(
                                "tenant_id",
                                1,
                                "call_id",
                                id,
                                "data_json",
                                "{\"phone\":\"private\"}",
                                "policy_json",
                                "{\"private\":true}"));
        var event =
                store.insert(
                        "callevent",
                        Values.map(
                                "call_session_id",
                                id,
                                "event_type",
                                "status",
                                "payload",
                                "{\"private\":true}",
                                "created_at",
                                old));
        var analysis =
                store.insert(
                        "callanalysis",
                        Values.map(
                                "tenant_id",
                                1,
                                "call_session_id",
                                id,
                                "summary",
                                "sensitive",
                                "needs_review",
                                true));
        var task =
                outbox.enqueue(
                        1, "ai_turn", id, "retention-ai:" + id, Values.map("private", "private"));
        var sms =
                store.insert(
                        "smslog",
                        Values.map(
                                "tenant_id",
                                1,
                                "call_session_id",
                                id,
                                "to_phone",
                                number,
                                "content",
                                "private"));
        var counts = retention.purge(1000);
        assertThat(counts.get("redacted_calls")).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> store.require("speechturn", 1, partial.get("id")))
                .isInstanceOf(ApiError.class);
        assertThatThrownBy(() -> store.require("speechturn", 1, last.get("id")))
                .isInstanceOf(ApiError.class);
        assertThat(store.require("speechturn", 1, recent.get("id"))).isNotNull();
        var redacted = store.require("callsession", 1, id);
        assertThat(redacted.get("phone").toString()).startsWith("redacted:");
        assertThat(redacted.get("last_transcript")).isNull();
        assertThat(redacted.get("summary")).isNull();
        assertThat(redacted.get("recording_url")).isNull();
        var cleared = store.require("conversationstate", 1, state.get("id"));
        assertThat(cleared.get("data_json")).isEqualTo("{}");
        assertThat(((Number) cleared.get("generation")).intValue()).isEqualTo(1);
        assertThat(store.require("callevent", 1, event.get("id")).get("payload")).isEqualTo("{}");
        assertThat(store.require("callanalysis", 1, analysis.get("id")).get("needs_review"))
                .isEqualTo(false);
        assertThat(store.require("taskoutbox", 1, task.get("id")).get("state")).isEqualTo("dead");
        assertThat(store.require("smslog", 1, sms.get("id")).get("content")).isEqualTo("");
    }

    @Test
    void recordingExpirationEnqueuesOneDurableDeletionAndArchiveRetainsIdempotency() {
        LocalDateTime old = Store.now().minusDays(400);
        var call =
                store.insert(
                        "callsession",
                        Values.map(
                                "tenant_id",
                                2,
                                "phone",
                                "redacted:archive-test",
                                "mode",
                                "ai_only",
                                "status",
                                "completed",
                                "finished_at",
                                old,
                                "created_at",
                                old));
        Object id = call.get("id");
        var asset =
                store.insert(
                        "recordingasset",
                        Values.map("tenant_id", 2, "call_session_id", id, "retention_until", old));
        retention.purge(1000);
        assertThat(store.require("recordingasset", 2, asset.get("id")).get("state"))
                .isEqualTo("deletion_pending");
        var task =
                store.one(
                        "taskoutbox",
                        "findByIdempotencyKey",
                        Values.map("tenantId", 2, "key", "recording-delete:" + asset.get("id")));
        assertThat(task).isNotNull();
        retention.purge(1000);
        assertThat(
                        store.one(
                                        "taskoutbox",
                                        "findByIdempotencyKey",
                                        Values.map(
                                                "tenantId",
                                                2,
                                                "key",
                                                "recording-delete:" + asset.get("id")))
                                .get("id"))
                .isEqualTo(task.get("id"));
        String key = "archive:" + id;
        var archived =
                store.insert(
                        "taskoutbox",
                        Values.map(
                                "tenant_id",
                                2,
                                "task_type",
                                "call_analysis",
                                "aggregate_id",
                                id,
                                "idempotency_key",
                                key,
                                "state",
                                "completed",
                                "updated_at",
                                old,
                                "created_at",
                                old));
        assertThat(retention.archive(1000)).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> store.require("taskoutbox", 2, archived.get("id")))
                .isInstanceOf(ApiError.class);
        var replay = outbox.enqueue(2, "call_analysis", id, key, Map.of());
        assertThat(replay.get("id")).isEqualTo(archived.get("id"));
        assertThat(
                        store.one(
                                "taskoutbox",
                                "findByIdempotencyKey",
                                Values.map("tenantId", 2, "key", key)))
                .isNull();
    }

    @Test
    void outboxUsesUniqueKeyAndSupersedesOnlyEarlierFinalAiTurns() {
        String aggregate = UUID.randomUUID().toString();
        String key = "ai-test:" + aggregate;
        var first =
                outbox.enqueue(
                        1, "ai_turn", aggregate, key, Values.map("attempt", 1, "turn_sequence", 1));
        assertThat(
                        outbox.enqueue(
                                        1,
                                        "ai_turn",
                                        aggregate,
                                        key,
                                        Values.map("attempt", 1, "turn_sequence", 1))
                                .get("id"))
                .isEqualTo(first.get("id"));
        var other =
                outbox.enqueue(
                        1,
                        "ai_turn",
                        aggregate,
                        key + ":other",
                        Values.map("attempt", 2, "turn_sequence", 1));
        outbox.enqueue(
                1,
                "ai_turn",
                aggregate,
                key + ":second",
                Values.map("attempt", 1, "turn_sequence", 2));
        assertThat(store.require("taskoutbox", 1, first.get("id")).get("state"))
                .isEqualTo("completed");
        assertThat(store.require("taskoutbox", 1, other.get("id")).get("state"))
                .isEqualTo("pending");
    }

    @Autowired
    com.duozhi.outbound.ai.service.ConversationPolicy conversations;
    @Autowired
    com.duozhi.outbound.workspace.controller.ContactBatches importBatches;

    JsonNode workspace(HttpMethod method, String path, Object body) {
        return ok(request(method, "/api/v1/workspace" + path, body));
    }

    Map<String, Object> object(JsonNode node) {
        return json.convertValue(
                node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    @Test
    void workspacePublicationsAreImmutableTaskBoundAndRejectStaleWrites() {
        var lib = workspace(HttpMethod.POST, "/libraries", Map.of("name", "知识测试"));
        String library = lib.path("object_id").asText();
        workspace(
                HttpMethod.PUT,
                "/libraries/" + library + "/items/faq-a",
                Map.of(
                        "expected_revision",
                        0,
                        "draft",
                        Map.of(
                                "name",
                                "办理条件",
                                "content",
                                "可通过预约进行咨询",
                                "keywords",
                                "预约 咨询",
                                "source",
                                "服务条款")));
        lib = workspace(HttpMethod.GET, "/libraries/" + library, null);
        var publication =
                workspace(
                        HttpMethod.POST,
                        "/libraries/" + library + "/publish",
                        Map.of("expected_revision", lib.path("revision").asInt()));
        assertThat(publication.path("published_version").asInt()).isEqualTo(1);
        assertThat(
                        request(
                                        HttpMethod.POST,
                                        "/api/v1/workspace/libraries/" + library + "/publish",
                                        Map.of("expected_revision", lib.path("revision").asInt()))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        var script = workspace(HttpMethod.POST, "/scripts", Map.of("name", "版本快照话术"));
        String id = script.path("id").asText();
        var draft = object(script.path("draft"));
        draft.put("goal", "了解咨询意向");
        draft.put("knowledge_library_ids", List.of(library));
        script =
                workspace(
                        HttpMethod.PUT,
                        "/scripts/" + id + "/draft",
                        Map.of(
                                "draft",
                                draft,
                                "expected_revision",
                                script.path("revision").asInt()));
        script =
                workspace(
                        HttpMethod.POST,
                        "/scripts/" + id + "/publish",
                        Map.of("expected_revision", script.path("revision").asInt()));
        var previewDraft = new LinkedHashMap<>(draft);
        previewDraft.put("knowledge_library_ids", List.of());
        var preview =
                workspace(
                        HttpMethod.POST,
                        "/scripts/" + id + "/probe",
                        Map.of("query", "预约咨询", "draft", previewDraft));
        assertThat(preview.path("knowledge")).isEmpty();
        var savedProbe =
                workspace(HttpMethod.POST, "/scripts/" + id + "/probe", Map.of("query", "预约咨询"));
        assertThat(savedProbe.path("knowledge")).hasSize(1);
        var afterProbe = workspace(HttpMethod.GET, "/scripts/" + id, null);
        assertThat(afterProbe.path("revision")).isEqualTo(script.path("revision"));
        assertThat(afterProbe.path("state").asText()).isEqualTo("published");
        assertThat(afterProbe.path("draft")).isEqualTo(script.path("draft"));
        long contact = create(phone()).path("id").asLong();
        var task =
                workspace(
                        HttpMethod.POST,
                        "/tasks",
                        Map.of(
                                "name",
                                "快照任务",
                                "mode",
                                "ai_only",
                                "script_template_id",
                                Long.parseLong(id),
                                "contact_ids",
                                List.of(contact)));
        assertThat(task.path("snapshot").path("script_version").asInt()).isEqualTo(1);
        assertThat(task.path("snapshot").path("knowledge").get(0).path("content").asText())
                .contains("预约");
        var frozen = task.path("snapshot");
        draft.put("goal", "改成另外一个目标");
        script =
                workspace(
                        HttpMethod.PUT,
                        "/scripts/" + id + "/draft",
                        Map.of(
                                "draft",
                                draft,
                                "expected_revision",
                                script.path("revision").asInt()));
        workspace(
                HttpMethod.POST,
                "/scripts/" + id + "/publish",
                Map.of("expected_revision", script.path("revision").asInt()));
        assertThat(
                        workspace(HttpMethod.GET, "/tasks/" + task.path("id").asLong(), null)
                                .path("snapshot"))
                .isEqualTo(frozen);
        var initial =
                conversations.initialStatePolicy(
                        Values.map("tenant_id", 1, "campaign_id", task.path("id").asLong()));
        assertThat(initial.get("json").toString())
                .contains("了解咨询意向", "可通过预约进行咨询")
                .doesNotContain("改成另外一个目标");
        var history = workspace(HttpMethod.GET, "/scripts/" + id + "/history", null);
        assertThat(history).hasSize(2);
        var current = workspace(HttpMethod.GET, "/scripts/" + id, null);
        var restored =
                workspace(
                        HttpMethod.POST,
                        "/scripts/" + id + "/restore",
                        Map.of(
                                "version",
                                1,
                                "expected_revision",
                                current.path("revision").asInt()));
        assertThat(restored.path("draft").path("goal").asText()).isEqualTo("了解咨询意向");
        var foreign =
                store.insert(
                        "scripttemplate",
                        Values.map("tenant_id", 2, "name", "foreign", "content", "foreign"));
        assertThat(
                        request(
                                        HttpMethod.GET,
                                        "/api/v1/workspace/scripts/" + foreign.get("id"),
                                        null)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        user.setRole("agent");
        users.updateById(user, 1);
        token = crypto.token(UserRecords.map(user));
        assertThat(
                        request(HttpMethod.GET, "/api/v1/workspace/libraries", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    @Test
    void importedBatchProtectsBlankConsentAndDncAndCanBeReusedWithoutBrowserIds() {
        String number = phone();
        var contact = create(number);
        long id = contact.path("id").asLong();
        ok(
                request(
                        HttpMethod.PATCH,
                        "/api/v1/contacts/" + id,
                        Map.of("dnc", true, "dnc_reason", "客户拒绝", "tags", "保留标签")));
        var result =
                ok(
                        upload(
                                ("手机号,姓名,同意状态,禁止呼叫,标签\n" + number + ",更新姓名,,,\n")
                                        .getBytes(StandardCharsets.UTF_8),
                                UUID.randomUUID().toString(),
                                true));
        assertThat(result.path("batch_id").asText()).isNotBlank();
        assertThat(result.path("selected_count").asInt()).isEqualTo(1);
        assertThat(result.has("contact_ids")).isFalse();
        var after = ok(request(HttpMethod.GET, "/api/v1/contacts/" + id, null));
        assertThat(after.path("dnc").asBoolean()).isTrue();
        assertThat(after.path("consent_state").asText()).isEqualTo("consented");
        assertThat(after.path("tags").asText()).isEqualTo("保留标签");
        var script = workspace(HttpMethod.POST, "/scripts", Map.of("name", "批次话术", "goal", "业务介绍"));
        String scriptId = script.path("id").asText();
        script =
                workspace(
                        HttpMethod.POST,
                        "/scripts/" + scriptId + "/publish",
                        Map.of("expected_revision", script.path("revision").asInt()));
        var task =
                workspace(
                        HttpMethod.POST,
                        "/tasks",
                        Map.of(
                                "name",
                                "批次任务",
                                "mode",
                                "ai_only",
                                "script_template_id",
                                script.path("id").asLong(),
                                "batch_ids",
                                List.of(result.path("batch_id").asText())));
        assertThat(task.path("contact_count").asInt()).isEqualTo(1);
        assertThat(task.path("status").asText()).isEqualTo("draft");
        assertThat(
                        http.exchange(
                                        "/api/v1/contacts/import/template",
                                        HttpMethod.GET,
                                        new HttpEntity<>(auth()),
                                        byte[].class)
                                .getBody())
                .startsWith((byte) 0x50, (byte) 0x4b);
    }

    @Test
    void asynchronousMappedImportCompletesReplaysAndRejectsDifferentPayload() throws Exception {
        String key = UUID.randomUUID().toString(), number = phone();
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add(
                "file",
                new ByteArrayResource(
                        ("mobile,nick\n" + number + ",异步客户\ninvalid,错误\n")
                                .getBytes(StandardCharsets.UTF_8)) {
                    public String getFilename() {
                        return "mapped.csv";
                    }
                });
        var headers = auth();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", key);
        String path = "/api/v1/workspace/contact-batches?mapping={mapping}";
        String mapping = "{\"mobile\":\"phone\",\"nick\":\"name\"}";
        var submitted =
                ok(
                        http.exchange(
                                path,
                                HttpMethod.POST,
                                new HttpEntity<>(parts, headers),
                                JsonNode.class,
                                mapping));
        String id = submitted.path("id").asText();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                workspace(
                                                                HttpMethod.GET,
                                                                "/contact-batches/uploads/" + id,
                                                                null)
                                                        .path("state")
                                                        .asText())
                                        .isEqualTo("completed"));
        var complete = workspace(HttpMethod.GET, "/contact-batches/uploads/" + id, null);
        assertThat(complete.path("result").path("created").asInt()).isEqualTo(1);
        assertThat(complete.path("result").path("failed").asInt()).isEqualTo(1);
        var replay =
                ok(
                        http.exchange(
                                path,
                                HttpMethod.POST,
                                new HttpEntity<>(parts, headers),
                                JsonNode.class,
                                mapping));
        assertThat(replay.path("id").asText()).isEqualTo(id);
        assertThat(
                        http.exchange(
                                        path,
                                        HttpMethod.POST,
                                        new HttpEntity<>(parts, headers),
                                        JsonNode.class,
                                        "{\"mobile\":\"phone\"}")
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        var errorFile =
                http.exchange(
                        "/api/v1/workspace/contact-batches/"
                                + complete.path("result").path("batch_id").asText()
                                + "/errors",
                        HttpMethod.GET,
                        new HttpEntity<>(auth()),
                        String.class);
        assertThat(errorFile.getBody()).contains("row 3", "invalid phone");
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = wb.createSheet("外呼名单");
            sheet.createRow(0).createCell(0).setCellValue("手机号");
            sheet.createRow(1).createCell(0).setCellValue(phone());
            var out = new java.io.ByteArrayOutputStream();
            wb.write(out);
            parts.set(
                    "file",
                    new ByteArrayResource(out.toByteArray()) {
                        public String getFilename() {
                            return "contacts.xlsx";
                        }
                    });
            headers.set("Idempotency-Key", UUID.randomUUID().toString());
            var xlsx =
                    ok(
                            http.exchange(
                                    "/api/v1/contacts/import?upsert=false",
                                    HttpMethod.POST,
                                    new HttpEntity<>(parts, headers),
                                    JsonNode.class));
            assertThat(xlsx.path("created").asInt()).isEqualTo(1);
        }
    }

    @Autowired
    com.duozhi.outbound.workspace.service.WorkspaceDocuments workspaceDocuments;

    @Test
    void hundredRowChunksPreserveFirstDuplicateAndReplayAfterInterruptedFinalization()
            throws Exception {
        String first = phone();
        long firstId = create(first).path("id").asLong();
        var csv = new StringBuilder("手机号,姓名,同意状态,禁止呼叫\n");
        csv.append(first).append(",首条有效,,\n");
        for (int n = 1; n < 250; n++)
            csv.append(phone()).append(",客户").append(n).append(",已同意,否\n");
        csv.append(first).append(",不得覆盖,已撤回,是\n");
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add(
                "file",
                new ByteArrayResource(bytes) {
                    public String getFilename() {
                        return "chunked.csv";
                    }
                });
        var headers = auth();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var submitted =
                ok(
                        http.exchange(
                                "/api/v1/workspace/contact-batches?upsert=true",
                                HttpMethod.POST,
                                new HttpEntity<>(parts, headers),
                                JsonNode.class));
        String id = submitted.path("id").asText();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(
                                                workspace(
                                                                HttpMethod.GET,
                                                                "/contact-batches/uploads/" + id,
                                                                null)
                                                        .path("state")
                                                        .asText())
                                        .isEqualTo("completed"));
        var initial =
                workspace(HttpMethod.GET, "/contact-batches/uploads/" + id, null).path("result");
        assertThat(initial.path("created").asInt()).isEqualTo(249);
        assertThat(initial.path("updated").asInt()).isEqualTo(1);
        assertThat(initial.path("skipped").asInt()).isEqualTo(1);
        assertThat(initial.path("selected_count").asInt()).isEqualTo(250);
        var contact = ok(request(HttpMethod.GET, "/api/v1/contacts/" + firstId, null));
        assertThat(contact.path("name").asText()).isEqualTo("首条有效");
        assertThat(contact.path("dnc").asBoolean()).isFalse();
        assertThat(
                        workspaceDocuments.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM platform_contact_batch WHERE tenant_id=1 AND request_key LIKE ?",
                                Integer.class,
                                "workspace-chunk:" + id + ":%"))
                .isEqualTo(3);
        long before =
                workspaceDocuments.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM contact WHERE tenant_id=1", Long.class);
        // Model a crash after child commits but before publishing the parent batch/result.
        String batch = initial.path("batch_id").asText();
        workspaceDocuments.tx.run(
                () -> {
                    workspaceDocuments.jdbc.update(
                            "DELETE FROM platform_contact_batch_member WHERE tenant_id=1 AND batch_id=?",
                            batch);
                    workspaceDocuments.jdbc.update(
                            "DELETE FROM platform_contact_batch_error WHERE tenant_id=1 AND batch_id=?",
                            batch);
                    workspaceDocuments.jdbc.update(
                            "DELETE FROM platform_contact_batch WHERE tenant_id=1 AND id=?", batch);
                    workspaceDocuments.jdbc.update(
                            "UPDATE platform_contact_upload SET state='queued',file_bytes=? WHERE tenant_id=1 AND id=?",
                            bytes,
                            id);
                    return null;
                });
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(
                                                workspace(
                                                                HttpMethod.GET,
                                                                "/contact-batches/uploads/" + id,
                                                                null)
                                                        .path("state")
                                                        .asText())
                                        .isEqualTo("completed"));
        var resumed =
                workspace(HttpMethod.GET, "/contact-batches/uploads/" + id, null).path("result");
        assertThat(resumed.path("created").asInt()).isEqualTo(249);
        assertThat(resumed.path("updated").asInt()).isEqualTo(1);
        assertThat(resumed.path("selected_count").asInt()).isEqualTo(250);
        assertThat(
                        workspaceDocuments.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM contact WHERE tenant_id=1", Long.class))
                .isEqualTo(before);
    }

    @Test
    void failedUploadCanRetryAndDiscardWithoutKeepingPayload() {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add(
                "file",
                new ByteArrayResource(new byte[] {(byte) 0xff}) {
                    public String getFilename() {
                        return "invalid.csv";
                    }
                });
        var headers = auth();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var job =
                ok(
                        http.exchange(
                                "/api/v1/workspace/contact-batches",
                                HttpMethod.POST,
                                new HttpEntity<>(parts, headers),
                                JsonNode.class));
        String id = job.path("id").asText();
        String path = "/contact-batches/uploads/" + id;
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                workspace(HttpMethod.GET, path, null)
                                                        .path("state")
                                                        .asText())
                                        .isEqualTo("failed"));
        assertThat(workspace(HttpMethod.GET, path, null).path("retryable").asBoolean()).isTrue();
        workspace(HttpMethod.POST, path + "/retry", null);
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                workspace(HttpMethod.GET, path, null)
                                                        .path("state")
                                                        .asText())
                                        .isEqualTo("failed"));
        var removed = workspace(HttpMethod.DELETE, path, null);
        assertThat(removed.path("state").asText()).isEqualTo("cancelled");
        assertThat(removed.path("retryable").asBoolean()).isFalse();
        assertThat(
                        request(HttpMethod.POST, "/api/v1/workspace" + path + "/retry", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
    }

    @Test
    void taskOverridesAreFrozenAndCopiedScriptRemainsDraft() {
        var script = workspace(HttpMethod.POST, "/scripts", Map.of("name", "排期话术", "goal", "业务介绍"));
        String id = script.path("id").asText();
        script =
                workspace(
                        HttpMethod.POST,
                        "/scripts/" + id + "/publish",
                        Map.of("expected_revision", script.path("revision").asInt()));
        var copy = workspace(HttpMethod.POST, "/scripts/" + id + "/copy", null);
        assertThat(copy.path("state").asText()).isEqualTo("draft");
        assertThat(copy.path("published_version").asInt()).isZero();
        assertThat(copy.path("draft").path("goal").asText()).isEqualTo("业务介绍");
        long contact = create(phone()).path("id").asLong();
        String
                start =
                        java.time.ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                                .plusDays(1)
                                .withHour(12)
                                .withMinute(0)
                                .withSecond(0)
                                .toInstant()
                                .toString(),
                end = Instant.parse(start).plusSeconds(3600).toString();
        var data =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "name",
                                "排期任务",
                                "mode",
                                "ai_only",
                                "script_template_id",
                                Long.parseLong(id),
                                "contact_ids",
                                List.of(contact),
                                "start_at",
                                start,
                                "end_at",
                                end,
                                "execution_policy",
                                Map.of("start_hour", 0, "end_hour", 24, "timezone", "UTC")));
        var task = workspace(HttpMethod.POST, "/tasks", data);
        var snapshot = task.path("snapshot");
        assertThat(snapshot.path("start_at").asText()).isEqualTo(start);
        assertThat(snapshot.path("policy").path("start_hour").asInt()).isZero();
        assertThat(snapshot.path("policy").path("timezone").asText()).isEqualTo("UTC");
        var runtime = conversations.current(1, task.path("id").asLong());
        var policy = object(json.valueToTree(runtime.get("policy")));
        assertThat(policy).containsEntry("_task_start_at", start);
        assertThat(com.duozhi.outbound.workspace.service.TaskSchedule.isOpen(policy, Instant.now()))
                .isFalse();
        assertThat(
                        com.duozhi.outbound.workspace.service.TaskSchedule.isOpen(
                                policy, Instant.parse(start)))
                .isTrue();
        assertThat(
                        com.duozhi.outbound.workspace.service.TaskSchedule.isOpen(
                                policy, Instant.parse(end)))
                .isFalse();
        var prepared =
                ok(
                        request(
                                HttpMethod.POST,
                                "/api/v1/campaigns/"
                                        + task.path("id").asLong()
                                        + "/start?auto_dial=false",
                                null));
        assertThat(prepared.path("created").asInt()).isEqualTo(1);
        data.put("mode", "ai_handoff");
        assertThat(
                        request(HttpMethod.POST, "/api/v1/workspace/tasks", data)
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
    }
}
