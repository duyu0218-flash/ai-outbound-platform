import hashlib
import json

from fastapi import APIRouter, BackgroundTasks, Depends
from sqlalchemy import update
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ...api.deps import check_sms_webhook_token, check_webhook_token, get_session
from ...clock import utc_now
from ...config import get_settings
from ...models import CallEvent, CallMode, CallSession, CallStatus, Campaign, HandoffRequest, HandoffState, RecordingAsset, SmsLog, User, WebhookEventIngest
from ...schemas import MediaWebhookEvent, SmsStatusWebhook, SpeechWebhookEvent, WebhookEvent
from ...services import dispatcher
from ...services.business_callbacks import deliver_business_callback
from ...services.call_service import complete_campaign_if_terminal, schedule_campaign_retry
from ...services.call_analysis import analyze_call
from ...services.realtime_voice import apply_media_event, ingest_speech_turn, interrupt_playback

router = APIRouter(prefix="/api/v1/webhooks", tags=["webhooks"])

SMS_TERMINAL_STATES = {"delivered", "failed", "undelivered", "rejected", "expired"}
settings = get_settings()

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
    status = (raw_status or "").strip().lower()
    configured = {
        CallStatus.NO_ANSWER: {str(value).strip().lower() for value in settings.no_answer_codes},
        CallStatus.BUSY: {str(value).strip().lower() for value in settings.busy_codes},
        CallStatus.VOICEMAIL: {str(value).strip().lower() for value in settings.voicemail_codes},
    }
    for mapped_status, provider_codes in configured.items():
        if status in provider_codes:
            return mapped_status
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
        if call.human_agent_id is not None:
            assigned_agent = session.get(User, call.human_agent_id)
            if assigned_agent is not None and assigned_agent.agent_status == "busy":
                assigned_agent.agent_status = "ready"
                assigned_agent.last_seen_at = utc_now()
                assigned_agent.updated_at = utc_now()
                session.add(assigned_agent)
        for handoff in session.exec(
            select(HandoffRequest).where(
                HandoffRequest.call_session_id == call.id,
                HandoffRequest.state.in_({HandoffState.WAITING, HandoffState.ACCEPTED}),
            )
        ).all():
            handoff.state = (
                HandoffState.COMPLETED
                if handoff.state == HandoffState.ACCEPTED
                else HandoffState.EXPIRED
            )
            handoff.completed_at = utc_now()
            handoff.updated_at = utc_now()
            session.add(handoff)
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
        analyze_call(session, call)

    if status_applied and mapped == CallStatus.ANSWERED and call.mode != CallMode.HUMAN_ONLY:
        background_tasks.add_task(dispatcher.run_ai_turn, call_id=call.id, transcript=payload.transcript or "")
    if status_applied and mapped is not None:
        background_tasks.add_task(
            deliver_business_callback,
            tenant_id=call.tenant_id,
            call_id=call.id,
            event_type="call.status",
            data={"status": mapped.value, "hangup_reason": payload.payload.get("hangup_reason")},
        )

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
    speech_payload = SpeechWebhookEvent(
        call_id=call.id,
        event_id=str(payload.payload.get("event_id") or _make_provider_event_key(
            call.id,
            "transcript",
            "telephony",
            json.dumps(payload.payload, ensure_ascii=False, sort_keys=True),
        )),
        transcript=payload.transcript or "",
        is_final=bool(payload.payload.get("is_final", True)),
        speaker_role=str(payload.payload.get("speaker_role") or "customer"),
        channel_id=str(payload.payload.get("channel_id") or "inbound"),
        confidence=payload.payload.get("confidence"),
        start_ms=payload.payload.get("start_ms"),
        end_ms=payload.payload.get("end_ms"),
        asr_provider=str(payload.payload.get("asr_provider") or "telephony"),
        barge_in=bool(payload.payload.get("barge_in", False)),
        attempt=payload.payload.get("attempt"),
    )
    ingest_speech_turn(session, call, speech_payload)
    call.last_transcript = payload.transcript
    if payload.transcript:
        call.summary = f"{(call.summary or '').rstrip()}\n{payload.transcript}".strip()
    session.add(call)
    session.commit()

    if speech_payload.barge_in:
        background_tasks.add_task(interrupt_playback, call.id)
    if speech_payload.is_final and call.mode != CallMode.HUMAN_ONLY:
        background_tasks.add_task(dispatcher.run_ai_turn, call_id=call.id, transcript=payload.transcript or "")
    background_tasks.add_task(
        deliver_business_callback,
        tenant_id=call.tenant_id,
        call_id=call.id,
        event_type="call.transcript",
        data={"transcript": payload.transcript or ""},
    )
    return {"result": "ok"}


