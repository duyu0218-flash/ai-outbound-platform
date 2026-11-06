package com.duozhi.outbound.ai.service;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import java.util.*;

/**
 * Ordered conditions over the facts of this attempt. Events can only change local tags and grades.
 */
public final class OutcomeRules {
    private OutcomeRules() {}

    public static List<Map<String, Object>> definitions(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() > 100)
            throw new ApiError(422, "最多100个评级");
        var result = new ArrayList<Map<String, Object>>();
        var scores = new HashSet<Integer>();
        var names = new HashSet<String>();
        for (var item : list) {
            var v = com.duozhi.outbound.workspace.service.WorkspaceDocuments.map(item);
            int score =
                    com.duozhi.outbound.operations.service.OutboundRules.integer(
                            v.get("score"), 0, 100);
            String name =
                    com.duozhi.outbound.workspace.service.WorkspaceDocuments.text(
                            v, "name", 100, true);
            if (!scores.add(score) || !names.add(name)) throw new ApiError(422, "评级分值和名称不可重复");
            result.add(Map.of("score", score, "name", name));
        }
        return result;
    }

    private static final Set<String> counters =
            Set.of("affirm", "deny", "knowledge", "rounds", "machine");

    public static List<Map<String, Object>> normalize(Object input) {
        if (!(input instanceof List<?> list) || list.size() > 100)
            throw new ApiError(422, "最多配置 100 条规则");
        var out = new ArrayList<Map<String, Object>>();
        var ids = new HashSet<String>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> r)) throw new ApiError(422, "规则格式不正确");
            String id = str(r, "id"),
                    value = str(r, "value"),
                    action = str(r, "action"),
                    label = str(r, "label");
            if (!id.matches("[a-zA-Z0-9_-]{1,64}")
                    || !ids.add(id)
                    || !Set.of("grade", "tag", "increment", "set").contains(action)
                    || value.isBlank()
                    || value.length() > 100
                    || label.length() > 100) throw new ApiError(422, "规则标识、动作或标签不正确");
            if (!(r.get("conditions") instanceof List<?> conditions)
                    || conditions.isEmpty()
                    || conditions.size() > 12) throw new ApiError(422, "每条规则需要1至12个条件");
            if (Set.of("set", "increment").contains(action)
                    && !value.matches("[\\p{L}_][\\p{L}0-9_]{0,63}=.{1,80}"))
                throw new ApiError(422, "变量动作须使用变量名=值");
            var checks = new ArrayList<Map<String, Object>>();
            for (Object x : conditions) {
                if (!(x instanceof Map<?, ?> c)) throw new ApiError(422, "条件格式不正确");
                String field = str(c, "field"), op = str(c, "op"), v = str(c, "value");
                boolean valid =
                        field.equals("tags")
                                ? op.equals("any") && !v.isBlank() && v.length() <= 500
                                : (counters.contains(field)
                                                || field.matches(
                                                        "variable:[\\p{L}_][\\p{L}0-9_]{0,63}"))
                                        && Set.of("eq", "gte", "lte").contains(op)
                                        && v.matches("[0-9]{1,6}");
                if (!valid) throw new ApiError(422, "触发条件无效");
                checks.add(Map.of("field", field, "op", op, "value", v));
            }
            out.add(
                    Map.of(
                            "id",
                            id,
                            "label",
                            label,
                            "conditions",
                            checks,
                            "action",
                            action,
                            "value",
                            value,
                            "once",
                            Boolean.TRUE.equals(r.get("once"))));
        }
        return out;
    }

    public static void validateVariables(Map<String, Object> policy) {
        var initial = FlowVariables.initial(policy);
        for (String key : List.of("events", "hangup_rules"))
            for (var item : (List<?>) policy.getOrDefault(key, List.of())) {
                var rule = (Map<?, ?>) item;
                String action = str(rule, "action");
                if (Set.of("set", "increment").contains(action)) {
                    String[] assignment = str(rule, "value").split("=", 2);
                    Object value = FlowVariables.value(policy, assignment[0], assignment[1]);
                    if (action.equals("increment") && !(value instanceof Number))
                        throw new ApiError(422, "只有数值变量支持递增");
                }
                for (var raw : (List<?>) rule.get("conditions")) {
                    String field = str((Map<?, ?>) raw, "field");
                    if (field.startsWith("variable:")
                            && !(initial.get(field.substring(9)) instanceof Number))
                        throw new ApiError(422, "变量比较条件须引用已定义的数值变量");
                }
            }
    }

    private static String str(Map<?, ?> row, String key) {
        return Objects.toString(row.get(key), "").trim();
    }

    private static LinkedHashSet<String> strings(Object raw) {
        var set = new LinkedHashSet<String>();
        if (raw instanceof Collection<?> col) for (Object v : col) set.add(v.toString());
        return set;
    }

    private static boolean matches(Map<?, ?> rule, Map<String, Object> facts, Set<String> tags) {
        for (Object o : (List<?>) rule.get("conditions")) {
            var c = (Map<?, ?>) o;
            String field = str(c, "field"), v = str(c, "value");
            if (field.equals("tags")) {
                if (Arrays.stream(v.split("[,，;；]")).map(String::trim).noneMatch(tags::contains))
                    return false;
            } else {
                int actual = JsonSupport.integer(facts.get(field), 0), bound = Integer.parseInt(v);
                boolean ok =
                        switch (str(c, "op")) {
                            case "gte" -> actual >= bound;
                            case "lte" -> actual <= bound;
                            default -> actual == bound;
                        };
                if (!ok) return false;
            }
        }
        return true;
    }

    public static void turn(
            Map<String, Object> policy,
            Map<String, Object> state,
            String intent,
            boolean knowledge,
            boolean machine) {
        if (!configured(policy)) return;
        var facts = facts(state);
        inc(facts, "rounds");
        if ("affirm".equals(intent)) inc(facts, "affirm");
        if ("deny".equals(intent) || "rejected".equals(intent)) inc(facts, "deny");
        if (knowledge) inc(facts, "knowledge");
        if (machine) facts.put("machine", 1);
        state.put("rule_facts", facts);
        apply(policy, state, "events", false);
    }

    private static boolean configured(Map<String, Object> p) {
        return p.get("events") instanceof List<?> e && !e.isEmpty()
                || p.get("hangup_rules") instanceof List<?> r && !r.isEmpty();
    }

    private static void inc(Map<String, Object> facts, String key) {
        facts.put(key, JsonSupport.integer(facts.get(key), 0) + 1);
    }

    private static Map<String, Object> facts(Map<String, Object> state) {
        Map<String, Object> f = new LinkedHashMap<>();
        if (state.get("rule_facts") instanceof Map<?, ?> m)
            m.forEach((k, v) -> f.put(k.toString(), v));
        return f;
    }

    public static Map<String, Object> finish(
            Map<String, Object> policy, Map<String, Object> state) {
        var copy = new LinkedHashMap<>(state);
        if (configured(policy)) apply(policy, copy, "hangup_rules", true);
        String grade = Objects.toString(copy.get("rule_grade"), "");
        Object score = "";
        for (var raw : (List<?>) policy.getOrDefault("grade_definitions", List.of())) {
            var def = (Map<?, ?>) raw;
            if (grade.equals(def.get("name")) || grade.equals(def.get("score").toString())) {
                grade = def.get("name").toString();
                score = def.get("score");
                break;
            }
        }
        return Map.of(
                "grade",
                grade,
                "grade_score",
                score,
                "tags",
                copy.getOrDefault("rule_tags", List.of()),
                "facts",
                facts(copy),
                "matched",
                copy.getOrDefault("hangup_match", ""),
                "variables",
                FlowVariables.exported(
                        policy,
                        com.duozhi.outbound.workspace.service.WorkspaceDocuments.map(
                                copy.getOrDefault("flow_variables", Map.of()))));
    }

    private static void apply(
            Map<String, Object> policy, Map<String, Object> state, String key, boolean first) {
        if (!(policy.get(key) instanceof List<?> rules)) return;
        var tags = strings(state.get("rule_tags"));
        var done = strings(state.get("rule_events_done"));
        var f = facts(state);
        var vars =
                new LinkedHashMap<>(
                        com.duozhi.outbound.workspace.service.WorkspaceDocuments.map(
                                state.getOrDefault(
                                        "flow_variables", FlowVariables.initial(policy))));
        vars.forEach((name, value) -> f.put("variable:" + name, value));
        for (Object o : rules) {
            var r = (Map<?, ?>) o;
            String id = str(r, "id");
            boolean once = Boolean.TRUE.equals(r.get("once"));
            if (!first && once && done.contains(id) || !matches(r, f, tags)) continue;
            if (str(r, "action").equals("grade")) state.put("rule_grade", str(r, "value"));
            else if (str(r, "action").equals("tag")) tags.add(str(r, "value"));
            else {
                String[] assignment = str(r, "value").split("=", 2);
                if (assignment.length != 2 || !vars.containsKey(assignment[0])) continue;
                Object current = vars.get(assignment[0]);
                try {
                    Object next =
                            current instanceof Number
                                    ? new java.math.BigDecimal(assignment[1])
                                            .add(
                                                    str(r, "action").equals("increment")
                                                            ? new java.math.BigDecimal(
                                                                    current.toString())
                                                            : java.math.BigDecimal.ZERO)
                                    : current instanceof Boolean
                                            ? Boolean.parseBoolean(assignment[1])
                                            : assignment[1];
                    if (next instanceof Number n
                            && (Math.abs(n.doubleValue()) > 1000000000
                                    || new java.math.BigDecimal(n.toString()).scale() > 6))
                        continue;
                    vars.put(assignment[0], next);
                    f.put("variable:" + assignment[0], next);
                } catch (NumberFormatException invalid) {
                    continue;
                }
            }
            if (!str(r, "label").isEmpty()) tags.add(str(r, "label"));
            if (first) {
                state.put("hangup_match", id);
                break;
            }
            if (once) done.add(id);
        }
        state.put("rule_tags", new ArrayList<>(tags));
        state.put("rule_events_done", new ArrayList<>(done));
        state.put("flow_variables", vars);
    }
}
