package com.duozhi.outbound.media.service;

import com.duozhi.outbound.integration.support.Support;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Bounded off-heap PCM ingress. Per-socket order is preserved; overload drops the oldest queued
 * frame.
 */
public final class DirectAudioDispatcher implements AutoCloseable {
    interface Sink {
        void accept(ByteBuffer frame) throws Exception;
    }

    private static final int FRAME_BYTES = 65536, SESSION_FRAMES = 4;

    private record Frame(ByteBuffer data, long start, long end, long queuedAt) {}

    private final ThreadPoolExecutor executor;
    private final ArrayBlockingQueue<ByteBuffer> free;
    private final Semaphore allocations;
    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();
    private final LongAdder dropped = new LongAdder(), processed = new LongAdder();
    private volatile boolean closed;

    private static final class Channel {
        final ArrayDeque<Frame> frames = new ArrayDeque<>();
        boolean running, closed;
        long receivedSamples, queuedBytes, droppedSamples, droppedFrames, maxQueueAge;
        final int rate;
        final long byteBudget;
        final Consumer<Map<String, Object>> gap;
        final Sink sink;
        final Consumer<Exception> failure;

        Channel(
                Sink sink,
                Consumer<Exception> failure,
                int rate,
                int queueMs,
                Consumer<Map<String, Object>> gap) {
            this.sink = sink;
            this.failure = failure;
            this.rate = rate;
            this.byteBudget = Math.min(65536L, (long) rate * 2 * queueMs / 1000);
            this.gap = gap;
        }
    }

    DirectAudioDispatcher(int sessions) {
        this(
                Math.min(32, Math.max(4, sessions)),
                Math.min(8192, Math.max(32, sessions * 2)),
                Math.min(1024, Math.max(32, sessions * 4)));
    }

