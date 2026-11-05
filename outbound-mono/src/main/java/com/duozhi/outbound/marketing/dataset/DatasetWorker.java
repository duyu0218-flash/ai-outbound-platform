package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.runtime.ScheduledWork;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background driver for imports, matching and bulk sends. Off unless MARKETING_WORKERS_ENABLED;
 * tests and the page buttons call {@link #step()} or the per-object single steps directly.
 */
@Component
public class DatasetWorker {
    @org.springframework.beans.factory.annotation.Autowired
    private ScheduledWork scheduledWork;

    private final DatasetImports imports;
    private final Matching matching;
    private final SendTasks sends;
    private final Config config;
    private final ExecutorService work =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("marketing-dataset-worker").factory());
    private final AtomicBoolean busy = new AtomicBoolean();

    public DatasetWorker(
            DatasetImports imports, Matching matching, SendTasks sends, Config config) {
        this.imports = imports;
        this.matching = matching;
        this.sends = sends;
        this.config = config;
    }

    /** One unit of each kind of work; true when anything was done. */
    public boolean step() {
        boolean worked = imports.processOne(null);
        worked |= matching.step(null);
        worked |= sends.step(null);
        return worked;
    }

    @Scheduled(fixedDelayString = "${MARKETING_POLL_MS:1000}")
    public void scheduledTick() {
        scheduledWork.submit(ScheduledWork.Job.DATASET, this::tick);
    }

    public void tick() {
        if (!config.flag("MARKETING_WORKERS_ENABLED", false) || !busy.compareAndSet(false, true))
            return;
        try {
            work.submit(
                    () -> {
                        try {
                            for (int i = 0;
                                    i < 20 && !Thread.currentThread().isInterrupted() && step();
                                    i++) {}
                        } catch (RuntimeException e) {
                            LoggerFactory.getLogger(DatasetWorker.class)
                                    .warn(
                                            "marketing dataset worker failed: {}",
                                            e.getClass().getSimpleName());
                        } finally {
                            busy.set(false);
                        }
                    });
        } catch (RejectedExecutionException e) {
            busy.set(false);
        }
    }

    @PreDestroy
    public void close() {
        work.shutdownNow();
    }
}
