from __future__ import annotations

import logging
import socket
import urllib.request
from typing import Optional

import redis
from sqlmodel import Session, select

from ..config import get_settings
from ..db import engine
from ..models import TelephonyLine

logger = logging.getLogger(__name__)


def db_health_check() -> str:
    try:
        with Session(engine) as session:
            session.exec(select(1)).first()
        return "ok"
    except Exception:
        return "unavailable"


def redis_health_check() -> str:
    settings = get_settings()
    if not settings.redis_url:
        return "ok"
    try:
        redis.from_url(
            settings.redis_url,
            socket_connect_timeout=1.5,
            socket_timeout=1.5,
        ).ping()
        return "ok"
    except Exception:
        return "unavailable"


def ai_agent_health_check(path: str = "/health", base_url: Optional[str] = None) -> str:
    settings = get_settings()
    return _probe_http(base_url or settings.ai_agent_url, path)


def telephony_http_health_check() -> str:
    settings = get_settings()
    if settings.voice_gateway_nodes_file or settings.voice_gateway_nodes_json.strip() != "[]":
        # Do not remove every API replica when the old/default first gateway
        # is drained. The fleet is usable while another authorized node is ready.
        return _gateway_fleet_health()
    if (settings.telephony_provider or "mock").strip().lower() != "http":
        return "ok"
    endpoint = (settings.telephony_provider_endpoint or settings.sip_provider_endpoint).strip()
    if not endpoint:
        return "unconfigured"
    # The process health endpoint stays green even when its downstream PBX is
    # disconnected. Readiness must cascade through the voice gateway.
    return _probe_http(endpoint, "/readyz")


def tenant_telephony_health_check(session: Session, tenant_id: int) -> str:
    settings = get_settings()
    if settings.voice_gateway_nodes_file or settings.voice_gateway_nodes_json.strip() != "[]":
        return _gateway_fleet_health(tenant_id)
    provider = (settings.telephony_provider or "mock").strip().lower()
    if provider != "tenant":
        return telephony_http_health_check() if provider == "http" else "mock"
    lines = session.exec(
        select(TelephonyLine)
        .where(TelephonyLine.tenant_id == tenant_id, TelephonyLine.enabled.is_(True))
        .order_by(TelephonyLine.priority.asc(), TelephonyLine.created_at.asc())
    ).all()
    if not lines:
        return "unconfigured"
    states: list[str] = []
    for line in lines:
        if line.provider.strip().lower() == "mock":
            states.append("mock")
            continue
        endpoint = line.gateway_url.strip()
        if not endpoint.startswith(("http://", "https://")):
            states.append("unsupported")
            continue
        states.append(_probe_http(endpoint, "/readyz"))
    if "ok" in states:
        return "ok"
    if all(state == "mock" for state in states):
        return "mock"
    return "unavailable"


def _gateway_fleet_health(tenant_id: int | None = None) -> str:
    from datetime import timedelta
    from ..clock import utc_now
    from ..models import GatewayNode
    from .gateway_cluster import node_specs
    settings = get_settings()
    try:
        specs = {n.id: n for n in node_specs() if n.enabled and
                 (tenant_id is None or any(scope.startswith(f"{tenant_id}:") for scope in n.routes))}
        cutoff = utc_now() - timedelta(seconds=max(1, settings.voice_gateway_health_ttl_sec))
        with Session(engine) as session:
            for node in session.exec(select(GatewayNode).where(
                GatewayNode.ready.is_(True), GatewayNode.checked_at >= cutoff, GatewayNode.capacity > 0)).all():
                if node.id in specs and node.endpoint == specs[node.id].endpoint:
                    return "ok"
        return "unavailable"
    except Exception:
        return "unavailable"


def _probe_http(base_url: Optional[str], path: str, timeout: float = 2.0) -> str:
    if not base_url:
        return "unconfigured"
    try:
        url = f"{base_url.rstrip('/')}/{path.lstrip('/')}"
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            if resp.status != 200:
                return "unavailable"
            return "ok"
    except (OSError, TimeoutError, socket.timeout):
        logger.debug("http probe failed: url=%s", base_url)
        return "unavailable"
    except Exception:
        logger.exception("http probe unexpected failure: url=%s", base_url)
        return "unavailable"
