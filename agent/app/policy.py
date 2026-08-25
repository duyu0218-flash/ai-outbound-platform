from typing import List

from .config import settings


def is_handoff(transcript: str, keywords: List[str]) -> bool:
    content = (transcript or "").lower()
    return any(k.strip().lower() in content for k in keywords if k.strip())


def ai_reply(mode: str, script: str, transcript: str) -> str:
    if script:
        if transcript:
            return "我已收到您刚才的内容，马上为您处理。"
        return f"{script}"
    if transcript:
        return f"已识别你说：{transcript}，我先记录一下。"
    if mode == "ai_only":
        return "您好，我是智能助手，请告诉我您需要什么帮助。"
    if mode == "ai_with_sms":
        return "您好，我是智能助手，稍后我会将处理结果和下一步发送到您的短信。"
    return "您好，先由我先做个简单核实，您如果需要我可以直接帮您转人工。"


def get_default_keywords() -> List[str]:
    return [x.strip() for x in settings.default_handoff_keywords.split(",") if x.strip()]


def resolve_action(mode: str, script: str, transcript: str) -> tuple[bool, str | None, str, int]:
    keywords = get_default_keywords()
    should_handoff = is_handoff(transcript, keywords)
    escalate_priority = 0
    if "紧急" in transcript:
        escalate_priority = 2
    elif "重要" in transcript:
        escalate_priority = 1

    hangup_sms = settings.default_hangup_sms if mode == "ai_with_sms" else None
    tts = ai_reply(mode, script, transcript)
    if should_handoff:
        tts = "我将立即帮您转接人工客服。"
        hangup_sms = None
    return should_handoff, hangup_sms, tts, escalate_priority
