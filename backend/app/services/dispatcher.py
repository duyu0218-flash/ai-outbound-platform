from __future__ import annotations

import json
from datetime import timedelta
from time import perf_counter
from typing import Any, Dict

import httpx
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
    User,
)
from ..schemas import AiTurnRequest, AiTurnResult
from .telephony import SmsAdapter, get_sms_adapter, with_retry, get_telephony_adapter
from .call_service import resolve_campaign_script
from .admin_settings import get_admin_setting
from .business_callbacks import deliver_business_callback
from .knowledge import retrieve_knowledge
from .script_flow import load_graph, simulate

settings = get_settings()


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
        response = await client.post(
            f"{(agent_url or settings.ai_agent_url).rstrip('/')}/agent/turn",
            json=payload.model_dump(mode="json"),
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
) -> None:
    event = CallEvent(
        call_session_id=call_id,
        event_type=event_type,
        source=source,
        payload=json.dumps(payload, ensure_ascii=False),
    )
    session.add(event)
    session.commit()


async def run_ai_turn(
    *,
    call_id,
    transcript: str = "",
) -> None:
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
                        "knowledge": knowledge,
                    },
                    agent_url=str(ai_config.get("agent_url") or settings.ai_agent_url),
                )
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


async def _apply_ai_action(*, session, call: CallSession, result: AiTurnResult) -> None:
    campaign = session.get(Campaign, call.campaign_id) if call.campaign_id is not None else None
    ai_config = get_admin_setting(session, call.tenant_id, "ai")
    adapter = get_telephony_adapter(
        session=session,
        tenant_id=call.tenant_id,
        line_id=call.telephony_line_id,
    )
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
            realtime = session.exec(
                select(RealtimeSession).where(RealtimeSession.call_session_id == call.id)
            ).first()
            if realtime is not None:
                realtime.state = RealtimeState.SPEAKING
                realtime.playback_id = str(response.get("playback_id") or "") or None
                realtime.updated_at = utc_now()
                session.add(realtime)
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
    await append_event(
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
    await deliver_business_callback(
        tenant_id=call.tenant_id,
        call_id=call.id,
        event_type="call.ai_decision",
        data={
            "action": result.action,
            "handoff_to_human": result.handoff_to_human,
            "tts_dispatched": bool(result.tts_text),
            "resulting_status": call.status.value,
        },
    )


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
