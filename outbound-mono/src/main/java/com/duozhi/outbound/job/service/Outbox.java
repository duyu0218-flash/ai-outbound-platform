package com.duozhi.outbound.job.service;

import com.duozhi.outbound.call.domain.CallStatusBatchScope;
import com.duozhi.outbound.capacity.service.CapacityCommitEvidence;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.system.service.Settings;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Enqueue joins the caller's transaction; no external work occurs before commit. */
@Service
public class Outbox {
    @Autowired
    private CapacityCommitEvidence timing;
    @Autowired
    private org.springframework.context.ApplicationEventPublisher events;
    private final Store store;
    private final Transactions tx;
    private final JsonSupport json;
    private final Settings settings;

    public Outbox(Store store, Transactions tx, JsonSupport json, Settings settings) {
        this.store = store;
        this.tx = tx;
        this.json = json;
        this.settings = settings;
    }

    public Map<String, Object> enqueue(
            long tenant, String type, Object aggregate, String key, Map<String, Object> payload) {
        return tx.run(
                () -> {
                    tx.identity(key);
                    var batch = CallStatusBatchScope.current();
                    boolean cached = batch != null && batch.calls.containsKey(aggregate.toString());
                    String cacheKey = tenant + ":" + key;
                    Map<String, Object> receipt =
                            cached
                                    ? batch.receipts.get(cacheKey)
                                    : store.one(
                                            "taskreceipt",
                                            "findByIdempotencyKey",
                                            Values.map("tenantId", tenant, "key", key));
                    if (receipt != null) return receipt;
                    Map<String, Object> existing =
                            cached
                                    ? batch.tasks.get(cacheKey)
                                    : store.one(
                                            "taskoutbox",
                                            "findByIdempotencyKey",
                                            Values.map("tenantId", tenant, "key", key));
                    if (existing != null) return existing;
                    if ("ai_turn".equals(type) && payload.get("turn_sequence") instanceof Number) {
                        int sequence = ((Number) payload.get("turn_sequence")).intValue();
                        for (Map<String, Object> candidate :
                                store.query(
                                        "taskoutbox",
                                        "pendingAiForAggregate",
                                        Values.map(
                                                "tenantId",
                                                tenant,
                                                "aggregate",
                                                aggregate.toString()))) {
                            Map<String, Object> previous =
                                    store.one(
                                            "taskoutbox",
                                            "lockById",
                                            Values.map(
                                                    "tenantId",
                                                    tenant,
                                                    "id",
                                                    store.encode(
                                                            "taskoutbox",
                                                            "id",
                                                            candidate.get("id"))));
                            if (previous == null
                                    || !Values.set("pending", "failed")
                                            .contains(previous.get("state"))) continue;
                            Map<String, Object> old =
                                    json.object(previous.get("payload_json").toString());
                            if (Objects.equals(old.get("attempt"), payload.get("attempt"))
                                    && old.get("turn_sequence") instanceof Number
                                    && ((Number) old.get("turn_sequence")).intValue() < sequence)
                                store.update(
                                        "taskoutbox",
                                        tenant,
                                        previous.get("id"),
                                        JsonSupport.map(
                                                "state",
                                                "completed",
                                                "last_error",
                                                "superseded by newer final transcript"));
                        }
                    }
                    var saved =
                            store.insert(
                                    "taskoutbox",
                                    JsonSupport.map(
                                            "tenant_id",
                                            tenant,
                                            "task_type",
                                            type,
                                            "aggregate_id",
                                            aggregate.toString(),
                                            "idempotency_key",
                                            key,
                                            "payload_json",
                                            json.json(payload)));
                    if (cached) batch.tasks.put(cacheKey, saved);
                    if ("dial_call".equals(type) && timing != null)
                        timing.record(
                                "queue",
                                tenant,
                                aggregate,
                                JsonSupport.integer(payload.get("attempt"), 0) + 1);
                    if (events != null) events.publishEvent(new OutboxCommitted(type));
                    return saved;
                });
    }

    public void callback(
            Map<String, Object> call, String event, Map<String, Object> data, String key) {
        long tenant = ((Number) call.get("tenant_id")).longValue();
        Map<String, Object> config = settings.get(tenant, "integration");
        if (!Boolean.TRUE.equals(config.get("callback_enabled"))
                || Objects.toString(config.get("webhook_base_url"), "").trim().isEmpty()) return;
        enqueue(
                tenant,
                "business_callback",
                call.get("id"),
                key,
                JsonSupport.map(
                        "tenant_id",
                        tenant,
                        "call_id",
                        call.get("id"),
                        "event_type",
                        event,
                        "data",
                        data));
    }
}