@router.post("/telephony/speech")
def telephony_speech(
    payload: SpeechWebhookEvent,
    background_tasks: BackgroundTasks,
    _: None = Depends(check_webhook_token),
    session: Session = Depends(get_session),
):
    call = session.get(CallSession, payload.call_id)
    if call is None:
        return {"result": "ignore"}
    if payload.attempt is not None and payload.attempt != call.attempts:
        return {"result": "ignored", "reason": "stale_attempt"}
    turn, duplicate = ingest_speech_turn(session, call, payload)
    if duplicate:
        return {"result": "ok", "duplicate": True, "turn_id": turn.id}
    if payload.barge_in:
        background_tasks.add_task(interrupt_playback, call.id)
    if payload.is_final and call.mode != CallMode.HUMAN_ONLY:
        background_tasks.add_task(dispatcher.run_ai_turn, call_id=call.id, transcript=payload.transcript)
    background_tasks.add_task(
        deliver_business_callback,
        tenant_id=call.tenant_id,
        call_id=call.id,
        event_type="call.speech_final" if payload.is_final else "call.speech_partial",
        data={
            "turn_id": turn.id,
            "transcript": payload.transcript,
            "is_final": payload.is_final,
            "confidence": payload.confidence,
        },
    )
    return {"result": "ok", "duplicate": False, "turn_id": turn.id}


@router.post("/telephony/media")
def telephony_media(
    payload: MediaWebhookEvent,
    _: None = Depends(check_webhook_token),
    session: Session = Depends(get_session),
):
    call = session.get(CallSession, payload.call_id)
    if call is None:
        return {"result": "ignore"}
    realtime = apply_media_event(session, call, payload)
    return {"result": "ok", "realtime_session_id": realtime.id, "state": realtime.state.value}


@router.post("/telephony/recording")
def telephony_recording(
    payload: WebhookEvent,
    background_tasks: BackgroundTasks,
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
        existing_asset = session.exec(
            select(RecordingAsset).where(
                RecordingAsset.call_session_id == call.id,
                RecordingAsset.provider_url == str(url),
            )
        ).first()
        if existing_asset is None:
            session.add(
                RecordingAsset(
                    tenant_id=call.tenant_id,
                    call_session_id=call.id,
                    provider_recording_id=str(payload.payload.get("recording_id") or "") or None,
                    provider_url=str(url),
                    storage_uri=str(payload.payload.get("storage_uri") or ""),
                    state=str(payload.payload.get("state") or "available"),
                    duration_sec=payload.payload.get("duration_sec"),
                    media_format=str(payload.payload.get("format") or ""),
                    channel_count=int(payload.payload.get("channel_count") or 1),
                    checksum_sha256=payload.payload.get("checksum_sha256"),
                )
            )
    session.add(call)
    session.commit()
    if url:
        background_tasks.add_task(
            deliver_business_callback,
            tenant_id=call.tenant_id,
            call_id=call.id,
            event_type="call.recording",
            data={"url": str(url)},
        )
    return {"result": "ok"}


@router.post("/sms/status")
def sms_status(
    payload: SmsStatusWebhook,
    _: None = Depends(check_sms_webhook_token),
    session: Session = Depends(get_session),
):
    if payload.sms_log_id is None and not payload.provider_message_id:
        return {"result": "ignored", "reason": "missing_message_identifier"}
    sms_log = None
    if payload.sms_log_id is not None:
        sms_log = session.get(SmsLog, payload.sms_log_id)
    if sms_log is None and payload.provider_message_id:
        sms_log = session.exec(
            select(SmsLog)
            .where(SmsLog.provider_message_id == payload.provider_message_id)
            .order_by(SmsLog.created_at.desc())
        ).first()
    if sms_log is None:
        return {"result": "ignore"}

    normalized_state = payload.state.strip().lower()
    if sms_log.state in SMS_TERMINAL_STATES and sms_log.state != normalized_state:
        return {"result": "ignored", "reason": "terminal_state"}
    sms_log.state = normalized_state
    sms_log.provider_error = payload.error
    if payload.provider_message_id and not sms_log.provider_message_id:
        sms_log.provider_message_id = payload.provider_message_id
    if normalized_state in {"sent", "delivered"} and sms_log.sent_at is None:
        sms_log.sent_at = utc_now()
    sms_log.updated_at = utc_now()
    session.add(sms_log)
    session.commit()
    return {"result": "ok"}
