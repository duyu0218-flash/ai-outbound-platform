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
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> from(Map<String, Object> policy) {
        return policy.get("flow_settings") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
    }
}
