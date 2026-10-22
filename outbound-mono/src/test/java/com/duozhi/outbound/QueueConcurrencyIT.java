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
        p.add("spring.flyway.url",MYSQL::getJdbcUrl);p.add("spring.flyway.user",()->"root");p.add("spring.flyway.password",MYSQL::getPassword);
        p.add("spring.data.redis.host",REDIS::getHost);p.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        p.add("REDIS_URL",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
        p.add("JAVA_ANALYSIS_DB_POOL",()->"1");p.add("JAVA_WORKERS_ENABLED",()->"false");p.add("JAVA_ANALYSIS_TASK_OWNERS",()->"2");p.add("spring.ai.model.chat",()->"none");
    }
    @Autowired Store store;@Autowired Transactions tx;@Autowired Outbox outbox;@Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;@Autowired RealtimeTaskCleanup taskCleanup;@Autowired RealtimeTaskPressure taskPressure;
    @Autowired PlatformLock platformLocks;@Autowired javax.sql.DataSource dataSource;
    @Autowired TaskWorker worker;@Autowired CallbackInbox inbox;@Autowired TestRestTemplate http;
    @Autowired org.apache.ibatis.session.SqlSession sqlSession;
    @org.springframework.boot.test.mock.mockito.SpyBean CallAnalysis analysis;
    @Autowired CallLifecycle lifecycle;@Autowired ConversationPolicy conversation;@Autowired VoiceCallbacks businessVoice;
    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    @org.springframework.boot.test.mock.mockito.SpyBean AiRuntime runtime;
    @org.springframework.boot.test.mock.mockito.SpyBean TelephonyClient telephony;
    @BeforeEach void seed(){
        if(store.one("tenant","findById",Map.of("id",1))==null)store.insert("tenant",Values.map("id",1,"code","queue-regression","name","Isolated queue fixture"));
        inbox.initialize();inbox.maintenance();
    }
    String key(){return UUID.randomUUID().toString();}
    @Test void predecessorChecksUseTenantAndStreamIndexForBothClaimPaths()throws Exception {
        // Use the actual mapped statements and type handlers, rather than a
        // copied SQL query that could drift away from the production path.
        var fixtures=new ArrayList<Map<String,Object>>();
        for(String type:List.of("ai_turn","dial_call"))fixtures.add(enqueue(key(),type,1));
        try{for(String mapper:List.of("TaskOutboxMapper.claimCandidate","DialBatchMapper.taskCandidates")){
            var params=Values.map("tenantId",1,"types",List.of("ai_turn"),"now",Store.now(),"cutoff",Store.now().minusSeconds(30),"parallelClaim",true,"limit",100,"recoverOnly",false);
            var statement=sqlSession.getConfiguration().getMappedStatement("com.duozhi.outbound.mapper."+mapper);
            var bound=statement.getBoundSql(params);
            try(var connection=dataSource.getConnection();var explain=connection.prepareStatement("EXPLAIN FORMAT=JSON "+bound.getSql())){
                new org.apache.ibatis.scripting.defaults.DefaultParameterHandler(statement,params,bound).setParameters(explain);
                try(var rows=explain.executeQuery()){
                    assertThat(rows.next()).isTrue();
                    var plan=new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getString(1));
                    var tables=plan.findParents("table_name").stream().filter(node->"older".equals(node.path("table_name").asText())).toList();
                    assertThat(tables).as(mapper+" must retain its predecessor anti-join").isNotEmpty();
                    assertThat(tables).allSatisfy(table->assertThat(table.path("key").asText()).isEqualTo("ix_taskoutbox_stream_scope"));
                }
            }
        }}finally{for(var task:fixtures)store.update("taskoutbox",1,task.get("id"),Values.map("state","completed"));}
    }
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
    @Test void enablingTerminalBatchesKeepsInitialReceiptsIndependentAndTerminalReleaseLocked()throws Exception{
        var properties=new org.springframework.core.env.MapPropertySource("callback-resource-classes",Map.of("JAVA_CALLBACK_TERMINAL_BATCH_ENABLED","true"));
        environment.getPropertySources().addFirst(properties);var pool=Executors.newFixedThreadPool(2);
        var call=store.insert("callsession",Values.map("tenant_id",1,"phone","15500002222","mode","ai_only","status","dialing","attempts",1,"max_attempts",1,"started_at",Store.now()));
        String id=call.get("id").toString();
        inbox.receive("telephony_status",Values.map("call_id",id,"payload",Values.map("status","answered","attempt",1,"event_id",key())));
        inbox.receive("telephony_status",Values.map("call_id",id,"payload",Values.map("status","completed","attempt",1,"event_id",key())));
        Future<Integer> terminal;
        try{
            try(var held=platformLocks.lock(718492061L)){
                assertThat(pool.submit(()->inbox.consumeBatch(100)).get(5,TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(store.require("callsession",1,id).get("status")).isEqualTo("answered");
                var entered=new CountDownLatch(1);terminal=pool.submit(()->{entered.countDown();return inbox.consumeBatch(100);});
                assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->terminal.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(store.require("callsession",1,id).get("status")).isEqualTo("answered");
            }
            assertThat(terminal.get(5,TimeUnit.SECONDS)).isEqualTo(1);assertThat(store.require("callsession",1,id).get("status")).isEqualTo("completed");
            var rows=store.query("callbackinbox","list",Values.map("offset",0,"limit",1000)).stream().filter(r->id.equals(r.get("call_id"))).toList();
            assertThat(rows).hasSize(2).allSatisfy(row->assertThat(row.get("state")).isEqualTo("done"));
        }finally{environment.getPropertySources().remove(properties.getName());pool.shutdownNow();}
    }
    @Test void concurrentAnalysisOfOneCallRemainsUniqueWhileResourceMutexIsHeld()throws Exception{
        var call=store.insert("callsession",Values.map("tenant_id",1,"phone","15500003333","mode","ai_only","status","completed","attempts",1,"max_attempts",1,"finished_at",Store.now()));
        store.insert("speechturn",Values.map("tenant_id",1,"call_session_id",call.get("id"),"provider_event_key",key(),"speaker_role","customer","is_final",true,"attempt",1,"turn_index",1,"transcript","并发复验唯一转写证据","normalized_transcript","并发复验唯一转写证据"));
        var pool=Executors.newFixedThreadPool(2);
        try(var held=platformLocks.lock(718492061L)){
            var first=pool.submit(()->analysis.analyze(call.get("id"),1));var second=pool.submit(()->analysis.analyze(call.get("id"),1));
            var one=first.get(5,TimeUnit.SECONDS);var two=second.get(5,TimeUnit.SECONDS);
            assertThat(one).isNotNull();assertThat(two.get("id")).isEqualTo(one.get("id"));assertThat(one.get("summary")).isEqualTo("并发复验唯一转写证据");assertThat(two.get("summary")).isEqualTo("并发复验唯一转写证据");
            assertThat(store.query("callanalysis","scopedCallAnalysis17",Store.scopedParameters(1,store.encode("callanalysis","call_session_id",call.get("id"))))).hasSize(1);
            assertThat(analysis.analyze(call.get("id"),2)).isNull();
        }finally{pool.shutdownNow();}
    }
    @Test void analysisWaitsForItsParentWithoutBlockingOtherCallsAndReadsTheCommittedResult()throws Exception{
        var first=store.insert("callsession",Values.map("tenant_id",1,"phone","15500003334","mode","ai_only","status","completed","attempts",1,"max_attempts",1,"finished_at",Store.now()));
        var other=store.insert("callsession",Values.map("tenant_id",1,"phone","15500003335","mode","ai_only","status","no_answer","attempts",1,"max_attempts",1,"finished_at",Store.now()));
        var pool=Executors.newFixedThreadPool(3);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try{
            var holding=pool.submit(()->tx.run(()->{
                store.one("callsession","lockById",Values.map("tenantId",1,"id",store.encode("callsession","id",first.get("id"))));locked.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("parent lock release timed out");}catch(InterruptedException e){throw new RuntimeException(e);}
                return analysis.analyze(first.get("id"),1);
            }));
            assertThat(locked.await(3,TimeUnit.SECONDS)).isTrue();
            var waiting=pool.submit(()->analysis.analyze(first.get("id"),1));
            assertThatThrownBy(()->waiting.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(pool.submit(()->analysis.analyze(other.get("id"),1)).get(5,TimeUnit.SECONDS).get("result_code")).isEqualTo("no_answer");
            release.countDown();var committed=holding.get(5,TimeUnit.SECONDS);var refreshed=waiting.get(5,TimeUnit.SECONDS);
            assertThat(refreshed.get("id")).isEqualTo(committed.get("id"));
            assertThat(store.query("callanalysis","scopedCallAnalysis17",Store.scopedParameters(1,store.encode("callanalysis","call_session_id",first.get("id"))))).hasSize(1);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void boundedAtomicAnalysisOwnersCommitIndependentResultsAndTasksExactlyOnce()throws Exception{
        long tid=93;store.insert("tenant",Values.map("id",tid,"code","atomic-analysis-fixture","name","Isolated atomic analysis fixture"));
        var property=new org.springframework.core.env.MapPropertySource("atomic-analysis-claims",Map.of("JAVA_TASK_CLAIM_PARALLEL_ENABLED","true"));environment.getPropertySources().addFirst(property);
        org.springframework.test.util.ReflectionTestUtils.setField(worker,"analysisBatchSize",16);
        String[] types=(String[])org.springframework.test.util.ReflectionTestUtils.getField(worker,"laneTypes");var indexes=new ArrayList<Integer>();for(int i=0;i<types.length;i++)if(types[i].equals("('call_analysis')"))indexes.add(i);
        var pool=Executors.newFixedThreadPool(2);
        try{
            for(int i=0;i<24;i++){var call=store.insert("callsession",Values.map("tenant_id",tid,"phone","15500006"+String.format("%03d",i),"mode","ai_only","status","no_answer","attempts",1,"max_attempts",1));outbox.enqueue(tid,"call_analysis",call.get("id"),key(),Values.map("call_id",call.get("id"),"attempt",1));}
            try(var held=platformLocks.lock(718492061L)){
                for(int round=0;round<4;round++){var gate=new CountDownLatch(1);var replies=new ArrayList<Future<Boolean>>();for(int index:indexes)replies.add(pool.submit(()->{gate.await();return worker.processLane(tid,index);}));gate.countDown();for(var reply:replies)reply.get(10,TimeUnit.SECONDS);}
            }
            var tasks=store.query("taskoutbox","list",Values.map("tenantId",tid,"offset",0,"limit",100));assertThat(tasks).hasSize(24).allSatisfy(task->assertThat(task).containsEntry("state","completed").containsEntry("attempts",1).containsEntry("lease_token",null));
            assertThat(store.query("callanalysis","list",Values.map("tenantId",tid,"offset",0,"limit",100))).hasSize(24);
        }finally{org.springframework.test.util.ReflectionTestUtils.setField(worker,"analysisBatchSize",1);environment.getPropertySources().remove(property.getName());pool.shutdownNow();}
    }
    @Test void analysisCohortFailureRollsBackBeforeSingleTaskRetryAndDoesNotDiscardHealthyCalls()throws Exception{
        long tid=94;store.insert("tenant",Values.map("id",tid,"code","analysis-rollback-fixture","name","Isolated analysis rollback fixture"));
        var property=new org.springframework.core.env.MapPropertySource("analysis-rollback-claims",Map.of("JAVA_TASK_CLAIM_PARALLEL_ENABLED","true"));environment.getPropertySources().addFirst(property);org.springframework.test.util.ReflectionTestUtils.setField(worker,"analysisBatchSize",16);
        int lane=Arrays.asList((String[])org.springframework.test.util.ReflectionTestUtils.getField(worker,"laneTypes")).indexOf("('call_analysis')");
        try{
            var tasks=new ArrayList<Map<String,Object>>();var calls=new HashMap<String,Object>();
            for(int i=0;i<4;i++){var call=store.insert("callsession",Values.map("tenant_id",tid,"phone","1550000777"+i,"mode","ai_only","status","no_answer","attempts",1,"max_attempts",1));var task=outbox.enqueue(tid,"call_analysis",call.get("id"),key(),Values.map("call_id",call.get("id"),"attempt",1));tasks.add(task);calls.put(task.get("id").toString(),call.get("id"));}
            tasks.sort(Comparator.comparing(t->t.get("id").toString()));Object poison=calls.get(tasks.get(3).get("id").toString());var injected=new java.util.concurrent.atomic.AtomicBoolean();
            org.mockito.Mockito.doAnswer(invocation->{Map<String,Object> call=invocation.getArgument(0);if(call!=null&&poison.equals(call.get("id"))){if(injected.compareAndSet(false,true))assertThat(store.query("callanalysis","list",Values.map("tenantId",tid,"offset",0,"limit",100))).hasSize(3);throw new IllegalStateException("isolated analysis cohort SQL failure");}return invocation.callRealMethod();}).when(analysis).analyzeLocked(org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyInt());
            worker.processLane(tid,lane);assertThat(injected.get()).isTrue();assertThat(store.query("callanalysis","list",Values.map("tenantId",tid,"offset",0,"limit",100))).hasSizeLessThanOrEqualTo(1);
            for(int i=0;i<6;i++)worker.processLane(tid,lane);
            var persisted=store.query("taskoutbox","list",Values.map("tenantId",tid,"offset",0,"limit",100));
            assertThat(persisted).hasSize(4).allSatisfy(task->{assertThat(task.get("attempts")).isEqualTo(1);Object call=calls.get(task.get("id").toString());assertThat(task.get("state")).isEqualTo(poison.equals(call)?"failed":"completed");});
            assertThat(store.query("callanalysis","list",Values.map("tenantId",tid,"offset",0,"limit",100))).hasSize(3).noneMatch(row->poison.equals(row.get("call_session_id")));
        }finally{org.springframework.test.util.ReflectionTestUtils.setField(worker,"analysisBatchSize",1);environment.getPropertySources().remove(property.getName());}
    }
    @Test void atomicAnalysisReclaimsAnExpiredHeadBeforeTheNextAttemptAndFencesTheOldOwner(){
        long tid=95;store.insert("tenant",Values.map("id",tid,"code","analysis-fifo-fixture","name","Isolated analysis FIFO fixture"));
        var property=new org.springframework.core.env.MapPropertySource("analysis-fifo-claims",Map.of("JAVA_TASK_CLAIM_PARALLEL_ENABLED","true"));environment.getPropertySources().addFirst(property);org.springframework.test.util.ReflectionTestUtils.setField(worker,"analysisBatchSize",16);
        int lane=Arrays.asList((String[])org.springframework.test.util.ReflectionTestUtils.getField(worker,"laneTypes")).indexOf("('call_analysis')");
        try{
            var call=store.insert("callsession",Values.map("tenant_id",tid,"phone","15500008880","mode","ai_only","status","no_answer","attempts",2,"max_attempts",2));
            var old=outbox.enqueue(tid,"call_analysis",call.get("id"),key(),Values.map("call_id",call.get("id"),"attempt",1));
            var head=store.update("taskoutbox",tid,old.get("id"),Values.map("state","processing","attempts",1,"locked_at",Store.now().minusSeconds(60),"lease_token","expired-analysis-owner"));
            var next=outbox.enqueue(tid,"call_analysis",call.get("id"),key(),Values.map("call_id",call.get("id"),"attempt",2));
            assertThat(worker.processLane(tid,lane)).isTrue();assertThat(worker.owns(head)).isFalse();assertThat(store.require("taskoutbox",tid,next.get("id")).get("state")).isEqualTo("pending");
            assertThat(store.query("callanalysis","list",Values.map("tenantId",tid,"offset",0,"limit",100))).isEmpty();
            assertThat(worker.processLane(tid,lane)).isTrue();assertThat(store.require("taskoutbox",tid,next.get("id")).get("state")).isEqualTo("completed");assertThat(store.query("callanalysis","list",Values.map("tenantId",tid,"offset",0,"limit",100))).hasSize(1);
        }finally{org.springframework.test.util.ReflectionTestUtils.setField(worker,"analysisBatchSize",1);environment.getPropertySources().remove(property.getName());}
    }
    @Test void analysisBatchDoesNotPassAnOlderDeadTaskBeingReactivated()throws Exception{
        long tid=96;store.insert("tenant",Values.map("id",tid,"code","analysis-reactivation-fixture","name","Isolated reactivation fixture"));
        var property=new org.springframework.core.env.MapPropertySource("analysis-reactivation-claims",Map.of("JAVA_TASK_CLAIM_PARALLEL_ENABLED","true"));environment.getPropertySources().addFirst(property);org.springframework.test.util.ReflectionTestUtils.setField(worker,"analysisBatchSize",16);
        int lane=Arrays.asList((String[])org.springframework.test.util.ReflectionTestUtils.getField(worker,"laneTypes")).indexOf("('call_analysis')");var pool=Executors.newSingleThreadExecutor();
        try{
            var call=store.insert("callsession",Values.map("tenant_id",tid,"phone","15500008881","mode","ai_only","status","no_answer","attempts",2,"max_attempts",2));
            var old=outbox.enqueue(tid,"call_analysis",call.get("id"),key(),Values.map("call_id",call.get("id"),"attempt",1));store.update("taskoutbox",tid,old.get("id"),Map.of("state","dead"));
            var next=outbox.enqueue(tid,"call_analysis",call.get("id"),key(),Values.map("call_id",call.get("id"),"attempt",2));
            tx.run(()->{
                store.one("taskoutbox","lockById",Values.map("tenantId",tid,"id",store.encode("taskoutbox","id",old.get("id"))));
                store.update("taskoutbox",tid,old.get("id"),Map.of("state","pending"));
                try{assertThat(pool.submit(()->worker.processLane(tid,lane)).get(5,TimeUnit.SECONDS)).isFalse();}catch(Exception error){throw new RuntimeException(error);}return null;
            });
            assertThat(store.require("taskoutbox",tid,next.get("id")).get("state")).isEqualTo("pending");
            assertThat(worker.processLane(tid,lane)).isTrue();assertThat(store.require("taskoutbox",tid,next.get("id")).get("state")).isEqualTo("pending");
            assertThat(worker.processLane(tid,lane)).isTrue();assertThat(store.query("callanalysis","list",Values.map("tenantId",tid,"offset",0,"limit",100))).hasSize(1);
        }finally{org.springframework.test.util.ReflectionTestUtils.setField(worker,"analysisBatchSize",1);environment.getPropertySources().remove(property.getName());pool.shutdownNow();}
    }
    @Test void localTimerPreparationAndCleanupDoNotWaitForResourceMutexAndStaleTimersHaveNoEffect()throws Exception{
        org.mockito.Mockito.doNothing().when(runtime).apply(org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyMap());
        var pool=Executors.newSingleThreadExecutor();
        try{
            for(String kind:List.of("silence","failure")){
                var call=store.insert("callsession",Values.map("tenant_id",1,"phone","1550000555"+(kind.equals("silence")?1:2),"mode","ai_only","status","in_ai","attempts",1,"max_attempts",1));
                var state=conversation.state(call);var media=businessVoice.realtime(call);store.update("realtimesession",1,media.get("id"),Map.of("state","listening"));
                store.update("conversationstate",1,state.get("id"),Values.map("timer_kind",kind,"deadline",Store.now().minusSeconds(1)));
                Map<String,Object> payload=Values.map("product_kind","timer","call_id",call.get("id"),"attempt",1,"generation",state.get("generation"),"kind",kind);
                try(var held=platformLocks.lock(718492061L)){pool.submit(()->runtime.afterPlayback(payload)).get(5,TimeUnit.SECONDS);}
                var saved=store.require("conversationstate",1,state.get("id"));assertThat(saved.get("timer_kind")).isEqualTo("");assertThat(saved.get("deadline")).isNull();
                assertThat(((Number)saved.get("generation")).intValue()).isGreaterThan(((Number)state.get("generation")).intValue());
                runtime.afterPlayback(payload);var stale=new LinkedHashMap<>(payload);stale.put("attempt",2);runtime.afterPlayback(stale);
                assertThat(store.require("conversationstate",1,state.get("id"))).containsEntry("generation",saved.get("generation")).containsEntry("data_json",saved.get("data_json"));
                org.mockito.Mockito.verify(runtime).apply(org.mockito.ArgumentMatchers.argThat(p->call.get("id").equals(p.get("call_id"))),org.mockito.ArgumentMatchers.anyMap());
            }
        }finally{pool.shutdownNow();}
    }
    @Test void handoffTimerStillWaitsForTheSharedResourceMutex()throws Exception{
        org.mockito.Mockito.doNothing().when(runtime).apply(org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyMap());
        var call=store.insert("callsession",Values.map("tenant_id",1,"phone","15500005553","mode","ai_handoff","status","waiting_human","attempts",1,"max_attempts",1));
        var state=conversation.state(call);store.update("conversationstate",1,state.get("id"),Values.map("timer_kind","handoff","deadline",Store.now().minusSeconds(1)));
        Map<String,Object> payload=Values.map("product_kind","timer","call_id",call.get("id"),"attempt",1,"generation",state.get("generation"),"kind","handoff");
        var pool=Executors.newSingleThreadExecutor();Future<?> waiting;
        try{
            try(var held=platformLocks.lock(718492061L)){waiting=pool.submit(()->runtime.afterPlayback(payload));assertThatThrownBy(()->waiting.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);assertThat(store.require("callsession",1,call.get("id")).get("status")).isEqualTo("waiting_human");}
            waiting.get(5,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
    }
    @Test void independentAnalysisOwnersCompleteWhileAnotherControlTaskIsPending()throws Exception{
        long tid=92;store.insert("tenant",Values.map("id",tid,"code","analysis-owner-fixture","name","Isolated analysis owner fixture"));
        var config=new org.springframework.core.env.MapPropertySource("analysis-owner-parallel",Map.of("JAVA_TASK_CLAIM_PARALLEL_ENABLED","true"));environment.getPropertySources().addFirst(config);
        String[] types=(String[])org.springframework.test.util.ReflectionTestUtils.getField(worker,"laneTypes");var indexes=new ArrayList<Integer>();
        for(int i=0;i<types.length;i++)if(types[i].equals("('call_analysis')"))indexes.add(i);
        assertThat(indexes).hasSize(2);assertThat(types[2]).doesNotContain("call_analysis");var pool=Executors.newFixedThreadPool(2);var rows=new ArrayList<Map<String,Object>>();
        var control=outbox.enqueue(tid,"after_playback",key(),key(),Map.of());
        try{
            for(int i=0;i<2;i++){
                var call=store.insert("callsession",Values.map("tenant_id",tid,"phone","1550000444"+i,"mode","ai_only","status","no_answer","attempts",1,"max_attempts",1,"finished_at",Store.now()));rows.add(call);
                outbox.enqueue(tid,"call_analysis",call.get("id"),key(),Values.map("call_id",call.get("id"),"attempt",1));
            }
            var start=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();
            for(int index:indexes)results.add(pool.submit(()->{start.await();return worker.processLane(tid,index);}));start.countDown();
            for(var result:results)assertThat(result.get(10,TimeUnit.SECONDS)).isTrue();
            for(var call:rows)assertThat(store.query("callanalysis","scopedCallAnalysis17",Store.scopedParameters(tid,store.encode("callanalysis","call_session_id",call.get("id"))))).hasSize(1);
            assertThat(store.require("taskoutbox",tid,control.get("id")).get("state")).isEqualTo("pending");
            assertThat(store.query("taskoutbox","list",Values.map("tenantId",tid,"offset",0,"limit",100)).stream().filter(r->"call_analysis".equals(r.get("task_type")))).allSatisfy(task->assertThat(task.get("state")).isEqualTo("completed"));
        }finally{store.update("taskoutbox",tid,control.get("id"),Map.of("state","completed"));environment.getPropertySources().remove(config.getName());pool.shutdownNow();}
    }
    @Test void parallelTaskClaimsSkipBusyRowsKeepStreamOrderAndFenceExpiredOwners()throws Exception{
        long tid=91;store.insert("tenant",Values.map("id",tid,"code","parallel-claim-fixture","name","Isolated parallel claim fixture"));
        var property=new org.springframework.core.env.MapPropertySource("parallel-task-claim-fixture",Map.of("JAVA_TASK_CLAIM_PARALLEL_ENABLED","true"));environment.getPropertySources().addFirst(property);
        var pool=Executors.newFixedThreadPool(4);var method=TaskWorker.class.getDeclaredMethod("claim",Long.class,String.class);method.setAccessible(true);
        var tasks=new ArrayList<Map<String,Object>>();
        try{
            for(int i=0;i<4;i++)tasks.add(outbox.enqueue(tid,"call_analysis",key(),key(),Map.of()));
            long mutex=java.nio.ByteBuffer.wrap(JsonSupport.digest("java-outbox-claim")).getLong();
            try(var held=platformLocks.lock(mutex)){
                var ready=new CountDownLatch(4);var start=new CountDownLatch(1);var pending=new ArrayList<Future<Map<String,Object>>>();
                for(int i=0;i<4;i++)pending.add(pool.submit(()->{ready.countDown();start.await();return (Map<String,Object>)method.invoke(worker,tid,"('call_analysis')");}));
                assertThat(ready.await(3,TimeUnit.SECONDS)).isTrue();start.countDown();var ids=new HashSet<Object>();
                for(var future:pending){var task=future.get(5,TimeUnit.SECONDS);assertThat(task).isNotNull();assertThat(worker.owns(task)).isTrue();ids.add(task.get("id"));}
                assertThat(ids).hasSize(4);for(var task:tasks)store.update("taskoutbox",tid,task.get("id"),Values.map("state","completed"));
                tasks.clear();String aggregate=key();for(int i=0;i<4;i++)tasks.add(outbox.enqueue(tid,"call_analysis",aggregate,key(),Map.of()));
                var next=new ArrayList<Future<Map<String,Object>>>();for(int i=0;i<4;i++)next.add(pool.submit(()->(Map<String,Object>)method.invoke(worker,tid,"('call_analysis')")));
                var claimed=new ArrayList<Map<String,Object>>();for(var future:next){var task=future.get(5,TimeUnit.SECONDS);if(task!=null)claimed.add(task);}
                assertThat(claimed).hasSize(1);var old=claimed.get(0);store.update("taskoutbox",tid,old.get("id"),Values.map("locked_at",Store.now().minusSeconds(60)));
                var recovered=(Map<String,Object>)method.invoke(worker,tid,"('call_analysis')");assertThat(recovered.get("id")).isEqualTo(old.get("id"));assertThat(recovered.get("lease_token")).isNotEqualTo(old.get("lease_token"));
                assertThat(worker.owns(old)).isFalse();assertThat(worker.owns(recovered)).isTrue();
            }
        }finally{for(var task:tasks)store.update("taskoutbox",tid,task.get("id"),Values.map("state","completed"));environment.getPropertySources().remove(property.getName());pool.shutdownNow();}
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
    @Test void playbackOwnersRunDifferentCallsInParallelWithoutOverlappingTheSameCall()throws Exception {
        long tid=194;store.insert("tenant",Values.map("id",tid,"code","control-partitions","name","Isolated playback owners"));
        var property=new org.springframework.core.env.MapPropertySource("control-parallel",Map.of("JAVA_TASK_CLAIM_PARALLEL_ENABLED","true"));environment.getPropertySources().addFirst(property);
        String[] types=(String[])org.springframework.test.util.ReflectionTestUtils.getField(worker,"laneTypes");var owners=new ArrayList<Integer>();for(int i=0;i<types.length;i++)if(types[i].equals("('after_playback','interrupt_playback')"))owners.add(i);
        assertThat(owners).hasSize(4);var active=new ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger>();var overlap=new java.util.concurrent.atomic.AtomicBoolean();var concurrent=new java.util.concurrent.atomic.AtomicInteger();var peak=new java.util.concurrent.atomic.AtomicInteger();
        var rows=new ArrayList<Map<String,Object>>();var future=new ArrayList<Map<String,Object>>();var pool=Executors.newFixedThreadPool(4);
        org.mockito.Mockito.doAnswer(invocation->{Map<String,Object> payload=invocation.getArgument(0);String id=payload.get("call_id").toString();if(active.computeIfAbsent(id,k->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet()>1)overlap.set(true);int n=concurrent.incrementAndGet();peak.accumulateAndGet(n,Math::max);try{Thread.sleep(30);}finally{concurrent.decrementAndGet();active.get(id).decrementAndGet();}return null;}).when(runtime).afterPlayback(org.mockito.ArgumentMatchers.argThat(p->p!=null&&Boolean.TRUE.equals(p.get("partition_fixture"))));
        try {
            for(int i=0;i<24;i++){
                String aggregate=key();future.add(outbox.enqueue(tid,"after_playback",aggregate,key(),Values.map("partition_fixture",true,"call_id",aggregate)));
                store.update("taskoutbox",tid,future.get(future.size()-1).get("id"),Values.map("available_at",Store.now().plusHours(1)));
                for(int j=0;j<2;j++)rows.add(outbox.enqueue(tid,"after_playback",aggregate,key(),Values.map("partition_fixture",true,"call_id",aggregate)));
            }
            var start=new CountDownLatch(1);var results=new ArrayList<Future<?>>();
            for(int owner:owners)results.add(pool.submit(()->{start.await();for(int n=0;n<60&&worker.processLane(tid,owner);n++){}return null;}));start.countDown();for(var result:results)result.get(20,TimeUnit.SECONDS);
            assertThat(overlap.get()).isFalse();assertThat(peak.get()).isGreaterThan(1);
            for(var task:rows)assertThat(store.require("taskoutbox",tid,task.get("id"))).containsEntry("state","completed").containsEntry("attempts",1);
            for(var task:future)assertThat(store.require("taskoutbox",tid,task.get("id"))).containsEntry("state","pending").containsEntry("attempts",0);
        }finally{for(var task:future)store.update("taskoutbox",tid,task.get("id"),Values.map("state","completed"));environment.getPropertySources().remove(property.getName());pool.shutdownNow();}
    }
    @Test void terminalCleanupPreservesAppointmentsOtherAttemptsOwnedAndDeadTasks() {
        long tid=195;store.insert("tenant",Values.map("id",tid,"code","terminal-controls","name","Isolated terminal controls"));
        var call=store.insert("callsession",Values.map("tenant_id",tid,"phone","15500009501","mode","ai_only","status","in_ai","attempts",2,"max_attempts",2));var tasks=new ArrayList<Map<String,Object>>();
        for(String type:List.of("after_playback","ai_turn","interrupt_playback"))tasks.add(outbox.enqueue(tid,type,call.get("id"),key(),Values.map("call_id",call.get("id"),"attempt",2,"product_kind","timer")));
        var appt=outbox.enqueue(tid,"after_playback",call.get("id"),key(),Values.map("attempt",2,"product_kind","appointment"));
        var other=outbox.enqueue(tid,"after_playback",call.get("id"),key(),Values.map("attempt",1,"product_kind","timer"));
        var owned=outbox.enqueue(tid,"after_playback",call.get("id"),key(),Values.map("attempt",2,"product_kind","timer"));store.update("taskoutbox",tid,owned.get("id"),Values.map("state","processing","lease_token","still-owned","locked_at",Store.now()));
        var dead=outbox.enqueue(tid,"after_playback",call.get("id"),key(),Values.map("attempt",2,"product_kind","timer"));store.update("taskoutbox",tid,dead.get("id"),Values.map("state","dead"));
        lifecycle.status(Values.map("call_id",call.get("id"),"payload",Values.map("attempt",2,"event_id",key(),"status","completed")));
        for(var task:tasks)assertThat(store.require("taskoutbox",tid,task.get("id"))).containsEntry("state","completed").containsEntry("last_error","superseded by terminal attempt");
        for(var task:List.of(appt,other))assertThat(store.require("taskoutbox",tid,task.get("id"))).containsEntry("state","pending");
        assertThat(store.require("taskoutbox",tid,owned.get("id"))).containsEntry("state","processing").containsEntry("lease_token","still-owned");assertThat(store.require("taskoutbox",tid,dead.get("id"))).containsEntry("state","dead");
    }

    @Test void exhaustedColdConnectionPoolCannotBorrowRealtimeOrDefaultReservations()throws Exception {
        var pool=Executors.newSingleThreadExecutor();java.sql.Connection held;
        try(var route=WorkloadDataSource.use(WorkloadDataSource.Lane.ANALYSIS)){held=dataSource.getConnection();}
        try {
            var waiting=pool.submit(()->{try(var route=WorkloadDataSource.use(WorkloadDataSource.Lane.ANALYSIS);var c=dataSource.getConnection()){return c.isValid(1);}});
            assertThatThrownBy(()->waiting.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            for(var lane:List.of(WorkloadDataSource.Lane.REALTIME,WorkloadDataSource.Lane.DEFAULT))
                try(var route=WorkloadDataSource.use(lane);var independent=dataSource.getConnection()){assertThat(independent.isValid(1)).isTrue();}
            held.close();assertThat(waiting.get(3,TimeUnit.SECONDS)).isTrue();
            // A lane hint change inside a transaction must not split business facts across connections.
            String code="route-rollback-"+key();
            try(var route=WorkloadDataSource.use(WorkloadDataSource.Lane.REALTIME)){
                assertThatThrownBy(()->tx.run(()->{long connection=jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);
                    jdbc.update("INSERT INTO tenant(code,name,enabled,created_at,updated_at) VALUES(?,?,TRUE,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",code,"Rollback routing fixture");
                    try(var nested=WorkloadDataSource.use(WorkloadDataSource.Lane.ANALYSIS)){assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class)).isEqualTo(connection);}
                    throw new IllegalStateException("route rollback fixture");
                })).isInstanceOf(IllegalStateException.class).hasMessage("route rollback fixture");
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE code=?",Long.class,code)).isZero();
        }finally{held.close();pool.shutdownNow();}
    }
    @Test void batchedTerminalCleanupCommitsAndRollsBackWithTheTerminalFacts(){
        long tid=196;store.insert("tenant",Values.map("id",tid,"code","batch-control-rollback","name","Batched cleanup atomicity"));
        var call=store.insert("callsession",Values.map("tenant_id",tid,"phone","15500009601","mode","ai_only","status","in_ai","attempts",1,"max_attempts",1));
        var control=outbox.enqueue(tid,"after_playback",call.get("id"),key(),Values.map("call_id",call.get("id"),"attempt",1,"product_kind","timer"));
        java.util.function.Supplier<Object> transition=()->{try(var scope=new CallStatusBatchScope()){
            scope.sharedResourcesLocked=true;scope.calls.put(call.get("id").toString(),store.one("callsession","lockById",Values.map("tenantId",tid,"id",store.encode("callsession","id",call.get("id")))));
            store.bufferWrites(()->lifecycle.status(Values.map("call_id",call.get("id"),"payload",Values.map("status","completed","attempt",1,"event_id","atomic-terminal"))));
            taskCleanup.complete(scope);return null;
        }};
        assertThatThrownBy(()->tx.serialized(()->{transition.get();assertThat(store.require("taskoutbox",tid,control.get("id"))).containsEntry("state","completed");throw new IllegalStateException("after-cleanup rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(store.require("callsession",tid,call.get("id"))).containsEntry("status","in_ai");assertThat(store.require("taskoutbox",tid,control.get("id"))).containsEntry("state","pending");
        tx.serialized(transition);assertThat(store.require("callsession",tid,call.get("id"))).containsEntry("status","completed");assertThat(store.require("taskoutbox",tid,control.get("id"))).containsEntry("state","completed");
    }

    @Test void realtimePressureIgnoresFutureTimersAndColdWorkButCountsOwnedDueAndDeadControls(){
        long tid=297;store.insert("tenant",Values.map("id",tid,"code","realtime-pressure","name","Realtime deadline pressure fixture"));
        var timer=outbox.enqueue(tid,"after_playback",key(),key(),Map.of());store.update("taskoutbox",tid,timer.get("id"),Values.map("available_at",Store.now().plusHours(1)));
        var cold=outbox.enqueue(tid,"call_analysis",key(),key(),Map.of());store.update("taskoutbox",tid,cold.get("id"),Values.map("available_at",Store.now().minusHours(1)));
        assertThat(RealtimeTaskPressure.fraction(taskPressure.snapshot(tid))).isEqualTo(1);
        store.update("taskoutbox",tid,timer.get("id"),Values.map("state","processing","available_at",Store.now().minusSeconds(1),"locked_at",Store.now(),"lease_token","pressure-owner"));
        assertThat(taskPressure.snapshot(tid).get("control_age_sec")).isGreaterThanOrEqualTo(1);assertThat(RealtimeTaskPressure.fraction(taskPressure.snapshot(tid))).isZero();
        store.update("taskoutbox",tid,timer.get("id"),Values.map("state","dead"));assertThat(taskPressure.snapshot(tid).get("dead")).isEqualTo(1d);
    }

}
