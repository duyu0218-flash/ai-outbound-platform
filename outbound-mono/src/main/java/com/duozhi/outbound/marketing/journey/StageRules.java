package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.marketing.common.OutcomeCodes;
import java.util.*;

/**
 * Lifecycle stage of a profile: the first rule that matches wins, otherwise {@link #NEW}. A rule
 * matches when the profile has seen any of {@code anyCodes} (if given), none of {@code noneCodes}
 * (if given) and at least {@code minTouches} sends and calls (if given). Tenants may override the
 * list in mk_policy_config.stage_rules.
 */
public final class StageRules {
    private StageRules() {}

    public static final String NEW = "待挖掘",
            POTENTIAL = "高潜",
            REACHED = "已触达",
            ACTIVE = "活跃",
            OPPORTUNITY = "商机",
            CONVERTED = "已转化",
            SILENT = "沉默",
            UNREACHABLE = "不可触达";

    public static final List<String> STAGES =
            List.of(NEW, POTENTIAL, REACHED, ACTIVE, OPPORTUNITY, CONVERTED, SILENT, UNREACHABLE);

    public static final List<Map<String, Object>> DEFAULT =
            List.of(
                    rule(UNREACHABLE, "anyCodes", OutcomeCodes.BLOCKING.stream().sorted().toList()),
                    rule(
                            CONVERTED,
                            "anyCodes",
                            List.of(OutcomeCodes.CONVERTED, OutcomeCodes.LINK_CONVERTED)),
                    rule(
                            OPPORTUNITY,
                            "anyCodes",
                            List.of(OutcomeCodes.INTENT_A, OutcomeCodes.INTENT_B)),
                    rule(
                            ACTIVE,
                            "anyCodes",
                            List.of(OutcomeCodes.LINK_CLICKED, OutcomeCodes.ANS_CONVERSATION)),
                    rule(POTENTIAL, "anyCodes", List.of(OutcomeCodes.INTENT_C)),
                    rule(SILENT, "minTouches", 3),
                    rule(
                            REACHED,
                            "anyCodes",
                            List.of(OutcomeCodes.SMS_DELIVERED, OutcomeCodes.CALL_ANSWERED)));

    private static Map<String, Object> rule(String stage, String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage);
        m.put(key, value);
        return Collections.unmodifiableMap(m);
    }

    public static String evaluate(List<Map<String, Object>> rules, Set<String> codes, int touches) {
        for (Map<String, Object> r : rules) {
            if (r.get("anyCodes") instanceof Collection<?> any
                    && any.stream().noneMatch(c -> codes.contains(String.valueOf(c)))) continue;
            if (r.get("noneCodes") instanceof Collection<?> none
                    && none.stream().anyMatch(c -> codes.contains(String.valueOf(c)))) continue;
            if (r.get("minTouches") instanceof Number n && touches < n.intValue()) continue;
            return String.valueOf(r.get("stage"));
        }
        return NEW;
    }

    public static void validate(List<Map<String, Object>> rules) {
        if (rules.size() > 50) throw new ApiError(422, "阶段规则过多");
        for (Object o : rules) {
            if (!(o instanceof Map<?, ?> r)) throw new ApiError(422, "阶段规则格式无效");
            if (!STAGES.contains(String.valueOf(r.get("stage"))))
                throw new ApiError(422, "未知阶段：" + r.get("stage"));
            for (String key : List.of("anyCodes", "noneCodes")) {
                Object v = r.get(key);
                if (v == null) continue;
                if (!(v instanceof Collection<?> c)) throw new ApiError(422, key + " 必须是结果码数组");
                for (Object code : c)
                    if (!OutcomeCodes.ALL.contains(String.valueOf(code)))
                        throw new ApiError(422, "未知触达结果码：" + code);
            }
            Object min = r.get("minTouches");
            if (min != null && !(min instanceof Number))
                throw new ApiError(422, "minTouches 必须是数字");
            if (r.get("anyCodes") == null && r.get("noneCodes") == null && min == null)
                throw new ApiError(422, "阶段规则至少需要一个条件");
        }
    }
}
