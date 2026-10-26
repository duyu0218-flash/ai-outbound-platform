package com.duozhi.outbound.voiceai.service;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/** Volc V3 framing; decompression and every length are bounded before allocation. */
public final class VolcVoiceProtocol {
    private static final int MAX = 2 * 1024 * 1024;

    public record Frame(int type, int event, String session, byte[] payload, boolean last) {}

    private VolcVoiceProtocol() {}

    public static byte[] event(int event, String session, byte[] payload, boolean audio) {
        byte[] id = session.getBytes(StandardCharsets.UTF_8);
        boolean scoped = event >= 100;
        var out = ByteBuffer.allocate(4 + 4 + (scoped ? 4 + id.length : 0) + 4 + payload.length);
        out.put((byte) 0x11)
                .put((byte) (audio ? 0x24 : 0x14))
                .put((byte) (audio ? 0 : 0x10))
                .put((byte) 0);
        out.putInt(event);
        if (scoped) out.putInt(id.length).put(id);
        return out.putInt(payload.length).put(payload).array();
    }

    public static byte[] asr(byte[] payload, boolean json, int sequence) {
        var out = ByteBuffer.allocate(12 + payload.length);
        out.put((byte) 0x11)
                .put((byte) ((json ? 0x10 : 0x20) | (sequence < 0 ? 3 : 1)))
                .put((byte) (json ? 0x10 : 0))
                .put((byte) 0)
                .putInt(sequence)
                .putInt(payload.length)
                .put(payload);
        return out.array();
    }

    public static Frame parse(byte[] bytes) throws IOException {
        try {
            if (bytes.length < 8 || bytes.length > MAX)
                throw new IOException("invalid voice frame size");
            var in = ByteBuffer.wrap(bytes);
            int header = (in.get(0) & 15) * 4,
                    type = (in.get(1) & 255) >> 4,
                    flags = in.get(1) & 15,
                    compression = in.get(2) & 15;
            if (header < 4 || header > in.limit())
                throw new IOException("invalid voice frame header");
            in.position(header);
            if (type == 15) throw new IOException("provider returned a protocol error");
            boolean last = (flags & 2) != 0;
            if ((flags & 1) != 0) in.getInt();
            int event = (flags & 4) != 0 ? in.getInt() : 0;
            String session = "";
            if (event >= 100 || event == 50 || event == 51 || event == 52)
                session = new String(field(in), StandardCharsets.UTF_8);
            byte[] payload = field(in);
            if (in.hasRemaining()) throw new IOException("trailing voice frame data");
            if (compression == 1) {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                    payload = gzip.readNBytes(MAX + 1);
                    if (payload.length > MAX)
                        throw new IOException("voice frame decompression limit");
                }
            } else if (compression != 0) throw new IOException("unsupported voice compression");
            return new Frame(type, event, session, payload, last);
        } catch (BufferUnderflowException | IllegalArgumentException error) {
            throw new IOException("invalid voice frame", error);
        }
    }

    private static byte[] field(ByteBuffer in) throws IOException {
        int size = in.getInt();
        if (size < 0 || size > MAX || size > in.remaining())
            throw new IOException("invalid voice frame field");
        byte[] bytes = new byte[size];
        in.get(bytes);
        return bytes;
    }
}
