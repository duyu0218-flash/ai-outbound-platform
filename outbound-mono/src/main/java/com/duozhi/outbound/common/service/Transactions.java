package com.duozhi.outbound.common.service;

import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
public class Transactions {
    public static final long PLATFORM_KEY = 718492061L;
    private final Store store;
    private final TransactionTemplate template;
    private final PlatformLock locks;

    public Transactions(Store store, PlatformTransactionManager manager, PlatformLock locks) {
        this.store = store;
        this.template = new TransactionTemplate(manager);
        this.locks = locks;
    }

    public <T> T run(Supplier<T> action) {
        return template.execute(status -> action.get());
    }

    /**
     * Acquire the existing platform mutex before borrowing a JDBC connection. Nested calls still
     * retain it until the surrounding transaction completes.
     */
    public <T> T serialized(Supplier<T> action) {
        return serialized(PLATFORM_KEY, action);
    }

    public <T> T serializedIdentity(String key, Supplier<T> action) {
        return serialized(ByteBuffer.wrap(JsonSupport.digest(key)).getLong(), action);
    }

    private <T> T serialized(long key, Supplier<T> action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            lock(key);
            return run(action);
        }
        try (var held = locks.lock(key)) {
            return run(action);
        }
    }

    public void lock(long key) {
        requireTransaction();
        retainUntilCompletion(locks.lock(key));
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("transaction lock requires transaction");
    }

    private void retainUntilCompletion(PlatformLock.Handle handle) {
        try {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        public void afterCompletion(int status) {
                            handle.close();
                        }
                    });
        } catch (RuntimeException error) {
            handle.close();
            throw error;
        }
    }

    public void identity(String key) {
        lock(ByteBuffer.wrap(JsonSupport.digest(key)).getLong());
    }

    public void platform() {
        lock(PLATFORM_KEY);
    }

    public Map<String, Object> tenant(long id) {
        Map<String, Object> tenant = store.one("tenant", "lockForTransaction", Map.of("id", id));
        if (tenant == null) throw new ApiError(404, "tenant not found");
        return tenant;
    }

    public Map<String, Object> call(Object id) {
        return store.locate("callsession", id, true);
    }

    public boolean tryLock(int namespace, int value) {
        requireTransaction();
        PlatformLock.Handle handle = locks.tryLock(namespace, value);
        if (handle == null) return false;
        retainUntilCompletion(handle);
        return true;
    }
}
