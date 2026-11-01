package com.duozhi.outbound.marketing.outcome;

import com.duozhi.outbound.common.support.JsonSupport;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Optional LLM judgements. Runs only when a Spring AI chat model bean exists (spring.ai.model.chat
 * is not "none"); otherwise every call returns empty and the classifier records the skip. No prompt
 * ever carries the phone number, only transcript text.
 */
@Service
public class OutcomeLlm {
    public record Judgement(String label, double confidence, String reason) {}

    private final ObjectProvider<ChatModel> models;
    private final JsonSupport json;
    private volatile UnaryOperator<String> override;

    public OutcomeLlm(ObjectProvider<ChatModel> models, JsonSupport json) {
        this.models = models;
        this.json = json;
    }

    /** Test seam: replaces the chat model with a deterministic function; null restores it. */
    public void override(UnaryOperator<String> fn) {
        override = fn;
    }

    public boolean available() {
        return override != null || models.getIfAvailable() != null;
    }

    /** label "assistant" or "human". */
    public Optional<Judgement> assistant(String firstTurns) {
        return ask(
                "你在判断外呼电话的接听方。下面是被叫方开头说的话（语音识别文本）。"
                        + "判断它是不是手机系统的智能语音助手/代接助理（而不是真人）。"
                        + "只输出 JSON：{\"label\":\"assistant\"或\"human\",\"confidence\":0到1,\"reason\":\"不超过30字\"}。\n文本："
                        + firstTurns,
                Set.of("assistant", "human"));
    }

    /** label A-E: A 高意向, B 有兴趣, C 暂无需要, D 明确拒绝/勿扰, E 无法判断. */
    public Optional<Judgement> intent(String dialogue) {
        return ask(
                "你在给营销外呼对话评意向等级。A=明确要办理/高意向，B=有兴趣待跟进，C=暂无需要，"
                        + "D=明确拒绝并要求不再联系，E=无法判断。只输出 JSON："
                        + "{\"label\":\"A-E 之一\",\"confidence\":0到1,\"reason\":\"不超过40字\"}。\n对话（客户: 前缀为被叫方）：\n"
                        + dialogue,
                Set.of("A", "B", "C", "D", "E"));
    }

    private static final Pattern OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private Optional<Judgement> ask(String prompt, Set<String> labels) {
        try {
            String answer;
            UnaryOperator<String> fn = override;
            if (fn != null) answer = fn.apply(prompt);
            else {
                ChatModel model = models.getIfAvailable();
                if (model == null) return Optional.empty();
                answer = model.call(prompt);
            }
            if (answer == null) return Optional.empty();
            var m = OBJECT.matcher(answer);
            if (!m.find()) return Optional.empty();
            Map<String, Object> o = json.object(m.group());
            String label = Objects.toString(o.get("label"), "").strip();
            if (!labels.contains(label)) return Optional.empty();
            double c =
                    o.get("confidence") instanceof Number n
                            ? n.doubleValue()
                            : Double.parseDouble(Objects.toString(o.get("confidence"), "0"));
            String reason = Objects.toString(o.get("reason"), "");
            if (reason.length() > 120) reason = reason.substring(0, 120);
            return Optional.of(new Judgement(label, Math.max(0, Math.min(1, c)), reason));
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(OutcomeLlm.class)
                    .warn("outcome llm failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
