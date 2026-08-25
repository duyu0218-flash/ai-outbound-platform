from typing import List

from fastapi import FastAPI
from pydantic import BaseModel

from .config import settings
from .policy import ai_reply, is_handoff

app = FastAPI(title=settings.app_name)


class TurnRequest(BaseModel):
    call_id: str
    phone: str
    mode: str
    transcript: str = ""
    context: dict = {}


class TurnResult(BaseModel):
    action: str
    tts_text: str | None = None
    handoff_to_human: bool = False
    hangup_sms: str | None = None
    next_keywords: List[str] = []


@app.get("/health")
def health():
    return {"status": "ok", "service": settings.app_name}


@app.post("/agent/turn")
def turn(payload: TurnRequest):
    keywords = [x for x in settings.default_handoff_keywords.split(",") if x]
    handoff = is_handoff(payload.transcript, keywords)
    action = "handoff" if handoff else "speak"
    tts = None
    if handoff:
        tts = "我将立即帮您转接人工客服。"
    else:
        tts = ai_reply(payload.mode, "", payload.transcript)
    hangup_sms = settings.default_hangup_sms if payload.mode == "ai_with_sms" else None
    return TurnResult(
        action=action,
        tts_text=tts,
        handoff_to_human=handoff,
        hangup_sms=hangup_sms,
        next_keywords=keywords,
    )


@app.post("/agent/start")
def start(payload: TurnRequest):
    tts = ai_reply(payload.mode, "", "")
    return TurnResult(action="greeting", tts_text=tts, handoff_to_human=False, next_keywords=[x for x in settings.default_handoff_keywords.split(",") if x])
