package com.duozhi.outbound.ai.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.operations.service.OutboundRules;
import com.duozhi.outbound.voiceai.service.RecognitionOptions;
import java.util.*;

/** Per-script speech settings overlay the selected, already authorized provider profile. */
public final class FlowSpeech {
    private FlowSpeech() {}

    public static Map<String, Object> normalize(Object raw) {
        var v = new LinkedHashMap<>(map(raw));
        v.put("voice", text(v, "voice", 128, false));
        if (!v.get("voice").toString().matches("[A-Za-z0-9_.:-]{0,128}"))
            throw new ApiError(422, "发音人标识无效");
        for (String k : List.of("volume", "speed", "pitch"))
            v.put(k, OutboundRules.integer(v.getOrDefault(k, 50), 0, 100));
        v.put("silence_ms", OutboundRules.integer(v.getOrDefault("silence_ms", 800), 200, 2000));
        v.put(
                "collect_silence_ms",
                OutboundRules.integer(
                        v.getOrDefault("collect_silence_ms", 1600),
                        ((Number) v.get("silence_ms")).intValue(),
                        3000));
        v.put("application_id", text(v, "application_id", 128, false));
        if (!v.get("application_id").toString().matches("[A-Za-z0-9_-]{0,128}"))
            throw new ApiError(422, "应用ID无效");
        Object options = v.getOrDefault("recognition_parameters", Map.of());
        try {
            var json =
                    new com.duozhi.outbound.common.support.JsonSupport(
                            new com.fasterxml.jackson.databind.ObjectMapper());
            RecognitionOptions.parse(json.json(options), false);
        } catch (Exception invalid) {
            throw new ApiError(422, "语音识别参数须使用已支持的供应商词表参数");
        }
        v.put("recognition_parameters", options);
        return v;
    }

    public static Map<String, Object> profile(
            Map<String, Object> profile, Map<String, Object> options) {
        var result = new LinkedHashMap<>(profile);
        if ("realtime".equals(result.get("route"))
                && result.get("speech_profile") instanceof Map<?, ?> speech)
            result.put("speech_profile", profile(map(speech), options));
        else {
            if (!Objects.toString(options.get("voice"), "").isBlank())
                result.put("voice", options.get("voice"));
            result.put(
                    "tts_options",
                    Map.of(
                            "volume",
                            options.get("volume"),
                            "speed",
                            options.get("speed"),
                            "pitch",
                            options.get("pitch")));
            var turn = new LinkedHashMap<>(map(result.getOrDefault("turn_policy", Map.of())));
            turn.put("vad_end_ms", options.get("silence_ms"));
            turn.put("vad_long_end_ms", options.get("collect_silence_ms"));
            result.put("turn_policy", turn);
            var parameters = map(options.getOrDefault("recognition_parameters", Map.of()));
            String vendor = Objects.toString(result.get("vendor"), "");
            if (parameters.get(vendor) instanceof Map<?, ?> selected) {
                var recognition =
                        new LinkedHashMap<>(
                                map(result.getOrDefault("recognition_options", Map.of())));
                recognition.putAll(map(selected));
                result.put("recognition_options", recognition);
            }
        }
        return result;
    }

    public static void apply(Map<String, Object> policy) {
        if (!(policy.get("flow_speech") instanceof Map<?, ?> options)) return;
        var ai = new LinkedHashMap<>(map(policy.getOrDefault("_ai", Map.of())));
        if (ai.get("_voice_profile") instanceof Map<?, ?> profile)
            ai.put("_voice_profile", profile(map(profile), map(options)));
        if (!Objects.toString(options.get("voice"), "").isBlank()) {
            ai.put("voice", options.get("voice"));
            policy.put("voice", options.get("voice"));
        }
        policy.put("_ai", ai);
    }

    public static Map<String, Object> aliyunTts(Map<String, Object> spec) {
        var p =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "text_type",
                                "PlainText",
                                "voice",
                                spec.get("voice"),
                                "format",
                                "pcm",
                                "sample_rate",
                                24000));
        if (spec.get("tts_options") instanceof Map<?, ?> o) {
            p.put("volume", o.get("volume"));
            p.put("rate", .5 + ((Number) o.get("speed")).doubleValue() / 100);
            p.put("pitch", .5 + ((Number) o.get("pitch")).doubleValue() / 100);
        }
        return p;
    }

    public static Map<String, Object> volcAudio(Map<String, Object> spec) {
        var p = new LinkedHashMap<String, Object>(Map.of("format", "pcm", "sample_rate", 24000));
        if (spec.get("tts_options") instanceof Map<?, ?> o) {
            p.put("speech_rate", Math.max(-50, 2 * (((Number) o.get("speed")).intValue() - 50)));
            p.put("loudness_rate", Math.max(-50, 2 * (((Number) o.get("volume")).intValue() - 50)));
        }
        return p;
    }
}
