package com.duozhi.outbound;

import java.util.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=${JAVA_TEST_DATABASE_URL:jdbc:sqlite:file:java-auth-test?mode=memory&cache=shared}","spring.datasource.hikari.maximum-pool-size=1","JAVA_INITIALIZE_SCHEMA=true","JWT_SECRET=java-integration-test-secret-at-least-32-characters","DEMO_USERS_ENABLED=true"})
@AutoConfigureMockMvc
class ConversationIntegrationTest {
    private static final java.util.concurrent.atomic.AtomicLong TENANTS=new java.util.concurrent.atomic.AtomicLong(90000);
    @Autowired Store store;@Autowired Transactions tx;@Autowired JsonSupport json;@Autowired Config config;@Autowired Settings settings;
    @Autowired ConversationPolicy product;@Autowired ScriptFlows flows;@Autowired VoiceCallbacks callbacks;@Autowired CallLifecycle lifecycle;
    @Autowired Outbox outbox;@Autowired Handoffs handoffs;@Autowired DialService dial;@Autowired AiRuntime ai;@Autowired Contract contract;
    @Autowired TelephonyClient client;@Autowired CallbackInbox inbox;@Autowired CallAnalysis analysis;
    Map<String,Object> fixture(String mode){long tenant=TENANTS.incrementAndGet();store.insert("tenant",JsonSupport.map("id",tenant,"name","Conversation test","code",UUID.randomUUID().toString()));return store.insert("callsession",JsonSupport.map("tenant_id",tenant,"phone","13100002222","mode",mode,"attempts",1,"max_attempts",2,"status","answered"));}
    MockHttpServletRequest request(long tenant){MockHttpServletRequest r=new MockHttpServletRequest();r.setAttribute("identity",new Identity(tenant,null));return r;}
    Map<String,Object> say(Map<String,Object> call,String event,String text){Map<String,Object> body=contract.input("SpeechWebhookEvent",JsonSupport.map("call_id",call.get("id"),"event_id",event,"transcript",text,"attempt",call.get("attempts"),"is_final",true,"confidence",1.0),true);callbacks.handle("telephony_speech",body);Map<String,Object> rt=store.one("realtimesession","WHERE call_session_id=?",store.encode("realtimesession","call_session_id",call.get("id")));return JsonSupport.map("call_id",call.get("id"),"attempt",call.get("attempts"),"turn_sequence",rt.get("turn_sequence"),"speech_event_id",event,"transcript",text);}
    @Test void optOutStopsOtherQueuedCallsAndSurvivesRepeatedAiTask(){
        Map<String,Object> call=fixture("ai_only");long tenant=CallLifecycle.tenant(call);
        store.insert("contact",JsonSupport.map("tenant_id",tenant,"phone",call.get("phone"),"consent_state","consented"));
        Map<String,Object> queued=store.insert("callsession",JsonSupport.map("tenant_id",tenant,"phone",call.get("phone"),"mode","ai_only","status","queued"));
        Map<String,Object> payload=say(call,"optout","不要再联系我");ai.turn(payload);ai.turn(payload);
        assertEquals("completed",store.require("callsession",tenant,call.get("id")).get("status"));assertEquals("failed",store.require("callsession",tenant,queued.get("id")).get("status"));
        assertEquals(1,store.count("phonesuppression","WHERE tenant_id=?",tenant));assertTrue((Boolean)store.one("contact","WHERE tenant_id=?",tenant).get("dnc"));
        assertEquals(1,store.count("speechturn","WHERE tenant_id=? AND speaker_role='ai'",tenant));
        assertEquals("stop_contact",analysis.analyze(call.get("id"),1).get("result_code"));
    }
    @Test void callbackRequiresConfirmationAndKeepsOneDurableAppointment(){
        Map<String,Object> call=fixture("ai_only");long tenant=CallLifecycle.tenant(call);
        Map<String,Object> first=say(call,"request","明天下午三点回电");ai.turn(first);
        assertEquals(0,store.count("callbackappointment","WHERE tenant_id=?",tenant));
        Map<String,Object> confirm=say(call,"confirm","是的");ai.turn(confirm);ai.turn(confirm);
        assertEquals(1,store.count("callbackappointment","WHERE tenant_id=?",tenant));
        Map<String,Object> appointment=store.one("callbackappointment","WHERE tenant_id=?",tenant);assertEquals("confirmed",appointment.get("state"));
        assertEquals(1,store.count("taskoutbox","WHERE tenant_id=? AND idempotency_key=?",tenant,"appointment:"+appointment.get("id")+":"+appointment.get("revision")));
        assertEquals("completed",store.require("callsession",tenant,call.get("id")).get("status"));
    }
    @Test void publishedFlowIsImmutableAndFirstAnswerFollowsListenEdge(){
        Map<String,Object> call=fixture("ai_only");long tenant=CallLifecycle.tenant(call);MockHttpServletRequest r=request(tenant);
        Map<String,Object> template=store.insert("scripttemplate",JsonSupport.map("tenant_id",tenant,"name","Test","content","您好"));long tid=((Number)template.get("id")).longValue();
        Map<String,Object> version=flows.create(tid,Collections.emptyMap(),r);long vid=((Number)version.get("id")).longValue();
        assertTrue(((java.util.List<?>)((java.util.Map<?,?>)version.get("graph")).get("edges")).stream().allMatch(e->((java.util.Map<?,?>)e).get("keywords") instanceof java.util.List));
        flows.publish(tid,vid,r);assertThrows(ApiError.class,()->flows.update(tid,vid,JsonSupport.map("graph",version.get("graph")),r));
        store.update("callsession",tenant,call.get("id"),JsonSupport.map("script_flow_version_id",vid,"flow_node_key","start"));
        ai.turn(JsonSupport.map("call_id",call.get("id"),"attempt",1,"transcript",""));assertEquals("opening",store.require("callsession",tenant,call.get("id")).get("flow_node_key"));
        ai.turn(say(call,"answer","继续讲"));assertEquals("completed",store.require("callsession",tenant,call.get("id")).get("status"));
        assertEquals("hangup",store.require("callsession",tenant,call.get("id")).get("flow_node_key"));
    }
    @Test void handoffReservesAgentAndWaitsForProviderConnection(){
        Map<String,Object> call=fixture("ai_handoff");long tenant=CallLifecycle.tenant(call);
        Map<String,Object> agent=store.insert("user",JsonSupport.map("tenant_id",tenant,"username","agent-"+UUID.randomUUID(),"password_hash","unused","full_name","Agent","role","agent","agent_status","ready","last_seen_at",Store.now()));
        ai.turn(say(call,"human","转人工"));Map<String,Object> h=store.one("handoffrequest","WHERE tenant_id=?",tenant);assertNotNull(h);assertEquals(agent.get("id"),h.get("assigned_agent_id"));
        handoffs.transfer(new Identity(tenant,agent),call.get("id"),h.get("id"),agent.get("id"),"agent_accept");
        assertEquals("handoff_transferring",store.require("callsession",tenant,call.get("id")).get("status"));assertEquals("accepted",store.require("handoffrequest",tenant,h.get("id")).get("state"));
        lifecycle.status(JsonSupport.map("call_id",call.get("id"),"payload",JsonSupport.map("event_id","bridge","attempt",1,"status","human_connected")));
        assertEquals("in_human",store.require("callsession",tenant,call.get("id")).get("status"));
        lifecycle.status(JsonSupport.map("call_id",call.get("id"),"payload",JsonSupport.map("event_id","end","attempt",1,"status","completed")));assertEquals("ready",store.require("user",tenant,agent.get("id")).get("agent_status"));
    }
    @Test void unansweredHumanTransferRestartsWaitTimerAndReleasesAgent() {
        Map<String,Object> call=fixture("ai_handoff");long tenant=CallLifecycle.tenant(call);
        Map<String,Object> agent=store.insert("user",JsonSupport.map("tenant_id",tenant,"username","agent-"+UUID.randomUUID(),"password_hash","unused","full_name","Agent","role","agent","agent_status","ready","last_seen_at",Store.now()));
        ai.turn(say(call,"human-timeout","转人工"));Map<String,Object> h=store.one("handoffrequest","WHERE tenant_id=?",tenant);
        handoffs.transfer(new Identity(tenant,agent),call.get("id"),h.get("id"),agent.get("id"),"agent_accept");
        Map<String,Object> before=tx.run(()->product.state(store.require("callsession",tenant,call.get("id"))));
        lifecycle.status(JsonSupport.map("call_id",call.get("id"),"payload",JsonSupport.map("event_id","unavailable","attempt",1,"status","human_unavailable")));
        assertEquals("waiting_human",store.require("callsession",tenant,call.get("id")).get("status"));
        assertEquals("ready",store.require("user",tenant,agent.get("id")).get("agent_status"));
        Map<String,Object> after=store.require("conversationstate",tenant,before.get("id"));assertEquals("handoff",after.get("timer_kind"));assertNotNull(after.get("deadline"));assertTrue(CallLifecycle.number(after,"generation")>CallLifecycle.number(before,"generation"));
        assertNull(store.require("handoffrequest",tenant,h.get("id")).get("assigned_agent_id"));
    }
    @Test void lateModelReplyCannotSpeakAfterNewSpeech() throws Exception {
        Map<String,Object> call=fixture("ai_only");long tenant=CallLifecycle.tenant(call);
        java.util.concurrent.atomic.AtomicInteger speaks=new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/agent/turn",exchange->{say(call,"newer","不要再联系我");byte[] body="{\"action\":\"speak\",\"tts_text\":\"old reply\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try{
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort();Config cfg=new Config(new MockEnvironment().withProperty("AI_AGENT_URL",endpoint));
            tx.run(()->{tx.platform();Map<String,Object> state=product.state(call),policy=product.policy(state);Map<String,Object> a=settings.get(tenant,"ai");a.put("agent_url",endpoint);policy.put("_ai",a);store.update("conversationstate",tenant,state.get("id"),JsonSupport.map("policy_json",json.json(policy)));return null;});
            TelephonyClient tracked=new TelephonyClient(store,cfg,json){@Override public Map<String,Object> command(Map<String,Object> c,String action,Map<String,Object> fields){if("speak".equals(action))speaks.incrementAndGet();return super.command(c,action,fields);}};
            AiRuntime runtime=new AiRuntime(store,tx,json,cfg,settings,product,flows,tracked,callbacks,lifecycle,outbox,handoffs,dial);
            runtime.turn(say(call,"old","介绍一下服务"));assertEquals(0,speaks.get());assertEquals(0,store.count("speechturn","WHERE tenant_id=? AND speaker_role='ai'",tenant));
            ai.turn(JsonSupport.map("call_id",call.get("id"),"attempt",1,"turn_sequence",2,"transcript","不要再联系我"));assertEquals("completed",store.require("callsession",tenant,call.get("id")).get("status"));
        }finally{server.stop(0);}
    }
    @Test void callbackDateParsingRejectsAmbiguousTimes(){
        Instant now=Instant.parse("2026-10-15T02:00:00Z");assertNull(DialogueRules.callbackTime("明天三点回电",now,"Asia/Shanghai"));
        assertEquals(LocalDateTime.parse("2026-10-16T07:30:00"),DialogueRules.callbackTime("明天下午三点半回电",now,"Asia/Shanghai"));
        assertNull(DialogueRules.callbackTime("2026-02-30 15:00",now,"Asia/Shanghai"));
        assertEquals(UUID.fromString("da5b8893-d6ca-5c1c-9a9c-91f40a2a3649"),ConversationPolicy.uuid5("test"));
    }
    @Test void mediaCompletionDoesNotInvalidatePendingHangup() {
        Map<String,Object> call=fixture("ai_only");long tenant=CallLifecycle.tenant(call);
        TelephonyClient playback=new TelephonyClient(store,config,json){@Override public Map<String,Object> command(Map<String,Object> c,String action,Map<String,Object> fields){return "speak".equals(action)?JsonSupport.map("playback_id","goodbye-1"):super.command(c,action,fields);}};
        AiRuntime runtime=new AiRuntime(store,tx,json,config,settings,product,flows,playback,callbacks,lifecycle,outbox,handoffs,dial);
        runtime.turn(say(call,"end","不要再联系我"));
        Map<String,Object> continuation=store.one("taskoutbox","WHERE tenant_id=? AND idempotency_key LIKE 'after-playback:%'",tenant);assertNotNull(continuation);
        assertEquals("answered",store.require("callsession",tenant,call.get("id")).get("status"));
        callbacks.handle("telephony_media",contract.input("MediaWebhookEvent",JsonSupport.map("call_id",call.get("id"),"event_id","playback-done","state","listening","attempt",1,"event_sequence",1,"playback_id","goodbye-1"),true));
        runtime.afterPlayback(json.object(continuation.get("payload_json").toString()));
        assertEquals("completed",store.require("callsession",tenant,call.get("id")).get("status"));
    }
    @Test void unavailableAiAndTtsStillHangUpAndCreateOneWorkItem() {
        Map<String,Object> call=fixture("ai_only");long tenant=CallLifecycle.tenant(call);
        TelephonyClient broken=new TelephonyClient(store,config,json){
            @Override public Map<String,Object> post(String endpoint,byte[] body,Map<String,String> headers,int timeout){throw new TelephonyClient.Failure(503,false);}
            @Override public Map<String,Object> command(Map<String,Object> c,String action,Map<String,Object> fields){if("speak".equals(action))throw new TelephonyClient.Failure(503,false);return super.command(c,action,fields);}
        };
        AiRuntime runtime=new AiRuntime(store,tx,json,config,settings,product,flows,broken,callbacks,lifecycle,outbox,handoffs,dial);
        Map<String,Object> payload=say(call,"question","介绍一下服务");runtime.turn(payload);runtime.turn(payload);
        assertEquals("completed",store.require("callsession",tenant,call.get("id")).get("status"));
        assertEquals(1,store.count("productworkitem","WHERE tenant_id=? AND kind='service_failure'",tenant));
        assertEquals(1,store.count("callmetric","WHERE tenant_id=? AND error_code='AI_TURN_FAILED'",tenant));
    }

