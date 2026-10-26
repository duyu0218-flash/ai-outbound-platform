package com.duozhi.outbound.voiceai.service;

import java.util.*;

/** Absolute byte offsets survive releasing reviewed prefixes. Caller owns synchronization. */
public final class PcmWindow {
    private record Block(long start, byte[] bytes) {}

    private final ArrayDeque<Block> blocks = new ArrayDeque<>();
    private long start, end;

    public long size() {
        return end;
    }

    public long retainedBytes() {
        return end - start;
    }

    public void writeBytes(byte[] bytes) {
        blocks.addLast(new Block(end, bytes.clone()));
        end += bytes.length;
    }

    public byte[] range(long from, long to) {
        if (from < start || to > end || to < from || to - from > Integer.MAX_VALUE)
            throw new IllegalArgumentException("PCM range no longer available");
        byte[] result = new byte[(int) (to - from)];
        for (var block : blocks) {
            long a = Math.max(from, block.start),
                    b = Math.min(to, block.start + block.bytes.length);
            if (b > a)
                System.arraycopy(
                        block.bytes,
                        (int) (a - block.start),
                        result,
                        (int) (a - from),
                        (int) (b - a));
        }
        return result;
    }

    public void release(long until) {
        if (until < start || until > end) throw new IllegalArgumentException("invalid PCM release");
        while (!blocks.isEmpty()
                && blocks.getFirst().start + blocks.getFirst().bytes.length <= until)
            blocks.removeFirst();
        if (!blocks.isEmpty() && blocks.getFirst().start < until) {
            var first = blocks.removeFirst();
            blocks.addFirst(
                    new Block(
                            until,
                            Arrays.copyOfRange(
                                    first.bytes, (int) (until - first.start), first.bytes.length)));
        }
        start = until;
    }
}
