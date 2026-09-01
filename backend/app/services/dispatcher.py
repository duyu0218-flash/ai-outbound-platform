from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import uuid
import weakref
from contextlib import asynccontextmanager
from datetime import timedelta
from time import perf_counter
from typing import Any, Dict

import httpx
from redis import asyncio as async_redis
from sqlmodel import select

from ..config import get_settings
from ..clock import utc_now
from ..db import session_scope
from ..models import (
    CallEvent,
    CallMetric,
    CallSession,
    CallStatus,
    Campaign,
    HandoffRequest,
    RealtimeSession,
    RealtimeState,
    ScriptFlowVersion,
    SmsLog,
    SpeechTurn,
    User,
)
from ..schemas import AiTurnRequest, AiTurnResult
from .telephony import SmsAdapter, get_sms_adapter, with_retry, get_telephony_adapter
from .call_service import resolve_campaign_script
from .admin_settings import get_admin_setting
from .task_queue import enqueue_business_callback, process_task
from .knowledge import retrieve_knowledge
from .script_flow import load_graph, simulate

settings = get_settings()
logger = logging.getLogger(__name__)
_local_turn_locks: weakref.WeakValueDictionary[str, asyncio.Lock] = weakref.WeakValueDictionary()


def _conversation_history(session, call: CallSession, limit: int) -> list[dict[str, str]]:
    turns = session.exec(
        select(SpeechTurn)
        .where(
            SpeechTurn.call_session_id == call.id,
            SpeechTurn.is_final.is_(True),
        )
        .order_by(SpeechTurn.created_at.desc(), SpeechTurn.id.desc())
        .limit(max(1, min(limit, 50)))
    ).all()
    return [
        {
            "role": "assistant" if turn.speaker_role in {"ai", "agent", "assistant"} else "user",
            "content": turn.normalized_transcript or turn.transcript,
        }
        for turn in reversed(turns)
        if (turn.normalized_transcript or turn.transcript).strip()
    ]


def _apply_output_guard(session, call: CallSession, result: AiTurnResult, ai_config: dict[str, Any]) -> AiTurnResult:
    text = (result.tts_text or "").strip()
    if not text:
        return result
    phrases = [
        item.strip().lower()
        for item in str(ai_config.get("forbidden_phrases") or "").replace("\n", ",").split(",")
        if item.strip()
    ]
    max_chars = max(20, int(ai_config.get("max_reply_chars") or 240))
    violation = "reply_too_long" if len(text) > max_chars else next(
        (f"forbidden_phrase:{phrase}" for phrase in phrases if phrase in text.lower()),
        "",
    )
    if not violation:
        return result
    fallback = str(ai_config.get("fallback_reply") or "抱歉，这个问题需要由人工客服为您确认。").strip()
    should_handoff = call.mode.value in {"ai_handoff", "mixed_human_first"}
    session.add(
        CallMetric(
            tenant_id=call.tenant_id,
            call_session_id=call.id,
            stage="ai.output_guard",
            provider="policy",
            success=False,
            error_code="AI_OUTPUT_BLOCKED",
            detail=violation,
        )
    )
    return result.model_copy(
        update={
            "action": "handoff" if should_handoff else "speak",
            "tts_text": fallback,
            "handoff_to_human": should_handoff,
        }
    )


