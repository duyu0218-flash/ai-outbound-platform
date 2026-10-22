package com.duozhi.outbound.callback.dao;

import com.duozhi.outbound.call.domain.CallStatusBatchScope;
import com.duozhi.outbound.callback.service.CallbackInbox;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.dial.dao.DialBatchStore;
import com.duozhi.outbound.system.service.Settings;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.springframework.stereotype.Repository;

@Repository
public class CallbackBatchStore {
    private final SqlSession sql;
    private final Store store;
    private final DialBatchStore calls;
    private final Settings settings;

    public CallbackBatchStore(
            SqlSession sql, Store store, DialBatchStore calls, Settings settings) {
        this.sql = sql;
        this.store = store;
        this.calls = calls;
        this.settings = settings;
    }

    private String statement(String method) {
        return "com.duozhi.outbound.callback.dao.CallbackBatchMapper." + method;
    }

    public List<Map<String, Object>> rows(String method, Map<String, Object> params) {
        return sql.selectList(statement(method), params);
    }

    public List<Map<String, Object>> records(
            String method, String table, Map<String, Object> params) {
        return sql.selectList(statement(method), params).stream()
                .map(raw -> store.record(table, raw))
                .toList();
    }

    public int write(String method, Map<String, Object> params) {
        return sql.update(statement(method), params);
    }

    public void prepare(CallStatusBatchScope scope, List<Map<String, Object>> receipts) {
        var ids =
                receipts.stream()
                        .map(r -> CallbackInbox.canonicalCallId(r.get("call_id")))
                        .distinct()
                        .toList();
        for (var tenant : store.query("tenant", "allForMaintenance", Map.of())) {
            long tid = ((Number) tenant.get("id")).longValue();
            var found = calls.calls(tid, ids);
            if (found.isEmpty()) continue;
            var selected = found.stream().map(c -> c.get("id").toString()).toList();
            for (var call : found) scope.calls.put(call.get("id").toString(), call);
            var params = JsonSupport.map("tenantId", tid, "ids", selected);
            for (var usage : records("usage", "callusage", params))
                scope.usage.put(usage.get("call_session_id") + ":" + usage.get("attempt"), usage);
            for (var event : records("events", "webhookeventingest", params))
                scope.events.put(
                        event.get("call_session_id") + ":" + event.get("provider_event_key"),
                        event);
            for (var task : records("tasks", "taskoutbox", params))
                scope.tasks.put(tid + ":" + task.get("idempotency_key"), task);
            for (var task : records("taskReceipts", "taskreceipt", params))
                scope.receipts.put(tid + ":" + task.get("idempotency_key"), task);
            if (scope.sharedResourcesLocked) {
                for (String id : selected) scope.handoffs.put(id, new ArrayList<>());
                for (var handoff : records("handoffs", "handoffrequest", params))
                    scope.handoffs
                            .computeIfAbsent(
                                    handoff.get("call_session_id").toString(),
                                    k -> new ArrayList<>())
                            .add(handoff);
            }
            settings.get(tid, "integration");
        }
    }
}
