package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.ai.service.DialogueRules;
import java.util.Map;

/** A one-shot conservative answer decision. Questions about an assistant never imply a machine. */
public final class AnswerClassifier {
    public enum Answer {
        CLASSIFYING,
        HUMAN,
        MACHINE,
        UNKNOWN
    }

    private Answer answer = Answer.CLASSIFYING;
    private String evidence = "";

    public synchronized Answer transcript(String text, boolean last) {
        if (answer != Answer.CLASSIFYING || !last) return answer;
        String normalized = DialogueRules.normalized(text);
        if ("machine_candidate".equals(DialogueRules.classify(text))
                || normalized.matches(
                        ".*(?:您拨打的(?:电话|号码).*(?:关机|停机|空号|无法接通)|暂时无法接通|请在.{0,4}声后留言).*"))
            answer = Answer.MACHINE;
        else if (!normalized.isBlank()) answer = Answer.HUMAN;
        if (answer != Answer.CLASSIFYING) evidence = text;
        return answer;
    }

    public synchronized Answer timeout() {
        if (answer == Answer.CLASSIFYING) answer = Answer.UNKNOWN;
        return answer;
    }

    public synchronized Answer answer() {
        return answer;
    }

    public synchronized Map<String, Object> evidence() {
        return Map.of("answer_class", answer.name().toLowerCase(), "transcript", evidence);
    }
}
