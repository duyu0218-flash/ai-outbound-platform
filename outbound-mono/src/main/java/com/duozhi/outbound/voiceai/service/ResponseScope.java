package com.duozhi.outbound.voiceai.service;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

/** One response owns its transports. Cancellation also closes a body blocked in read(). */
public final class ResponseScope implements AutoCloseable {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private volatile boolean cancelled;

    public <T extends AutoCloseable> T own(T resource) throws Exception {
        synchronized (this) {
            if (!cancelled) {
                resources.add(resource);
                return resource;
            }
        }
        // Closing a stream may block. Never hold the scope monitor during transport cleanup.
        resource.close();
        check();
        throw new InterruptedIOException("voice response cancelled");
    }

    public void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("voice response cancelled");
    }

    public boolean cancelled() {
        return cancelled;
    }

    @Override
    public void close() {
        List<AutoCloseable> closing;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            closing = List.copyOf(resources);
            resources.clear();
        }
        for (var resource : closing) {
            try {
                resource.close();
            } catch (Exception ignored) {
                /* Fence is already closed. */
            }
        }
    }
}
