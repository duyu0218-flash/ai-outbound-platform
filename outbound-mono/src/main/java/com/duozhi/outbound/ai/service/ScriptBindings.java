package com.duozhi.outbound.ai.service;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.voiceai.service.VoiceAiProfiles;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class ScriptBindings {
    private ScriptBindings() {}

    public static Map<String, Object> from(Map<String, Object> policy) {
        return com.duozhi.outbound.workspace.service.WorkspaceDocuments.map(
                policy.getOrDefault("bindings", Map.of()));
    }

    private static List<String> strings(Map<?, ?> m, String key, int count, int length) {
        if (m.get(key) == null) return List.of();
        if (!(m.get(key) instanceof List<?> items) || items.size() > count)
            throw new ApiError(422, "配置条目超过上限");
        var values = new LinkedHashSet<String>();
        for (Object raw : items) {
            if (!(raw instanceof String s) || s.isBlank() || s.trim().length() > length)
                throw new ApiError(422, "配置条目格式错误");
            values.add(s.trim());
        }
        return List.copyOf(values);
    }

    public static Map<String, Object> normalize(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) throw new ApiError(422, "话术资源设置无效");
        var result = new LinkedHashMap<String, Object>();
        var blocked = strings(m, "blocked_hashes", 2000, 64);
        if (blocked.stream().anyMatch(k -> !k.matches("[a-f0-9]{64}")))
            throw new ApiError(422, "黑名单指纹无效");
        result.put("blocked_hashes", blocked);
        result.put("openings", strings(m, "openings", 20, 240));
        String combo = Objects.toString(m.get("service_combo"), "inherit");
        if (!Set.of("inherit", "legacy").contains(combo)
                && VoiceAiProfiles.PROFILES.stream().noneMatch(v -> v.id().equals(combo)))
            throw new ApiError(422, "不支持的语音组合");
        result.put("service_combo", combo);
        if (m.get("line_id") instanceof Number n && n.longValue() > 0)
            result.put("line_id", n.longValue());
        String callback = Objects.toString(m.get("callback_mode"), "inherit");
        if (!Set.of("inherit", "off").contains(callback)) throw new ApiError(422, "接口策略无效");
        result.put("callback_mode", callback);
        return result;
    }

    public static boolean blocked(Map<String, Object> policy, Object phone) {
        var hashes = from(policy).get("blocked_hashes");
        if (!(hashes instanceof List<?> list) || list.isEmpty()) return false;
        try {
            String number = phone.toString().replaceAll("[^0-9]", "");
            return list.contains(
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(number.getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
