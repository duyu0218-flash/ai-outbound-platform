package com.duozhi.outbound;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=${JAVA_TEST_DATABASE_URL:jdbc:sqlite:file:java-auth-test?mode=memory&cache=shared}","spring.datasource.hikari.maximum-pool-size=1","JAVA_INITIALIZE_SCHEMA=true","JWT_SECRET=java-integration-test-secret-at-least-32-characters","DEMO_USERS_ENABLED=true"})
@AutoConfigureMockMvc
class StabilityIntegrationTest {
    @Autowired Store store;@Autowired Transactions tx;@Autowired JsonSupport json;@Autowired CallbackInbox inbox;
    @Test void hardPressurePausesImmediatelyAndHealthyRecoveryRequiresHold() {
        Map<String,Object> state=StabilityAdmission.advance(null,1000,0,0,true);assertEquals("PAUSED",state.get("mode"));
        state=StabilityAdmission.advance(state,1059,0,0,true);assertEquals(0.0,state.get("fraction"));
        state=StabilityAdmission.advance(state,1060,0,0,true);assertEquals("RECOVERING",state.get("mode"));assertEquals(.5,state.get("fraction"));
        state=StabilityAdmission.advance(state,1061,0,.6,true);assertEquals(0.0,state.get("fraction"));assertEquals("PAUSED",state.get("mode"));
        state=StabilityAdmission.advance(state,1062,0,0,true);state=StabilityAdmission.advance(state,1121,0,0,true);assertEquals(0.0,state.get("fraction"));
        state=StabilityAdmission.advance(state,1122,0,0,true);assertEquals(.5,state.get("fraction"));
        state=StabilityAdmission.advance(state,1200,0,0,false);assertEquals(0.0,state.get("fraction"));
    }
    @Test void softPressureThrottlesOnlyAfterSustainedWindow() {
        Map<String,Object> state=JsonSupport.map("mode","NORMAL","fraction",1.0,"changed_at",0,"version",0);
        state=StabilityAdmission.advance(state,20,100,0,true);assertEquals(1.0,state.get("fraction"));
        state=StabilityAdmission.advance(state,29,100,0,true);assertEquals(1.0,state.get("fraction"));
        state=StabilityAdmission.advance(state,30,100,0,true);assertEquals(.8,state.get("fraction"));assertEquals("THROTTLED",state.get("mode"));
        state=StabilityAdmission.advance(JsonSupport.map("mode","NORMAL","fraction",Double.NaN),31,0,0,true);assertEquals(0.0,state.get("fraction"));
    }
    @Test void missingRedisDoesNotUseDevelopmentMemoryForAdmission() {
        Config cfg=new Config(new MockEnvironment().withProperty("STABILITY_ADMISSION_ENABLED","true"));StabilityAdmission gate=new StabilityAdmission(cfg,new ExpiringValues(cfg),json,inbox);
        assertEquals(0.0,gate.fraction(Collections.emptyList()));assertEquals("PAUSED",gate.status().get("mode"));
    }
    @Test void sharedRedisSignalsAndMediaValuesWorkAcrossInstances() {
        String endpoint=System.getenv("JAVA_TEST_REDIS_URL");org.junit.jupiter.api.Assumptions.assumeTrue(endpoint!=null,"set JAVA_TEST_REDIS_URL for isolated Redis integration");
        String prefix="java-test:"+UUID.randomUUID();Config cfg=new Config(new MockEnvironment().withProperty("REDIS_URL",endpoint).withProperty("STABILITY_ADMISSION_ENABLED","true").withProperty("STABILITY_MIN_AI_WORKERS","1").withProperty("STABILITY_REDIS_KEY",prefix));
        ExpiringValues first=new ExpiringValues(cfg),second=new ExpiringValues(cfg);StabilityAdmission gate=new StabilityAdmission(cfg,first,json,inbox);
        List<Map<String,Object>> nodes=Arrays.asList(JsonSupport.map("id","test","cps",1,"enabled",true));
        try {
            first.set(prefix+":media","registered",30);assertEquals("registered",second.get(prefix+":media"));second.delete(prefix+":media");assertNull(first.get(prefix+":media"));
            double now=System.currentTimeMillis()/1000.0;
            first.shared("HSET",prefix+":signals","ai:test",json.json(JsonSupport.map("at",now,"queue_p95_ms",0,"oldest_wait_ms",0)));gate.publishGateway("test",true,0);
            first.shared("SET",prefix+":control",json.json(JsonSupport.map("mode","NORMAL","fraction",1.0,"changed_at",now-120,"version",0)),"EX","120");
            // The test has its own inbox view, independent of other tests' intentionally dead callbacks.
            CallbackInbox healthy=new CallbackInbox(store,tx,json,cfg,null,null){@Override public Map<String,Object> snapshot(){return JsonSupport.map("ready",true,"oldest_age_sec",0);}};
            StabilityAdmission independent=new StabilityAdmission(cfg,second,json,healthy);assertEquals(1.0,independent.fraction(nodes));
            gate.publishGateway("test",true,.7);assertEquals(0.0,independent.fraction(nodes));assertEquals("PAUSED",independent.status().get("mode"));
            first.shared("HSET",prefix+":signals","ai:old",json.json(JsonSupport.map("at",now-60,"queue_p95_ms",0,"oldest_wait_ms",0)));independent.fraction(nodes);
            assertFalse(((List<?>)first.shared("HGETALL",prefix+":signals")).contains("ai:old"));
        }finally{first.shared("DEL",prefix+":control",prefix+":signals",prefix+":media");}
    }
}
