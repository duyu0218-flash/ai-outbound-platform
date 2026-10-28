package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.common.config.Config;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MessageWorker {
    private final Messaging messaging;
    private final Config config;
    private final ExecutorService work =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("message-worker").factory());
    private final AtomicBoolean busy = new AtomicBoolean();

    public MessageWorker(Messaging messaging, Config config) {
        this.messaging = messaging;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${MESSAGING_POLL_MS:250}")
    public void tick() {
        if (!config.flag("MESSAGING_WORKERS_ENABLED", false)
                || !config.flag("MESSAGING_ENABLED", false)
                || config.production()
                || !busy.compareAndSet(false, true)) return;
        try {
            work.submit(
                    () -> {
                        try {
                            messaging.recoverUnknown();
                            messaging.processCallIntent();
                            messaging.processBatch();
                            for (int i = 0;
                                    i < 32
                                            && !Thread.currentThread().isInterrupted()
                                            && messaging.processOne();
                                    i++) {}
                        } catch (RuntimeException e) {
                            org.slf4j.LoggerFactory.getLogger(MessageWorker.class)
                                    .warn(
                                            "message worker failed: {}",
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
