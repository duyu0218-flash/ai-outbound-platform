from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import weakref
from contextlib import asynccontextmanager
from contextvars import ContextVar
from datetime import timedelta
from time import perf_counter
from typing import Any, Dict

import httpx
from .worker_runtime import http_client
from sqlalchemy import update
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
from .task_queue import enqueue_business_callback, process_task, notify_task
from .leases import LeaseLost
from .knowledge import retrieve_knowledge
from .script_flow import load_graph, simulate

settings = get_settings()
logger = logging.getLogger(__name__)
_local_turn_locks: weakref.WeakValueDictionary[str, asyncio.Lock] = weakref.WeakValueDictionary()
AI_ACTIVE_STATUSES = {CallStatus.ANSWERED, CallStatus.IN_AI}
_expected_turn_sequence = ContextVar("expected_turn_sequence", default=None)
_expected_speech_event = ContextVar("expected_speech_event", default=None)


def _ai_call_is_current(session, call: CallSession, attempt: int) -> bool:
    from .leases import assert_execution_permitted
    assert_execution_permitted()
    session.refresh(call)
    if call.attempts != attempt or call.status not in AI_ACTIVE_STATUSES:
        return False
    sequence = _expected_turn_sequence.get()
    if sequence is not None:
        realtime = session.exec(select(RealtimeSession).where(RealtimeSession.call_session_id == call.id)).first()
        if realtime is not None:
            session.refresh(realtime)
            if realtime.turn_sequence != sequence:
                return False
    return True


