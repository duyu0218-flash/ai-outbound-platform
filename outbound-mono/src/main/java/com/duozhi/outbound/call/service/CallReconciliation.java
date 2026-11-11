package com.duozhi.outbound.call.service;

import com.duozhi.outbound.callback.service.CallbackInbox;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.runtime.ScheduledWork;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.integration.service.TelephonyClient;
import com.duozhi.outbound.job.worker.TaskWorker;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * A database timeout alone is never evidence that the PBX released a call. Reconciliation is a
 * read-only provider probe: queue/admission age must never become permission to hang up a live
 * channel. Explicit business hangups continue through the normal fenced control path.
 */
@Service
public class CallReconciliation {
    @org.springframework.beans.factory.annotation.Autowired
    private ScheduledWork scheduledWork;

    private final Store store;
    private final Transactions tx;
    private final Config config;
    private final TelephonyClient client;
    private final CallLifecycle lifecycle;
    @org.springframework.beans.factory.annotation.Autowired
    private CallbackInbox inbox;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "call-reconciliation");
                        t.setDaemon(true);
                        return t;
                    });
    private volatile boolean ready;

    public CallReconciliation(
            Store store,
            Transactions tx,
            Config config,
            TelephonyClient client,
            CallLifecycle lifecycle) {
        this.store = store;
        this.tx = tx;
        this.config = config;
        this.client = client;
        this.lifecycle = lifecycle;
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

    public int reconcile(int limit) {
        if (limit < 1 || limit > 200)
            throw new IllegalArgumentException("invalid reconciliation batch size");
        List<Map<String, Object>> stale =
                store.orderedAcross(
                        "callsession",
                        "scopedCallReconciliation30",
                        Store.queryParameters(
                                -1,
                                store.encode(
                                        "callsession",
                                        "updated_at",
                                        Store.now()
                                                .minusSeconds(
                                                        Math.max(
                                                                10,
                                                                config.number(
                                                                        "DEFAULT_CALL_TIMEOUT_SEC",
                                                                        120)))),
                                limit),
                        List.of("updated_at:ASC", "id:ASC"),
                        limit);
        int replayed = 0;
        for (Map<String, Object> call : stale) {
            Map<String, Object> confirmed;
            try {
                TaskWorker.permitted();
                confirmed = terminalEvent(call, client.command(call, "status", Map.of()));
            } catch (RuntimeException failure) {
                TaskWorker.permitted();
                confirmed = null;
            }
            final Map<String, Object> terminal = confirmed;
            boolean changed =
                    tx.serialized(
                            () -> {
                                tx.platform();
                                TaskWorker.permitted();
                                Map<String, Object> latest = tx.call(call.get("id"));
                                if (latest == null
                                        || CallLifecycle.number(latest, "attempts")
                                                != CallLifecycle.number(call, "attempts")
                                        || !Objects.equals(
                                                latest.get("telephony_call_id"),
                                                call.get("telephony_call_id"))
                                        || CallLifecycle.TERMINAL.contains(latest.get("status")))
                                    return false;
                                if (terminal == null) {
                                    // Do not overwrite newer activity with an older failed probe.
                                    if (Objects.equals(
                                            latest.get("updated_at"), call.get("updated_at")))
                                        store.update(
                                                "callsession",
                                                CallLifecycle.tenant(call),
                                                call.get("id"),
                                                JsonSupport.map(
                                                        "last_error",
                                                        "provider status unknown; capacity"
                                                                + " retained"));
                                    return false;
                                }
                                if (config.flag("CALLBACK_INBOX_ENABLED", false))
                                    inbox.receive("telephony_status", terminal);
                                else lifecycle.status(terminal);
                                return true;
                            });
            if (changed) replayed++;
        }
        return replayed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> terminalEvent(Map<String, Object> call, Map<String, Object> reply) {
        if (!Boolean.TRUE.equals(reply.get("ended"))
                || !(reply.get("terminal_event") instanceof Map<?, ?> event)
                || !(event.get("payload") instanceof Map<?, ?> raw)) return null;
        var payload = (Map<String, Object>) raw;
        Object provider =
                payload.getOrDefault("provider_call_id", payload.get("telephony_call_id"));
        if (!"status".equals(event.get("kind"))
                || !Objects.toString(event.get("call_id"), "")
                        .equals(Objects.toString(call.get("id"), ""))
                || CallLifecycle.number(payload, "tenant_id") != CallLifecycle.tenant(call)
                || CallLifecycle.number(payload, "attempt")
                        != CallLifecycle.number(call, "attempts")
                || call.get("telephony_call_id") == null
                || !Objects.equals(provider, call.get("telephony_call_id"))
                || !Objects.equals(provider, reply.get("provider_call_id"))
                || Objects.toString(payload.get("event_id"), "").isBlank()
                || !Set.of(
                                "ended",
                                "completed",
                                "failed",
                                "busy",
                                "no_answer",
                                "voicemail",
                                "canceled")
                        .contains(payload.get("status"))) return null;
        return (Map<String, Object>) event;
    }

    @Scheduled(fixedDelayString = "${JAVA_RECONCILE_POLL_MS:5000}")
    public void scheduledTick() {
        scheduledWork.submit(ScheduledWork.Job.RECONCILIATION, this::tick);
    }

    public void tick() {
        if (!ready
                || !config.flag("JAVA_WORKERS_ENABLED", false)
                || !running.compareAndSet(false, true)) return;
        executor.submit(
                () -> {
                    try {
                        reconcile(20);
                    } catch (RuntimeException e) {
                        LoggerFactory.getLogger(CallReconciliation.class)
                                .warn(
                                        "call reconciliation error_type={}",
                                        e.getClass().getSimpleName());
                    } finally {
                        running.set(false);
                    }
                });
    }
}
