package com.duozhi.outbound.marketing.dataset;

import java.util.ArrayDeque;

/** One-process admission budget for a paid partner API (port of ant-platform query_limits.py). */
final class QueryLimiter {
    private final int qps;
    private final ArrayDeque<Long> starts = new ArrayDeque<>();
    private long nextStart, blockedUntil;

    QueryLimiter(int qps) {
        this.qps = qps;
    }

    int qps() {
        return qps;
    }

    void acquire() {
        if (qps <= 0) return;
        while (true) {
            long delay;
            synchronized (this) {
                long now = System.nanoTime();
                while (!starts.isEmpty() && now - starts.peekFirst() >= 1_000_000_000L)
                    starts.pollFirst();
                long deadline = Math.max(nextStart, blockedUntil);
                if (starts.size() >= qps)
                    deadline = Math.max(deadline, starts.peekFirst() + 1_000_000_000L);
                delay = deadline - now;
                if (delay <= 0) {
                    starts.addLast(now);
                    nextStart = now + 1_000_000_000L / qps;
                    return;
                }
            }
            try {
                Thread.sleep(delay / 1_000_000L, (int) (delay % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while throttled");
            }
        }
    }

    synchronized void defer(long seconds) {
        blockedUntil = Math.max(blockedUntil, System.nanoTime() + seconds * 1_000_000_000L);
    }
}
