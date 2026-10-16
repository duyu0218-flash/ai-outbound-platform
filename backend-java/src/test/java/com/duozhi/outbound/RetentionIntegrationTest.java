package com.duozhi.outbound;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=${JAVA_TEST_DATABASE_URL:jdbc:sqlite:file:java-auth-test?mode=memory&cache=shared}","spring.datasource.hikari.maximum-pool-size=1","JAVA_INITIALIZE_SCHEMA=true","JWT_SECRET=java-integration-test-secret-at-least-32-characters","DEMO_USERS_ENABLED=true"})
@AutoConfigureMockMvc
class RetentionIntegrationTest {
    @Autowired Store store;@Autowired DataRetention retention;@Autowired Outbox outbox;@Autowired JsonSupport json;@Autowired Settings settings;
    private static final java.util.concurrent.atomic.AtomicLong IDS=new java.util.concurrent.atomic.AtomicLong(120000);
    long tenant(){long id=IDS.incrementAndGet();store.insert("tenant",JsonSupport.map("id",id,"name","Retention fixture","code",UUID.randomUUID().toString()));Map<String,Object> policy=settings.get(id,"compliance");policy.put("call_sensitive_data_retention_days",1);policy.put("final_transcript_retention_days",1);store.insert("adminsetting",JsonSupport.map("tenant_id",id,"section","compliance","data_json",json.json(policy)));return id;}
    @Test void expiredTextIsRedactedButRecordingLocationSurvivesUntilRemoteDeletion(){
        long tenant=tenant();Map<String,Object> call=store.insert("callsession",JsonSupport.map("tenant_id",tenant,"phone","13100007777","mode","ai_only","status","completed","last_transcript","private transcript","summary","private summary","finished_at",Store.now().minusDays(3)));
        store.insert("speechturn",JsonSupport.map("tenant_id",tenant,"call_session_id",call.get("id"),"provider_event_key","retention-final","transcript","private transcript","is_final",true,"created_at",Store.now().minusDays(3)));
        store.insert("conversationstate",JsonSupport.map("tenant_id",tenant,"call_id",call.get("id"),"attempt",1,"data_json","{\"private\":true}","policy_json","{\"private\":true}"));
        Map<String,Object> asset=store.insert("recordingasset",JsonSupport.map("tenant_id",tenant,"call_session_id",call.get("id"),"provider_url","https://example.invalid/private.wav","storage_uri","test://private.wav","retention_until",Store.now().minusHours(1)));
        Map<String,Object> task=outbox.enqueue(tenant,"business_callback",call.get("id"),"retention-callback:"+call.get("id"),JsonSupport.map("private","payload"));
        retention.purge(500);Map<String,Object> redacted=store.require("callsession",tenant,call.get("id"));assertTrue(redacted.get("phone").toString().startsWith("redacted:"));assertNull(redacted.get("last_transcript"));assertEquals(0,store.count("speechturn","WHERE tenant_id=?",tenant));assertEquals("{}",store.one("conversationstate","WHERE tenant_id=?",tenant).get("data_json"));
        Map<String,Object> recording=store.require("recordingasset",tenant,asset.get("id"));assertEquals("deletion_pending",recording.get("state"));assertEquals("test://private.wav",recording.get("storage_uri"));assertNull(recording.get("deleted_at"));
        assertEquals("dead",store.require("taskoutbox",tenant,task.get("id")).get("state"));assertEquals("{}",store.require("taskoutbox",tenant,task.get("id")).get("payload_json"));
        retention.purge(500);assertEquals(1,store.count("taskoutbox","WHERE tenant_id=? AND task_type='recording_delete'",tenant));
    }
    @Test void compactionPreservesIdempotencyReceiptAndKeepsFailedWork(){
        long tenant=tenant();Map<String,Object> call=store.insert("callsession",JsonSupport.map("tenant_id",tenant,"phone","redacted:fixture","mode","human_only","status","completed","finished_at",Store.now().minusDays(200)));
        String key="archive:"+call.get("id");Map<String,Object> done=outbox.enqueue(tenant,"call_analysis",call.get("id"),key,Collections.emptyMap());
        store.jdbc().update("UPDATE taskoutbox SET state='COMPLETED',updated_at=? WHERE id=?",store.encode("taskoutbox","updated_at",Store.now().minusDays(200)),store.encode("taskoutbox","id",done.get("id")));
        Map<String,Object> failed=outbox.enqueue(tenant,"call_analysis",call.get("id"),key+":failed",Collections.emptyMap());store.update("taskoutbox",tenant,failed.get("id"),JsonSupport.map("state","dead"));
        retention.archive(500);assertEquals(0,store.count("taskoutbox","WHERE id=?",store.encode("taskoutbox","id",done.get("id"))));assertNotNull(store.one("taskreceipt","WHERE idempotency_key=?",key));assertEquals("dead",store.require("taskoutbox",tenant,failed.get("id")).get("state"));
        Map<String,Object> replay=outbox.enqueue(tenant,"call_analysis",call.get("id"),key,Collections.emptyMap());assertEquals(done.get("id"),replay.get("id"));assertEquals(0,store.count("taskoutbox","WHERE idempotency_key=?",key));
    }
}
