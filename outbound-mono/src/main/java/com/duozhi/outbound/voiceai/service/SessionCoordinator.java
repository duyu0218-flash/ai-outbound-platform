package com.duozhi.outbound.voiceai.service;

import java.io.InterruptedIOException;
import java.util.*;

/** Fences response ownership independently from input, generation and playout progress. */
public final class SessionCoordinator implements AutoCloseable {
    private long epoch;
    private boolean closed;
    private ResponseScope active;
    private long generatedSamples, sentSamples, playedSamples;
    private String generatedText = "", playedText = "";
    private long began;
    private final Map<String, Long> timings = new LinkedHashMap<>();

    public synchronized long begin(ResponseScope scope) throws InterruptedIOException {
        if (closed) throw new InterruptedIOException("voice session closed");
        if (active != null) throw new InterruptedIOException("previous response has not drained");
        active = scope;
        began = System.nanoTime();
        timings.clear();
        generatedSamples = sentSamples = playedSamples = 0;
        generatedText = "";
        return ++epoch;
    }

    public synchronized void check(long expected) throws InterruptedIOException {
        if (closed || expected != epoch || active == null)
            throw new InterruptedIOException("stale voice response");
        active.check();
    }

    public synchronized void generated(long expected, long samples) throws InterruptedIOException {
        check(expected);
        generatedSamples += samples;
    }

    public synchronized void sent(long expected, long samples) throws InterruptedIOException {
        check(expected);
        sentSamples += samples;
    }

    public synchronized void text(long expected, String text) throws InterruptedIOException {
        check(expected);
        generatedText = text;
    }

    public String drained(long expected, boolean confirmed) throws InterruptedIOException {
        ResponseScope finished;
        String result;
        synchronized (this) {
            check(expected);
            if (confirmed) {
                playedSamples = sentSamples;
                playedText = generatedText;
            }
            finished = active;
            active = null;
            result = confirmed ? playedText : "";
        }
        finished.close();
        return result;
    }

    public void interrupt() {
        ResponseScope cancelling;
        synchronized (this) {
            ++epoch;
            cancelling = active;
            active = null;
            generatedText = "";
        }
        if (cancelling != null) cancelling.close();
    }

    public synchronized void stamp(long expected, String stage) throws InterruptedIOException {
        check(expected);
        timings.putIfAbsent(stage, (System.nanoTime() - began) / 1000000);
    }

    public synchronized Map<String, Object> evidence() {
        return Map.of(
                "response_epoch",
                epoch,
                "generated_samples",
                generatedSamples,
                "sent_samples",
                sentSamples,
                "played_samples",
                playedSamples,
                "evidence_level",
                playedSamples > 0 ? "module_played" : "sent",
                "active",
                active != null,
                "closed",
                closed,
                "stage_elapsed_ms",
                Map.copyOf(timings));
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        interrupt();
    }
}