@asynccontextmanager
async def _ai_turn_lock(call_id: str):
    """Serialize turns locally and across workers when Redis is configured."""
    local_lock = _local_turn_locks.setdefault(call_id, asyncio.Lock())
    async with local_lock:
        redis_client = async_redis.from_url(settings.redis_url, decode_responses=True) if settings.redis_url else None
        redis_key = f"ai-outbound:ai-turn:{call_id}"
        lock_token = uuid.uuid4().hex
        acquired = redis_client is None
        try:
            if redis_client is not None:
                loop = asyncio.get_running_loop()
                deadline = loop.time() + max(0.1, settings.ai_turn_lock_wait_sec)
                while loop.time() < deadline:
                    try:
                        acquired = bool(
                            await redis_client.set(
                                redis_key,
                                lock_token,
                                ex=max(5, settings.ai_turn_lock_ttl_sec),
                                nx=True,
                            )
                        )
                    except Exception:
                        if settings.env.lower() in {"prod", "production"}:
                            raise RuntimeError("Redis is unavailable for AI turn serialization")
                        logger.warning("Redis unavailable; AI turn serialization is process-local", exc_info=True)
                        acquired = True
                        break
                    if acquired:
                        break
                    await asyncio.sleep(0.05)
            if not acquired:
                raise TimeoutError("timed out waiting for the previous AI turn")
            yield
        finally:
            if redis_client is not None:
                if acquired:
                    try:
                        await redis_client.eval(
                            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                            1,
                            redis_key,
                            lock_token,
                        )
                    except Exception:
                        logger.warning("failed to release AI turn Redis lock", exc_info=True)
                await redis_client.aclose()


def _run_script_flow_turn(*, session, call: CallSession, transcript: str) -> AiTurnResult | None:
    if call.script_flow_version_id is None:
        return None
    version = session.get(ScriptFlowVersion, call.script_flow_version_id)
    if not version or version.tenant_id != call.tenant_id or version.status != "published":
        raise RuntimeError("bound script flow version is unavailable")
    graph = load_graph(version.graph_json)
    decision = simulate(graph, call.flow_node_key, transcript, silence=not transcript.strip())
    node_map = {node.id: node for node in graph.nodes}
    current = node_map.get(decision.current_node_id)
    target = node_map.get(decision.next_node_id or "")
    # A customer may respond immediately after a message. Advance through the
    # deterministic message->listen edge, then evaluate that same transcript
    # against the listen node so the first answer is never discarded.
    if transcript.strip() and current and current.type in {"start", "message"} and target and target.type == "listen":
        decision = simulate(graph, target.id, transcript, silence=False)
    call.flow_node_key = decision.next_node_id or decision.current_node_id
    action = decision.action
    if action in {"wait", "listen", "continue"}:
        action = "continue"
    return AiTurnResult(
        action=action,
        tts_text=decision.prompt or None,
        handoff_to_human=action == "handoff",
    )


async def request_ai_turn(
    *,
    call_id: str,
    phone: str,
    mode: str,
    script: str = "",
    transcript: str = "",
    context: Dict[str, Any] | None = None,
    agent_url: str | None = None,
) -> AiTurnResult:
    payload = AiTurnRequest(
        call_id=call_id,
        phone=phone,
        mode=mode,
        script=script,
        transcript=transcript,
        context=context or {},
    )
    async with httpx.AsyncClient(timeout=settings.ai_callback_timeout_sec) as client:
        headers = (
            {"Authorization": f"Bearer {settings.ai_agent_service_token}"}
            if settings.ai_agent_service_token
            else {}
        )
        response = await client.post(
            f"{(agent_url or settings.ai_agent_url).rstrip('/')}/agent/turn",
            json=payload.model_dump(mode="json"),
            headers=headers,
        )
        if response.status_code != 200:
            raise RuntimeError(f"ai service error: {response.status_code} {response.text}")
        data = response.json()
    return AiTurnResult(**data)


async def append_event(
    *,
    session,
    call_id,
    event_type: str,
    source: str,
    payload: Dict[str, Any],
) -> CallEvent:
    event = CallEvent(
        call_session_id=call_id,
        event_type=event_type,
        source=source,
        payload=json.dumps(payload, ensure_ascii=False),
    )
    session.add(event)
    session.commit()
    session.refresh(event)
    return event


async def run_ai_turn(
    *,
    call_id,
    transcript: str = "",
    durable: bool = False,
) -> None:
    async with _ai_turn_lock(str(call_id)):
        await _run_ai_turn_locked(call_id=call_id, transcript=transcript, durable=durable)


