package com.duozhi.outbound.media.transport;

import java.nio.*;
import java.util.Arrays;

/** RTP framing and G.711 conversion kept independent of SIP/FreeSWITCH ownership. */
public final class Rtp {
    record Packet(
            int payloadType,
            int sequence,
            long timestamp,
            long ssrc,
            byte[] payload,
            boolean marker) {
        byte[] encode() {
            if (payloadType < 0 || payloadType > 127)
                throw new IllegalArgumentException("invalid RTP payload type");
            return ByteBuffer.allocate(12 + payload.length)
                    .put((byte) 0x80)
                    .put((byte) (payloadType | (marker ? 128 : 0)))
                    .putShort((short) sequence)
                    .putInt((int) timestamp)
                    .putInt((int) ssrc)
                    .put(payload)
                    .array();
        }

        static Packet decode(byte[] bytes) {
            if (bytes.length < 12 || (bytes[0] & 255) >> 6 != 2)
                throw new IllegalArgumentException("invalid RTP header");
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            int first = buffer.get() & 255,
                    second = buffer.get() & 255,
                    sequence = buffer.getShort() & 65535;
            long timestamp = buffer.getInt() & 0xffffffffL, ssrc = buffer.getInt() & 0xffffffffL;
            int offset = 12 + (first & 15) * 4;
            if (bytes.length < offset)
                throw new IllegalArgumentException("truncated RTP CSRC list");
            if ((first & 16) != 0) {
                if (bytes.length < offset + 4)
                    throw new IllegalArgumentException("truncated RTP extension");
                offset += 4 + (ByteBuffer.wrap(bytes, offset + 2, 2).getShort() & 65535) * 4;
            }
            if (bytes.length < offset) throw new IllegalArgumentException("truncated RTP payload");
            return new Packet(
                    second & 127,
                    sequence,
                    timestamp,
                    ssrc,
                    Arrays.copyOfRange(bytes, offset, bytes.length),
                    (second & 128) != 0);
        }
    }

    static byte[] encode(byte[] pcm, boolean alaw) {
        if (pcm.length % 2 != 0)
            throw new IllegalArgumentException("PCM16 input must contain complete samples");
        byte[] result = new byte[pcm.length / 2];
        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < result.length; i++) {
            int sample = buffer.getShort();
            if (alaw) {
                int mask = sample >= 0 ? 0xd5 : 0x55,
                        value = Math.max(0, Math.min(sample >= 0 ? sample : -sample - 8, 32635));
                int segment = Math.min(7, 32 - Integer.numberOfLeadingZeros(value) - 8);
                result[i] =
                        (byte)
                                ((value < 256
                                                ? value >> 4
                                                : (segment << 4) | ((value >> (segment + 3)) & 15))
                                        ^ mask);
            } else {
                int sign = sample < 0 ? 128 : 0, value = Math.min(Math.abs(sample), 32635) + 132;
                int exponent = Math.max(0, 32 - Integer.numberOfLeadingZeros(value) - 8),
                        mantissa = (value >> (exponent + 3)) & 15;
                result[i] = (byte) ~(sign | (exponent << 4) | mantissa);
            }
        }
        return result;
    }

    static byte[] decode(byte[] encoded, boolean alaw) {
        ByteBuffer pcm = ByteBuffer.allocate(encoded.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (byte item : encoded) {
            int value = item & 255, sample;
            if (alaw) {
                value ^= 0x55;
                sample = (value & 15) << 4;
                int segment = (value & 112) >> 4;
                sample =
                        segment == 0
                                ? sample + 8
                                : segment == 1 ? sample + 264 : (sample + 264) << (segment - 1);
                if ((value & 128) == 0) sample = -sample;
            } else {
                value = ~value & 255;
                sample = (((value & 15) << 3) + 132) << ((value >> 4) & 7);
                sample -= 132;
                if ((value & 128) != 0) sample = -sample;
            }
            pcm.putShort((short) sample);
        }
        return pcm.array();
    }
}
