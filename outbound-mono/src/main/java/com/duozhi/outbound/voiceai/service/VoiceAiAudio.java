package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.media.service.NativeMedia.PcmResampler;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class VoiceAiAudio {
    private VoiceAiAudio() {}

    public record Pcm(byte[] bytes, int rate) {}

    public static Pcm wav(String base64) {
        try {
            if (base64.length() > 2600000) throw new IOException();
            byte[] bytes = Base64.getDecoder().decode(base64);
            var in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (in.remaining() < 44 || in.getInt() != 0x46464952) throw new IOException();
            in.getInt();
            if (in.getInt() != 0x45564157) throw new IOException();
            int rate = 0;
            boolean format = false;
            byte[] pcm = null;
            while (in.remaining() >= 8) {
                int chunk = in.getInt(), length = in.getInt();
                if (length < 0 || length > in.remaining()) throw new IOException();
                int next = in.position() + length + (length & 1);
                if (chunk == 0x20746d66) {
                    if (length < 16 || in.getShort() != 1 || in.getShort() != 1)
                        throw new IOException();
                    rate = in.getInt();
                    in.getInt();
                    in.getShort();
                    if (in.getShort() != 16) throw new IOException();
                    format = true;
                } else if (chunk == 0x61746164) {
                    pcm = new byte[length];
                    in.get(pcm);
                }
                if (next > in.limit()) throw new IOException();
                in.position(next);
            }
            if (!format
                    || !Set.of(8000, 16000, 24000, 48000).contains(rate)
                    || pcm == null
                    || pcm.length < rate / 5 * 2
                    || pcm.length % 2 != 0
                    || pcm.length > rate * 2 * 30) throw new IOException();
            return new Pcm(pcm, rate);
        } catch (Exception invalid) {
            throw new ApiError(400, "音频需为 0.2—30 秒、单声道 16bit PCM WAV，采样率为 8/16/24/48kHz");
        }
    }

    public static byte[] resample(byte[] pcm, int input, int output) {
        if (input == output) return pcm;
        var resampler = new PcmResampler(input, output);
        var out = new ByteArrayOutputStream();
        for (int i = 0; i < pcm.length; i += 4096)
            out.writeBytes(
                    resampler.convert(Arrays.copyOfRange(pcm, i, Math.min(pcm.length, i + 4096))));
        out.writeBytes(resampler.finish());
        return out.toByteArray();
    }

    public static byte[] wav(byte[] pcm, int rate) {
        return ByteBuffer.allocate(44 + pcm.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII))
                .putInt(36 + pcm.length)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(16)
                .putShort((short) 1)
                .putShort((short) 1)
                .putInt(rate)
                .putInt(rate * 2)
                .putShort((short) 2)
                .putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII))
                .putInt(pcm.length)
                .put(pcm)
                .array();
    }
}
