from typing import List
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlmodel import Session
from sqlmodel import select

from ...api.deps import check_api_key, current_user_optional, get_pagination, get_tenant_id_for_request, require_roles_if_authenticated
from ...db import get_session
from ...clock import utc_now
from ...models import CallEvent, CallMode, CallStatus, User, WebhookEventIngest
from ...config import get_settings
from ...services.webrtc import media_is_registered
from ...schemas import (
    CallEventOut,
    CallSessionOut,
    CallWebhookStatsItem,
    CallWebhookStatsOut,
    StartCallRequest,
    WebhookEventIngestOut,
)
from ...services.call_service import (
    CallPermissionError,
    NotFoundError,
    TERMINAL_STATUSES,
    create_call,
    get_call,
    retry_call,
    handover_to_human,
    list_calls,
    place_call,
)

router = APIRouter(
    prefix="/api/v1/calls",
    tags=["calls"],
    dependencies=[Depends(check_api_key), Depends(require_roles_if_authenticated("admin", "agent"))],
)
settings = get_settings()


def _ensure_agent_call_access(call, current: User | None) -> None:
    if current is None or current.role == "admin" or current.is_supervisor:
        return
    if call.human_agent_id != current.id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="call is not assigned to this agent")


@router.post("", response_model=CallSessionOut)
async def create_call_api(
    payload: StartCallRequest,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    try:
        if (
            current is not None
            and current.role == "agent"
            and payload.mode == CallMode.HUMAN_ONLY
            and settings.webrtc_enabled
            and not media_is_registered(tenant_id=tenant_id, agent_id=int(current.id))
        ):
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="agent browser SIP endpoint is not registered",
            )
        call = create_call(
            session=session,
            tenant_id=tenant_id,
            phone=payload.phone,
            mode=payload.mode,
            campaign_id=payload.campaign_id,
            contact_id=payload.contact_id,
            max_attempts=payload.max_attempts,
        )
        if current is not None and current.role == "agent":
            call.human_agent_id = current.id
            call.updated_at = utc_now()
            session.add(call)
            session.commit()
        call = await place_call(session=session, call=call)
        return call
    except CallPermissionError as exc:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=str(exc))
    except NotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc))


@router.get("", response_model=List[CallSessionOut])
def list_calls_api(
    tenant_id: int = Depends(get_tenant_id_for_request),
    status_filter: str | None = Query(default=None, alias="status"),
    campaign_id: int | None = Query(default=None),
    page: int = Query(default=1, ge=1),
    size: int = Query(default=50, ge=1, le=200),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    skip, limit = get_pagination(page=page, size=size)
    status_enum = status_filter if status_filter else None
    try:
        calls = list_calls(
            session,
            tenant_id,
            status=status_enum,
            campaign_id=campaign_id,
            skip=skip,
            limit=limit,
        )
        if current is not None and current.role == "agent" and not current.is_supervisor:
            return [call for call in calls if call.human_agent_id == current.id]
        return calls
    except ValueError:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid status filter")


@router.get("/{call_id}", response_model=CallSessionOut)
def get_call_api(
    call_id: UUID,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    try:
        call = get_call(session, tenant_id, call_id)
        _ensure_agent_call_access(call, current)
        return call
    except NotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc))


@router.post("/{call_id}/handover", response_model=CallSessionOut)
async def handover_api(
    call_id: UUID,
    tenant_id: int = Depends(get_tenant_id_for_request),
    reason: str = Query(default="operator_request"),
    target_group: str | None = Query(default=None),
    current: User | None = Depends(current_user_optional),
    session: Session = Depends(get_session),
):
    try:
        existing_call = get_call(session, tenant_id, call_id)
        if (
            current is not None
            and current.role == "agent"
            and not current.is_supervisor
            and existing_call.human_agent_id not in {None, current.id}
        ):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="call is assigned to another agent")
        return await handover_to_human(
            session,
            tenant_id=tenant_id,
            call_id=call_id,
            reason=reason,
            target_group=target_group or (f"agent:{current.id}" if current and current.role == "agent" else None),
            human_agent_id=current.id if current and current.role == "agent" else None,
        )
    except NotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc))
    except CallPermissionError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc))


