package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.integration.support.Support;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Vendor allowlists; output auditing cannot be biased with model-generated hotwords. */
public final class RecognitionOptions {
    private RecognitionOptions() {}

    private static final Map<String, Set<String>> FIELDS =
            Map.of(
                    "aliyun", Set.of("vocabulary_id", "asr_model"),
                    "volcengine", Set.of("boosting_table_id"),
                    "tencent", Set.of("hotword_id", "hotword_list", "word_info", "asr_model"));

    public static Map<String, Object> parse(String json, boolean audit) {
        var result = Support.object(json.getBytes(StandardCharsets.UTF_8));
        for (var entry : result.entrySet()) {
            if (!FIELDS.containsKey(entry.getKey()) || !(entry.getValue() instanceof Map))
                throw new IllegalArgumentException("unknown recognition vendor");
            validate(entry.getKey(), Support.map(entry.getValue()), audit);
        }
        return result;
    }

    public static void validate(String vendor, Map<String, Object> values, boolean audit) {
        for (var entry : values.entrySet()) {
            String key = entry.getKey();
            if (!FIELDS.getOrDefault(vendor, Set.of()).contains(key)
                    || audit && !Set.of("word_info", "asr_model").contains(key)
                    || !audit && key.equals("asr_model"))
                throw new IllegalArgumentException("unsupported recognition option: " + key);
            if (key.equals("asr_model")) {
                Set<String> allowed =
                        vendor.equals("aliyun")
                                ? Set.of("fun-asr-flash-8k-realtime", "fun-asr-realtime")
                                : Set.of("8k_zh", "16k_zh");
                if (!allowed.contains(entry.getValue()))
                    throw new IllegalArgumentException("unsupported audit ASR model");
            } else if (key.equals("word_info")) {
                if (!(entry.getValue() instanceof Number n)
                        || n.doubleValue() != n.intValue()
                        || n.intValue() < 0
                        || n.intValue() > 2)
                    throw new IllegalArgumentException("invalid word_info");
            } else {
                if (!(entry.getValue() instanceof String text)
                        || text.isBlank()
                        || text.length() > 2000)
                    throw new IllegalArgumentException("invalid recognition option: " + key);
                if (key.equals("hotword_list")) {
                    String[] words = text.split(",", -1);
                    if (words.length > 128) throw new IllegalArgumentException("too many hotwords");
                    for (String word : words)
                        if (!word.matches("[^\\s,|]{1,30}\\|(?:[1-9]|10)"))
                            throw new IllegalArgumentException("hotword weights must be 1-10");
                } else if (!text.matches("[A-Za-z0-9_-]{1,200}"))
                    throw new IllegalArgumentException("invalid vocabulary id");
            }
        }
        if (values.containsKey("hotword_id") && values.containsKey("hotword_list"))
            throw new IllegalArgumentException("choose one hotword source");
    }

    public static Map<String, Object> attach(
            Map<String, Object> profile, String options, boolean audit) {
        var all = parse(options, audit);
        String vendor = Support.str(profile, "vendor", "");
        var result = new LinkedHashMap<>(profile);
        var selected = new LinkedHashMap<>(Support.map(all.get(vendor)));
        if (selected.containsKey("asr_model"))
            result.put("asr_model", selected.remove("asr_model"));
        result.put("recognition_options", Map.copyOf(selected));
        result.put("recognition_purpose", audit ? "output_audit" : "customer");
        result.put("recognition_options_hash", Support.hex(Support.sha(Support.canonical(all))));
        return result;
    }

    public static int rate(Map<String, Object> profile) {
        if (!"cascade".equals(profile.get("route"))) return 16000;
        String vendor = Support.str(profile, "vendor", "");
        String model = Support.str(profile, "asr_model", "");
        if (vendor.equals("aliyun")) return model.contains("8k") ? 8000 : 16000;
        if (vendor.equals("tencent")) return model.startsWith("8k_") ? 8000 : 16000;
        return 16000;
    }

    public static Map<String, Object> aliyunParameters(Map<String, Object> spec) {
        var result = new LinkedHashMap<String, Object>();
        result.put("format", "pcm");
        result.put("sample_rate", rate(spec));
        result.put(
                "max_sentence_silence",
                Support.integer(Support.map(spec.get("turn_policy")), "vad_end_ms", 400));
        result.putAll(Support.map(spec.get("recognition_options")));
        return result;
    }

    public static Map<String, Object> volcRequest(Map<String, Object> spec) {
        var result = new LinkedHashMap<String, Object>();
        result.put("model_name", "bigmodel");
        result.put("enable_itn", true);
        result.put("enable_punc", true);
        result.put("show_utterances", true);
        result.put("result_type", "full");
        result.put(
                "end_window_size",
                Support.integer(Support.map(spec.get("turn_policy")), "vad_end_ms", 400));
        String vocabulary =
                Support.str(Support.map(spec.get("recognition_options")), "boosting_table_id", "");
        if (!vocabulary.isBlank()) result.put("corpus", Map.of("boosting_table_id", vocabulary));
        // Do not send force_to_speech_time=0: SDK samples and API constraints conflict.
        return result;
    }
}
