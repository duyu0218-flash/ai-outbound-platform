package com.duozhi.outbound;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CallbackInbox {
    private final Store store;
    private final Transactions tx;
    private final JsonSupport json;
    private final Config config;
    private final CallLifecycle lifecycle;
    private final VoiceCallbacks callbacks;
    public CallbackInbox(Store store, Transactions tx, JsonSupport json, Config config, CallLifecycle lifecycle, VoiceCallbacks callbacks) {
        this.store = store; this.tx = tx; this.json = json; this.config = config; this.lifecycle = lifecycle; this.callbacks = callbacks;
    }
    public void initialize() {
        tx.run(() -> {
            for (int id = 0; id < 64; id++) store.jdbc().update("INSERT INTO callbackinboxpartition (id,pending_count,pending_bytes) VALUES (?,0,0) ON CONFLICT(id) DO NOTHING", id);
            return null;
        });
    }
    private final String workerId="java-"+UUID.randomUUID();
    public Map<String,Object> snapshot() {
        Map<String,Object> oldest=store.one("callbackinbox","WHERE state IN ('pending','dead') ORDER BY received_at LIMIT 1");
        double age=oldest==null?0:Math.max(0,Duration.between(LocalDateTime.parse(oldest.get("received_at").toString()),Store.now()).toMillis()/1000.0);
        long dead=store.count("callbackinbox","WHERE state='dead'"),live=store.count("callbackinboxworker","WHERE heartbeat_at>=?",store.encode("callbackinboxworker","heartbeat_at",Store.now().minusSeconds(config.number("CALLBACK_INBOX_WORKER_TTL_SEC",10))));
        return JsonSupport.map("oldest_age_sec",age,"dead",dead,"live_workers",live,"ready",live>=config.number("CALLBACK_INBOX_MIN_WORKERS",1)&&dead==0&&age<=Double.parseDouble(config.get("CALLBACK_INBOX_MAX_AGE_SEC","1")));
    }
    public void maintenance() {
        tx.run(()->{
            tx.identity("callback-worker:"+workerId);
            if(store.one("callbackinboxworker","WHERE id=?",workerId)==null)store.insert("callbackinboxworker",JsonSupport.map("id",workerId,"processed",0L));
            else store.updateInternal("callbackinboxworker",workerId,JsonSupport.map("heartbeat_at",Store.now()));
            Object cutoff=store.encode("callbackinbox","completed_at",Store.now().minusDays(Math.max(7,config.number("CALLBACK_INBOX_RECEIPT_DAYS",7))));
            store.jdbc().update("DELETE FROM callbackinbox WHERE id IN (SELECT id FROM callbackinbox WHERE state='done' AND completed_at<? ORDER BY id LIMIT 100)",cutoff);
            store.jdbc().update("DELETE FROM callbackinboxworker WHERE id IN (SELECT id FROM callbackinboxworker WHERE heartbeat_at<? LIMIT 100)",cutoff);
            return null;
        });
    }
    public Map<String, Object> receive(String kind, Map<String, Object> body) {
        String encoded = json.canonical(body, false);
        int size = encoded.getBytes(StandardCharsets.UTF_8).length;
        if (size > config.number("CALLBACK_INBOX_BODY_BYTES", 262144)) throw new ApiError(413, "callback body exceeds durable receipt limit");
        String callId = body.get("call_id").toString();
        Map<?, ?> payload = (Map<?, ?>)body.getOrDefault("payload", Collections.emptyMap());
        Object eventId = body.get("event_id") == null ? payload.get("event_id") : body.get("event_id");
        String digest = JsonSupport.sha(encoded);
        String key = JsonSupport.sha(json.json(Arrays.asList(kind, callId, eventId == null || eventId.toString().isEmpty() ? digest : eventId)));
        int partitionId = (int)((ByteBuffer.wrap(JsonSupport.digest(callId)).getInt() & 0xffffffffL) % 64);
        return tx.run(() -> {
            if (store.lock().isEmpty()) store.jdbc().update("UPDATE callbackinboxpartition SET id=id WHERE id=?", partitionId);
            Map<String, Object> partition = store.one("callbackinboxpartition", "WHERE id=?" + store.lock(), partitionId);
            if (partition == null) throw new ApiError(503, "callback inbox partitions not initialized");
            Map<String, Object> existing = store.one("callbackinbox", "WHERE receipt_key=?", key);
            if (existing != null) {
                if (!Crypto.equal(digest, Objects.toString(existing.get("body_digest"), ""))) throw new ApiError(409, "event identity reused with different callback body");
                return JsonSupport.map("result", "received", "receipt_id", key, "duplicate", true, "processing", existing.get("state"));
            }
            if (((Number)partition.get("pending_count")).longValue() >= config.number("CALLBACK_INBOX_PARTITION_LIMIT", 256)
                    || ((Number)partition.get("pending_bytes")).longValue() + size > config.number("CALLBACK_INBOX_PARTITION_BYTES", 4194304))
                throw new ApiError(503, "callback inbox partition is full; retry with the same event identity");
            store.insert("callbackinbox", JsonSupport.map("receipt_key", key, "partition_id", partitionId, "call_id", callId, "kind", kind, "body_json", encoded, "body_digest", digest, "body_bytes", size));
            store.jdbc().update("UPDATE callbackinboxpartition SET pending_count=pending_count+1,pending_bytes=pending_bytes+? WHERE id=?", size, partitionId);
            return JsonSupport.map("result", "received", "receipt_id", key, "duplicate", false, "processing", "pending");
        });
    }
    public Map<String,Object> batch(List<Map<String,Object>> events) {
        return tx.run(() -> {
            Set<Integer> partitions=new TreeSet<>();
            for(Map<String,Object> event:events) {
                Map<?,?> body=(Map<?,?>)event.get("payload");String id=body.get("call_id").toString();
                partitions.add((int)((ByteBuffer.wrap(JsonSupport.digest(id)).getInt()&0xffffffffL)%64));
            }
            for(Integer id:partitions) {
                if(store.lock().isEmpty())store.jdbc().update("UPDATE callbackinboxpartition SET id=id WHERE id=?",id);
                if(store.one("callbackinboxpartition","WHERE id=?"+store.lock(),id)==null)throw new ApiError(503,"callback partitions are not initialized");
            }
            List<Object> accepted=new ArrayList<>();
            for(Map<String,Object> event:events) {
                @SuppressWarnings("unchecked")
                Map<String,Object> body=(Map<String,Object>)event.get("payload");
                receive("telephony_"+event.get("kind"),body);accepted.add(event.get("id"));
            }
            return JsonSupport.map("result","received","version",1,"accepted",accepted);
        });
    }
    public boolean consume(int partitionId) {
        // Each receipt's business writes, outbox rows and completion commit together.
        final Object[] failedId = new Object[1];
        try {
            return tx.run(() -> {
                tx.platform();
                if (!store.lock().isEmpty()) {
                    Boolean locked = store.jdbc().queryForObject("SELECT pg_try_advisory_xact_lock(19483921,?)", Boolean.class, partitionId);
                    if (!Boolean.TRUE.equals(locked)) return false;
                }
                Map<String, Object> receipt = store.one("callbackinbox", "WHERE partition_id=? AND state='pending' AND available_at<=? AND NOT EXISTS (SELECT 1 FROM callbackinbox older WHERE older.call_id=callbackinbox.call_id AND older.id<callbackinbox.id AND older.state<>'done') ORDER BY id ASC LIMIT 1" + store.lock(), partitionId, store.encode("callbackinbox", "available_at", Store.now()));
                if (receipt == null || LocalDateTime.parse(receipt.get("available_at").toString()).isAfter(Store.now())) return false;
                failedId[0] = receipt.get("id");
                Map<String, Object> body = json.object(receipt.get("body_json").toString());
                callbacks.handle(receipt.get("kind").toString(), body);
                store.updateInternal("callbackinbox", receipt.get("id"), JsonSupport.map("state", "done", "completed_at", Store.now(), "error_type", ""));
                store.jdbc().update("UPDATE callbackinboxpartition SET pending_count=pending_count-1,pending_bytes=pending_bytes-? WHERE id=?", receipt.get("body_bytes"), partitionId);
                double latency=Math.max(0,Duration.between(LocalDateTime.parse(receipt.get("received_at").toString()),Store.now()).toMillis());
                store.jdbc().update("UPDATE callbackinboxworker SET processed=processed+1,max_latency_ms=CASE WHEN max_latency_ms<? THEN ? ELSE max_latency_ms END WHERE id=?",latency,latency,workerId);
                return true;
            });
        } catch (RuntimeException e) {
            if (failedId[0] == null) throw e;
            tx.run(() -> {
                tx.platform();
                Map<String, Object> row = store.one("callbackinbox", "WHERE id=?" + store.lock(), failedId[0]);
                if (row == null || !"pending".equals(row.get("state"))) return null;
                int attempts = CallLifecycle.number(row, "attempts") + 1;
                boolean dead = attempts >= config.number("CALLBACK_INBOX_MAX_ATTEMPTS", 5);
                store.updateInternal("callbackinbox", row.get("id"), JsonSupport.map("state", dead ? "dead" : "pending", "attempts", attempts,
                        "available_at", Store.now().plusSeconds(Math.min(60, 1L << Math.min(6, attempts))), "error_type", e.getClass().getSimpleName()));

                return null;
            });
            return false;
        }
    }
}
