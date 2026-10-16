package com.duozhi.outbound;

import java.util.*;
import org.springframework.stereotype.Service;

/** Enqueue joins the caller's transaction; no external work occurs before commit. */
@Service
public class Outbox {
    private final Store store;
    private final Transactions tx;
    private final JsonSupport json;
    private final Settings settings;
    public Outbox(Store store, Transactions tx, JsonSupport json, Settings settings) {
        this.store = store; this.tx = tx; this.json = json; this.settings = settings;
    }
    public Map<String, Object> enqueue(long tenant, String type, Object aggregate, String key, Map<String, Object> payload) {
        return tx.run(() -> {
            tx.identity(key);
            Map<String, Object> receipt = store.one("taskreceipt", "WHERE idempotency_key=?", key);
            if (receipt != null) return receipt;
            Map<String, Object> existing = store.one("taskoutbox", "WHERE idempotency_key=?", key);
            if (existing != null) return existing;
            if ("ai_turn".equals(type) && payload.get("turn_sequence") instanceof Number) {
                int sequence = ((Number)payload.get("turn_sequence")).intValue();
                for (Map<String, Object> previous : store.list("taskoutbox", "WHERE tenant_id=? AND aggregate_id=? AND task_type='ai_turn' AND state IN ('PENDING','FAILED')" + store.lock(), tenant, aggregate.toString())) {
                    Map<String, Object> old = json.object(previous.get("payload_json").toString());
                    if (Objects.equals(old.get("attempt"), payload.get("attempt")) && old.get("turn_sequence") instanceof Number && ((Number)old.get("turn_sequence")).intValue() < sequence)
                        store.update("taskoutbox", tenant, previous.get("id"), JsonSupport.map("state", "completed", "last_error", "superseded by newer final transcript"));
                }
            }
            return store.insert("taskoutbox", JsonSupport.map("tenant_id", tenant, "task_type", type, "aggregate_id", aggregate.toString(), "idempotency_key", key, "payload_json", json.json(payload)));
        });
    }
    public void callback(Map<String, Object> call, String event, Map<String, Object> data, String key) {
        long tenant = ((Number)call.get("tenant_id")).longValue();
        Map<String, Object> config = settings.get(tenant, "integration");
        if (!Boolean.TRUE.equals(config.get("callback_enabled")) || Objects.toString(config.get("webhook_base_url"), "").trim().isEmpty()) return;
        enqueue(tenant, "business_callback", call.get("id"), key, JsonSupport.map("tenant_id", tenant, "call_id", call.get("id"), "event_type", event, "data", data));
    }
}
