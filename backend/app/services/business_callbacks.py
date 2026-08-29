from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import logging
import os
import time
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
    raise_on_failure: bool = False,
) -> bool:
    with session_scope() as session:
        config = get_admin_setting(session, tenant_id, "integration")
        callback_url = str(config.get("webhook_base_url") or "").strip()
        if not config.get("callback_enabled", False) or not callback_url:
            return True
        call = session.get(CallSession, call_id)
        if call is None or call.tenant_id != tenant_id:
            return True
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
        retry_times = max(0, int(config.get("webhook_retry_times") or 0))
        retry_backoff = max(1, int(config.get("webhook_retry_backoff_sec") or 1))
        secret_ref = str(config.get("webhook_secret_ref") or "").strip()

    delivery_state = "delivered"
    detail: dict[str, Any] = {"event": event_type, "url": callback_url}
    payload_bytes = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    last_error: Exception | None = None
    if secret_ref:
        secret = os.getenv(f"BUSINESS_WEBHOOK_SECRET_{secret_ref}", "")
        if not secret:
            last_error = RuntimeError(f"business callback secret is not configured: {secret_ref}")
        else:
            timestamp = str(int(time.time()))
            signature = hmac.new(secret.encode("utf-8"), timestamp.encode("ascii") + b"." + payload_bytes, hashlib.sha256).hexdigest()
            headers.update({"X-Webhook-Timestamp": timestamp, "X-Webhook-Signature": f"sha256={signature}"})

    for attempt in range(retry_times + 1) if last_error is None else ():
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                response = await client.post(callback_url, content=payload_bytes, headers=headers)
                response.raise_for_status()
                detail["status_code"] = response.status_code
                detail["attempts"] = attempt + 1
                last_error = None
                break
        except Exception as exc:
            last_error = exc
            if attempt < retry_times:
                await asyncio.sleep(retry_backoff * (2**attempt))
    if last_error is not None:
        delivery_state = "failed"
        detail["error"] = str(last_error)[:1000]
        detail["attempts"] = retry_times + 1
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
    if last_error is not None and raise_on_failure:
        raise RuntimeError(str(last_error)) from last_error
    return last_error is None