def _conversation_history(session, call: CallSession, limit: int) -> list[dict[str, str]]:
    turns = session.exec(
        select(SpeechTurn)
        .where(
            SpeechTurn.call_session_id == call.id,
            SpeechTurn.is_final.is_(True),
            SpeechTurn.attempt == call.attempts,
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
    from .leases import redis_lease
    # asyncio locks belong to one event loop; independent worker lanes use Redis.
    key = f"{id(asyncio.get_running_loop())}:{call_id}"
    local_lock = _local_turn_locks.setdefault(key, asyncio.Lock())
    async with local_lock:
        async with redis_lease(url=settings.redis_url, key=f"ai-outbound:ai-turn:{call_id}",
                               ttl=settings.ai_turn_lock_ttl_sec, wait_sec=settings.ai_turn_lock_wait_sec) as acquired:
            if not acquired:
                raise TimeoutError("timed out waiting for the previous AI turn")
            yield


def _run_script_flow_turn(*, session, call: CallSession, transcript: str) -> AiTurnResult | None:
    if call.script_flow_version_id is None:
        return None
    version = session.get(ScriptFlowVersion, call.script_flow_version_id)
    if not version or version.tenant_id != call.tenant_id or version.status != "published":
        raise RuntimeError("bound script flow version is unavailable")
    graph = load_graph(version.graph_json)
    from .conversation_policy import state_for,save_state
    flow_state = state_for(session,call)
    flow_data = json.loads(flow_state.data_json)
    cached = flow_data.get('flow_turn') or {}
    if flow_data.get('sequence') is not None and cached.get('sequence') == flow_data['sequence'] and cached.get('transcript') == transcript:
        call.flow_node_key = cached['node']
        return AiTurnResult.model_validate(cached['result'])
    decision = simulate(graph, call.flow_node_key, transcript, silence=not transcript.strip(),variables=flow_data.get('flow_variables'))
    node_map = {node.id: node for node in graph.nodes}
    current = node_map.get(decision.current_node_id)
    target = node_map.get(decision.next_node_id or "")
    # A customer may respond immediately after a message. Advance through the
    # deterministic message->listen edge, then evaluate that same transcript
    # against the listen node so the first answer is never discarded.
    if transcript.strip() and current and current.type in {"start", "message"} and target and target.type == "listen":
        decision = simulate(graph, target.id, transcript, silence=False,variables=decision.variables)
    visited=set()
    while decision.next_node_id and node_map[decision.next_node_id].type in {'set','branch'}:
        if decision.next_node_id in visited:
            raise RuntimeError('flow contains an automatic cycle')
        visited.add(decision.next_node_id)
        decision = simulate(graph,decision.next_node_id,transcript,silence=False,variables=decision.variables)
    flow_data['flow_variables']=decision.variables
    save_state(session,flow_state,flow_data)
    call.flow_node_key = decision.next_node_id or decision.current_node_id
    action = decision.action
    if action in {"wait", "listen", "continue"}:
        action = "continue"
    result = AiTurnResult(
        action=action,
        tts_text=decision.prompt or None,
        handoff_to_human=action == "handoff",
    )
    flow_data['flow_turn']={'sequence':flow_data.get('sequence'),'transcript':transcript,
        'node':call.flow_node_key,'result':result.model_dump(mode='json')}
    save_state(session,flow_state,flow_data)
    return result


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
    from .leases import assert_execution_permitted
    assert_execution_permitted()
    from .outbound_policy import require_platform_endpoint
    endpoint = require_platform_endpoint(agent_url or settings.ai_agent_url, settings.ai_agent_url, "AI")
    payload = AiTurnRequest(
        call_id=call_id,
        phone=phone,
        mode=mode,
        script=script,
        transcript=transcript,
        context=context or {},
    )
    async with http_client(max_connections=max(100, settings.task_ai_concurrency), timeout=settings.ai_callback_timeout_sec, follow_redirects=False, trust_env=False) as client:
        headers = (
            {"Authorization": f"Bearer {settings.ai_agent_service_token}"}
            if settings.ai_agent_service_token
            else {}
        )
        response = await client.post(
            f"{endpoint}/agent/turn",
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
    expected_attempt: int | None = None,
    expected_turn_sequence: int | None = None,
    expected_speech_event_id: str | None = None,
) -> None:
    token = _expected_turn_sequence.set(expected_turn_sequence)
    speech_token = _expected_speech_event.set(expected_speech_event_id)
    try:
        async with _ai_turn_lock(str(call_id)):
            await _run_ai_turn_locked(call_id=call_id, transcript=transcript, durable=durable, expected_attempt=expected_attempt)
    finally:
        _expected_turn_sequence.reset(token)
        _expected_speech_event.reset(speech_token)


async def _prepare_ai_turn(call_id, transcript, expected_attempt):
    with session_scope() as session:
        call = session.get(CallSession, call_id)
        if call is None:
            return None
        expected_attempt = call.attempts if expected_attempt is None else expected_attempt
        if not _ai_call_is_current(session, call, expected_attempt):
            return None
        expected_attempt = call.attempts
        await append_event(session=session, call_id=call.id, event_type="ai_start",
                           source="dispatcher", payload={"transcript": transcript})
        ai_started = perf_counter()
        ai_config = get_admin_setting(session, call.tenant_id, "ai")
        campaign = session.get(Campaign, call.campaign_id) if call.campaign_id is not None else None
        language = str(ai_config.get("language") or "zh-CN")
        from .conversation_policy import prepare_turn, state_for
        session.refresh(call, with_for_update=True)
        if not _ai_call_is_current(session, call, expected_attempt):
            return None
        original_flow_node = call.flow_node_key
        result = prepare_turn(session, call, transcript)
        policy_state = state_for(session, call)
        policy_snapshot = json.loads(policy_state.policy_json)
        ai_config = policy_snapshot.get('_ai') or ai_config
        language = str(policy_snapshot.get('language') or ai_config.get('language') or 'zh-CN')
        if result is None:
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
            campaign_script = policy_snapshot.get('_script', campaign_script)
            from .knowledge import retrieve_bound_knowledge
            knowledge = retrieve_bound_knowledge(session, policy_state, transcript, call.campaign_id)
            provider = str(ai_config.get("llm_provider") or "rule")
            history = _conversation_history(
                session,
                call,
                int(ai_config.get("conversation_history_turns") or 12),
            )
            ai_request = dict(
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
        snapshot = dict(call_id=call_id, attempt=expected_attempt, result=result,
            ai_request=ai_request if result is None else None, ai_config=ai_config,
            provider=provider, knowledge_count=len(knowledge), started=ai_started,
            flow_node_key=call.flow_node_key, model_wait_seconds=policy_snapshot.get('model_wait_seconds',4),
            model_wait_prompt=policy_snapshot.get('model_wait_prompt','请稍等，我正在为您确认。'))
        policy_data=json.loads(policy_state.data_json)
        policy_data['model_pending']=result is None
        from .conversation_policy import save_state
        save_state(session,policy_state,policy_data)
        # Keep script-flow progress private until output guards and the action phase.
        call.flow_node_key = original_flow_node
        session.add(call)
        session.commit()
        return snapshot


async def _finish_ai_turn(snapshot, result):
    with session_scope() as session:
        call = session.get(CallSession, snapshot['call_id'])
        if call is None or not _ai_call_is_current(session, call, snapshot['attempt']):
            return
        call.flow_node_key = snapshot['flow_node_key']
        from .conversation_policy import state_for,save_state
        product_state=state_for(session,call);product_data=json.loads(product_state.data_json)
        product_data['model_pending']=False;save_state(session,product_state,product_data)
        result = _apply_output_guard(session, call, result, snapshot['ai_config'])
        session.add(CallMetric(tenant_id=call.tenant_id, call_session_id=call.id,
            stage="ai.turn", provider=snapshot['provider'],
            duration_ms=int((perf_counter()-snapshot['started'])*1000), success=True,
            detail=f"knowledge_hits={snapshot['knowledge_count']}"))
        session.commit()
        await _apply_ai_action(session=session, call=call, result=result,
                               expected_attempt=snapshot['attempt'])


async def _fail_ai_turn(call_id, expected_attempt, exc):
    with session_scope() as session:
        call = session.get(CallSession, call_id)
        if call is None or not _ai_call_is_current(session, call, expected_attempt):
            return
        call.last_error=f"AI调用失败: {type(exc).__name__}"
        session.add(call)
        session.add(CallMetric(tenant_id=call.tenant_id, call_session_id=call.id,
            stage="ai.turn", success=False, error_code="AI_TURN_FAILED", detail=str(exc)[:2000]))
        session.commit()
        await append_event(session=session, call_id=call.id, event_type="error",
            source="dispatcher", payload={"module":"dispatcher", "error":str(exc)})
        from .conversation_policy import state_for, reply, save_state, add_work
        from ..product_schemas import ScenarioPolicy
        session.refresh(call, with_for_update=True)
        state = state_for(session, call)
        data = json.loads(state.data_json)
        if data.get('failure_handled'):
            return
        data['failure_handled'] = True
        data['model_pending'] = False
        data['outcome'] = 'service_failure'
        add_work(session, call, 'service_failure', f'failure:{call.id}:{call.attempts}', {'error':type(exc).__name__})
        save_state(session, state, data)
        session.commit()
        fallback = reply(ScenarioPolicy.model_validate_json(state.policy_json).failure_prompt, 'hangup')
        try:
            await _apply_ai_action(session=session, call=call, result=fallback, expected_attempt=expected_attempt)
        except LeaseLost:
            raise
        except Exception:
            # A failed speech service must not prevent bounded PBX termination.
            try:
                await _apply_ai_action(session=session, call=call, result=fallback,
                    expected_attempt=expected_attempt, fallback_audio=True)
                return
            except LeaseLost:
                raise
            except Exception:
                pass
            try:
                await _apply_ai_action(session=session, call=call,
                    result=AiTurnResult(action='hangup'), expected_attempt=expected_attempt)
            except LeaseLost:
                raise
            except Exception:
                logger.warning('fallback termination unconfirmed; PBX reconciliation retains capacity')


async def _run_ai_turn_locked(*, call_id, transcript: str = "", durable: bool = False, expected_attempt: int | None = None):
    try:
        snapshot = await _prepare_ai_turn(call_id, transcript, expected_attempt)
        if snapshot is None:
            return
        expected_attempt = snapshot['attempt']
        result = snapshot['result']
        if result is None:
            result = await _wait_for_ai(snapshot)
            if result is None:return
        await _finish_ai_turn(snapshot, result)
    except LeaseLost:
        raise
    except Exception as exc:
        await _fail_ai_turn(call_id, expected_attempt, exc)
        if durable:
            raise


async def run_ai_turn_async(*, pool, action_pool=None, call_id, transcript='', expected_attempt=None,
                            expected_turn_sequence=None, expected_speech_event_id=None):
    """Model latency holds a coroutine, never a DB connection or lane thread."""
    token = _expected_turn_sequence.set(expected_turn_sequence)
    speech_token = _expected_speech_event.set(expected_speech_event_id)
    try:
        async with _ai_turn_lock(str(call_id)):
            try:
                snapshot = await pool.run(_prepare_ai_turn, call_id, transcript, expected_attempt)
                if snapshot is None:
                    return
                expected_attempt = snapshot['attempt']
                result = snapshot['result']
                if result is None:
                    result = await _wait_for_ai(snapshot,pool=pool,action_pool=action_pool)
                    if result is None:return
                await (action_pool or pool).run(_finish_ai_turn, snapshot, result)
            except LeaseLost:
                raise
            except Exception as exc:
                await pool.run(_fail_ai_turn, call_id, expected_attempt, exc)
                raise
    finally:
        _expected_turn_sequence.reset(token)
        _expected_speech_event.reset(speech_token)


async def _speak_wait_notice(snapshot):
    with session_scope() as session:
        call=session.get(CallSession,snapshot['call_id'])
        if call is None or not _ai_call_is_current(session,call,snapshot['attempt']):return
        await _apply_ai_action(session=session,call=call,expected_attempt=snapshot['attempt'],
            result=AiTurnResult(action='speak',tts_text=snapshot['model_wait_prompt']))


async def _wait_for_ai(snapshot,pool=None,action_pool=None):
    request=asyncio.create_task(request_ai_turn(**snapshot['ai_request']))
    started=perf_counter();notice_sent=False
    try:
        while not request.done():
            done,_=await asyncio.wait({request},timeout=1)
            if done:break
            current=await pool.run(_ai_snapshot_current,snapshot) if pool else _ai_snapshot_current(snapshot)
            if not current:return None
            if not notice_sent and perf_counter()-started>=snapshot.get('model_wait_seconds',4):
                notice_sent=True
                if pool:await (action_pool or pool).run(_speak_wait_notice,snapshot)
                else:await _speak_wait_notice(snapshot)
        return request.result()
    finally:
        if not request.done():request.cancel()
        await asyncio.gather(request,return_exceptions=True)


def _ai_snapshot_current(snapshot):
    with session_scope() as session:
        call = session.get(CallSession, snapshot['call_id'])
        return call is not None and _ai_call_is_current(session, call, snapshot['attempt'])


async def resume_after_playback(payload):
    """Durable continuation, woken by media ACK or the bounded playback deadline."""
    from uuid import UUID
    with session_scope() as session:
        call = session.get(CallSession, UUID(payload["call_id"]))
        if call is None:
            return
        token = _expected_turn_sequence.set(payload.get("turn_sequence"))
        speech_token = _expected_speech_event.set(payload.get("speech_event_id"))
        try:
            if not _ai_call_is_current(session, call, payload["attempt"]):
                return
            await _apply_ai_action(session=session, call=call,
                result=AiTurnResult.model_validate(payload["result"]).model_copy(update={"tts_text": None}),
                expected_attempt=payload["attempt"])
        finally:
            _expected_turn_sequence.reset(token)
            _expected_speech_event.reset(speech_token)


async def _apply_ai_action(*, session, call: CallSession, result: AiTurnResult, expected_attempt: int | None = None, fallback_audio: bool = False) -> None:
    # Session.commit expires ORM attributes. Never dereference call after a
    # commit while preparing an await: even its primary key checks out a DB
    # connection again and pins it for the entire remote operation.
    command_call_id = call.id
    attempt = call.attempts if expected_attempt is None else expected_attempt
    if not _ai_call_is_current(session, call, attempt):
        return
    campaign = session.get(Campaign, call.campaign_id) if call.campaign_id is not None else None
    hangup_sms_allowed = campaign.hangup_sms_enabled if campaign else True
    ai_config = get_admin_setting(session, call.tenant_id, "ai")
    from .conversation_policy import state_for
    product_snapshot = json.loads(state_for(session, call).policy_json)
    ai_config = {**ai_config, **(product_snapshot.get('_ai') or {})}
    for name in ('voice', 'language'):
        if product_snapshot.get(name):
            ai_config[name] = product_snapshot[name]
    if fallback_audio:
        ai_config['tts_provider'] = 'fallback-audio'
    adapter = get_telephony_adapter(
        session=session,
        tenant_id=call.tenant_id,
        line_id=call.telephony_line_id,
        call_id=call.id,
    )
    from .telephony import HttpAdapter
    speech_guard = ({"expected_speech_event_id": _expected_speech_event.get() or ""}
                    if isinstance(adapter, HttpAdapter) and call.voice_ai_pipeline == "pipecat" else {})
    playback_id: str | None = None
    playback_complete = False
    if result.tts_text:
        tts_started = perf_counter()
        speak_payload = dict(call_id=str(call.id), text=result.tts_text or "",
                             language=str(ai_config.get("language") or "zh-CN"),
                             voice=str(ai_config.get("voice") or ""),
                             provider=str(ai_config.get("tts_provider") or ""), **speech_guard)
        session.commit()
        try:
            response = await with_retry(
                lambda: adapter.speak(**speak_payload)
            )
            if not _ai_call_is_current(session, call, attempt):
                return
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
                f"{call.id}:{attempt}:ai:{realtime.turn_sequence if realtime else 0}:{normalized_reply}".encode()
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
                    attempt=attempt,
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
        except LeaseLost:
            session.rollback()
            raise
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
    # Release database writes before waiting on remote playback/telephony/SMS.
    session.commit()
    hangup_confirmed = False
    if result.action == "hangup":
        if playback_id and not playback_complete:
            from .task_queue import enqueue_task
            realtime = session.exec(select(RealtimeSession).where(
                RealtimeSession.call_session_id == command_call_id)).first()
            # Do not occupy an AI worker or poll SQL while the user hears audio.
            # Store intent before ACK, with a deadline surviving worker restarts.
            task = enqueue_task(session, tenant_id=call.tenant_id, task_type="after_playback",
                aggregate_id=str(command_call_id),
                idempotency_key=f"after-playback:{command_call_id}:{attempt}:{playback_id}",
                available_at=utc_now() + timedelta(seconds=max(1, settings.tts_playback_timeout_sec)),
                payload={"call_id": str(command_call_id), "attempt": attempt,
                    "turn_sequence": realtime.turn_sequence if realtime else None,
                    "playback_id": playback_id, "speech_event_id": _expected_speech_event.get(), "result": result.model_dump(mode="json")})
            # Recheck after the insertion commit to cover an ACK that raced it.
            if realtime is not None:
                session.refresh(realtime)
                if realtime.playback_id != playback_id:
                    from ..models import TaskOutbox, TaskState
                    session.exec(update(TaskOutbox).where(TaskOutbox.id == task.id,
                        TaskOutbox.state == TaskState.PENDING).values(available_at=utc_now()))
                    session.commit()
            return
        if not _ai_call_is_current(session, call, attempt):
            return
        session.commit()
        hangup_result = await with_retry(lambda: adapter.hangup(call_id=str(command_call_id), reason="ai_decision", **speech_guard))
        hangup_confirmed = hangup_result.get("ended") is True

    if result.hangup_sms and hangup_sms_allowed:
        if not _ai_call_is_current(session, call, attempt):
            return
        sms_config = get_admin_setting(session, call.tenant_id, "sms")
        sms_text = str(sms_config.get("hangup_template") or result.hangup_sms)
        await send_sms_text(session, call, sms_text)

    # Compare-and-set acquires the call row before any state/assignment writes.
    # No network awaits are allowed until the transaction is committed below.
    claimed = session.exec(update(CallSession).where(
        CallSession.id == call.id,
        CallSession.attempts == attempt,
        CallSession.status.in_(AI_ACTIVE_STATUSES),
    ).values(updated_at=utc_now()))
    if claimed.rowcount != 1:
        session.rollback()
        return
    session.refresh(call)
    if result.action == "hangup":
        if hangup_confirmed:
            call.status = CallStatus.COMPLETED
            call.finished_at = utc_now()
        else:
            call.last_error = "hangup requested; awaiting PBX termination"
    elif result.action == "handoff" or result.handoff_to_human:
        from .conversation_policy import state_for, in_hours
        from ..product_schemas import ScenarioPolicy
        product_policy = ScenarioPolicy.model_validate_json(state_for(session, call).policy_json)
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
                User.id.in_(product_policy.handoff_agent_ids) if product_policy.handoff_agent_ids else True,
            )
            .order_by(User.last_seen_at.asc(), User.id.asc()).with_for_update(skip_locked=True)
        ).first()
        if not in_hours(product_policy, utc_now()) and state_for(session, call).policy_version_id is not None:
            assigned_agent = None
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
    from .conversation_policy import arm_timer
    if call.status == CallStatus.WAITING_HUMAN:
        arm_timer(session, call, 'handoff')
    elif playback_complete and call.status in AI_ACTIVE_STATUSES:
        realtime = session.exec(select(RealtimeSession).where(RealtimeSession.call_session_id == call.id)).first()
        if realtime:
            realtime.state = RealtimeState.LISTENING
            session.add(realtime)
        arm_timer(session, call)
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
    if callback_task is not None:
        await notify_task(callback_task.id)


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
    destination_phone = call.phone
    session.commit()
    try:
        sms_result = await with_retry(lambda: adapter.send_sms(destination_phone, text))
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
