from typing import List

from .config import settings


def is_handoff(transcript: str, keywords: List[str]) -> bool:
    content = (transcript or "").lower()
    return any(k.strip().lower() in content for k in keywords if k.strip())


def _is_english(language: str) -> bool:
    return (language or "").lower().startswith("en")


def ai_reply(mode: str, script: str, transcript: str, language: str = "zh-CN") -> str:
    if script:
        if transcript:
            return "I received your message and will handle it now." if _is_english(language) else "我已收到您刚才的内容，马上为您处理。"
        return f"{script}"
    if transcript:
        return f"I heard: {transcript}. I have noted it." if _is_english(language) else f"已识别你说：{transcript}，我先记录一下。"
    if _is_english(language):
        if mode == "ai_only":
            return "Hello, I am your virtual assistant. How may I help you?"
        if mode == "ai_with_sms":
            return "Hello, I am your virtual assistant. I will send the result and next steps by text message."
        return "Hello. I will verify a few details first, and I can transfer you to a human agent at any time."
    if mode == "ai_only":
        return "您好，我是智能助手，请告诉我您需要什么帮助。"
    if mode == "ai_with_sms":
        return "您好，我是智能助手，稍后我会将处理结果和下一步发送到您的短信。"
    return "您好，先由我先做个简单核实，您如果需要我可以直接帮您转人工。"


def get_default_keywords(language: str = "zh-CN") -> List[str]:
    configured = settings.default_handoff_keywords_en if _is_english(language) else settings.default_handoff_keywords
    return [x.strip() for x in configured.split(",") if x.strip()]


def resolve_action(mode: str, script: str, transcript: str, language: str = "zh-CN") -> tuple[bool, str | None, str, int]:
    keywords = get_default_keywords(language)
    should_handoff = is_handoff(transcript, keywords)
    escalate_priority = 0
    normalized_transcript = (transcript or "").lower()
    if "紧急" in transcript or "urgent" in normalized_transcript or "emergency" in normalized_transcript:
        escalate_priority = 2
    elif "重要" in transcript or "important" in normalized_transcript:
        escalate_priority = 1

    hangup_sms = (settings.default_hangup_sms_en if _is_english(language) else settings.default_hangup_sms) if mode == "ai_with_sms" else None
    tts = ai_reply(mode, script, transcript, language)
    if should_handoff:
        tts = "I will transfer you to a human agent now." if _is_english(language) else "我将立即帮您转接人工客服。"
        hangup_sms = None
    return should_handoff, hangup_sms, tts, escalate_priority
