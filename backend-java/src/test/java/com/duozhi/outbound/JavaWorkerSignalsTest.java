package com.duozhi.outbound;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite:file:java-worker-signals-test?mode=memory&cache=shared","spring.datasource.hikari.maximum-pool-size=1","JAVA_INITIALIZE_SCHEMA=true","DEMO_USERS_ENABLED=false","JWT_SECRET=isolated-worker-test-secret-at-least-32-characters"})
class JavaWorkerSignalsTest {
    @Autowired Store store;@Autowired Transactions tx;@Autowired JsonSupport json;
    @Test void actualJavaLanePublishesOnlyAfterPollingAndWithdrawsWhenStopped() throws Exception {
        Config config=new Config(new MockEnvironment().withProperty("JAVA_WORKERS_ENABLED","true").withProperty("JAVA_DISPATCH_ENABLED","false").withProperty("STABILITY_SIGNAL_TTL_SEC","1"));
        TaskWorker worker=new TaskWorker(store,tx,config,json,null,null,null,null,null,null,null);
        try {
            assertNull(worker.aiSignal());worker.ready();assertNull(worker.aiSignal());worker.tick();
            assertTimeoutPreemptively(Duration.ofSeconds(3),()->{while(worker.aiSignal()==null)Thread.sleep(10);});
            var signal=worker.aiSignal();assertNotNull(signal);
            assertTrue(((Number)signal.get("queue_p95_ms")).doubleValue()>=0);
            assertEquals(0.0,((Number)signal.get("oldest_wait_ms")).doubleValue());
            assertTrue(System.currentTimeMillis()/1000.0-((Number)signal.get("at")).doubleValue()<3);
            assertTimeoutPreemptively(Duration.ofSeconds(3),()->{while(worker.aiSignal()!=null)Thread.sleep(10);});
        }finally{worker.close();}
        assertNull(worker.aiSignal());
    }
}