    DirectAudioDispatcher(int threads, int tasks, int frames) {
        free = new ArrayBlockingQueue<>(frames);
        allocations = new Semaphore(frames);
        executor =
                new ThreadPoolExecutor(
                        threads,
                        threads,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(tasks),
                        task -> {
                            var thread = new Thread(task, "mono-media-pcm");
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    void register(String id, Sink sink, Consumer<Exception> failure) {
        register(id, sink, failure, 16000, evidence -> {});
    }

    void register(
            String id,
            Sink sink,
            Consumer<Exception> failure,
            int rate,
            Consumer<Map<String, Object>> gap) {
        register(id, sink, failure, rate, 4000, gap);
    }

    void register(
            String id,
            Sink sink,
            Consumer<Exception> failure,
            int rate,
            int queueMs,
            Consumer<Map<String, Object>> gap) {
        if (queueMs < 100 || queueMs > 4000)
            throw new IllegalArgumentException("invalid ingress queue duration");
        if (rate != 8000 && rate != 16000 && rate != 24000 && rate != 48000)
            throw new IllegalArgumentException("invalid ingress sample rate");
        if (closed) throw new IllegalStateException("media ingress closed");
        Channel old = channels.putIfAbsent(id, new Channel(sink, failure, rate, queueMs, gap));
        if (old != null) throw new IllegalStateException("media socket already registered");
    }

    void offer(String id, ByteBuffer source) {
        if (source.remaining() % 2 != 0 || source.remaining() > FRAME_BYTES)
            throw new Support.Failure(400, "invalid PCM frame");
        Channel channel = channels.get(id);
        if (channel == null || closed) return;
        synchronized (channel) {
            if (channel.closed) return;
            long first = channel.receivedSamples;
            channel.receivedSamples += source.remaining() / 2;
            int size = source.remaining();
            while (!channel.frames.isEmpty()
                    && (channel.frames.size() >= SESSION_FRAMES
                            || channel.queuedBytes + size > channel.byteBudget)) {
                discard(channel, channel.frames.removeFirst(), "queue_budget");
            }
            if (size > channel.byteBudget) {
                noteGap(channel, first, channel.receivedSamples, "frame_duration_budget");
                return;
            }
            ByteBuffer frame = free.poll();
            if (frame == null && allocations.tryAcquire())
                try {
                    frame = ByteBuffer.allocateDirect(FRAME_BYTES);
                } catch (Throwable error) {
                    allocations.release();
                    throw error;
                }
            if (frame == null) {
                noteGap(channel, first, channel.receivedSamples, "buffer_pool_exhausted");
                return;
            }
            frame.clear();
            frame.put(source.duplicate()).flip();
            channel.frames.addLast(
                    new Frame(frame, first, channel.receivedSamples, System.nanoTime()));
            channel.queuedBytes += size;
            if (!channel.running) {
                channel.running = true;
                try {
                    executor.execute(() -> drain(channel));
                } catch (RejectedExecutionException busy) {
                    channel.running = false;
                    clear(channel);
                }
            }
        }
    }

    private void drain(Channel channel) {
        for (; ; ) {
            Frame frame;
            synchronized (channel) {
                frame = channel.frames.pollFirst();
                if (frame == null || channel.closed) {
                    if (frame != null) discard(channel, frame, "closed");
                    clear(channel);
                    channel.running = false;
                    return;
                }
                channel.queuedBytes -= frame.data.remaining();
                channel.maxQueueAge =
                        Math.max(
                                channel.maxQueueAge,
                                (System.nanoTime() - frame.queuedAt) / 1000000);
            }
            try {
                channel.sink.accept(frame.data.asReadOnlyBuffer());
                processed.increment();
            } catch (Exception error) {
                try {
                    if (!closed && !channel.closed) channel.failure.accept(error);
                } finally {
                    closeChannel(channel);
                }
            } finally {
                release(frame.data);
            }
        }
    }

    private void release(ByteBuffer frame) {
        frame.clear();
        if (!free.offer(frame)) throw new IllegalStateException("PCM pool ownership violation");
    }

    private void clear(Channel channel) {
        Frame frame;
        while ((frame = channel.frames.pollFirst()) != null) {
            discard(channel, frame, "queue_cleared");
        }
    }

    private void discard(Channel channel, Frame frame, String reason) {
        channel.queuedBytes -= frame.data.remaining();
        release(frame.data);
        noteGap(channel, frame.start, frame.end, reason);
    }

    private void noteGap(Channel channel, long from, long to, String reason) {
        dropped.increment();
        channel.droppedFrames++;
        channel.droppedSamples += to - from;
        channel.gap.accept(
                Map.of(
                        "start_sample",
                        from,
                        "end_sample",
                        to,
                        "sample_rate",
                        channel.rate,
                        "reason",
                        reason));
    }

    Map<String, Object> evidence(String id) {
        var channel = channels.get(id);
        if (channel == null) return Map.of();
        synchronized (channel) {
            long age =
                    channel.frames.isEmpty()
                            ? 0
                            : (System.nanoTime() - channel.frames.getFirst().queuedAt) / 1000000;
            return Map.of(
                    "received_samples",
                    channel.receivedSamples,
                    "dropped_samples",
                    channel.droppedSamples,
                    "dropped_frames",
                    channel.droppedFrames,
                    "queued_bytes",
                    channel.queuedBytes,
                    "oldest_frame_age_ms",
                    age,
                    "max_queue_age_ms",
                    channel.maxQueueAge);
        }
    }

    private void closeChannel(Channel channel) {
        synchronized (channel) {
            channel.closed = true;
            clear(channel);
        }
    }

    void disconnect(String id) {
        Channel channel = channels.remove(id);
        if (channel != null) closeChannel(channel);
    }

    long dropped() {
        return dropped.sum();
    }

    long processed() {
        return processed.sum();
    }

    int retainedBuffers() {
        return free.size();
    }

    @Override
    public void close() {
        closed = true;
        for (var channel : channels.values()) closeChannel(channel);
        channels.clear();
        executor.shutdownNow();
    }
}
