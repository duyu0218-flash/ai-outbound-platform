package com.duozhi.outbound.marketing.outcome;

import static com.duozhi.outbound.marketing.common.OutcomeCodes.*;

import com.duozhi.outbound.marketing.outcome.CallSignals.Signals;
import com.duozhi.outbound.marketing.outcome.CallSignals.Turn;
import com.duozhi.outbound.marketing.outcome.OutcomeRules.Rule;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Three-layer call outcome: connection, answer quality (only for answered calls), intent level
 * (only for real conversations). Pure rules first; the optional LLM is consulted only where the
 * rules leave the answer open. Every decision carries a reason and the inputs it used.
 */
@Service
public class OutcomeClassifier {
    public static final List<String> CONNECTION =
            List.of(
                    CALL_ANSWERED,
                    CALL_NO_ANSWER,
                    CALL_BUSY,
                    CALL_REJECTED,
                    CALL_INVALID,
                    CALL_POWER_OFF,
                    CALL_SUSPENDED,
                    CALL_LINE_FAIL);
    public static final List<String> ANSWER =
            List.of(
                    ANS_CONVERSATION,
                    ANS_INSTANT_HANGUP,
                    ANS_SHORT_HANGUP,
                    ANS_VOICE_ASSISTANT,
                    ANS_VOICEMAIL,
                    ANS_NOT_OWNER,
                    ANS_REFUSE_HARD);
    public static final List<String> INTENT =
            List.of(INTENT_A, INTENT_B, INTENT_C, INTENT_D, INTENT_E);

    private final OutcomeLlm llm;

    public OutcomeClassifier(OutcomeLlm llm) {
        this.llm = llm;
    }

