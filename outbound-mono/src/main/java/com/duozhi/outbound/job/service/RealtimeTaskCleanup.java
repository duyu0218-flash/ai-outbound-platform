package com.duozhi.outbound.job.service;

import com.duozhi.outbound.call.domain.CallStatusBatchScope;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.support.JsonSupport;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Called in the reliable terminal-fact transaction, after any buffered batch has flushed. Lock
 * skips never revoke a running owner's task.
 */
@Service
public final class RealtimeTaskCleanup {
    private final Store store;

    RealtimeTaskCleanup(Store store) {
        this.store = store;
    }

    public void complete(long tenant, List<String> ids) {
        for (int start = 0; start < ids.size(); start += 100) {
            var tasks =
                    store.query(
                            "taskoutbox",
                            "obsoleteControls",
                            JsonSupport.map(
                                    "tenantId",
                                    tenant,
                                    "ids",
                                    ids.subList(start, Math.min(ids.size(), start + 100))));
            store.bufferWrites(
                    () -> {
                        for (var task : tasks)
                            store.writeLocked(
                                    "taskoutbox",
                                    tenant,
                                    task.get("id"),
                                    JsonSupport.map(
                                            "state",
                                            "completed",
                                            "lease_token",
                                            null,
                                            "locked_at",
                                            null,
                                            "last_error",
                                            "superseded by terminal attempt"));
                        return null;
                    });
        }
    }

    public void complete(CallStatusBatchScope scope) {
        for (var tenant : scope.terminalControls.entrySet())
            complete(tenant.getKey(), new ArrayList<>(tenant.getValue()));
    }
}
