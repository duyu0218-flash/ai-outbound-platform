package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.integration.support.Support;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Actual output PCM -> independent final ASR timestamps -> semantic review -> ordered playback. */
public final class SentenceSafetyGate implements AutoCloseable {
    @FunctionalInterface
    public interface RecognizerFactory {
        VoiceAiBackends.Connection open(VoiceAiBackends.Listener listener, ResponseScope scope)
                throws Exception;
    }

    private record Sentence(String text, long start, long end) {}

    private record Gap(long start, long end) {}

    private final List<Gap> gaps = new ArrayList<>();

    private static final byte[] END_AUDIO = new byte[0];
    private static final Sentence END_TEXT = new Sentence("", -1, -1);
    private final ResponseScope scope = new ResponseScope();
    private final BlockingQueue<byte[]> input = new ArrayBlockingQueue<>(64);
    private final BlockingQueue<Sentence> finals = new ArrayBlockingQueue<>(16);
    private final BlockingQueue<byte[]> approvedAudio = new ArrayBlockingQueue<>(2);
    private final PcmWindow pcm = new PcmWindow();
    private final AtomicReference<Exception> failed = new AtomicReference<>();
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private final Object lock = new Object();
    private final Map<String, Object> guard;
    private final ContentSafety.Reviewer reviewer;
    private final VoiceAiBackends.Audio sink;
    private final Consumer<Map<String, Object>> trace;
    private final Runnable abort;
    private final Thread recognition, review, playback;
    private final int maxPendingBytes;
    private final long began = System.nanoTime(), deadline = began + TimeUnit.SECONDS.toNanos(40);
    private long released, lastFinalEnd;
    private boolean ended;
    private final StringBuilder approved = new StringBuilder();

    public SentenceSafetyGate(
            ResponseScope parent,
            Map<String, Object> policy,
            Map<String, Object> guard,
            RecognizerFactory factory,
            ContentSafety.Reviewer reviewer,
            VoiceAiBackends.Audio sink,
            Consumer<Map<String, Object>> trace,
            Runnable abort)
            throws Exception {
        this.guard = Map.copyOf(guard);
        this.reviewer = reviewer;
        this.sink = sink;
        this.trace = trace;
        this.abort = abort;
        long pending = Support.integer(policy, "max_pending_ms", 6000);
        if (pending < 1000 || pending > 10000)
            throw new ContentSafety.Failure("S2S_SAFETY_POLICY_INVALID");
        maxPendingBytes = (int) pending * 48;
        recognition = Thread.ofVirtual().name("s2s-output-asr").unstarted(() -> recognize(factory));
        review = Thread.ofVirtual().name("s2s-sentence-review").unstarted(this::review);
        playback = Thread.ofVirtual().name("s2s-approved-playback").unstarted(this::play);
        parent.own(this);
        recognition.start();
        review.start();
        playback.start();
    }

