package com.duozhi.services;

import java.util.*;
import java.util.regex.Pattern;

final class AgentPolicy {
    private final Settings settings;
    AgentPolicy(Settings settings) { this.settings = settings; }
    static boolean english(String language) { return language.toLowerCase(Locale.ROOT).startsWith("en"); }
    List<String> keywords(String language) {
        String configured = english(language) ? settings.get("DEFAULT_HANDOFF_KEYWORDS_EN", "human,agent,representative,operator,customer service") : settings.get("DEFAULT_HANDOFF_KEYWORDS", "人工,转人工,坐席,客服");
        return Arrays.stream(configured.split(",")).map(String::strip).filter(v -> !v.isEmpty()).toList();
    }
    static boolean handoff(String transcript, List<String> keywords) {
        String content = transcript.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword.isBlank()) continue;
            var matches = Pattern.compile(Pattern.quote(keyword.strip().toLowerCase(Locale.ROOT))).matcher(content);
            while (matches.find()) {
                String prefix = content.substring(Math.max(0, matches.start() - 48), matches.start());
                if (Pattern.compile("(?:not|don't|no need to)\\s+(?:\\w+\\s+){0,5}$", Pattern.UNICODE_CHARACTER_CLASS).matcher(prefix).find()) continue;
                if (!Pattern.compile("(?:不用|不要|不必|不想|无需|别|不|not|don't|no)(?:再|转|找|帮我|给我|要|需要|转接|\\s)*$").matcher(prefix).find()) return true;
            }
        }
        return false;
    }
    static String reply(String mode, String script, String transcript, String language) {
        boolean en = english(language);
        if (!script.isEmpty()) return transcript.isEmpty() ? script : en ? "I received your message and will handle it now." : "我已收到您刚才的内容，马上为您处理。";
        if (!transcript.isEmpty()) return en ? "I heard: " + transcript + ". I have noted it." : "已识别你说：" + transcript + "，我先记录一下。";
        if (en) return switch(mode) {
            case "ai_only" -> "Hello, I am your virtual assistant. How may I help you?";
            case "ai_with_sms" -> "Hello, I am your virtual assistant. I will send the result and next steps by text message.";
            default -> "Hello. I will verify a few details first, and I can transfer you to a human agent at any time.";
        };
        return switch(mode) {
            case "ai_only" -> "您好，我是智能助手，请告诉我您需要什么帮助。";
            case "ai_with_sms" -> "您好，我是智能助手，稍后我会将处理结果和下一步发送到您的短信。";
            default -> "您好，先由我先做个简单核实，您如果需要我可以直接帮您转人工。";
        };
    }
    Map<String,Object> turn(Map<String,Object> payload, boolean start) {
        Support.required(payload, "call_id", 128); Support.required(payload, "phone", 128); String mode = Support.required(payload, "mode", 128);
        String script = Support.str(payload, "script", ""), transcript = Support.str(payload, "transcript", "");
        Map<String,Object> context = Support.map(payload.get("context")); String language = Support.str(context, "language", "zh-CN");
        boolean handoff = !start && (mode.equals("mixed_human_first") || (!mode.equals("ai_only") && handoff(transcript, keywords(language))));
        String tts = reply(mode, script, start ? "" : transcript, language);
        String sms = !start && mode.equals("ai_with_sms") && Support.truthy(context.getOrDefault("hangup_sms_enabled", true)) ? english(language) ? settings.get("DEFAULT_HANGUP_SMS_EN", "Thank you for your time. Reply to this message if you need a human agent.") : settings.get("DEFAULT_HANGUP_SMS", "感谢来电，如有需要请回复我们") : null;
        if (handoff) { tts = english(language) ? "I will transfer you to a human agent now." : "我将立即帮您转接人工客服。"; sms = null; }
        String lower = transcript.toLowerCase(Locale.ROOT);
        int priority = start ? 0 : transcript.contains("紧急") || lower.contains("urgent") || lower.contains("emergency") ? 2 : transcript.contains("重要") || lower.contains("important") ? 1 : 0;
        Map<String,Object> result = new LinkedHashMap<>(); result.put("action", start ? "greeting" : handoff ? "handoff" : "speak"); result.put("tts_text", tts); result.put("handoff_to_human", handoff); result.put("hangup_sms", sms); result.put("next_keywords", keywords(language)); result.put("escalate_priority", priority); return result;
    }
}