async def _run_ai_turn_locked(*, call_id, transcript: str = "", durable: bool = False) -> None:
    # independent session for background execution
    with session_scope() as session:
        call = session.get(CallSession, call_id)
        if not call:
            return
        if call.status not in {CallStatus.ANSWERED, CallStatus.IN_AI}:
            return

        await append_event(
            session=session,
            call_id=call.id,
            event_type="ai_start",
            source="dispatcher",
            payload={"transcript": transcript},
        )

        try:
            ai_started = perf_counter()
            ai_config = get_admin_setting(session, call.tenant_id, "ai")
            campaign = session.get(Campaign, call.campaign_id) if call.campaign_id is not None else None
            language = str(ai_config.get("language") or "zh-CN")
            result = _run_script_flow_turn(session=session, call=call, transcript=transcript)
            provider = "script_flow"
            knowledge: list[dict[str, Any]] = []
            if result is None:
                if not ai_config.get("enabled", True):
                    raise RuntimeError("AI service is disabled for tenant")
                campaign_script = resolve_campaign_script(
                    session,
                    tenant_id=call.tenant_id,
                    campaign_id=call.campaign_id,
                )
                knowledge = retrieve_knowledge(session, call.tenant_id, transcript)
                provider = str(ai_config.get("llm_provider") or "rule")
                history = _conversation_history(
                    session,
                    call,
                    int(ai_config.get("conversation_history_turns") or 12),
                )
                result = await request_ai_turn(
                    call_id=str(call.id),
                    phone=call.phone,
                    mode=call.mode.value,
                    script=campaign_script,
                    transcript=transcript,
                    context={
                        "campaign_id": call.campaign_id,
                        "tenant_id": call.tenant_id,
                        "language": language,
                        "recording_enabled": campaign.recording_enabled if campaign else True,
                        "hangup_sms_enabled": campaign.hangup_sms_enabled if campaign else True,
                        "llm_provider": str(ai_config.get("llm_provider") or "rule"),
                        "llm_model": str(ai_config.get("llm_model") or ""),
                        "external_llm_enabled": bool(ai_config.get("external_llm_enabled", False)),
                        "knowledge": knowledge,
                        "conversation": history,
                    },
                    agent_url=str(ai_config.get("agent_url") or settings.ai_agent_url),
                )
            result = _apply_output_guard(session, call, result, ai_config)
            session.add(
                CallMetric(
                    tenant_id=call.tenant_id,
                    call_session_id=call.id,
                    stage="ai.turn",
                    provider=provider,
                    duration_ms=int((perf_counter() - ai_started) * 1000),
                    success=True,
                    detail=f"knowledge_hits={len(knowledge)}",
                )
            )
            session.commit()
            await _apply_ai_action(session=session, call=call, result=result)
        except Exception as exc:
            if not durable:
                call.status = CallStatus.FAILED
            call.last_error = f"AI调用失败: {exc}"
            session.add(call)
            session.add(
                CallMetric(
                    tenant_id=call.tenant_id,
                    call_session_id=call.id,
                    stage="ai.turn",
                    success=False,
                    error_code="AI_TURN_FAILED",
                    detail=str(exc)[:2000],
                )
            )
            session.commit()
            await append_event(
                session=session,
                call_id=call.id,
                event_type="error",
                source="dispatcher",
                payload={"module": "dispatcher", "error": str(exc)},
            )
            if durable:
                raise


async def _wait_for_playback_completion(call_id, playback_id: str) -> bool:
    deadline = asyncio.get_running_loop().time() + max(1, int(settings.tts_playback_timeout_sec))
    while asyncio.get_running_loop().time() < deadline:
        with session_scope() as playback_session:
            realtime = playback_session.exec(
                select(RealtimeSession).where(RealtimeSession.call_session_id == call_id)
            ).first()
            if realtime is not None and (
                realtime.state in {RealtimeState.LISTENING, RealtimeState.INTERRUPTED, RealtimeState.CLOSED}
                and realtime.playback_id != playback_id
            ):
                return True
        await asyncio.sleep(0.1)
    return False


