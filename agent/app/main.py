from typing import List

from fastapi import FastAPI
from pydantic import BaseModel
from pydantic import Field

from .config import settings
from .policy import get_default_keywords, resolve_action, ai_reply

app = FastAPI(title=settings.app_name)


class TurnRequest(BaseModel):
    call_id: str
    phone: str
    mode: str
    script: str = ""
    transcript: str = ""
    context: dict = Field(default_factory=dict)


class TurnResult(BaseModel):
    action: str
    tts_text: str | None = None
    handoff_to_human: bool = False
    hangup_sms: str | None = None
    next_keywords: List[str] = Field(default_factory=list)
    escalate_priority: int = 0


@app.get("/health")
def health():
    return {"status": "ok", "service": settings.app_name}


@app.post("/agent/turn")
def turn(payload: TurnRequest):
    keywords = get_default_keywords()
    handoff, hangup_sms, tts, escalate_priority = resolve_action(
        payload.mode,
        payload.script,
        payload.transcript,
    )
    action = "handoff" if handoff else "speak"
    return TurnResult(
        action=action,
        tts_text=tts,
        handoff_to_human=handoff,
        hangup_sms=hangup_sms,
        next_keywords=keywords,
        escalate_priority=escalate_priority,
    )


@app.post("/agent/start")
def start(payload: TurnRequest):
    tts = ai_reply(payload.mode, payload.script, "")
    return TurnResult(
        action="greeting",
        tts_text=tts,
        handoff_to_human=False,
        next_keywords=get_default_keywords(),
    )
