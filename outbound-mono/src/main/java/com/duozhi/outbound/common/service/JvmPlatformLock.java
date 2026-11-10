package com.duozhi.outbound.common.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.runtime.RuntimeOwnership;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

@Component
public final class JvmPlatformLock implements PlatformLock {
    private static final ThreadLocal<Integer> HELD = new ThreadLocal<>();

    public static boolean heldByCurrentThread() {
        return HELD.get() != null;
    }

    private static final class Entry {
        // Non-fair: queued waiters are still served in order, but a thread arriving at release may
        // take the lock without a park/unpark hand-off, which dominates on the hot platform key.
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    private record Namespaced(int namespace, int key) {}

    private final ConcurrentHashMap<Object, Entry> entries = new ConcurrentHashMap<>();

    private final Timer platformWait, identityWait, partitionWait;
    private final Timer platformHold, identityHold, partitionHold;
    private final Counter timeouts, interruptions;
    private RuntimeOwnership ownership;
    private int backgroundWaitMs = 2000, httpWaitMs = 3000;

    @Autowired
    public JvmPlatformLock(MeterRegistry registry, Config config, RuntimeOwnership ownership) {
        this(registry);
        this.ownership = ownership;
        backgroundWaitMs = bounded(config.number("JAVA_LOCK_WAIT_MS", 2000), 2000);
        httpWaitMs = bounded(config.number("JAVA_HTTP_LOCK_WAIT_MS", 3000), 3000);
    }

    private static int bounded(int value, int maximum) {
        if (value < 1 || value > maximum) throw new IllegalArgumentException("invalid lock budget");
        return value;
    }

    public JvmPlatformLock(MeterRegistry registry) {
        timeouts = registry.counter("outbound.lock.failures", "reason", "timeout");
        interruptions = registry.counter("outbound.lock.failures", "reason", "interrupted");
        platformWait = timer(registry, "wait", "platform");
        identityWait = timer(registry, "wait", "identity");
        partitionWait = timer(registry, "wait", "partition");
        platformHold = timer(registry, "hold", "platform");
        identityHold = timer(registry, "hold", "identity");
        partitionHold = timer(registry, "hold", "partition");
    }

    private static Timer timer(MeterRegistry registry, String phase, String scope) {
        return Timer.builder("outbound.lock." + phase)
                .tag("scope", scope)
                .publishPercentileHistogram()
                .register(registry);
    }

    public Handle lock(long key) {
        return lock(
                key,
                Duration.ofMillis(
                        RequestContextHolder.getRequestAttributes() == null
                                ? backgroundWaitMs
                                : httpWaitMs));
    }

    public Handle lock(long key, Duration timeout) {
        if (timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(3)) > 0)
            throw new IllegalArgumentException("invalid lock budget");
        return acquire(key, false, timeout.toNanos());
    }

    public Handle tryLock(int namespace, int key) {
        return acquire(new Namespaced(namespace, key), true, 0);
    }

    private Handle acquire(Object key, boolean immediate, long timeoutNanos) {
        if (ownership != null) ownership.requireRunning();
        Timer wait =
                immediate
                        ? partitionWait
                        : Long.valueOf(Transactions.PLATFORM_KEY).equals(key)
                                ? platformWait
                                : identityWait;
        Timer hold =
                immediate
                        ? partitionHold
                        : Long.valueOf(Transactions.PLATFORM_KEY).equals(key)
                                ? platformHold
                                : identityHold;
        long waiting = System.nanoTime();
        Entry entry =
                entries.compute(
                        key,
                        (k, current) -> {
                            if (current == null) current = new Entry();
                            current.users++;
                            return current;
                        });
        boolean acquiredLock;
        try {
            acquiredLock =
                    immediate
                            ? entry.lock.tryLock()
                            : entry.lock.tryLock(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            wait.record(System.nanoTime() - waiting, TimeUnit.NANOSECONDS);
            releaseReference(key, entry);
            interruptions.increment();
            Thread.currentThread().interrupt();
            throw new PlatformLock.Unavailable(true);
        }
        if (!acquiredLock) {
            wait.record(System.nanoTime() - waiting, TimeUnit.NANOSECONDS);
            releaseReference(key, entry);
            if (immediate) return null;
            timeouts.increment();
            throw new PlatformLock.Unavailable(false);
        }
        try {
            if (ownership != null) ownership.requireRunning();
        } catch (RuntimeException frozen) {
            entry.lock.unlock();
            releaseReference(key, entry);
            throw frozen;
        }
        wait.record(System.nanoTime() - waiting, TimeUnit.NANOSECONDS);
        long acquired = System.nanoTime();
        Thread owner = Thread.currentThread();
        Integer depth = HELD.get();
        HELD.set(depth == null ? 1 : depth + 1);
        var closed = new AtomicBoolean();
        return () -> {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("lock handle belongs to another thread");
            if (closed.compareAndSet(false, true)) {
                entry.lock.unlock();
                int remaining = HELD.get() - 1;
                if (remaining == 0) HELD.remove();
                else HELD.set(remaining);
                releaseReference(key, entry);
                hold.record(System.nanoTime() - acquired, TimeUnit.NANOSECONDS);
            }
        };
    }

    private void releaseReference(Object key, Entry entry) {
        entries.compute(
                key,
                (k, current) -> {
                    if (current != entry)
                        throw new IllegalStateException("transaction lock ownership changed");
                    return --entry.users == 0 ? null : entry;
                });
    }

    int retainedKeys() {
        return entries.size();
    }
}
