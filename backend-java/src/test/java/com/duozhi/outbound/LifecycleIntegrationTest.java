package com.duozhi.outbound;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=${JAVA_TEST_DATABASE_URL:jdbc:sqlite:file:java-auth-test?mode=memory&cache=shared}","spring.datasource.hikari.maximum-pool-size=1","JAVA_INITIALIZE_SCHEMA=true","JWT_SECRET=java-integration-test-secret-at-least-32-characters","DEMO_USERS_ENABLED=true"})
@AutoConfigureMockMvc
class LifecycleIntegrationTest {
    private static final AtomicLong TENANTS = new AtomicLong(70000);
    @Autowired Store store;
    @Autowired Transactions tx;
    @Autowired CallLifecycle lifecycle;
    @Autowired CallbackInbox inbox;
    @Autowired VoiceCallbacks callbacks;
    @Autowired Outbox outbox;
    @Autowired Contract contract;
    @Autowired JsonSupport json;

    Map<String, Object> call() {
        long tenant = TENANTS.incrementAndGet();
        store.insert("tenant", JsonSupport.map("id", tenant, "name", "Lifecycle fixture", "code", "lifecycle-" + tenant));
        Map<String, Object> campaign = store.insert("campaign", JsonSupport.map("tenant_id", tenant, "name", "Lifecycle fixture", "status", "running", "dispatch_enabled", true, "retry_interval_sec", 30));
        return store.insert("callsession", JsonSupport.map("tenant_id", tenant, "phone", "13100000801", "mode", "ai_only", "campaign_id", campaign.get("id"), "status", "dialing", "attempts", 1, "max_attempts", 3));
    }
    Map<String, Object> body(Map<String, Object> call, String status, String id, int attempt) {
        return JsonSupport.map("call_id", call.get("id"), "kind", "status", "transcript", null, "payload", JsonSupport.map("status", status, "event_id", id, "attempt", attempt));
    }
    Map<String, Object> reload(Map<String, Object> call) { return store.require("callsession", CallLifecycle.tenant(call), call.get("id")); }

    @Test void duplicateAndStaleCallbacksCannotReopenTerminalCall() {
        Map<String, Object> call = call();
        lifecycle.status(body(call, "answered", "answer", 1));
        lifecycle.status(body(call, "answered", "answer", 1));
        Object bound = store.encode("callevent", "call_session_id", call.get("id"));
        assertEquals(1, store.count("callevent", "WHERE call_session_id=?", bound));
        assertEquals(1, store.count("taskoutbox", "WHERE aggregate_id=? AND task_type='ai_turn'", call.get("id")));
        assertEquals(2, CallLifecycle.number(store.one("webhookeventingest", "WHERE call_session_id=?", bound), "repeat_count"));
        assertEquals("stale_attempt", lifecycle.status(body(call, "failed", "old", 0)).get("reason"));
        assertEquals("answered", reload(call).get("status"));
        lifecycle.status(body(call, "completed", "end", 1));
        lifecycle.status(body(call, "answered", "late", 1));
        assertEquals("completed", reload(call).get("status"));
        assertEquals("completed", store.require("campaign", CallLifecycle.tenant(call), call.get("campaign_id")).get("status"));
        assertEquals(1, store.count("taskoutbox", "WHERE aggregate_id=? AND task_type='call_analysis'", call.get("id")));
        assertNotNull(store.one("callusage", "WHERE call_session_id=?", bound).get("telephony_seconds"));
    }

    @Test void retryKeepsCampaignOpenButSuppressionCancelsIt() {
        Map<String, Object> call = call();
        lifecycle.status(body(call, "busy", "busy", 1));
        assertNotNull(reload(call).get("next_attempt_at"));
        assertEquals("running", store.require("campaign", CallLifecycle.tenant(call), call.get("campaign_id")).get("status"));
        store.insert("phonesuppression", JsonSupport.map("tenant_id", CallLifecycle.tenant(call), "phone", call.get("phone"), "reason", "opt out"));
        lifecycle.status(body(call, "busy", "busy-new-evidence", 1));
        assertNull(reload(call).get("next_attempt_at"));
        assertEquals("completed", store.require("campaign", CallLifecycle.tenant(call), call.get("campaign_id")).get("status"));
    }

    @Test void businessStateEventsAndOutboxRollbackTogether() {
        Map<String, Object> call = call();
        assertThrows(IllegalStateException.class, () -> tx.run(() -> {
            lifecycle.status(body(call, "answered", "rollback", 1));
            throw new IllegalStateException("synthetic failure before transaction commit");
        }));
        assertEquals("dialing", reload(call).get("status"));
        assertEquals(0, store.count("callevent", "WHERE call_session_id=?", store.encode("callevent", "call_session_id", call.get("id"))));
        assertEquals(0, store.count("taskoutbox", "WHERE aggregate_id=?", call.get("id")));
    }

