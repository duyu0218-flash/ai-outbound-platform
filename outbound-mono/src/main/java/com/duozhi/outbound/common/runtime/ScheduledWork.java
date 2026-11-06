package com.duozhi.outbound.common.runtime;

import com.duozhi.outbound.common.config.Config;
import io.micrometer.core.instrument.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Scheduler threads only trigger. Each fixed job has one bounded, non-reentrant execution lane. */
@Component
public final class ScheduledWork implements AutoCloseable {
    public enum Job {
        CONTACT_IMPORT,
        REPORT_EXPORT,
        KNOWLEDGE_IMPORT,
        WORKSPACE_MESSAGES,
        MARKETING_OUTCOME,
        DATASET,
        TASKS,
        CALLBACKS,
        JOURNEYS,
        PROFILES,
        STATISTICS,
        DOMAIN_PROBE,
        SMS_RECEIPTS,
        MESSAGES,
        MESSAGE_WEBHOOKS,
        OPERATIONS_PUSH,
        DIAL_RECOVERY,
        RECONCILIATION,
        RETENTION,
        HANDOFF,
        WORKER_SIGNALS,
        GATEWAY_CLUSTER
    }

    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private final Map<Job, Lane> lanes = new EnumMap<>(Job.class);
    private final RuntimeOwnership ownership;
    private final ScheduledExecutorService watchdog;
    private final int budgetMs;
    private volatile boolean closed;

    private static final class Lane {
        final AtomicBoolean busy = new AtomicBoolean();
        final ThreadPoolExecutor executor;
        final io.micrometer.core.instrument.Timer delay, duration;
        final Counter rejected, exceeded;
        volatile long queued;

        Lane(Job job, MeterRegistry registry) {
            String name = job.name().toLowerCase(Locale.ROOT);
            executor =
                    new ThreadPoolExecutor(
                            1,
                            1,
                            30,
                            TimeUnit.SECONDS,
                            new ArrayBlockingQueue<>(1),
                            r -> {
                                var thread = new Thread(r, "scheduled-" + name);
                                thread.setDaemon(true);
                                return thread;
                            });
            executor.allowCoreThreadTimeOut(true);
            delay = registry.timer("outbound.scheduled.delay", "job", name);
            duration = registry.timer("outbound.scheduled.duration", "job", name);
            rejected = registry.counter("outbound.scheduled.rejected", "job", name);
            exceeded = registry.counter("outbound.scheduled.budget_exceeded", "job", name);
            Gauge.builder(
                            "outbound.scheduled.age",
                            this,
                            lane -> lane.queued == 0 ? 0 : (System.nanoTime() - lane.queued) / 1e9)
                    .tag("job", name)
                    .register(registry);
        }
    }

    public ScheduledWork(Config config, MeterRegistry registry, RuntimeOwnership ownership) {
        this.ownership = ownership;
        budgetMs = config.number("JAVA_SCHEDULED_WORK_MS", 5000);
        if (budgetMs < 100 || budgetMs > 30000)
            throw new IllegalArgumentException("invalid scheduled budget");
        for (Job job : Job.values()) lanes.put(job, new Lane(job, registry));
        watchdog =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            var thread = new Thread(r, "scheduled-budget-watchdog");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    public static boolean withinBudget() {
        Long deadline = DEADLINE.get();
        return !Thread.currentThread().isInterrupted()
                && (deadline == null || System.nanoTime() < deadline);
    }

    public boolean submit(Job job, Runnable action) {
        Lane lane = lanes.get(job);
        if (closed || !lane.busy.compareAndSet(false, true)) {
            lane.rejected.increment();
            return false;
        }
        long queued = System.nanoTime();
        lane.queued = queued;
        try {
            ownership.requireRunning();
            lane.executor.execute(
                    () -> {
                        long started = System.nanoTime();
                        lane.delay.record(started - queued, TimeUnit.NANOSECONDS);
                        ScheduledFuture<?> timeout = null;
                        Object interruptFence = new Object();
                        var armed = new AtomicBoolean(true);
                        try {
                            ownership.requireRunning();
                            DEADLINE.set(started + TimeUnit.MILLISECONDS.toNanos(budgetMs));
                            Thread worker = Thread.currentThread();
                            timeout =
                                    watchdog.schedule(
                                            () -> {
                                                synchronized (interruptFence) {
                                                    if (armed.get()) {
                                                        lane.exceeded.increment();
                                                        worker.interrupt();
                                                    }
                                                }
                                            },
                                            budgetMs,
                                            TimeUnit.MILLISECONDS);
                            action.run();
                        } catch (RuntimeException failed) {
                            LoggerFactory.getLogger(ScheduledWork.class)
                                    .warn(
                                            "scheduled job={} error_type={}",
                                            job,
                                            failed.getClass().getSimpleName());
                        } finally {
                            synchronized (interruptFence) {
                                armed.set(false);
                            }
                            if (timeout != null) timeout.cancel(false);
                            DEADLINE.remove();
                            Thread.interrupted();
                            lane.duration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                            lane.queued = 0;
                            lane.busy.set(false);
                        }
                    });
            return true;
        } catch (RuntimeException rejected) {
            lane.queued = 0;
            lane.busy.set(false);
            lane.rejected.increment();
            return false;
        }
    }

    @PreDestroy
    public void close() {
        closed = true;
        lanes.values().forEach(lane -> lane.executor.shutdownNow());
        watchdog.shutdownNow();
    }
}
