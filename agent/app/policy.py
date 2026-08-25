from typing import List


def is_handoff(transcript: str, keywords: List[str]) -> bool:
    content = (transcript or "").lower()
    return any(k.strip().lower() in content for k in keywords if k.strip())


def ai_reply(mode: str, script: str, transcript: str) -> str:
    if transcript:
        return f"已识别你说：{transcript}。请继续提供具体信息，或告诉我是否转人工。"
    if mode == "ai_only":
        return "您好，我是智能助手，请告诉我您需要什么帮助。"
    if mode == "ai_with_sms":
        return "您好，我是智能助手，稍后我会将处理结果和下一步发送到您的短信。"
    return "您好，先由我先做个简单核实，您如果需要我可以直接帮您转人工。"
