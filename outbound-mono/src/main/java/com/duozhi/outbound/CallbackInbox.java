package com.duozhi.outbound;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
            for (int id = 0; id < 64; id++) store.execute("callbackinboxpartition","initializePartition",Map.of("id",id));
            return null;
        });
    }
    private final String workerId="java-"+UUID.randomUUID();
    public Map<String,Object> snapshot() {
        Map<String,Object> oldest=store.one("callbackinbox","sourceCallbackInbox28_0",Store.queryParameters(-1));
        double age=oldest==null?0:Math.max(0,java.time.Duration.between(LocalDateTime.parse(oldest.get("received_at").toString()),Store.now()).toMillis()/1000.0);
        long dead=store.scalar("callbackinbox","sourceCallbackInbox30_1",Store.queryParameters(-1)),live=store.scalar("callbackinboxworker","sourceCallbackInbox30_2",Store.queryParameters(-1,store.encode("callbackinboxworker","heartbeat_at",Store.now().minusSeconds(config.number("CALLBACK_INBOX_WORKER_TTL_SEC",10)))));
        return JsonSupport.map("oldest_age_sec",age,"dead",dead,"live_workers",live,"ready",live>=config.number("CALLBACK_INBOX_MIN_WORKERS",1)&&dead==0&&age<=Double.parseDouble(config.get("CALLBACK_INBOX_MAX_AGE_SEC","1")));
    }
    public void maintenance() {
        tx.run(()->{
            tx.identity("callback-worker:"+workerId);
            if(store.one("callbackinboxworker","sourceCallbackInbox36_3",Store.queryParameters(-1,workerId))==null)store.insert("callbackinboxworker",JsonSupport.map("id",workerId,"processed",0L));
            else store.updateInternal("callbackinboxworker",workerId,JsonSupport.map("heartbeat_at",Store.now()));
            Object cutoff=store.encode("callbackinbox","completed_at",Store.now().minusDays(Math.max(7,config.number("CALLBACK_INBOX_RECEIPT_DAYS",7))));
            for(var expired:store.query("callbackinbox","expiredReceipts",Store.queryParameters(-1,cutoff)))
                store.execute("callbackinbox","deleteExpiredReceipt",Values.map("id",expired.get("id"),"cutoff",cutoff));
            store.execute("callbackinboxworker","rawCallbackInbox40_2",Store.queryParameters(-1,cutoff));
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
            Map<String, Object> partition = store.one("callbackinboxpartition","sourceCallbackInbox56_4",Store.queryParameters(-1,partitionId));
            if (partition == null) throw new ApiError(503, "callback inbox partitions not initialized");
            Map<String, Object> existing = store.one("callbackinbox","sourceCallbackInbox58_5",Store.queryParameters(-1,key));
            if (existing != null) {
                if (!Crypto.equal(digest, Objects.toString(existing.get("body_digest"), ""))) throw new ApiError(409, "event identity reused with different callback body");
                return JsonSupport.map("result", "received", "receipt_id", key, "duplicate", true, "processing", existing.get("state"));
            }
            if (((Number)partition.get("pending_count")).longValue() >= config.number("CALLBACK_INBOX_PARTITION_LIMIT", 256)
                    || ((Number)partition.get("pending_bytes")).longValue() + size > config.number("CALLBACK_INBOX_PARTITION_BYTES", 4194304))
                throw new ApiError(503, "callback inbox partition is full; retry with the same event identity");
            store.insert("callbackinbox", JsonSupport.map("receipt_key", key, "partition_id", partitionId, "call_id", callId, "kind", kind, "body_json", encoded, "body_digest", digest, "body_bytes", size));
            store.execute("callbackinboxpartition","rawCallbackInbox67_4",Store.queryParameters(-1,size,partitionId));
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
                if(store.one("callbackinboxpartition","sourceCallbackInbox80_6",Store.queryParameters(-1,id))==null)throw new ApiError(503,"callback partitions are not initialized");
            }
            List<Object> accepted=new ArrayList<>();
            for(Map<String,Object> event:events) {
                @SuppressWarnings("unchecked") Map<String,Object> body=(Map<String,Object>)event.get("payload");
                receive("telephony_"+event.get("kind"),body);accepted.add(event.get("id"));
            }
            return JsonSupport.map("result","received","version",1,"accepted",accepted);
        });
    }
    public boolean consume(int partitionId) {
        // Each receipt's business writes, outbox rows and completion commit together.
        final Object[] failedId = new Object[1];
        try {
            return tx.serialized(() -> {
                if (!store.lock().isEmpty()) {
                    Boolean locked = tx.tryLock(19483921,partitionId);
                    if (!Boolean.TRUE.equals(locked)) return false;
                }
                // Match receive/batch ordering: partition before receipt, never the reverse.
                if(store.one("callbackinboxpartition","sourceCallbackInbox56_4",Store.queryParameters(-1,partitionId))==null)return false;
                Map<String, Object> receipt = store.one("callbackinbox","sourceCallbackInbox100_7",Store.queryParameters(-1,partitionId,store.encode("callbackinbox", "available_at", Store.now())));
                if(receipt!=null)receipt=store.one("callbackinbox","sourceCallbackInbox115_8",Store.queryParameters(-1,receipt.get("id")));
                if (receipt == null || !"pending".equals(receipt.get("state")) || LocalDateTime.parse(receipt.get("available_at").toString()).isAfter(Store.now())) return false;
                failedId[0] = receipt.get("id");
                Map<String, Object> body = json.object(receipt.get("body_json").toString());
                callbacks.handle(receipt.get("kind").toString(), body);
                store.updateInternal("callbackinbox", receipt.get("id"), JsonSupport.map("state", "done", "completed_at", Store.now(), "error_type", ""));
                store.execute("callbackinboxpartition","rawCallbackInbox106_7",Store.queryParameters(-1,receipt.get("body_bytes"),partitionId));
                double latency=Math.max(0,java.time.Duration.between(LocalDateTime.parse(receipt.get("received_at").toString()),Store.now()).toMillis());
                store.execute("callbackinboxworker","rawCallbackInbox108_8",Store.queryParameters(-1,latency,latency,workerId));
                return true;
            });
        } catch (RuntimeException e) {
            if (failedId[0] == null) throw e;
            tx.serialized(() -> {
                Map<String, Object> row = store.one("callbackinbox","sourceCallbackInbox115_8",Store.queryParameters(-1,failedId[0]));
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
