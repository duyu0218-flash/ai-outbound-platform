package com.duozhi.outbound;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.web.bind.annotation.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class QueueConcurrencyIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("JWT_SECRET",()->"queue-regression-fixture-secret-with-more-than-32-characters");
        p.add("spring.datasource.url",MYSQL::getJdbcUrl);p.add("spring.datasource.username",MYSQL::getUsername);p.add("spring.datasource.password",MYSQL::getPassword);
        p.add("spring.flyway.url",MYSQL::getJdbcUrl);p.add("spring.flyway.user",MYSQL::getUsername);p.add("spring.flyway.password",MYSQL::getPassword);
        p.add("spring.data.redis.host",REDIS::getHost);p.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        p.add("REDIS_URL",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
        p.add("JAVA_WORKERS_ENABLED",()->"false");p.add("spring.ai.model.chat",()->"none");
    }
    @Autowired Store store;@Autowired Transactions tx;@Autowired Outbox outbox;
    @Autowired PlatformLock platformLocks;@Autowired javax.sql.DataSource dataSource;
    @Autowired TaskWorker worker;@Autowired CallbackInbox inbox;@Autowired TestRestTemplate http;
    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    @org.springframework.boot.test.mock.mockito.SpyBean AiRuntime runtime;
    @org.springframework.boot.test.mock.mockito.SpyBean TelephonyClient telephony;
    @BeforeEach void seed(){
        if(store.one("tenant","findById",Map.of("id",1))==null)store.insert("tenant",Values.map("id",1,"code","queue-regression","name","Isolated queue fixture"));
        inbox.initialize();inbox.maintenance();
    }
    String key(){return UUID.randomUUID().toString();}
    Map<String,Object> enqueue(String aggregate,String type,int sequence){
        return outbox.enqueue(1,type,aggregate,key(),Values.map("call_id",aggregate,"attempt",1,"turn_sequence",sequence));
    }
    @Test void processingTaskIsNotSupersededAndSameStreamWaitsForOlderTask(){
        String aggregate=key();var first=enqueue(aggregate,"ai_turn",1);
        store.update("taskoutbox",1,first.get("id"),Values.map("state","processing","locked_at",Store.now(),"lease_token","owned"));
        var second=enqueue(aggregate,"ai_turn",2);
        assertThat(store.require("taskoutbox",1,first.get("id")).get("state")).isEqualTo("processing");
        assertThat(candidates(aggregate)).isEmpty();
        store.update("taskoutbox",1,first.get("id"),Values.map("state","completed"));
        assertThat(candidates(aggregate)).extracting(r->r.get("id")).containsExactly(second.get("id"));
        store.update("taskoutbox",1,second.get("id"),Values.map("state","completed"));
    }
    List<Map<String,Object>> candidates(String aggregate){
        return store.query("taskoutbox","claimCandidate",Values.map("tenantId",1,"types",List.of("ai_turn"),"now",Store.now(),"cutoff",Store.now().minusSeconds(30))).stream().filter(r->aggregate.equals(r.get("aggregate_id"))).toList();
    }
    @Test void supersedingPendingTranscriptPreservesAttemptAndIdempotency(){
        String aggregate=key(),idempotency=key();
        var first=enqueue(aggregate,"ai_turn",1);
        var newer=outbox.enqueue(1,"ai_turn",aggregate,idempotency,Values.map("call_id",aggregate,"attempt",1,"turn_sequence",2));
        assertThat(store.require("taskoutbox",1,first.get("id")).get("state")).isEqualTo("completed");
        assertThat(outbox.enqueue(1,"ai_turn",aggregate,idempotency,Values.map("call_id",aggregate)).get("id")).isEqualTo(newer.get("id"));
        store.update("taskoutbox",1,newer.get("id"),Values.map("state","completed"));
    }
    @Test void expiredLeasesRetainAttemptLimitsAndFencing()throws Exception {
        String aggregate=key();var task=enqueue(aggregate,"after_playback",1);
        store.update("taskoutbox",1,task.get("id"),Values.map("state","processing","attempts",1,"locked_at",Store.now().minusSeconds(60),"lease_token","old-owner"));
        var claim=TaskWorker.class.getDeclaredMethod("claim",Long.class,String.class);claim.setAccessible(true);
        @SuppressWarnings("unchecked") var reclaimed=(Map<String,Object>)claim.invoke(worker,1L,"('after_playback')");
        assertThat(reclaimed.get("id")).isEqualTo(task.get("id"));
        assertThat(reclaimed.get("attempts")).isEqualTo(2);
        assertThat(reclaimed.get("lease_token")).isNotEqualTo("old-owner");
        var old=new LinkedHashMap<>(reclaimed);old.put("lease_token","old-owner");assertThat(worker.owns(old)).isFalse();assertThat(worker.owns(reclaimed)).isTrue();
        store.update("taskoutbox",1,task.get("id"),Values.map("locked_at",Store.now().minusSeconds(60),"attempts",reclaimed.get("max_attempts")));
        assertThat(claim.invoke(worker,1L,"('after_playback')")).isNull();
        var exhausted=store.require("taskoutbox",1,task.get("id"));assertThat(exhausted.get("state")).isEqualTo("dead");assertThat(exhausted.get("lease_token")).isNull();
    }
    @Test void concurrentEnqueueAndClaimDoNotDeadlockOrLoseDurableTasks()throws Exception {
        String aggregate=key();var pool=Executors.newFixedThreadPool(2);
        try {
            var producer=pool.submit(()->{for(int i=0;i<50;i++)enqueue(aggregate,"ai_turn",i);});
            var consumer=pool.submit(()->{for(int i=0;i<100;i++)worker.processLane(1L,1);});
            producer.get(60,TimeUnit.SECONDS);consumer.get(60,TimeUnit.SECONDS);
            for(int i=0;i<100&&worker.processLane(1L,1);i++){}
            var rows=store.query("taskoutbox","list",Values.map("tenantId",1,"offset",0,"limit",1000)).stream().filter(r->aggregate.equals(r.get("aggregate_id"))).toList();
            assertThat(rows).hasSize(50).allSatisfy(r->assertThat(r.get("state")).isEqualTo("completed"));
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentReceiveConsumeKeepsReceiptsAndPartitionCountersExact()throws Exception{
        String call=key();int total=100;var pool=Executors.newFixedThreadPool(3);var failures=new ConcurrentLinkedQueue<Throwable>();
        try{
            var sender=pool.submit(()->{for(int n=0;n<total;n++)try{inbox.receive("telephony_status",Values.map("call_id",call,"payload",Values.map("event_id",call+"-"+n,"status","answered","attempt",1)));}catch(Throwable e){failures.add(e);}});
            var consumer=pool.submit(()->{for(int n=0;n<total*2;n++)try{for(int p=0;p<64;p++)inbox.consume(p);}catch(Throwable e){failures.add(e);}});
            sender.get(90,TimeUnit.SECONDS);consumer.get(90,TimeUnit.SECONDS);
            for(int n=0;n<total;n++){boolean progress=false;for(int p=0;p<64;p++)progress|=inbox.consume(p);if(!progress)break;}
            assertThat(failures).isEmpty();
            var rows=store.query("callbackinbox","list",Values.map("offset",0,"limit",1000)).stream().filter(r->call.equals(r.get("call_id"))).toList();
            assertThat(rows).hasSize(total).allSatisfy(r->assertThat(r.get("state")).isEqualTo("done"));
            assertThat(store.query("callbackinboxpartition","list",Values.map("offset",0,"limit",100))).allSatisfy(r->{assertThat(((Number)r.get("pending_count")).longValue()).isZero();assertThat(((Number)r.get("pending_bytes")).longValue()).isZero();});
        }finally{pool.shutdownNow();}
    }
    @Test void independentCallsUseFourExistingThreadsWithoutOvertakingTheirOwnStream()throws Exception {
        String marker=key();var entered=new CountDownLatch(4);var release=new CountDownLatch(1);
        var active=new java.util.concurrent.atomic.AtomicInteger();var peak=new java.util.concurrent.atomic.AtomicInteger();
        var tasks=new ArrayList<Map<String,Object>>();
        org.mockito.Mockito.doAnswer(invocation->{
            assertThat(TaskWorker.tenantHint()).isEqualTo(1L);
            int concurrent=active.incrementAndGet();peak.accumulateAndGet(concurrent,Math::max);entered.countDown();
            try {if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("parallel fixture timed out");return invocation.callRealMethod();}
            finally {active.decrementAndGet();}
        }).when(runtime).turn(org.mockito.ArgumentMatchers.argThat(payload->payload!=null&&marker.equals(payload.get("_parallel_fixture"))));
        try {
            for(int i=0;i<5;i++)tasks.add(outbox.enqueue(1,"ai_turn",key(),key(),Values.map("call_id",key(),"attempt",1,"_parallel_fixture",marker)));
            environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("queue-parallel-fixture",Map.of("JAVA_WORKERS_ENABLED","true")));
            worker.tick();
            assertThat(entered.await(15,TimeUnit.SECONDS)).as("Four independent calls must overlap on the unchanged four-thread executor").isTrue();
            assertThat(active.get()).isEqualTo(4);assertThat(peak.get()).isEqualTo(4);
        } finally {release.countDown();environment.getPropertySources().remove("queue-parallel-fixture");}
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline&&tasks.stream().anyMatch(t->!"completed".equals(store.require("taskoutbox",1,t.get("id")).get("state"))))Thread.sleep(20);
        assertThat(tasks).allSatisfy(t->assertThat(store.require("taskoutbox",1,t.get("id")).get("state")).isEqualTo("completed"));
        assertThat(peak.get()).isEqualTo(4);
    }
    @Test void platformWaitersDoNotBorrowTheFourBusinessConnections()throws Exception {
        var pool=Executors.newFixedThreadPool(4);var started=new CountDownLatch(4);
        var waiting=new CopyOnWriteArrayList<Thread>();var futures=new ArrayList<Future<?>>();
        try {
            try(var held=platformLocks.lock(718492061L)){
                for(int i=0;i<4;i++)futures.add(pool.submit(()->{
                    waiting.add(Thread.currentThread());started.countDown();
                    return tx.serialized(()->store.require("tenant",0,1));
                }));
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline&&!waiting.stream().allMatch(t->t.getState()==Thread.State.WAITING))Thread.sleep(10);
                assertThat(waiting).hasSize(4).allSatisfy(t->assertThat(t.getState()).isEqualTo(Thread.State.WAITING));
                assertThat(((com.alibaba.druid.pool.DruidDataSource)dataSource).getActiveCount()).as("Mutex waiters must leave every JDBC connection available").isZero();
                assertThat(futures).allSatisfy(f->assertThat(f.isDone()).isFalse());
            }
            for(var result:futures)assertThat(result.get(5,TimeUnit.SECONDS)).isNotNull();
        }finally{pool.shutdownNow();}
    }
    @Test void taskClaimMutexWaitersAlsoLeaveTheBusinessPoolAvailable()throws Exception{
        var pool=Executors.newFixedThreadPool(4);var started=new CountDownLatch(4);var waiting=new CopyOnWriteArrayList<Thread>();var futures=new ArrayList<Future<?>>();
        long key=java.nio.ByteBuffer.wrap(JsonSupport.digest("java-outbox-claim")).getLong();
        try{
            try(var held=platformLocks.lock(key)){
                for(int i=0;i<4;i++)futures.add(pool.submit(()->{waiting.add(Thread.currentThread());started.countDown();return tx.serializedIdentity("java-outbox-claim",()->store.require("tenant",0,1));}));
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline&&!waiting.stream().allMatch(thread->thread.getState()==Thread.State.WAITING))Thread.sleep(10);
                assertThat(waiting).hasSize(4).allSatisfy(thread->assertThat(thread.getState()).isEqualTo(Thread.State.WAITING));
                assertThat(((com.alibaba.druid.pool.DruidDataSource)dataSource).getActiveCount()).isZero();
            }
            for(var result:futures)assertThat(result.get(5,TimeUnit.SECONDS)).isNotNull();
        }finally{pool.shutdownNow();}
    }
    @Test void serializedTransactionsRetainNestedRollbackSemantics(){
        String eventKey=key();
        assertThatThrownBy(()->tx.serialized(()->{
            try {tx.serialized(()->{
                outbox.enqueue(1,"ai_turn",key(),eventKey,Values.map("call_id",key(),"attempt",1));
                throw new IllegalStateException("nested rollback fixture");
            });}catch(IllegalStateException expected){}
            return null;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(store.query("taskoutbox","list",Values.map("tenantId",1,"offset",0,"limit",1000)))
            .noneMatch(row->eventKey.equals(row.get("idempotency_key")));
    }
    @Test void knownRowWritesKeepNullsTimestampsAndTenantBoundaries(){
        var call=store.insert("callsession",Values.map("tenant_id",1,"phone","+8613800000991","mode","ai_only"));
        var state=store.insert("conversationstate",Values.map("tenant_id",1,"call_id",call.get("id"),"attempt",1));
        var deadline=Store.now().plusSeconds(10);
        tx.serialized(()->{
            store.one("conversationstate","scopedConversationPolicy30",Store.scopedParameters(1,store.encode("conversationstate","call_id",call.get("id")),1));
            store.writeLocked("conversationstate",1,state.get("id"),Values.map("generation",3,"deadline",deadline,"timer_kind","silence","data_json","{\"evidence\":true}"));
            return null;
        });
        var saved=store.require("conversationstate",1,state.get("id"));
        assertThat(saved).containsEntry("generation",3).containsEntry("deadline",deadline.toString()).containsEntry("timer_kind","silence").containsEntry("data_json","{\"evidence\":true}");
        assertThat(saved.get("call_id")).isEqualTo(call.get("id"));assertThat(saved.get("policy_json")).isEqualTo(state.get("policy_json"));
        tx.serialized(()->{store.writeLocked("conversationstate",1,state.get("id"),JsonSupport.map("deadline",null,"timer_kind",""));return null;});
        assertThat(store.require("conversationstate",1,state.get("id"))).containsEntry("deadline",null).containsEntry("timer_kind","");
        assertThatThrownBy(()->tx.serialized(()->{store.writeLocked("conversationstate",2,state.get("id"),Values.map("generation",99));return null;})).isInstanceOf(ApiError.class).hasMessageContaining("not found");
        assertThat(store.require("conversationstate",1,state.get("id"))).containsEntry("generation",3);
        assertThatThrownBy(()->store.writeLocked("conversationstate",1,state.get("id"),Values.map("generation",99))).isInstanceOf(IllegalStateException.class);
    }
    @Test void appendOnlyMetricIsReallyPersistedWithGeneratedKeyAndDefaults(){
        var call=store.insert("callsession",Values.map("tenant_id",1,"phone","+8613800000992","mode","ai_only"));String stage=key();
        tx.serialized(()->{store.appendMetric(Values.map("tenant_id",1,"call_session_id",call.get("id"),"stage",stage,"duration_ms",17,"success",false,"error_code","fixture"));return null;});
        var rows=store.query("callmetric","list",Values.map("tenantId",1,"offset",0,"limit",1000)).stream().filter(r->stage.equals(r.get("stage"))).toList();
        assertThat(rows).hasSize(1);var metric=rows.get(0);
        assertThat(((Number)metric.get("id")).longValue()).isPositive();
        assertThat(metric).containsEntry("call_session_id",call.get("id")).containsEntry("duration_ms",17).containsEntry("success",false).containsEntry("error_code","fixture").containsEntry("provider","").containsEntry("detail","");
        assertThat(metric.get("created_at")).isNotNull();
    }
    @Test void consolidatedAiLookupKeepsEveryAttemptSequenceGenerationAndMissingRowFence()throws Exception {
        var call=store.insert("callsession",Values.map("tenant_id",1,"phone","+8613800000993","mode","ai_only","status","in_ai","attempts",1));
        store.insert("realtimesession",Values.map("tenant_id",1,"call_session_id",call.get("id"),"turn_sequence",4));
        store.insert("conversationstate",Values.map("tenant_id",1,"call_id",call.get("id"),"attempt",1,"generation",2));
        var current=AiRuntime.class.getDeclaredMethod("current",Map.class,boolean.class);current.setAccessible(true);
        var matching=JsonSupport.map("call_id",call.get("id"),"attempt",1,"turn_sequence",4,"_generation",2);
        for(boolean locked:List.of(false,true)){
            assertThat(currentLookup(current,matching,locked)).containsEntry("id",call.get("id"));
            for(var mismatch:List.of(JsonSupport.map("attempt",0),JsonSupport.map("turn_sequence",3),JsonSupport.map("_generation",3))){
                var payload=new LinkedHashMap<>(matching);payload.putAll(mismatch);assertThat(currentLookup(current,payload,locked)).isNull();
            }
            var p=JsonSupport.map("tenantId",2,"id",store.encode("callsession","id",call.get("id")),"attempt",1,"turnSequence",4,"generation",2);
            assertThat(tx.serialized(()->store.one("callsession",locked?"currentAiLock":"currentAiRead",p))).isNull();
        }
        store.update("callsession",1,call.get("id"),Values.map("status","completed"));
        for(boolean locked:List.of(false,true))assertThat(currentLookup(current,matching,locked)).isNull();
        var noRealtime=store.insert("callsession",Values.map("tenant_id",1,"phone","+8613800000994","mode","ai_only","status","in_ai","attempts",1));
        var withoutGeneration=JsonSupport.map("call_id",noRealtime.get("id"),"attempt",1,"turn_sequence",99);
        for(boolean locked:List.of(false,true)){
            assertThat(currentLookup(current,withoutGeneration,locked)).containsEntry("id",noRealtime.get("id"));
            var missingState=new LinkedHashMap<>(withoutGeneration);missingState.put("_generation",2);assertThat(currentLookup(current,missingState,locked)).isNull();
            assertThat(currentLookup(current,JsonSupport.map("call_id",key(),"attempt",1),locked)).isNull();
        }
    }
    @SuppressWarnings("unchecked") Map<String,Object> currentLookup(java.lang.reflect.Method method,Map<String,Object> payload,boolean locked){
        return tx.serialized(()->{try{return (Map<String,Object>)method.invoke(runtime,payload,locked);}catch(ReflectiveOperationException e){throw new AssertionError(e);}});
    }
    @Test void speechDispatchSnapshotStillRejectsStaleAndInFlightSupersededReplies(){
        var call=store.insert("callsession",Values.map("tenant_id",1,"phone","+8613800000995","mode","ai_only","status","in_ai","attempts",1));
        var rt=store.insert("realtimesession",Values.map("tenant_id",1,"call_session_id",call.get("id"),"turn_sequence",4));
        store.insert("conversationstate",Values.map("tenant_id",1,"call_id",call.get("id"),"attempt",1,"generation",2));
        var stale=JsonSupport.map("call_id",call.get("id"),"attempt",1,"turn_sequence",3,"_generation",2);
        runtime.apply(stale,ConversationPolicy.reply("stale reply","speak"));
        org.mockito.Mockito.verify(telephony,org.mockito.Mockito.never()).command(org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.eq("speak"),org.mockito.ArgumentMatchers.anyMap());
        org.mockito.Mockito.doAnswer(invocation->{tx.serialized(()->{store.writeLocked("realtimesession",1,rt.get("id"),Values.map("turn_sequence",5));return null;});return Values.map("result","accepted","playback_id","superseded-playback");})
            .when(telephony).command(org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.eq("speak"),org.mockito.ArgumentMatchers.anyMap());
        runtime.apply(JsonSupport.map("call_id",call.get("id"),"attempt",1,"turn_sequence",4,"_generation",2),ConversationPolicy.reply("superseded reply","speak"));
        org.mockito.Mockito.verify(telephony,org.mockito.Mockito.times(1)).command(org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.eq("speak"),org.mockito.ArgumentMatchers.anyMap());
        assertThat(store.query("speechturn","list",Values.map("tenantId",1,"offset",0,"limit",1000))).noneMatch(r->call.get("id").equals(r.get("call_session_id")));
        assertThat(store.query("callevent","list",Values.map("tenantId",1,"offset",0,"limit",1000))).noneMatch(r->call.get("id").equals(r.get("call_session_id")));
    }
    @Test void errorDispatchRetainsServerStatusWhileDirectErrorRequestStillRequiresAuthentication(){
        var failed=http.getForEntity("/v1/call/queue-fault-probe",String.class);
        assertThat(failed.getStatusCode().value()).isEqualTo(500);
        assertThat(failed.getHeaders().get("WWW-Authenticate")).isNull();
        assertThat(failed.getHeaders().get("Set-Cookie")).isNull();
        assertThat(http.getForEntity("/error",String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.getForEntity("/api/v1/auth/me",String.class).getStatusCode().value()).isEqualTo(401);
    }
    @TestConfiguration(proxyBeanMethods=false) static class FaultConfiguration {
        @Bean FaultController queueFaultController(){return new FaultController();}
    }
    @RestController static class FaultController {
        @GetMapping("/v1/call/queue-fault-probe") void fail(){throw new org.springframework.dao.CannotAcquireLockException("isolated database-failure regression fixture");}
    }
}
