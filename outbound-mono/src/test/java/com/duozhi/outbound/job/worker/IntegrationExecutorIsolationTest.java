package com.duozhi.outbound.job.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.ai.service.AiRuntime;
import com.duozhi.outbound.call.service.CallLifecycle;
import com.duozhi.outbound.callback.service.CallbackInbox;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.dial.service.DialDrainControl;
import com.duozhi.outbound.dial.service.DialService;
import com.duozhi.outbound.dial.worker.DialBatchWorker;
import com.duozhi.outbound.job.service.IntegrationTasks;
import com.duozhi.outbound.job.service.Outbox;
import com.duozhi.outbound.operations.controller.CallAnalysis;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

class IntegrationExecutorIsolationTest {
    @Test
    void slowPushAndRecordingDoNotConsumeNodeInterfaceOrSmsThreads() throws Exception {
        var release = new CountDownLatch(1);
        var slow = new CountDownLatch(2);
        var interfaceRan = new CountDownLatch(1);
        var smsRan = new CountDownLatch(1);
        var worker =
                new TaskWorker(
                        mock(Store.class),
                        mock(Transactions.class),
                        new Config(
                                new MockEnvironment()
                                        .withProperty("JAVA_WORKERS_ENABLED", "true")
                                        .withProperty("JAVA_TASK_WORKERS", "1")
                                        .withProperty("JAVA_INTEGRATION_TASK_WORKERS", "1")),
                        mock(JsonSupport.class),
                        mock(Outbox.class),
                        mock(DialService.class),
                        mock(CallLifecycle.class),
                        mock(CallAnalysis.class),
                        mock(CallbackInbox.class),
                        mock(AiRuntime.class),
                        mock(IntegrationTasks.class),
                        mock(DialBatchWorker.class)) {
                    public int enqueueDue() {
                        return 0;
                    }

                    public boolean processLane(Long tenant, int lane) {
                        try {
                            if (lane == integrationLane("push")
                                    || lane == integrationLane("recording")) {
                                slow.countDown();
                                release.await(3, TimeUnit.SECONDS);
                            } else if (lane == integrationLane("interface"))
                                interfaceRan.countDown();
                            else if (lane == integrationLane("sms")) smsRan.countDown();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return false;
                    }
                };
        ReflectionTestUtils.setField(worker, "dialControl", mock(DialDrainControl.class));
        try {
            worker.ready();
            worker.tick();
            assertThat(slow.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(interfaceRan.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(smsRan.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            worker.close();
        }
    }
}
