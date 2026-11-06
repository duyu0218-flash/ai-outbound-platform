package com.duozhi.outbound.media.service;

import java.nio.*;

/** Saturating PCM16 gain with an optional low-amplitude noise gate. */
public final class PcmLevel {
    private PcmLevel() {}

    public static ByteBuffer apply(ByteBuffer source, int decibels, boolean noiseGate) {
        if (decibels == 0 && !noiseGate) return source;
        ByteBuffer out =
                source.isReadOnly()
                        ? ByteBuffer.allocate(source.remaining()).put(source.duplicate()).flip()
                        : source.duplicate();
        out.order(ByteOrder.LITTLE_ENDIAN);
        double gain = Math.pow(10, decibels / 20.0);
        for (int i = out.position(); i + 1 < out.limit(); i += 2) {
            int value = out.getShort(i);
            if (noiseGate && Math.abs(value) < 120) value = 0;
            int scaled = (int) Math.round(value * gain);
            out.putShort(i, (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled)));
        }
        return out;
    }
}
