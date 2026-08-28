import hashlib
import json

from fastapi import APIRouter, BackgroundTasks, Depends
from sqlalchemy import update
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ...api.deps import check_webhook_token, get_session
from ...clock import utc_now
from ...models import CallEvent, CallMode, CallSession, CallStatus, Campaign, WebhookEventIngest
from ...schemas import WebhookEvent
from ...services import dispatcher
from ...services.call_service import complete_campaign_if_terminal, schedule_campaign_retry

router = APIRouter(prefix="/api/v1/webhooks", tags=["webhooks"])

STATUS_ORDER = {
    CallStatus.CREATED: 0,
    CallStatus.QUEUED: 1,
    CallStatus.DIALING: 2,
    CallStatus.ANSWERED: 3,
    CallStatus.IN_AI: 4,
    CallStatus.WAITING_HUMAN: 5,
    CallStatus.HANDOFF_TRANSFERRING: 4,
    CallStatus.COMPLETED: 6,
    CallStatus.FAILED: 6,
    CallStatus.NO_ANSWER: 6,
    CallStatus.BUSY: 6,
    CallStatus.VOICEMAIL: 6,
}


def _is_safe_status_transition(current: CallStatus, target: CallStatus | None) -> bool:
    if target is None:
        return False
    if current == target:
        return True
    if current in {CallStatus.COMPLETED, CallStatus.FAILED, CallStatus.NO_ANSWER, CallStatus.BUSY, CallStatus.VOICEMAIL}:
        return False
    return STATUS_ORDER.get(target, 0) >= STATUS_ORDER.get(current, 0)


def _safe_status_sources_for_target(target: CallStatus | None) -> list[CallStatus]:
    if target is None:
        return []
    return [state for state in CallStatus if _is_safe_status_transition(state, target)]


def _apply_status_transition(session: Session, call_id, mapped_status: CallStatus | None) -> bool:
    if mapped_status is None:
        return False

    safe_sources = _safe_status_sources_for_target(mapped_status)
    if not safe_sources:
        return False

    now = utc_now()
    result = session.exec(
        update(CallSession)
        .where(CallSession.id == call_id, CallSession.status.in_(safe_sources))
        .values(status=mapped_status, updated_at=now)
    )
    return bool(result.rowcount)


def _status_to_call_status(raw_status: str) -> CallStatus | None:
    status = (raw_status or "").lower()
    mapping = {
        "dialing": CallStatus.DIALING,
        "answering": CallStatus.ANSWERED,
        "answered": CallStatus.ANSWERED,
        "in_ai": CallStatus.IN_AI,
        "completed": CallStatus.COMPLETED,
        "ended": CallStatus.COMPLETED,
        "failed": CallStatus.FAILED,
        "busy": CallStatus.BUSY,
        "no_answer": CallStatus.NO_ANSWER,
        "voicemail": CallStatus.VOICEMAIL,
    }
    return mapping.get(status)


def _add_event(session: Session, call_id, event_type: str, source: str, payload: dict) -> bool:
    payload_json = json.dumps(payload, ensure_ascii=False, sort_keys=True)
    provider_key = _make_provider_event_key(call_id, event_type, source, payload_json, payload.get("event_id") if isinstance(payload, dict) else None)
    existed = _get_duplicate_event(session, call_id, provider_key)
    if existed is not None:
        session.exec(
            update(WebhookEventIngest)
            .where(WebhookEventIngest.id == existed.id)
            .values(repeat_count=WebhookEventIngest.repeat_count + 1)
        )
        session.commit()
        return True

    session.add(
        CallEvent(
            call_session_id=call_id,
            event_type=event_type,
            source=source,
            payload=payload_json,
        )
    )
    session.add(
        WebhookEventIngest(
            call_session_id=call_id,
            event_type=event_type,
            source=source,
            provider_event_key=provider_key,
        )
    )
    try:
        session.commit()
    except IntegrityError:
        session.rollback()
        existed = _get_duplicate_event(session, call_id, provider_key)
        if existed is None:
            raise
        session.exec(
            update(WebhookEventIngest)
            .where(WebhookEventIngest.id == existed.id)
            .values(repeat_count=WebhookEventIngest.repeat_count + 1)
        )
        session.commit()
        return True
    return False


def _event_matches_current_attempt(call: CallSession, payload: dict) -> bool:
    raw_attempt = payload.get("attempt")
    if raw_attempt is None:
        return True
    try:
        return int(raw_attempt) == call.attempts
    except (TypeError, ValueError):
        return False


