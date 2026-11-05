package com.duozhi.outbound.common.service;

import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.dao.WorkloadDataSource;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.runtime.RuntimeOwnership;
import com.duozhi.outbound.common.support.JsonSupport;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Service
public class Transactions {
    public static final long PLATFORM_KEY = 718492061L;
    private final Store store;
    private final TransactionTemplate template;
    private final PlatformLock locks;
    private final TransactionTemplate realtime;
    private final PlatformTransactionManager manager;
    @Autowired
    private RuntimeOwnership ownership;

    public Transactions(Store store, PlatformTransactionManager manager, PlatformLock locks) {
        this.store = store;
        this.manager = manager;
        this.template = new TransactionTemplate(manager);
        this.template.setTimeout(15);
        this.realtime = new TransactionTemplate(manager);
        this.realtime.setTimeout(5);
        this.locks = locks;
    }

    public <T> T run(Supplier<T> action) {
        if (ownership != null) ownership.requireRunning();
        var budget =
                WorkloadDataSource.current() == WorkloadDataSource.Lane.REALTIME
                        ? realtime
                        : template;
        return budget.execute(
                status -> {
                    // REQUIRED joins the outer transaction. Shorten its connection deadline instead
                    // of silently ignoring the inner budget or granting a fresh timeout on reentry.
                    if (manager instanceof DataSourceTransactionManager jdbcManager
                            && TransactionSynchronizationManager.getResource(
                                            jdbcManager.getDataSource())
                                    instanceof ConnectionHolder holder) {
                        long limit = budget.getTimeout() * 1000L;
                        if (holder.hasTimeout())
                            limit = Math.min(limit, holder.getTimeToLiveInMillis());
                        holder.setTimeoutInMillis(limit);
                    }
                    T result = action.get();
                    if (manager instanceof DataSourceTransactionManager jdbcManager
                            && TransactionSynchronizationManager.getResource(
                                            jdbcManager.getDataSource())
                                    instanceof ConnectionHolder holder
                            && holder.hasTimeout()) holder.getTimeToLiveInMillis();
                    if (ownership != null) ownership.requireRunning();
                    return result;
                });
    }

    /**
     * Acquire the existing platform mutex before borrowing a JDBC connection. Nested calls still
     * retain it until the surrounding transaction completes.
     */
    public <T> T serialized(Supplier<T> action) {
        return serialized(PLATFORM_KEY, action);
    }

    public <T> T admission(Supplier<T> action) {
        try (var lane = WorkloadDataSource.use(WorkloadDataSource.Lane.REALTIME)) {
            return serialized(action);
        }
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
        try {
            retainUntilCompletion(locks.lock(key));
        } catch (RuntimeException failed) {
            if (manager instanceof DataSourceTransactionManager jdbcManager
                    && TransactionSynchronizationManager.getResource(jdbcManager.getDataSource())
                            instanceof ConnectionHolder holder) holder.setRollbackOnly();
            throw failed;
        }
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
