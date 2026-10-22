package com.duozhi.outbound;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties={"spring.datasource.url=${JAVA_TEST_DATABASE_URL:jdbc:sqlite:file:java-auth-test?mode=memory&cache=shared}","spring.datasource.hikari.maximum-pool-size=1","JAVA_INITIALIZE_SCHEMA=true","JWT_SECRET=java-integration-test-secret-at-least-32-characters","DEMO_USERS_ENABLED=true"})
@AutoConfigureMockMvc
class DispatchIntegrationTest {
    private static final AtomicLong TENANTS=new AtomicLong(80000);
    @Autowired
    ConversationPolicy conversation; @Autowired
    AgentMedia media; @Autowired
    Store store; @Autowired
    Transactions tx; @Autowired
    JsonSupport json; @Autowired
    Config config;
    @Autowired
    Settings settings; @Autowired
    CallPolicy policy; @Autowired
    CallLifecycle lifecycle; @Autowired
    Outbox outbox;
    @Autowired
    Contract contract; @Autowired
    ObjectMapper mapper; @Autowired
    CallAnalysis analysis; @Autowired
    CallbackInbox inbox;
    @Autowired
    IntegrationTasks integrations; @Autowired
    AiRuntime ai; @Autowired
    VoiceCallbacks callbacks; @Autowired
    MockMvc mvc;
    Identity fixture() {
        long tenant=TENANTS.incrementAndGet();store.insert("tenant",Values.map("id",tenant,"name","Dispatch test","code",UUID.randomUUID().toString()));
        Map<String,Object> compliance=settings.defaults("compliance");compliance.put("allowed_start_hour",0);compliance.put("allowed_end_hour",0);compliance.put("min_attempt_interval_sec",0);
        store.insert("adminsetting",Values.map("tenant_id",tenant,"section","compliance","data_json",json.json(compliance)));
        return new Identity(tenant,null);
    }
    Config enabled() { return new Config(new MockEnvironment().withProperty("JAVA_DISPATCH_ENABLED","true").withProperty("OUTBOUND_PLATFORM_MAX_CONCURRENT","200")); }
    DialService dial(Config cfg) {return new DialService(store,tx,cfg,settings,policy,lifecycle,new TelephonyClient(store,cfg,json),outbox,json,media,new GatewayCluster(store,tx,cfg,json,new TelephonyClient(store,cfg,json),new StabilityAdmission(cfg,new ExpiringValues(cfg),json,inbox)),new CallbackInbox(store,tx,json,cfg,lifecycle,callbacks),conversation);}
    MockHttpServletRequest request(Identity id) {MockHttpServletRequest r=new MockHttpServletRequest();r.setAttribute("identity",id);return r;}
    Map<String,Object> call(Identity id,String status,int attempt) {return store.insert("callsession",JsonSupport.map("tenant_id",id.tenant(),"phone","13100001111","mode","human_only","status",status,"attempts",attempt,"max_attempts",3));}
    Map<String,Object> speech(Map<String,Object> call,String id,String text,boolean last) {
        return contract.input("SpeechWebhookEvent",JsonSupport.map("call_id",call.get("id"),"event_id",id,"transcript",text,"is_final",last,"attempt",call.get("attempts")),true);
    }
    @Test
    void durableCampaignDispatchCompletesAndLeavesUnselectedContactsPrepared() {
        Identity id=fixture();Config cfg=enabled();DialService dial=dial(cfg);
        Campaigns campaigns=new Campaigns(store,contract,policy,mapper,tx,dial,lifecycle);
        List<Object> contacts=new ArrayList<>();for(String phone:Arrays.asList("13100001101","13100001102"))contacts.add(store.insert("contact",JsonSupport.map("tenant_id",id.tenant(),"phone",phone,"consent_state","consented")).get("id"));
        Map<String,Object> campaign=tx.run(()->campaigns.create(JsonSupport.map("name","Worker lifecycle","mode","human_only","contact_ids",contacts,"retry_limit",0),request(id)));
        long cid=((Number)campaign.get("id")).longValue();
        Map<String,Object> start=campaigns.start(cid,request(id),true,true,1);
        assertEquals("queued",((Map<?,?>)start.get("dispatch_result")).get("status"));assertEquals(1,start.get("auto_dial_count"));
        TaskWorker worker=new TaskWorker(store,tx,cfg,json,outbox,dial,lifecycle,analysis,inbox,ai,integrations);
        try {
            assertTrue(worker.enqueueDue()>0);for(int n=0;n<100&&worker.processOne(id.tenant());n++) { }
            Map<String,Object> current=store.one("callsession","WHERE campaign_id=? AND status='DIALING'",cid);assertNotNull(current);assertEquals(1,current.get("attempts"));
            store.jdbc().update("UPDATE taskoutbox SET available_at=? WHERE tenant_id=? AND task_type='mock_status'",store.encode("taskoutbox","available_at",Store.now().minusSeconds(1)),id.tenant());
            for(int n=0;n<100&&worker.processOne(id.tenant());n++) { }
            assertEquals("completed",store.require("callsession",id.tenant(),current.get("id")).get("status"));
            assertNotNull(store.one("callanalysis","WHERE call_session_id=?",store.encode("callanalysis","call_session_id",current.get("id"))));
            assertEquals("prepared",store.require("campaign",id.tenant(),cid).get("status"));
            tx.run(()->campaigns.resume(cid,request(id)));assertEquals(1,store.count("callsession","WHERE campaign_id=? AND status='QUEUED'",cid));
            tx.run(()->campaigns.pause(cid,request(id)));assertFalse(dial.dispatch(store.one("callsession","WHERE campaign_id=? AND status='QUEUED'",cid).get("id")));
            tx.run(()->campaigns.stop(cid,request(id)));assertEquals(0,store.count("callsession","WHERE campaign_id=? AND status='QUEUED'",cid));
        }finally{worker.close();}
    }
    @Test
    void dispatchRequiresLiveCallbackConsumerAndFreezesPolicyAtAdmission() {
        Identity id=fixture(); Map<String,Object> call=call(id,"queued",0);
        store.insert("contact",JsonSupport.map("tenant_id",id.tenant(),"phone",call.get("phone"),"consent_state","consented"));
        Config cfg=new Config(new MockEnvironment().withProperty("JAVA_DISPATCH_ENABLED","true").withProperty("OUTBOUND_PLATFORM_MAX_CONCURRENT","200").withProperty("CALLBACK_INBOX_ENABLED","true").withProperty("CALLBACK_INBOX_WORKER_TTL_SEC","0").withProperty("CALLBACK_INBOX_MAX_AGE_SEC","0.5"));
        assertFalse(dial(cfg).dispatch(call.get("id")));
        assertEquals(0,store.require("callsession",id.tenant(),call.get("id")).get("attempts"));
        assertTrue(dial(enabled()).dispatch(call.get("id")));
        Map<String,Object> state=store.one("conversationstate","WHERE call_id=? AND attempt=1",store.encode("conversationstate","call_id",call.get("id")));
        assertNotNull(state);Object originalTimezone=conversation.policy(state).get("timezone");
        store.insert("scenarioversion",JsonSupport.map("tenant_id",id.tenant(),"policy_json",json.json(JsonSupport.map("timezone","UTC"))));
        assertEquals(originalTimezone,conversation.policy(tx.run(()->conversation.state(store.require("callsession",id.tenant(),call.get("id"))))).get("timezone"));
        assertEquals(state.get("id"),tx.run(()->conversation.state(store.require("callsession",id.tenant(),call.get("id")))).get("id"));
    }
    @Test
    void speechIsDeduplicatedAndInvalidatesGenerationWithoutReopeningClosedMedia() throws Exception {
        Identity id=fixture();Map<String,Object> call=call(id,"answered",1);
        Map<String,Object> state=store.insert("conversationstate",JsonSupport.map("tenant_id",id.tenant(),"call_id",call.get("id"),"attempt",1,"generation",3,"deadline",Store.now().plusSeconds(30)));
        Map<String,Object> body=speech(call,"speech-1","  请   不要再打电话  ",true);
        byte[] raw=mapper.writeValueAsBytes(body);
        mvc.perform(post("/api/v1/webhooks/telephony/speech").contentType("application/json").content(raw)).andExpect(status().isOk()).andExpect(jsonPath("duplicate").value(false));
        mvc.perform(post("/api/v1/webhooks/telephony/speech").contentType("application/json").content(raw)).andExpect(status().isOk()).andExpect(jsonPath("duplicate").value(true));
        assertEquals(1,store.count("speechturn","WHERE tenant_id=?",id.tenant()));
        Map<String,Object> saved=store.require("conversationstate",id.tenant(),state.get("id"));assertEquals(4,saved.get("generation"));assertNull(saved.get("deadline"));
        Map<String,Object> rt=store.one("realtimesession","WHERE tenant_id=?",id.tenant());assertEquals(1,rt.get("turn_sequence"));
        store.update("callsession",id.tenant(),call.get("id"),JsonSupport.map("attempts",2));
        assertEquals("stale_attempt",callbacks.handle("telephony_speech",speech(call,"speech-old","old",true)).get("reason"));
        assertEquals(1,store.count("speechturn","WHERE tenant_id=?",id.tenant()));
    }
    @Test
    void mediaSequenceFencesAndLateRecordingCannotReplaceCurrentAttempt() {
        Identity id=fixture();Map<String,Object> call=call(id,"answered",2);
        Map<String,Object> m=contract.input("MediaWebhookEvent",JsonSupport.map("call_id",call.get("id"),"event_id","m-2","state","speaking","attempt",2,"event_sequence",2,"playback_id","p2"),true);
        callbacks.handle("telephony_media",m);
        Map<String,Object> older=new LinkedHashMap<>(m);older.put("event_id","m-1");older.put("event_sequence",1);older.put("state","listening");
        assertEquals("speaking",callbacks.handle("telephony_media",older).get("state"));
        Map<String,Object> closed=new LinkedHashMap<>(m);closed.put("event_id","m-3");closed.put("event_sequence",3);closed.put("state","closed");callbacks.handle("telephony_media",closed);
        m.put("event_id","m-4");m.put("event_sequence",4);assertEquals("closed",callbacks.handle("telephony_media",m).get("state"));
        Map<String,Object> rec=JsonSupport.map("call_id",call.get("id"),"payload",JsonSupport.map("url","https://recordings.example/current.wav","attempt",2,"event_id","r-2"));
        callbacks.handle("telephony_recording",rec);
        rec.put("payload",JsonSupport.map("url","https://recordings.example/old.wav","attempt",1,"event_id","r-1"));callbacks.handle("telephony_recording",rec);
        assertEquals("https://recordings.example/current.wav",store.require("callsession",id.tenant(),call.get("id")).get("recording_url"));
        assertEquals(2,store.count("recordingasset","WHERE tenant_id=?",id.tenant()));
        rec.put("payload",JsonSupport.map("url","https://recordings.example/ambiguous.wav"));assertEquals("ambiguous_attempt",callbacks.handle("telephony_recording",rec).get("reason"));
    }
    @Test
    void failedHttpAdmissionDoesNotConsumeAttemptAndUnknownOutcomeKeepsReservation() throws Exception {
        Identity id=fixture();Map<String,Object> call=call(id,"queued",0);
        store.insert("contact",JsonSupport.map("tenant_id",id.tenant(),"phone",call.get("phone"),"consent_state","consented"));
        AtomicInteger code=new AtomicInteger(429);
        AtomicBoolean signed=new AtomicBoolean();
        AtomicReference<Map<String,Object>> sent=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/call/dial",exchange->{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buf=new byte[1024];int read;while((read=exchange.getRequestBody().read(buf))!=-1)bytes.write(buf,0,read);
            sent.set(json.object(new String(bytes.toByteArray(),StandardCharsets.UTF_8)));
            String stamp=exchange.getRequestHeaders().getFirst("x-voice-timestamp"),nonce=exchange.getRequestHeaders().getFirst("x-voice-nonce");
            String expected=JsonSupport.hmac("test-command-secret",(stamp+"."+nonce+"./v1/call/dial."+new String(bytes.toByteArray(),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
            signed.set(expected.equals(exchange.getRequestHeaders().getFirst("x-voice-signature")));
            if(code.get()==429)exchange.getResponseHeaders().add("X-Voice-Dial-Admitted","false");
            byte[] result="{}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code.get(),result.length);exchange.getResponseBody().write(result);exchange.close();
        });server.start();
        try {
            Config cfg=new Config(new MockEnvironment().withProperty("JAVA_DISPATCH_ENABLED","true").withProperty("OUTBOUND_PLATFORM_MAX_CONCURRENT","200").withProperty("TELEPHONY_PROVIDER","http").withProperty("TELEPHONY_PROVIDER_ENDPOINT","http://127.0.0.1:"+server.getAddress().getPort()).withProperty("VOICE_COMMAND_SECRET","test-command-secret"));
            DialService dial=dial(cfg);assertFalse(dial.dispatch(call.get("id")));assertTrue(signed.get());
            Map<?,?> sentPolicy=(Map<?,?>)((Map<?,?>)sent.get().get("metadata")).get("scenario_policy");assertNotNull(sentPolicy);assertTrue(sentPolicy.containsKey("timezone"));assertFalse(sentPolicy.containsKey("_ai"));
            Map<String,Object> pending=store.require("callsession",id.tenant(),call.get("id"));assertEquals(0,pending.get("attempts"));assertEquals("queued",pending.get("status"));
            code.set(503);assertTrue(dial.dispatch(call.get("id")));pending=store.require("callsession",id.tenant(),call.get("id"));assertEquals(1,pending.get("attempts"));assertEquals("dialing",pending.get("status"));assertTrue(pending.get("last_error").toString().contains("unknown"));
            assertFalse(dial.dispatch(call.get("id")));assertEquals(1,store.require("callsession",id.tenant(),call.get("id")).get("attempts"));
        }finally{server.stop(0);}
    }
}