async def _apply_ai_action(*, session, call: CallSession, result: AiTurnResult) -> None:
    campaign = session.get(Campaign, call.campaign_id) if call.campaign_id is not None else None
    ai_config = get_admin_setting(session, call.tenant_id, "ai")
    adapter = get_telephony_adapter(
        session=session,
        tenant_id=call.tenant_id,
        line_id=call.telephony_line_id,
    )
    playback_id: str | None = None
    playback_complete = False
    if result.tts_text:
        tts_started = perf_counter()
        try:
            response = await with_retry(
                lambda: adapter.speak(
                    call_id=str(call.id),
                    text=result.tts_text or "",
                    language=str(ai_config.get("language") or "zh-CN"),
                    voice=str(ai_config.get("voice") or ""),
                    provider=str(ai_config.get("tts_provider") or ""),
                )
            )
            playback_id = str(response.get("playback_id") or "") or None
            playback_complete = bool(response.get("playback_complete", False))
            realtime = session.exec(
                select(RealtimeSession).where(RealtimeSession.call_session_id == call.id)
            ).first()
            if realtime is not None:
                realtime.state = RealtimeState.SPEAKING
                realtime.playback_id = playback_id
                realtime.updated_at = utc_now()
                session.add(realtime)
            normalized_reply = " ".join((result.tts_text or "").split())
            reply_event_key = hashlib.sha256(
                f"{call.id}:ai:{realtime.turn_sequence if realtime else 0}:{normalized_reply}".encode()
            ).hexdigest()
            existing_reply = session.exec(
                select(SpeechTurn).where(
                    SpeechTurn.call_session_id == call.id,
                    SpeechTurn.provider_event_key == reply_event_key,
                )
            ).first()
            if existing_reply is None:
                session.add(SpeechTurn(
                    tenant_id=call.tenant_id,
                    call_session_id=call.id,
                    provider_event_key=reply_event_key,
                    turn_index=realtime.turn_sequence if realtime else 0,
                    speaker_role="ai",
                    channel_id="outbound",
                    transcript=result.tts_text or "",
                    normalized_transcript=normalized_reply,
                    is_final=True,
                    asr_provider="",
                ))
            session.add(
                CallMetric(
                    tenant_id=call.tenant_id,
                    call_session_id=call.id,
                    stage="tts.dispatch",
                    provider=str(ai_config.get("tts_provider") or "gateway"),
                    duration_ms=int((perf_counter() - tts_started) * 1000),
                    success=True,
                )
            )
        except Exception as exc:
            session.add(
                CallMetric(
                    tenant_id=call.tenant_id,
                    call_session_id=call.id,
                    stage="tts.dispatch",
                    provider=str(ai_config.get("tts_provider") or "gateway"),
                    duration_ms=int((perf_counter() - tts_started) * 1000),
                    success=False,
                    error_code="TTS_DISPATCH_FAILED",
                    detail=str(exc)[:2000],
                )
            )
            session.commit()
            raise
    if result.action == "hangup":
        if playback_id and not playback_complete:
            # Make the playback id visible to the webhook session before
            # waiting for the gateway's listening/interrupted/closed event.
            session.commit()
            playback_complete = await _wait_for_playback_completion(call.id, playback_id)
            if not playback_complete:
                session.add(
                    CallMetric(
                        tenant_id=call.tenant_id,
                        call_session_id=call.id,
                        stage="tts.playback",
                        provider=str(ai_config.get("tts_provider") or "gateway"),
                        success=False,
                        error_code="TTS_PLAYBACK_TIMEOUT",
                        detail=f"playback_id={playback_id}",
                    )
                )
        await with_retry(lambda: adapter.hangup(call_id=str(call.id), reason="ai_decision"))
        call.status = CallStatus.COMPLETED
        call.finished_at = utc_now()
    elif result.action == "handoff" or result.handoff_to_human:
        presence_cutoff = utc_now() - timedelta(seconds=max(30, settings.agent_presence_timeout_sec))
        assigned_agent = session.exec(
            select(User)
            .where(
                User.tenant_id == call.tenant_id,
                User.role == "agent",
                User.enabled.is_(True),
                User.agent_status == "ready",
                User.last_seen_at.is_not(None),
                User.last_seen_at >= presence_cutoff,
            )
            .order_by(User.last_seen_at.asc(), User.id.asc())
        ).first()
        target_group = f"agent:{assigned_agent.id}" if assigned_agent is not None else None
        call.status = CallStatus.WAITING_HUMAN
        call.handoff_reason = "ai_decision"
        if assigned_agent is not None:
            call.human_agent_id = assigned_agent.id
            assigned_agent.agent_status = "busy"
            assigned_agent.last_seen_at = utc_now()
            assigned_agent.updated_at = utc_now()
            session.add(assigned_agent)
        session.add(
            HandoffRequest(
                tenant_id=call.tenant_id,
                call_session_id=call.id,
                assigned_agent_id=assigned_agent.id if assigned_agent is not None else None,
                reason="ai_decision",
                target_group=target_group or "default",
            )
        )
    else:
        call.status = CallStatus.IN_AI

    hangup_sms_allowed = campaign.hangup_sms_enabled if campaign else True
    if result.hangup_sms and hangup_sms_allowed:
        sms_config = get_admin_setting(session, call.tenant_id, "sms")
        sms_text = str(sms_config.get("hangup_template") or result.hangup_sms)
        await send_sms_text(session, call, sms_text)
        if call.status != CallStatus.WAITING_HUMAN:
            call.status = CallStatus.COMPLETED
            call.finished_at = utc_now()

    if result.escalate_priority:
        call.handoff_reason = f"escalate_priority={result.escalate_priority}"

    if result.next_keywords:
        # not persisted yet; add to summary for audit
        call.summary = (
            (call.summary or "").strip()
            + f"\n[AI next_keywords] {','.join(result.next_keywords)}"
        ).strip()

    session.add(call)
    session.commit()
    decision_event = await append_event(
        session=session,
        call_id=call.id,
        event_type="ai_decision",
        source="dispatcher",
        payload={
            "action": result.action,
            "tts_dispatched": bool(result.tts_text),
            "handoff_to_human": result.handoff_to_human,
            "hangup_sms": bool(result.hangup_sms and hangup_sms_allowed),
            "next_keywords": result.next_keywords,
            "escalate_priority": result.escalate_priority,
            "resulting_status": call.status.value,
        },
    )
    callback_task = enqueue_business_callback(
        session,
        tenant_id=call.tenant_id,
        call_id=call.id,
        event_type="call.ai_decision",
        data={
            "action": result.action,
            "handoff_to_human": result.handoff_to_human,
            "tts_dispatched": bool(result.tts_text),
            "resulting_status": call.status.value,
        },
        idempotency_key=f"callback:ai-decision:{decision_event.id}",
    )
    await process_task(callback_task.id)


