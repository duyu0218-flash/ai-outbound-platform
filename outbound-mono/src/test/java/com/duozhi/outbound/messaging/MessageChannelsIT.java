package com.duozhi.outbound.messaging;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.auth.dao.TenantMapper;
import com.duozhi.outbound.auth.domain.Tenant;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.dao.MessageStore;
import com.duozhi.outbound.messaging.service.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MessageChannelsIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("ENV", () -> "internal-test");
        p.add("JWT_SECRET", () -> "message-channel-tests-only-synthetic-secret-12345678");
        p.add("MESSAGING_MASTER_KEY", () -> Base64.getEncoder().encodeToString(new byte[32]));
        p.add("MESSAGING_ENABLED", () -> "true");
        p.add("MESSAGING_TRANSPORT_ENABLED", () -> "true");
        p.add("MESSAGING_WORKERS_ENABLED", () -> "false");
        p.add("JAVA_WORKERS_ENABLED", () -> "false");
        p.add("JAVA_DISPATCH_ENABLED", () -> "false");
        p.add("spring.ai.model.chat", () -> "none");
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.flyway.url", MYSQL::getJdbcUrl);
        p.add("spring.flyway.user", () -> "root");
        p.add("spring.flyway.password", MYSQL::getPassword);
        p.add("spring.data.redis.host", () -> "127.0.0.1");
        p.add("spring.data.redis.port", () -> 9);
        p.add("REDIS_URL", () -> "");
        p.add("RATE_LIMIT_RPS", () -> 1000);
        p.add("RATE_LIMIT_BURST", () -> 2000);
    }

    @Autowired
    Messaging messages;
    @Autowired
    MessageChannels channels;
    @Autowired
    MessageDelivery delivery;
    @Autowired
    MessageAssets assets;
    @Autowired
    MessageReconciliation bills;
    @Autowired
    MessageStore db;
    @Autowired
    Store store;
    @Autowired
    TenantMapper tenants;
    @Autowired
    com.duozhi.outbound.auth.dao.SysUserMapper users;
    @Autowired
    com.duozhi.outbound.workspace.service.ScriptMessageDefaults defaults;
    @LocalServerPort
    int port;
    static final AtomicLong TENANTS = new AtomicLong(1200);
    static final String CALLBACK = "local-callback-test-key-12345678901234567890";
    long tenant;
    String phone, contact, channel, sender, template, product;
    HttpServer http;
    ExecutorService serverThreads;
    HttpClient client;
    AtomicInteger submits, queries;
    AtomicReference<Map<String, Object>> received;
    String mode;
    Messaging.Principal console;

    @BeforeEach
    void setup() throws Exception {
        tenant = TENANTS.incrementAndGet();
        var t = new Tenant();
        t.setId((int) tenant);
        t.setCode("channel-" + tenant);
        t.setName("通道验收");
        tenants.insert(t);
        console = new Messaging.Principal(tenant, "console");
        phone = "+8613800" + String.format("%06d", tenant);
        contact =
                store.insert(
                                "contact",
                                Values.map(
                                        "tenant_id",
                                        tenant,
                                        "phone",
                                        phone,
                                        "name",
                                        "通道测试客户",
                                        "consent_state",
                                        "consented",
                                        "consented_by",
                                        "local-test"))
                        .get("id")
                        .toString();
        messages.credit(tenant, "local-credit", 1000000);
        submits = new AtomicInteger();
        queries = new AtomicInteger();
        received = new AtomicReference<>();
        mode = "ACCEPTED";
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newVirtualThreadPerTaskExecutor();
        http.setExecutor(serverThreads);
        http.createContext(
                "/bridge",
                exchange -> {
                    try {
                        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                                .isEqualTo("Bearer test-api-key");
                        var request = Support.object(exchange.getRequestBody().readAllBytes());
                        String operation = request.get("operation").toString();
                        Map<String, Object> response;
                        if (operation.equals("probe"))
                            response =
                                    Map.of(
                                            "version",
                                            "1",
                                            "status",
                                            "OK",
                                            "forms",
                                            List.of("sms", "rcs", "yuexin", "oem_ip"));
                        else {
                            String id = request.get("message_id").toString();
                            String provider = "provider-" + id;
                            String state = mode;
                            if (operation.equals("submit")) {
                                submits.incrementAndGet();
                                received.set(request);
                                assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key"))
                                        .isEqualTo("submit:" + id);
                                if (mode.equals("EARLY")) {
                                    var result =
                                            callback(
                                                    Map.of(
                                                            "event_id",
                                                            "early-" + id,
                                                            "kind",
                                                            "delivery",
                                                            "provider_id",
                                                            provider,
                                                            "state",
                                                            "DELIVERED"),
                                                    CALLBACK);
                                    assertThat(result.statusCode()).isEqualTo(200);
                                    state = "ACCEPTED";
                                }
                                if (mode.equals("LOST")) {
                                    exchange.close();
                                    return;
                                }
                            } else {
                                queries.incrementAndGet();
                                state = "DELIVERED";
                            }
                            response =
                                    Map.of(
                                            "version",
                                            "1",
                                            "message_id",
                                            id,
                                            "provider_id",
                                            provider,
                                            "status",
                                            state);
                        }
                        byte[] bytes = Support.json(response);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        exchange.close();
                    }
                });
        http.start();
        client = HttpClient.newHttpClient();
        channel =
                channels.create(
                                tenant,
                                Map.of(
                                        "name",
                                        "本地 HTTP",
                                        "protocol",
                                        "http",
                                        "environment",
                                        "test",
                                        "forms",
                                        List.of("sms", "rcs", "yuexin", "oem_ip"),
                                        "config",
                                        Map.of(
                                                "endpoint",
                                                "http://127.0.0.1:"
                                                        + http.getAddress().getPort()
                                                        + "/bridge",
                                                "query_supported",
                                                true,
                                                "tps",
                                                100,
                                                "supports",
                                                List.of(
                                                        "delivery",
                                                        "display",
                                                        "read",
                                                        "click",
                                                        "reply",
                                                        "unsubscribe")),
                                        "credentials",
                                        Map.of(
                                                "api_key",
                                                "test-api-key",
                                                "callback_secret",
                                                CALLBACK)))
                        .get("id")
                        .toString();
        delivery.probe(tenant, channel);
        channels.state(tenant, channel, "ACTIVE");
        sender = resource("sender", Map.of("brand", "测试品牌"));
        bind(sender, "provider-sender");
        select("sms", Map.of());
    }

    @AfterEach
    void cleanup() {
        client.close();
        http.stop(0);
        serverThreads.close();
        db.jdbc()
                .update(
                        "UPDATE msg_message SET state='CANCELLED' WHERE tenant_id=? AND state='QUEUED'",
                        tenant);
        db.jdbc().update("UPDATE msg_delivery SET next_query_at=NULL WHERE tenant_id=?", tenant);
    }

    String resource(String kind, Map<String, Object> payload) {
        var r =
                Support.map(
                        messages.createResource(
                                tenant, kind, Map.of("name", "本地验收 " + kind, "payload", payload)));
        String id = r.get("id").toString();
        messages.resourceState(tenant, id, "APPROVED");
        return id;
    }

    void bind(String id, String ref) {
        channels.bind(
                tenant,
                channel,
                Map.of(
                        "resource_id",
                        id,
                        "provider_ref",
                        ref,
                        "state",
                        "APPROVED",
                        "evidence",
                        "local-test-fixture"));
    }

    void select(String form, Map<String, Object> rich) {
        product =
                resource(
                        "product",
                        Map.of(
                                "form",
                                form,
                                "adapter",
                                "channel",
                                "channel_id",
                                channel,
                                "unit_price",
                                1000,
                                "daily_recipient_limit",
                                100));
        template = resource("template", Map.of("form", form, "body", "您好，{{name}}", "rich", rich));
        bind(template, "provider-template-" + form);
    }

    Map<String, Object> body(String form) {
        return new LinkedHashMap<>(
                Map.of(
                        "service_product_id",
                        product,
                        "sender_identity_id",
                        sender,
                        "template_version_id",
                        template,
                        "recipient",
                        Map.of("type", "phone", "value", phone),
                        "consent_reference",
                        contact,
                        "variables",
                        Map.of("name", "李先生"),
                        "purpose",
                        "marketing",
                        "scheduled_at",
                        Instant.now().minusSeconds(2).toString(),
                        "channel_policy",
                        Map.of(
                                "mode",
                                "fixed",
                                "allowed_forms",
                                List.of(form),
                                "allow_fallback",
                                false)));
    }

    String send(String form) {
        String id =
                messages.create(console, UUID.randomUUID().toString(), body(form))
                        .get("message_id")
                        .toString();
        assertThat(messages.processOne()).isTrue();
        return id;
    }

    HttpResponse<String> callback(Map<String, Object> event, String secret) throws Exception {
        byte[] bytes = Support.json(event);
        String stamp = "" + Instant.now().getEpochSecond();
        String signature =
                Support.hmac(
                        secret,
                        (channel + "\n" + stamp + "\n" + Support.hex(Support.sha(bytes)) + "\n")
                                .getBytes(StandardCharsets.UTF_8));
        return client.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/webhooks/messaging/"
                                                + channel))
                        .header("X-Message-Timestamp", stamp)
                        .header("X-Message-Signature", signature)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    Map<String, Object> event(String id, String key, String state) {
        return Map.of("event_id", key, "kind", "delivery", "message_id", id, "state", state);
    }

    @Test
    void httpSubmitAuthenticatedReceiptIdempotencyAndBilling() throws Exception {
        String id = send("sms");
        assertThat(messages.message(console, id))
                .containsEntry("status", "ACCEPTED")
                .containsEntry("billing_state", "HELD")
                .containsEntry("simulated", false);
        assertThat(submits.get()).isEqualTo(1);
        assertThat(received.get())
                .containsEntry("to", phone)
                .containsEntry("sender_ref", "provider-sender");
        assertThat(Support.map(received.get().get("content")).get("text"))
                .isEqualTo("【测试品牌】您好，李先生");
        assertThat(callback(event(id, "receipt-1", "DELIVERED"), "wrong").statusCode())
                .isEqualTo(401);
        assertThat(messages.message(console, id).get("status")).isEqualTo("ACCEPTED");
        assertThat(callback(event(id, "receipt-1", "DELIVERED"), CALLBACK).statusCode())
                .isEqualTo(200);
        assertThat(callback(event(id, "receipt-1", "DELIVERED"), CALLBACK).statusCode())
                .isEqualTo(200);
        assertThat(callback(event(id, "receipt-1", "FAILED"), CALLBACK).statusCode())
                .isEqualTo(409);
        assertThat(messages.message(console, id))
                .containsEntry("status", "DELIVERED")
                .containsEntry("settled_amount", 1000L);
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT COUNT(*) FROM msg_journal WHERE message_id=? AND kind='SETTLE'",
                                        Long.class,
                                        id))
                .isEqualTo(1);
        assertThat(
                        db.one(
                                        "SELECT recipient_cipher,content_cipher FROM msg_message WHERE id=?",
                                        id)
                                .toString())
                .doesNotContain(phone, "李先生");
        assertThat(channels.get(tenant, channel).toString())
                .doesNotContain(CALLBACK, "test-api-key", "credential_cipher");
        assertThatThrownBy(() -> messages.receipt(tenant, id, "fake-admin", "DELIVERED"))
                .isInstanceOf(ApiError.class);
    }

    @Test
    void earlyReceiptIsStoredUntilSubmitResponseArrives() throws Exception {
        mode = "EARLY";
        String id = send("sms");
        assertThat(messages.message(console, id))
                .containsEntry("status", "DELIVERED")
                .containsEntry("billing_state", "SETTLED");
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT applied FROM msg_provider_event WHERE channel_id=?",
                                        Boolean.class,
                                        channel))
                .isTrue();
    }

    @Test
    void lostResponseIsQueriedWithoutResendingOrReleasingFunds() {
        mode = "LOST";
        String id = send("sms");
        assertThat(messages.message(console, id))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("billing_state", "HELD");
        assertThat(messages.processOne()).isFalse();
        delivery.submit(tenant, id);
        assertThat(submits.get()).isEqualTo(1);
        delivery.scheduleQuery(tenant, id);
        assertThat(delivery.queryOne()).isTrue();
        assertThat(queries.get()).isEqualTo(1);
        assertThat(submits.get()).isEqualTo(1);
        assertThat(messages.message(console, id).get("status")).isEqualTo("DELIVERED");
    }

    @Test
    void allRichFormsPreserveCardsAssetsAndProviderInteraction() throws Exception {
        byte[] png =
                Base64.getDecoder()
                        .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jV5sAAAAASUVORK5CYII=");
        String asset =
                Support.map(
                                assets.upload(
                                        tenant, "image.png", png, Instant.now().plusSeconds(3600)))
                        .get("id")
                        .toString();
        bind(asset, "asset-at-provider");
        for (String form : List.of("rcs", "yuexin", "oem_ip")) {
            select(
                    form,
                    Map.of(
                            "title",
                            "通知",
                            "asset_id",
                            asset,
                            "cards",
                            List.of(
                                    Map.of(
                                            "title",
                                            "预约",
                                            "body",
                                            "{{name}}，欢迎",
                                            "actions",
                                            List.of(
                                                    Map.of(
                                                            "code",
                                                            "book",
                                                            "label",
                                                            "详情",
                                                            "type",
                                                            "url",
                                                            "value",
                                                            "https://example.com/booking"))))));
            String id = send(form);
            assertThat(received.get()).containsEntry("form", form);
            assertThat(received.get().get("content").toString()).contains("李先生，欢迎");
            assertThat(Support.map(received.get().get("asset_refs")))
                    .containsEntry(asset, "asset-at-provider");
            assertThat(
                            callback(
                                            Map.of(
                                                    "event_id",
                                                    "read-" + form,
                                                    "kind",
                                                    "read",
                                                    "message_id",
                                                    id),
                                            CALLBACK)
                                    .statusCode())
                    .isEqualTo(200);
            assertThat(callback(event(id, "deliver-" + form, "DELIVERED"), CALLBACK).statusCode())
                    .isEqualTo(200);
        }
        String id = messages.messages(tenant).getFirst().get("id").toString();
        assertThat(
                        callback(
                                        Map.of(
                                                "event_id",
                                                "reply-1",
                                                "kind",
                                                "reply",
                                                "message_id",
                                                id,
                                                "text",
                                                "需要回电"),
                                        CALLBACK)
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT COUNT(*) FROM ops_history WHERE tenant_id=? AND action='message_interaction'",
                                        Long.class,
                                        tenant))
                .isEqualTo(1);
        assertThat(
                        callback(
                                        Map.of(
                                                "event_id",
                                                "unsubscribe-1",
                                                "kind",
                                                "unsubscribe",
                                                "recipient",
                                                phone),
                                        CALLBACK)
                                .statusCode())
                .isEqualTo(200);
        assertThatThrownBy(() -> messages.create(console, "after-unsubscribe", body("oem_ip")))
                .isInstanceOf(ApiError.class)
                .hasMessageContaining("退订");
        assets.disable(tenant, asset);
        assertThatThrownBy(() -> assets.get(tenant, asset, true)).isInstanceOf(ApiError.class);
        assertThatThrownBy(() -> assets.get(tenant + 100, asset, false))
                .isInstanceOf(ApiError.class);
    }

    @Test
    void grantsChannelApprovalIsolationAndRevocationBlockNetwork() {
        channels.bind(
                tenant,
                channel,
                Map.of(
                        "resource_id",
                        template,
                        "provider_ref",
                        "provider-template-sms",
                        "state",
                        "DISABLED"));
        assertThatThrownBy(() -> messages.create(console, "unapproved-resource", body("sms")))
                .isInstanceOf(ApiError.class);
        bind(template, "provider-template-sms");
        String id =
                messages.create(console, "queued-before-disable", body("sms"))
                        .get("message_id")
                        .toString();
        channels.state(tenant, channel, "DISABLED");
        messages.processOne();
        assertThat(messages.message(console, id).get("status")).isEqualTo("BLOCKED");
        assertThat(submits.get()).isZero();
        assertThatThrownBy(() -> channels.get(tenant + 1, channel)).isInstanceOf(ApiError.class);
        assertThatThrownBy(
                        () ->
                                channels.create(
                                        tenant,
                                        Map.of(
                                                "name",
                                                "wrong",
                                                "protocol",
                                                "cmpp",
                                                "forms",
                                                List.of("rcs"),
                                                "config",
                                                Map.of(),
                                                "credentials",
                                                Map.of())))
                .isInstanceOf(ApiError.class);
    }

    @Test
    void supplierCostsAndBillsAreIdempotentAndDifferencesRemainReviewable() throws Exception {
        String id = send("sms");
        assertThat(
                        callback(
                                        Map.of(
                                                "event_id",
                                                "cost-1",
                                                "kind",
                                                "cost",
                                                "message_id",
                                                id,
                                                "amount",
                                                300,
                                                "units",
                                                1),
                                        CALLBACK)
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        callback(
                                        Map.of(
                                                "event_id",
                                                "cost-1",
                                                "kind",
                                                "cost",
                                                "message_id",
                                                id,
                                                "amount",
                                                300,
                                                "units",
                                                1),
                                        CALLBACK)
                                .statusCode())
                .isEqualTo(200);
        assertThat(db.one("SELECT purchase_amount FROM msg_message WHERE id=?", id))
                .containsEntry("purchase_amount", 300L);
        var request =
                Map.<String, Object>of(
                        "channel_id",
                        channel,
                        "external_ref",
                        "local-bill",
                        "items",
                        List.of(
                                Map.of("provider_id", "provider-" + id, "amount", 300),
                                Map.of("provider_id", "missing-id", "amount", 100)));
        var first = Support.map(bills.create(tenant, request));
        assertThat(bills.create(tenant, request)).isEqualTo(first);
        assertThat(Support.map(messages.report(tenant)).get("messages").toString())
                .contains("purchase_cost_pending");
        assertThat(first.get("items").toString()).contains("MATCHED", "UNMATCHED");
        assertThatThrownBy(() -> bills.get(tenant + 1, first.get("id").toString()))
                .isInstanceOf(ApiError.class);
    }

    @Test
    void apiResourcesRotationLimitsAndEventsRemainApplicationScoped() throws Exception {
        var app =
                Support.map(
                        messages.createApplication(
                                tenant,
                                Map.of(
                                        "name",
                                        "业务系统",
                                        "grants",
                                        Map.of(
                                                "products",
                                                List.of(product),
                                                "senders",
                                                List.of(sender),
                                                "templates",
                                                List.of(template)))));
        String appId = app.get("application_id").toString();
        var principal = new Messaging.Principal(tenant, appId);
        assertThat(signed(app, "GET", "/openapi/v1/resources/product", null, "").statusCode())
                .isEqualTo(200);
        var accepted =
                signed(app, "POST", "/openapi/v1/messages", body("sms"), "external-send-001");
        assertThat(accepted.statusCode()).isEqualTo(202);
        String id =
                Support.object(accepted.body().getBytes(StandardCharsets.UTF_8))
                        .get("message_id")
                        .toString();
        messages.processOne();
        assertThat(callback(event(id, "api-receipt", "DELIVERED"), CALLBACK).statusCode())
                .isEqualTo(200);
        assertThat(signed(app, "GET", "/openapi/v1/events", null, "").body())
                .contains("message.delivered");
        assertThat(signed(app, "GET", "/openapi/v1/usage", null, "").body()).contains("sms");
        assertThat(signed(app, "GET", "/openapi/v1/ledger", null, "").body()).contains("SETTLE");
        assertThat(
                        callback(
                                        Map.of(
                                                "event_id",
                                                "api-reply",
                                                "kind",
                                                "reply",
                                                "message_id",
                                                id,
                                                "text",
                                                "请回电"),
                                        CALLBACK)
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        signed(app, "GET", "/openapi/v1/messages/" + id + "/interactions", null, "")
                                .body())
                .contains("请回电", "PROVIDER");
        var rotated =
                Support.map(
                        messages.rotateApplication(tenant, appId, Map.of("grace_seconds", 300)));
        assertThat(signed(app, "GET", "/openapi/v1/capabilities", null, "").statusCode())
                .isEqualTo(200);
        assertThat(signed(rotated, "GET", "/openapi/v1/capabilities", null, "").statusCode())
                .isEqualTo(200);
        db.jdbc()
                .update(
                        "UPDATE msg_application SET previous_secret_until=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id=?",
                        appId);
        assertThat(signed(app, "GET", "/openapi/v1/capabilities", null, "").statusCode())
                .isEqualTo(401);
        messages.applicationSettings(
                tenant,
                appId,
                Map.of("rate_per_minute", 100, "daily_limit", 1, "daily_budget", 100000));
        assertThatThrownBy(() -> messages.create(principal, "daily-limit-second", body("sms")))
                .isInstanceOf(ApiError.class)
                .hasMessageContaining("限额");
        var other =
                Support.map(
                        messages.createApplication(
                                tenant,
                                Map.of(
                                        "name",
                                        "其他系统",
                                        "grants",
                                        Map.of(
                                                "products",
                                                List.of(product),
                                                "senders",
                                                List.of(sender),
                                                "templates",
                                                List.of(template)))));
        assertThat(signed(other, "GET", "/openapi/v1/messages/" + id, null, "").statusCode())
                .isEqualTo(404);
        messages.revokeApplication(tenant, appId);
        assertThat(signed(rotated, "GET", "/openapi/v1/capabilities", null, "").statusCode())
                .isEqualTo(401);
    }

    HttpResponse<String> signed(
            Map<String, Object> app,
            String method,
            String path,
            Map<String, Object> body,
            String key)
            throws Exception {
        byte[] bytes = body == null ? new byte[0] : Support.json(body);
        String stamp = "" + Instant.now().getEpochSecond(),
                nonce = UUID.randomUUID().toString().replace("-", ""),
                hash = Support.hex(Support.sha(bytes));
        String canonical = String.join("\n", method, path, "", stamp, nonce, hash, key, "");
        String signature =
                Support.hmac(
                        app.get("secret").toString(), canonical.getBytes(StandardCharsets.UTF_8));
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header(
                                "Authorization",
                                "HMAC-SHA256 Credential="
                                        + app.get("application_id")
                                        + ",Signature="
                                        + signature)
                        .header("X-Timestamp", stamp)
                        .header("X-Nonce", nonce)
                        .header("X-Content-SHA256", hash)
                        .header("Idempotency-Key", key)
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void multipartDeliveryWaitsForAllSegmentsAndSettlesOnlyBillableParts() throws Exception {
        template = resource("template", Map.of("form", "sms", "body", "长短信".repeat(35)));
        bind(template, "long-template");
        var request = body("sms");
        request.put("variables", Map.of());
        String id =
                messages.create(console, "multipart-price", request).get("message_id").toString();
        messages.processOne();
        assertThat(messages.message(console, id))
                .containsEntry("billable_units", 2)
                .containsEntry("sales_amount", 2000L);
        // Native wire segmentation is covered by CmppMessageProtocolTest. Seed its two accepted
        // part facts here.
        db.jdbc().update("DELETE FROM msg_delivery_part WHERE message_id=?", id);
        db.jdbc().update("UPDATE msg_delivery SET part_count=2 WHERE message_id=?", id);
        for (int n = 1; n <= 2; n++)
            db.jdbc()
                    .update(
                            "INSERT INTO msg_delivery_part(message_id,channel_id,part_no,provider_id,state) VALUES(?,?,?,?,'ACCEPTED')",
                            id,
                            channel,
                            n,
                            "segment-" + n);
        assertThat(
                        callback(
                                        Map.of(
                                                "event_id",
                                                "part-1",
                                                "kind",
                                                "delivery",
                                                "provider_id",
                                                "segment-1",
                                                "state",
                                                "DELIVERED"),
                                        CALLBACK)
                                .statusCode())
                .isEqualTo(200);
        assertThat(messages.message(console, id))
                .containsEntry("status", "ACCEPTED")
                .containsEntry("billing_state", "HELD");
        assertThat(
                        callback(
                                        Map.of(
                                                "event_id",
                                                "part-2",
                                                "kind",
                                                "delivery",
                                                "provider_id",
                                                "segment-2",
                                                "state",
                                                "FAILED"),
                                        CALLBACK)
                                .statusCode())
                .isEqualTo(200);
        assertThat(messages.message(console, id))
                .containsEntry("status", "FAILED")
                .containsEntry("settled_amount", 1000L);
        messages.refund(tenant, id, "partial refund test");
        assertThat(
                        db.one(
                                "SELECT available,frozen,spent FROM msg_account WHERE tenant_id=?",
                                tenant))
                .containsEntry("available", 1000000L)
                .containsEntry("frozen", 0L)
                .containsEntry("spent", 0L);
    }

    @Test
    void acceptedBillingDoesNotPreventFinalReceiptAndLongMessagesAreNotStarvedAtOneTps()
            throws Exception {
        var cfg = new LinkedHashMap<>(Support.map(channels.get(tenant, channel).get("config")));
        cfg.put("billing_basis", "accepted");
        cfg.put("tps", 1);
        channel =
                channels.create(
                                tenant,
                                Map.of(
                                        "name",
                                        "受理计费",
                                        "protocol",
                                        "http",
                                        "forms",
                                        List.of("sms"),
                                        "config",
                                        cfg,
                                        "credentials",
                                        Map.of(
                                                "api_key",
                                                "test-api-key",
                                                "callback_secret",
                                                CALLBACK)))
                        .get("id")
                        .toString();
        delivery.probe(tenant, channel);
        channels.state(tenant, channel, "ACTIVE");
        bind(sender, "provider-sender");
        select("sms", Map.of());
        template = resource("template", Map.of("form", "sms", "body", "长短信".repeat(35)));
        bind(template, "long-template");
        var request = body("sms");
        request.put("variables", Map.of());
        String id =
                messages.create(console, "one-tps-long-message", request)
                        .get("message_id")
                        .toString();
        messages.processOne();
        assertThat(submits.get()).isEqualTo(1);
        assertThat(messages.message(console, id))
                .containsEntry("status", "ACCEPTED")
                .containsEntry("settled_amount", 2000L)
                .containsEntry("billing_state", "SETTLED");
        assertThat(callback(event(id, "accepted-then-failed", "FAILED"), CALLBACK).statusCode())
                .isEqualTo(200);
        assertThat(messages.message(console, id))
                .containsEntry("status", "FAILED")
                .containsEntry("settled_amount", 2000L)
                .containsEntry("billing_state", "SETTLED");
    }

    @Autowired
    com.duozhi.outbound.workspace.service.WorkspaceMessages workspace;

    @Test
    void richMarketingTaskAndCompletedCallUseTheSameRealChannel() {
        select("rcs", Map.of("title", "{{name}}的预约"));
        var user = new com.duozhi.outbound.auth.domain.SysUser();
        user.setTenantId((int) tenant);
        user.setUsername("local-admin-" + tenant);
        user.setFullName("验收管理员");
        user.setRole("admin");
        user.setPasswordHash("unused-test-password-hash");
        users.insert(user);
        var actor =
                new com.duozhi.outbound.auth.domain.Identity(
                        tenant,
                        Map.of("id", user.getId(), "username", user.getUsername(), "role", "admin"),
                        Set::of);
        String task = UUID.randomUUID().toString();
        var draft =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "name",
                                "富媒体营销任务",
                                "product_id",
                                product,
                                "sender_id",
                                sender,
                                "template_id",
                                template,
                                "contact_ids",
                                List.of(Long.parseLong(contact)),
                                "budget",
                                10000,
                                "expires_at",
                                Instant.now().plusSeconds(3600).toString()));
        var saved =
                Support.map(
                        workspace.save(
                                actor, task, Map.of("draft", draft, "expected_revision", 0)));
        assertThat(workspace.preflight(tenant, task))
                .containsEntry("ready", true)
                .containsEntry("estimated", 1000L);
        workspace.start(
                actor,
                task,
                Map.of(
                        "confirmed",
                        true,
                        "expected_revision",
                        saved.get("revision"),
                        "request_key",
                        "local-rich-task-start"));
        workspace.processNext();
        messages.processBatch();
        db.jdbc()
                .update(
                        "UPDATE msg_message SET scheduled_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE tenant_id=?",
                        tenant);
        messages.processOne();
        assertThat(received.get()).containsEntry("form", "rcs");
        assertThat(received.get().get("content").toString()).contains("通道测试客户的预约");
        assertThat(
                        defaults.validate(
                                tenant,
                                Map.of(
                                        "product_id",
                                        product,
                                        "template_id",
                                        template,
                                        "sender_id",
                                        sender,
                                        "condition",
                                        "completed",
                                        "budget",
                                        10000)))
                .containsEntry("template_id", template);
        resource(
                "call_rule",
                Map.of(
                        "call_campaign_id",
                        0,
                        "product_id",
                        product,
                        "sender_id",
                        sender,
                        "template_id",
                        template,
                        "variables",
                        Map.of("name", "外呼客户")));
        var call =
                store.insert(
                        "callsession",
                        Values.map(
                                "tenant_id",
                                tenant,
                                "contact_id",
                                Long.parseLong(contact),
                                "phone",
                                phone,
                                "mode",
                                "ai_with_sms",
                                "status",
                                "in_ai",
                                "attempts",
                                1,
                                "max_attempts",
                                1));
        messages.deferCall(tenant, call.get("id").toString(), 1);
        assertThat(messages.processCallIntent()).isFalse();
        store.update("callsession", tenant, call.get("id"), Values.map("status", "completed"));
        assertThat(messages.processCallIntent()).isTrue();
        db.jdbc()
                .update(
                        "UPDATE msg_message SET scheduled_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE tenant_id=?",
                        tenant);
        messages.processOne();
        assertThat(received.get().get("content").toString()).contains("外呼客户的预约");
        assertThat(submits.get()).isEqualTo(2);
    }

    @Test
    void maximumLengthMessageUsesTheApprovedSnapshotWithoutPreviewRerender() {
        sender = resource("sender", Map.of("brand", "测"));
        bind(sender, "short-sender");
        template = resource("template", Map.of("form", "sms", "body", "中".repeat(3997)));
        bind(template, "max-length-template");
        var input = body("sms");
        input.put("variables", Map.of());
        String id =
                messages.create(console, "max-length-snapshot", input).get("message_id").toString();
        messages.processOne();
        assertThat(messages.message(console, id))
                .containsEntry("status", "ACCEPTED")
                .containsEntry("billable_units", 60);
        assertThat(Support.map(received.get().get("content")).get("text").toString())
                .hasSize(4000)
                .startsWith("【测】");
    }
}
