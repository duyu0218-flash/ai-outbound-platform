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
        var failure = new CompletableFuture<Void>();
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
                                        failure.completeExceptionally(error);
                                        synthesis.completeExceptionally(error);
                                        owner.close();
                                    }
                                });
        owner.own(() -> worker.interrupt());
        var deltas = new ArrayBlockingQueue<String>(32);
        var reviewed = new CompletableFuture<String>();
        Thread reviewing =
                Thread.ofVirtual()
                        .name("voice-cascade-review")
                        .unstarted(
                                () -> {
                                    try {
                                        var gate =
                                                new SegmentSafetyGate(
                                                        owner,
                                                        guard,
                                                        reviewer,
                                                        text -> {
                                                            stage.accept("first_reviewed_sentence");
                                                            while (!sentences.offer(
                                                                    text,
                                                                    50,
                                                                    TimeUnit.MILLISECONDS)) {
                                                                check(synthesis);
                                                                owner.check();
                                                            }
                                                        },
                                                        trace);
                                        for (; ; ) {
                                            owner.check();
                                            String delta = deltas.poll(50, TimeUnit.MILLISECONDS);
                                            if (delta == null) continue;
                                            if (delta == END) break;
                                            gate.append(delta);
                                        }
                                        String result = gate.finish();
                                        while (!sentences.offer(END, 50, TimeUnit.MILLISECONDS)) {
                                            check(synthesis);
                                            owner.check();
                                        }
                                        reviewed.complete(result);
                                    } catch (Throwable error) {
                                        failure.completeExceptionally(error);
                                        reviewed.completeExceptionally(error);
                                        owner.close();
                                    }
                                });
        owner.own(() -> reviewing.interrupt());
        try {
            worker.start();
            reviewing.start();
            stage.accept("llm_request");
            generator.run(
                    delta -> {
                        check(reviewed);
                        check(synthesis);
                        owner.check();
                        stage.accept("llm_first_token");
                        if (delta.length() > 4000)
                            throw new IOException("text delta exceeds limit");
                        while (!deltas.offer(delta, 50, TimeUnit.MILLISECONDS)) {
                            check(reviewed);
                            check(synthesis);
                            owner.check();
                        }
                    });
            while (!deltas.offer(END, 50, TimeUnit.MILLISECONDS)) {
                check(reviewed);
                check(synthesis);
                owner.check();
            }
            while (!reviewed.isDone() || !synthesis.isDone()) {
                check(reviewed);
                check(synthesis);
                owner.check();
                try {
                    synthesis.get(50, TimeUnit.MILLISECONDS);
                } catch (TimeoutException pending) {
                    /* cancellation remains bounded */
                }
            }
            check(reviewed);
            check(synthesis);
            owner.check();
            return reviewed.get();
        } catch (Exception error) {
            // Capture the substantive failure before cancellation interrupts other stages.
            Exception cause = error;
            try {
                check(failure);
            } catch (Exception original) {
                cause = original;
            }
            owner.close();
            reviewing.interrupt();
            worker.interrupt();
            throw cause;
        }
    }

    private static void check(CompletableFuture<?> result) throws Exception {
        if (!result.isCompletedExceptionally()) return;
        try {
            result.get();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Exception cause) throw cause;
            throw new IOException("cascade synthesis failed", failed.getCause());
        }
    }
}
