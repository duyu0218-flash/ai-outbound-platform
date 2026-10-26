package com.duozhi.outbound.voiceai.service;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

/** One response owns its transports. Cancellation also closes a body blocked in read(). */
public final class ResponseScope implements AutoCloseable {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private volatile boolean cancelled;

    public synchronized <T extends AutoCloseable> T own(T resource) throws Exception {
        if (cancelled) {
            resource.close();
            check();
        }
        resources.add(resource);
        return resource;
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