async def send_sms_text(session, call: CallSession, text: str) -> None:
    sms_config = get_admin_setting(session, call.tenant_id, "sms")
    if not sms_config.get("enabled", True):
        state = "disabled"
        sms_log = SmsLog(
            tenant_id=call.tenant_id,
            call_session_id=call.id,
            to_phone=call.phone,
            template_code="hangup_sms",
            content=text,
            state=state,
        )
        session.add(sms_log)
        session.commit()
        return
    adapter: SmsAdapter = get_sms_adapter(sms_config)
    try:
        sms_result = await with_retry(lambda: adapter.send_sms(call.phone, text))
        state = str(sms_result.get("state", "sent"))
        provider_message_id = str(sms_result.get("message_id") or sms_result.get("provider_message_id") or "") or None
    except Exception as exc:
        state = "failed"
        provider_message_id = None
        call.last_error = f"短信发送失败: {exc}"

    sms_log = SmsLog(
        tenant_id=call.tenant_id,
        call_session_id=call.id,
        to_phone=call.phone,
        template_code="hangup_sms",
        content=text,
        state=state,
        provider_message_id=provider_message_id,
        provider_error=call.last_error if state == "failed" else None,
        sent_at=utc_now() if state != "failed" else None,
    )
    session.add(sms_log)
    session.add(call)
    session.commit()
