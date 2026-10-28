package com.duozhi.outbound.media.service;

import com.duozhi.outbound.voiceai.service.ResponseScope;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Response-owned bounded PCM queue. One sender; finish means every queued frame was sent. */
final class PcmPlayback implements AutoCloseable {
    interface Sender {
        void send(byte[] bytes, String purpose) throws Exception;
    }

    interface Fence {
        void check() throws Exception;
    }

    private record Frame(byte[] bytes, String purpose) {}

    private final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(75);
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final Fence fence;
    private final Thread sender;
    private final int frameBytes;
    private volatile boolean closed, finished;

    PcmPlayback(int rate, Fence fence, Sender output, ResponseScope scope) throws Exception {
        this.fence = fence;
        frameBytes = rate / 50 * 2;
        sender =
                Thread.ofVirtual()
                        .name("voice-pcm-playback")
                        .unstarted(
                                () -> {
                                    try {
                                        while (!closed) {
                                            Frame frame = frames.poll(100, TimeUnit.MILLISECONDS);
                                            if (frame == null) {
                                                if (finished && frames.isEmpty()) {
                                                    drained.complete(null);
                                                    return;
                                                }
                                                continue;
                                            }
                                            fence.check();
                                            output.send(frame.bytes(), frame.purpose());
                                        }
                                        throw new InterruptedIOException("playback cancelled");
                                    } catch (Exception error) {
                                        drained.completeExceptionally(error);
                                    } finally {
                                        closed = true;
                                        frames.clear();
                                    }
                                });
        scope.own(this);
        sender.start();
    }

    void accept(byte[] bytes, String purpose) throws Exception {
        for (int offset = 0; offset < bytes.length; offset += frameBytes) {
            fence.check();
            if (closed || finished) throw new IOException("playback closed");
            var frame =
                    new Frame(
                            Arrays.copyOfRange(
                                    bytes, offset, Math.min(bytes.length, offset + frameBytes)),
                            purpose);
            if (!frames.offer(frame, 2, TimeUnit.SECONDS))
                throw new IOException("playback queue stalled");
            fence.check();
            if (drained.isCompletedExceptionally()) drained.get();
        }
    }

    void finish() throws Exception {
        finished = true;
        drained.get(10, TimeUnit.SECONDS);
        fence.check();
    }

    public void close() {
        closed = true;
        frames.clear();
        sender.interrupt();
        drained.completeExceptionally(new InterruptedIOException("playback cancelled"));
    }
}
