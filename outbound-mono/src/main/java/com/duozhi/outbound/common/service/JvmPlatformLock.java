package com.duozhi.outbound.common.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
public final class JvmPlatformLock implements PlatformLock {
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

    public JvmPlatformLock(MeterRegistry registry) {
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
        return acquire(key, false);
    }

    public Handle tryLock(int namespace, int key) {
        return acquire(new Namespaced(namespace, key), true);
    }

    private Handle acquire(Object key, boolean immediate) {
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
        if (immediate && !entry.lock.tryLock()) {
            releaseReference(key, entry);
            return null;
        }
        if (!immediate) entry.lock.lock();
        wait.record(System.nanoTime() - waiting, TimeUnit.NANOSECONDS);
        long acquired = System.nanoTime();
        var closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                entry.lock.unlock();
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
