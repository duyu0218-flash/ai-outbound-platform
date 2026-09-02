from __future__ import annotations

import asyncio
import json
import secrets
import time
from html import escape

from fastapi import APIRouter, Depends, Form, HTTPException, Query, Request, status
from fastapi.responses import Response, StreamingResponse
from sqlalchemy import or_
from sqlmodel import Session, select

from ...api.deps import current_user, get_session
from ...clock import utc_now
from ...config import get_settings
from ...db import session_scope
from ...models import CallSession, CallStatus, HandoffRequest, HandoffState, User
from ...schemas import AgentMediaStatusOut, AgentMediaStatusUpdate, WebRtcSessionOut
from ...services.webrtc import (
    agent_extension,
    build_ice_servers,
    get_media_status,
    get_sip_credential,
    issue_sip_credential,
    save_media_status,
)


router = APIRouter(tags=["agent-webrtc"])
settings = get_settings()
TERMINAL_CALL_STATES = {
    CallStatus.COMPLETED,
    CallStatus.FAILED,
    CallStatus.NO_ANSWER,
    CallStatus.BUSY,
    CallStatus.VOICEMAIL,
}


def _require_agent(current: User) -> User:
    if current.role != "agent" or current.id is None:
        raise HTTPException(status_code=403, detail="agent role required")
    return current


@router.post("/api/v1/agent/webrtc/session", response_model=WebRtcSessionOut)
def create_webrtc_session(current: User = Depends(current_user)):
    agent = _require_agent(current)
    if not settings.webrtc_enabled:
        return WebRtcSessionOut(enabled=False)
    if not settings.webrtc_wss_url.startswith("wss://"):
        raise HTTPException(status_code=503, detail="secure WebRTC WSS endpoint is not configured")
    if not settings.webrtc_sip_domain.strip():
        raise HTTPException(status_code=503, detail="WebRTC SIP domain is not configured")
    try:
        extension, password, expires_at = issue_sip_credential(
            tenant_id=agent.tenant_id,
            agent_id=int(agent.id),
        )
        ice_servers = build_ice_servers(agent_id=int(agent.id))
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return WebRtcSessionOut(
        enabled=True,
        wss_url=settings.webrtc_wss_url,
        sip_uri=f"sip:{extension}@{settings.webrtc_sip_domain}",
        authorization_username=extension,
        authorization_password=password,
        extension=extension,
        expires_at=expires_at,
        ice_servers=ice_servers,
    )


@router.get("/api/v1/agent/media/status", response_model=AgentMediaStatusOut)
def read_media_status(current: User = Depends(current_user)):
    agent = _require_agent(current)
    payload = get_media_status(tenant_id=agent.tenant_id, agent_id=int(agent.id))
    if payload is None:
        payload = {
            "user_id": int(agent.id),
            "tenant_id": agent.tenant_id,
            "extension": agent_extension(int(agent.id)),
            "registration_state": "disabled" if not settings.webrtc_enabled else "disconnected",
            "media_state": "idle",
            "microphone_permission": "unknown",
            "input_device_id": "",
            "output_device_id": "",
            "active_call_id": None,
            "muted": False,
            "held": False,
            "network_quality": "unknown",
            "round_trip_time_ms": None,
            "jitter_ms": None,
            "packets_lost": None,
            "last_error": "",
            "last_seen_at": utc_now(),
        }
    return payload


