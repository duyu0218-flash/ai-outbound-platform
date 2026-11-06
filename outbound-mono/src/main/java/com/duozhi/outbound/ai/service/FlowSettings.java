package com.duozhi.outbound.ai.service;

import com.duozhi.outbound.common.exception.ApiError;
import java.util.*;

public final class FlowSettings {
    private FlowSettings() {}

    private static int integer(Map<?, ?> raw, String key, int initial, int min, int max) {
        Object v = raw.get(key);
        if (v == null) return initial;
        if (!(v instanceof Number num)
                || num.doubleValue() != num.intValue()
                || num.intValue() < min
                || num.intValue() > max) throw new ApiError(422, "流程设置数值越界: " + key);
        return num.intValue();
    }

    private static boolean bool(Map<?, ?> raw, String key, boolean initial) {
        Object v = raw.get(key);
        if (v == null) return initial;
        if (!(v instanceof Boolean b)) throw new ApiError(422, "流程开关值无效");
        return b;
    }

    public static Map<String, Object> normalize(Object source) {
        if (!(source instanceof Map<?, ?> raw)) throw new ApiError(422, "流程设置格式无效");
        var m = new LinkedHashMap<String, Object>();
        m.put("human_enabled", bool(raw, "human_enabled", true));
        m.put("block_machine", bool(raw, "block_machine", true));
        for (String key :
                List.of(
                        "handoff_hide_dialogue",
                        "handoff_disable_agent_recognition",
                        "handoff_disable_customer_recognition")) m.put(key, bool(raw, key, false));
        m.put("ring_seconds", integer(raw, "ring_seconds", 50, 1, 300));
        int max = integer(raw, "max_seconds", 2600, 1, 3600);
        m.put("max_seconds", max);
        m.put("response_ms", integer(raw, "response_ms", 8000, 300, 120000));
        m.put("timeout_count", integer(raw, "timeout_count", 1, 1, 20));
        m.put("frequency_hours", integer(raw, "frequency_hours", 0, 0, 720));
        m.put("frequency_count", integer(raw, "frequency_count", 1, 1, 1000));
        m.put("min_seconds", integer(raw, "min_seconds", 0, 0, max));
        m.put("record_delay", integer(raw, "record_delay", 0, 0, max));
        String at = Objects.toString(raw.get("record_at"), "answer");
        if (!Set.of("answer", "disabled").contains(at)) throw new ApiError(422, "录音时机无效");
        m.put("record_at", at);
        m.put("input_gain", integer(raw, "input_gain", 0, -12, 12));
        m.put("output_gain", integer(raw, "output_gain", 0, -12, 12));
        m.put("noise_gate", bool(raw, "noise_gate", false));
        m.put("filter_fillers", bool(raw, "filter_fillers", false));
        m.put("intent_threshold", integer(raw, "intent_threshold", 0, 0, 100));
        String prompt = Objects.toString(raw.get("timeout_prompt"), "您好，您还在听吗？"),
                fillers = Objects.toString(raw.get("fillers"), "嗯,呃,啊");
        if (prompt.length() > 1000 || fillers.length() > 1000)
            throw new ApiError(422, "超时提示或语气词过长");
        m.put("timeout_prompt", prompt);
        m.put("fillers", fillers);
        return m;
    }

    public static long cap(Map<String, Object> options, String key, long platformLimit) {
        return options.get(key) instanceof Number n
                ? Math.min(platformLimit, n.longValue())
                : platformLimit;
    }

    public static boolean timedOut(
            Map<String, Object> options, int count, int legacyClarifications) {
        return options.get("timeout_count") instanceof Number n
                ? count >= n.intValue()
                : count > legacyClarifications;
    }

    public static boolean overFrequency(Map<String, Object> opts, long prior) {
        return opts.get("frequency_hours") instanceof Number h
                && h.intValue() > 0
                && opts.get("frequency_count") instanceof Number n
                && prior >= n.longValue();
    }

    public static boolean humanAllowed(Map<String, Object> opts) {
        return !Boolean.FALSE.equals(opts.get("human_enabled"));
    }

    public static boolean machineBlocked(Map<String, Object> opts) {
        return !Boolean.FALSE.equals(opts.get("block_machine"));
    }

    public static boolean recording(Map<String, Object> opts, boolean enabled) {
        return enabled && !"disabled".equals(opts.get("record_at"));
    }

    public static int delay(Map<String, Object> opts) {
        return opts.get("record_delay") instanceof Number n ? n.intValue() : 0;
    }

    public static long responseMillis(Map<String, Object> opts, long fallback) {
        return opts.get("response_ms") instanceof Number n ? n.longValue() : fallback;
    }

    public static Map<String, Object> simulate(
            Map<String, Object> options, Map<String, Object> sample) {
        int ring = integer(sample, "ring", 1, 0, 86400),
                talk = integer(sample, "talk", 10, 0, 86400),
                wait = integer(sample, "wait_ms", 0, 0, 3600000),
                count = integer(sample, "silences", 0, 0, 10000),
                prior = integer(sample, "prior", 0, 0, 100000);
        boolean answered = ring < cap(options, "ring_seconds", 300),
                frequency = overFrequency(options, prior),
                admitted = !frequency;
        long duration = answered && admitted ? cap(options, "max_seconds", talk) : 0;
        var result = new LinkedHashMap<String, Object>();
        result.put("admitted", admitted);
        result.put("answered", admitted && answered);
        result.put("talk_seconds", duration);
        result.put("human", admitted && answered && humanAllowed(options));
        result.put(
                "intercepted_machine",
                admitted && answered && bool(sample, "machine", false) && machineBlocked(options));
        boolean expired = admitted && answered && wait >= responseMillis(options, 10000);
        result.put("wait_expired", expired);
        result.put("silence_ended", expired && timedOut(options, count, 2));
        result.put("frequency_block", frequency);
        int delay = delay(options);
        result.put(
                "recording_started",
                admitted && answered && recording(options, true) && duration > delay);
        result.put(
                "recording_seconds",
                admitted && answered && recording(options, true)
                        ? Math.max(0, duration - delay)
                        : 0);
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> from(Map<String, Object> policy) {
        return policy.get("flow_settings") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
    }
}