@router.post("/{call_id}/hangup", response_model=CallSessionOut)
async def hangup_api(
    call_id: UUID,
    tenant_id: int = Depends(get_tenant_id_for_request),
    reason: str = Query(default="hangup"),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    try:
        call = get_call(session, tenant_id, call_id)
        _ensure_agent_call_access(call, current)
        from ...services.telephony import get_telephony_adapter, with_retry

        adapter = get_telephony_adapter(
            session=session,
            tenant_id=tenant_id,
            line_id=call.telephony_line_id,
        )
        await with_retry(lambda: adapter.hangup(call_id=str(call.id), reason=reason))
        if call.status in TERMINAL_STATUSES:
            return call

        call.status = CallStatus.COMPLETED
        call.finished_at = utc_now()
        session.add(call)
        session.commit()
        return call
    except NotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc))


@router.get("/{call_id}/events", response_model=List[CallEventOut])
def list_call_events(
    call_id: UUID,
    tenant_id: int = Depends(get_tenant_id_for_request),
    page: int = Query(default=1, ge=1),
    size: int = Query(default=50, ge=1, le=200),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    # ensure visibility permission for tenant
    call = get_call(session, tenant_id, call_id)
    _ensure_agent_call_access(call, current)
    skip, limit = get_pagination(page=page, size=size)
    events = session.exec(
        select(CallEvent)
        .where(CallEvent.call_session_id == call_id)
        .order_by(CallEvent.created_at.desc())
        .offset(skip)
        .limit(limit)
    ).all()
    return events


@router.get("/{call_id}/webhook-events", response_model=list[WebhookEventIngestOut])
def list_webhook_events(
    call_id: UUID,
    tenant_id: int = Depends(get_tenant_id_for_request),
    page: int = Query(default=1, ge=1),
    size: int = Query(default=50, ge=1, le=200),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    call = get_call(session, tenant_id, call_id)
    _ensure_agent_call_access(call, current)
    skip, limit = get_pagination(page=page, size=size)
    records = session.exec(
        select(WebhookEventIngest)
        .where(WebhookEventIngest.call_session_id == call_id)
        .order_by(WebhookEventIngest.created_at.desc())
        .offset(skip)
        .limit(limit)
    ).all()
    return records


@router.get("/{call_id}/webhook-stats", response_model=CallWebhookStatsOut)
def get_webhook_stats(
    call_id: UUID,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    call = get_call(session, tenant_id, call_id)
    _ensure_agent_call_access(call, current)

    records = session.exec(
        select(WebhookEventIngest)
        .where(WebhookEventIngest.call_session_id == call_id)
        .order_by(WebhookEventIngest.created_at.asc())
    ).all()

    bucket: dict[str, int] = {}
    for item in records:
        key = f"{item.source}:{item.event_type}"
        bucket[key] = bucket.get(key, 0) + 1

    buckets = []
    for raw_key, count in sorted(bucket.items()):
        source, event_type = raw_key.split(":", 1)
        buckets.append(CallWebhookStatsItem(event_type=event_type, source=source, count=count))

    duplicate_count = sum((item.repeat_count or 1) - 1 for item in records)

    return {
        "total": len(records),
        "unique": len(records),
        "duplicate_estimate": duplicate_count,
        "buckets": buckets,
    }


@router.post("/{call_id}/retry", response_model=CallSessionOut)
async def retry_call_api(
    call_id: UUID,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    current: User | None = Depends(current_user_optional),
):
    try:
        call = get_call(session, tenant_id, call_id)
        _ensure_agent_call_access(call, current)
        return await retry_call(session=session, tenant_id=tenant_id, call_id=call_id)
    except CallPermissionError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc))
    except NotFoundError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc))
