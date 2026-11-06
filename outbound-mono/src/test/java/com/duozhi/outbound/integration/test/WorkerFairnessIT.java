package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.auth.service.AccountSecurity;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.runtime.ScheduledWork;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.job.service.IntegrationTasks;
import com.duozhi.outbound.job.service.Outbox;
import com.duozhi.outbound.job.worker.TaskWorker;
import com.duozhi.outbound.messaging.service.*;
import com.duozhi.outbound.operations.controller.VoiceStatements;
import com.duozhi.outbound.operations.service.OperationalPush;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerFairnessIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("ENV", () -> "internal-test");
        p.add("JWT_SECRET", () -> "worker-fairness-local-fixture-secret-1234567890");
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
    }

    @Autowired
    WorkspaceDocuments docs;
    @Autowired
    Store store;
    @Autowired
    Outbox outbox;
    @Autowired
    TaskWorker worker;
    @Autowired
    Messaging messages;
    @Autowired
    MessageDelivery delivery;
    @MockitoSpyBean
    IntegrationTasks integrations;
    @MockitoBean
    ScheduledWork scheduled;

    @BeforeEach
    void seed() {
        if (store.one("tenant", "findById", Map.of("id", 1)) == null)
            store.insert(
                    "tenant", Values.map("id", 1, "code", "worker-fairness", "name", "本地并发测试"));
    }

    @Test
    void oldPayloadsUseIsolatedLanesAndPreserveSameKindPredecessors() throws Exception {
        String aggregate = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();
        var slowEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interfaceRan = new AtomicBoolean();
        var secondPushRan = new AtomicBoolean();
        outbox.enqueue(
                1,
                "business_callback",
                aggregate,
                key + ":first",
                Map.of("push_type", "calls", "first", true));
        // Timestamps are part of FIFO; force a deterministic sequence for this regression.
        outbox.enqueue(
                1, "business_callback", aggregate, key + ":second", Map.of("push_type", "calls"));
        outbox.enqueue(
                1,
                "business_callback",
                aggregate,
                key + ":interface",
                Map.of("interface_request", key));
        docs.jdbc.update(
                "UPDATE taskoutbox SET created_at=DATE_ADD('2026-01-01 00:00:00',INTERVAL 1 SECOND) WHERE idempotency_key=?",
                key + ":first");
        docs.jdbc.update(
                "UPDATE taskoutbox SET created_at=DATE_ADD('2026-01-01 00:00:00',INTERVAL 2 SECOND) WHERE idempotency_key=?",
                key + ":second");
        docs.jdbc.update(
                "UPDATE taskoutbox SET created_at=DATE_ADD('2026-01-01 00:00:00',INTERVAL 3 SECOND) WHERE idempotency_key=?",
                key + ":interface");
        doAnswer(
                        inv -> {
                            Map<String, Object> payload = inv.getArgument(2);
                            if (Boolean.TRUE.equals(payload.get("first"))) {
                                slowEntered.countDown();
                                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                            } else if (payload.containsKey("interface_request"))
                                interfaceRan.set(true);
                            else secondPushRan.set(true);
                            return null;
                        })
                .when(integrations)
                .execute(anyString(), anyMap(), anyMap());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var slow =
                    executor.submit(() -> worker.processLane(1L, worker.integrationLane("push")));
            try {
                assertThat(slowEntered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(worker.processLane(1L, worker.integrationLane("interface"))).isTrue();
                assertThat(interfaceRan).isTrue();
                assertThat(worker.processLane(1L, worker.integrationLane("push"))).isFalse();
                assertThat(secondPushRan).isFalse();
            } finally {
                release.countDown();
            }
            assertThat(slow.get(2, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.processLane(1L, worker.integrationLane("push"))).isTrue();
            assertThat(secondPushRan).isTrue();
        }
    }

    @Test
    void expiredNodeRequestResumesFailureBranchWithoutCallingTheProvider() {
        String request = "expired-" + UUID.randomUUID();
        var call =
                store.insert(
                        "callsession",
                        Values.map(
                                "tenant_id",
                                1,
                                "phone",
                                "13100001111",
                                "mode",
                                "ai_only",
                                "status",
                                "in_ai",
                                "attempts",
                                1));
        var state =
                store.insert(
                        "conversationstate",
                        Values.map(
                                "tenant_id",
                                1,
                                "call_id",
                                call.get("id"),
                                "attempt",
                                1,
                                "policy_json",
                                "{}",
                                "data_json",
                                docs.json.json(
                                        Map.of(
                                                "pending_interface",
                                                Map.of("id", request, "node", "lookup")))));
        var transport = mock(PinnedWebhookTransport.class);
        var interfaces =
                new com.duozhi.outbound.ai.service.BusinessInterfaces(docs, outbox, transport);
        var payload =
                Map.<String, Object>of(
                        "call_id",
                        call.get("id"),
                        "attempt",
                        1,
                        "interface_request",
                        request,
                        "node",
                        "lookup",
                        "definition",
                        Map.of(),
                        "variables",
                        Map.of(),
                        "queued_at",
                        java.time.Instant.now().minusSeconds(61).toString());
        interfaces.execute(Map.of("tenant_id", 1), payload);
        interfaces.execute(Map.of("tenant_id", 1), payload);
        verifyNoInteractions(transport);
        var data =
                docs.json.object(
                        docs.one(
                                        "SELECT data_json FROM conversationstate WHERE tenant_id=1 AND id=?",
                                        state.get("id"))
                                .get("data_json")
                                .toString());
        assertThat(data)
                .doesNotContainKey("pending_interface")
                .containsEntry("interface_error", "业务接口排队超时");
        assertThat(
                        docs.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM taskoutbox WHERE tenant_id=1 AND idempotency_key=?",
                                Integer.class,
                                request + ":resume"))
                .isEqualTo(1);
    }

    @Test
    void balanceScanResumesAfterBudgetAndRestartAndCoversMoreThanOneThousandTenants() {
        var statements = mock(VoiceStatements.class);
        var out = mock(Outbox.class);
        var seen = new ArrayList<Long>();
        for (long tenant = 30000; tenant < 31500; tenant++)
            docs.jdbc.update(
                    "INSERT INTO platform_document(tenant_id,kind,object_id,name,draft_json,updated_at) VALUES(?,'push_balance','tenant','test','{\"enabled\":true,\"threshold\":0}',CURRENT_TIMESTAMP(3))",
                    tenant);
        docs.jdbc.update("UPDATE platform_job_cursor SET last_id=0 WHERE job_key='balance-push'");
        when(statements.balance(anyLong()))
                .thenAnswer(
                        inv -> {
                            long tenant = inv.getArgument(0);
                            seen.add(tenant);
                            if (tenant == 30000) Thread.currentThread().interrupt();
                            if (tenant == 30001)
                                throw new IllegalStateException("synthetic tenant failure");
                            return Map.of("available", false);
                        });
        try {
            new OperationalPush(
                            docs,
                            mock(AccountSecurity.class),
                            mock(WebhookTransport.class),
                            out,
                            scheduled,
                            statements)
                    .balances();
            assertThat(Thread.interrupted()).isTrue();
            assertThat(seen).containsExactly(30000L);
            assertThat(
                            docs.jdbc.queryForObject(
                                    "SELECT last_id FROM platform_job_cursor WHERE job_key='balance-push'",
                                    Long.class))
                    .isEqualTo(30000);
            // A new instance reads the durable cursor, rather than restarting from the slow row.
            var restarted =
                    new OperationalPush(
                            docs,
                            mock(AccountSecurity.class),
                            mock(WebhookTransport.class),
                            out,
                            scheduled,
                            statements);
            restarted.balances();
            restarted.balances();
            assertThat(seen).hasSize(1500).doesNotHaveDuplicates().contains(30000L, 30001L, 31499L);
            verifyNoInteractions(out);
        } finally {
            Thread.interrupted();
            docs.jdbc.update(
                    "DELETE FROM platform_document WHERE kind='push_balance' AND tenant_id BETWEEN 30000 AND 31499");
            docs.jdbc.update(
                    "UPDATE platform_job_cursor SET last_id=0 WHERE job_key='balance-push'");
        }
    }

    @Test
    void messagePartitionsPageByTenantAndChannelAndNeverSelectUnknownForResend() {
        try {
            for (int i = 0; i < 130; i++) {
                long tenant = 40000 + i;
                String id = "fair-" + i;
                docs.jdbc.update(
                        "INSERT INTO msg_message(id,tenant_id,application_id,idempotency_key,request_hash,product_id,template_id,sender_id,recipient_cipher,recipient_hash,recipient_mask,payload,state,requested_form,sales_amount,price_version,scheduled_at,expires_at,channel_id) VALUES(?,?,'console',?,'hash','p','t','s','cipher','hash','mask','{}','QUEUED','sms',0,'v',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)+INTERVAL 1 DAY,?)",
                        id,
                        tenant,
                        id,
                        "channel-" + i);
            }
            docs.jdbc.update("UPDATE msg_message SET state='UNKNOWN' WHERE id='fair-0'");
            var all = new ArrayList<MessagePartition>();
            var cursor = MessagePartition.START;
            for (int i = 0; i < 4; i++) {
                var page = messages.readyPartitions(cursor, 64);
                if (page.isEmpty()) break;
                all.addAll(page);
                cursor = page.getLast();
            }
            assertThat(all)
                    .hasSize(129)
                    .doesNotHaveDuplicates()
                    .doesNotContain(new MessagePartition(40000, "channel-0"))
                    .contains(new MessagePartition(40129, "channel-129"));
            assertThat(messages.processOne(new MessagePartition(40000, "channel-0"))).isFalse();
            assertThat(
                            docs.jdbc.queryForObject(
                                    "SELECT state FROM msg_message WHERE id='fair-0'",
                                    String.class))
                    .isEqualTo("UNKNOWN");
            docs.jdbc.update(
                    "INSERT INTO msg_delivery(message_id,tenant_id,channel_id,state,next_query_at) VALUES('fair-0',40000,'channel-0','UNKNOWN',CURRENT_TIMESTAMP(6))");
            assertThat(delivery.queryPartitions(MessagePartition.START, 64))
                    .containsExactly(new MessagePartition(40000, "channel-0"));
        } finally {
            docs.jdbc.update("DELETE FROM msg_delivery WHERE tenant_id BETWEEN 40000 AND 40129");
            docs.jdbc.update("DELETE FROM msg_message WHERE tenant_id BETWEEN 40000 AND 40129");
        }
    }
}
