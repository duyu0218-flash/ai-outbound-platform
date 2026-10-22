package com.duozhi.outbound.integration.test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.agent.service.HumanRouting;
import com.duozhi.outbound.common.config.Config;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class HumanRoutingSchedulerTest {
    @Test
    void blockedRedistributionCannotBlockTheSharedSchedulerOrQueueAnotherSweep() throws Exception {
        var config = mock(Config.class);
        when(config.flag("JAVA_WORKERS_ENABLED", false)).thenReturn(true);
        var routing = spy(new HumanRouting(null, null, null, null, null, null, config, null));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            entered.countDown();
                            release.await(3, TimeUnit.SECONDS);
                            return 0;
                        })
                .when(routing)
                .redistribute();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1), routing::tick);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), routing::tick);
            verify(routing, times(1)).redistribute();
        } finally {
            release.countDown();
            routing.close();
        }
    }
}
