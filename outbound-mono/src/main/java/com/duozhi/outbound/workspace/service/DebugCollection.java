package com.duozhi.outbound.workspace.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.ai.service.DialogueRules;
import com.duozhi.outbound.common.support.JsonSupport;
import java.util.*;

/** Pure rehearsal of collection validation and confirmation; never writes business records. */
public final class DebugCollection {
    private DebugCollection() {}

    public static Map<String, Object> step(
            Map<String, Object> snapshot,
            Map<String, Object> state,
            Map<String, Object> values,
            String input,
            String intent,
            int turn) {
        var policy = map(snapshot.get("policy"));
        var slots = (List<?>) policy.getOrDefault("slots", List.of());
        var collected = map(state.getOrDefault("collected", Map.of()));
        var trace = new ArrayList<String>();
        var pending = map(state.getOrDefault("pending_slot", Map.of()));
        String active = Objects.toString(state.get("active_slot"), "");
        String reply = "",
                action = "speak",
                node = active.isBlank() ? "opening" : "collect:" + active;
        if (!pending.isEmpty()) {
            if (intent.equals("affirm")) {
                String key = pending.get("key").toString();
                values.put(key, pending.get("value"));
                collected.put(
                        key, JsonSupport.map("value", pending.get("value"), "confirmed", true));
                state.remove("pending_slot");
                state.remove("active_slot");
                active = "";
                trace.add("客户复述确认通过：" + key);
            } else if (intent.equals("deny")) {
                state.remove("pending_slot");
                reply = "请重新说明这项信息。";
                trace.add("客户否认，保留待采集字段");
            } else reply = "您说的是" + pending.get("value") + "，对吗？";
        } else if (!active.isBlank()) {
            for (Object raw : slots) {
                var slot = map(raw);
                if (!active.equals(slot.get("key"))) continue;
                var rule =
                        JsonSupport.<String, Object>map(
                                "key",
                                active,
                                "min_length",
                                1,
                                "max_length",
                                500,
                                "max_retries",
                                2,
                                "missing_action",
                                "end",
                                "retry_prompt",
                                "");
                for (Object r : (List<?>) snapshot.getOrDefault("slot_rules", List.of()))
                    if (active.equals(map(r).get("key"))) rule.putAll(map(r));
                String invalid = SlotRules.invalid(rule, slot, input);
                if (!invalid.isBlank()) {
                    var retries = map(state.getOrDefault("slot_retries", Map.of()));
                    int count = number(retries.get(active)) + 1;
                    retries.put(active, count);
                    state.put("slot_retries", retries);
                    if (count > number(rule.get("max_retries"))) {
                        String missing = Objects.toString(rule.get("missing_action"), "end");
                        trace.add("超过重问次数，字段缺失处理：" + missing);
                        if (missing.equals("skip")) {
                            collected.put(
                                    active,
                                    JsonSupport.map(
                                            "value", "", "confirmed", true, "missing", true));
                            values.put(active, "");
                            state.remove("active_slot");
                            active = "";
                        } else {
                            reply = "这项信息需要人工进一步确认。";
                            action = missing.equals("handoff") ? "handoff" : "hangup";
                        }
                    } else {
                        String prompt = Objects.toString(rule.get("retry_prompt"), "");
                        reply = prompt.isBlank() ? invalid + "。" + slot.get("question") : prompt;
                        trace.add("字段校验失败，第" + count + "次重问");
                    }
                } else {
                    String accepted = input.trim();
                    if ("choice".equals(slot.get("kind")))
                        for (Object choice : (List<?>) slot.get("choices"))
                            if (DialogueRules.keyword(accepted, choice.toString())) {
                                accepted = choice.toString();
                                break;
                            }
                    if (Boolean.TRUE.equals(slot.get("confirm"))) {
                        state.put(
                                "pending_slot", JsonSupport.map("key", active, "value", accepted));
                        reply = "您说的是" + accepted + "，对吗？";
                        trace.add("字段校验通过，等待客户复述确认");
                    } else {
                        values.put(active, accepted);
                        collected.put(
                                active, JsonSupport.map("value", accepted, "confirmed", true));
                        state.remove("active_slot");
                        active = "";
                        trace.add("字段校验通过并记录");
                    }
                }
                break;
            }
        }
        if (reply.isBlank())
            for (Object raw : slots) {
                var slot = map(raw);
                String key = slot.get("key").toString();
                if (Boolean.TRUE.equals(slot.get("required"))
                        && !Boolean.TRUE.equals(
                                map(collected.getOrDefault(key, Map.of())).get("confirmed"))) {
                    state.put("active_slot", key);
                    reply = Objects.toString(slot.get("question"), "");
                    node = "collect:" + key;
                    break;
                }
            }
        if (reply.isBlank()) {
            if (turn == 0 && slots.isEmpty()) {
                reply = Objects.toString(snapshot.get("opening"), "");
                node = "opening";
            } else {
                reply = Objects.toString(snapshot.get("ending"), "感谢，信息已记录。");
                if (reply.isBlank()) reply = "感谢，信息已记录。";
                node = "ending";
                action = "hangup";
                trace.add("采集完成，模拟结束通话");
            }
        }
        state.put("collected", collected);
        return JsonSupport.map(
                "reply",
                reply,
                "action",
                action,
                "node",
                node,
                "variables",
                values,
                "trace",
                trace,
                "collected",
                collected,
                "pending_slot",
                state.getOrDefault("pending_slot", Map.of()));
    }
}
