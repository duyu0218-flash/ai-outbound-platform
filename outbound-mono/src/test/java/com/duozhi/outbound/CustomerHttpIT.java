package com.duozhi.outbound;

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
class CustomerHttpIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("JWT_SECRET",()->"customer-http-secret-at-least-thirty-two-characters");
        p.add("spring.datasource.url",MYSQL::getJdbcUrl);p.add("spring.datasource.username",MYSQL::getUsername);p.add("spring.datasource.password",MYSQL::getPassword);
        p.add("spring.flyway.url",MYSQL::getJdbcUrl);p.add("spring.flyway.user",MYSQL::getUsername);p.add("spring.flyway.password",MYSQL::getPassword);
        p.add("spring.data.redis.host",REDIS::getHost);p.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));p.add("REDIS_URL",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
        p.add("spring.ai.model.chat",()->"none");p.add("TENANT_API_KEYS_JSON",()->"{\"1\":\"customer-test-key\"}");
    }
    @Autowired TestRestTemplate http;@Autowired ObjectMapper json;@Autowired Crypto crypto;@Autowired Store store;
    @Autowired SysUserMapper users;@Autowired TenantMapper tenants;@Autowired AuditLogMapper audits;
    @Autowired ConfigurableEnvironment environment;@Autowired CallPolicy policy;@Autowired Transactions tx;@Autowired DataRetention retention;@Autowired Outbox outbox;
    private final Map<String,Object> overrides=new HashMap<>();private String token;private SysUser user;
    @BeforeEach void seed(){
        environment.getPropertySources().addFirst(new MapPropertySource("customer-scenario",overrides));
        overrides.put("ENV","dev");overrides.put("TENANT_API_SCOPES_JSON","{}");
        for(int id:List.of(1,2))if(tenants.findById(id)==null){Tenant t=new Tenant();t.setId(id);t.setCode("customer-tenant-"+id);t.setName("Customer tenant "+id);tenants.insert(t);}
        user=new SysUser();user.setTenantId(1);user.setUsername("customer"+UUID.randomUUID().toString().replace("-",""));user.setFullName("Customer test admin");user.setRole("admin");users.insert(user);token=crypto.token(UserRecords.map(user));
    }
    @AfterEach void reset(){environment.getPropertySources().remove("customer-scenario");}
    HttpHeaders auth(){var h=new HttpHeaders();h.setBearerAuth(token);return h;}
    ResponseEntity<JsonNode> request(HttpMethod method,String path,Object body){var h=auth();h.setContentType(MediaType.APPLICATION_JSON);return http.exchange(path,method,new HttpEntity<>(body,h),JsonNode.class);}
    JsonNode ok(ResponseEntity<JsonNode> r){assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);return r.getBody();}
    String phone(){return "13"+String.format(Locale.ROOT,"%011d",Math.abs(UUID.randomUUID().getMostSignificantBits()%100000000000L));}
    JsonNode create(String number){return ok(request(HttpMethod.POST,"/api/v1/contacts",Map.of("phone",number,"name","客户Case","consent_state","consented")));}
    Map<String,Object> contact(String number){return store.insert("contact",Values.map("tenant_id",1,"phone",number,"consent_state","consented"));}
    String at(String number){return policy.checkAt(1,number,true,null,Instant.parse("2026-10-19T04:00:00Z"));}

    @Test void contactCreateReadPatchListDeleteKeepsContractAndAudit(){
        String number=phone();var created=create("+"+number);long id=created.get("id").asLong();
        assertThat(created.fieldNames()).toIterable().containsExactlyInAnyOrder("phone","name","tags","consent_state","dnc","dnc_reason","timezone","id","tenant_id","consented_at","consented_by","created_at","updated_at");
        assertThat(created.get("phone").asText()).isEqualTo(number);assertThat(created.get("consented_by").asText()).isEqualTo(user.getUsername());assertThat(created.get("dnc_reason").isNull()).isTrue();
        assertThat(ok(request(HttpMethod.GET,"/api/v1/contacts/"+id,null))).isEqualTo(created);
        var patch=ok(request(HttpMethod.PATCH,"/api/v1/contacts/"+id,Map.of("name","改名","tags","a,b")));assertThat(patch.get("phone").asText()).isEqualTo(number);assertThat(patch.get("name").asText()).isEqualTo("改名");
        var list=ok(request(HttpMethod.GET,"/api/v1/contacts?keyword="+number,null));assertThat(list).hasSize(1);
        assertThat(ok(request(HttpMethod.DELETE,"/api/v1/contacts/"+id,null)).get("result").asText()).isEqualTo("deleted");assertThat(request(HttpMethod.GET,"/api/v1/contacts/"+id,null).getStatusCode().value()).isEqualTo(404);
        assertThat(audits.list(1,0,200).stream().filter(a->String.valueOf(id).equals(a.getResourceId())&&"contact".equals(a.getResourceType())).count()).isEqualTo(3);
    }
    @Test void invalidAndDuplicateContactKeepSourceErrors(){
        String number=phone();create(number);var duplicate=request(HttpMethod.POST,"/api/v1/contacts",Map.of("phone",number));assertThat(duplicate.getStatusCode().value()).isEqualTo(409);assertThat(duplicate.getBody().get("detail").asText()).isEqualTo("contact phone already exists");
        assertThat(request(HttpMethod.POST,"/api/v1/contacts",Map.of("phone","abcdef")).getStatusCode().value()).isEqualTo(400);
        assertThat(request(HttpMethod.POST,"/api/v1/contacts",Map.of("phone",phone(),"timezone","Invalid/Zone")).getStatusCode().value()).isEqualTo(400);
        assertThat(request(HttpMethod.GET,"/api/v1/contacts?page=0",null).getStatusCode().value()).isEqualTo(400);
    }
    @Test void partialPatchAndConsentTransitionsPreserveOtherFields(){
        var c=create(phone());long id=c.get("id").asLong();var changed=ok(request(HttpMethod.PATCH,"/api/v1/contacts/"+id,Map.of("consent_state","revoked")));
        assertThat(changed.get("consented_at").isNull()).isTrue();assertThat(changed.get("consented_by").isNull()).isTrue();assertThat(changed.get("name")).isEqualTo(c.get("name"));
        var changes=new LinkedHashMap<String,Object>();changes.put("name",null);var empty=ok(request(HttpMethod.PATCH,"/api/v1/contacts/"+id,changes));assertThat(empty.get("name").isNull()).isTrue();assertThat(empty.get("phone")).isEqualTo(c.get("phone"));
    }
    @Test void batchDncSortsDeduplicatesAndReportsMissing(){
        var c=create(phone());long id=c.get("id").asLong();var result=ok(request(HttpMethod.PATCH,"/api/v1/contacts/batch-dnc",Map.of("contact_ids",List.of(2147483647L,id,id),"dnc",true,"dnc_reason","do not contact")));
        assertThat(result.get("total").asInt()).isEqualTo(2);assertThat(result.get("updated").asInt()).isEqualTo(1);assertThat(result.get("missing_contact_ids").get(0).asLong()).isEqualTo(2147483647L);
        var changed=ok(request(HttpMethod.GET,"/api/v1/contacts/"+id,null));assertThat(changed.get("dnc").asBoolean()).isTrue();assertThat(changed.get("consented_at").isNull()).isTrue();assertThat(changed.get("consent_state").asText()).isEqualTo("consented");
    }
    @Test void customerTenantAndExplicitMachinePermissionAreEnforced(){
        var foreign=store.insert("contact",Values.map("tenant_id",2,"phone",phone(),"name","foreign"));
        for(var method:List.of(HttpMethod.GET,HttpMethod.PATCH,HttpMethod.DELETE))assertThat(request(method,"/api/v1/contacts/"+foreign.get("id"),method==HttpMethod.PATCH?Map.of("name","wrong"):null).getStatusCode().value()).isEqualTo(404);
        var h=new HttpHeaders();h.set("x-api-key","customer-test-key");assertThat(http.exchange("/api/v1/contacts",HttpMethod.GET,new HttpEntity<>(h),JsonNode.class).getStatusCode().value()).isEqualTo(403);
        overrides.put("TENANT_API_SCOPES_JSON","{\"1\":[\"contacts:write\"]}");assertThat(http.exchange("/api/v1/contacts",HttpMethod.GET,new HttpEntity<>(h),JsonNode.class).getStatusCode().value()).isEqualTo(200);
    }
    ResponseEntity<JsonNode> upload(byte[] bytes,String key,boolean upsert){
        var parts=new LinkedMultiValueMap<String,Object>();parts.add("file",new ByteArrayResource(bytes){@Override public String getFilename(){return "contacts.csv";}});var h=auth();h.setContentType(MediaType.MULTIPART_FORM_DATA);h.set("Idempotency-Key",key);return http.exchange("/api/v1/contacts/import?upsert="+upsert,HttpMethod.POST,new HttpEntity<>(parts,h),JsonNode.class);
    }
    @Test void csvImportPreservesIdempotentReplayAndRowOutcomes(){
        String a=phone(),b=phone(),key=UUID.randomUUID().toString();var csv="\ufeffphone,name,consent_state,dnc,timezone\n"+a+",首条,consented,false,Asia/Shanghai\n"+b+",次条,revoked,true,Asia/Shanghai\n"+a+",重复,consented,false,Asia/Shanghai\nbad,错误,consented,false,Asia/Shanghai\n";
        var result=ok(upload(csv.getBytes(StandardCharsets.UTF_8),key,true));assertThat(result.get("total").asInt()).isEqualTo(4);assertThat(result.get("created").asInt()).isEqualTo(2);assertThat(result.get("skipped").asInt()).isEqualTo(1);assertThat(result.get("failed").asInt()).isEqualTo(1);assertThat(result.get("errors")).hasSize(2);
        assertThat(ok(upload("phone\n000000\n".getBytes(StandardCharsets.UTF_8),key,true))).isEqualTo(result);
        var updated=ok(upload(("phone,name\n"+a+",新名\n").getBytes(StandardCharsets.UTF_8),UUID.randomUUID().toString(),true));assertThat(updated.get("updated").asInt()).isEqualTo(1);
        assertThat(ok(upload(("phone,name\n"+a+",跳过\n").getBytes(StandardCharsets.UTF_8),UUID.randomUUID().toString(),false)).get("skipped").asInt()).isEqualTo(1);
    }
    @Test void csvRejectsEncodingKeyAndLimitsBeforeMutation(){
        assertThat(upload(new byte[]{(byte)0xff},UUID.randomUUID().toString(),true).getStatusCode().value()).isEqualTo(400);
        assertThat(upload("phone\n123456\n".getBytes(StandardCharsets.UTF_8),"bad key",true).getStatusCode().value()).isEqualTo(400);
        overrides.put("CONTACT_IMPORT_MAX_ROWS","1");String first=phone(),second=phone();assertThat(upload(("phone\n"+first+"\n"+second+"\n").getBytes(StandardCharsets.UTF_8),UUID.randomUUID().toString(),true).getStatusCode().value()).isEqualTo(413);
        assertThat(store.one("contact","findByPhone",Values.map("tenantId",1,"phone",first))).isNull();
        overrides.put("CONTACT_IMPORT_MAX_BYTES","2");assertThat(upload("phone\n123456\n".getBytes(StandardCharsets.UTF_8),UUID.randomUUID().toString(),true).getStatusCode().value()).isEqualTo(413);
    }
    @Test void csvExportLoadsActualDataAndEscapesFormulas(){
        var c=create(phone());ok(request(HttpMethod.PATCH,"/api/v1/contacts/"+c.get("id").asLong(),Map.of("name","=formula","tags","x,y")));
        var result=http.exchange("/api/v1/contacts/export?keyword="+c.get("phone").asText(),HttpMethod.GET,new HttpEntity<>(auth()),String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);assertThat(result.getHeaders().getContentType().toString()).contains("text/csv");assertThat(result.getBody()).startsWith("\ufeffphone,name,tags,consent_state,dnc,dnc_reason,timezone,created_at,updated_at");assertThat(result.getBody()).contains("'=formula","\"x,y\"","ConsentState.CONSENTED",c.get("phone").asText());
    }
    @Test void callHistoryPreventsDeletion(){
        var c=create(phone());store.insert("callsession",Values.map("tenant_id",1,"phone",c.get("phone").asText(),"mode","ai_only","contact_id",c.get("id").asInt()));var result=request(HttpMethod.DELETE,"/api/v1/contacts/"+c.get("id").asLong(),null);assertThat(result.getStatusCode().value()).isEqualTo(409);assertThat(result.getBody().get("detail").asText()).isEqualTo("contact is referenced by campaign or call history; mark it DNC instead");
    }
    @Test void policyChecksDncConsentWindowAndDestinationInSourceOrder(){
        String number=phone();var c=contact(number);assertThat(at(number)).isEmpty();store.update("contact",1,c.get("id"),Values.map("consent_state","revoked"));assertThat(at(number)).isEqualTo("consent_revoked");store.update("contact",1,c.get("id"),Values.map("consent_state","not_consented","dnc",true));assertThat(at(number)).isEqualTo("contact_dnc");
        overrides.put("OUTBOUND_ALLOWED_PHONE_PREFIXES","99");assertThat(at(number)).isEqualTo("platform_destination_not_allowed");store.insert("phonesuppression",Values.map("tenant_id",1,"phone",number));assertThat(at(number)).isEqualTo("contact_dnc");
        String fresh=phone();overrides.remove("OUTBOUND_ALLOWED_PHONE_PREFIXES");assertThat(at(fresh)).isEqualTo("explicit_consent_required");contact(fresh);assertThat(policy.checkAt(1,fresh,true,null,Instant.parse("2026-10-19T15:00:00Z"))).isEqualTo("outside_calling_hours");
    }
    @Test void policyDailyAttemptsAndRetryIntervalUseActualCalls(){
        String number=phone();contact(number);var call=store.insert("callsession",Values.map("tenant_id",1,"phone",number,"mode","ai_only","attempts",3,"created_at",LocalDateTime.of(2026,10,19,3,0)));assertThat(at(number)).isEqualTo("daily_attempt_limit");
        store.update("callsession",1,call.get("id"),Values.map("attempts",0,"finished_at",LocalDateTime.of(2026,10,19,3,59,30)));
        var policy=Values.map("min_attempt_interval_sec",60);store.insert("adminsetting",Values.map("tenant_id",1,"section","compliance","data_json",json.valueToTree(policy).toString()));
        try{assertThat(at(number)).isEqualTo("retry_interval_not_elapsed");assertThat(this.policy.checkAt(1,number,true,call.get("id"),Instant.parse("2026-10-19T04:00:00Z"))).isEmpty();}finally{var setting=store.one("adminsetting","findBySection",Values.map("tenantId",1,"section","compliance"));store.delete("adminsetting",1,setting.get("id"));}
    }
    @Test void transactionRollbackReleasesIdentityAndTenantLocks(){
        String number=phone();assertThatThrownBy(()->tx.run(()->{tx.platform();tx.tenant(1);store.insert("contact",Values.map("tenant_id",1,"phone",number));throw new ApiError(400,"rollback");})).isInstanceOf(ApiError.class);
        assertThat(tx.run(()->{tx.platform();tx.tenant(1);return store.one("contact","findByPhone",Values.map("tenantId",1,"phone",number));})).isNull();
    }

    @Test void retentionRemovesExpiredTextAndRedactsAllLinkedEvidence(){
        LocalDateTime old=Store.now().minusDays(400);String number=phone();var call=store.insert("callsession",Values.map("tenant_id",1,"phone",number,"mode","ai_only","status","completed","finished_at",old,"created_at",old,"last_transcript","sensitive","summary","sensitive","recording_url","https://recording.invalid/private"));Object id=call.get("id");
        var partial=store.insert("speechturn",Values.map("tenant_id",1,"call_session_id",id,"provider_event_key",UUID.randomUUID().toString(),"created_at",old,"is_final",false));
        var last=store.insert("speechturn",Values.map("tenant_id",1,"call_session_id",id,"provider_event_key",UUID.randomUUID().toString(),"created_at",old,"is_final",true));
        var recent=store.insert("speechturn",Values.map("tenant_id",1,"call_session_id",id,"provider_event_key",UUID.randomUUID().toString(),"is_final",true));
        var state=store.insert("conversationstate",Values.map("tenant_id",1,"call_id",id,"data_json","{\"phone\":\"private\"}","policy_json","{\"private\":true}"));
        var event=store.insert("callevent",Values.map("call_session_id",id,"event_type","status","payload","{\"private\":true}","created_at",old));
        var analysis=store.insert("callanalysis",Values.map("tenant_id",1,"call_session_id",id,"summary","sensitive","needs_review",true));
        var task=outbox.enqueue(1,"ai_turn",id,"retention-ai:"+id,Values.map("private","private"));
        var sms=store.insert("smslog",Values.map("tenant_id",1,"call_session_id",id,"to_phone",number,"content","private"));
        var counts=retention.purge(1000);assertThat(counts.get("redacted_calls")).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(()->store.require("speechturn",1,partial.get("id"))).isInstanceOf(ApiError.class);assertThatThrownBy(()->store.require("speechturn",1,last.get("id"))).isInstanceOf(ApiError.class);assertThat(store.require("speechturn",1,recent.get("id"))).isNotNull();
        var redacted=store.require("callsession",1,id);assertThat(redacted.get("phone").toString()).startsWith("redacted:");assertThat(redacted.get("last_transcript")).isNull();assertThat(redacted.get("summary")).isNull();assertThat(redacted.get("recording_url")).isNull();
        var cleared=store.require("conversationstate",1,state.get("id"));assertThat(cleared.get("data_json")).isEqualTo("{}");assertThat(((Number)cleared.get("generation")).intValue()).isEqualTo(1);assertThat(store.require("callevent",1,event.get("id")).get("payload")).isEqualTo("{}");
        assertThat(store.require("callanalysis",1,analysis.get("id")).get("needs_review")).isEqualTo(false);assertThat(store.require("taskoutbox",1,task.get("id")).get("state")).isEqualTo("dead");assertThat(store.require("smslog",1,sms.get("id")).get("content")).isEqualTo("");
    }
    @Test void recordingExpirationEnqueuesOneDurableDeletionAndArchiveRetainsIdempotency(){
        LocalDateTime old=Store.now().minusDays(400);var call=store.insert("callsession",Values.map("tenant_id",2,"phone","redacted:archive-test","mode","ai_only","status","completed","finished_at",old,"created_at",old));Object id=call.get("id");
        var asset=store.insert("recordingasset",Values.map("tenant_id",2,"call_session_id",id,"retention_until",old));retention.purge(1000);assertThat(store.require("recordingasset",2,asset.get("id")).get("state")).isEqualTo("deletion_pending");
        var task=store.one("taskoutbox","findByIdempotencyKey",Values.map("tenantId",2,"key","recording-delete:"+asset.get("id")));assertThat(task).isNotNull();retention.purge(1000);assertThat(store.one("taskoutbox","findByIdempotencyKey",Values.map("tenantId",2,"key","recording-delete:"+asset.get("id"))).get("id")).isEqualTo(task.get("id"));
        String key="archive:"+id;var archived=store.insert("taskoutbox",Values.map("tenant_id",2,"task_type","call_analysis","aggregate_id",id,"idempotency_key",key,"state","completed","updated_at",old,"created_at",old));
        assertThat(retention.archive(1000)).isGreaterThanOrEqualTo(1);assertThatThrownBy(()->store.require("taskoutbox",2,archived.get("id"))).isInstanceOf(ApiError.class);
        var replay=outbox.enqueue(2,"call_analysis",id,key,Map.of());assertThat(replay.get("id")).isEqualTo(archived.get("id"));assertThat(store.one("taskoutbox","findByIdempotencyKey",Values.map("tenantId",2,"key",key))).isNull();
    }
    @Test void outboxUsesUniqueKeyAndSupersedesOnlyEarlierFinalAiTurns(){
        String aggregate=UUID.randomUUID().toString();String key="ai-test:"+aggregate;
        var first=outbox.enqueue(1,"ai_turn",aggregate,key,Values.map("attempt",1,"turn_sequence",1));assertThat(outbox.enqueue(1,"ai_turn",aggregate,key,Values.map("attempt",1,"turn_sequence",1)).get("id")).isEqualTo(first.get("id"));
        var other=outbox.enqueue(1,"ai_turn",aggregate,key+":other",Values.map("attempt",2,"turn_sequence",1));outbox.enqueue(1,"ai_turn",aggregate,key+":second",Values.map("attempt",1,"turn_sequence",2));
        assertThat(store.require("taskoutbox",1,first.get("id")).get("state")).isEqualTo("completed");assertThat(store.require("taskoutbox",1,other.get("id")).get("state")).isEqualTo("pending");
    }
}