    @Test void staleCallReconciliationRetainsUnknownCapacityAndFencesNewAttempt() {
        Map<String,Object> call=fixture("ai_only");long tenant=CallLifecycle.tenant(call);
        store.jdbc().update("UPDATE callsession SET updated_at=? WHERE id=?",store.encode("callsession","updated_at",Store.now().minusHours(1)),store.encode("callsession","id",call.get("id")));
        TelephonyClient unknown=new TelephonyClient(store,config,json){@Override public Map<String,Object> command(Map<String,Object> c,String action,Map<String,Object> fields){return JsonSupport.map("ended",false);}};
        CallReconciliation pending=new CallReconciliation(store,tx,config,unknown,lifecycle);
        try {assertEquals(0,pending.reconcile(200));assertEquals("answered",store.require("callsession",tenant,call.get("id")).get("status"));assertTrue(store.require("callsession",tenant,call.get("id")).get("last_error").toString().contains("capacity retained"));}finally{pending.close();}
        store.jdbc().update("UPDATE callsession SET updated_at=? WHERE id=?",store.encode("callsession","updated_at",Store.now().minusHours(1)),store.encode("callsession","id",call.get("id")));
        TelephonyClient racing=new TelephonyClient(store,config,json){@Override public Map<String,Object> command(Map<String,Object> c,String action,Map<String,Object> fields){store.update("callsession",tenant,call.get("id"),JsonSupport.map("attempts",2,"status","dialing"));return JsonSupport.map("ended",true);}};
        CallReconciliation stale=new CallReconciliation(store,tx,config,racing,lifecycle);
        try {assertEquals(0,stale.reconcile(200));assertEquals("dialing",store.require("callsession",tenant,call.get("id")).get("status"));}finally{stale.close();}
        store.jdbc().update("UPDATE callsession SET updated_at=? WHERE id=?",store.encode("callsession","updated_at",Store.now().minusHours(1)),store.encode("callsession","id",call.get("id")));
        CallReconciliation confirmed=new CallReconciliation(store,tx,config,client,lifecycle);
        try {assertEquals(1,confirmed.reconcile(200));assertEquals("failed",store.require("callsession",tenant,call.get("id")).get("status"));}finally{confirmed.close();}
    }