def _make_provider_event_key(
    call_id,
    event_type: str,
    source: str,
    payload_json: str,
    explicit_event_id: str | None = None,
) -> str:
    seed = (
        f"{call_id}:{source}:event_id:{explicit_event_id}"
        if explicit_event_id
        else f"{call_id}:{event_type}:{source}:{payload_json}"
    )
    return hashlib.sha256(seed.encode("utf-8")).hexdigest()


def _get_duplicate_event(session: Session, call_id, provider_key: str) -> WebhookEventIngest | None:
    return session.exec(
        select(WebhookEventIngest).where(
            WebhookEventIngest.call_session_id == call_id,
            WebhookEventIngest.provider_event_key == provider_key,
        )
    ).first()


@router.post("/telephony/status")
def telephony_status(
    payload: WebhookEvent,
    background_tasks: BackgroundTasks,
    _: None = Depends(check_webhook_token),
    session: Session = Depends(get_session),
):
    call = session.get(CallSession, payload.call_id)
    if not call:
        return {"result": "ignore"}

    raw_status = payload.payload.get("status", "")
    mapped = _status_to_call_status(raw_status)
    is_duplicate = _add_event(session, call.id, "status", "telephony", payload.payload)
    if is_duplicate:
        return {"result": "ok"}
    if not _event_matches_current_attempt(call, payload.payload):
        return {"result": "ignored", "reason": "stale_attempt"}

    status_applied = bool(mapped and _apply_status_transition(session, call.id, mapped))
    if status_applied:
        session.refresh(call)
        call = session.get(CallSession, call.id)
        if not call:
            return {"result": "ignore"}
    telephony_call_id = payload.payload.get("telephony_call_id")
    if telephony_call_id:
        call.telephony_call_id = str(telephony_call_id)
    if payload.payload.get("hangup_reason") and (status_applied or not call.last_error):
        call.last_error = payload.payload.get("hangup_reason")
    if status_applied and mapped in {
        CallStatus.COMPLETED,
        CallStatus.FAILED,
        CallStatus.BUSY,
        CallStatus.VOICEMAIL,
        CallStatus.NO_ANSWER,
    }:
        call.finished_at = utc_now()
    if payload.payload.get("summary"):
        call.summary = (call.summary or "") + "\n" + str(payload.payload.get("summary"))

    session.add(call)
    if status_applied and mapped is not None:
        schedule_campaign_retry(session, call, mapped)
    session.commit()
    if mapped in {
        CallStatus.COMPLETED,
        CallStatus.FAILED,
        CallStatus.BUSY,
        CallStatus.VOICEMAIL,
        CallStatus.NO_ANSWER,
    }:
        complete_campaign_if_terminal(session, call.campaign_id)

    if status_applied and mapped == CallStatus.ANSWERED and call.mode != CallMode.HUMAN_ONLY:
        background_tasks.add_task(dispatcher.run_ai_turn, call_id=call.id, transcript=payload.transcript or "")

    return {"result": "ok"}


@router.post("/telephony/transcript")
def telephony_transcript(
    payload: WebhookEvent,
    background_tasks: BackgroundTasks,
    _: None = Depends(check_webhook_token),
    session: Session = Depends(get_session),
):
    call = session.get(CallSession, payload.call_id)
    if not call:
        return {"result": "ignore"}
    is_duplicate = _add_event(session, call.id, "transcript", "telephony", payload.payload)
    if is_duplicate:
        return {"result": "ok"}
    if not _event_matches_current_attempt(call, payload.payload):
        return {"result": "ignored", "reason": "stale_attempt"}
    call.last_transcript = payload.transcript
    if payload.transcript:
        call.summary = f"{(call.summary or '').rstrip()}\n{payload.transcript}".strip()
    session.add(call)
    session.commit()

    if call.mode != CallMode.HUMAN_ONLY:
        background_tasks.add_task(dispatcher.run_ai_turn, call_id=call.id, transcript=payload.transcript or "")
    return {"result": "ok"}


@router.post("/telephony/recording")
def telephony_recording(
    payload: WebhookEvent,
    _: None = Depends(check_webhook_token),
    session: Session = Depends(get_session),
):
    call = session.get(CallSession, payload.call_id)
    if not call:
        return {"result": "ignore"}
    is_duplicate = _add_event(session, call.id, "recording", "telephony", payload.payload)
    if is_duplicate:
        return {"result": "ok"}
    if not _event_matches_current_attempt(call, payload.payload):
        return {"result": "ignored", "reason": "stale_attempt"}
    campaign = session.get(Campaign, call.campaign_id) if call.campaign_id is not None else None
    if campaign and not campaign.recording_enabled:
        return {"result": "ignored", "reason": "recording_disabled"}
    url = payload.payload.get("url")
    if url:
        call.recording_url = str(url)
    session.add(call)
    session.commit()
    return {"result": "ok"}
