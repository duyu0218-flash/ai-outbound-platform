package com.duozhi.outbound.ai.service;

import com.duozhi.outbound.common.exception.ApiError;
import java.util.*;
import java.util.regex.*;

public final class ScriptIntents {
    private ScriptIntents() {}

    private static final Set<String> outcomes =
            Set.of(
                    "affirm",
                    "deny",
                    "rejected",
                    "continue",
                    "wait",
                    "machine_candidate",
                    "interested",
                    "question");

    public static List<Map<String, String>> normalize(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() > 50)
            throw new ApiError(422, "话术最多配置50条意图");
        var result = new ArrayList<Map<String, String>>();
        var names = new HashSet<String>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rule)) throw new ApiError(422, "意图格式不正确");
            String name = Objects.toString(rule.get("name"), "").trim(),
                    target = Objects.toString(rule.get("target"), "");
            String words = Objects.toString(rule.get("keywords"), "").trim(),
                    regex = Objects.toString(rule.get("regex"), "").trim();
            if (name.isEmpty()
                    || name.length() > 80
                    || !names.add(name)
                    || !outcomes.contains(target)
                    || words.length() > 4000
                    || regex.length() > 256
                    || (words.isEmpty() && regex.isEmpty()))
                throw new ApiError(422, "请填写不重复的意图名称、有效结果和关键词或正则");
            if (!regex.isEmpty())
                try {
                    Pattern.compile(regex);
                } catch (PatternSyntaxException e) {
                    throw new ApiError(422, "意图“" + name + "”的正则格式不正确");
                }
            result.add(Map.of("name", name, "target", target, "keywords", words, "regex", regex));
        }
        return result;
    }

    // Cap all regex navigation across this turn, including lookarounds and failed alternatives.
    private static final class Limit extends RuntimeException {}

    private record Input(String text, int start, int end, int[] budget) implements CharSequence {
        public int length() {
            return end - start;
        }

        public char charAt(int i) {
            Objects.checkIndex(i, length());
            if (--budget[0] < 0) throw new Limit();
            return text.charAt(start + i);
        }

        public CharSequence subSequence(int a, int b) {
            Objects.checkFromToIndex(a, b, length());
            return new Input(text, start + a, start + b, budget);
        }

        public String toString() {
            return text.substring(start, end);
        }
    }

    public static String classify(String text, Map<String, Object> policy) {
        String baseline = DialogueRules.classify(text);
        if (!Set.of("continue", "unclear").contains(baseline)
                || text.isBlank()
                || !(policy.get("intent_rules") instanceof List<?> rules)) return baseline;
        String sample = DialogueRules.normalized(text.substring(0, Math.min(512, text.length())));
        var input = new Input(sample, 0, sample.length(), new int[] {80000});
        for (Object value : rules) {
            if (!(value instanceof Map<?, ?> rule)) continue;
            String target = Objects.toString(rule.get("target"), "");
            if (!outcomes.contains(target)) continue;
            for (String word : Objects.toString(rule.get("keywords"), "").split("[;；，,\\n]"))
                if (!word.isBlank() && DialogueRules.keyword(sample, word.trim())) return target;
            String regex = Objects.toString(rule.get("regex"), "");
            try {
                if (!regex.isEmpty() && Pattern.compile(regex).matcher(input).find()) return target;
            } catch (Limit exhausted) {
                return baseline;
            }
        }
        return baseline;
    }
}
