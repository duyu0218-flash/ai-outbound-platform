package com.duozhi.outbound.job.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.service.ExpiringValues;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.job.worker.TaskWorker;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Publishes only observed Java AI-lane progress; a stalled or stopped lane expires. */
@Service
public final class JavaWorkerSignals {
    private final TaskWorker worker;
    private final Config config;
    private final ExpiringValues redis;
    private final JsonSupport json;
    private final String id = "ai:java-" + UUID.randomUUID();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "java-worker-signal");
                        t.setDaemon(true);
                        return t;
                    });

    JavaWorkerSignals(TaskWorker worker, Config config, ExpiringValues redis, JsonSupport json) {
        this.worker = worker;
        this.config = config;
        this.redis = redis;
        this.json = json;
    }

    void publish() {
        if (!config.flag("STABILITY_ADMISSION_ENABLED", false)) return;
        Map<String, Object> signal = worker.aiSignal();
        if (signal == null) return;
        String key = config.get("STABILITY_REDIS_KEY", "ai-outbound:stability") + ":signals";
        redis.shared("HSET", key, id, json.json(signal));
        redis.shared("EXPIRE", key, "60");
    }

    @Scheduled(fixedDelayString = "${JAVA_WORKER_SIGNAL_POLL_MS:1000}")
    void tick() {
        if (!config.flag("JAVA_WORKERS_ENABLED", false)
                || !config.flag("STABILITY_ADMISSION_ENABLED", false)
                || !busy.compareAndSet(false, true)) return;
        try {
            executor.submit(
                    () -> {
                        try {
                            publish();
                        } catch (RuntimeException unavailable) {
                            LoggerFactory.getLogger(JavaWorkerSignals.class)
                                    .warn("worker telemetry unavailable");
                        } finally {
                            busy.set(false);
                        }
                    });
        } catch (RejectedExecutionException stopping) {
            busy.set(false);
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
