import asyncio
import json
from datetime import datetime

from fastapi import APIRouter, Depends
from sqlmodel import Session

from ...db import get_session
from ...models import CallEvent, CallSession, CallStatus
from ...schemas import WebhookEvent
from ...services.dispatcher import ai_call_turn, execute_ai_action
from ...services.telephony import get_adapter

router = APIRouter(prefix="/api/v1/webhooks", tags=["webhooks"])


@router.post("/telephony/status")
def telephony_status(payload: WebhookEvent, session: Session = Depends(get_session)):
    call = session.get(CallSession, payload.call_id)
    if not call:
        return {"result": "ignore"}

    status = payload.payload.get("status")
    event = CallEvent(
        call_session_id=payload.call_id,
        event_type="status",
        source="telephony",
        payload=json.dumps(payload.payload),
    )
    session.add(event)

    if status == "answered":
        call.status = CallStatus.IN_AI
        if call.mode != "human_only":
            asyncio.create_task(_notify_ai_turn(call, session))
    elif status == "ended":
        call.status = CallStatus.COMPLETED
        call.finished_at = call.finished_at or datetime.utcnow()
    elif status == "failed":
        call.status = CallStatus.FAILED
    session.add(call)
    session.commit()
    return {"result": "ok"}


async def _notify_ai_turn(call: CallSession, session: Session) -> None:
    result = await ai_call_turn(call.id, call.phone, call.mode, transcript="")
    if result.handoff_to_human:
        call.status = CallStatus.WAITING_HUMAN
        call.handoff_reason = "ai_decision"
        session.add(call)
        session.commit()
        adapter = get_adapter()
        await adapter.transfer_to_human(str(call.id), call.handoff_reason)
        return
    await execute_ai_action(result, str(call.id), call.phone)
