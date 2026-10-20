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
    }
    @Autowired Store store;@Autowired OperationsStore db;@Autowired Transactions tx;@Autowired DialService dial;@Autowired Handoffs handoffs;
    @Autowired HumanRouting routing;@Autowired ConversationPolicy conversation;@Autowired CallLifecycle lifecycle;@Autowired JsonSupport json;
    @Autowired PlatformLock platformLocks;@Autowired javax.sql.DataSource dataSource;
    static AtomicInteger sequence=new AtomicInteger(1000);long tenant;
    @BeforeEach void seed(){tenant=sequence.incrementAndGet();store.insert("tenant",JsonSupport.map("id",tenant,"code","reliability-"+tenant,"name","Synthetic reliability"));store.insert("adminsetting",JsonSupport.map("tenant_id",tenant,"section","compliance","data_json","{\"allowed_start_hour\":0,\"allowed_end_hour\":0}"));}
    @AfterEach void releaseFixtureCapacity(){for(var c:store.query("callsession","list",JsonSupport.map("tenantId",tenant,"offset",0,"limit",1000)))if(!CallLifecycle.TERMINAL.contains(c.get("status")))store.update("callsession",tenant,c.get("id"),Map.of("status","completed"));}
    Map<String,Object> call(String state,String mode){return store.insert("callsession",JsonSupport.map("tenant_id",tenant,"phone","1555"+String.format("%07d",sequence.incrementAndGet()),"mode",mode,"status",state,"attempts",state.equals("queued")?0:1,"max_attempts",2));}
    Map<String,Object> agent(String state){return store.insert("user",JsonSupport.map("tenant_id",tenant,"username","desk-"+UUID.randomUUID(),"full_name","Synthetic desk","role","agent","agent_status",state,"last_seen_at",Store.now()));}
    void policy(Map<String,Object> call,Map<String,Object> changes){tx.serialized(()->{var state=conversation.state(call);var p=conversation.policy(state);p.putAll(changes);p.put("start_hour",0);p.put("end_hour",24);store.update("conversationstate",tenant,state.get("id"),JsonSupport.map("policy_json",json.json(p)));return null;});}
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
