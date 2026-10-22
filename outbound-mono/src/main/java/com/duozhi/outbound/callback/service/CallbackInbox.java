package com.duozhi.outbound.callback.service;

import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.call.controller.VoiceCallbacks;
import com.duozhi.outbound.call.domain.CallStatusBatchScope;
import com.duozhi.outbound.call.service.CallLifecycle;
import com.duozhi.outbound.callback.dao.CallbackBatchStore;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.job.service.RealtimeTaskCleanup;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.ibatis.executor.BatchExecutorException;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CallbackInbox {
    @Autowired
    private RealtimeTaskCleanup taskCleanup;
    private final Store store;
    private final Transactions tx;
    private final JsonSupport json;
    private final Config config;
    private final CallLifecycle lifecycle;
    private final VoiceCallbacks callbacks;
    private final CallbackBatchStore batchDb;

    public CallbackInbox(
            Store store,
            Transactions tx,
            JsonSupport json,
            Config config,
            CallLifecycle lifecycle,
            VoiceCallbacks callbacks,
            CallbackBatchStore batchDb) {
        this.store = store;
        this.tx = tx;
        this.json = json;
        this.config = config;
        this.lifecycle = lifecycle;
        this.callbacks = callbacks;
        this.batchDb = batchDb;
    }

    public void initialize() {
        tx.run(
                () -> {
                    for (int id = 0; id < 64; id++)
                        store.execute(
                                "callbackinboxpartition", "initializePartition", Map.of("id", id));
                    return null;
                });
    }

    private final String workerId = "java-" + UUID.randomUUID();

    public static String canonicalCallId(Object value) {
        String text = value.toString();
        try {
            return UUID.fromString(text).toString();
        } catch (IllegalArgumentException opaque) {
            return text;
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> oldest =
                store.one("callbackinbox", "sourceCallbackInbox28_0", Store.queryParameters(-1));
        double age =
                oldest == null
                        ? 0
                        : Math.max(
                                0,
                                Duration.between(
                                                        LocalDateTime.parse(
                                                                oldest.get("received_at")
                                                                        .toString()),
                                                        Store.now())
                                                .toMillis()
                                        / 1000.0);
        long
                dead =
                        store.scalar(
                                "callbackinbox",
                                "sourceCallbackInbox30_1",
                                Store.queryParameters(-1)),
                live =
                        store.scalar(
                                "callbackinboxworker",
                                "sourceCallbackInbox30_2",
                                Store.queryParameters(
                                        -1,
                                        store.encode(
                                                "callbackinboxworker",
                                                "heartbeat_at",
                                                Store.now()
                                                        .minusSeconds(
                                                                config.number(
                                                                        "CALLBACK_INBOX_WORKER_TTL_SEC",
                                                                        10)))));
        return JsonSupport.map(
                "oldest_age_sec",
                age,
                "dead",
                dead,
                "live_workers",
                live,
                "ready",
                live >= config.number("CALLBACK_INBOX_MIN_WORKERS", 1)
                        && dead == 0
                        && age
                                <= Double.parseDouble(
                                        config.get("CALLBACK_INBOX_MAX_AGE_SEC", "1")));
    }

    public void maintenance() {
        tx.run(
                () -> {
                    tx.identity("callback-worker:" + workerId);
                    if (store.one(
                                    "callbackinboxworker",
                                    "sourceCallbackInbox36_3",
                                    Store.queryParameters(-1, workerId))
                            == null)
                        store.insert(
                                "callbackinboxworker",
                                JsonSupport.map("id", workerId, "processed", 0L));
                    else
                        store.updateInternal(
                                "callbackinboxworker",
                                workerId,
                                JsonSupport.map("heartbeat_at", Store.now()));
                    Object cutoff =
                            store.encode(
                                    "callbackinbox",
                                    "completed_at",
                                    Store.now()
                                            .minusDays(
                                                    Math.max(
                                                            7,
                                                            config.number(
                                                                    "CALLBACK_INBOX_RECEIPT_DAYS",
                                                                    7))));
                    for (var expired :
                            store.query(
                                    "callbackinbox",
                                    "expiredReceipts",
                                    Store.queryParameters(-1, cutoff)))
                        store.execute(
                                "callbackinbox",
                                "deleteExpiredReceipt",
                                Values.map("id", expired.get("id"), "cutoff", cutoff));
                    store.execute(
                            "callbackinboxworker",
                            "rawCallbackInbox40_2",
                            Store.queryParameters(-1, cutoff));
                    return null;
                });
    }

    public Map<String, Object> receive(String kind, Map<String, Object> body) {
        String encoded = json.canonical(body, false);
        int size = encoded.getBytes(StandardCharsets.UTF_8).length;
        if (size > config.number("CALLBACK_INBOX_BODY_BYTES", 262144))
            throw new ApiError(413, "callback body exceeds durable receipt limit");
        String rawCallId = body.get("call_id").toString(), callId = canonicalCallId(rawCallId);
        Map<?, ?> payload = (Map<?, ?>) body.getOrDefault("payload", Collections.emptyMap());
        Object eventId =
                body.get("event_id") == null ? payload.get("event_id") : body.get("event_id");
        String digest = JsonSupport.sha(encoded);
        String key =
                JsonSupport.sha(
                        json.json(
                                Arrays.asList(
                                        kind,
                                        rawCallId,
                                        eventId == null || eventId.toString().isEmpty()
                                                ? digest
                                                : eventId)));
        int partitionId =
                (int) ((ByteBuffer.wrap(JsonSupport.digest(callId)).getInt() & 0xffffffffL) % 64);
        return tx.run(
                () -> {
                    Map<String, Object> partition =
                            store.one(
                                    "callbackinboxpartition",
                                    "sourceCallbackInbox56_4",
                                    Store.queryParameters(-1, partitionId));
                    if (partition == null)
                        throw new ApiError(503, "callback inbox partitions not initialized");
                    Map<String, Object> existing =
                            store.one(
                                    "callbackinbox",
                                    "sourceCallbackInbox58_5",
                                    Store.queryParameters(-1, key));
                    if (existing != null) {
                        if (!Crypto.equal(
                                digest, Objects.toString(existing.get("body_digest"), "")))
                            throw new ApiError(
                                    409, "event identity reused with different callback body");
                        return JsonSupport.map(
                                "result",
                                "received",
                                "receipt_id",
                                key,
                                "duplicate",
                                true,
                                "processing",
                                existing.get("state"));
                    }
                    if (((Number) partition.get("pending_count")).longValue()
                                    >= config.number("CALLBACK_INBOX_PARTITION_LIMIT", 256)
                            || ((Number) partition.get("pending_bytes")).longValue() + size
                                    > config.number("CALLBACK_INBOX_PARTITION_BYTES", 4194304))
                        throw new ApiError(
                                503,
                                "callback inbox partition is full; retry with the same event"
                                        + " identity");
                    store.insert(
                            "callbackinbox",
                            JsonSupport.map(
                                    "receipt_key",
                                    key,
                                    "partition_id",
                                    partitionId,
                                    "call_id",
                                    callId,
                                    "kind",
                                    kind,
                                    "body_json",
                                    encoded,
                                    "body_digest",
                                    digest,
                                    "body_bytes",
                                    size));
                    store.execute(
                            "callbackinboxpartition",
                            "rawCallbackInbox67_4",
                            Store.queryParameters(-1, size, partitionId));
                    return JsonSupport.map(
                            "result",
                            "received",
                            "receipt_id",
                            key,
                            "duplicate",
                            false,
                            "processing",
                            "pending");
                });
    }

    public Map<String, Object> batch(List<Map<String, Object>> events) {
        if (config.number("JAVA_CALLBACK_BATCH_SIZE", 1) > 1) return receiveBatch(events);
        return tx.run(
                () -> {
                    Set<Integer> partitions = new TreeSet<>();
                    for (Map<String, Object> event : events) {
                        Map<?, ?> body = (Map<?, ?>) event.get("payload");
                        String id = canonicalCallId(body.get("call_id"));
                        partitions.add(
                                (int)
                                        ((ByteBuffer.wrap(JsonSupport.digest(id)).getInt()
                                                        & 0xffffffffL)
                                                % 64));
                    }
                    for (Integer id : partitions) {
                        if (store.one(
                                        "callbackinboxpartition",
                                        "sourceCallbackInbox80_6",
                                        Store.queryParameters(-1, id))
                                == null)
                            throw new ApiError(503, "callback partitions are not initialized");
                    }
                    List<Object> accepted = new ArrayList<>();
                    for (Map<String, Object> event : events) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> body = (Map<String, Object>) event.get("payload");
                        receive("telephony_" + event.get("kind"), body);
                        accepted.add(event.get("id"));
                    }
                    return JsonSupport.map(
                            "result", "received", "version", 1, "accepted", accepted);
                });
    }

    private Map<String, Object> receiveBatch(List<Map<String, Object>> events) {
        var entries = new ArrayList<Map<String, Object>>();
        var ids = new TreeSet<Integer>();
        for (var event : events) {
            var body = (Map<String, Object>) event.get("payload");
            String encoded = json.canonical(body, false);
            int size = encoded.getBytes(StandardCharsets.UTF_8).length;
            if (size > config.number("CALLBACK_INBOX_BODY_BYTES", 262144))
                throw new ApiError(413, "callback body exceeds durable receipt limit");
            String rawCall = body.get("call_id").toString(),
                    call = canonicalCallId(rawCall),
                    kind = "telephony_" + event.get("kind"),
                    digest = JsonSupport.sha(encoded);
            var payload = (Map<?, ?>) body.getOrDefault("payload", Map.of());
            Object eventId =
                    body.get("event_id") == null ? payload.get("event_id") : body.get("event_id");
            String key =
                    JsonSupport.sha(
                            json.json(
                                    Arrays.asList(
                                            kind,
                                            rawCall,
                                            eventId == null || eventId.toString().isEmpty()
                                                    ? digest
                                                    : eventId)));
            int partition =
                    (int) ((ByteBuffer.wrap(JsonSupport.digest(call)).getInt() & 0xffffffffL) % 64);
            ids.add(partition);
            entries.add(
                    JsonSupport.map(
                            "receipt_key",
                            key,
                            "partition_id",
                            partition,
                            "call_id",
                            call,
                            "kind",
                            kind,
                            "body_json",
                            encoded,
                            "body_digest",
                            digest,
                            "body_bytes",
                            size));
        }
        return tx.run(
                () -> {
                    var partitions = new HashMap<Integer, Map<String, Object>>();
                    for (var row : batchDb.rows("partitions", Map.of("ids", new ArrayList<>(ids))))
                        partitions.put(((Number) row.get("id")).intValue(), row);
                    if (partitions.size() != ids.size())
                        throw new ApiError(503, "callback partitions are not initialized");
                    var known = new HashMap<String, Map<String, Object>>();
                    for (var row :
                            batchDb.records(
                                    "receipts",
                                    "callbackinbox",
                                    Map.of(
                                            "keys",
                                            entries.stream()
                                                    .map(e -> e.get("receipt_key"))
                                                    .toList())))
                        known.put(row.get("receipt_key").toString(), row);
                    var deltas = new TreeMap<Integer, Map<String, Object>>();
                    store.bufferWrites(
                            () -> {
                                for (var entry : entries) {
                                    String key = entry.get("receipt_key").toString();
                                    var old = known.get(key);
                                    if (old != null) {
                                        if (!Crypto.equal(
                                                entry.get("body_digest").toString(),
                                                Objects.toString(old.get("body_digest"), "")))
                                            throw new ApiError(
                                                    409,
                                                    "event identity reused with different callback"
                                                            + " body");
                                        continue;
                                    }
                                    int id = ((Number) entry.get("partition_id")).intValue(),
                                            bytes = ((Number) entry.get("body_bytes")).intValue();
                                    var partition = partitions.get(id);
                                    var d =
                                            deltas.computeIfAbsent(
                                                    id,
                                                    k ->
                                                            JsonSupport.map(
                                                                    "id", k, "count", 0L, "bytes",
                                                                    0L));
                                    long count = ((Number) d.get("count")).longValue() + 1,
                                            total = ((Number) d.get("bytes")).longValue() + bytes;
                                    if (((Number) partition.get("pending_count")).longValue()
                                                            + count
                                                    > config.number(
                                                            "CALLBACK_INBOX_PARTITION_LIMIT", 256)
                                            || ((Number) partition.get("pending_bytes")).longValue()
                                                            + total
                                                    > config.number(
                                                            "CALLBACK_INBOX_PARTITION_BYTES",
                                                            4194304))
                                        throw new ApiError(
                                                503,
                                                "callback inbox partition is full; retry with the"
                                                        + " same event identity");
                                    d.put("count", count);
                                    d.put("bytes", total);
                                    known.put(key, store.insert("callbackinbox", entry));
                                }
                                return null;
                            });
                    if (!deltas.isEmpty())
                        batchDb.write(
                                "partitionDelta",
                                Map.of("deltas", new ArrayList<>(deltas.values())));
                    return JsonSupport.map(
                            "result",
                            "received",
                            "version",
                            1,
                            "accepted",
                            events.stream().map(e -> e.get("id")).toList());
                });
    }

    public boolean consume(int partitionId) {
        // Each receipt's business writes, outbox rows and completion commit together.
        final Object[] failedId = new Object[1];
        try {
            return tx.serialized(
                    () -> {
                        if (!store.lock().isEmpty()) {
                            Boolean locked = tx.tryLock(19483921, partitionId);
                            if (!Boolean.TRUE.equals(locked)) return false;
                        }
                        // Match receive/batch ordering: partition before receipt, never the
                        // reverse.
                        if (store.one(
                                        "callbackinboxpartition",
                                        "sourceCallbackInbox56_4",
                                        Store.queryParameters(-1, partitionId))
                                == null) return false;
                        Map<String, Object> receipt =
                                store.one(
                                        "callbackinbox",
                                        "sourceCallbackInbox100_7",
                                        Store.queryParameters(
                                                -1,
                                                partitionId,
                                                store.encode(
                                                        "callbackinbox",
                                                        "available_at",
                                                        Store.now())));
                        if (receipt != null)
                            receipt =
                                    store.one(
                                            "callbackinbox",
                                            "sourceCallbackInbox115_8",
                                            Store.queryParameters(-1, receipt.get("id")));
                        if (receipt == null
                                || !"pending".equals(receipt.get("state"))
                                || LocalDateTime.parse(receipt.get("available_at").toString())
                                        .isAfter(Store.now())) return false;
                        failedId[0] = receipt.get("id");
                        Map<String, Object> body = json.object(receipt.get("body_json").toString());
                        callbacks.handle(receipt.get("kind").toString(), body);
                        store.updateInternal(
                                "callbackinbox",
                                receipt.get("id"),
                                JsonSupport.map(
                                        "state",
                                        "done",
                                        "completed_at",
                                        Store.now(),
                                        "error_type",
                                        ""));
                        store.execute(
                                "callbackinboxpartition",
                                "rawCallbackInbox106_7",
                                Store.queryParameters(-1, receipt.get("body_bytes"), partitionId));
                        double latency =
                                Math.max(
                                        0,
                                        Duration.between(
                                                        LocalDateTime.parse(
                                                                receipt.get("received_at")
                                                                        .toString()),
                                                        Store.now())
                                                .toMillis());
                        store.execute(
                                "callbackinboxworker",
                                "rawCallbackInbox108_8",
                                Store.queryParameters(-1, latency, latency, workerId));
                        return true;
                    });
        } catch (RuntimeException e) {
            if (failedId[0] == null) throw e;
            tx.serialized(
                    () -> {
                        Map<String, Object> row =
                                store.one(
                                        "callbackinbox",
                                        "sourceCallbackInbox115_8",
                                        Store.queryParameters(-1, failedId[0]));
                        if (row == null || !"pending".equals(row.get("state"))) return null;
                        int attempts = CallLifecycle.number(row, "attempts") + 1;
                        boolean dead = attempts >= config.number("CALLBACK_INBOX_MAX_ATTEMPTS", 5);
                        store.updateInternal(
                                "callbackinbox",
                                row.get("id"),
                                JsonSupport.map(
                                        "state",
                                        dead ? "dead" : "pending",
                                        "attempts",
                                        attempts,
                                        "available_at",
                                        Store.now()
                                                .plusSeconds(
                                                        Math.min(60, 1L << Math.min(6, attempts))),
                                        "error_type",
                                        e.getClass().getSimpleName()));

                        return null;
                    });
            return false;
        }
    }

    public int consumeBatch(int limit) {
        return consumeBatch(limit, 0, 1);
    }

    public int consumeBatch(int limit, int owner, int owners) {
        if (owners < 1 || owners > 4 || owner < 0 || owner >= owners)
            throw new IllegalArgumentException("invalid callback ownership");
        long started = System.nanoTime();
        long[] stages = new long[4];
        boolean terminalBatch = config.flag("JAVA_CALLBACK_TERMINAL_BATCH_ENABLED", false);
        if (terminalBatch) {
            // Pick the oldest eligible stream head first, then batch only its
            // resource class. Enabling terminal batching must not move ordinary
            // dialing/answered receipts back under the global resource mutex.
            var oldest =
                    batchDb.rows(
                            "nextStatusClass",
                            JsonSupport.map("now", Store.now(), "owner", owner, "owners", owners));
            if (oldest.isEmpty()) return 0;
            terminalBatch =
                    !Values.set("dialing", "answered").contains(oldest.get(0).get("status"));
        }
        final boolean sharedResources = terminalBatch;
        Function<Supplier<Integer>, Integer> transaction = terminalBatch ? tx::serialized : tx::run;
        try {
            int consumed =
                    transaction.apply(
                            () -> {
                                stages[0] = System.nanoTime();
                                // Initial dialing/answered transitions preserve shared active
                                // counts.
                                // Partition ownership + sorted call row locks serialize each call;
                                // receivers append after its earliest pending receipt. Resource-
                                // releasing and human transitions remain on the platform-locked
                                // path.
                                var candidates =
                                        batchDb.records(
                                                "initialStatuses",
                                                "callbackinbox",
                                                JsonSupport.map(
                                                        "now",
                                                        Store.now(),
                                                        "limit",
                                                        Math.max(1, Math.min(500, limit)),
                                                        "owner",
                                                        owner,
                                                        "owners",
                                                        owners,
                                                        "terminalBatch",
                                                        sharedResources));
                                if (candidates.isEmpty()) return 0;
                                var partitions =
                                        candidates.stream()
                                                .map(
                                                        r ->
                                                                ((Number) r.get("partition_id"))
                                                                        .intValue())
                                                .distinct()
                                                .sorted()
                                                .toList();
                                for (int id : partitions) if (!tx.tryLock(19483921, id)) return 0;
                                if (batchDb.rows("partitions", Map.of("ids", partitions)).size()
                                        != partitions.size())
                                    throw new ApiError(
                                            503, "callback partitions are not initialized");
                                var receipts =
                                        batchDb
                                                .records(
                                                        "lockReceipts",
                                                        "callbackinbox",
                                                        Map.of(
                                                                "ids",
                                                                candidates.stream()
                                                                        .map(r -> r.get("id"))
                                                                        .toList()))
                                                .stream()
                                                .filter(
                                                        r ->
                                                                "pending".equals(r.get("state"))
                                                                        && !LocalDateTime.parse(
                                                                                        r.get(
                                                                                                        "available_at")
                                                                                                .toString())
                                                                                .isAfter(
                                                                                        Store
                                                                                                .now()))
                                                .toList();
                                if (receipts.isEmpty()) return 0;
                                stages[1] = System.nanoTime();
                                try (var scope = new CallStatusBatchScope()) {
                                    scope.sharedResourcesLocked = sharedResources;
                                    batchDb.prepare(scope, receipts);
                                    stages[2] = System.nanoTime();
                                    store.bufferWrites(
                                            () -> {
                                                for (var receipt : receipts)
                                                    callbacks.handle(
                                                            receipt.get("kind").toString(),
                                                            json.object(
                                                                    receipt.get("body_json")
                                                                            .toString()));
                                                return null;
                                            });
                                    taskCleanup.complete(scope);
                                    stages[3] = System.nanoTime();
                                    if (batchDb.write(
                                                    "complete",
                                                    JsonSupport.map(
                                                            "ids",
                                                            receipts.stream()
                                                                    .map(r -> r.get("id"))
                                                                    .toList(),
                                                            "now",
                                                            Store.now()))
                                            != receipts.size())
                                        throw new IllegalStateException(
                                                "callback batch ownership changed");
                                    var deltas = new TreeMap<Integer, Map<String, Object>>();
                                    double latency = 0;
                                    for (var receipt : receipts) {
                                        int id = ((Number) receipt.get("partition_id")).intValue();
                                        var d =
                                                deltas.computeIfAbsent(
                                                        id,
                                                        k ->
                                                                JsonSupport.map(
                                                                        "id", k, "count", 0L,
                                                                        "bytes", 0L));
                                        d.put("count", ((Number) d.get("count")).longValue() - 1);
                                        d.put(
                                                "bytes",
                                                ((Number) d.get("bytes")).longValue()
                                                        - ((Number) receipt.get("body_bytes"))
                                                                .longValue());
                                        latency =
                                                Math.max(
                                                        latency,
                                                        Duration.between(
                                                                        LocalDateTime.parse(
                                                                                receipt.get(
                                                                                                "received_at")
                                                                                        .toString()),
                                                                        Store.now())
                                                                .toMillis());
                                    }
                                    batchDb.write(
                                            "partitionDelta",
                                            Map.of("deltas", new ArrayList<>(deltas.values())));
                                    batchDb.write(
                                            "progress",
                                            JsonSupport.map(
                                                    "count",
                                                    receipts.size(),
                                                    "latency",
                                                    latency,
                                                    "now",
                                                    Store.now(),
                                                    "worker",
                                                    workerId));
                                    return receipts.size();
                                }
                            });
            if (consumed > 0 && config.flag("JAVA_DIAL_PROFILE", false))
                LoggerFactory.getLogger(CallbackInbox.class)
                        .info(
                                "callback_batch size={} wait_ms={} claim_ms={} facts_ms={}"
                                        + " apply_flush_ms={} finish_ms={}",
                                consumed,
                                (stages[0] - started) / 1_000_000.0,
                                (stages[1] - stages[0]) / 1_000_000.0,
                                (stages[2] - stages[1]) / 1_000_000.0,
                                (stages[3] - stages[2]) / 1_000_000.0,
                                (System.nanoTime() - stages[3]) / 1_000_000.0);
            return consumed;
        } catch (RuntimeException failure) {
            // The entire cohort rolled back, including event dedupe and tasks.
            // Existing per-receipt handling isolates/retries a malformed event.
            LoggerFactory.getLogger(CallbackInbox.class)
                    .warn(
                            "callback batch rolled back error_type={} cause_codes={}",
                            failure.getClass().getSimpleName(),
                            failureCodes(failure));
            return 0;
        }
    }

    // Keep SQL text, bound parameters and callback bodies out of diagnostics.
    static String failureCodes(Throwable failure) {
        var codes = new ArrayList<String>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure;
                cause != null && seen.add(cause) && codes.size() < 12;
                cause = cause.getCause()) {
            codes.add(
                    cause.getClass().getSimpleName()
                            + (cause instanceof SQLException sql
                                    ? ":" + sql.getSQLState() + ":" + sql.getErrorCode()
                                    : cause instanceof BatchExecutorException batch
                                            ? ":" + batch.getFailingStatementId()
                                            : ""));
        }
        return String.join("/", codes);
    }
}
