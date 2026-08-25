from datetime import datetime
from typing import List
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, status
from sqlmodel import Session, select

from ...db import get_session
from ...models import CallSession, CallStatus
from ...schemas import CallSessionOut, StartCallRequest
from ...services.telephony import get_adapter
from ...api.deps import check_api_key
from ...config import get_settings

router = APIRouter(prefix="/api/v1/calls", tags=["calls"], dependencies=[Depends(check_api_key)])
settings = get_settings()


@router.post("", response_model=CallSessionOut)
async def create_call(payload: StartCallRequest, session: Session = Depends(get_session)):
    call = CallSession(
        tenant_id=1,
        campaign_id=payload.campaign_id,
        contact_id=payload.contact_id,
        phone=payload.phone,
        mode=payload.mode,
        status=CallStatus.CREATED,
        max_attempts=payload.max_attempts,
    )
    session.add(call)
    session.commit()
    session.refresh(call)

    adapter = get_adapter()
    callback_url = f"{settings.telephony_webhook_base}/api/v1/webhooks/telephony/status"
    dial_result = await adapter.dial(str(call.id), payload.phone, callback_url)
    call.ai_session_id = dial_result.get("provider_call_id")
    call.status = CallStatus.DIALING
    call.started_at = datetime.utcnow()
    session.add(call)
    session.commit()
    session.refresh(call)
    return call


@router.get("", response_model=List[CallSessionOut])
def list_calls(session: Session = Depends(get_session)):
    calls = session.exec(select(CallSession).order_by(CallSession.created_at.desc())).all()
    return calls


@router.get("/{call_id}", response_model=CallSessionOut)
def get_call(call_id: UUID, session: Session = Depends(get_session)):
    call = session.get(CallSession, call_id)
    if not call:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="call not found")
    return call


@router.post("/{call_id}/handover", response_model=CallSessionOut)
async def handover_to_human(call_id: UUID, reason: str = "operator_request", session: Session = Depends(get_session)):
    call = session.get(CallSession, call_id)
    if not call:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="call not found")
    call.status = CallStatus.WAITING_HUMAN
    call.handoff_reason = reason
    adapter = get_adapter()
    await adapter.transfer_to_human(str(call.id), reason)
    session.add(call)
    session.commit()
    session.refresh(call)
    return call


@router.post("/{call_id}/sms", response_model=CallSessionOut)
async def send_post_sms(call_id: UUID, sms_text: str, session: Session = Depends(get_session)):
    call = session.get(CallSession, call_id)
    if not call:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="call not found")
    # placeholder: send sms is triggered by caller side
    return call
