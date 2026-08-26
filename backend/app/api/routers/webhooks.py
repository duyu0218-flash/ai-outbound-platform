import json
import hashlib
from datetime import datetime

from fastapi import APIRouter, BackgroundTasks, Depends
from sqlmodel import Session, select

from ...api.deps import check_webhook_token, get_session
from ...models import CallEvent, CallSession, CallStatus, WebhookEventIngest
from ...schemas import WebhookEvent
from ...services import dispatcher

router = APIRouter(prefix="/api/v1/webhooks", tags=["webhooks"])


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


def _add_event(session: Session, call_id, event_type: str, source: str, payload: dict) -> None:
    payload_json = json.dumps(payload, ensure_ascii=False, sort_keys=True)
    provider_key = _make_provider_event_key(call_id, event_type, source, payload_json, payload.get("event_id") if isinstance(payload, dict) else None)
    existed = _get_duplicate_event(session, call_id, provider_key)
    if existed is not None:
        existed.repeat_count = max(1, int(existed.repeat_count or 1)) + 1
        session.add(existed)
        return

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


def _make_provider_event_key(
    call_id,
    event_type: str,
    source: str,
    payload_json: str,
    explicit_event_id: str | None = None,
) -> str:
    seed = explicit_event_id or f"{call_id}:{event_type}:{source}:{payload_json}"
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
    if mapped:
        call.status = mapped
    telephony_call_id = payload.payload.get("telephony_call_id")
    if telephony_call_id:
        call.telephony_call_id = str(telephony_call_id)
    if payload.payload.get("hangup_reason"):
        call.last_error = payload.payload.get("hangup_reason")
    if mapped in {
        CallStatus.COMPLETED,
        CallStatus.FAILED,
        CallStatus.BUSY,
        CallStatus.VOICEMAIL,
        CallStatus.NO_ANSWER,
    }:
        call.finished_at = datetime.utcnow()
    if payload.payload.get("summary"):
        call.summary = (call.summary or "") + "\n" + str(payload.payload.get("summary"))

    session.add(call)
    _add_event(session, call.id, "status", "telephony", payload.payload)
    session.commit()

    if mapped == CallStatus.ANSWERED and str(call.mode) != "human_only":
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
    call.last_transcript = payload.transcript
    if payload.transcript:
        call.summary = f"{(call.summary or '').rstrip()}\n{payload.transcript}".strip()
    session.add(call)
    _add_event(session, call.id, "transcript", "telephony", payload.payload)
    session.commit()

    if str(call.mode) != "human_only":
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
    url = payload.payload.get("url")
    if url:
        call.recording_url = str(url)
    session.add(call)
    _add_event(session, call.id, "recording", "telephony", payload.payload)
    session.commit()
    return {"result": "ok"}
