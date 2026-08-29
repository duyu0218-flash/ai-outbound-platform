from __future__ import annotations

import json
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import update
from sqlmodel import Session, select

from ...api.deps import (
    check_api_key,
    current_user_optional,
    get_session,
    get_tenant_id_for_request,
    require_roles_if_authenticated,
)
from ...clock import utc_now
from ...models import (
    CallAnalysis,
    CallMetric,
    CallSession,
    CallStatus,
    HandoffRequest,
    HandoffState,
    KnowledgeItem,
    RealtimeSession,
    RecordingAsset,
    SpeechTurn,
    User,
)
from ...schemas import (
    CallAnalysisOut,
    CallAnalysisReview,
    CallMetricOut,
    HandoffRequestOut,
    KnowledgeItemCreate,
    KnowledgeItemOut,
    KnowledgeItemUpdate,
    RealtimeSessionOut,
    RecordingAssetOut,
    SpeechTurnOut,
)
from ...services.call_analysis import analyze_call
from ...services.call_service import get_call
from ...services.realtime_voice import interrupt_playback
from ...services.telephony import get_telephony_adapter, with_retry


router = APIRouter(
    prefix="/api/v1",
    tags=["voice-operations"],
    dependencies=[Depends(check_api_key), Depends(require_roles_if_authenticated("admin", "agent"))],
)


def _visible_call(session: Session, tenant_id: int, call_id: UUID, user: User | None) -> CallSession:
    call = get_call(session, tenant_id, call_id)
    if user is not None and user.role == "agent" and not user.is_supervisor:
        if call.human_agent_id not in {None, user.id}:
            raise HTTPException(status_code=403, detail="call is assigned to another agent")
    return call


