"""Static operator roster, fresh readiness, and durable call ownership.

CallSession's active state is the reservation ledger. No Redis TTL releases it.
All dispatch paths take the same transaction lock before capacity admission.
"""
from __future__ import annotations

import asyncio
import json
from datetime import timedelta
from pathlib import Path
from urllib.parse import urlsplit

import httpx
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import func, text, update
from sqlmodel import select

from ..clock import utc_now
from ..config import get_settings
from ..db import session_scope
from ..models import CallSession, GatewayNode, Tenant
from .telephony import LINE_CAPACITY_STATUSES

settings = get_settings()


class NodeSpec(BaseModel):
    model_config = ConfigDict(extra='forbid')
    id: str = Field(pattern=r'^[a-zA-Z0-9_-]{1,64}$')
    endpoint: str
    capacity: int = Field(default=200, ge=1, le=200)
    enabled: bool = True
    # Explicit tenant:line scopes prevent a global roster bypassing line routing.
    routes: list[str] = Field(min_length=1)

    @field_validator('endpoint')
    @classmethod
    def endpoint_origin(cls, value):
        p = urlsplit(value)
        if p.scheme not in {'http', 'https'} or not p.hostname or p.username or p.password or p.query or p.fragment or p.path not in {'', '/'}:
            raise ValueError('gateway endpoint must be an operator-configured HTTP origin')
        return value.rstrip('/')

    @field_validator('routes')
    @classmethod
    def routes_valid(cls, values):
        import re
        if any(not re.fullmatch(r'[1-9][0-9]*:[0-9]+', value) for value in values):
            raise ValueError('gateway routes must be tenant_id:line_id')
        return values


def node_specs() -> list[NodeSpec]:
    raw = Path(settings.voice_gateway_nodes_file).read_text() if settings.voice_gateway_nodes_file else settings.voice_gateway_nodes_json
    specs = [NodeSpec.model_validate(value) for value in json.loads(raw)]
    if settings.voice_gateway_nodes_file and not specs:
        raise ValueError('configured gateway roster must not be empty')
    if len({n.id for n in specs}) != len(specs) or len({n.endpoint for n in specs}) != len(specs):
        raise ValueError('gateway node IDs and endpoints must be unique')
    return specs


def lock_platform_admission(session):
    if session.get_bind().dialect.name == 'postgresql':
        # Transaction-scoped, compatible with transaction pooling. It precedes
        # tenant/call locks everywhere; never retain it across provider I/O.
        session.execute(text("SELECT pg_advisory_xact_lock(718492061)"))
    else:
        session.exec(update(Tenant).where(Tenant.id == -1).values(updated_at=Tenant.updated_at))


def choose_gateway(session, tenant_id, line_id):
    specs = node_specs()
    if not specs:
        return None
    scope = f'{tenant_id}:{line_id or 0}'
    eligible = {n.id: n for n in specs if n.enabled and scope in n.routes}
    counts = dict(session.exec(select(CallSession.gateway_node_id, func.count(CallSession.id)).where(
        CallSession.status.in_(LINE_CAPACITY_STATUSES)).group_by(CallSession.gateway_node_id)).all())
    candidates = []
    cutoff = utc_now() - timedelta(seconds=max(1, settings.voice_gateway_health_ttl_sec))
    for node in session.exec(select(GatewayNode).where(GatewayNode.ready.is_(True), GatewayNode.checked_at >= cutoff)).all():
        spec = eligible.get(node.id)
        if spec is None or node.endpoint != spec.endpoint:
            continue
        capacity = min(spec.capacity, node.capacity)
        occupied = counts.get(node.id, 0)
        if occupied < capacity:
            candidates.append((occupied / capacity, node.id, node))
    if not candidates:
        raise RuntimeError('no ready gateway has an authorized capacity slot')
    return min(candidates, key=lambda item: item[:2])[2]


def _store_probe(spec, started, ready, capacity):
    with session_scope() as session:
        lock_platform_admission(session)
        node = session.get(GatewayNode, spec.id)
        if node and node.checked_at > started:
            return
        if node and node.endpoint != spec.endpoint:
            active = session.exec(select(CallSession.id).where(
                CallSession.gateway_node_id == spec.id,
                CallSession.status.in_(LINE_CAPACITY_STATUSES))).first()
            if active is not None:
                # Never move an active or unknown call by changing configuration.
                node.ready = False
                session.add(node); session.commit()
                return
        if node is None:
            node = GatewayNode(id=spec.id, endpoint=spec.endpoint)
        node.endpoint = spec.endpoint
        node.ready = ready and spec.enabled
        node.capacity = capacity
        node.checked_at = started
        session.add(node); session.commit()


async def probe_gateways():
    specs = node_specs()
    if not specs:
        return
    async with httpx.AsyncClient(timeout=2, trust_env=False, follow_redirects=False) as client:
        async def probe(spec):
            started = utc_now()
            ready, capacity = False, 0
            try:
                response = await client.get(spec.endpoint + '/readyz')
                response.raise_for_status()
                data = response.json()
                ready = data.get('status') == 'ready' and data.get('node_id') == spec.id
                capacity = min(spec.capacity, int(data.get('call_capacity', 0)))
            except (httpx.HTTPError, ValueError, TypeError):
                pass
            await asyncio.to_thread(_store_probe, spec, started, ready, capacity)
        await asyncio.gather(*(probe(spec) for spec in specs))


async def run_gateway_probes(stop_event):
    while not stop_event.is_set():
        try:
            await probe_gateways()
        except Exception:
            import logging
            logging.getLogger(__name__).exception('gateway roster/probe failed; stale nodes reject admission')
        try:
            await asyncio.wait_for(stop_event.wait(), max(1, settings.voice_gateway_health_poll_sec))
        except asyncio.TimeoutError:
            pass
