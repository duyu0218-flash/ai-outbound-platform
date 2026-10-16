package com.duozhi.outbound;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=${JAVA_TEST_DATABASE_URL:jdbc:sqlite:file:java-auth-test?mode=memory&cache=shared}","spring.datasource.hikari.maximum-pool-size=1","JAVA_INITIALIZE_SCHEMA=true","JWT_SECRET=java-integration-test-secret-at-least-32-characters","DEMO_USERS_ENABLED=true"})
@AutoConfigureMockMvc
class CampaignIntegrationTest {
    private static final java.util.concurrent.atomic.AtomicLong TENANTS = new java.util.concurrent.atomic.AtomicLong(50000);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired Store store;
    @Autowired CallPolicy policy;
    @Autowired Settings settings;
    @Autowired Crypto crypto;
    @Autowired javax.sql.DataSource source;

    String token(String username, String password) throws Exception {
        return "Bearer " + mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(mapper.writeValueAsString(Values.map("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("access_token").asText();
    }

    // Each test uses a private tenant, avoiding shared admin settings and login lockouts.
    Identity fixture() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Map<String, Object> tenant = store.insert("tenant", Values.map("id", TENANTS.incrementAndGet(), "name", "Campaign test", "code", suffix));
        long id = ((Number)tenant.get("id")).longValue();
        Map<String, Object> user = store.insert("user", Values.map("tenant_id", id, "username", "campaign-" + suffix,
                "password_hash", crypto.password("test-password"), "role", "admin", "full_name", "Test admin"));
        Map<String, Object> compliance = settings.defaults("compliance");
        compliance.put("allowed_start_hour", 0); compliance.put("allowed_end_hour", 0); compliance.put("min_attempt_interval_sec", 0);
        store.insert("adminsetting", Values.map("tenant_id", id, "section", "compliance", "data_json", mapper.writeValueAsString(compliance)));
        return new Identity(id, user);
    }

    Map<String, Object> contact(long tenant, String phone, String consent) {
        return store.insert("contact", Values.map("tenant_id", tenant, "phone", phone, "consent_state", consent));
    }

    Map<String, Object> body(Object... ids) {
        return new LinkedHashMap<>(Values.map("name", "Migration test", "mode", "ai_only", "contact_ids", Arrays.asList(ids), "retry_limit", 2));
    }

    JsonNode send(MockHttpServletRequestBuilder request, String auth, Object body) throws Exception {
        request.header("Authorization", auth);
        if (body != null) request.contentType("application/json").content(mapper.writeValueAsString(body));
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test void prepareIsIdempotentStopCancelsAndEditPreservesAttemptedHistory() throws Exception {
        Identity f = fixture(); String auth = token(f.actor(), "test-password");
        Map<String, Object> one = contact(f.tenant(), "13100000101", "consented");
        Map<String, Object> two = contact(f.tenant(), "13100000102", "consented");
        JsonNode campaign = send(post("/api/v1/campaigns"), auth, body(one.get("id"), two.get("id")));
        long id = campaign.path("id").asLong();
        JsonNode started = send(post("/api/v1/campaigns/" + id + "/start?auto_dial=false"), auth, null);
        assertEquals(2, started.path("created").asInt()); assertEquals("prepared", started.path("status").asText());
        assertEquals(0, send(post("/api/v1/campaigns/" + id + "/start?auto_dial=false"), auth, null).path("created").asInt());
        assertEquals(2, store.count("callsession", "WHERE campaign_id=?", id));
        List<Map<String, Object>> calls = store.list("callsession", "WHERE campaign_id=? ORDER BY contact_id", id);
        assertEquals(3, ((Number)calls.get(0).get("max_attempts")).intValue());
        store.update("callsession", f.tenant(), calls.get(0).get("id"), Values.map("attempts", 1, "status", "busy", "next_attempt_at", Store.now().plusMinutes(10)));
        send(post("/api/v1/campaigns/" + id + "/stop"), auth, null);
        assertNull(store.require("callsession", f.tenant(), calls.get(0).get("id")).get("next_attempt_at"));
        assertEquals("failed", store.require("callsession", f.tenant(), calls.get(1).get("id")).get("status"));
        Map<String, Object> changed = body(one.get("id")); changed.put("mode", "human_only"); changed.put("retry_limit", 4);
        send(put("/api/v1/campaigns/" + id), auth, changed);
        Map<String, Object> attempted = store.require("callsession", f.tenant(), calls.get(0).get("id"));
        assertEquals("ai_only", attempted.get("mode")); assertEquals(3, ((Number)attempted.get("max_attempts")).intValue());
        assertEquals("contact removed before dispatch", store.require("callsession", f.tenant(), calls.get(1).get("id")).get("last_error"));
        send(delete("/api/v1/campaigns/" + id), auth, null);
        mvc.perform(get("/api/v1/campaigns/" + id).header("Authorization", auth)).andExpect(status().isNotFound());
    }

    @Test void invalidReferencesRollbackAndDispatchDoesNotPretendSuccess() throws Exception {
        Identity f = fixture(); String auth = token(f.actor(), "test-password");
        Map<String, Object> contact = contact(f.tenant(), "13100000201", "consented");
        long before = store.count("campaign", "WHERE tenant_id=?", f.tenant());
        mvc.perform(post("/api/v1/campaigns").header("Authorization", auth).contentType("application/json")
                .content(mapper.writeValueAsString(body(contact.get("id"), contact.get("id"))))).andExpect(status().isBadRequest());
        assertEquals(before, store.count("campaign", "WHERE tenant_id=?", f.tenant()));
        Map<String, Object> badFlow = body(contact.get("id")); badFlow.put("script_flow_version_id", 999999);
        mvc.perform(post("/api/v1/campaigns").header("Authorization", auth).contentType("application/json").content(mapper.writeValueAsString(badFlow))).andExpect(status().isBadRequest());
        long id = send(post("/api/v1/campaigns"), auth, body(contact.get("id"))).path("id").asLong();
        mvc.perform(post("/api/v1/campaigns/" + id + "/start").header("Authorization", auth)).andExpect(status().isServiceUnavailable());
        assertEquals(0, store.count("callsession", "WHERE campaign_id=?", id));
        assertEquals("draft", store.require("campaign", f.tenant(), id).get("status"));
        send(post("/api/v1/campaigns/" + id + "/start?auto_dial=false"), auth, null);
        mvc.perform(post("/api/v1/campaigns/" + id + "/resume").header("Authorization", auth)).andExpect(status().isServiceUnavailable());
        assertEquals("prepared", store.require("campaign", f.tenant(), id).get("status"));
        store.update("campaign", f.tenant(), id, Values.map("status", "running", "dispatch_enabled", true));
        mvc.perform(put("/api/v1/campaigns/" + id).header("Authorization", auth).contentType("application/json").content(mapper.writeValueAsString(body()))).andExpect(status().isConflict());
        assertEquals("paused", send(post("/api/v1/campaigns/" + id + "/pause"), auth, null).path("status").asText());
        mvc.perform(delete("/api/v1/campaigns/" + id).header("Authorization", auth)).andExpect(status().isConflict());
    }

    @Test void preflightReportsDncConsentAndDailyLimitsWithoutCreatingBlockedCalls() throws Exception {
        Identity f = fixture(); String auth = token(f.actor(), "test-password");
        Map<String, Object> good = contact(f.tenant(), "13100000301", "consented");
        Map<String, Object> revoked = contact(f.tenant(), "13100000302", "revoked");
        Map<String, Object> dnc = contact(f.tenant(), "13100000303", "consented");
        store.insert("phonesuppression", Values.map("tenant_id", f.tenant(), "phone", dnc.get("phone"), "reason", "test opt out"));
        long id = send(post("/api/v1/campaigns"), auth, body(good.get("id"), revoked.get("id"), dnc.get("id"))).path("id").asLong();
        JsonNode prepared = send(post("/api/v1/campaigns/" + id + "/start?auto_dial=false"), auth, null);
        assertEquals(1, prepared.path("created").asInt()); assertEquals(2, prepared.path("skipped").asInt());
        assertEquals("PARTIAL_SUCCESS", prepared.path("result_code").asText());
        assertTrue(prepared.path("error_codes").toString().contains("CONTACT_DNC"));
        assertTrue(prepared.path("error_codes").toString().contains("CONTACT_CONSENT_REVOKED"));
        Map<String, Object> call = store.one("callsession", "WHERE campaign_id=?", id);
        store.update("callsession", f.tenant(), call.get("id"), Values.map("attempts", 3));
        assertEquals("daily_attempt_limit", policy.check(f.tenant(), good.get("phone").toString(), true, null));
    }

    @Test void callQueriesEnforceTenantAndAssignmentAndAggregateEvents() throws Exception {
        Identity f = fixture(); String auth = token(f.actor(), "test-password");
        Map<String, Object> agent = store.insert("user", Values.map("tenant_id", f.tenant(), "username", "agent-" + UUID.randomUUID(), "password_hash", crypto.password("test-password"), "role", "agent", "full_name", "Test agent"));
        String agentAuth = token(agent.get("username").toString(), "test-password");
        Map<String, Object> call = store.insert("callsession", Values.map("tenant_id", f.tenant(), "phone", "13100000401", "mode", "ai_only"));
        String path = "/api/v1/calls/" + call.get("id");
        for (String suffix : Arrays.asList("", "/events", "/webhook-events", "/webhook-stats"))
            mvc.perform(get(path + suffix).header("Authorization", agentAuth)).andExpect(status().isForbidden());
        assertEquals(0, send(get("/api/v1/calls"), agentAuth, null).size());
        store.update("callsession", f.tenant(), call.get("id"), Values.map("human_agent_id", agent.get("id")));
        assertEquals(1, send(get("/api/v1/calls?status=CREATED"), agentAuth, null).size());
        store.insert("callevent", Values.map("call_session_id", call.get("id"), "event_type", "status"));
        store.insert("webhookeventingest", Values.map("call_session_id", call.get("id"), "event_type", "status", "source", "telephony", "provider_event_key", UUID.randomUUID().toString(), "repeat_count", 3));
        assertEquals(1, send(get(path + "/events"), agentAuth, null).size());
        assertEquals(2, send(get(path + "/webhook-stats"), auth, null).path("duplicate_estimate").asInt());
        String otherAuth = token("admin", "12345678");
        mvc.perform(get(path).header("Authorization", otherAuth)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/calls?status=wrong").header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/campaigns").header("Authorization", agentAuth)).andExpect(status().isForbidden());
    }

    @Test void complianceClockAndRecordedAttemptsUseContactLocalDay() throws Exception {
        Identity f = fixture(); Map<String, Object> contact = contact(f.tenant(), "13100000501", "consented");
        Map<String, Object> compliance = settings.get(f.tenant(), "compliance");
        compliance.put("allowed_start_hour", 9); compliance.put("allowed_end_hour", 20);
        Map<String, Object> record = store.one("adminsetting", "WHERE tenant_id=? AND section='compliance'", f.tenant());
        store.update("adminsetting", f.tenant(), record.get("id"), Values.map("data_json", mapper.writeValueAsString(compliance)));
        assertEquals("outside_calling_hours", policy.checkAt(f.tenant(), "13100000501", true, null, Instant.parse("2026-10-15T00:59:00Z")));
        assertEquals("", policy.checkAt(f.tenant(), "13100000501", true, null, Instant.parse("2026-10-15T01:00:00Z")));
        Map<String, Object> call = store.insert("callsession", Values.map("tenant_id", f.tenant(), "phone", contact.get("phone"), "mode", "ai_only", "created_at", LocalDateTime.parse("2026-10-14T00:00:00")));
        for (int i = 0; i < 3; i++) store.insert("callevent", Values.map("call_session_id", call.get("id"), "event_type", "dial_attempt_claimed", "created_at", LocalDateTime.parse("2026-10-15T01:00:00")));
        assertEquals("daily_attempt_limit", policy.checkAt(f.tenant(), "13100000501", true, null, Instant.parse("2026-10-15T02:00:00Z")));
    }

    @Test void concurrentPrepareCreatesOneCallPerContact() throws Exception {
        Identity f = fixture(); String auth = token(f.actor(), "test-password");
        Map<String, Object> contact = contact(f.tenant(), "13100000601", "consented");
        long id = send(post("/api/v1/campaigns"), auth, body(contact.get("id"))).path("id").asLong();
        com.zaxxer.hikari.HikariDataSource pool = (com.zaxxer.hikari.HikariDataSource)source;
        int original = pool.getMaximumPoolSize();
        if (!store.lock().isEmpty()) pool.setMaximumPoolSize(4);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> {
                start.await();
                return send(post("/api/v1/campaigns/" + id + "/start?auto_dial=false"), auth, null);
            }));
            start.countDown();
            int created = 0;
            for (java.util.concurrent.Future<JsonNode> future : futures) created += future.get(15, java.util.concurrent.TimeUnit.SECONDS).path("created").asInt();
            assertEquals(1, created); assertEquals(1, store.count("callsession", "WHERE campaign_id=?", id));
        } finally { executor.shutdownNow(); pool.setMaximumPoolSize(original); }
    }

    @Test void overviewCapacityAndEvidenceReadFromPersistedTenantData() throws Exception {
        Identity f = fixture(); String auth = token(f.actor(), "test-password");
        store.insert("telephonyline", Values.map("tenant_id", f.tenant(), "name", "Test line", "provider", "mock", "max_concurrency", 2));
        Map<String, Object> call = store.insert("callsession", Values.map("tenant_id", f.tenant(), "phone", "13100000701", "mode", "ai_only", "status", "dialing"));
        store.insert("speechturn", Values.map("tenant_id", f.tenant(), "call_session_id", call.get("id"), "provider_event_key", "final", "is_final", true, "transcript", "test transcript"));
        store.insert("speechturn", Values.map("tenant_id", f.tenant(), "call_session_id", call.get("id"), "provider_event_key", "partial", "is_final", false));
        store.insert("callmetric", Values.map("tenant_id", f.tenant(), "call_session_id", call.get("id"), "stage", "ai.turn", "duration_ms", 123, "success", true));
        store.insert("recordingasset", Values.map("tenant_id", f.tenant(), "call_session_id", call.get("id")));
        store.insert("realtimesession", Values.map("tenant_id", f.tenant(), "call_session_id", call.get("id")));
        JsonNode overview = send(get("/api/v1/admin/system-overview"), auth, null);
        assertEquals(2, overview.path("capacity").path("effective_max_concurrent_calls").asInt());
        assertEquals(1, overview.path("capacity").path("available_slots").asInt());
        assertEquals(123, overview.path("operations").path("average_ai_turn_ms").asInt());
        assertEquals("mock", overview.path("services").path("telephony").asText());
        String path = "/api/v1/calls/" + call.get("id");
        assertEquals(2, send(get(path + "/speech-turns"), auth, null).size());
        assertEquals(1, send(get(path + "/speech-turns?final_only=true"), auth, null).size());
        assertEquals(1, send(get(path + "/metrics"), auth, null).size());
        assertEquals(1, send(get(path + "/recordings"), auth, null).size());
        assertEquals(call.get("id").toString(), send(get(path + "/realtime"), auth, null).path("call_session_id").asText());
        String otherAuth = token("admin", "12345678");
        mvc.perform(get(path + "/speech-turns").header("Authorization", otherAuth)).andExpect(status().isNotFound());
    }
}
