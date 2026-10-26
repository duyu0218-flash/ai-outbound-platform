package com.duozhi.outbound.voiceai.service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Bounded overlap of TTS preparation and generation; reviewed sentences alone reach the task. */
public final class CascadePipeline {
    @FunctionalInterface
    public interface Factory {
        VoiceAiBackends.TextSession open() throws Exception;
    }

    @FunctionalInterface
    public interface Generator {
        void run(VoiceAiBackends.TextDelta output) throws Exception;
    }

    private static final String END = new String("end");

    private CascadePipeline() {}

    public static String run(
            ResponseScope owner,
            Map<String, Object> guard,
            Factory factory,
            Generator generator,
            ContentSafety.Reviewer reviewer,
            Consumer<Map<String, Object>> trace,
            Consumer<String> stage)
            throws Exception {
        owner.check();
        Factory preparing = factory;
        if (Boolean.FALSE.equals(guard.get("tts_prepare_parallel"))) {
            stage.accept("tts_connect_start");
            var prepared = owner.own(factory.open());
            preparing = () -> prepared;
        }
        Factory taskFactory = preparing;
        var sentences = new ArrayBlockingQueue<String>(8);
        var synthesis = new CompletableFuture<Void>();
        Thread worker =
                Thread.ofVirtual()
                        .name("voice-cascade-tts")
                        .unstarted(
                                () -> {
                                    try {
                                        stage.accept("tts_connect_start");
                                        try (var task = owner.own(taskFactory.open())) {
                                            stage.accept("tts_ready");
                                            for (; ; ) {
                                                owner.check();
                                                String text =
                                                        sentences.poll(50, TimeUnit.MILLISECONDS);
                                                if (text == null) continue;
                                                if (text == END) break;
                                                task.append(text);
                                            }
                                            task.finish();
                                        }
                                        synthesis.complete(null);
                                    } catch (Throwable error) {
                                        synthesis.completeExceptionally(error);
                                        owner.close();
                                    }
                                });
        owner.own(() -> worker.interrupt());
        worker.start();
        try {
            var gate =
                    new SegmentSafetyGate(
                            owner,
                            guard,
                            reviewer,
                            text -> {
                                while (!sentences.offer(text, 50, TimeUnit.MILLISECONDS)) {
                                    check(synthesis);
                                    owner.check();
                                }
                            },
                            trace);
            stage.accept("llm_request");
            generator.run(
                    delta -> {
                        check(synthesis);
                        owner.check();
                        stage.accept("llm_first_token");
                        gate.append(delta);
                    });
            String result = gate.finish();
            while (!sentences.offer(END, 50, TimeUnit.MILLISECONDS)) {
                check(synthesis);
                owner.check();
            }
            while (!synthesis.isDone()) {
                owner.check();
                try {
                    synthesis.get(50, TimeUnit.MILLISECONDS);
                } catch (TimeoutException pending) {
                    /* Owner cancellation remains responsive. */
                }
            }
            check(synthesis);
            owner.check();
            return result;
        } catch (Exception error) {
            owner.close();
            worker.interrupt();
            if (synthesis.isCompletedExceptionally()) check(synthesis);
            throw error;
        }
    }

    private static void check(CompletableFuture<Void> result) throws Exception {
        if (!result.isCompletedExceptionally()) return;
        try {
            result.get();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Exception cause) throw cause;
            throw new IOException("cascade synthesis failed", failed.getCause());
        }
    }
}
