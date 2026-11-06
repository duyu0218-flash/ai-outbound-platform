package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.runtime.ScheduledWork;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MessageWorker {
    @org.springframework.beans.factory.annotation.Autowired
    private ScheduledWork scheduledWork;

    @org.springframework.beans.factory.annotation.Autowired
    private MessageDelivery delivery;
    private final Messaging messaging;
    private final Config config;
    private final ExecutorService sends, queries, preparation, recovery;

    private record Slot(MessagePartition partition, int owner) {}

    private final Set<Slot> sending = ConcurrentHashMap.newKeySet();
    private final Set<Slot> querying = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean preparing = new AtomicBoolean(), recovering = new AtomicBoolean();
    private final int sendWorkers, queryWorkers, channelConcurrency, budgetMs;
    private MessagePartition sendCursor = MessagePartition.START;
    private MessagePartition queryCursor = MessagePartition.START;
    private volatile boolean closed;

    public MessageWorker(Messaging messaging, Config config) {
        this.messaging = messaging;
        this.config = config;
        sendWorkers = Math.max(1, Math.min(16, config.number("MESSAGING_SEND_WORKERS", 4)));
        queryWorkers = Math.max(1, Math.min(8, config.number("MESSAGING_QUERY_WORKERS", 2)));
        channelConcurrency =
                Math.max(
                        1,
                        Math.min(sendWorkers, config.number("MESSAGING_CHANNEL_CONCURRENCY", 2)));
        budgetMs = Math.max(100, Math.min(30000, config.number("MESSAGING_WORK_BUDGET_MS", 5000)));
        sends = pool(sendWorkers, "message-send");
        queries = pool(queryWorkers, "message-query");
        preparation = pool(1, "message-prepare");
        recovery = pool(1, "message-recovery");
    }

    private static ExecutorService pool(int size, String name) {
        return new ThreadPoolExecutor(
                size,
                size,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(size),
                Thread.ofPlatform().daemon().name(name).factory());
    }

    @Scheduled(fixedDelayString = "${MESSAGING_POLL_MS:250}")
    public void scheduledTick() {
        scheduledWork.submit(ScheduledWork.Job.MESSAGES, this::tick);
    }

    public synchronized void tick() {
        if (closed
                || !config.flag("MESSAGING_WORKERS_ENABLED", false)
                || !config.flag("MESSAGING_ENABLED", false)) return;
        single(
                recovery,
                recovering,
                () -> {
                    messaging.recoverUnknown();
                    delivery.reapConnections();
                });
        single(
                preparation,
                preparing,
                () -> {
                    long deadline = deadline();
                    messaging.processCallIntent();
                    if (within(deadline)) messaging.processBatch();
                });
        sendCursor =
                dispatch(
                        sends,
                        sending,
                        sendWorkers,
                        channelConcurrency,
                        sendCursor,
                        messaging::readyPartitions,
                        messaging::processOne);
        queryCursor =
                dispatch(
                        queries,
                        querying,
                        queryWorkers,
                        1,
                        queryCursor,
                        delivery::queryPartitions,
                        delivery::queryOne);
    }

    private MessagePartition dispatch(
            ExecutorService executor,
            Set<Slot> active,
            int capacity,
            int perPartition,
            MessagePartition cursor,
            BiFunction<MessagePartition, Integer, List<MessagePartition>> candidates,
            Predicate<MessagePartition> process) {
        if (active.size() >= capacity) return cursor;
        var partitions = candidates.apply(cursor, 64);
        if (partitions.isEmpty()) return MessagePartition.START;
        int free = capacity - active.size();
        // Give each ready partition one owner before granting a second owner to any channel.
        // Both limits remain bounded; channel TPS and day limits are still reserved in the DB.
        for (int owner = 0; owner < perPartition && free > 0; owner++) {
            for (var partition : partitions) {
                if (free == 0) break;
                if (owner == 0) cursor = partition;
                var slot = new Slot(partition, owner);
                if (!active.add(slot)) continue;
                free--;
                try {
                    executor.execute(
                            () -> {
                                try {
                                    // The deadline belongs to this worker, not the scheduler's
                                    // ThreadLocal.
                                    // Finish an already-submitted bounded protocol request; do not
                                    // claim more
                                    // work after the slice expires. UNKNOWN is never put back in
                                    // the send queue.
                                    long deadline = deadline();
                                    for (int i = 0;
                                            i < 32 && within(deadline) && process.test(partition);
                                            i++) {}
                                } catch (RuntimeException failed) {
                                    failure(failed);
                                } finally {
                                    active.remove(slot);
                                }
                            });
                } catch (RejectedExecutionException rejected) {
                    active.remove(slot);
                }
            }
        }
        return cursor;
    }

    private long deadline() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
    }

    private boolean within(long deadline) {
        return !closed && !Thread.currentThread().isInterrupted() && System.nanoTime() < deadline;
    }

    private void single(ExecutorService executor, AtomicBoolean busy, Runnable action) {
        if (!busy.compareAndSet(false, true)) return;
        try {
            executor.execute(
                    () -> {
                        try {
                            if (!closed) action.run();
                        } catch (RuntimeException e) {
                            failure(e);
                        } finally {
                            busy.set(false);
                        }
                    });
        } catch (RejectedExecutionException e) {
            busy.set(false);
        }
    }

    private static void failure(RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger(MessageWorker.class)
                .warn("message worker failed: {}", error.getClass().getSimpleName());
    }

    @PreDestroy
    public void close() {
        closed = true;
        sends.shutdownNow();
        queries.shutdownNow();
        preparation.shutdownNow();
        recovery.shutdownNow();
    }
}
