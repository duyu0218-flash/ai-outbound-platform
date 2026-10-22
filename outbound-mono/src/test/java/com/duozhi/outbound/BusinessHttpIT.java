package com.duozhi.outbound;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.duozhi.outbound.auth.UserRecords;
import com.duozhi.outbound.entity.*;
import com.duozhi.outbound.mapper.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class BusinessHttpIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("JWT_SECRET",()->"customer-http-secret-at-least-thirty-two-characters");
        p.add("spring.datasource.url",MYSQL::getJdbcUrl);p.add("spring.datasource.username",MYSQL::getUsername);p.add("spring.datasource.password",MYSQL::getPassword);
        p.add("spring.flyway.url",MYSQL::getJdbcUrl);p.add("spring.flyway.user",()->"root");p.add("spring.flyway.password",MYSQL::getPassword);
        p.add("spring.data.redis.host",REDIS::getHost);p.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));p.add("REDIS_URL",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
        p.add("JAVA_DISPATCH_ENABLED",()->"true");p.add("OUTBOUND_PLATFORM_MAX_CONCURRENT",()->"200");p.add("JAVA_WORKERS_ENABLED",()->"false");
        p.add("spring.ai.model.chat",()->"none");p.add("TENANT_API_KEYS_JSON",()->"{\"1\":\"customer-test-key\"}");
    }
    @Autowired TestRestTemplate http;@Autowired ObjectMapper json;@Autowired Crypto crypto;@Autowired Store store;
    @Autowired SysUserMapper users;@Autowired TenantMapper tenants;@Autowired AuditLogMapper audits;
    @Autowired ConfigurableEnvironment environment;@Autowired CallPolicy policy;@Autowired Transactions tx;@Autowired DataRetention retention;@Autowired Outbox outbox;
    private final Map<String,Object> overrides=new HashMap<>();private String token;private SysUser user;private Object temporaryAsyncPolicy;
    private final List<String> temporaryAsyncCalls=new ArrayList<>();
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.boot.test.mock.mockito.SpyBean TelephonyClient telephony;
    @Autowired ConversationPolicy conversation;@Autowired AiRuntime aiRuntime;
    @Autowired TaskWorker worker;@Autowired CallbackInbox inbox;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping routes;
    @BeforeEach void seed(){
        environment.getPropertySources().addFirst(new MapPropertySource("business-scenario",overrides));
        overrides.put("ENV","dev");overrides.put("TENANT_API_SCOPES_JSON","{}");
        for(int id:List.of(1,2))if(tenants.findById(id)==null){Tenant t=new Tenant();t.setId(id);t.setCode("customer-tenant-"+id);t.setName("Customer tenant "+id);tenants.insert(t);}
        user=new SysUser();user.setTenantId(1);user.setUsername("customer"+UUID.randomUUID().toString().replace("-",""));user.setFullName("Customer test admin");user.setRole("admin");users.insert(user);token=crypto.token(UserRecords.map(user));
    }
    @AfterEach void reset(){
        for(String id:temporaryAsyncCalls){jdbc.update("DELETE FROM taskoutbox WHERE tenant_id=1 AND aggregate_id=?",id);store.delete("callsession",1,id);}temporaryAsyncCalls.clear();
        if(temporaryAsyncPolicy!=null){store.delete("adminsetting",1,temporaryAsyncPolicy);temporaryAsyncPolicy=null;}environment.getPropertySources().remove("business-scenario");
    }
    HttpHeaders auth(){var h=new HttpHeaders();h.setBearerAuth(token);return h;}
    ResponseEntity<JsonNode> request(HttpMethod method,String path,Object body){var h=auth();h.setContentType(MediaType.APPLICATION_JSON);return http.exchange(path,method,new HttpEntity<>(body,h),JsonNode.class);}
    JsonNode ok(ResponseEntity<JsonNode> r){assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);return r.getBody();}
    String phone(){return "13"+String.format(Locale.ROOT,"%011d",Math.abs(UUID.randomUUID().getMostSignificantBits()%100000000000L));}
    JsonNode create(String number){return ok(request(HttpMethod.POST,"/api/v1/contacts",Map.of("phone",number,"name","客户Case","consent_state","consented")));}
    Map<String,Object> contact(String number){return store.insert("contact",Values.map("tenant_id",1,"phone",number,"consent_state","consented"));}
    String at(String number){return policy.checkAt(1,number,true,null,Instant.parse("2026-10-19T04:00:00Z"));}


    JsonNode callback(String id,String event,String state,Object... extra){var payload=JsonSupport.map("event_id",event,"status",state,"attempt",store.locate("callsession",id,false).get("attempts"));for(int i=0;i<extra.length;i+=2)payload.put(extra[i].toString(),extra[i+1]);return ok(request(HttpMethod.POST,"/api/v1/webhooks/telephony/status",Map.of("call_id",id,"kind","status","payload",payload)));}
    void state(String id,String expected,List<String> observed){var call=ok(request(HttpMethod.GET,"/api/v1/calls/"+id,null));assertThat(call.get("status").asText()).isEqualTo(expected);observed.add(expected);}
    Map<String,Object> node(String id,String type,String prompt){return JsonSupport.map("id",id,"type",type,"label",id,"prompt",prompt,"position",Map.of("x",100,"y",100));}
    Map<String,Object> twoTurnGraph(){
        var nodes=List.of(node("start","start",""),node("opening","message","您好，我们想向您介绍服务。"),node("listen1","listen",""),node("answer1","message","我们的服务提供预约咨询。"),node("listen2","listen",""),node("answer2","message","您可以继续了解或要求转人工。"),node("listen3","listen",""),node("human","handoff","正在转接人工。"));var edges=new ArrayList<Map<String,Object>>();for(int i=1;i<nodes.size();i++)edges.add(JsonSupport.map("id","edge"+i,"source",nodes.get(i-1).get("id"),"target",nodes.get(i).get("id"),"condition","always"));return Map.of("nodes",nodes,"edges",edges);
    }
    @Test void asyncBatchIsAtomicIdempotentQueryableAndDoesNotDialSynchronously(){
        overrides.put("JAVA_DIAL_ASYNC_API_ENABLED","true");overrides.put("JAVA_DISPATCH_ENABLED","true");
        var policy=store.one("adminsetting","findBySection",JsonSupport.map("tenantId",1,"section","compliance"));
        if(policy==null)temporaryAsyncPolicy=store.insert("adminsetting",Map.of("tenant_id",1,"section","compliance","data_json","{\"allowed_start_hour\":0,\"allowed_end_hour\":0}")).get("id");
        String first=phone(),second=phone();contact(first);contact(second);
        var headers=auth();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("Idempotency-Key","atomic-"+UUID.randomUUID());
        var invalid=Map.of("items",List.of(Map.of("phone",first,"mode","ai_only"),Map.of("phone","invalid-number","mode","ai_only")));
        var rejected=http.exchange("/api/v1/calls/batch",HttpMethod.POST,new HttpEntity<>(invalid,headers),JsonNode.class);
        assertThat(rejected.getStatusCode().value()).isEqualTo(422);assertThat(rejected.getBody().path("errors").get(0).path("index").asInt()).isEqualTo(1);
        var body=Map.of("items",List.of(Map.of("phone",first,"mode","ai_only"),Map.of("phone",second,"mode","ai_only")));
        var accepted=http.exchange("/api/v1/calls/batch",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);
        assertThat(accepted.getStatusCode().value()).as(String.valueOf(accepted.getBody())).isEqualTo(202);
        for(var id:accepted.getBody().path("call_ids"))temporaryAsyncCalls.add(id.asText());
        assertThat(accepted.getBody().path("accepted_count").asInt()).isEqualTo(2);assertThat(accepted.getBody().path("status").asText()).isEqualTo("queued");
        var replay=http.exchange("/api/v1/calls/batch",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);
        assertThat(replay.getStatusCode().value()).isEqualTo(202);assertThat(replay.getBody()).isEqualTo(accepted.getBody());
        var conflict=http.exchange("/api/v1/calls/batch",HttpMethod.POST,new HttpEntity<>(invalid,headers),JsonNode.class);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        var status=ok(request(HttpMethod.GET,accepted.getBody().path("status_url").asText(),null));
        assertThat(status.path("counts").path("queued").asInt()).isEqualTo(2);
        for(var id:accepted.getBody().path("call_ids"))assertThat(store.require("callsession",1,id.asText())).containsEntry("attempts",0).containsEntry("status","queued");
        verify(telephony,never()).command(anyMap(),eq("dial"),anyMap());
        assertThat(request(HttpMethod.GET,"/api/v1/calls/batches/"+UUID.randomUUID(),null).getStatusCode().value()).isEqualTo(404);
    }
    @Test void asyncApiHasAnExplicitFeatureGateAndBoundedRequestBody(){
        overrides.put("JAVA_DIAL_ASYNC_API_ENABLED","false");
        assertThat(request(HttpMethod.POST,"/api/v1/calls/async",Map.of("phone",phone(),"mode","ai_only")).getStatusCode().value()).isEqualTo(404);
        overrides.put("JAVA_DIAL_ASYNC_API_ENABLED","true");var headers=auth();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("Idempotency-Key","body-limit");
        var oversized=http.exchange("/api/v1/calls/batch",HttpMethod.POST,new HttpEntity<>(" ".repeat(1048577),headers),JsonNode.class);
        assertThat(oversized.getStatusCode().value()).isEqualTo(413);
        assertThat(request(HttpMethod.POST,"/api/v1/calls/batch",Map.of("items",List.of())).getStatusCode().value()).isEqualTo(422);
    }
    @Test void asyncBatchEnforcesCallerTenantAndMachineDialScope(){
        overrides.put("JAVA_DIAL_ASYNC_API_ENABLED","true");
        var policy=store.one("adminsetting","findBySection",JsonSupport.map("tenantId",1,"section","compliance"));
        if(policy==null)temporaryAsyncPolicy=store.insert("adminsetting",Map.of("tenant_id",1,"section","compliance","data_json","{\"allowed_start_hour\":0,\"allowed_end_hour\":0}")).get("id");
        String number=phone();contact(number);var body=Map.of("items",List.of(Map.of("phone",number,"mode","ai_only")));
        var creator=auth();creator.setContentType(MediaType.APPLICATION_JSON);creator.set("Idempotency-Key","actor-"+UUID.randomUUID());
        var accepted=http.exchange("/api/v1/calls/batch",HttpMethod.POST,new HttpEntity<>(body,creator),JsonNode.class);assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        for(var id:accepted.getBody().path("call_ids"))temporaryAsyncCalls.add(id.asText());String status=accepted.getBody().path("status_url").asText();
        for(int tenant:List.of(1,2)){
            var other=new SysUser();other.setTenantId(tenant);other.setUsername("batch-other-"+UUID.randomUUID());other.setFullName("Batch isolation fixture");other.setRole("agent");users.insert(other);
            var headers=new HttpHeaders();headers.setBearerAuth(crypto.token(UserRecords.map(other)));headers.setContentType(MediaType.APPLICATION_JSON);headers.set("Idempotency-Key",creator.getFirst("Idempotency-Key"));
            assertThat(http.exchange(status,HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class).getStatusCode().value()).isEqualTo(tenant==1?403:404);
            if(tenant==1)assertThat(http.exchange("/api/v1/calls/batch",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class).getStatusCode().value()).isEqualTo(403);
        }
        overrides.put("ENV","prod");overrides.put("TRUSTED_HOSTS","localhost,127.0.0.1");overrides.put("TENANT_API_SCOPES_JSON","{\"1\":[\"calls:control\"]}");
        var machine=new HttpHeaders();machine.set("x-api-key","customer-test-key");machine.setContentType(MediaType.APPLICATION_JSON);machine.set("Idempotency-Key","machine-"+UUID.randomUUID());
        var forbidden=http.exchange("/api/v1/calls/batch",HttpMethod.POST,new HttpEntity<>(body,machine),JsonNode.class);assertThat(forbidden.getStatusCode().value()).as(String.valueOf(forbidden.getBody())).isEqualTo(403);assertThat(forbidden.getBody().path("detail").asText()).contains("calls:dial");
        overrides.put("TENANT_API_SCOPES_JSON","{\"1\":[\"calls:dial\"]}");
        var allowed=http.exchange("/api/v1/calls/batch",HttpMethod.POST,new HttpEntity<>(body,machine),JsonNode.class);assertThat(allowed.getStatusCode().value()).isEqualTo(202);
        for(var id:allowed.getBody().path("call_ids"))temporaryAsyncCalls.add(id.asText());
        assertThat(http.exchange(allowed.getBody().path("status_url").asText(),HttpMethod.GET,new HttpEntity<>(machine),JsonNode.class).getStatusCode().value()).isEqualTo(200);
        machine.set("x-tenant-id","2");assertThat(http.exchange(allowed.getBody().path("status_url").asText(),HttpMethod.GET,new HttpEntity<>(machine),JsonNode.class).getStatusCode().value()).isEqualTo(403);
    }
    @Test void repeatableMockOutboundLifecycleProducesQualityReportsAndBilling()throws Exception{
        store.insert("adminsetting",Values.map("tenant_id",1,"section","compliance","data_json","{\"allowed_start_hour\":0,\"allowed_end_hour\":0}"));
        var agent=new SysUser();agent.setTenantId(1);agent.setUsername("desk"+UUID.randomUUID().toString().replace("-",""));agent.setFullName("接听坐席");agent.setRole("agent");agent.setAgentStatus("ready");agent.setLastSeenAt(Store.now());users.insert(agent);
        var contact=create(phone());var template=ok(request(HttpMethod.POST,"/api/v1/script-templates",Map.of("name","闭环话术","content","您好，我们想向您介绍服务。")));long templateId=template.get("id").asLong();String flowPath="/api/v1/script-templates/"+templateId+"/flows";
        var flow=ok(request(HttpMethod.POST,flowPath,Map.of()));String versionPath=flowPath+"/"+flow.get("id").asLong();ok(request(HttpMethod.PUT,versionPath,Map.of("graph",twoTurnGraph())));ok(request(HttpMethod.POST,versionPath+"/publish",null));
        var campaign=ok(request(HttpMethod.POST,"/api/v1/campaigns",Map.of("name","闭环活动","mode","ai_handoff","script_template_id",templateId,"script_flow_version_id",flow.get("id").asLong(),"contact_ids",List.of(contact.get("id").asLong()),"retry_limit",1)));long campaignId=campaign.get("id").asLong();
        var prepared=ok(request(HttpMethod.POST,"/api/v1/campaigns/"+campaignId+"/start?auto_dial=false",null));assertThat(prepared.get("created").asInt()).isEqualTo(1);var calls=ok(request(HttpMethod.GET,"/api/v1/calls?campaign_id="+campaignId,null));assertThat(calls).hasSize(1);String id=calls.get(0).get("id").asText();List<String> observed=new ArrayList<>();state(id,"created",observed);
        ok(request(HttpMethod.POST,"/api/v1/campaigns/"+campaignId+"/start?auto_dial=true&async_dial=true",null));state(id,"queued",observed);assertThat(worker.enqueueDue()).isGreaterThanOrEqualTo(1);assertThat(worker.processLane(1L,0)).isTrue();state(id,"dialing",observed);
        callback(id,"initial-failure","failed");state(id,"failed",observed);var retried=ok(request(HttpMethod.POST,"/api/v1/calls/"+id+"/retry",null));assertThat(retried.get("attempts").asInt()).isEqualTo(2);state(id,"dialing",observed);
        callback(id,"answered-mock","answered");state(id,"answered",observed);for(int pending=0;pending<10&&!"in_ai".equals(store.require("callsession",1,id).get("status"));pending++)assertThat(worker.processLane(1L,1)).isTrue();state(id,"in_ai",observed);
        for(int round=1;round<=2;round++){
            var speech=Map.of("call_id",id,"event_id","customer-"+round,"transcript","请继续介绍服务","attempt",2,"is_final",true,"confidence",1.0);
            ok(request(HttpMethod.POST,"/api/v1/webhooks/telephony/speech",speech));assertThat(worker.processLane(1L,1)).isTrue();var current=ok(request(HttpMethod.GET,"/api/v1/calls/"+id,null));assertThat(current.get("status").asText()).as("Synthetic fixture round="+round+" call="+current.toPrettyString()+" decisions="+ok(request(HttpMethod.GET,"/api/v1/product/outcomes",null)).toPrettyString()+" turns="+ok(request(HttpMethod.GET,"/api/v1/calls/"+id+"/speech-turns",null)).toPrettyString()).isEqualTo("in_ai");
            var turns=ok(request(HttpMethod.GET,"/api/v1/calls/"+id+"/speech-turns?final_only=true",null));long customer=0,ai=0;for(var turn:turns){if(turn.get("speaker_role").asText().equals("customer"))customer++;if(turn.get("speaker_role").asText().equals("ai"))ai++;}assertThat(customer).isEqualTo(round);assertThat(ai).isEqualTo(round+1);
        }
        ok(request(HttpMethod.POST,"/api/v1/webhooks/telephony/speech",Map.of("call_id",id,"event_id","handoff-request","transcript","请转人工","attempt",2,"is_final",true,"confidence",1.0)));assertThat(worker.processLane(1L,1)).isTrue();state(id,"waiting_human",observed);
        var handoffs=ok(request(HttpMethod.GET,"/api/v1/calls/"+id+"/handoffs",null));assertThat(handoffs).hasSize(1);long handoffId=handoffs.get(0).get("id").asLong();assertThat(handoffs.get(0).get("assigned_agent_id").asInt()).isEqualTo(agent.getId());
        var h=new HttpHeaders();h.setBearerAuth(crypto.token(UserRecords.map(agent)));var accepted=http.exchange("/api/v1/calls/"+id+"/handoffs/"+handoffId+"/accept",HttpMethod.POST,new HttpEntity<>(h),JsonNode.class);assertThat(ok(accepted).get("state").asText()).isEqualTo("accepting");state(id,"handoff_transferring",observed);
        callback(id,"bridge-confirmed","human_connected");state(id,"in_human",observed);callback(id,"provider-completed","completed","billable_duration_sec",120);state(id,"completed",observed);
        assertThat(worker.processLane(1L,worker.analysisLane())).isTrue();var analysis=ok(request(HttpMethod.GET,"/api/v1/calls/"+id+"/analysis",null));assertThat(analysis.get("call_session_id").asText()).isEqualTo(id);assertThat(analysis.get("qa_score").isNumber()).isTrue();assertThat(analysis.get("automatic_result_json").isTextual()).isTrue();assertThat(json.readTree(analysis.get("automatic_result_json").asText()).isObject()).isTrue();
        var report=ok(request(HttpMethod.GET,"/api/v1/admin/call-reports?dimension=campaign",null));assertThat(report.get("summary").get("calls").asInt()).isEqualTo(1);assertThat(report.get("summary").get("completed").asInt()).isEqualTo(1);
        var billing=ok(request(HttpMethod.GET,"/api/v1/admin/billing?telephony_unit_price_per_minute=0.5&ai_unit_price_per_minute=1",null));assertThat(billing.get("summary").get("telephony_minutes").asDouble()).isEqualTo(2.0);assertThat(billing.get("summary").get("estimated_cost").asDouble()).isGreaterThanOrEqualTo(1.0);assertThat(billing.get("rows").get(0).get("key").asText()).isEqualTo(""+campaignId);
        callback(id,"provider-completed","completed","billable_duration_sec",120);var repeated=ok(request(HttpMethod.GET,"/api/v1/admin/billing?telephony_unit_price_per_minute=0.5&ai_unit_price_per_minute=1",null));assertThat(repeated.get("summary")).isEqualTo(billing.get("summary"));assertThat(ok(request(HttpMethod.GET,"/api/v1/calls/"+id+"/handoffs",null))).hasSize(1);
        assertThat(observed).containsExactly("created","queued","dialing","failed","dialing","answered","in_ai","waiting_human","handoff_transferring","in_human","completed");assertThat(users.findById(agent.getId(),1).getAgentStatus()).isEqualTo("ready");
    }
    @Test void everyBaselineOperationHasAConcreteHandler()throws Exception{
        JsonNode baseline;try(var in=getClass().getResourceAsStream("/contracts/openapi-baseline.json")){baseline=json.readTree(in);}
        Set<String> implemented=new HashSet<>();routes.getHandlerMethods().forEach((mapping,method)->{for(var verb:mapping.getMethodsCondition().getMethods())for(var path:mapping.getPatternValues())implemented.add(verb.name()+" "+path.replaceAll("\\{[^/]+\\}","{}"));});
        var missing=new ArrayList<String>();int total=0;var paths=baseline.get("paths").fields();while(paths.hasNext()){var path=paths.next();var methods=path.getValue().fields();while(methods.hasNext()){var method=methods.next();if(!Set.of("get","post","put","patch","delete","head","options").contains(method.getKey()))continue;total++;String key=method.getKey().toUpperCase(Locale.ROOT)+" "+path.getKey().replaceAll("\\{[^/]+\\}","{}");if(!implemented.contains(key))missing.add(key);}}
        assertThat(total).isEqualTo(108);assertThat(missing).as("untranslated OpenAPI routes").isEmpty();
    }
    @Test void callbackPartitionsInitializeExactly64AndReinitializationPreservesCounters(){
        var partitions=store.query("callbackinboxpartition","list",Values.map("offset",0,"limit",100));assertThat(partitions).hasSize(64);inbox.initialize();assertThat(store.query("callbackinboxpartition","list",Values.map("offset",0,"limit",100))).isEqualTo(partitions);
    }
    void tenantTwo(){SysUser admin=new SysUser();admin.setTenantId(2);admin.setUsername("state"+UUID.randomUUID().toString().replace("-",""));admin.setFullName("State test admin");admin.setRole("admin");users.insert(admin);token=crypto.token(UserRecords.map(admin));if(store.query("adminsetting","list",Values.map("tenantId",2,"offset",0,"limit",100)).stream().noneMatch(row->"compliance".equals(row.get("section"))))store.insert("adminsetting",Values.map("tenant_id",2,"section","compliance","data_json","{\"allowed_start_hour\":0,\"allowed_end_hour\":0}"));}
    Map<String,Object> stateCall(String status){return store.insert("callsession",Values.map("tenant_id",2,"phone",phone(),"mode","ai_handoff","status",status,"attempts",1,"max_attempts",3));}
    ResponseEntity<JsonNode> signedBatch(String path,byte[] body,boolean valid){String stamp=Long.toString(Instant.now().getEpochSecond());var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("x-webhook-token","bounded-v2-fixture-token");headers.set("x-webhook-timestamp",stamp);headers.set("x-webhook-signature",valid?JsonSupport.hmac("bounded-v2-fixture-secret",(stamp+"."+new String(body,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)):"forged");return http.exchange(path,HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);}
    @Test void signedV2CallbackEnvelopeKeepsLegacyLimitsAndRejectsInvalidBatchesBeforeWriting()throws Exception{
        overrides.put("CALLBACK_INBOX_ENABLED","true");overrides.put("JAVA_CALLBACK_BATCH_SIZE","100");overrides.put("TELEPHONY_WEBHOOK_TOKEN","bounded-v2-fixture-token");overrides.put("TELEPHONY_WEBHOOK_SECRET","bounded-v2-fixture-secret");inbox.initialize();var c=stateCall("dialing");
        var events=new ArrayList<Map<String,Object>>();for(int i=0;i<64;i++)events.add(JsonSupport.map("id",JsonSupport.sha(c.get("id")+":v2:"+i),"kind","status","payload",JsonSupport.map("call_id",c.get("id"),"kind","status","payload",Map.of("status","dialing","attempt",1,"event_id","v2-"+i))));
        byte[] body=json.writeValueAsBytes(Map.of("version",2,"events",events));var received=ok(signedBatch("/api/v1/webhooks/telephony/batch-v2",body,true));assertThat(received.get("version").asInt()).isEqualTo(2);assertThat(received.get("accepted").size()).isEqualTo(64);
        assertThat(signedBatch("/api/v1/webhooks/telephony/batch-v2",body,false).getStatusCode().value()).isEqualTo(401);
        assertThat(signedBatch("/api/v1/webhooks/telephony/batch",json.writeValueAsBytes(Map.of("version",1,"events",events)),true).getStatusCode().value()).isEqualTo(400);
        var extra=new ArrayList<>(events);extra.add(events.get(0));assertThat(signedBatch("/api/v1/webhooks/telephony/batch-v2",json.writeValueAsBytes(Map.of("version",2,"events",extra)),true).getStatusCode().value()).isEqualTo(400);
        var duplicate=new ArrayList<>(events);duplicate.set(63,events.get(0));assertThat(signedBatch("/api/v1/webhooks/telephony/batch-v2",json.writeValueAsBytes(Map.of("version",2,"events",duplicate)),true).getStatusCode().value()).isEqualTo(422);
        assertThat(signedBatch("/api/v1/webhooks/telephony/batch-v2",new byte[262145],true).getStatusCode().value()).isEqualTo(413);
        assertThat(store.query("callbackinbox","list",Map.of("offset",0,"limit",1000)).stream().filter(r->c.get("id").equals(r.get("call_id"))).toList()).hasSize(64);
        for(int p=0;p<64;p++)for(int i=0;i<64&&inbox.consume(p);i++){} // leave shared fixture partitions drained
    }
    Map<String,Object> retryFixture(String status){tenantTwo();/* Keep this retry fixture independent of policies published by other tests and the wall-clock business window. */store.insert("scenarioversion",Values.map("tenant_id",2,"policy_json",new JsonSupport(json).json(Values.map("timezone","Asia/Shanghai","start_hour",0,"end_hour",24,"weekdays",List.of(0,1,2,3,4,5,6),"handoff_capacity_control",false))));var call=stateCall(status);store.insert("contact",Values.map("tenant_id",2,"phone",call.get("phone"),"consent_state","consented"));return call;}
    @Test void retryFromTerminalRequeuesAndDialsAgain()throws Exception{
        var call=retryFixture("completed");String id=call.get("id").toString();assertThat(ok(request(HttpMethod.GET,"/api/v1/calls/"+id,null)).get("status").asText()).isEqualTo("completed");long before=store.query("callevent","list",Values.map("tenantId",2,"offset",0,"limit",1000)).stream().filter(e->id.equals(e.get("call_session_id"))&&"dial_attempt_claimed".equals(e.get("event_type"))).count();
        var seen=new ArrayList<String>();org.mockito.Mockito.doAnswer(invocation->{seen.add(store.require("callsession",2,id).get("status").toString());return invocation.callRealMethod();}).when(telephony).validate(org.mockito.ArgumentMatchers.anyMap());
        var retried=ok(request(HttpMethod.POST,"/api/v1/calls/"+id+"/retry",null));assertThat(retried.get("status").asText()).isEqualTo("dialing");assertThat(seen).contains("queued");assertThat(retried.get("attempts").asInt()).isEqualTo(2).isLessThanOrEqualTo(retried.get("max_attempts").asInt());
        long after=store.query("callevent","list",Values.map("tenantId",2,"offset",0,"limit",1000)).stream().filter(e->id.equals(e.get("call_session_id"))&&"dial_attempt_claimed".equals(e.get("event_type"))).count();assertThat(after).isEqualTo(before+1);
        System.out.println("RETRY_EVIDENCE before=completed; transition=queued -> dialing; attempts=2; max_attempts=3; new_dial_attempt_claimed="+(after-before));
    }
    @Test void retryRejectedWhenAttemptsExhausted(){var call=retryFixture("completed");store.update("callsession",2,call.get("id"),Values.map("attempts",3));var rejected=request(HttpMethod.POST,"/api/v1/calls/"+call.get("id")+"/retry",null);assertThat(rejected.getStatusCode().value()).isEqualTo(409);assertThat(rejected.getBody().get("detail").asText()).isEqualTo("reach max attempts");assertThat(store.require("callsession",2,call.get("id")).get("attempts")).isEqualTo(3);}
    @Test void scheduledRetryRequeuesTerminalCall(){var call=retryFixture("busy");store.update("callsession",2,call.get("id"),Values.map("next_attempt_at",Store.now().minusSeconds(1)));worker.enqueueDue();assertThat(store.require("callsession",2,call.get("id")).get("status")).isEqualTo("queued");assertThat(store.query("taskoutbox","list",Values.map("tenantId",2,"offset",0,"limit",1000))).anyMatch(t->call.get("id").equals(t.get("aggregate_id"))&&"dial_call".equals(t.get("task_type")));}
    @Test void ordinaryQueuedSelectionCannotBypassADeferredRetryTime(){
        var call=retryFixture("queued");store.update("callsession",2,call.get("id"),Values.map("next_attempt_at",Store.now().plusHours(1)));worker.enqueueDue();
        assertThat(store.query("taskoutbox","list",Values.map("tenantId",2,"offset",0,"limit",1000))).noneMatch(t->call.get("id").equals(t.get("aggregate_id"))&&"dial_call".equals(t.get("task_type")));
        store.update("callsession",2,call.get("id"),Values.map("next_attempt_at",Store.now().minusSeconds(1)));worker.enqueueDue();
        assertThat(store.query("taskoutbox","list",Values.map("tenantId",2,"offset",0,"limit",1000))).anyMatch(t->call.get("id").equals(t.get("aggregate_id"))&&"dial_call".equals(t.get("task_type")));
    }
    @Test void campaignRestartReusesFailedCallInsteadOfCreatingNew(){tenantTwo();var contact=store.insert("contact",Values.map("tenant_id",2,"phone",phone(),"consent_state","consented"));var campaign=ok(request(HttpMethod.POST,"/api/v1/campaigns",Map.of("name","retry reuse","mode","ai_only","contact_ids",List.of(contact.get("id")),"retry_limit",2)));String route="/api/v1/campaigns/"+campaign.get("id").asInt()+"/start?auto_dial=false";ok(request(HttpMethod.POST,route,null));var calls=ok(request(HttpMethod.GET,"/api/v1/calls?campaign_id="+campaign.get("id").asInt(),null));String id=calls.get(0).get("id").asText();store.update("callsession",2,id,Values.map("status","failed","attempts",1));ok(request(HttpMethod.POST,route,null));var again=ok(request(HttpMethod.GET,"/api/v1/calls?campaign_id="+campaign.get("id").asInt(),null));assertThat(again).hasSize(1);assertThat(again.get(0).get("id").asText()).isEqualTo(id);assertThat(again.get(0).get("status").asText()).isEqualTo("created");assertThat(again.get(0).get("attempts").asInt()).isEqualTo(1);}
    @Test void unprovenPreAdmissionHeaderRetainsOriginalAttempt(){
        var call=retryFixture("queued");store.update("callsession",2,call.get("id"),Values.map("attempts",0));
        doThrow(new TelephonyClient.Failure(429,true)).when(telephony).command(anyMap(),eq("dial"),anyMap());
        var dial=(DialService)org.springframework.test.util.ReflectionTestUtils.getField(worker,"dial");
        assertThatThrownBy(()->dial.dispatch(call.get("id"))).isInstanceOf(DialService.PendingConfirmation.class);
        var after=store.require("callsession",2,call.get("id"));
        assertThat(after).containsEntry("status","dialing").containsEntry("attempts",1);assertThat(after.get("started_at")).isNotNull();
        assertThat(after.get("telephony_call_id")).isNull();assertThat(after.get("ai_session_id")).isNull();
        // The isolated stub issued no external command; remove fixture pressure.
        store.update("callsession",2,call.get("id"),Values.map("status","failed"));
    }
    SysUser handoffAgent(){var agent=new SysUser();agent.setTenantId(2);agent.setUsername("handoff"+UUID.randomUUID());agent.setFullName("Handoff fixture");agent.setRole("agent");agent.setAgentStatus("ready");agent.setLastSeenAt(Store.now());users.insert(agent);return agent;}
    @Test void handoffTransferFailureReturnsToWaitingHuman(){tenantTwo();var call=stateCall("waiting_human");var agent=handoffAgent();var handoff=store.insert("handoffrequest",Values.map("tenant_id",2,"call_session_id",call.get("id"),"state","waiting","assigned_agent_id",agent.getId()));org.mockito.Mockito.doThrow(new TelephonyClient.Failure(503,false)).when(telephony).command(org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.eq("transfer"),org.mockito.ArgumentMatchers.anyMap());var h=new HttpHeaders();h.setBearerAuth(crypto.token(UserRecords.map(agent)));var response=http.exchange("/api/v1/calls/"+call.get("id")+"/handoffs/"+handoff.get("id")+"/accept",HttpMethod.POST,new HttpEntity<>(h),JsonNode.class);assertThat(response.getStatusCode().value()).isEqualTo(502);var after=store.require("callsession",2,call.get("id"));assertThat(after.get("status")).isEqualTo("waiting_human");assertThat(after.get("human_agent_id")).isNull();assertThat(after.get("last_error")).isEqualTo("handover failed");assertThat(users.findById(agent.getId(),2).getAgentStatus()).isEqualTo("ready");assertThat(store.require("handoffrequest",2,handoff.get("id")).get("state")).isEqualTo("waiting");}
    @Test void handoffTimeoutReturnsToAi()throws Exception{tenantTwo();for(String fallback:List.of("continue","end")){var call=stateCall("waiting_human");var h=store.insert("handoffrequest",Values.map("tenant_id",2,"call_session_id",call.get("id"),"state","waiting"));var state=conversation.state(call);var policy=json.readValue(state.get("policy_json").toString(),Map.class);policy.put("handoff_fallback",fallback);policy.put("handoff_timeout_prompt","超时提示文本");store.update("conversationstate",2,state.get("id"),Values.map("policy_json",json.writeValueAsString(policy),"timer_kind","handoff","deadline",Store.now().minusSeconds(1)));aiRuntime.afterPlayback(Values.map("product_kind","timer","call_id",call.get("id"),"attempt",1,"generation",state.get("generation"),"kind","handoff"));var after=store.require("callsession",2,call.get("id"));assertThat(after.get("status")).isEqualTo(fallback.equals("end")?"completed":"in_ai");assertThat(store.require("handoffrequest",2,h.get("id")).get("state")).isEqualTo("rejected");assertThat(ok(request(HttpMethod.GET,"/api/v1/calls/"+call.get("id")+"/speech-turns",null))).anySatisfy(turn->assertThat(turn.get("transcript").asText()).isEqualTo("超时提示文本"));assertThat(store.require("conversationstate",2,state.get("id")).get("data_json").toString()).doesNotContain("_ai_resumed");}}
    @Test void humanUnavailableReturnsToWaitingHuman(){
        tenantTwo();SysUser agent=new SysUser();agent.setTenantId(2);agent.setUsername("handoff"+UUID.randomUUID());agent.setFullName("State test agent");agent.setRole("agent");agent.setAgentStatus("busy");agent.setLastSeenAt(Store.now());users.insert(agent);
        var call=stateCall("handoff_transferring");String id=call.get("id").toString();store.update("callsession",2,id,Values.map("human_agent_id",agent.getId()));var h=store.insert("handoffrequest",Values.map("tenant_id",2,"call_session_id",id,"assigned_agent_id",agent.getId(),"state","accepted","reason","test"));
        callback(id,"bridge-unavailable","human_unavailable");assertThat(store.require("callsession",2,id).get("status")).isEqualTo("waiting_human");assertThat(store.require("handoffrequest",2,h.get("id")).get("state")).isEqualTo("waiting");assertThat(users.findById(agent.getId(),2).getAgentStatus()).isEqualTo("ready");
        var agentHeaders=new HttpHeaders();agentHeaders.setBearerAuth(crypto.token(UserRecords.map(agent)));ok(http.exchange("/api/v1/calls/"+id+"/handoffs/"+h.get("id")+"/accept",HttpMethod.POST,new HttpEntity<>(agentHeaders),JsonNode.class));assertThat(store.require("callsession",2,id).get("status")).isEqualTo("handoff_transferring");callback(id,"bridge-retry-connected","human_connected");assertThat(store.require("callsession",2,id).get("status")).isEqualTo("in_human");
    }
    @Test void urgentDncDuringWaitingRestoresAiBeforeCompleting(){
        tenantTwo();var call=stateCall("waiting_human");String id=call.get("id").toString();store.insert("handoffrequest",Values.map("tenant_id",2,"call_session_id",id,"state","waiting","reason","test"));
        ok(request(HttpMethod.POST,"/api/v1/webhooks/telephony/speech",Map.of("call_id",id,"event_id","stop-during-wait","transcript","请停止联系，别再打电话","attempt",1,"is_final",true,"confidence",1.0)));assertThat(store.require("callsession",2,id).get("status")).isEqualTo("in_ai");assertThat(worker.processLane(2L,1)).isTrue();assertThat(store.require("callsession",2,id).get("status")).isEqualTo("completed");assertThat(store.scalar("phonesuppression","sourceCallLifecycle65_4",Store.queryParameters(0,2,call.get("phone")))).isEqualTo(1);
    }

    @Test void productSimulationRollsBackTemporaryNegativeTenantAndPolicyPublishUsesOptimisticVersion(){
        tenantTwo();var tenantsBefore=store.query("tenant","allForMaintenance",Map.of());var initial=ok(request(HttpMethod.GET,"/api/v1/product/policy",null));var payload=json.convertValue(initial.get("policy"),Map.class);
        var simulated=ok(request(HttpMethod.POST,"/api/v1/product/simulate",Values.map("policy",payload,"utterances",List.of("不用转人工"))));assertThat(simulated.get("external_actions_executed").asBoolean()).isFalse();assertThat(simulated.get("trace").get(0).get("intent").asText()).isEqualTo("decline_handoff");assertThat(store.query("tenant","allForMaintenance",Map.of())).isEqualTo(tenantsBefore);
        var saved=ok(request(HttpMethod.POST,"/api/v1/product/policy",JsonSupport.map("policy",payload,"expected_version_id",initial.get("version_id").isNull()?null:initial.get("version_id").asInt())));assertThat(saved.get("version_id").asInt()).isPositive();var stale=request(HttpMethod.POST,"/api/v1/product/policy",JsonSupport.map("policy",payload,"expected_version_id",null));assertThat(stale.getStatusCode().value()).isEqualTo(409);assertThat(stale.getBody().get("detail").asText()).isEqualTo("策略已更新，请刷新后重试");var current=ok(request(HttpMethod.GET,"/api/v1/product/policy",null));assertThat(current.get("version_id")).isEqualTo(saved.get("version_id"));assertThat(current.get("history").size()).isGreaterThanOrEqualTo(1);
    }
    @Test void funnelKeepsJsonNullStringNullCaseSensitivityAndUncoalescedGroupIdentity(){
        Tenant tenant=new Tenant();tenant.setId(3);tenant.setName("Funnel fixture");tenant.setCode("funnel-fixture");tenants.insert(tenant);SysUser admin=new SysUser();admin.setTenantId(3);admin.setUsername("funnel"+UUID.randomUUID());admin.setRole("admin");admin.setFullName("Funnel admin");users.insert(admin);token=crypto.token(UserRecords.map(admin));String phone=phone();List<String> values=Arrays.asList("{}","{\"outcome\":null}","{\"outcome\":\"null\"}","{\"outcome\":\"ABC\"}","{\"outcome\":\"abc\"}","{\"outcome\":\"pending\"}");for(String data:values){var call=store.insert("callsession",Values.map("tenant_id",3,"phone",phone,"mode","ai_only","status","in_ai","attempts",1,"max_attempts",1));store.insert("conversationstate",Values.map("tenant_id",3,"call_id",call.get("id"),"attempt",1,"data_json",data));}
        var funnel=ok(request(HttpMethod.GET,"/api/v1/product/funnel",null));var groups=funnel.get("groups");var observed=new ArrayList<String>();boolean nullAndMissing=false;int pending=0;for(var group:groups){String value=group.get("outcome").asText();observed.add(value);if(value.equals("pending")){pending++;if(group.get("attempts").asInt()==2&&group.get("unique_phones").asInt()==1)nullAndMissing=true;}}
        assertThat(observed).contains("null","ABC","abc");assertThat(pending).isGreaterThanOrEqualTo(2);assertThat(nullAndMissing).isTrue();
    }

}