    @Test void durableReceiptRejectsChangedReplayAndConsumesAtomically() {
        Set<Object> existing=new HashSet<>();for(Map<String,Object> worker:store.list("callbackinboxworker",""))existing.add(worker.get("id"));
        Config cfg=new Config(new MockEnvironment());CallbackInbox consumer=new CallbackInbox(store,tx,json,cfg,lifecycle,callbacks);
        consumer.maintenance();consumer.maintenance();
        Map<String,Object> worker=store.list("callbackinboxworker","").stream().filter(row->!existing.contains(row.get("id"))).findFirst().get();
        assertEquals(0,CallLifecycle.number(worker,"processed"));
        assertTrue(((Number)consumer.snapshot().get("live_workers")).longValue()>0);
        Map<String, Object> call = call(); Map<String, Object> body = body(call, "answered", "receipt", 1);
        Map<String, Object> ack = inbox.receive("telephony_status", body);
        assertEquals("dialing", reload(call).get("status"));
        assertEquals(true, inbox.receive("telephony_status", body).get("duplicate"));
        ApiError conflict = assertThrows(ApiError.class, () -> inbox.receive("telephony_status", body(call, "busy", "receipt", 1)));
        assertEquals(409, conflict.status);
        Map<String, Object> receipt = store.one("callbackinbox", "WHERE receipt_key=?", ack.get("receipt_id"));
        assertTrue(consumer.consume(CallLifecycle.number(receipt, "partition_id")));
        Map<String,Object> updatedWorker=store.one("callbackinboxworker","WHERE id=?",worker.get("id"));
        assertEquals(1,CallLifecycle.number(updatedWorker,"processed"));
        assertTrue(((Number)updatedWorker.get("max_latency_ms")).doubleValue()>=0);
        assertEquals("answered", reload(call).get("status"));
        assertEquals("done", inbox.receive("telephony_status", body).get("processing"));
        Map<String, Object> partition = store.one("callbackinboxpartition", "WHERE id=?", receipt.get("partition_id"));
        assertEquals(0, CallLifecycle.number(partition, "pending_count"));
        assertEquals(0, CallLifecycle.number(partition, "pending_bytes"));
    }

    @Test void inboxBackpressureDoesNotAcknowledgeLostEvent() {
        Map<String, Object> call = call();
        CallbackInbox bounded = new CallbackInbox(store, tx, json, new Config(new MockEnvironment().withProperty("CALLBACK_INBOX_PARTITION_LIMIT", "1")), lifecycle, callbacks);
        Map<String, Object> ack = bounded.receive("telephony_status", body(call, "answered", "capacity-1", 1));
        ApiError full = assertThrows(ApiError.class, () -> bounded.receive("telephony_status", body(call, "completed", "capacity-2", 1)));
        assertEquals(503, full.status);
        Map<String, Object> receipt = store.one("callbackinbox", "WHERE receipt_key=?", ack.get("receipt_id"));
        assertEquals(1, store.count("callbackinbox", "WHERE call_id=?", store.encode("callbackinbox", "call_id", call.get("id"))));
        assertTrue(bounded.consume(CallLifecycle.number(receipt, "partition_id")));
    }

    @Test void webhookSignatureAndTokenAreRequiredWhenConfigured() {
        Map<String, Object> call = call();
        String secret = "synthetic-webhook-signing-secret", token = "synthetic-webhook-token";
        Config configured = new Config(new MockEnvironment().withProperty("TELEPHONY_WEBHOOK_SECRET", secret).withProperty("TELEPHONY_WEBHOOK_TOKEN", token));
        Webhooks controller = new Webhooks(configured, contract, json, lifecycle, inbox, callbacks);
        byte[] body = json.json(body(call, "answered", "signed", 1)).getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertEquals(401, assertThrows(ApiError.class, () -> controller.status(body, request)).status);
        String stamp = Long.toString(Instant.now().getEpochSecond());
        request.addHeader("x-webhook-token", token); request.addHeader("x-webhook-timestamp", stamp);
        request.addHeader("x-webhook-signature", JsonSupport.hmac(secret, (stamp + "." + new String(body, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)));
        assertEquals("ok", controller.status(body, request).get("result"));
        byte[] tampered = json.json(body(call, "completed", "signed", 1)).getBytes(StandardCharsets.UTF_8);
        assertEquals(401, assertThrows(ApiError.class, () -> controller.status(tampered, request)).status);
        assertEquals("answered", reload(call).get("status"));
    }
}
