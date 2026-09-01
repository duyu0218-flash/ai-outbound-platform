from __future__ import annotations

import hashlib
import json
from time import perf_counter
from typing import Any
from uuid import UUID

from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ..clock import utc_now
from ..models import (
    CallEvent,
    CallMetric,
    CallSession,
    RealtimeSession,
    RealtimeState,
    SpeechTurn,
)
from ..schemas import MediaWebhookEvent, SpeechWebhookEvent
from .telephony import get_telephony_adapter, with_retry


def event_key(call_id: UUID, provider_event_id: str) -> str:
    return hashlib.sha256(f"{call_id}:speech:{provider_event_id}".encode()).hexdigest()


def get_or_create_realtime_session(session: Session, call: CallSession) -> RealtimeSession:
    realtime = session.exec(
        select(RealtimeSession).where(RealtimeSession.call_session_id == call.id)
    ).first()
    if realtime is None:
        realtime = RealtimeSession(tenant_id=call.tenant_id, call_session_id=call.id)
        session.add(realtime)
        session.commit()
        session.refresh(realtime)
    return realtime


def ingest_speech_turn(
    session: Session,
    call: CallSession,
    payload: SpeechWebhookEvent,
) -> tuple[SpeechTurn, bool]:
    key = event_key(call.id, payload.event_id)
    existing = session.exec(
        select(SpeechTurn).where(
            SpeechTurn.call_session_id == call.id,
            SpeechTurn.provider_event_key == key,
        )
    ).first()
    if existing is not None:
        return existing, True

    realtime = get_or_create_realtime_session(session, call)
    if payload.is_final:
        realtime.turn_sequence += 1
    realtime.state = RealtimeState.THINKING if payload.is_final else RealtimeState.LISTENING
    realtime.updated_at = utc_now()
    if realtime.started_at is None:
        realtime.started_at = utc_now()

    normalized = " ".join(payload.transcript.split())
    turn = SpeechTurn(
        tenant_id=call.tenant_id,
        call_session_id=call.id,
        provider_event_key=key,
        turn_index=realtime.turn_sequence,
        speaker_role=payload.speaker_role,
        channel_id=payload.channel_id,
        transcript=payload.transcript,
        normalized_transcript=normalized,
        is_final=payload.is_final,
        confidence=payload.confidence,
        start_ms=payload.start_ms,
        end_ms=payload.end_ms,
        asr_provider=payload.asr_provider,
    )
    session.add(realtime)
    session.add(turn)
    session.add(
        CallMetric(
            tenant_id=call.tenant_id,
            call_session_id=call.id,
            stage="asr.final" if payload.is_final else "asr.partial",
            provider=payload.asr_provider,
            duration_ms=max(0, payload.end_ms - payload.start_ms)
            if payload.start_ms is not None and payload.end_ms is not None
            else None,
            success=bool(normalized) if payload.is_final else True,
            error_code="EMPTY_FINAL_TRANSCRIPT" if payload.is_final and not normalized else None,
            detail=f"confidence={payload.confidence}" if payload.confidence is not None else "",
        )
    )
    session.add(
        CallEvent(
            call_session_id=call.id,
            event_type="speech_final" if payload.is_final else "speech_partial",
            source="asr",
            payload=json.dumps(payload.model_dump(mode="json"), ensure_ascii=False),
        )
    )
    if payload.is_final:
        call.last_transcript = payload.transcript
        if normalized:
            call.summary = f"{(call.summary or '').rstrip()}\n客户：{normalized}".strip()
        session.add(call)
    try:
        session.commit()
    except IntegrityError:
        session.rollback()
        existing = session.exec(
            select(SpeechTurn).where(
                SpeechTurn.call_session_id == call.id,
                SpeechTurn.provider_event_key == key,
            )
        ).first()
        if existing is None:
            raise
        return existing, True
    session.refresh(turn)
    return turn, False


def apply_media_event(session: Session, call: CallSession, payload: MediaWebhookEvent) -> RealtimeSession:
    realtime = get_or_create_realtime_session(session, call)
    realtime.state = payload.state
    if payload.attempt is not None:
        realtime.attempt = payload.attempt
    realtime.provider_session_id = payload.provider_session_id or realtime.provider_session_id
    realtime.playback_id = payload.playback_id
    realtime.codec = payload.codec
    realtime.sample_rate = payload.sample_rate
    realtime.channel_count = payload.channel_count
    realtime.updated_at = utc_now()
    if realtime.started_at is None and payload.state != RealtimeState.CREATED:
        realtime.started_at = utc_now()
    if payload.state == RealtimeState.CLOSED:
        realtime.ended_at = utc_now()
    session.add(realtime)
    session.add(
        CallMetric(
            tenant_id=call.tenant_id,
            call_session_id=call.id,
            stage=f"media.{payload.state.value}",
            provider=payload.provider,
            duration_ms=payload.duration_ms,
            success=not bool(payload.error_code),
            error_code=payload.error_code,
        )
    )
    session.add(
        CallEvent(
            call_session_id=call.id,
            event_type="media_state",
            source="media_gateway",
            payload=json.dumps(payload.model_dump(mode="json"), ensure_ascii=False),
        )
    )
    session.commit()
    session.refresh(realtime)
    return realtime


async def interrupt_playback(call_id: UUID) -> None:
    from ..db import session_scope

    started = perf_counter()
    with session_scope() as session:
        call = session.get(CallSession, call_id)
        if call is None:
            return
        success = True
        error = None
        try:
            adapter = get_telephony_adapter(
                session=session,
                tenant_id=call.tenant_id,
                line_id=call.telephony_line_id,
            )
            await with_retry(lambda: adapter.stop_speaking(call_id=str(call.id)))
            realtime = get_or_create_realtime_session(session, call)
            realtime.state = RealtimeState.INTERRUPTED
            realtime.playback_id = None
            realtime.updated_at = utc_now()
            session.add(realtime)
        except Exception as exc:
            success = False
            error = str(exc)
        session.add(
            CallMetric(
                tenant_id=call.tenant_id,
                call_session_id=call.id,
                stage="tts.interrupt",
                duration_ms=int((perf_counter() - started) * 1000),
                success=success,
                error_code="INTERRUPT_FAILED" if error else None,
                detail=(error or "")[:2000],
            )
        )
        session.add(
            CallEvent(
                call_session_id=call.id,
                event_type="barge_in",
                source="dispatcher",
                payload=json.dumps({"success": success, "error": error}, ensure_ascii=False),
            )
        )
        session.commit()