    public void accept(byte[] bytes, int rate) throws Exception {
        if (rate != 24000 || bytes.length == 0 || bytes.length % 2 != 0)
            throw new ContentSafety.Failure("S2S_SAFETY_PCM_INVALID");
        for (int offset = 0; offset < bytes.length; offset += 4800) {
            byte[] frame = Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + 4800));
            synchronized (lock) {
                check();
                if (ended) throw new ContentSafety.Failure("S2S_SAFETY_AUDIO_AFTER_DONE");
                long pendingDeadline =
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxPendingBytes / 48);
                while (pcm.size() - released + frame.length > maxPendingBytes) {
                    lock.wait(50);
                    check();
                    if (System.nanoTime() > pendingDeadline)
                        throw new ContentSafety.Failure("S2S_SAFETY_PENDING_TIMEOUT");
                }
                if (pcm.size() + frame.length > 1440000)
                    throw new ContentSafety.Failure("S2S_SAFETY_AUDIO_LIMIT");
                pcm.writeBytes(frame);
            }
            offer(input, frame);
        }
    }

    private <T> void offer(BlockingQueue<T> queue, T value) throws Exception {
        while (!queue.offer(value, 50, TimeUnit.MILLISECONDS)) check();
    }

    private void recognize(RecognizerFactory factory) {
        try (var connection =
                scope.own(
                        factory.open(
                                new VoiceAiBackends.Listener() {
                                    public void transcript(String text, String event)
                                            throws Exception {
                                        throw new ContentSafety.Failure(
                                                "S2S_SAFETY_TIMESTAMPS_MISSING");
                                    }

                                    public void speechStarted() {}

                                    public void recognition(Map<String, Object> event)
                                            throws Exception {
                                        if (!Boolean.TRUE.equals(event.get("is_final"))) return;
                                        String text = Support.str(event, "text", "");
                                        long start = Support.integer(event, "start_ms", -1),
                                                end = Support.integer(event, "end_ms", -1);
                                        if (text.isBlank()
                                                || start < 0
                                                || end <= start
                                                || end > 30000)
                                            throw new ContentSafety.Failure(
                                                    "S2S_SAFETY_TIMESTAMPS_MISSING");
                                        if (!finals.offer(new Sentence(text, start, end)))
                                            throw new ContentSafety.Failure(
                                                    "S2S_SAFETY_QUEUE_FULL");
                                    }
                                },
                                scope))) {
            for (; ; ) {
                check();
                byte[] frame = input.poll(50, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                if (frame == END_AUDIO) break;
                connection.audio(frame);
            }
            connection.finishInput();
            connection.awaitFinished();
            check();
            offer(finals, END_TEXT);
        } catch (Exception error) {
            fail(error);
        }
    }

    private void review() {
        try {
            StringBuilder pending = new StringBuilder();
            long pendingEnd = 0;
            for (; ; ) {
                check();
                Sentence sentence = finals.poll(50, TimeUnit.MILLISECONDS);
                if (sentence == null) continue;
                if (sentence == END_TEXT) {
                    synchronized (lock) {
                        byte[] all = pcm.range(lastFinalEnd, pcm.size());
                        if ((approved.isEmpty() && pending.isEmpty()) || !quiet(all, 0, all.length))
                            throw new ContentSafety.Failure("S2S_SAFETY_UNRECOGNIZED_AUDIO");
                    }
                    if (!pending.isEmpty()) approve(pending.toString(), pendingEnd);
                    synchronized (lock) {
                        released = pcm.size();
                        pcm.release(released);
                        lock.notifyAll();
                    }
                    offer(approvedAudio, END_AUDIO);
                    return;
                }
                long start = sentence.start * 48, end = sentence.end * 48;
                synchronized (lock) {
                    if (start < lastFinalEnd || end > pcm.size() || end <= lastFinalEnd)
                        throw new ContentSafety.Failure("S2S_SAFETY_BOUNDARY_INVALID");
                    byte[] gap = pcm.range(lastFinalEnd, start);
                    if (!quiet(gap, 0, gap.length))
                        throw new ContentSafety.Failure("S2S_SAFETY_UNRECOGNIZED_AUDIO");
                    if (start > lastFinalEnd) gaps.add(new Gap(lastFinalEnd, start));
                    lastFinalEnd = end;
                }
                pending.append(sentence.text);
                pendingEnd = end;
                TextCommitter.validate(approved.toString() + pending, guard);
                if (sentence.text.matches("(?s).*[。！？!?；;][\\s”’\"']*$")) {
                    approve(pending.toString(), pendingEnd);
                    pending.setLength(0);
                }
            }
        } catch (Exception error) {
            fail(error);
        }
    }

    private void approve(String sentence, long end) throws Exception {
        check();
        String cumulative = approved.toString() + sentence;
        TextCommitter.validate(cumulative, guard);
        long started = System.nanoTime();
        var decision = reviewer.review(cumulative, scope);
        if (!"none".equals(decision.get("risk_level"))
                || Support.str(decision, "request_id", "").isBlank())
            throw new ContentSafety.Failure("S2S_SAFETY_RESULT_INVALID");
        check();
        byte[] audio;
        synchronized (lock) {
            audio = pcm.range(released, end);
            // Only recognized spans may carry audio. Even low-energy unrecognized gaps are muted.
            for (var gap : gaps) {
                long from = Math.max(released, gap.start), to = Math.min(end, gap.end);
                if (to > from)
                    Arrays.fill(audio, (int) (from - released), (int) (to - released), (byte) 0);
            }
            released = end;
            pcm.release(released);
            gaps.removeIf(gap -> gap.end <= released);
            lock.notifyAll();
        }
        trace.accept(
                Map.of(
                        "status",
                        "approved",
                        "text_sha256",
                        Support.hex(
                                Support.sha(
                                        cumulative.getBytes(
                                                java.nio.charset.StandardCharsets.UTF_8))),
                        "audio_sha256",
                        Support.hex(Support.sha(audio)),
                        "end_sample",
                        end / 2,
                        "review_ms",
                        (System.nanoTime() - started) / 1000000,
                        "elapsed_ms",
                        (System.nanoTime() - began) / 1000000,
                        "decision",
                        decision));
        check();
        approved.append(sentence);
        offer(approvedAudio, audio);
    }

    private void play() {
        try {
            for (; ; ) {
                check();
                byte[] audio = approvedAudio.poll(50, TimeUnit.MILLISECONDS);
                if (audio == null) continue;
                if (audio == END_AUDIO) {
                    done.complete(null);
                    return;
                }
                check();
                sink.accept(audio, 24000);
                check();
            }
        } catch (Exception error) {
            fail(error);
        }
    }

    private static boolean quiet(byte[] bytes, int from, int to) {
        if (from < 0 || to > bytes.length || from > to) return false;
        for (int offset = from; offset < to; offset += 960) {
            int end = Math.min(to, offset + 960);
            double energy = 0;
            for (int i = offset; i + 1 < end; i += 2) {
                short v = (short) ((bytes[i] & 255) | bytes[i + 1] << 8);
                energy += (double) v * v;
            }
            if (Math.sqrt(energy / Math.max(1, (end - offset) / 2)) >= 100) return false;
        }
        return true;
    }

    public String finish() throws Exception {
        synchronized (lock) {
            check();
            if (ended) throw new IOException("duplicate safety finish");
            ended = true;
        }
        offer(input, END_AUDIO);
        while (!done.isDone()) {
            check();
            try {
                done.get(50, TimeUnit.MILLISECONDS);
            } catch (TimeoutException pending) {
            }
        }
        check();
        return approved.toString();
    }

    public void check() throws Exception {
        Exception error = failed.get();
        if (error != null) throw error;
        scope.check();
        if (System.nanoTime() > deadline) throw new ContentSafety.Failure("S2S_SAFETY_TIMEOUT");
    }

    public long retainedAudioBytes() {
        synchronized (lock) {
            return pcm.retainedBytes();
        }
    }

    private void fail(Exception error) {
        if (!scope.cancelled() && failed.compareAndSet(null, error)) {
            try {
                trace.accept(
                        Map.of(
                                "status",
                                "blocked",
                                "code",
                                error instanceof ContentSafety.Failure f
                                        ? f.code
                                        : "S2S_SAFETY_UNAVAILABLE"));
            } catch (Exception ignored) {
            }
            done.completeExceptionally(error);
            scope.close();
            synchronized (lock) {
                lock.notifyAll();
            }
            try {
                abort.run();
            } catch (Exception ignored) {
            }
            recognition.interrupt();
            review.interrupt();
            playback.interrupt();
        }
    }

    @Override
    public void close() {
        scope.close();
        recognition.interrupt();
        review.interrupt();
        playback.interrupt();
        input.clear();
        finals.clear();
        approvedAudio.clear();
        synchronized (lock) {
            lock.notifyAll();
        }
    }
}
