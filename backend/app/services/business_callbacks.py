from __future__ import annotations

import json
import logging
from typing import Any

import httpx

from ..db import session_scope
from ..models import CallEvent, CallSession
from .admin_settings import get_admin_setting

logger = logging.getLogger(__name__)


async def deliver_business_callback(
    *,
    tenant_id: int,
    call_id,
    event_type: str,
    data: dict[str, Any],
) -> None:
    with session_scope() as session:
        config = get_admin_setting(session, tenant_id, "integration")
        callback_url = str(config.get("webhook_base_url") or "").strip()
        if not config.get("callback_enabled", False) or not callback_url:
            return
        call = session.get(CallSession, call_id)
        if call is None or call.tenant_id != tenant_id:
            return
        payload = {
            "event": event_type,
            "tenant_id": tenant_id,
            "call_id": str(call.id),
            "campaign_id": call.campaign_id,
            "phone": call.phone,
            "mode": call.mode.value,
            "status": call.status.value,
            "data": data,
        }
        timeout = int(config.get("webhook_timeout_sec") or 10)

    delivery_state = "delivered"
    detail: dict[str, Any] = {"event": event_type, "url": callback_url}
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.post(callback_url, json=payload)
            response.raise_for_status()
            detail["status_code"] = response.status_code
    except Exception as exc:
        delivery_state = "failed"
        detail["error"] = str(exc)[:1000]
        logger.warning("business callback failed tenant_id=%s call_id=%s event=%s", tenant_id, call_id, event_type)

    with session_scope() as session:
        session.add(
            CallEvent(
                call_session_id=call_id,
                event_type=f"business_callback_{delivery_state}",
                source="integration",
                payload=json.dumps(detail, ensure_ascii=False),
            )
        )
        session.commit()
