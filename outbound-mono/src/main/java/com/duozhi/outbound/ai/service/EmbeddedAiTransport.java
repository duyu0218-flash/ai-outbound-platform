package com.duozhi.outbound.ai.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.integration.config.UnifiedServiceSettings;
import com.duozhi.outbound.integration.service.TelephonyClient;
import com.duozhi.outbound.integration.support.Support;
import java.util.*;
import java.util.concurrent.*;

/** Retains the former agent HTTP deadline while executing the same agent inside this JVM. */
@org.springframework.stereotype.Service
public class EmbeddedAiTransport {
    private final EmbeddedAgent agent;
    private final ThreadPoolExecutor executor;

    public EmbeddedAiTransport(EmbeddedAgent agent, Config config) {
        this.agent = agent;
        int maximum =
                (int)
                        new UnifiedServiceSettings(config, "agent")
                                .number("LLM_MAX_CONNECTIONS", 256, 1, 1024);
        executor =
                new ThreadPoolExecutor(
                        0,
                        maximum,
                        60,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        task -> {
                            Thread thread = new Thread(task, "mono-ai-agent");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    public Map<String, Object> turn(Map<String, Object> payload, String token, int timeout) {
        if (timeout < 0) throw new IllegalArgumentException("timeout can not be negative");
        Future<Map<String, Object>> future;
        try {
            future = executor.submit(() -> agent.turn(payload, token));
        } catch (RejectedExecutionException busy) {
            throw new TelephonyClient.Failure(503, false);
        }
        try {
            return timeout == 0 ? future.get() : future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutFailure) {
            future.cancel(true);
            throw new TelephonyClient.Failure(0, false);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new TelephonyClient.Failure(0, false);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Support.Failure failure)
                throw new TelephonyClient.Failure(failure.status, false);
            throw new TelephonyClient.Failure(
                    cause instanceof IllegalArgumentException ? 422 : 500, false);
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
