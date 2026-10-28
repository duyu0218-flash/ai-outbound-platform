package com.duozhi.outbound.voiceai.service;

import java.net.SocketTimeoutException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded timer per response; heartbeats do not count as useful text. */
final class TextStreamDeadline implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor TIMER = timer();

    private static ScheduledThreadPoolExecutor timer() {
        var timer =
                new ScheduledThreadPoolExecutor(
                        1,
                        task -> {
                            var thread = new Thread(task, "voice-text-deadlines");
                            thread.setDaemon(true);
                            return thread;
                        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    private final ResponseScope scope;
    private final long started = System.nanoTime();
    private final long first, idle, total;
    private volatile long lastContent;
    private volatile String expired;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledFuture<?> task;

    TextStreamDeadline(ResponseScope scope, long firstMs, long idleMs, long totalMs) {
        this.scope = scope;
        first = TimeUnit.MILLISECONDS.toNanos(firstMs);
        idle = TimeUnit.MILLISECONDS.toNanos(idleMs);
        total = TimeUnit.MILLISECONDS.toNanos(totalMs);
        task = TIMER.scheduleWithFixedDelay(this::expire, 10, 10, TimeUnit.MILLISECONDS);
        if (closed.get()) task.cancel(false);
    }

    void content() throws SocketTimeoutException {
        check();
        lastContent = System.nanoTime();
    }

    private void expire() {
        if (closed.get()) return;
        long now = System.nanoTime(), content = lastContent;
        String reason =
                now - started >= total
                        ? "total"
                        : content == 0 && now - started >= first
                                ? "first_content"
                                : content != 0 && now - content >= idle ? "content_idle" : null;
        if (reason == null || !closed.compareAndSet(false, true)) return;
        expired = reason;
        if (task != null) task.cancel(false);
        // A blocked close must not stop all other sessions' deadlines.
        Thread.ofVirtual().name("voice-text-cancel").start(scope::close);
    }

    void check() throws SocketTimeoutException {
        if (expired != null) throw new SocketTimeoutException("text stream deadline: " + expired);
    }

    public void close() {
        closed.set(true);
        task.cancel(false);
    }
}
