package com.duozhi.outbound.workspace.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import java.util.*;

public final class SlotRules {
    private SlotRules() {}

    public static List<Map<String, Object>> normalize(Object raw, List<?> slots) {
        if (!(raw instanceof List<?> list) || list.size() > 50)
            throw new ApiError(422, "最多50项字段校验规则");
        var result = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<String>();
        for (Object v : list) {
            var r = map(v);
            String key = text(r, "key", 64, true);
            if (!seen.add(key) || slots.stream().noneMatch(s -> key.equals(map(s).get("key"))))
                throw new ApiError(422, "字段规则未关联采集字段或重复：" + key);
            int min = number(r.getOrDefault("min_length", 1)),
                    max = number(r.getOrDefault("max_length", 500)),
                    retry = number(r.getOrDefault("max_retries", 2));
            if (min < 0 || max < min || max > 500 || retry < 0 || retry > 3)
                throw new ApiError(422, "长度与重问次数超出范围");
            String action = Objects.toString(r.get("missing_action"), "end");
            if (!Set.of("end", "handoff", "skip").contains(action))
                throw new ApiError(422, "字段缺失处理不支持");
            var rule =
                    JsonSupport.<String, Object>map(
                            "key",
                            key,
                            "min_length",
                            min,
                            "max_length",
                            max,
                            "max_retries",
                            retry,
                            "missing_action",
                            action,
                            "retry_prompt",
                            text(r, "retry_prompt", 500, false));
            for (String bound : List.of("min_value", "max_value"))
                if (r.get(bound) != null && !r.get(bound).toString().isBlank())
                    try {
                        rule.put(bound, new java.math.BigDecimal(r.get(bound).toString()));
                    } catch (Exception e) {
                        throw new ApiError(422, "数值范围格式不正确");
                    }
            if (rule.get("min_value") != null
                    && rule.get("max_value") != null
                    && new java.math.BigDecimal(rule.get("min_value").toString())
                                    .compareTo(
                                            new java.math.BigDecimal(
                                                    rule.get("max_value").toString()))
                            > 0) throw new ApiError(422, "数值下限不得高于上限");
            result.add(rule);
        }
        return result;
    }

    public static String invalid(Map<String, Object> rule, Map<String, Object> slot, String text) {
        int length = text.codePointCount(0, text.length());
        if (length < number(rule.getOrDefault("min_length", 1))
                || length > number(rule.getOrDefault("max_length", 500))) return "回答长度不符合要求";
        String kind = Objects.toString(slot.get("kind"), "text");
        if (Set.of("integer", "digits").contains(kind) && !text.matches("[0-9]{1,20}"))
            return "请用数字回答";
        if (kind.equals("date"))
            try {
                java.time.LocalDate.parse(text);
            } catch (Exception e) {
                return "请按年-月-日说明日期";
            }
        if (kind.equals("choice")
                && ((List<?>) slot.getOrDefault("choices", List.of()))
                                .stream()
                                        .filter(
                                                c ->
                                                        com.duozhi.outbound.ai.service.DialogueRules
                                                                .keyword(text, c.toString()))
                                        .count()
                        != 1) return "请从可选值中确认一个";
        if (kind.equals("integer"))
            try {
                var n = new java.math.BigDecimal(text);
                if (rule.get("min_value") != null
                                && n.compareTo(
                                                new java.math.BigDecimal(
                                                        rule.get("min_value").toString()))
                                        < 0
                        || rule.get("max_value") != null
                                && n.compareTo(
                                                new java.math.BigDecimal(
                                                        rule.get("max_value").toString()))
                                        > 0) return "回答数值不在允许范围";
            } catch (Exception e) {
                return "请用数字回答";
            }
        return "";
    }
}
