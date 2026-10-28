package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.integration.support.Support;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Byte-bounded SSE framing. UTF-8 decoding happens only after an entire line arrives. */
final class SseTextReader {
    private SseTextReader() {}

    static String read(
            InputStream input,
            VoiceAiBackends.TextDelta output,
            ResponseScope scope,
            TextStreamDeadline deadline)
            throws Exception {
        var line = new ByteArrayOutputStream();
        var data = new StringBuilder();
        var result = new StringBuilder();
        byte[] buffer = new byte[4096];
        int received = 0;
        for (int n; (n = input.read(buffer)) != -1; ) {
            scope.check();
            deadline.check();
            received += n;
            if (received > 512 * 1024) throw new IOException("text model response limit");
            for (int i = 0; i < n; i++) {
                int value = buffer[i] & 255;
                if (value != '\n') {
                    if (line.size() >= 65536) throw new IOException("SSE line exceeds limit");
                    line.write(value);
                    continue;
                }
                String text =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(line.toByteArray()))
                                .toString();
                line.reset();
                if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
                if (text.isEmpty()) {
                    if (data.isEmpty()) continue;
                    String payload = data.toString();
                    data.setLength(0);
                    if (payload.strip().equals("[DONE]")) {
                        if (result.toString().isBlank())
                            throw new IOException("text model returned no speech text");
                        deadline.check();
                        return result.toString().strip();
                    }
                    var event = Support.object(payload.getBytes(StandardCharsets.UTF_8));
                    if (event.containsKey("error"))
                        throw new IOException("text model stream error");
                    if (event.get("choices") instanceof List<?> choices && !choices.isEmpty()) {
                        var choice = Support.map(choices.get(0));
                        var delta = Support.map(choice.get("delta"));
                        String finish = Support.str(choice, "finish_reason", "");
                        if (delta.containsKey("tool_calls")
                                || delta.containsKey("function_call")
                                || (!finish.isBlank() && !"stop".equals(finish)))
                            throw new IOException(
                                    "model response requires controlled business handling");
                        String token = Support.str(delta, "content", "");
                        result.append(token);
                        if (result.length() > 4000)
                            throw new IOException("text model output limit");
                        if (!token.isEmpty()) {
                            if (!token.isBlank()) deadline.content();
                            output.accept(token);
                            scope.check();
                        }
                    }
                } else if (text.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(text.substring(5).stripLeading());
                    if (data.length() > 65536) throw new IOException("SSE event exceeds limit");
                }
            }
        }
        deadline.check();
        throw new IOException("text model stream ended without DONE");
    }
}