@router.put("/api/v1/agent/media/status", response_model=AgentMediaStatusOut)
def update_media_status(
    payload: AgentMediaStatusUpdate,
    current: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    agent = _require_agent(current)
    if payload.active_call_id is not None:
        call = session.get(CallSession, payload.active_call_id)
        if call is None or call.tenant_id != agent.tenant_id or call.human_agent_id != agent.id:
            raise HTTPException(status_code=403, detail="active call is not assigned to this agent")
    result = save_media_status(
        tenant_id=agent.tenant_id,
        agent_id=int(agent.id),
        payload=payload.model_dump(mode="json"),
    )
    managed = session.get(User, agent.id)
    if managed is not None:
        if settings.webrtc_enabled and payload.registration_state != "registered" and managed.agent_status == "ready":
            managed.agent_status = "offline"
        managed.last_seen_at = utc_now()
        managed.updated_at = utc_now()
        session.add(managed)
        session.commit()
    return result


def _agent_snapshot(agent_id: int, tenant_id: int) -> dict:
    with session_scope() as session:
        handoffs = session.exec(
            select(HandoffRequest).where(
                HandoffRequest.tenant_id == tenant_id,
                HandoffRequest.state.in_([HandoffState.WAITING, HandoffState.ACCEPTING, HandoffState.ACCEPTED]),
                or_(HandoffRequest.assigned_agent_id.is_(None), HandoffRequest.assigned_agent_id == agent_id),
            )
        ).all()
        calls = session.exec(
            select(CallSession).where(
                CallSession.tenant_id == tenant_id,
                CallSession.human_agent_id == agent_id,
                CallSession.status.notin_(TERMINAL_CALL_STATES),
            )
        ).all()
        return {
            "type": "agent_snapshot",
            "handoffs": [
                {"id": item.id, "call_session_id": str(item.call_session_id), "state": item.state.value, "updated_at": item.updated_at.isoformat()}
                for item in handoffs
            ],
            "calls": [
                {"id": str(item.id), "status": item.status.value, "updated_at": item.updated_at.isoformat()}
                for item in calls
            ],
        }


@router.get("/api/v1/agent/events/stream")
async def stream_agent_events(request: Request, current: User = Depends(current_user)):
    agent = _require_agent(current)
    agent_id = int(agent.id)
    tenant_id = agent.tenant_id

    async def generate():
        previous = ""
        last_heartbeat = 0.0
        while not await request.is_disconnected():
            snapshot = await asyncio.to_thread(_agent_snapshot, agent_id, tenant_id)
            canonical = json.dumps(snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            now = time.monotonic()
            if canonical != previous or now - last_heartbeat >= 15:
                event = {**snapshot, "timestamp": utc_now().isoformat()}
                yield json.dumps(event, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
                previous = canonical
                last_heartbeat = now
            await asyncio.sleep(max(0.5, float(settings.webrtc_event_stream_interval_sec)))

    return StreamingResponse(
        generate(),
        media_type="application/x-ndjson",
        headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"},
    )


@router.post("/internal/freeswitch/directory", include_in_schema=False)
def freeswitch_directory(
    user: str = Form(default=""),
    domain: str = Form(default=""),
    token: str = Query(default=""),
):
    expected = settings.freeswitch_directory_token.strip()
    if not expected or not secrets.compare_digest(token, expected):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid directory token")
    credential = get_sip_credential(user)
    if not credential:
        xml = "<document type=\"freeswitch/xml\"><section name=\"result\"><result status=\"not found\" /></section></document>"
        return Response(content=xml, media_type="application/xml")
    safe_user = escape(str(credential["extension"]), quote=True)
    safe_domain = escape(domain or settings.webrtc_sip_domain, quote=True)
    safe_password = escape(str(credential["password"]), quote=True)
    xml = f"""<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>
<document type=\"freeswitch/xml\">
  <section name=\"directory\">
    <domain name=\"{safe_domain}\">
      <groups><group name=\"agents\"><users>
        <user id=\"{safe_user}\">
          <params><param name=\"password\" value=\"{safe_password}\"/></params>
          <variables>
            <variable name=\"user_context\" value=\"browser-no-outbound\"/>
            <variable name=\"effective_caller_id_name\" value=\"{safe_user}\"/>
            <variable name=\"effective_caller_id_number\" value=\"{safe_user}\"/>
          </variables>
        </user>
      </users></group></groups>
    </domain>
  </section>
</document>"""
    return Response(content=xml, media_type="application/xml")