    public record Layer(String code, double confidence, String reason, String source) {
        Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("confidence", confidence);
            m.put("reason", reason);
            m.put("source", source);
            return m;
        }
    }

    public record Verdict(
            Layer connection,
            Layer answer,
            Layer intent,
            boolean countsAgainstUser,
            boolean needsReview,
            String reviewReason,
            Map<String, Object> evidence) {
        double confidence() {
            double c = connection.confidence();
            if (answer != null) c = Math.min(c, answer.confidence());
            if (intent != null) c = Math.min(c, intent.confidence());
            return c;
        }
    }

    public Verdict classify(Signals s, Rule rule) {
        List<Turn> customer = s.turns().stream().filter(Turn::customer).toList();
        List<Turn> valid = customer.stream().filter(t -> valid(t, rule)).toList();
        List<Map<String, Object>> llmTrace = new ArrayList<>();
        Layer connection = connection(s, rule, customer, valid);
        Layer answer = null, intent = null;
        if (CALL_ANSWERED.equals(connection.code())) {
            answer = answer(s, rule, customer, valid, llmTrace);
            if (ANS_CONVERSATION.equals(answer.code()))
                intent = intent(s, rule, customer, llmTrace);
        }
        double threshold = rule.num("review_confidence");
        List<String> review = new ArrayList<>();
        if (connection.confidence() < threshold) review.add("接通判定置信度低");
        if (answer != null && answer.confidence() < threshold) review.add("接听质量置信度低");
        if (intent != null && intent.confidence() < rule.num("intent_llm_confidence"))
            review.add("意向判定置信度低");
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("status", s.status());
        inputs.put("attempt", s.attempt());
        inputs.put("hangup_cause", s.lastError());
        inputs.put("sip_code", s.sipCode());
        inputs.put("hangup_side", s.hangupSide());
        inputs.put("ring_sec", round(s.ringSeconds()));
        inputs.put("talk_sec", round(s.talkSeconds()));
        inputs.put("answered_at", s.answeredAt() == null ? null : s.answeredAt().toString());
        inputs.put(
                "opening_done_sec",
                s.answeredAt() == null || s.openingDoneAt() == null
                        ? null
                        : round(CallSignals.seconds(s.answeredAt(), s.openingDoneAt())));
        inputs.put("early_media_text", clip(s.earlyMediaText(), 120));
        inputs.put("customer_turns", customer.size());
        inputs.put("valid_customer_turns", valid.size());
        inputs.put(
                "first_customer_text",
                customer.isEmpty() ? null : clip(customer.getFirst().text(), 200));
        inputs.put("first_customer_sec", firstCustomerSec(s, customer));
        inputs.put("flow_node", s.flowNode());
        inputs.put("analysis_result", s.analysisResult());
        inputs.put("analysis_intent", s.analysisIntent());
        Map<String, Object> layers = new LinkedHashMap<>();
        layers.put("connection", connection.view());
        layers.put("answer", answer == null ? null : answer.view());
        layers.put("intent", intent == null ? null : intent.view());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("rule_version", rule.version());
        evidence.put("inputs", inputs);
        evidence.put("layers", layers);
        evidence.put("missing_signals", s.missing());
        evidence.put("llm", llmTrace);
        return new Verdict(
                connection,
                answer,
                intent,
                !CALL_LINE_FAIL.equals(connection.code()),
                !review.isEmpty(),
                review.isEmpty() ? null : String.join("；", review),
                evidence);
    }

    // ---------------------------------------------------------------- layer 1: connection

    private Layer connection(Signals s, Rule rule, List<Turn> customer, List<Turn> valid) {
        String early = s.earlyMediaText();
        if (early != null && !early.isBlank()) {
            Layer tone = carrier(early, rule, "early_media");
            if (tone != null) return tone;
        }
        boolean answered = s.answeredAt() != null || "voicemail".equals(s.status());
        // Some carriers answer (200 OK) and then play the prompt tone; ASR captures it as the
        // first customer turn. Only trust that for very short exchanges.
        if (answered && !customer.isEmpty() && valid.size() <= 2) {
            String first = customer.getFirst().text();
            if (!hit(first, rule.list("assistant_phrases")).isPresent()) {
                Layer tone = carrier(first, rule, "first_turn_tone");
                if (tone != null) return tone;
            }
        }
        Integer sip = s.sipCode();
        if (sip != null && !answered) {
            if (rule.codes("sip", "invalid").contains(sip))
                return new Layer(CALL_INVALID, 0.95, "SIP " + sip + " 号码不存在", "sip");
            if (rule.codes("sip", "busy").contains(sip))
                return new Layer(CALL_BUSY, 0.95, "SIP " + sip + " 用户忙", "sip");
            if (rule.codes("sip", "rejected").contains(sip))
                return new Layer(CALL_REJECTED, 0.95, "SIP " + sip + " 用户拒接", "sip");
            if (rule.codes("sip", "no_answer").contains(sip))
                return new Layer(CALL_NO_ANSWER, 0.95, "SIP " + sip + " 无人接听", "sip");
            if (sip >= 500 && sip < 600)
                return new Layer(CALL_LINE_FAIL, 0.9, "SIP " + sip + " 线路或网关故障", "sip");
        }
        String cause = s.lastError() == null ? "" : s.lastError().strip().toUpperCase(Locale.ROOT);
        if (!answered && !cause.isEmpty()) {
            if (contains(rule.list("hangup_causes", "invalid"), cause))
                return new Layer(CALL_INVALID, 0.9, "挂断原因 " + cause, "hangup_cause");
            if (contains(rule.list("hangup_causes", "busy"), cause))
                return new Layer(CALL_BUSY, 0.9, "挂断原因 " + cause, "hangup_cause");
            if (contains(rule.list("hangup_causes", "rejected"), cause))
                return new Layer(CALL_REJECTED, 0.9, "挂断原因 " + cause, "hangup_cause");
            if (contains(rule.list("hangup_causes", "power_off"), cause))
                return new Layer(
                        CALL_POWER_OFF, 0.75, "挂断原因 " + cause + "（不在服务区/关机）", "hangup_cause");
            if (contains(rule.list("hangup_causes", "line_fail"), cause))
                return new Layer(
                        CALL_LINE_FAIL, 0.9, "挂断原因 " + cause + "（线路故障，不计入用户）", "hangup_cause");
            if (contains(rule.list("hangup_causes", "no_answer"), cause))
                return new Layer(CALL_NO_ANSWER, 0.9, "挂断原因 " + cause, "hangup_cause");
        }
        if (answered) return new Layer(CALL_ANSWERED, 1.0, "已应答", "answer_time");
        if (!customer.isEmpty() && "completed".equals(s.status()))
            return new Layer(CALL_ANSWERED, 0.8, "无应答时间但有客户转写", "speech");
        Double ring = s.ringSeconds();
        if (ring != null && ring >= rule.num("ring_timeout_sec"))
            return new Layer(CALL_NO_ANSWER, 0.85, "振铃 " + round(ring) + " 秒超时", "ring_time");
        if ("no_answer".equals(s.status()))
            return new Layer(CALL_NO_ANSWER, 0.85, "通话状态无人接听", "status");
        if ("busy".equals(s.status())) return new Layer(CALL_BUSY, 0.85, "通话状态忙", "status");
        if (ring != null && ring <= rule.num("quick_reject_sec"))
            return new Layer(CALL_REJECTED, 0.75, "振铃 " + round(ring) + " 秒即结束，疑似拒接", "ring_time");
        if ("failed".equals(s.status()))
            return new Layer(
                    CALL_LINE_FAIL,
                    0.6,
                    "呼叫失败且无明确原因" + (cause.isEmpty() ? "" : "：" + cause),
                    "status");
        return new Layer(CALL_NO_ANSWER, 0.6, "未应答", "status");
    }

    private Layer carrier(String text, Rule rule, String source) {
        String t = norm(text);
        for (var kind :
                List.of(
                        Map.entry("invalid", CALL_INVALID),
                        Map.entry("suspended", CALL_SUSPENDED),
                        Map.entry("power_off", CALL_POWER_OFF),
                        Map.entry("busy", CALL_BUSY))) {
            var h = hit(t, rule.list("carrier_phrases", kind.getKey()));
            if (h.isPresent())
                return new Layer(kind.getValue(), 0.9, "运营商提示音命中「" + h.get() + "」", source);
        }
        return null;
    }

    // ---------------------------------------------------------------- layer 2: answer quality

    private Layer answer(
            Signals s,
            Rule rule,
            List<Turn> customer,
            List<Turn> valid,
            List<Map<String, Object>> trace) {
        String opening =
                norm(String.join(" ", customer.stream().limit(2).map(Turn::text).toList()));
        String all = norm(String.join(" ", customer.stream().map(Turn::text).toList()));
        var assistant = hit(opening, rule.list("assistant_phrases"));
        if (assistant.isPresent())
            return new Layer(
                    ANS_VOICE_ASSISTANT, 0.9, "首轮命中语音助手话术「" + assistant.get() + "」", "phrase");
        var voicemail = hit(opening, rule.list("voicemail_phrases"));
        if ("voicemail".equals(s.status()) || voicemail.isPresent())
            return new Layer(
                    ANS_VOICEMAIL,
                    voicemail.isPresent() ? 0.9 : 0.85,
                    voicemail.map(v -> "命中语音信箱话术「" + v + "」").orElse("通话状态为语音信箱"),
                    voicemail.isPresent() ? "phrase" : "status");
        if (!opening.isEmpty() && rule.flag("assistant_llm_enabled") && llm.available()) {
            var j = llm.assistant(clip(opening, 300));
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("task", "voice_assistant");
            t.put("result", j.map(OutcomeLlm.Judgement::label).orElse("no_answer"));
            t.put("confidence", j.map(OutcomeLlm.Judgement::confidence).orElse(null));
            t.put("reason", j.map(OutcomeLlm.Judgement::reason).orElse(null));
            trace.add(t);
            if (j.isPresent()
                    && "assistant".equals(j.get().label())
                    && j.get().confidence() >= rule.num("assistant_llm_confidence"))
                return new Layer(
                        ANS_VOICE_ASSISTANT,
                        j.get().confidence(),
                        "大模型判定为语音助手：" + j.get().reason(),
                        "llm");
        }
        // A refusal inside a real dialogue (2+ valid turns) is an intent level D, not a hang-up.
        var refuse = hit(all, rule.list("refuse_hard_phrases"));
        if (valid.size() < 2 && (refuse.isPresent() || "stop_contact".equals(s.analysisResult())))
            return new Layer(
                    ANS_REFUSE_HARD,
                    refuse.isPresent() ? 0.9 : 0.85,
                    refuse.map(r -> "客户明确拒绝「" + r + "」").orElse("通话分析结论为要求停止联系"),
                    refuse.isPresent() ? "phrase" : "callanalysis");
        var notOwner = hit(all, rule.list("not_owner_phrases"));
        if (notOwner.isPresent() || "wrong_person".equals(s.analysisResult()))
            return new Layer(
                    ANS_NOT_OWNER,
                    notOwner.isPresent() ? 0.85 : 0.8,
                    notOwner.map(r -> "非机主「" + r + "」").orElse("通话分析结论为非本人"),
                    notOwner.isPresent() ? "phrase" : "callanalysis");
        Double talk = s.talkSeconds();
        boolean remote = !"platform".equals(s.hangupSide());
        if (talk != null && talk <= rule.num("instant_hangup_sec") && remote)
            return new Layer(
                    ANS_INSTANT_HANGUP,
                    s.hangupSide() == null ? 0.8 : 0.95,
                    "接通 "
                            + round(talk)
                            + " 秒内挂断"
                            + (s.hangupSide() == null ? "（挂断方未知，按对方挂断处理）" : ""),
                    "talk_time");
        if (valid.isEmpty()
                && remote
                && s.openingDoneAt() != null
                && s.end() != null
                && s.end().isBefore(s.openingDoneAt()))
            return new Layer(ANS_INSTANT_HANGUP, 0.85, "开场白未播完客户即挂断且无有效回应", "opening");
        if (valid.size() >= 2)
            return new Layer(ANS_CONVERSATION, 0.9, "客户有效发言 " + valid.size() + " 轮", "turns");
        if (talk != null && talk <= rule.num("short_hangup_sec"))
            return new Layer(
                    ANS_SHORT_HANGUP,
                    0.85,
                    "接通 " + round(talk) + " 秒，客户有效发言 " + valid.size() + " 轮",
                    "talk_time");
        return new Layer(
                ANS_SHORT_HANGUP,
                talk == null ? 0.6 : 0.75,
                "客户有效发言不足 2 轮（"
                        + valid.size()
                        + " 轮）"
                        + (talk == null ? "，通话时长未知" : "，通话 " + round(talk) + " 秒"),
                "turns");
    }

    // ---------------------------------------------------------------- layer 3: intent

    private Layer intent(
            Signals s, Rule rule, List<Turn> customer, List<Map<String, Object>> trace) {
        String all = norm(String.join(" ", customer.stream().map(Turn::text).toList()));
        List<String> refusals = new ArrayList<>(rule.list("intent", "D"));
        refusals.addAll(rule.list("refuse_hard_phrases"));
        var d = hit(all, refusals);
        if (d.isPresent()) return new Layer(INTENT_D, 0.9, "命中拒绝关键词「" + d.get() + "」", "keyword");
        String node = s.flowNode();
        for (String level : List.of("A", "B", "C", "D"))
            if (node != null && rule.list("intent", "nodes", level).contains(node))
                return new Layer("INTENT_" + level, 0.9, "命中话术节点 " + node, "script_node");
        var a = hit(all, rule.list("intent", "A"));
        if (a.isPresent()) return new Layer(INTENT_A, 0.85, "命中高意向关键词「" + a.get() + "」", "keyword");
        Object mapped =
                s.analysisResult() == null
                        ? null
                        : rule.map("intent", "analysis").get(s.analysisResult());
        if (mapped != null && List.of("A", "B", "C", "D", "E").contains(mapped.toString()))
            return new Layer(
                    "INTENT_" + mapped,
                    s.analysisReviewed() ? 0.95 : 0.8,
                    "通话分析结论 " + s.analysisResult() + (s.analysisReviewed() ? "（已人工复核）" : ""),
                    "callanalysis");
        var b = hit(all, rule.list("intent", "B"));
        if (b.isPresent()) return new Layer(INTENT_B, 0.8, "命中兴趣关键词「" + b.get() + "」", "keyword");
        var c = hit(all, rule.list("intent", "C"));
        if (c.isPresent()) return new Layer(INTENT_C, 0.8, "命中暂无需要关键词「" + c.get() + "」", "keyword");
        if (rule.flag("intent_llm_enabled") && llm.available()) {
            StringBuilder dialogue = new StringBuilder();
            for (Turn t : s.turns())
                dialogue.append(t.customer() ? "客户: " : "坐席: ").append(t.text()).append('\n');
            var j = llm.intent(clip(dialogue.toString(), 3000));
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("task", "intent");
            t.put("result", j.map(OutcomeLlm.Judgement::label).orElse("no_answer"));
            t.put("confidence", j.map(OutcomeLlm.Judgement::confidence).orElse(null));
            t.put("reason", j.map(OutcomeLlm.Judgement::reason).orElse(null));
            trace.add(t);
            if (j.isPresent())
                return new Layer(
                        "INTENT_" + j.get().label(),
                        j.get().confidence(),
                        "大模型判定：" + j.get().reason(),
                        "llm");
        }
        return new Layer(INTENT_E, 0.5, "规则未命中且大模型未启用，待人工判断", "fallback");
    }

    // ---------------------------------------------------------------- helpers

    private boolean valid(Turn t, Rule rule) {
        if (t.confidence() != null && t.confidence() < rule.num("min_asr_confidence")) return false;
        String text = norm(t.text());
        for (String filler : rule.list("filler_words")) text = text.replace(norm(filler), "");
        return text.codePointCount(0, text.length()) >= rule.num("min_valid_chars");
    }

    /** Removes whitespace and punctuation so phrase matching survives ASR formatting. */
    static String norm(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\p{P}\\p{S}]", "");
    }

    static Optional<String> hit(String text, List<String> phrases) {
        String t = norm(text);
        if (t.isEmpty()) return Optional.empty();
        return phrases.stream().filter(p -> !norm(p).isEmpty() && t.contains(norm(p))).findFirst();
    }

    private static boolean contains(List<String> list, String cause) {
        for (String c : list) if (cause.contains(c.toUpperCase(Locale.ROOT))) return true;
        return false;
    }

    private static Double firstCustomerSec(Signals s, List<Turn> customer) {
        if (customer.isEmpty()) return null;
        Turn f = customer.getFirst();
        if (f.startMs() != null) return round(f.startMs() / 1000.0);
        if (s.answeredAt() != null && f.at() != null)
            return round(CallSignals.seconds(s.answeredAt(), f.at()));
        return null;
    }

    static Double round(Double v) {
        return v == null ? null : Math.round(v * 10) / 10.0;
    }

    static String clip(String v, int max) {
        if (v == null) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }
}
