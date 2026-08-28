from __future__ import annotations

import json
from typing import Any, Dict

import httpx

from ..config import get_settings
from ..clock import utc_now
from ..db import session_scope
from ..models import CallEvent, CallSession, CallStatus, SmsLog
from ..schemas import AiTurnRequest, AiTurnResult
from .telephony import SmsAdapter, get_sms_adapter, with_retry, get_telephony_adapter
from .call_service import resolve_campaign_script
from .admin_settings import get_admin_setting

settings = get_settings()


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
            ai_config = get_admin_setting(session, call.tenant_id, "ai")
            if not ai_config.get("enabled", True):
                raise RuntimeError("AI service is disabled for tenant")
            campaign_script = resolve_campaign_script(
                session,
                tenant_id=call.tenant_id,
                campaign_id=call.campaign_id,
            )
            result = await request_ai_turn(
                call_id=str(call.id),
                phone=call.phone,
                mode=call.mode.value,
                script=campaign_script,
                transcript=transcript,
                context={"campaign_id": call.campaign_id, "tenant_id": call.tenant_id},
                agent_url=str(ai_config.get("agent_url") or settings.ai_agent_url),
            )
            await _apply_ai_action(session=session, call=call, result=result)
        except Exception as exc:
            call.status = CallStatus.FAILED
            call.last_error = f"AI调用失败: {exc}"
            session.add(call)
            session.commit()
            await append_event(
                session=session,
                call_id=call.id,
                event_type="error",
                source="dispatcher",
                payload={"module": "dispatcher", "error": str(exc)},
            )


async def _apply_ai_action(*, session, call: CallSession, result: AiTurnResult) -> None:
    if result.action == "hangup":
        call.status = CallStatus.COMPLETED
        call.finished_at = utc_now()
    elif result.action == "handoff" or result.handoff_to_human:
        adapter = get_telephony_adapter()
        await with_retry(lambda: adapter.transfer_to_human(call_id=str(call.id), reason="ai_decision"))
        call.status = CallStatus.WAITING_HUMAN
        call.handoff_reason = "ai_decision"
    else:
        call.status = CallStatus.IN_AI

    if result.hangup_sms:
        await send_sms_text(session, call, result.hangup_sms)
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
            "handoff_to_human": result.handoff_to_human,
            "hangup_sms": bool(result.hangup_sms),
            "next_keywords": result.next_keywords,
            "escalate_priority": result.escalate_priority,
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
    except Exception as exc:
        state = "failed"
        call.last_error = f"短信发送失败: {exc}"

    sms_log = SmsLog(
        tenant_id=call.tenant_id,
        call_session_id=call.id,
        to_phone=call.phone,
        template_code="hangup_sms",
        content=text,
        state=state,
        sent_at=utc_now() if state != "failed" else None,
    )
    session.add(sms_log)
    session.add(call)
    session.commit()
