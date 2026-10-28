package com.duozhi.outbound.messaging;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.auth.dao.*;
import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.dao.MessageStore;
import com.duozhi.outbound.messaging.service.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
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
class MessagingIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "messaging-integration-test-synthetic-secret-12345678");
        p.add("MESSAGING_MASTER_KEY", () -> Base64.getEncoder().encodeToString(new byte[32]));
        p.add("MESSAGING_ENABLED", () -> "true");
        p.add("MESSAGING_WORKERS_ENABLED", () -> "false");
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.flyway.url", MYSQL::getJdbcUrl);
        p.add("spring.flyway.user", () -> "root");
        p.add("spring.flyway.password", MYSQL::getPassword);
        p.add("spring.data.redis.host", () -> "127.0.0.1");
        p.add("spring.data.redis.port", () -> 9);
        p.add("REDIS_URL", () -> "");
        p.add("JAVA_WORKERS_ENABLED", () -> "false");
        p.add("JAVA_DISPATCH_ENABLED", () -> "false");
        p.add("spring.ai.model.chat", () -> "none");
        p.add("RATE_LIMIT_RPS", () -> 1000);
        p.add("RATE_LIMIT_BURST", () -> 2000);
    }

    @Autowired
    com.duozhi.outbound.common.service.Transactions transactions;
    @Autowired
    MessageSecrets secrets;
    @Autowired
    com.duozhi.outbound.common.config.Config config;
    @Autowired
    Messaging service;
    @Autowired
    MessageStore db;
    @Autowired
    Store store;
    @Autowired
    TenantMapper tenants;
    @Autowired
    SysUserMapper users;
    @Autowired
    Crypto crypto;
    @LocalServerPort
    int port;
    static long nextTenant = 100;
    long tenant;
    Messaging.Principal console;
    String product, sender, template, contact, phone;
    Map<String, Object> body;

    @org.springframework.boot.test.context.TestConfiguration
    static class WakeConfig {
        @org.springframework.context.annotation.Bean
        WakeProbe wakeProbe() {
            return new WakeProbe();
        }
    }

    static class WakeProbe {
        final java.util.concurrent.atomic.AtomicInteger committed =
                new java.util.concurrent.atomic.AtomicInteger();

        @org.springframework.transaction.event.TransactionalEventListener(
                phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
        public void commit(com.duozhi.outbound.job.service.OutboxCommitted event) {
            committed.incrementAndGet();
        }
    }

    @Autowired
    WakeProbe wake;
    @Autowired
    com.duozhi.outbound.job.service.Outbox outbox;

    @Test
    void outboxWakeOnlyRunsAfterSuccessfulCommit() {
        int before = wake.committed.get();
        assertThatThrownBy(
                        () ->
                                transactions.run(
                                        () -> {
                                            outbox.enqueue(
                                                    tenant,
                                                    "send_sms",
                                                    "unused",
                                                    "rollback-wake-" + tenant,
                                                    Map.of());
                                            assertThat(wake.committed.get()).isEqualTo(before);
                                            throw new IllegalStateException("rollback");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(wake.committed.get()).isEqualTo(before);
        transactions.run(
                () -> {
                    outbox.enqueue(tenant, "send_sms", "unused", "commit-wake-" + tenant, Map.of());
                    assertThat(wake.committed.get()).isEqualTo(before);
                    return null;
                });
        assertThat(wake.committed.get()).isEqualTo(before + 1);
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT COUNT(*) FROM taskoutbox WHERE tenant_id=?",
                                        Long.class,
                                        tenant))
                .isEqualTo(1);
    }

    @BeforeEach
    void seed() {
        tenant = ++nextTenant;
        Tenant t = new Tenant();
        t.setId((int) tenant);
        t.setCode("msg-" + tenant);
        t.setName("消息测试");
        tenants.insert(t);
        console = new Messaging.Principal(tenant, "console");
        phone = "+861380000" + String.format("%04d", tenant);
        contact =
                store.insert(
                                "contact",
                                Values.map(
                                        "tenant_id",
                                        tenant,
                                        "phone",
                                        phone,
                                        "name",
                                        "消息测试联系人",
                                        "consent_state",
                                        "consented",
                                        "consented_by",
                                        "test"))
                        .get("id")
                        .toString();
        product =
                resource(
                        "product",
                        Map.of("form", "rcs", "unit_price", 1000, "daily_recipient_limit", 100));
        sender = resource("sender", Map.of("brand", "测试品牌"));
        template = resource("template", Map.of("form", "rcs", "body", "您好，{{name}}。"));
        service.credit(tenant, "initial-credit", 100000);
        body =
                new LinkedHashMap<>(
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
                                Map.of("name", "客户"),
                                "purpose",
                                "marketing",
                                "channel_policy",
                                Map.of(
                                        "mode",
                                        "fixed",
                                        "allowed_forms",
                                        List.of("rcs"),
                                        "allow_fallback",
                                        false),
                                "expires_at",
                                Instant.now().plusSeconds(3600).toString()));
    }

    @AfterEach
    void cleanup() {
        for (String table :
                List.of(
                        "msg_interaction",
                        "msg_call_intent",
                        "msg_webhook_delivery",
                        "msg_webhook",
                        "msg_event",
                        "msg_attempt",
                        "msg_message",
                        "msg_inbox",
                        "msg_batch",
                        "msg_journal",
                        "msg_application",
                        "msg_resource",
                        "msg_account",
                        "msg_suppression"))
            db.jdbc().update("DELETE FROM " + table + " WHERE tenant_id=?", tenant);
    }

    String resource(String kind, Map<String, Object> payload) {
        var r =
                Support.map(
                        service.createResource(
                                tenant, kind, Map.of("name", kind + "测试", "payload", payload)));
        String id = r.get("id").toString();
        service.resourceState(tenant, id, "APPROVED");
        return id;
    }

    Map<String, Object> app() {
        return Support.map(
                service.createApplication(
                        tenant,
                        Map.of(
                                "name",
                                "下游测试",
                                "grants",
                                Map.of(
                                        "products",
                                        List.of(product),
                                        "senders",
                                        List.of(sender),
                                        "templates",
                                        List.of(template)))));
    }

    Map<String, Object> account() {
        return Support.map(Support.map(service.balance(tenant)).get("account"));
    }

    @Test
    void independentDeliverySettlementRefundAndCancellationAreAtomicAndIdempotent() {
        var sent = service.create(console, "message-0001", body);
        String id = sent.get("message_id").toString();
        assertThat(service.create(console, "message-0001", body).get("message_id")).isEqualTo(id);
        assertThat(sent.get("expires_at").toString()).endsWith("Z");
        assertThat(account()).containsEntry("available", 99000L).containsEntry("frozen", 1000L);
        var changed = new LinkedHashMap<>(body);
        changed.put("variables", Map.of("name", "另一客户"));
        assertThatThrownBy(() -> service.create(console, "message-0001", changed))
                .isInstanceOf(ApiError.class)
                .hasMessageContaining("幂等");
        assertThat(service.processOne()).isTrue();
        assertThat(service.message(console, id))
                .containsEntry("status", "DELIVERED")
                .containsEntry("simulated", true);
        assertThat(account())
                .containsEntry("available", 99000L)
                .containsEntry("frozen", 0L)
                .containsEntry("spent", 1000L);
        service.receipt(tenant, id, "duplicate-receipt", "DELIVERED");
        service.receipt(tenant, id, "duplicate-receipt", "DELIVERED");
        service.refund(tenant, id, "模拟退款");
        service.refund(tenant, id, "模拟退款");
        assertThat(account()).containsEntry("available", 100000L).containsEntry("spent", 0L);
        var later = new LinkedHashMap<>(body);
        later.put("scheduled_at", Instant.now().plusSeconds(120).toString());
        String pending =
                service.create(console, "message-0002", later).get("message_id").toString();
        service.cancel(console, pending);
        service.cancel(console, pending);
        assertThat(account()).containsEntry("available", 100000L).containsEntry("frozen", 0L);
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT COUNT(*) FROM msg_journal WHERE tenant_id=? AND available_delta+frozen_delta+spent_delta+clearing_delta<>0",
                                        Long.class,
                                        tenant))
                .isZero();
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT SUM(available_delta) FROM msg_journal WHERE tenant_id=?",
                                        Long.class,
                                        tenant))
                .isEqualTo(account().get("available"));
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT payload FROM msg_message WHERE id=?",
                                        String.class,
                                        id))
                .doesNotContain(phone, "客户");
    }

    @Test
    void concurrentIdempotencyNeverDuplicatesMessagesOrHolds() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Map<String, Object>>>();
            for (int i = 0; i < 12; i++)
                futures.add(
                        pool.submit(() -> service.create(console, "same-concurrent-key", body)));
            var ids = new HashSet<Object>();
            for (var future : futures) ids.add(future.get(15, TimeUnit.SECONDS).get("message_id"));
            assertThat(ids).hasSize(1);
        }
        assertThat(account()).containsEntry("available", 99000L).containsEntry("frozen", 1000L);
    }

    @Test
    void unknownNeverResendsOrReleasesAndLateReceiptSettlesOnce() {
        product =
                resource(
                        "product",
                        Map.of(
                                "form",
                                "rcs",
                                "simulation_outcome",
                                "UNKNOWN",
                                "unit_price",
                                1000,
                                "daily_recipient_limit",
                                100));
        body.put("service_product_id", product);
        String id = service.create(console, "unknown-message", body).get("message_id").toString();
        service.processOne();
        assertThat(service.message(console, id))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("billing_state", "HELD");
        service.processOne();
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT COUNT(*) FROM msg_attempt WHERE message_id=?",
                                        Long.class,
                                        id))
                .isEqualTo(1);
        assertThatThrownBy(() -> service.cancel(console, id)).isInstanceOf(ApiError.class);
        service.receipt(tenant, id, "late-delivered", "DELIVERED");
        service.receipt(tenant, id, "late-delivered", "DELIVERED");
        service.receipt(tenant, id, "out-of-order", "REJECTED");
        assertThat(service.message(console, id)).containsEntry("status", "DELIVERED");
        assertThat(account()).containsEntry("spent", 1000L).containsEntry("frozen", 0L);
    }

    @Test
    void revocationBeforeDispatchBlocksAndReleases() {
        var app = app();
        var p = new Messaging.Principal(tenant, app.get("application_id").toString());
        String id = service.create(p, "revoked-message", body).get("message_id").toString();
        service.revokeApplication(tenant, p.application());
        service.processOne();
        assertThat(service.message(console, id)).containsEntry("status", "BLOCKED");
        assertThat(account()).containsEntry("available", 100000L);
        assertThatThrownBy(() -> service.create(p, "after-revocation", body))
                .isInstanceOf(ApiError.class);
        service.suppress(tenant, contact, "退订");
        assertThatThrownBy(() -> service.create(console, "after-suppression", body))
                .isInstanceOf(ApiError.class)
                .hasMessageContaining("退订");
    }

    @Test
    void resourceAndMessageIsolationAndBudgetChecks() {
        var app = app();
        var p = new Messaging.Principal(tenant, app.get("application_id").toString());
        String id = service.create(p, "isolated-message", body).get("message_id").toString();
        assertThatThrownBy(() -> service.message(new Messaging.Principal(999, "console"), id))
                .isInstanceOf(ApiError.class);
        assertThatThrownBy(
                        () -> service.message(new Messaging.Principal(tenant, "app_unknown"), id))
                .isInstanceOf(ApiError.class);
        var changed = new LinkedHashMap<>(body);
        changed.put("sender_identity_id", resource("sender", Map.of("brand", "未授权品牌")));
        var ungranted = changed;
        assertThatThrownBy(() -> service.create(p, "ungranted-sender", ungranted))
                .isInstanceOf(ApiError.class)
                .hasMessageContaining("未授权");
        changed = new LinkedHashMap<>(body);
        changed.put("max_sales_amount", "0.000001");
        var over = changed;
        assertThatThrownBy(() -> service.create(console, "over-sales-budget", over))
                .isInstanceOf(ApiError.class)
                .hasMessageContaining("费用上限");
        db.jdbc().update("UPDATE msg_account SET available=0 WHERE tenant_id=?", tenant);
        assertThatThrownBy(() -> service.create(console, "no-balance-message", body))
                .isInstanceOf(Exception.class);
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT COUNT(*) FROM msg_message WHERE tenant_id=?",
                                        Long.class,
                                        tenant))
                .isEqualTo(1);
    }

    @Test
    void batchPartialAcceptanceHasStableResultsAcrossRestart() {
        var bad = new LinkedHashMap<>(body);
        bad.put("max_sales_amount", null);
        var batch =
                Support.map(
                        service.batch(
                                console,
                                "batch-partial-key",
                                Map.of(
                                        "items",
                                        List.of(
                                                Map.of("client_item_id", "good", "message", body),
                                                Map.of("client_item_id", "bad", "message", bad)))));
        assertThat(service.processBatch()).isTrue();
        var result = service.batchStatus(console, batch.get("batch_id").toString());
        assertThat(result.get("state")).isEqualTo("COMPLETED");
        assertThat(result.get("results").toString()).contains("ACCEPTED", "REJECTED");
        db.jdbc()
                .update(
                        "UPDATE msg_batch SET state='PROCESSING' WHERE id=?",
                        batch.get("batch_id"));
        service.processBatch();
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT COUNT(*) FROM msg_message WHERE tenant_id=?",
                                        Long.class,
                                        tenant))
                .isEqualTo(1);
    }

    HttpResponse<String> signed(
            HttpClient client,
            Map<String, Object> app,
            String method,
            String path,
            byte[] bytes,
            String nonce,
            String key,
            String signatureOverride)
            throws Exception {
        String timestamp = "" + Instant.now().getEpochSecond(),
                hash = Support.hex(Support.sha(bytes));
        String canonical = String.join("\n", method, path, "", timestamp, nonce, hash, key, "");
        String signature =
                signatureOverride == null
                        ? Support.hmac(
                                app.get("secret").toString(),
                                canonical.getBytes(StandardCharsets.UTF_8))
                        : signatureOverride;
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header(
                                "Authorization",
                                "HMAC-SHA256 Credential="
                                        + app.get("application_id")
                                        + ",Signature="
                                        + signature)
                        .header("X-Timestamp", timestamp)
                        .header("X-Nonce", nonce)
                        .header("X-Content-SHA256", hash)
                        .header("Idempotency-Key", key)
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void realHttpHmacRejectsTamperReplayAndCrossApplicationReads() throws Exception {
        var app = app();
        try (var client = HttpClient.newHttpClient()) {
            String nonce = UUID.randomUUID().toString().replace("-", "");
            var response =
                    signed(
                            client,
                            app,
                            "POST",
                            "/openapi/v1/messages",
                            Support.json(body),
                            nonce,
                            "http-message-key",
                            null);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
            String id =
                    Support.object(response.body().getBytes(StandardCharsets.UTF_8))
                            .get("message_id")
                            .toString();
            assertThat(
                            signed(
                                            client,
                                            app,
                                            "POST",
                                            "/openapi/v1/messages",
                                            Support.json(body),
                                            nonce,
                                            "http-message-key",
                                            null)
                                    .statusCode())
                    .isEqualTo(409);
            assertThat(
                            signed(
                                            client,
                                            app,
                                            "POST",
                                            "/openapi/v1/messages",
                                            Support.json(body),
                                            Messaging.id("x").substring(2),
                                            "http-message-key",
                                            "0".repeat(64))
                                    .statusCode())
                    .isEqualTo(401);
            var other = app();
            assertThat(
                            signed(
                                            client,
                                            other,
                                            "GET",
                                            "/openapi/v1/messages/" + id,
                                            new byte[0],
                                            UUID.randomUUID().toString().replace("-", ""),
                                            "",
                                            null)
                                    .statusCode())
                    .isEqualTo(404);
            service.revokeApplication(tenant, app.get("application_id").toString());
            assertThat(
                            signed(
                                            client,
                                            app,
                                            "GET",
                                            "/openapi/v1/messages/" + id,
                                            new byte[0],
                                            UUID.randomUUID().toString().replace("-", ""),
                                            "",
                                            null)
                                    .statusCode())
                    .isEqualTo(401);
        }
    }

    @Test
    void completedCallCreatesExactlyOneMessageOnlyAfterHangup() {
        String rule =
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
                                Map.of("name", "客户")));
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
        service.deferCall(tenant, call.get("id").toString(), 1);
        service.deferCall(tenant, call.get("id").toString(), 1);
        assertThat(service.processCallIntent()).isFalse();
        assertThat(service.messages(tenant)).isEmpty();
        store.update("callsession", tenant, call.get("id"), Values.map("status", "completed"));
        assertThat(service.processCallIntent()).isTrue();
        assertThat(service.messages(tenant)).hasSize(1);
        assertThat(service.processCallIntent()).isFalse();
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT call_id FROM msg_message WHERE tenant_id=?",
                                        String.class,
                                        tenant))
                .isEqualTo(call.get("id"));
    }

    @Test
    void signedWebhookRetryKeepsEventIdentityAndNeverResendsMessage() throws Exception {
        var application = app();
        var principal =
                new Messaging.Principal(tenant, application.get("application_id").toString());
        var deliveries = new ArrayList<Map<String, String>>();
        var requestBodies = new ArrayList<byte[]>();
        var reject = new java.util.concurrent.atomic.AtomicBoolean(true);
        WebhookTransport fake =
                (uri, headers, bytes) -> {
                    var event = Support.object(bytes);
                    if ("webhook.verification".equals(event.get("type")))
                        return new WebhookTransport.Reply(200, event.get("challenge").toString());
                    deliveries.add(headers);
                    requestBodies.add(bytes);
                    return new WebhookTransport.Reply(reject.get() ? 503 : 204, "");
                };
        try (var cleanup =
                new AutoCloseable() {
                    public void close() {}
                }) {
            var hooks = new MessageWebhooks(db, transactions, secrets, config, fake);
            try {
                var input =
                        Map.<String, Object>of(
                                "url",
                                "https://example.com/events",
                                "event_types",
                                List.of("message.delivered"));
                var subscription = Support.map(hooks.create(principal, "webhook-test-key", input));
                String hook = subscription.get("subscription_id").toString();
                hooks.create(principal, "webhook-test-key", input);
                assertThat(
                                db.jdbc()
                                        .queryForObject(
                                                "SELECT COUNT(*) FROM msg_webhook WHERE tenant_id=?",
                                                Long.class,
                                                tenant))
                        .isEqualTo(1);
                assertThat(hooks.processOne()).isTrue();
                assertThat(Support.map(hooks.get(principal, hook)).get("state"))
                        .isEqualTo("ACTIVE");
                String message =
                        service.create(principal, "hook-message-key", body)
                                .get("message_id")
                                .toString();
                service.processOne();
                hooks.processOne();
                assertThat(deliveries).hasSize(1);
                String expected =
                        Support.hmac(
                                subscription.get("secret").toString(),
                                (deliveries.getFirst().get("X-Timestamp")
                                                + "\n"
                                                + deliveries.getFirst().get("X-Event-Id")
                                                + "\n"
                                                + Support.hex(
                                                        Support.sha(requestBodies.getFirst())))
                                        .getBytes(StandardCharsets.UTF_8));
                assertThat(deliveries.getFirst().get("X-Signature")).isEqualTo("v1=" + expected);
                reject.set(false);
                db.jdbc()
                        .update(
                                "UPDATE msg_webhook_delivery SET next_run_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE tenant_id=?",
                                tenant);
                hooks.processOne();
                assertThat(deliveries).hasSize(2);
                assertThat(deliveries.get(1).get("X-Event-Id"))
                        .isEqualTo(deliveries.getFirst().get("X-Event-Id"));
                assertThat(deliveries.get(1).get("X-Delivery-Id"))
                        .isNotEqualTo(deliveries.getFirst().get("X-Delivery-Id"));
                assertThat(
                                db.jdbc()
                                        .queryForObject(
                                                "SELECT COUNT(*) FROM msg_attempt WHERE message_id=?",
                                                Long.class,
                                                message))
                        .isEqualTo(1);
                assertThat(
                                db.jdbc()
                                        .queryForObject(
                                                "SELECT state FROM msg_webhook_delivery WHERE tenant_id=?",
                                                String.class,
                                                tenant))
                        .isEqualTo("DELIVERED");
            } finally {
                hooks.close();
            }
        }
    }

    @Test
    void interactionsDeduplicateConversionPreserveLeadAndUnsubscribe() {
        String message =
                service.create(console, "interaction-message", body).get("message_id").toString();
        service.processOne();
        var conversion =
                Map.<String, Object>of("kind", "conversion", "business_event_id", "order-123");
        var first =
                Support.map(service.interaction(console, message, "conversion-key-1", conversion));
        assertThat(
                        Support.map(
                                service.interaction(
                                        console, message, "conversion-key-2", conversion)))
                .containsEntry("interaction_id", first.get("interaction_id"))
                .containsEntry("duplicate", true);
        String other =
                service.create(console, "other-interaction-message", body)
                        .get("message_id")
                        .toString();
        assertThatThrownBy(
                        () -> service.interaction(console, other, "conversion-key-3", conversion))
                .isInstanceOf(ApiError.class)
                .hasMessageContaining("业务转化");
        service.interaction(
                console, message, "reply-event-key", Map.of("kind", "reply", "text", "请联系我"));
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT COUNT(*) FROM ops_history WHERE tenant_id=? AND action='message_interaction'",
                                        Long.class,
                                        tenant))
                .isEqualTo(1);
        service.interaction(
                console, message, "unsubscribe-event-key", Map.of("kind", "unsubscribe"));
        service.processOne();
        assertThat(service.message(console, other)).containsEntry("status", "BLOCKED");
        assertThatThrownBy(() -> service.create(console, "after-unsubscribe-key", body))
                .isInstanceOf(ApiError.class)
                .hasMessageContaining("退订");
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT COUNT(*) FROM msg_interaction WHERE tenant_id=?",
                                        Long.class,
                                        tenant))
                .isEqualTo(3);
    }

    @Test
    void retentionRedactsFinalPiiButKeepsUnknownMoneyAndIdempotency() {
        String done =
                service.create(console, "retention-delivered", body).get("message_id").toString();
        service.processOne();
        product =
                resource(
                        "product",
                        Map.of(
                                "form",
                                "rcs",
                                "simulation_outcome",
                                "UNKNOWN",
                                "unit_price",
                                1000,
                                "daily_recipient_limit",
                                100));
        body.put("service_product_id", product);
        String unknown =
                service.create(console, "retention-unknown", body).get("message_id").toString();
        service.processOne();
        db.jdbc()
                .update(
                        "UPDATE msg_message SET finished_at=CURRENT_TIMESTAMP(6)-INTERVAL 91 DAY WHERE tenant_id=?",
                        tenant);
        service.retain();
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT recipient_cipher FROM msg_message WHERE id=?",
                                        String.class,
                                        done))
                .isEmpty();
        assertThat(
                        db.jdbc()
                                .queryForObject(
                                        "SELECT recipient_cipher FROM msg_message WHERE id=?",
                                        String.class,
                                        unknown))
                .isNotEmpty();
        assertThat(service.message(console, unknown)).containsEntry("billing_state", "HELD");
        assertThat(service.create(console, "retention-unknown", body).get("message_id"))
                .isEqualTo(unknown);
        assertThat(account()).containsEntry("frozen", 1000L).containsEntry("spent", 1000L);
    }

    @Test
    void disabledTenantDoesNotPoisonDispatchAndStaticTemplateNeedsNoVariables() {
        String pending =
                service.create(console, "disabled-tenant-message", body)
                        .get("message_id")
                        .toString();
        db.jdbc().update("UPDATE tenant SET enabled=FALSE WHERE id=?", tenant);
        assertThat(service.processOne()).isTrue();
        assertThat(service.message(console, pending)).containsEntry("status", "BLOCKED");
        assertThat(account()).containsEntry("available", 100000L).containsEntry("frozen", 0L);
        db.jdbc().update("UPDATE tenant SET enabled=TRUE WHERE id=?", tenant);
        body.put(
                "template_version_id",
                resource("template", Map.of("form", "rcs", "body", "您好，预约已确认。")));
        body.remove("variables");
        String sent =
                service.create(console, "static-template-message", body)
                        .get("message_id")
                        .toString();
        service.processOne();
        assertThat(service.message(console, sent)).containsEntry("status", "DELIVERED");
    }
}
