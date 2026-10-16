package com.duozhi.outbound;

import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=${JAVA_TEST_DATABASE_URL:jdbc:sqlite:file:java-auth-test?mode=memory&cache=shared}","spring.datasource.hikari.maximum-pool-size=1","JAVA_INITIALIZE_SCHEMA=true","JWT_SECRET=java-integration-test-secret-at-least-32-characters","DEMO_USERS_ENABLED=true"})
@AutoConfigureMockMvc
@org.junit.jupiter.api.Timeout(30)
class OperationsIntegrationTest {
    private static final java.util.concurrent.atomic.AtomicLong TENANTS = new java.util.concurrent.atomic.AtomicLong(100000);
    @Autowired Store store; @Autowired Transactions tx; @Autowired JsonSupport json; @Autowired ProductOperations product;
    @Autowired Reports reports; @Autowired ContactFiles files; @Autowired AgentMedia media; @Autowired AuthService auth;
    @Autowired Crypto crypto; @Autowired MockMvc mvc; @Autowired Contract contract; @Autowired Settings settings;
    @Autowired AdminOperations admin; @Autowired TaskWorker worker; @Autowired Config config;
    Identity fixture(String role) {
        long tenant = TENANTS.incrementAndGet(); store.insert("tenant", JsonSupport.map("id", tenant, "name", "Operations test", "code", UUID.randomUUID().toString()));
        Map<String,Object> user = store.insert("user", JsonSupport.map("tenant_id", tenant, "username", "ops-"+UUID.randomUUID(), "password_hash", "unused", "full_name", "Test", "role", role));
        return new Identity(tenant, user);
    }
    MockHttpServletRequest request(Identity i) { MockHttpServletRequest r=new MockHttpServletRequest();r.setAttribute("identity",i);return r; }
    @SuppressWarnings("unchecked") Map<String,Object> object(Object o) { return (Map<String,Object>)o; }
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Object o) { return (List<Map<String,Object>>)o; }
    Map<String,Object> call(Identity i, String status, int attempts) { return store.insert("callsession", JsonSupport.map("tenant_id", i.tenant(), "phone", "13100003333", "mode", "ai_only", "status", status, "attempts", attempts)); }
    @Test void policyPublishConflictSimulationRollbackAndKnowledgeSnapshot() {
        Identity i=fixture("admin");MockHttpServletRequest r=request(i);
        Map<String,Object> knowledge=product.importKnowledge(JsonSupport.map("title","营业时间","content","工作日九点开始营业","keywords","营业时间"),r);
        Map<String,Object> saved=product.savePolicy(JsonSupport.map("policy",JsonSupport.map("start_hour",0,"end_hour",24)),r);
        assertEquals(saved.get("version_id"),product.policy(null,r).get("version_id"));
        assertEquals(409,assertThrows(ApiError.class,()->product.savePolicy(JsonSupport.map("policy",Collections.emptyMap()),r)).status);
        assertEquals(1,product.knowledge(JsonSupport.map("query","营业时间"),r).size());
        Map<String,Object> version=store.require("scenarioversion",i.tenant(),saved.get("version_id"));assertEquals(knowledge.get("title"),rows(json.object(version.get("policy_json").toString()).get("_knowledge")).get(0).get("title"));
        long tenants=store.count("tenant",""),calls=store.count("callsession",""),tasks=store.count("taskoutbox","");
        Map<String,Object> simulation=product.simulate(JsonSupport.map("policy",Collections.emptyMap(),"utterances",Arrays.asList("不要再联系我")),r);
        assertEquals(false,simulation.get("external_actions_executed"));assertEquals("hangup",rows(simulation.get("trace")).get(0).get("action"));
        assertEquals(tenants,store.count("tenant",""));assertEquals(calls,store.count("callsession",""));assertEquals(tasks,store.count("taskoutbox",""));
        assertNotNull(product.funnel(7,r).get("groups"));
        assertEquals(403,assertThrows(ApiError.class,()->product.policy(null,request(fixture("agent")))).status);
    }
    @Test void incomingOptOutIsIdempotentAndWorkAssignmentIsTenantBound() {
        Identity i=fixture("admin"),other=fixture("admin");MockHttpServletRequest r=request(i);Map<String,Object> c=call(i,"queued",0);
        Map<String,Object> input=JsonSupport.map("event_id","sms-1","phone",c.get("phone"),"kind","sms_reply","text","不要再联系我");
        Map<String,Object> result=product.incoming(input,r);assertEquals(false,result.get("duplicate"));assertEquals(true,product.incoming(input,r).get("duplicate"));
        assertEquals("failed",store.require("callsession",i.tenant(),c.get("id")).get("status"));assertEquals(1,store.count("phonesuppression","WHERE tenant_id=?",i.tenant()));
        input.put("text","换内容");assertEquals(409,assertThrows(ApiError.class,()->product.incoming(input,r)).status);
        UUID id=UUID.fromString(result.get("item_id").toString());
        assertEquals(404,assertThrows(ApiError.class,()->product.work(id,JsonSupport.map("state","in_progress","assigned_to",other.userId()),r)).status);
        product.work(id,JsonSupport.map("state","completed","assigned_to",i.userId()),r);assertEquals("completed",product.work(1,r).get(0).get("state"));
    }
    @Test void appointmentRevisionPreventsLostUpdatesAndCancellation() {
        Identity i=fixture("admin");MockHttpServletRequest r=request(i);Map<String,Object> c=call(i,"completed",1);
        product.savePolicy(JsonSupport.map("policy",JsonSupport.map("start_hour",0,"end_hour",24)),r);
        Map<String,Object> a=store.insert("callbackappointment",JsonSupport.map("tenant_id",i.tenant(),"source_call_id",c.get("id"),"request_key",UUID.randomUUID().toString(),"scheduled_at",Store.now().plusDays(1),"state","confirmed"));UUID id=UUID.fromString(a.get("id").toString());int rev=CallLifecycle.number(a,"revision");
        product.appointment(id,JsonSupport.map("revision",rev,"scheduled_at",Store.now().plusDays(2).atOffset(ZoneOffset.UTC).toString()),r);
        assertEquals(1,store.count("taskoutbox","WHERE tenant_id=? AND task_type='after_playback'",i.tenant()));
        assertEquals(409,assertThrows(ApiError.class,()->product.appointment(id,JsonSupport.map("revision",rev,"cancel",true),r)).status);
        product.appointment(id,JsonSupport.map("revision",rev+1,"cancel",true),r);assertEquals("cancelled",product.appointments(1,r).get(0).get("state"));
    }
    @Test void csvImportReplayErrorsAndSafeExport() throws Exception {
        Identity i=fixture("admin");MockHttpServletRequest r=request(i);
        String csv="phone,name,tags,consent_state\n13100004441,=SUM(1),测试,consented\n13100004441,重复,,consented\ninvalid,错误,,unknown\n";
        MockMultipartFile file=new MockMultipartFile("file","test.csv","text/csv",csv.getBytes(StandardCharsets.UTF_8));
        Map<String,Object> result=files.importCsv(file,true,"csv-1",r);assertEquals(1,result.get("created"));assertEquals(1,result.get("skipped"));assertEquals(1,result.get("failed"));
        assertEquals(result,files.importCsv(file,true,"csv-1",r));assertEquals(1,store.count("contact","WHERE tenant_id=?",i.tenant()));
        ByteArrayOutputStream output=new ByteArrayOutputStream();files.export(null,null,null,r).getBody().writeTo(output);String data=output.toString("UTF-8");assertTrue(data.contains("'=SUM(1)"));assertTrue(data.contains("consented"));assertFalse(data.contains("invalid"));
        assertEquals("'\t=1\n+1",ContactFiles.safe("\t=1\n+1"));
    }
    @Test void billingCountsAttemptLedgerMissingDataAndAllSmsStates() {
        Identity i=fixture("admin"),other=fixture("admin");Map<String,Object> c=call(i,"completed",3);call(other,"completed",1);
        store.insert("callusage",JsonSupport.map("tenant_id",i.tenant(),"call_session_id",c.get("id"),"attempt",1,"ended_at",Store.now(),"telephony_seconds",120.0,"ai_seconds",60.0,"duration_source","provider_cdr"));
        store.insert("callusage",JsonSupport.map("tenant_id",i.tenant(),"call_session_id",c.get("id"),"attempt",2));
        store.insert("smslog",JsonSupport.map("tenant_id",i.tenant(),"call_session_id",c.get("id"),"to_phone",c.get("phone"),"content","test","state","failed"));
        Map<String,Object> bill=reports.billing("campaign",30,2,1,0.5,request(i)),summary=object(bill.get("summary"));
        assertEquals(1L,summary.get("calls"));assertEquals(2.0,summary.get("telephony_minutes"));assertEquals(1.0,summary.get("ai_minutes"));assertEquals(2L,summary.get("missing_duration_count"));assertEquals(2L,summary.get("missing_ai_duration_count"));assertEquals(4.5,summary.get("estimated_cost"));assertEquals(1L,summary.get("sms_count"));
        assertEquals("未绑定任务",rows(bill.get("rows")).get(0).get("label"));
        assertEquals(422,assertThrows(ApiError.class,()->reports.billing("campaign",30,Double.NaN,0,0,request(i))).status);
    }
    @Test void reportsReviewsAndEvidenceExportRespectTenantAndFilters() throws Exception {
        Identity i=fixture("admin"),other=fixture("admin");MockHttpServletRequest r=request(i);
        Map<String,Object> contact=store.insert("contact",JsonSupport.map("tenant_id",i.tenant(),"phone","13100003333","tags","客户组，次要","dnc",true));
        Map<String,Object> c=call(i,"completed",1);store.update("callsession",i.tenant(),c.get("id"),JsonSupport.map("contact_id",contact.get("id"),"handoff_reason","operator"));call(i,"no_answer",1);Map<String,Object> foreign=call(other,"completed",1);
        store.insert("callanalysis",JsonSupport.map("tenant_id",i.tenant(),"call_session_id",c.get("id"),"qa_score",25,"needs_review",true,"review_state","reviewed","summary","=HYPERLINK(1)"));store.insert("callanalysis",JsonSupport.map("tenant_id",other.tenant(),"call_session_id",foreign.get("id"),"qa_score",0));
        Map<String,Object> summary=object(reports.report("campaign","hour",30,r).get("summary"));assertEquals(2L,summary.get("calls"));assertEquals(1L,summary.get("reached"));assertEquals(1L,summary.get("loss"));assertEquals(1L,summary.get("handoff"));
        Map<String,Object> group=reports.groups(30,r);assertEquals(1L,object(group.get("summary")).get("dnc_contacts"));assertTrue(rows(group.get("rows")).stream().anyMatch(x->"客户组".equals(x.get("key"))));
        List<Map<String,Object>> reviews=reports.reviews("auto",30,1,50,r);assertEquals(1,reviews.size());assertEquals("needs_review",reviews.get(0).get("review_state"));assertEquals(0,reports.reviews("auto",10,1,50,r).size());
        ByteArrayOutputStream out=new ByteArrayOutputStream();reports.export(30,"completed",r).getBody().writeTo(out);String csv=out.toString("UTF-8");assertTrue(csv.contains(c.get("id").toString()));assertFalse(csv.contains(foreign.get("id").toString()));assertTrue(csv.contains("'=HYPERLINK(1)"));assertFalse(csv.contains("no_answer"));
    }
    @Test void mediaRegistrationAndLogoutRevokeAvailability() {
        Identity i=fixture("agent"),other=fixture("agent");MockHttpServletRequest r=request(i);assertEquals("disabled",media.status(r).get("registration_state"));assertEquals(false,media.session(r).get("enabled"));
        Map<String,Object> body=JsonSupport.map("registration_state","registered","media_state","idle","microphone_permission","granted");media.update(body,r);assertTrue(media.registered(i.tenant(),i.userId()));
        Map<String,Object> c=call(other,"in_human",1);body.put("active_call_id",c.get("id").toString());assertEquals(404,assertThrows(ApiError.class,()->media.update(body,r)).status);
        auth.logout(i);assertFalse(media.registered(i.tenant(),i.userId()));assertEquals(1,CallLifecycle.number(store.require("user",i.tenant(),i.userId()),"token_version"));
    }
    @Test void expiredTaskLeaseCannotAuthorizeEffects() {
        Identity i=fixture("admin");Map<String,Object> task=store.insert("taskoutbox",JsonSupport.map("tenant_id",i.tenant(),"task_type","call_analysis","aggregate_id","test","idempotency_key",UUID.randomUUID().toString(),"payload_json","{}","state","processing","lease_token","lease","locked_at",Store.now().minusHours(1)));
        assertFalse(worker.owns(task));store.update("taskoutbox",i.tenant(),task.get("id"),JsonSupport.map("locked_at",Store.now()));assertTrue(worker.owns(task));
        store.update("taskoutbox",i.tenant(),task.get("id"),JsonSupport.map("lease_token","new-owner"));assertFalse(worker.owns(task));
    }
    @Test void httpReportsEnforceBearerRoleAndInvalidPagination() throws Exception {
        Identity i=fixture("admin"),agent=fixture("agent");String token=crypto.token(i.user()),agentToken=crypto.token(agent.user());
        mvc.perform(get("/api/v1/admin/billing").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("summary.calls").value(0));
        mvc.perform(get("/api/v1/admin/call-reports").header("Authorization","Bearer "+agentToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/quality/reviews").header("Authorization","Bearer "+agentToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/contact-groups").header("Authorization","Bearer "+token).param("days","0")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/agent/media/status").header("Authorization","Bearer "+agentToken)).andExpect(status().isOk()).andExpect(jsonPath("registration_state").value("disabled"));
    }
    @Test void recordingDeleteRequiresAcknowledgementAndAcceptsEmpty204() throws Exception {
        Identity i=fixture("admin");Map<String,Object> c=call(i,"completed",1);
        Map<String,Object> asset=store.insert("recordingasset",JsonSupport.map("tenant_id",i.tenant(),"call_session_id",c.get("id"),"provider_url","https://recordings.invalid/test.wav","storage_uri","test://recording","state","deletion_pending"));
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger response=new java.util.concurrent.atomic.AtomicInteger(503);
        server.createContext("/delete",exchange->{exchange.getRequestBody().close();exchange.sendResponseHeaders(response.get(),-1);exchange.close();});server.start();
        try {
            Config cfg=new Config(new MockEnvironment().withProperty("RECORDING_DELETE_ENDPOINT","http://127.0.0.1:"+server.getAddress().getPort()+"/delete"));
            IntegrationTasks tasks=new IntegrationTasks(store,tx,cfg,settings,json,new TelephonyClient(store,cfg,json));
            Map<String,Object> task=JsonSupport.map("tenant_id",i.tenant(),"id",UUID.randomUUID().toString()),payload=JsonSupport.map("recording_asset_id",asset.get("id"));
            assertThrows(TelephonyClient.Failure.class,()->tasks.execute("recording_delete",task,payload));assertEquals("test://recording",store.require("recordingasset",i.tenant(),asset.get("id")).get("storage_uri"));
            response.set(204);tasks.execute("recording_delete",task,payload);Map<String,Object> deleted=store.require("recordingasset",i.tenant(),asset.get("id"));assertEquals("deleted",deleted.get("state"));assertEquals("",deleted.get("storage_uri"));assertNotNull(deleted.get("deleted_at"));
        } finally {server.stop(0);}
    }

    @Test void futureTimerDoesNotBlockReadyPlaybackCompletion() {
        Identity i=fixture("admin");String callId=UUID.randomUUID().toString();
        Map<String,Object> future=store.insert("taskoutbox",JsonSupport.map("tenant_id",i.tenant(),"task_type","after_playback","aggregate_id",callId,"idempotency_key",UUID.randomUUID().toString(),"payload_json",json.json(JsonSupport.map("call_id",callId,"attempt",1)),"available_at",Store.now().plusDays(1)));
        Map<String,Object> ready=store.insert("taskoutbox",JsonSupport.map("tenant_id",i.tenant(),"task_type","after_playback","aggregate_id",callId,"idempotency_key",UUID.randomUUID().toString(),"payload_json",json.json(JsonSupport.map("call_id",callId,"attempt",1))));
        assertTrue(worker.processOne(i.tenant()));assertEquals("completed",store.require("taskoutbox",i.tenant(),ready.get("id")).get("state"));assertEquals("pending",store.require("taskoutbox",i.tenant(),future.get("id")).get("state"));
    }

}