    @Test void synchronousMediaCallbackBeforeSpeakResponseStillSchedulesHangup() {
        Map<String,Object> call=fixture("ai_only");long tenant=CallLifecycle.tenant(call);
        TelephonyClient racing=new TelephonyClient(store,config,json){@Override public Map<String,Object> command(Map<String,Object> c,String action,Map<String,Object> fields){
            if("speak".equals(action)){
                callbacks.handle("telephony_media",contract.input("MediaWebhookEvent",JsonSupport.map("call_id",call.get("id"),"event_id","fast-speaking","state","speaking","attempt",1,"event_sequence",1,"playback_id","fast-1"),true));
                callbacks.handle("telephony_media",contract.input("MediaWebhookEvent",JsonSupport.map("call_id",call.get("id"),"event_id","fast-listening","state","listening","attempt",1,"event_sequence",2,"playback_id",null),true));
                return JsonSupport.map("playback_id","fast-1");
            }return super.command(c,action,fields);
        }};
        AiRuntime runtime=new AiRuntime(store,tx,json,config,settings,product,flows,racing,callbacks,lifecycle,outbox,handoffs,dial);
        runtime.turn(say(call,"goodbye-fast","不要再联系我"));
        Map<String,Object> task=store.one("taskoutbox","WHERE tenant_id=? AND idempotency_key LIKE 'after-playback:%'",tenant);assertNotNull(task);
        assertEquals("listening",store.one("realtimesession","WHERE tenant_id=?",tenant).get("state"));runtime.afterPlayback(json.object(task.get("payload_json").toString()));assertEquals("completed",store.require("callsession",tenant,call.get("id")).get("status"));
    }

}
