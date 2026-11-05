package com.duozhi.outbound.common.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.duozhi.outbound.common.config.Config;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ScheduledWorkTest {
    @Test
    void blockedMarketingCannotDelayDialOrCallbacksAndCannotReenter() throws Exception {
        var metrics = new SimpleMeterRegistry();
        try (var ownership =
                        new RuntimeOwnership(null, "test", () -> fail("unexpected halt"), false);
                var work =
                        new ScheduledWork(
                                new Config(
                                        new MockEnvironment()
                                                .withProperty("JAVA_SCHEDULED_WORK_MS", "100")),
                                metrics,
                                ownership)) {
            var entered = new CountDownLatch(1);
            var unblock = new CountDownLatch(1);
            var finished = new CountDownLatch(1);
            var interruptions = new AtomicInteger();
            work.submit(
                    ScheduledWork.Job.DOMAIN_PROBE,
                    () -> {
                        entered.countDown();
                        try {
                            // Deliberately emulate a dependency ignoring cancellation. This lane
                            // must
                            // stay owned until it really exits; timeout must not launch a second
                            // writer.
                            while (unblock.getCount() != 0) {
                                try {
                                    unblock.await();
                                } catch (InterruptedException ignored) {
                                    interruptions.incrementAndGet();
                                }
                            }
                        } finally {
                            finished.countDown();
                        }
                    });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            try {
                var dial = new CountDownLatch(1);
                var callbacks = new CountDownLatch(1);
                assertTrue(work.submit(ScheduledWork.Job.TASKS, dial::countDown));
                assertTrue(work.submit(ScheduledWork.Job.CALLBACKS, callbacks::countDown));
                assertTrue(dial.await(1, TimeUnit.SECONDS));
                assertTrue(callbacks.await(1, TimeUnit.SECONDS));
                assertFalse(work.submit(ScheduledWork.Job.DOMAIN_PROBE, () -> fail("overlap")));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (interruptions.get() == 0 && System.nanoTime() < deadline)
                    Thread.onSpinWait();
                assertEquals(1, interruptions.get());
                assertFalse(
                        work.submit(
                                ScheduledWork.Job.DOMAIN_PROBE,
                                () -> fail("timeout released lane")));
            } finally {
                unblock.countDown();
            }
            assertTrue(finished.await(1, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    metrics.get("outbound.scheduled.budget_exceeded")
                            .tag("job", "domain_probe")
                            .counter()
                            .count());
            work.close();
            assertFalse(work.submit(ScheduledWork.Job.TASKS, () -> fail("closed")));
        }
    }

    @Test
    void frozenRuntimeDoesNotStartScheduledWork() throws Exception {
        try (var ownership = new RuntimeOwnership(null, "test", () -> {}, false);
                var work =
                        new ScheduledWork(
                                new Config(new MockEnvironment()),
                                new SimpleMeterRegistry(),
                                ownership)) {
            ownership.close();
            assertFalse(work.submit(ScheduledWork.Job.DIAL_RECOVERY, () -> fail("frozen writer")));
        }
    }
}
