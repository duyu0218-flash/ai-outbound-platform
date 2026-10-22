package com.duozhi.outbound.system.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.job.service.Outbox;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Bounded cleanup of expired text and confirmed recording deletion requests. */
@Service
public class DataRetention {
    private final Store store;
    private final Transactions tx;
    private final Config config;
    private final Settings settings;
    private final Outbox outbox;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "retention");
                        t.setDaemon(true);
                        return t;
                    });
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean ready;

    public DataRetention(
            Store store, Transactions tx, Config config, Settings settings, Outbox outbox) {
        this.store = store;
        this.tx = tx;
        this.config = config;
        this.settings = settings;
        this.outbox = outbox;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        ready = true;
    }

    @PreDestroy
    public void close() {
        ready = false;
        executor.shutdownNow();
    }

    private int days(Map<String, Object> policy, String name, int fallback) {
        return Math.max(1, Math.min(3650, JsonSupport.integer(policy.get(name), fallback)));
    }

    private String redacted(long tenant, Object id, Object phone) {
        String secret = config.get("SECRET_KEY", config.get("JWT_SECRET", ""));
        if (secret.isEmpty()) throw new ApiError(503, "retention redaction secret is missing");
        return "redacted:"
                + JsonSupport.hmac(
                                secret,
                                (tenant + ":" + id + ":" + phone).getBytes(StandardCharsets.UTF_8))
                        .substring(0, 24);
    }

    private void increment(Map<String, Integer> counts, String key) {
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    public Map<String, Integer> purge(int limit) {
        if (limit < 1 || limit > 1000)
            throw new IllegalArgumentException("invalid retention batch size");
        return tx.run(
                () -> {
                    tx.identity("java-retention");
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    LocalDateTime now = Store.now();
                    for (Map<String, Object> tenant :
                            store.query("tenant", "allForMaintenance", Map.of())) {
                        long tid = ((Number) tenant.get("id")).longValue();
                        Map<String, Object> policy = settings.get(tid, "compliance");
                        LocalDateTime
                                sensitive =
                                        now.minusDays(
                                                days(
                                                        policy,
                                                        "call_sensitive_data_retention_days",
                                                        180)),
                                text =
                                        now.minusDays(
                                                days(
                                                        policy,
                                                        "final_transcript_retention_days",
                                                        30));
                        for (boolean last : new boolean[] {false, true}) {
                            String metric = last ? "final_transcripts" : "partial_transcripts";
                            int remaining = limit - counts.getOrDefault(metric, 0);
                            if (remaining <= 0) continue;
                            LocalDateTime cutoff =
                                    last
                                            ? text
                                            : now.minusHours(
                                                    Math.max(
                                                            1,
                                                            Math.min(
                                                                    720,
                                                                    JsonSupport.integer(
                                                                            policy.get(
                                                                                    "partial_transcript_retention_hours"),
                                                                            24))));
                            for (Map<String, Object> row :
                                    store.query(
                                            "speechturn",
                                            "expiredTranscripts",
                                            Values.map(
                                                    "tenantId",
                                                    tid,
                                                    "final",
                                                    last,
                                                    "cutoff",
                                                    cutoff,
                                                    "limit",
                                                    remaining))) {
                                store.delete("speechturn", tid, row.get("id"));
                                increment(counts, metric);
                            }
                        }
                        int remaining = limit - counts.getOrDefault("redacted_calls", 0);
                        if (remaining > 0)
                            for (Map<String, Object> call :
                                    store.query(
                                            "callsession",
                                            "expiredSensitiveCalls",
                                            Values.map(
                                                    "tenantId",
                                                    tid,
                                                    "cutoff",
                                                    sensitive,
                                                    "limit",
                                                    remaining))) {
                                Object id = store.encode("callsession", "id", call.get("id"));
                                String phone = Objects.toString(call.get("phone"), "");
                                if (!phone.startsWith("redacted:"))
                                    phone = redacted(tid, call.get("id"), phone);
                                store.update(
                                        "callsession",
                                        tid,
                                        call.get("id"),
                                        JsonSupport.map(
                                                "phone",
                                                phone,
                                                "last_transcript",
                                                null,
                                                "summary",
                                                null,
                                                "recording_url",
                                                null,
                                                "last_error",
                                                null));
                                store.execute(
                                        "conversationstate",
                                        "redactCallEvidence",
                                        Values.map("tenantId", tid, "callId", id, "phone", phone));
                                store.execute(
                                        "productworkitem",
                                        "redactCallEvidence",
                                        Values.map("tenantId", tid, "callId", id, "phone", phone));
                                store.execute(
                                        "callevent",
                                        "redactCallEvidence",
                                        Values.map("tenantId", tid, "callId", id, "phone", phone));
                                store.execute(
                                        "callanalysis",
                                        "redactCallEvidence",
                                        Values.map("tenantId", tid, "callId", id, "phone", phone));
                                store.execute(
                                        "callmetric",
                                        "redactCallEvidence",
                                        Values.map("tenantId", tid, "callId", id, "phone", phone));
                                store.execute(
                                        "recordingasset",
                                        "redactCallEvidence",
                                        Values.map("tenantId", tid, "callId", id, "phone", phone));
                                increment(counts, "redacted_calls");
                            }
                        remaining = limit - counts.getOrDefault("orphan_work_items", 0);
                        if (remaining > 0)
                            for (Map<String, Object> item :
                                    store.query(
                                            "productworkitem",
                                            "expiredOrphans",
                                            Values.map(
                                                    "tenantId",
                                                    tid,
                                                    "cutoff",
                                                    sensitive,
                                                    "limit",
                                                    remaining))) {
                                store.update(
                                        "productworkitem",
                                        tid,
                                        item.get("id"),
                                        JsonSupport.map(
                                                "phone",
                                                redacted(tid, item.get("id"), item.get("phone")),
                                                "detail_json",
                                                "{}"));
                                increment(counts, "orphan_work_items");
                            }
                        LocalDateTime cutoff = text.isBefore(sensitive) ? sensitive : text;
                        remaining = limit - counts.getOrDefault("task_payloads", 0);
                        if (remaining > 0)
                            for (Map<String, Object> task :
                                    store.query(
                                            "taskoutbox",
                                            "expiredPayloads",
                                            Values.map(
                                                    "tenantId",
                                                    tid,
                                                    "cutoff",
                                                    cutoff,
                                                    "limit",
                                                    remaining))) {
                                Map<String, Object> change =
                                        JsonSupport.map("payload_json", "{}", "last_error", "");
                                if (Values.set("pending", "failed").contains(task.get("state")))
                                    change.put("state", "dead");
                                store.update("taskoutbox", tid, task.get("id"), change);
                                increment(counts, "task_payloads");
                            }
                        remaining = limit - counts.getOrDefault("sms_payloads", 0);
                        if (remaining > 0)
                            for (Map<String, Object> sms :
                                    store.query(
                                            "smslog",
                                            "expiredPayloads",
                                            Values.map(
                                                    "tenantId",
                                                    tid,
                                                    "cutoff",
                                                    sensitive,
                                                    "limit",
                                                    remaining))) {
                                store.update(
                                        "smslog",
                                        tid,
                                        sms.get("id"),
                                        JsonSupport.map(
                                                "to_phone",
                                                "redacted",
                                                "content",
                                                "",
                                                "provider_error",
                                                null));
                                increment(counts, "sms_payloads");
                            }
                    }
                    for (Map<String, Object> asset :
                            globalCandidates(
                                    "recordingasset",
                                    "expiredRecordings",
                                    now,
                                    limit,
                                    "retention_until",
                                    "id")) {
                        long tenant = ((Number) asset.get("tenant_id")).longValue();
                        Map<String, Object> task =
                                outbox.enqueue(
                                        tenant,
                                        "recording_delete",
                                        asset.get("id"),
                                        "recording-delete:" + asset.get("id"),
                                        JsonSupport.map("recording_asset_id", asset.get("id")));
                        store.update(
                                "recordingasset",
                                tenant,
                                asset.get("id"),
                                JsonSupport.map(
                                        "state",
                                        "dead".equals(task.get("state"))
                                                ? "deletion_failed"
                                                : "deletion_pending"));
                        increment(counts, "recording_deletion_tasks");
                    }
                    return counts;
                });
    }

    public int archive(int limit) {
        if (limit < 1 || limit > 1000)
            throw new IllegalArgumentException("invalid archive batch size");
        Object cutoff = store.encode("callsession", "finished_at", Store.now().minusDays(180));
        List<Map<String, Object>> candidates =
                globalCandidates(
                        "taskoutbox", "archivableTasks", cutoff, limit, "updated_at", null);
        int archived = 0;
        for (Map<String, Object> task : candidates) {
            Boolean removed =
                    tx.run(
                            () -> {
                                tx.identity(task.get("idempotency_key").toString());
                                Map<String, Object> latest =
                                        store.one(
                                                "taskoutbox",
                                                "lockById",
                                                Values.map(
                                                        "tenantId",
                                                        task.get("tenant_id"),
                                                        "id",
                                                        store.encode(
                                                                "taskoutbox",
                                                                "id",
                                                                task.get("id"))));
                                if (latest == null
                                        || !"completed".equals(latest.get("state"))
                                        || LocalDateTime.parse(latest.get("updated_at").toString())
                                                .isAfter(Store.now().minusDays(180))) return false;
                                if (store.one(
                                                "taskreceipt",
                                                "findById",
                                                Values.map(
                                                        "tenantId",
                                                        task.get("tenant_id"),
                                                        "id",
                                                        store.encode(
                                                                "taskreceipt",
                                                                "id",
                                                                task.get("id"))))
                                        == null)
                                    store.insert(
                                            "taskreceipt",
                                            JsonSupport.map(
                                                    "id",
                                                    latest.get("id"),
                                                    "tenant_id",
                                                    latest.get("tenant_id"),
                                                    "task_type",
                                                    latest.get("task_type"),
                                                    "aggregate_id",
                                                    latest.get("aggregate_id"),
                                                    "idempotency_key",
                                                    latest.get("idempotency_key"),
                                                    "completed_at",
                                                    latest.get("updated_at")));
                                store.delete(
                                        "taskoutbox",
                                        ((Number) latest.get("tenant_id")).longValue(),
                                        latest.get("id"));
                                return true;
                            });
            if (removed) archived++;
        }
        for (String table : Arrays.asList("callevent", "callmetric"))
            for (Map<String, Object> row :
                    globalCandidates(
                            table, "archivableEvidence", cutoff, limit, "created_at", null)) {
                store.execute(
                        table,
                        "deleteArchivedEvidence",
                        Values.map("tenantId", row.get("_tenant_id"), "id", row.get("id")));
            }
        return archived;
    }

    private List<Map<String, Object>> globalCandidates(
            String table, String method, Object cutoff, int limit, String order, String tie) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> tenant : store.query("tenant", "allForMaintenance", Map.of())) {
            Object tid = tenant.get("id");
            for (Map<String, Object> row :
                    store.query(
                            table,
                            method,
                            Values.map("tenantId", tid, "cutoff", cutoff, "limit", limit))) {
                row.put("_tenant_id", tid);
                result.add(row);
            }
        }
        Comparator<Map<String, Object>> comparator =
                Comparator.comparing(row -> LocalDateTime.parse(row.get(order).toString()));
        if (tie != null)
            comparator = comparator.thenComparing(row -> Objects.toString(row.get(tie), ""));
        result.sort(comparator);
        return result.subList(0, Math.min(limit, result.size()));
    }

    @Scheduled(fixedDelayString = "${JAVA_RETENTION_POLL_MS:60000}")
    public void tick() {
        if (!ready
                || !config.flag("JAVA_WORKERS_ENABLED", false)
                || !running.compareAndSet(false, true)) return;
        executor.submit(
                () -> {
                    try {
                        int limit =
                                Math.max(
                                        1,
                                        Math.min(
                                                1000, config.number("RETENTION_BATCH_SIZE", 1000)));
                        purge(limit);
                        archive(limit);
                    } catch (RuntimeException e) {
                        LoggerFactory.getLogger(DataRetention.class)
                                .warn("retention error_type={}", e.getClass().getSimpleName());
                    } finally {
                        running.set(false);
                    }
                });
    }
}
