import httpx
from uuid import UUID

from ..schemas import AiTurnRequest, AiTurnResult
from ..config import get_settings
from .telephony import send_sms

settings = get_settings()


async def ai_call_turn(call_id: UUID, phone: str, mode: str, transcript: str = "") -> AiTurnResult:
    payload = AiTurnRequest(
        call_id=call_id,
        phone=phone,
        mode=mode,  # type: ignore[arg-type]
        transcript=transcript,
        context={},
    )
    async with httpx.AsyncClient(timeout=10.0) as client:
        r = await client.post(
            f"{settings.ai_agent_url}/agent/turn",
            json=payload.model_dump(),
        )
        if r.status_code != 200:
            return AiTurnResult(action="fallback_human", handoff_to_human=True, tts_text="当前系统异常，请稍后由人工接听。")
        data = r.json()
    return AiTurnResult(**data)


async def execute_ai_action(result: AiTurnResult, call_id: str, phone: str) -> None:
    if result.handoff_to_human:
        # caller-side handoff is completed in backend/webhook layer
        return
    if result.hangup_sms:
        await send_sms(phone, result.hangup_sms)
