package com.duozhi.outbound.common.service;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class JvmPlatformLockTest {
    @Test
    void reentryWaitersAndReferenceCleanupPreserveOneKey() throws Exception {
        var locks = new JvmPlatformLock(new SimpleMeterRegistry());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = locks.lock(7);
            var again = locks.lock(7);
            var entered = new CountDownLatch(1);
            var waiter =
                    pool.submit(
                            () -> {
                                try (var ignored = locks.lock(7)) {
                                    entered.countDown();
                                }
                            });
            assertFalse(entered.await(100, TimeUnit.MILLISECONDS));
            again.close();
            assertFalse(entered.await(100, TimeUnit.MILLISECONDS));
            first.close();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            waiter.get(2, TimeUnit.SECONDS);
            assertEquals(0, locks.retainedKeys());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void namespacedTryLockIsImmediateAndSeparateFromLongKey() throws Exception {
        var locks = new JvmPlatformLock(new SimpleMeterRegistry());
        var pool = Executors.newSingleThreadExecutor();
        try (var one = locks.tryLock(1, 2);
                var unrelated = locks.lock(2)) {
            assertNotNull(one);
            assertNotNull(unrelated);
            assertNull(pool.submit(() -> locks.tryLock(1, 2)).get(1, TimeUnit.SECONDS));
            try (var reentry = locks.tryLock(1, 2)) {
                assertNotNull(reentry);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, locks.retainedKeys());
    }

    @Test
    void metricsUseBoundedScopesAndCountEachHandleOnce() {
        var registry = new SimpleMeterRegistry();
        var locks = new JvmPlatformLock(registry);
        var platform = locks.lock(Transactions.PLATFORM_KEY);
        platform.close();
        platform.close();
        try (var identity = locks.lock(42);
                var partition = locks.tryLock(1, 99)) {
            assertNotNull(partition);
        }
        for (String scope : java.util.List.of("platform", "identity", "partition")) {
            assertEquals(1, registry.get("outbound.lock.wait").tag("scope", scope).timer().count());
            assertEquals(1, registry.get("outbound.lock.hold").tag("scope", scope).timer().count());
        }
        assertEquals(6, registry.getMeters().size());
        assertEquals(0, locks.retainedKeys());
    }
}
