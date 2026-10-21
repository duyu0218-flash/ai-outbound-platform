package com.duozhi.outbound;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DialV5ProtocolIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET",()->"v5-protocol-isolated-fixture-secret-more-than-32-characters");
        p.add("spring.datasource.url",MYSQL::getJdbcUrl);p.add("spring.datasource.username",MYSQL::getUsername);p.add("spring.datasource.password",MYSQL::getPassword);
        p.add("spring.flyway.url",MYSQL::getJdbcUrl);p.add("spring.flyway.user",()->"root");p.add("spring.flyway.password",MYSQL::getPassword);
        p.add("spring.data.redis.host",REDIS::getHost);p.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        p.add("REDIS_URL",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
        p.add("JAVA_WORKERS_ENABLED",()->"false");p.add("JAVA_DISPATCH_ENABLED",()->"true");
        p.add("TELEPHONY_PROVIDER",()->"mock");p.add("spring.ai.model.chat",()->"none");
        p.add("JAVA_CALLBACK_TERMINAL_BATCH_ENABLED",()->"true");
        p.add("JAVA_CAPACITY_EVIDENCE_ENABLED",()->"true");p.add("JAVA_CAPACITY_RUN_ID",()->"v5-isolated-protocol");
    }
    @Autowired Store store;@Autowired Settings settings;@Autowired JsonSupport json;
    @Autowired DialService dial;@Autowired DialDrainControl drain;@Autowired DialBatchStore batch;
    @Autowired CallLifecycle lifecycle;@Autowired Transactions tx;
    @Autowired CallbackInbox inbox;
    @Autowired CapacityCommitEvidence timing;@Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    @org.springframework.boot.test.mock.mockito.SpyBean TelephonyClient telephony;

    @BeforeEach void seed() {
        drain.cancelDrain();
        if(store.one("tenant","findById",Map.of("id",1))==null)store.insert("tenant",JsonSupport.map("id",1,"code","v5","name","Isolated v5 protocol"));
        var policy=new LinkedHashMap<>(settings.get(1,"compliance"));
        policy.put("allowed_start_hour",0);policy.put("allowed_end_hour",0);policy.put("timezone","UTC");
        policy.put("require_explicit_consent",false);policy.put("require_explicit_consent_for_direct_calls",false);
        var row=store.one("adminsetting","findBySection",JsonSupport.map("tenantId",1,"section","compliance"));
        if(row==null)store.insert("adminsetting",JsonSupport.map("tenant_id",1,"section","compliance","data_json",json.json(policy)));
        else store.update("adminsetting",1,row.get("id"),JsonSupport.map("data_json",json.json(policy)));
    }
    @AfterEach void cleanup() { reset(telephony);drain.cancelDrain(); }
    Map<String,Object> queued() {
        return store.insert("callsession",JsonSupport.map("tenant_id",1,"phone","+86139"+String.format("%08d",Math.abs(UUID.randomUUID().getLeastSignificantBits()%100000000L)),"mode","ai_only","status","queued","max_attempts",5));
    }
    Map<String,Object> claim(Map<String,Object> call) { return ReflectionTestUtils.invokeMethod(dial,"claim",call.get("id")); }
    Map<String,Object> metadata(Map<String,Object> call) { return ReflectionTestUtils.invokeMethod(dial,"metadataFor",call); }

    @Test void reservedUnsentTaskMustCompleteBeforeClosingSends() {
        var claimed=claim(queued());assertThat(claimed).isNotNull();
        assertThat(drain.begin()).containsEntry("phase","ADMISSION_CLOSED");
        var waiting=queued();assertThat(claim(waiting)).isNull();
        assertThatThrownBy(drain::closeSending).isInstanceOf(ApiError.class).hasMessageContaining("reserved");
        assertThat(dial.sendClaimed(claimed,metadata(claimed))).isTrue();
        assertThat(drain.closeSending()).containsEntry("phase","SEND_CLOSED");
        assertThat(drain.sending(claimed)).isFalse();
        lifecycle.status(JsonSupport.map("call_id",claimed.get("id"),"payload",JsonSupport.map("attempt",1,"status","completed","event_id","v5-terminal-"+claimed.get("id"))));
        assertThat(store.require("callsession",1,claimed.get("id")).get("status")).isEqualTo("completed");
        assertThat(drain.snapshot()).containsEntry("certificate_issued",false);
    }
    @Test void pendingGatewayResponseCannotFinishTheTaskOrCreateAcceptance() {
        var claimed=claim(queued());
        doReturn(Map.of("result","pending_reconciliation","provider_call_id","pending-v5")).when(telephony).command(anyMap(),eq("dial"),anyMap());
        assertThatThrownBy(()->dial.sendClaimed(claimed,metadata(claimed))).isInstanceOf(DialService.PendingConfirmation.class);
        assertThat(store.scalar("callevent","dialAcceptedAttempt",JsonSupport.map("tenantId",1,"id",store.encode("callsession","id",claimed.get("id")),"attempt",1))).isZero();
        assertThat(store.require("callsession",1,claimed.get("id"))).containsEntry("status","dialing");
        drain.begin();assertThatThrownBy(drain::closeSending).isInstanceOf(ApiError.class);
        lifecycle.status(JsonSupport.map("call_id",claimed.get("id"),"payload",JsonSupport.map("attempt",1,"status","failed","event_id","v5-fixture-cleanup-"+claimed.get("id"))));
    }
    @Test void lateAcceptanceRecordsTheOriginalAttemptWithoutChangingTheNewOne() {
        var original=claim(queued());
        store.update("callsession",1,original.get("id"),JsonSupport.map("attempts",2,"status","queued","telephony_call_id","new-attempt-id"));
        var entry=JsonSupport.map("call",original,"response",JsonSupport.map("result","accepted","provider_call_id","old-attempt-id"));
        dial.acknowledgeTasks(List.of(entry),batch);
        assertThat(store.require("callsession",1,original.get("id"))).containsEntry("attempts",2).containsEntry("telephony_call_id","new-attempt-id");
        assertThat(store.scalar("callevent","dialAcceptedAttempt",JsonSupport.map("tenantId",1,"id",store.encode("callsession","id",original.get("id")),"attempt",1))).isEqualTo(1);
        dial.acknowledgeTasks(List.of(entry),batch);
        assertThat(store.scalar("callevent","dialAcceptedAttempt",JsonSupport.map("tenantId",1,"id",store.encode("callsession","id",original.get("id")),"attempt",1))).isEqualTo(1);
    }
    @Test void rolledBackDrainDoesNotLeaveACapturedCohortOrClosedGate() {
        assertThatThrownBy(()->tx.serialized(()->{drain.begin();throw new IllegalStateException("injected rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(drain.snapshot()).containsEntry("phase","RUNNING");
    }
    @Test void originalAttemptCanBeConfirmedReadOnlyAfterTheCallHasAdvanced() {
        var original=claim(queued());var frozen=metadata(original);
        store.update("callsession",1,original.get("id"),JsonSupport.map("attempts",2,"status","queued","telephony_call_id","current-uuid"));
        doReturn(Map.of("result","accepted","provider_call_id","previous-uuid")).when(telephony).command(anyMap(),eq("dial-confirmation"),anyMap());
        assertThat(dial.confirmClaimed(original,frozen)).isTrue();
        verify(telephony,never()).command(anyMap(),eq("dial"),anyMap());
        assertThat(store.require("callsession",1,original.get("id"))).containsEntry("attempts",2).containsEntry("status","queued").containsEntry("telephony_call_id","current-uuid");
    }
    @Test void pendingCommandKeepsItsOriginalProviderAndEndpointAfterLineConfigurationChanges(){
        String property="v5-route-fixture",secret="independent-route-snapshot-fixture-secret-32-plus";
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(property,Map.of("VOICE_COMMAND_SECRET",secret)));
        var line=store.insert("telephonyline",JsonSupport.map("tenant_id",1,"name","Frozen route fixture","provider","http","gateway_url","http://127.0.0.1:1","max_concurrency",10));
        Map<String,Object> original=null;
        try{
            original=claim(queued());assertThat(original).isNotNull();assertThat(telephony.mock(original)).isFalse();
            assertThat(jdbc.queryForObject("SELECT frozen_payload FROM dial_command_journal WHERE call_id=?",String.class,original.get("id"))).doesNotContain(secret);
            store.update("telephonyline",1,line.get("id"),JsonSupport.map("provider","mock","gateway_url","http://127.0.0.1:2"));
            assertThat(telephony.mock(original)).isFalse();
            // A reconstructed request must use the same persisted route, too.
            assertThat(telephony.mock(store.require("callsession",1,original.get("id")))).isFalse();
        }finally{
            if(original!=null)lifecycle.status(JsonSupport.map("call_id",original.get("id"),"payload",JsonSupport.map("attempt",1,"status","failed","event_id","route-fixture-cleanup-"+original.get("id"))));
            store.update("telephonyline",1,line.get("id"),JsonSupport.map("enabled",false));environment.getPropertySources().remove(property);
        }
    }
    @Test void mixedTerminalBatchPreservesFifoAndCommitsEverySideEffectOnce() {
        inbox.initialize();inbox.maintenance();var calls=new ArrayList<Map<String,Object>>();
        for(int i=0;i<3;i++){
            var call=claim(queued());calls.add(call);
            for(String state:List.of("dialing","completed"))inbox.receive("telephony_status",JsonSupport.map("call_id",call.get("id"),"payload",JsonSupport.map("status",state,"attempt",1,"event_id","terminal-batch:"+call.get("id")+":"+state)));
        }
        int consumed=0;for(int round=0;round<10;round++)consumed+=inbox.consumeBatch(100);
        assertThat(consumed).isEqualTo(6);
        for(var call:calls)assertThat(store.require("callsession",1,call.get("id"))).containsEntry("status","completed");
        assertThat(inbox.consumeBatch(100)).isZero();
    }
    @Test void conservativeCommitEvidenceIsUniqueAndRollsBackWithItsFact(){
        String rollbackId=UUID.randomUUID().toString();
        assertThatThrownBy(()->tx.run(()->{timing.record("ack",1,rollbackId,1);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capacity_identity_evidence WHERE call_id=?",Long.class,rollbackId)).isZero();
        var original=claim(queued());assertThat(dial.sendClaimed(original,metadata(original))).isTrue();
        var evidence=jdbc.queryForMap("SELECT e.transaction_id,t.lo_us,t.hi_us,t.clock_valid FROM capacity_identity_evidence e LEFT JOIN capacity_commit_evidence t ON t.transaction_id=e.transaction_id WHERE e.evidence_kind='ack' AND e.tenant_id=1 AND e.call_id=? AND e.attempt=1",original.get("id"));
        assertThat(evidence.get("lo_us")).isNotNull();assertThat(((Number)evidence.get("hi_us")).longValue()).isGreaterThanOrEqualTo(((Number)evidence.get("lo_us")).longValue());
        assertThat(evidence.get("clock_valid").toString()).isIn("true","1");
        var entry=JsonSupport.map("call",original,"response",JsonSupport.map("result","accepted","provider_call_id","mock-"+original.get("id")));
        dial.acknowledgeTasks(List.of(entry),batch);
        assertThat(jdbc.queryForObject("SELECT transaction_id FROM capacity_identity_evidence WHERE evidence_kind='ack' AND tenant_id=1 AND call_id=? AND attempt=1",String.class,original.get("id"))).isEqualTo(evidence.get("transaction_id"));
    }
    @Test void plainRejectionHeaderCannotReleaseAnAlreadyIssuedIdentity(){
        var original=claim(queued());
        doThrow(new TelephonyClient.Failure(429,true)).when(telephony).command(anyMap(),eq("dial"),anyMap());
        assertThatThrownBy(()->dial.sendClaimed(original,metadata(original))).isInstanceOf(DialService.PendingConfirmation.class);
        assertThat(store.require("callsession",1,original.get("id"))).containsEntry("status","dialing").containsEntry("attempts",1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dial_reservation WHERE call_id=? AND attempt=1 AND state='RESERVED'",Long.class,original.get("id"))).isEqualTo(1);
        lifecycle.status(JsonSupport.map("call_id",original.get("id"),"payload",JsonSupport.map("attempt",1,"status","failed","event_id","isolated-header-test-cleanup")));
    }
}
