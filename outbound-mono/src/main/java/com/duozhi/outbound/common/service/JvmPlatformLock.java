package com.duozhi.outbound.common.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
public final class JvmPlatformLock implements PlatformLock {
    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock(true);
        int users;
    }

    private record Namespaced(int namespace, int key) {}

    private final ConcurrentHashMap<Object, Entry> entries = new ConcurrentHashMap<>();

    public Handle lock(long key) {
        return acquire(key, false);
    }

    public Handle tryLock(int namespace, int key) {
        return acquire(new Namespaced(namespace, key), true);
    }

    private Handle acquire(Object key, boolean immediate) {
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
        var closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                entry.lock.unlock();
                releaseReference(key, entry);
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