@router.get("/calls/{call_id}/realtime", response_model=RealtimeSessionOut | None)
def get_realtime_session(
    call_id: UUID,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    _visible_call(session, tenant_id, call_id, current)
    return session.exec(select(RealtimeSession).where(RealtimeSession.call_session_id == call_id)).first()


@router.get("/calls/{call_id}/speech-turns", response_model=list[SpeechTurnOut])
def list_speech_turns(
    call_id: UUID,
    final_only: bool = Query(default=False),
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    _visible_call(session, tenant_id, call_id, current)
    query = select(SpeechTurn).where(SpeechTurn.call_session_id == call_id)
    if final_only:
        query = query.where(SpeechTurn.is_final.is_(True))
    return session.exec(query.order_by(SpeechTurn.created_at.asc(), SpeechTurn.id.asc())).all()


@router.get("/calls/{call_id}/metrics", response_model=list[CallMetricOut])
def list_call_metrics(
    call_id: UUID,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    _visible_call(session, tenant_id, call_id, current)
    return session.exec(
        select(CallMetric).where(CallMetric.call_session_id == call_id).order_by(CallMetric.created_at.asc())
    ).all()


@router.get("/calls/{call_id}/recordings", response_model=list[RecordingAssetOut])
def list_recordings(
    call_id: UUID,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    _visible_call(session, tenant_id, call_id, current)
    return session.exec(
        select(RecordingAsset).where(RecordingAsset.call_session_id == call_id).order_by(RecordingAsset.created_at.desc())
    ).all()


@router.post("/calls/{call_id}/interrupt")
async def interrupt_call_playback(
    call_id: UUID,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    _visible_call(session, tenant_id, call_id, current)
    await interrupt_playback(call_id)
    return {"result": "ok"}


@router.get("/calls/{call_id}/analysis", response_model=CallAnalysisOut)
def get_call_analysis(
    call_id: UUID,
    refresh: bool = Query(default=False),
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    call = _visible_call(session, tenant_id, call_id, current)
    analysis = session.exec(select(CallAnalysis).where(CallAnalysis.call_session_id == call_id)).first()
    if analysis is None or refresh:
        analysis = analyze_call(session, call)
    return analysis


@router.put("/calls/{call_id}/analysis", response_model=CallAnalysisOut)
def review_call_analysis(
    call_id: UUID,
    payload: CallAnalysisReview,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    call = _visible_call(session, tenant_id, call_id, current)
    if current is not None and current.role != "admin" and not current.is_supervisor:
        raise HTTPException(status_code=403, detail="supervisor permission required")
    analysis = session.exec(select(CallAnalysis).where(CallAnalysis.call_session_id == call_id)).first()
    if analysis is None:
        analysis = analyze_call(session, call)
    updates = payload.model_dump(exclude_unset=True, exclude={"qa_flags"})
    for field, value in updates.items():
        setattr(analysis, field, value)
    if payload.qa_flags is not None:
        analysis.qa_flags_json = json.dumps(payload.qa_flags, ensure_ascii=False)
    analysis.review_state = "reviewed"
    analysis.reviewed_by = current.id if current is not None else None
    analysis.reviewed_at = utc_now()
    analysis.updated_at = utc_now()
    session.add(analysis)
    session.commit()
    session.refresh(analysis)
    return analysis


@router.get("/calls/{call_id}/handoffs", response_model=list[HandoffRequestOut])
def list_handoffs(
    call_id: UUID,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    _visible_call(session, tenant_id, call_id, current)
    return session.exec(
        select(HandoffRequest).where(HandoffRequest.call_session_id == call_id).order_by(HandoffRequest.requested_at.desc())
    ).all()


@router.get("/handoffs", response_model=list[HandoffRequestOut])
def list_handoff_queue(
    handoff_state: HandoffState = Query(default=HandoffState.WAITING, alias="state"),
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    query = select(HandoffRequest).where(
        HandoffRequest.tenant_id == tenant_id,
        HandoffRequest.state == handoff_state,
    )
    if current is not None and current.role == "agent" and not current.is_supervisor:
        query = query.where(HandoffRequest.assigned_agent_id.in_([None, current.id]))
    return session.exec(query.order_by(HandoffRequest.requested_at.asc())).all()


@router.post("/calls/{call_id}/handoffs/{handoff_id}/accept", response_model=HandoffRequestOut)
async def accept_handoff(
    call_id: UUID,
    handoff_id: int,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    call = _visible_call(session, tenant_id, call_id, current)
    handoff = session.get(HandoffRequest, handoff_id)
    if handoff is None or handoff.call_session_id != call_id or handoff.tenant_id != tenant_id:
        raise HTTPException(status_code=404, detail="handoff not found")
    if handoff.state != HandoffState.WAITING:
        raise HTTPException(status_code=409, detail="handoff is not waiting")
    if current is not None and handoff.assigned_agent_id not in {None, current.id} and current.role != "admin":
        raise HTTPException(status_code=403, detail="handoff is assigned to another agent")
    original_assigned_agent_id = handoff.assigned_agent_id
    claimed_agent_id = current.id if current is not None and current.role == "agent" else original_assigned_agent_id
    claim_result = session.execute(
        update(HandoffRequest)
        .where(
            HandoffRequest.id == handoff_id,
            HandoffRequest.state == HandoffState.WAITING,
        )
        .values(
            state=HandoffState.ACCEPTING,
            assigned_agent_id=claimed_agent_id,
            updated_at=utc_now(),
        )
    )
    if claim_result.rowcount != 1:
        session.rollback()
        raise HTTPException(status_code=409, detail="handoff has already been claimed")
    session.commit()
    adapter = get_telephony_adapter(
        session=session,
        tenant_id=tenant_id,
        line_id=call.telephony_line_id,
    )
    try:
        await with_retry(
            lambda: adapter.transfer_to_human(
                call_id=str(call.id),
                reason=handoff.reason or "agent_accept",
                target_group=handoff.target_group or (
                    f"agent:{handoff.assigned_agent_id}" if handoff.assigned_agent_id else None
                ),
            )
        )
    except Exception as exc:
        session.execute(
            update(HandoffRequest)
            .where(
                HandoffRequest.id == handoff_id,
                HandoffRequest.state == HandoffState.ACCEPTING,
            )
            .values(
                state=HandoffState.WAITING,
                assigned_agent_id=original_assigned_agent_id,
                updated_at=utc_now(),
            )
        )
        session.commit()
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=f"telephony transfer failed: {exc}")
    session.refresh(handoff)
    handoff.state = HandoffState.ACCEPTED
    handoff.assigned_agent_id = claimed_agent_id
    handoff.responded_at = utc_now()
    handoff.updated_at = utc_now()
    call.human_agent_id = handoff.assigned_agent_id
    call.status = CallStatus.HANDOFF_TRANSFERRING
    call.updated_at = utc_now()
    session.add(handoff)
    session.add(call)
    session.commit()
    session.refresh(handoff)
    return handoff


@router.post("/calls/{call_id}/handoffs/{handoff_id}/reject", response_model=HandoffRequestOut)
def reject_handoff(
    call_id: UUID,
    handoff_id: int,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    call = _visible_call(session, tenant_id, call_id, current)
    handoff = session.get(HandoffRequest, handoff_id)
    if handoff is None or handoff.call_session_id != call_id or handoff.state != HandoffState.WAITING:
        raise HTTPException(status_code=409, detail="handoff is not waiting")
    handoff.state = HandoffState.REJECTED
    handoff.responded_at = utc_now()
    handoff.updated_at = utc_now()
    if handoff.assigned_agent_id:
        agent = session.get(User, handoff.assigned_agent_id)
        if agent is not None:
            agent.agent_status = "ready"
            agent.updated_at = utc_now()
            session.add(agent)
    call.human_agent_id = None
    call.status = CallStatus.WAITING_HUMAN
    call.updated_at = utc_now()
    session.add(handoff)
    session.add(call)
    session.commit()
    session.refresh(handoff)
    return handoff


@router.get("/knowledge", response_model=list[KnowledgeItemOut])
def list_knowledge(
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
):
    return session.exec(
        select(KnowledgeItem).where(KnowledgeItem.tenant_id == tenant_id).order_by(KnowledgeItem.updated_at.desc())
    ).all()


@router.post("/knowledge", response_model=KnowledgeItemOut)
def create_knowledge(
    payload: KnowledgeItemCreate,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    if current is not None and current.role != "admin":
        raise HTTPException(status_code=403, detail="admin permission required")
    item = KnowledgeItem(tenant_id=tenant_id, created_by=current.id if current else None, **payload.model_dump())
    session.add(item)
    session.commit()
    session.refresh(item)
    return item


@router.put("/knowledge/{item_id}", response_model=KnowledgeItemOut)
def update_knowledge(
    item_id: int,
    payload: KnowledgeItemUpdate,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    if current is not None and current.role != "admin":
        raise HTTPException(status_code=403, detail="admin permission required")
    item = session.get(KnowledgeItem, item_id)
    if item is None or item.tenant_id != tenant_id:
        raise HTTPException(status_code=404, detail="knowledge item not found")
    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(item, field, value)
    item.version += 1
    item.updated_at = utc_now()
    session.add(item)
    session.commit()
    session.refresh(item)
    return item


@router.delete("/knowledge/{item_id}", response_model=KnowledgeItemOut)
def disable_knowledge(
    item_id: int,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    if current is not None and current.role != "admin":
        raise HTTPException(status_code=403, detail="admin permission required")
    item = session.get(KnowledgeItem, item_id)
    if item is None or item.tenant_id != tenant_id:
        raise HTTPException(status_code=404, detail="knowledge item not found")
    item.is_active = False
    item.version += 1
    item.updated_at = utc_now()
    session.add(item)
    session.commit()
    session.refresh(item)
    return item
