package com.duozhi.outbound;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OutboundReliabilityIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7").withExposedPorts(6379);
    @DynamicPropertySource static void config(DynamicPropertyRegistry p){
        p.add("JWT_SECRET",()->"outbound-reliability-test-secret-more-than-32-chars");
        p.add("spring.datasource.url",MYSQL::getJdbcUrl);p.add("spring.datasource.username",MYSQL::getUsername);p.add("spring.datasource.password",MYSQL::getPassword);
        p.add("spring.flyway.url",MYSQL::getJdbcUrl);p.add("spring.flyway.user",MYSQL::getUsername);p.add("spring.flyway.password",MYSQL::getPassword);
        p.add("spring.data.redis.host",REDIS::getHost);p.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));p.add("REDIS_URL",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
        p.add("JAVA_WORKERS_ENABLED",()->"false");p.add("JAVA_DISPATCH_ENABLED",()->"true");p.add("TELEPHONY_PROVIDER",()->"mock");
        p.add("OUTBOUND_PLATFORM_MAX_CONCURRENT",()->"500");
        p.add("JAVA_DIAL_BATCH_SIZE",()->"100");
        p.add("JAVA_CALLBACK_BATCH_SIZE",()->"100");
    }
    @Autowired Store store;@Autowired OperationsStore db;@Autowired Transactions tx;@Autowired DialService dial;@Autowired Handoffs handoffs;
    @Autowired HumanRouting routing;@Autowired ConversationPolicy conversation;@Autowired CallLifecycle lifecycle;@Autowired JsonSupport json;
    @Autowired PlatformLock platformLocks;@Autowired javax.sql.DataSource dataSource;
    @Autowired DialBatchStore dialDb;@Autowired TaskWorker worker;
    @Autowired CallbackInbox inbox;@Autowired CallbackBatchStore callbackDb;
    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    @Test void jdbcBatchRewriteIsEnabledOnTheActualBusinessConnection()throws Exception{try(var connection=dataSource.getConnection()){
        var properties=connection.unwrap(com.mysql.cj.jdbc.JdbcConnection.class).getPropertySet();
        for(var key:List.of(com.mysql.cj.conf.PropertyKey.rewriteBatchedStatements,com.mysql.cj.conf.PropertyKey.useServerPrepStmts,com.mysql.cj.conf.PropertyKey.cachePrepStmts))assertThat(properties.getBooleanProperty(key).getValue()).isTrue();
        assertThat(properties.getIntegerProperty(com.mysql.cj.conf.PropertyKey.prepStmtCacheSize).getValue()).isEqualTo(128);
    }}
    static AtomicInteger sequence=new AtomicInteger(1000);long tenant;
    @Test void admissionTenantReadDoesNotBlockUnrelatedAiMetricForeignKeyInsert()throws Exception{
        var call=call("in_ai","ai_only");var pool=Executors.newFixedThreadPool(2);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try{
            var owner=pool.submit(()->tx.serialized(()->{tx.tenant(tenant);locked.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixture release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return null;}));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var writer=pool.submit(()->tx.run(()->{store.appendMetric(JsonSupport.map("tenant_id",tenant,"call_session_id",call.get("id"),"stage","fixture.concurrent-ai","success",true));return null;}));
            writer.get(2,TimeUnit.SECONDS);
            var disabling=new CountDownLatch(1);var disable=pool.submit(()->tx.run(()->{disabling.countDown();store.update("tenant",0,tenant,Map.of("enabled",false));return null;}));
            assertThat(disabling.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->disable.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();owner.get(5,TimeUnit.SECONDS);disable.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void ordinaryDialoguePreparesWhileAdmissionIsLockedButSuppressionRetriesAtomically()throws Exception{
        var call=call("in_ai","ai_only");policy(call,Map.of());var pool=Executors.newFixedThreadPool(2);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try{
            var holder=pool.submit(()->tx.serialized(()->{locked.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixture release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return null;}));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var ordinary=pool.submit(()->tx.run(()->conversation.localPreparation(()->conversation.prepare(tx.call(call.get("id")),"稍等一下"))));
            assertThat(ordinary.get(2,TimeUnit.SECONDS).get("tts_text").toString()).contains("稍等");
            release.countDown();holder.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();pool.shutdownNow();}
        var before=conversation.state(call);Object generation=before.get("generation");
        assertThatThrownBy(()->tx.run(()->conversation.localPreparation(()->conversation.prepare(tx.call(call.get("id")),"不要再打电话")))).isInstanceOf(ConversationPolicy.SharedPreparationRequired.class);
        assertThat(store.scalar("phonesuppression","countPhone",JsonSupport.map("tenantId",tenant,"phone",call.get("phone")))).isZero();
        assertThat(conversation.state(call).get("generation")).isEqualTo(generation);
        assertThat(tx.serialized(()->conversation.prepare(tx.call(call.get("id")),"不要再打电话")).get("action")).isEqualTo("hangup");
        assertThat(store.scalar("phonesuppression","countPhone",JsonSupport.map("tenantId",tenant,"phone",call.get("phone")))).isEqualTo(1);
    }
    @Test void initialBatchCannotApplyProviderMappingThatReleasesSharedCapacity(){
        var call=call("dialing","ai_only");var body=JsonSupport.map("call_id",call.get("id"),"payload",JsonSupport.map("status","dialing","attempt",1,"event_id","mapped-fixture"));
        String property="mapped-status-fixture";environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(property,Map.of("NO_ANSWER_CODES","[\"dialing\"]")));
        try{
            assertThatThrownBy(()->tx.run(()->{var locked=tx.call(call.get("id"));try(var scope=new CallStatusBatchScope()){scope.calls.put(call.get("id").toString(),locked);return lifecycle.status(body);}})).isInstanceOf(IllegalStateException.class).hasMessageContaining("requires platform transaction");
            assertThat(store.require("callsession",tenant,call.get("id")).get("status")).isEqualTo("dialing");
            lifecycle.status(body);assertThat(store.require("callsession",tenant,call.get("id")).get("status")).isEqualTo("no_answer");
        }finally{environment.getPropertySources().remove(property);}
    }
    @Test void concurrentAcceptanceIsIdempotentAndDoesNotHoldTheSharedAdmissionLock()throws Exception{
        var call=call("dialing","ai_only");var entries=List.of(JsonSupport.map("call",call,"response",JsonSupport.map("provider_call_id","synthetic-ack")));var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try(var held=platformLocks.lock(718492061L)){
            var futures=new ArrayList<Future<?>>();for(int i=0;i<2;i++)futures.add(pool.submit(()->{try{start.await();dial.acknowledgeTasks(entries,dialDb);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));
            start.countDown();for(var future:futures)future.get(3,TimeUnit.SECONDS);
        }finally{start.countDown();pool.shutdownNow();}
        assertThat(store.scalar("callevent","dialAcceptedAttempt",JsonSupport.map("tenantId",tenant,"id",store.encode("callsession","id",call.get("id")),"attempt",1))).isEqualTo(1);
        assertThat(store.require("callsession",tenant,call.get("id")).get("telephony_call_id")).isEqualTo("synthetic-ack");
    }
    @BeforeEach void seed(){tenant=sequence.incrementAndGet();store.insert("tenant",JsonSupport.map("id",tenant,"code","reliability-"+tenant,"name","Synthetic reliability"));store.insert("adminsetting",JsonSupport.map("tenant_id",tenant,"section","compliance","data_json","{\"allowed_start_hour\":0,\"allowed_end_hour\":0}"));}
    @AfterEach void releaseFixtureCapacity(){for(var c:store.query("callsession","list",JsonSupport.map("tenantId",tenant,"offset",0,"limit",1000)))if(!CallLifecycle.TERMINAL.contains(c.get("status")))store.update("callsession",tenant,c.get("id"),Map.of("status","completed"));}
    Map<String,Object> call(String state,String mode){return store.insert("callsession",JsonSupport.map("tenant_id",tenant,"phone","1555"+String.format("%07d",sequence.incrementAndGet()),"mode",mode,"status",state,"attempts",state.equals("queued")?0:1,"max_attempts",2));}
    Map<String,Object> agent(String state){return store.insert("user",JsonSupport.map("tenant_id",tenant,"username","desk-"+UUID.randomUUID(),"full_name","Synthetic desk","role","agent","agent_status",state,"last_seen_at",Store.now()));}
    void policy(Map<String,Object> call,Map<String,Object> changes){tx.serialized(()->{var state=conversation.state(call);var p=conversation.policy(state);p.putAll(changes);p.put("start_hour",0);p.put("end_hour",24);store.update("conversationstate",tenant,state.get("id"),JsonSupport.map("policy_json",json.json(p)));return null;});}
    void consent(Map<String,Object> call){store.insert("contact",JsonSupport.map("tenant_id",tenant,"phone",call.get("phone"),"consent_state","consented"));}
    @Test void frozenCommandCarriesTheSelectedTenantLineForGatewayAuthorization(){
        var c=call("dialing","ai_only");var line=store.insert("telephonyline",JsonSupport.map("tenant_id",tenant,"name","Synthetic authorized line","provider","mock","caller_id","+8613800138000"));var bound=store.update("callsession",tenant,c.get("id"),Map.of("telephony_line_id",line.get("id")));var metadata=dial.metadataFor(bound);
        assertThat(metadata.get("telephony_line_id")).isEqualTo(line.get("id"));assertThat(metadata.get("caller_id")).isEqualTo("+8613800138000");
    }
    @Test void bufferedCaseUpdatesKeepWriteOrderAndWrongTenantRollsBackOtherChanges(){
        var a=call("queued","ai_only");var b=call("queued","ai_only");
        store.update("callsession",tenant,a.get("id"),Map.of("last_error","fixture"));store.update("callsession",tenant,b.get("id"),Map.of("last_error","fixture"));
        tx.serialized(()->store.bufferWrites(()->{store.writeLocked("callsession",tenant,a.get("id"),JsonSupport.map("status","dialing","last_error",null));store.writeLocked("callsession",tenant,b.get("id"),JsonSupport.map("status","dialing","last_error",null));store.writeLocked("callsession",tenant,a.get("id"),Map.of("status","answered"));return null;}));
        assertThat(store.require("callsession",tenant,a.get("id")).get("status")).isEqualTo("answered");assertThat(store.require("callsession",tenant,b.get("id")).get("status")).isEqualTo("dialing");
        assertThat(store.require("callsession",tenant,a.get("id")).get("last_error")).isNull();assertThat(store.require("callsession",tenant,b.get("id")).get("last_error")).isNull();
        assertThatThrownBy(()->tx.serialized(()->store.bufferWrites(()->{store.writeLocked("callsession",tenant,a.get("id"),Map.of("status","failed"));store.writeLocked("callsession",tenant+100000,b.get("id"),Map.of("status","failed"));return null;}))).isInstanceOfAny(ApiError.class,IllegalStateException.class);
        assertThat(store.require("callsession",tenant,a.get("id")).get("status")).isEqualTo("answered");assertThat(store.require("callsession",tenant,b.get("id")).get("status")).isEqualTo("dialing");
    }
    @Test void bufferedGeneratedKeysReadYourWritesAndRollbackUseTheBusinessTransaction(){
        var c=call("queued","ai_only");var states=new ArrayList<Map<String,Object>>();
        assertThatThrownBy(()->tx.serialized(()->{store.bufferWrites(()->{
            store.writeLocked("callsession",tenant,c.get("id"),Map.of("status","dialing"));
            for(int attempt:List.of(1,2))states.add(store.insert("conversationstate",JsonSupport.map("tenant_id",tenant,"call_id",c.get("id"),"attempt",attempt,"policy_json","{}")));
            store.insert("callevent",JsonSupport.map("call_session_id",c.get("id"),"event_type","synthetic_rollback","payload","{}"));
            assertThat(store.require("callsession",tenant,c.get("id")).get("status")).isEqualTo("dialing");return null;});
            assertThat(states).allSatisfy(s->assertThat(s.get("id")).isNotNull());assertThat(states.get(0).get("id")).isNotEqualTo(states.get(1).get("id"));throw new IllegalStateException("synthetic crash before commit");
        })).isInstanceOf(IllegalStateException.class).hasMessage("synthetic crash before commit");
        assertThat(store.require("callsession",tenant,c.get("id")).get("status")).isEqualTo("queued");
        assertThat(store.one("conversationstate","scopedConversationPolicy30",Store.scopedParameters(tenant,store.encode("callsession","id",c.get("id")),1))).isNull();
    }
    @Test void largeBufferedInsertsKeepGeneratedKeysAndAtomicRollbackAcrossPacketChunks(){
        var call=call("dialing","ai_only");String payload=json.json(Map.of("text","中".repeat(4096)));var rows=new ArrayList<Map<String,Object>>();
        tx.run(()->store.bufferWrites(()->{for(int i=0;i<160;i++)rows.add(store.insert("callevent",JsonSupport.map("call_session_id",call.get("id"),"event_type","packet_fixture","payload",payload)));return null;}));
        assertThat(rows).allSatisfy(row->assertThat(row.get("id")).isNotNull());assertThat(rows.stream().map(row->row.get("id")).distinct().count()).isEqualTo(160);
        assertThat(store.query("callevent","list",Map.of("tenantId",tenant,"offset",0,"limit",1000))).hasSize(160);
        assertThatThrownBy(()->tx.run(()->{store.bufferWrites(()->{for(int i=0;i<160;i++)store.insert("callevent",JsonSupport.map("call_session_id",call.get("id"),"event_type","packet_rollback","payload",payload));return null;});throw new IllegalStateException("rollback after every packet flushed");})).isInstanceOf(IllegalStateException.class).hasMessage("rollback after every packet flushed");
        assertThat(store.query("callevent","list",Map.of("tenantId",tenant,"offset",0,"limit",1000))).hasSize(160);
    }
    @Test void largerCohortKeepsFactsAndConsentAcrossBoundedReadChunks(){
        var calls=new ArrayList<Map<String,Object>>();for(int i=0;i<125;i++){var call=call("queued","ai_only");consent(call);calls.add(call);}
        tx.serialized(()->{try(var scope=new DialBatchScope()){
            dialDb.prepare(scope,tenant,calls.stream().map(call->call.get("id").toString()).toList());
            assertThat(scope.calls).hasSize(125);assertThat(scope.facts).hasSize(125);assertThat(scope.contacts).hasSize(125);
            assertThat(calls).allSatisfy(call->{String key=tenant+":"+call.get("phone");assertThat(scope.contacts.get(key).get("consent_state")).isEqualTo("consented");assertThat(((Number)scope.facts.get(key).get("active")).longValue()).isZero();});
        }return null;});
    }
    @Test void oneCohortKeepsPhoneMutualExclusionConsentAndPerAttemptAcknowledgements(){
        var a=call("queued","ai_only");consent(a);var b=call("queued","ai_only");store.update("callsession",tenant,b.get("id"),Map.of("phone",a.get("phone")));
        var denied=call("queued","ai_only");var good=call("queued","ai_only");consent(good);
        worker.enqueueDue();assertThat(worker.processLane(tenant,0)).isTrue();
        var statuses=List.of(store.require("callsession",tenant,a.get("id")).get("status"),store.require("callsession",tenant,b.get("id")).get("status"));assertThat(statuses).containsExactlyInAnyOrder("dialing","queued");
        assertThat(store.require("callsession",tenant,denied.get("id")).get("status")).isEqualTo("failed");assertThat(store.require("callsession",tenant,good.get("id")).get("status")).isEqualTo("dialing");
        assertThat(dialDb.rows("dialAcks",Map.of("tenantId",tenant,"ids",List.of(a.get("id"),b.get("id"),good.get("id"))))).hasSize(2);
    }
    @Test void parallelCohortsCannotAdmitTheSamePhoneTwice()throws Exception{
        var first=call("queued","ai_only");consent(first);var ids=new ArrayList<String>();ids.add(first.get("id").toString());for(int i=0;i<100;i++){var next=call("queued","ai_only");store.update("callsession",tenant,next.get("id"),Map.of("phone",first.get("phone")));ids.add(next.get("id").toString());}
        worker.enqueueDue();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{var a=pool.submit(()->{gate.await();return worker.processLane(tenant,0);});var b=pool.submit(()->{gate.await();return worker.processLane(tenant,0);});gate.countDown();boolean ar=a.get(20,TimeUnit.SECONDS),br=b.get(20,TimeUnit.SECONDS);assertThat(ar||br).isTrue();}finally{pool.shutdownNow();}
        assertThat(dialDb.rows("dialAcks",Map.of("tenantId",tenant,"ids",ids))).hasSize(1);assertThat(store.scalar("callsession","activePhone",JsonSupport.map("tenantId",tenant,"phone",first.get("phone"),"id",UUID.randomUUID()))).isEqualTo(1);
    }
    @Test void finishingOneCohortDoesNotWaitForUnrelatedTaskRows()throws Exception{
        var rows=new ArrayList<Map<String,Object>>();tx.run(()->store.bufferWrites(()->{for(int i=0;i<1000;i++)rows.add(store.insert("taskoutbox",JsonSupport.map("tenant_id",tenant,"task_type","dial_call","aggregate_id","synthetic-lock-scope-"+i,"idempotency_key","synthetic-lock-scope-"+i,"payload_json","{}")));return null;}));
        rows.sort(Comparator.comparing(r->r.get("id").toString()));var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var holder=pool.submit(()->tx.run(()->{assertThat(dialDb.lockedTasks(Map.of("tenantId",tenant,"ids",rows.subList(0,100).stream().map(r->r.get("id").toString()).toList()))).hasSize(100);locked.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test lock timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return null;}));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();var other=pool.submit(()->tx.run(()->store.bufferWrites(()->{for(var row:rows.subList(100,200))store.writeLocked("taskoutbox",tenant,row.get("id"),Map.of("last_error","synthetic-disjoint-cohort"));return null;})));
            other.get(3,TimeUnit.SECONDS);release.countDown();holder.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();pool.shutdownNow();}
        assertThat(store.require("taskoutbox",tenant,rows.get(100).get("id")).get("last_error")).isEqualTo("synthetic-disjoint-cohort");
    }
    @Test void crashAfterReservationReclaimsFrozenCommandWithoutSpendingAnotherAttempt(){
        var c=call("queued","ai_only");consent(c);worker.enqueueDue();
        var tasks=tx.run(()->{var found=dialDb.tasks(JsonSupport.map("tenantId",tenant,"now",Store.now(),"cutoff",Store.now(),"limit",100));for(var task:found)store.update("taskoutbox",tenant,task.get("id"),JsonSupport.map("state","processing","lease_token","synthetic-expired","locked_at",Store.now().minusSeconds(60),"attempts",1));return found;});
        assertThat(dial.prepareTasks(tasks,dialDb)).hasSize(1);assertThat(store.require("callsession",tenant,c.get("id")).get("attempts")).isEqualTo(1);
        assertThat(worker.processLane(tenant,0)).isTrue();assertThat(store.require("callsession",tenant,c.get("id")).get("attempts")).isEqualTo(1);
        var task=store.require("taskoutbox",tenant,tasks.get(0).get("id"));assertThat(task.get("state")).isEqualTo("completed");
        store.update("taskoutbox",tenant,task.get("id"),Map.of("state","failed","available_at",Store.now()));assertThat(worker.processLane(tenant,0)).isTrue();
        assertThat(dialDb.rows("dialAcks",Map.of("tenantId",tenant,"ids",List.of(c.get("id"))))).hasSize(1);
    }
    @Test void batchedStatusReceiptsKeepFifoIdempotencyUsageAndOpeningAiTask(){
        inbox.initialize();inbox.maintenance();var c=call("dialing","ai_only");
        var events=new ArrayList<Map<String,Object>>();for(String status:List.of("dialing","answered"))events.add(JsonSupport.map("id",status,"kind","status","payload",JsonSupport.map("call_id",c.get("id"),"kind","status","payload",JsonSupport.map("status",status,"attempt",1,"event_id",c.get("id")+":"+status))));
        inbox.batch(events);inbox.batch(events);
        assertThat(inbox.consumeBatch(100)).isEqualTo(1);assertThat(store.require("callsession",tenant,c.get("id")).get("status")).isEqualTo("dialing");
        assertThat(inbox.consumeBatch(100)).isEqualTo(1);assertThat(store.require("callsession",tenant,c.get("id")).get("status")).isEqualTo("answered");assertThat(inbox.consumeBatch(100)).isZero();
        var tasks=callbackDb.records("tasks","taskoutbox",Map.of("tenantId",tenant,"ids",List.of(c.get("id"))));assertThat(tasks.stream().filter(t->"ai_turn".equals(t.get("task_type"))).toList()).hasSize(1);
        assertThat(callbackDb.records("usage","callusage",Map.of("tenantId",tenant,"ids",List.of(c.get("id"))))).singleElement().satisfies(u->assertThat(u.get("answered_at")).isNotNull());
        assertThat(store.query("callbackinboxpartition","list",Map.of("offset",0,"limit",100))).allSatisfy(p->{assertThat(((Number)p.get("pending_count")).longValue()).isZero();assertThat(((Number)p.get("pending_bytes")).longValue()).isZero();});
        var changed=JsonSupport.map("id","conflict","kind","status","payload",JsonSupport.map("call_id",c.get("id"),"payload",JsonSupport.map("status","answered","attempt",1,"event_id",c.get("id")+":dialing")));
        assertThatThrownBy(()->inbox.batch(List.of(changed))).isInstanceOf(ApiError.class);
    }
    @Test void twoCallbackOwnersKeepDisjointPartitionsAndPerCallFifo()throws Exception{
        inbox.initialize();inbox.maintenance();var calls=new ArrayList<Map<String,Object>>();var events=new ArrayList<Map<String,Object>>();
        for(int i=0;i<48;i++){var call=call("dialing","ai_only");calls.add(call);for(String status:List.of("dialing","answered"))events.add(JsonSupport.map("id",call.get("id")+":"+status,"kind","status","payload",JsonSupport.map("call_id",call.get("id"),"payload",JsonSupport.map("status",status,"attempt",1,"event_id",call.get("id")+":"+status))));}
        inbox.batch(events);var pool=Executors.newFixedThreadPool(2);
        try{
            for(String expected:List.of("dialing","answered")){
                var gate=new CountDownLatch(1);var first=pool.submit(()->{gate.await();return inbox.consumeBatch(250,0,2);});var second=pool.submit(()->{gate.await();return inbox.consumeBatch(250,1,2);});gate.countDown();
                int a=first.get(10,TimeUnit.SECONDS),b=second.get(10,TimeUnit.SECONDS);assertThat(a).isPositive();assertThat(b).isPositive();assertThat(a+b).isEqualTo(calls.size());
                assertThat(calls).allSatisfy(call->assertThat(store.require("callsession",tenant,call.get("id")).get("status")).isEqualTo(expected));
            }
        }finally{pool.shutdownNow();}
        assertThat(inbox.consumeBatch(250,0,2)+inbox.consumeBatch(250,1,2)).isZero();
        var tasks=callbackDb.records("tasks","taskoutbox",Map.of("tenantId",tenant,"ids",calls.stream().map(call->call.get("id")).toList()));assertThat(tasks.stream().filter(task->"ai_turn".equals(task.get("task_type"))).count()).isEqualTo(48);
    }
    @Test void uuidCaseVariantsUseOnePartitionAndStillApplyBothFifoTransitions(){
        inbox.initialize();inbox.maintenance();var call=call("dialing","ai_only");String lower=call.get("id").toString(),upper=lower.toUpperCase(Locale.ROOT);
        inbox.batch(List.of(
            JsonSupport.map("id","upper-dial","kind","status","payload",JsonSupport.map("call_id",upper,"payload",JsonSupport.map("status","dialing","attempt",1,"event_id","upper-dial"))),
            JsonSupport.map("id","lower-answer","kind","status","payload",JsonSupport.map("call_id",lower,"payload",JsonSupport.map("status","answered","attempt",1,"event_id","lower-answer")))));
        var receipts=store.query("callbackinbox","list",Map.of("offset",0,"limit",10000)).stream().filter(row->lower.equals(row.get("call_id"))).toList();assertThat(receipts).hasSize(2);assertThat(receipts.stream().map(row->row.get("partition_id")).distinct().count()).isEqualTo(1);
        int owner=((Number)receipts.get(0).get("partition_id")).intValue()%2;
        assertThat(inbox.consumeBatch(250,1-owner,2)).isZero();assertThat(inbox.consumeBatch(250,owner,2)).isEqualTo(1);
        assertThat(store.require("callsession",tenant,lower).get("status")).isEqualTo("dialing");
        assertThat(inbox.consumeBatch(250,owner,2)).isEqualTo(1);assertThat(store.require("callsession",tenant,lower).get("status")).isEqualTo("answered");
    }
    @Test void statusCohortDoesNotLockUnrelatedInboxPartitions()throws Exception{
        inbox.initialize();inbox.maintenance();var c=call("dialing","ai_only");int partition=(int)((java.nio.ByteBuffer.wrap(JsonSupport.digest(c.get("id").toString())).getInt()&0xffffffffL)%64),other=(partition+1)%64;
        inbox.receive("telephony_status",JsonSupport.map("call_id",c.get("id"),"payload",JsonSupport.map("status","answered","attempt",1,"event_id","partition-isolation")));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{
            var holder=pool.submit(()->tx.run(()->{store.one("callbackinboxpartition","sourceCallbackInbox56_4",Store.queryParameters(-1,other));locked.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test lock timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return null;}));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();var consumed=pool.submit(()->inbox.consumeBatch(100));assertThat(consumed.get(3,TimeUnit.SECONDS)).isEqualTo(1);release.countDown();holder.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void statusBatchCannotPassEarlierTerminalReceiptOrResurrectItsCall(){
        inbox.initialize();inbox.maintenance();var c=call("dialing","ai_only");
        inbox.receive("telephony_status",JsonSupport.map("call_id",c.get("id"),"payload",JsonSupport.map("status","completed","attempt",1,"event_id","ended")));
        inbox.batch(List.of(JsonSupport.map("id","late","kind","status","payload",JsonSupport.map("call_id",c.get("id"),"payload",JsonSupport.map("status","answered","attempt",1,"event_id","late-answer")))));
        assertThat(inbox.consumeBatch(100)).isZero();int partition=(int)((java.nio.ByteBuffer.wrap(JsonSupport.digest(c.get("id").toString())).getInt()&0xffffffffL)%64);assertThat(inbox.consume(partition)).isTrue();
        assertThat(inbox.consumeBatch(100)).isEqualTo(1);assertThat(store.require("callsession",tenant,c.get("id")).get("status")).isEqualTo("completed");
        assertThat(callbackDb.records("tasks","taskoutbox",Map.of("tenantId",tenant,"ids",List.of(c.get("id")))).stream().filter(t->"ai_turn".equals(t.get("task_type"))).toList()).isEmpty();
    }
    @Test void concurrentDialEntrancesWaitForPlatformBeforeBorrowingConnections()throws Exception{
        var pool=Executors.newFixedThreadPool(4);var started=new CountDownLatch(4);var threads=new CopyOnWriteArrayList<Thread>();var futures=new ArrayList<Future<?>>();
        var phones=new ArrayList<String>();for(int i=0;i<4;i++){String phone="1555"+String.format("%07d",sequence.incrementAndGet());phones.add(phone);store.insert("contact",JsonSupport.map("tenant_id",tenant,"phone",phone,"consent_state","consented"));}
        try{
            try(var held=platformLocks.lock(718492061L)){
                for(String phone:phones)futures.add(pool.submit(()->{threads.add(Thread.currentThread());started.countDown();return dial.create(new Identity(tenant,null,Set::of),Map.of("phone",phone,"mode","ai_only","max_attempts",1));}));
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline&&!threads.stream().allMatch(t->t.getState()==Thread.State.WAITING))Thread.sleep(10);
                assertThat(threads).hasSize(4).allSatisfy(t->assertThat(t.getState()).isEqualTo(Thread.State.WAITING));
                assertThat(((com.alibaba.druid.pool.DruidDataSource)dataSource).getActiveCount()).as("Dial requests must not exhaust connections while waiting for admission").isZero();
            }
            for(var future:futures)assertThat(future.get(15,TimeUnit.SECONDS)).isNotNull();
        }finally{pool.shutdownNow();}
    }
    @Test void parallelCallsToSameNumberAdmitExactlyOneAndCanContinueAfterHangup()throws Exception{
        var first=call("queued","ai_only");store.insert("contact",JsonSupport.map("tenant_id",tenant,"phone",first.get("phone"),"consent_state","consented"));var second=call("queued","ai_only");store.update("callsession",tenant,second.get("id"),JsonSupport.map("phone",first.get("phone")));
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{
            var a=pool.submit(()->{gate.await();return dial.dispatch(first.get("id"));});var b=pool.submit(()->{gate.await();return dial.dispatch(second.get("id"));});gate.countDown();
            boolean ar=a.get(15,TimeUnit.SECONDS),br=b.get(15,TimeUnit.SECONDS);assertThat(ar^br).as("first="+store.require("callsession",tenant,first.get("id"))+" second="+store.require("callsession",tenant,second.get("id"))).isTrue();
            var active=ar?first:second;var pending=ar?second:first;
            lifecycle.status(JsonSupport.map("call_id",active.get("id"),"payload",JsonSupport.map("status","completed","attempt",1,"event_id",UUID.randomUUID().toString())));
            assertThat(dial.dispatch(pending.get("id"))).isTrue();
        }finally{pool.shutdownNow();}
    }
    @Test void commandAcknowledgementDoesNotBecomeAnAcceptedBridge(){
        var a=agent("ready");var c=call("in_ai","ai_handoff");policy(c,Map.of("handoff_agent_ids",List.of(a.get("id"))));
        var h=tx.serialized(()->handoffs.queue(c,"customer_request"));var identity=new Identity(tenant,a,Set::of);
        assertThat(handoffs.transfer(identity,c.get("id"),h.get("id"),a.get("id"),"accept").get("state")).isEqualTo("accepting");
        assertThat(store.require("callsession",tenant,c.get("id")).get("status")).isEqualTo("handoff_transferring");
        lifecycle.status(JsonSupport.map("call_id",c.get("id"),"payload",JsonSupport.map("status","human_connected","attempt",1,"event_id","bridge")));
        assertThat(store.require("handoffrequest",tenant,h.get("id")).get("state")).isEqualTo("accepted");
    }
    @Test void waitingQueueReassignsWhenDeskReturnsAndRespectsSkillsAndDisabledGroups(){
        var right=agent("offline");var wrong=agent("ready");var c=call("in_ai","ai_handoff");
        var team=JsonSupport.map("tenantId",tenant,"name","北京口腔","settings_json",json.json(Map.of("routing_skills",List.of("北京","口腔","普通话"))));db.write("teamCreate",team);
        db.write("memberAdd",JsonSupport.map("tenantId",tenant,"team_id",team.get("team_id"),"member_id",right.get("id"),"is_lead",false));
        policy(c,Map.of("handoff_team_ids",List.of(team.get("team_id")),"handoff_skills",List.of("北京","口腔")));
        var h=tx.serialized(()->handoffs.queue(c,"qualified"));assertThat(h.get("assigned_agent_id")).isNull();
        store.update("user",tenant,right.get("id"),JsonSupport.map("agent_status","ready","last_seen_at",Store.now()));assertThat(routing.redistribute()).isGreaterThanOrEqualTo(1);
        assertThat(store.require("handoffrequest",tenant,h.get("id")).get("assigned_agent_id").toString()).isEqualTo(right.get("id").toString());
        assertThat(store.require("user",tenant,wrong.get("id")).get("agent_status")).isEqualTo("ready");
        var args=JsonSupport.map("tenantId",tenant,"team_id",team.get("team_id"),"name","北京口腔","settings_json",team.get("settings_json"),"enabled",false,"revision",0);db.write("teamUpdate",args);
        assertThat(tx.serialized(()->routing.eligible(c,right.get("id")))).isFalse();
    }
    @Test void fullHumanQueueStopsNewDialAndDrainingQueueRestoresAdmission(){
        var a=agent("ready");var next=call("queued","ai_handoff");policy(next,Map.of("handoff_agent_ids",List.of(a.get("id")),"handoff_queue_limit",1,"handoff_expected_ratio",1.0));
        var waiting=call("waiting_human","ai_handoff");assertThat(tx.serialized(()->routing.permits(next))).isFalse();
        store.update("callsession",tenant,waiting.get("id"),JsonSupport.map("status","completed"));assertThat(tx.serialized(()->routing.permits(next))).isTrue();
    }
    @Test void oneBatchCannotSpendTheSharedHumanReserveMoreThanOnce(){
        agent("ready");store.insert("scenarioversion",JsonSupport.map("tenant_id",tenant,"policy_json",json.json(JsonSupport.map("timezone","Asia/Shanghai","start_hour",0,"end_hour",24,"weekdays",List.of(0,1,2,3,4,5,6),"handoff_queue_limit",1,"handoff_expected_ratio",1.0))));
        var ids=new ArrayList<String>();for(int i=0;i<5;i++){var c=call("queued","ai_handoff");consent(c);ids.add(c.get("id").toString());}
        worker.enqueueDue();assertThat(worker.processLane(tenant,0)).isTrue();
        assertThat(dialDb.rows("dialAcks",Map.of("tenantId",tenant,"ids",ids))).hasSize(2);assertThat(dialDb.rows("dialAcks",Map.of("tenantId",tenant+100000,"ids",ids))).isEmpty();
        assertThat(((Number)db.one("routingPressure",Map.of("tenantId",tenant)).get("upstream")).longValue()).isEqualTo(2);
    }
    @Test void unmatchedFirstHundredWaitingCallsDoNotStarveLaterMatchingCall(){
        var absent=agent("offline");var ready=agent("ready");Map<String,Object> last=null;
        for(int i=0;i<101;i++){
            var c=call("waiting_human","ai_handoff");policy(c,Map.of("handoff_agent_ids",List.of((i==100?ready:absent).get("id"))));
            last=store.insert("handoffrequest",JsonSupport.map("tenant_id",tenant,"call_session_id",c.get("id"),"reason","synthetic FIFO boundary"));
        }
        assertThat(routing.redistribute()).isGreaterThanOrEqualTo(1);
        assertThat(store.require("handoffrequest",tenant,last.get("id")).get("assigned_agent_id").toString()).isEqualTo(ready.get("id").toString());
    }
    @Test void timeoutCallbackIsAssignedAndIdempotentWithoutInventingCustomerAppointment(){
        var a=agent("ready");var c=call("waiting_human","ai_handoff");policy(c,Map.of("handoff_agent_ids",List.of(a.get("id"))));
        tx.serialized(()->{var p=conversation.policy(conversation.state(c));conversation.handoffCallback(c,p);conversation.handoffCallback(c,p);return null;});
        var tasks=store.query("productworkitem","list",JsonSupport.map("tenantId",tenant,"offset",0,"limit",100));assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).get("assigned_to").toString()).isEqualTo(a.get("id").toString());assertThat(tasks.get(0).get("detail_json").toString()).contains("\"customer_time_confirmed\":false");
    }
}
