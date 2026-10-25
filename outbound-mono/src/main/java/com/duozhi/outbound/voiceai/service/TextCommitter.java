package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.integration.support.Support;
import java.io.IOException;
import java.util.*;

/** Approves complete sentences against the cumulative output before irreversible TTS submission. */
public final class TextCommitter {
    @FunctionalInterface
    public interface Sink {
        void accept(String text) throws Exception;
    }

    private final Map<String, Object> guard;
    private final Sink sink;
    private final StringBuilder text = new StringBuilder();
    private int committed;

    public TextCommitter(Map<String, Object> guard, Sink sink) {
        this.guard = Map.copyOf(guard);
        this.sink = sink;
    }

    public void append(String delta) throws Exception {
        text.append(delta);
        validate(text.toString(), guard);
        for (int i = committed; i < text.length(); i++) {
            if ("。！？!?；;\n".indexOf(text.charAt(i)) >= 0) {
                String sentence = text.substring(committed, i + 1);
                sink.accept(sentence);
                committed = i + 1;
            }
        }
    }

    public String finish() throws Exception {
        validate(text.toString(), guard);
        if (text.toString().isBlank()) throw new IOException("empty model reply");
        // A trailing unpunctuated sentence is released only after a successful model DONE.
        if (committed < text.length()) sink.accept(text.substring(committed));
        committed = text.length();
        return text.toString();
    }

    public static void validate(String text, Map<String, Object> guard) throws IOException {
        if (text.codePointCount(0, text.length()) > Support.integer(guard, "max_reply_chars", 240))
            throw new IOException("voice output exceeds approved length");
        String normalized = normalize(text);
        for (String phrase :
                Support.str(guard, "forbidden_phrases", "").replace('\n', ',').split(","))
            if (!phrase.isBlank() && normalized.contains(normalize(phrase)))
                throw new IOException("voice output blocked by policy");
        if (Boolean.TRUE.equals(guard.get("require_fact_templates"))
                && text.matches(
                        "(?s).*(?:[0-9一二三四五六七八九十百千万]+(?:元|块|%|折)|已(?:预约|转接|办理|提交)|保证|承诺).*"))
            throw new IOException("business facts require an approved template");
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]", "");
    }
}
