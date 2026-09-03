from typing import List

from fastapi import Depends, FastAPI, Header, HTTPException, status
from pydantic import BaseModel
from pydantic import Field

from .config import settings
from .llm import generate_reply
from .policy import get_default_keywords, resolve_action, ai_reply

settings.validate_runtime()
app = FastAPI(title=settings.app_name)


def require_service_token(authorization: str | None = Header(default=None)) -> None:
    expected = settings.service_token.strip()
    if not expected and settings.env.lower() not in {"prod", "production"}:
        return
    if not expected or authorization != f"Bearer {expected}":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid service token")


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


@app.post("/agent/turn", dependencies=[Depends(require_service_token)])
async def turn(payload: TurnRequest):
    language = str(payload.context.get("language") or "zh-CN")
    keywords = get_default_keywords(language)
    handoff, hangup_sms, tts, escalate_priority = resolve_action(
        payload.mode,
        payload.script,
        payload.transcript,
        language,
    )
    if not bool(payload.context.get("hangup_sms_enabled", True)):
        hangup_sms = None
    action = "handoff" if handoff else "speak"
    provider = str(payload.context.get("llm_provider") or settings.llm_provider).strip().lower()
    if action == "speak" and provider == "openai-compatible":
        if not bool(payload.context.get("external_llm_enabled", False)):
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="external LLM use is not approved for this tenant",
            )
        tts = await generate_reply(
            script=payload.script,
            transcript=payload.transcript,
            language=language,
            model=str(payload.context.get("llm_model") or settings.openai_model),
            knowledge=payload.context.get("knowledge") or [],
            conversation=payload.context.get("conversation") or [],
        )
    elif provider != "rule":
        raise RuntimeError(f"unsupported LLM provider: {provider}")
    return TurnResult(
        action=action,
        tts_text=tts,
        handoff_to_human=handoff,
        hangup_sms=hangup_sms,
        next_keywords=keywords,
        escalate_priority=escalate_priority,
    )


@app.post("/agent/start", dependencies=[Depends(require_service_token)])
def start(payload: TurnRequest):
    language = str(payload.context.get("language") or "zh-CN")
    tts = ai_reply(payload.mode, payload.script, "", language)
    return TurnResult(
        action="greeting",
        tts_text=tts,
        handoff_to_human=False,
        next_keywords=get_default_keywords(language),
    )
