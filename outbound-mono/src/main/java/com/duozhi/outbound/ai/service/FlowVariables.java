package com.duozhi.outbound.ai.service;

import com.duozhi.outbound.common.exception.ApiError;
import java.math.BigDecimal;
import java.util.*;

/** Typed attempt-local variables, separate from customer data collected in slots. */
public final class FlowVariables {
    private FlowVariables() {}

    public static List<Map<String, Object>> normalize(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() > 100)
            throw new ApiError(422, "最多配置100个自定义变量");
        var result = new ArrayList<Map<String, Object>>();
        var names = new HashSet<String>();
        for (var item : list) {
            if (!(item instanceof Map<?, ?> m)) throw new ApiError(422, "变量格式无效");
            String name = Objects.toString(m.get("name"), "").trim(),
                    type = Objects.toString(m.get("type"), "STR");
            if (!name.matches("[\\p{L}_][\\p{L}0-9_]{0,63}")
                    || !names.add(name)
                    || !Set.of("NUM", "STR", "BOOL").contains(type)
                    || !(m.get("export") instanceof Boolean))
                throw new ApiError(422, "变量名称、类型或导出选项无效");
            Object value = m.get("initial");
            try {
                value =
                        switch (type) {
                            case "NUM" -> {
                                var n = new BigDecimal(Objects.toString(value, "0"));
                                if (n.abs().compareTo(new BigDecimal("1000000000")) > 0
                                        || n.scale() > 6) throw new IllegalArgumentException();
                                yield n;
                            }
                            case "BOOL" -> {
                                if (!Set.of("true", "false")
                                        .contains(Objects.toString(value, "false")))
                                    throw new IllegalArgumentException();
                                yield Boolean.valueOf(Objects.toString(value, "false"));
                            }
                            default -> {
                                String s = Objects.toString(value, "");
                                if (s.length() > 2000) throw new IllegalArgumentException();
                                yield s;
                            }
                        };
            } catch (Exception e) {
                throw new ApiError(422, "变量初始值不符合类型");
            }
            result.add(
                    Map.of(
                            "name",
                            name,
                            "type",
                            type,
                            "initial",
                            value,
                            "export",
                            m.get("export")));
        }
        return result;
    }

    public static Object value(Map<String, Object> policy, String name, Object raw) {
        for (var item : (List<?>) policy.getOrDefault("custom_variables", List.of())) {
            var def = (Map<?, ?>) item;
            if (name.equals(def.get("name")))
                return normalize(
                                List.of(
                                        Map.of(
                                                "name",
                                                name,
                                                "type",
                                                def.get("type"),
                                                "initial",
                                                raw,
                                                "export",
                                                false)))
                        .getFirst()
                        .get("initial");
        }
        throw new ApiError(422, "引用了未定义的变量：" + name);
    }

    public static Map<String, Object> initial(Map<String, Object> policy) {
        var values = new LinkedHashMap<String, Object>();
        for (var raw : (List<?>) policy.getOrDefault("custom_variables", List.of())) {
            var v = (Map<?, ?>) raw;
            values.put(v.get("name").toString(), v.get("initial"));
        }
        return values;
    }

    public static Map<String, Object> exported(
            Map<String, Object> policy, Map<String, Object> values) {
        var result = new LinkedHashMap<String, Object>();
        for (var raw : (List<?>) policy.getOrDefault("custom_variables", List.of())) {
            var v = (Map<?, ?>) raw;
            String name = v.get("name").toString();
            if (Boolean.TRUE.equals(v.get("export")))
                result.put(name, values.getOrDefault(name, v.get("initial")));
        }
        return result;
    }
}
