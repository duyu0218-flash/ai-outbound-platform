package com.duozhi.outbound.integration.support;

import java.util.List;
import java.util.concurrent.*;

/**
 * Blocking orchestration gets virtual threads, with explicit admission instead of an unbounded
 * queue.
 */
public final class BoundedVirtualExecutor extends AbstractExecutorService {
    private final ExecutorService delegate;
    private final Semaphore capacity;

    public BoundedVirtualExecutor(String name, int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("invalid admission capacity");
        capacity = new Semaphore(maximum);
        delegate = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(name, 0).factory());
    }

    public void execute(Runnable command) {
        if (!capacity.tryAcquire())
            throw new RejectedExecutionException("orchestration admission exhausted");
        try {
            delegate.execute(
                    () -> {
                        try {
                            command.run();
                        } finally {
                            capacity.release();
                        }
                    });
        } catch (RuntimeException failure) {
            capacity.release();
            throw failure;
        }
    }

    public void shutdown() {
        delegate.shutdown();
    }

    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
