import asyncio
from abc import ABC, abstractmethod
import os
import re
from typing import Any, Callable, Dict, Awaitable, TYPE_CHECKING

import httpx
from sqlalchemy import func
from sqlmodel import select

from ..config import get_settings
from ..models import CallSession, CallStatus, TelephonyLine

if TYPE_CHECKING:
    from sqlmodel import Session

settings = get_settings()

LINE_CAPACITY_STATUSES = {
    CallStatus.DIALING,
    CallStatus.ANSWERED,
    CallStatus.IN_AI,
    CallStatus.WAITING_HUMAN,
    CallStatus.HANDOFF_TRANSFERRING,
}


def list_tenant_telephony_lines(session: "Session", tenant_id: int) -> list[TelephonyLine]:
    return session.exec(
        select(TelephonyLine)
        .where(TelephonyLine.tenant_id == tenant_id, TelephonyLine.enabled.is_(True))
        .order_by(TelephonyLine.priority.asc(), TelephonyLine.created_at.asc(), TelephonyLine.id.asc())
    ).all()


def get_tenant_telephony_line(
    session: "Session",
    tenant_id: int,
    *,
    for_update: bool = False,
    line_id: int | None = None,
    enabled_only: bool = True,
) -> TelephonyLine | None:
    query = select(TelephonyLine).where(TelephonyLine.tenant_id == tenant_id)
    if line_id is not None:
        query = query.where(TelephonyLine.id == line_id)
    if enabled_only:
        query = query.where(TelephonyLine.enabled.is_(True))
    query = query.order_by(TelephonyLine.priority.asc(), TelephonyLine.created_at.asc(), TelephonyLine.id.asc())
    if for_update:
        query = query.with_for_update()
    return session.exec(query).first()


def get_telephony_concurrency_limit(*, session: "Session", tenant_id: int) -> int | None:
    lines = list_tenant_telephony_lines(session, tenant_id)
    if lines:
        return sum(max(1, int(line.max_concurrency)) for line in lines)
    return None


def select_tenant_telephony_line(session: "Session", tenant_id: int) -> TelephonyLine | None:
    """Select an enabled line with remaining capacity.

    Tenant row locking in the caller serializes capacity claims. Priority is
    considered first, then the least utilized weighted line is selected.
    """

    lines = list_tenant_telephony_lines(session, tenant_id)
    if not lines:
        return None
    active_rows = session.exec(
        select(CallSession.telephony_line_id, func.count(CallSession.id))
        .where(
            CallSession.tenant_id == tenant_id,
            CallSession.status.in_(LINE_CAPACITY_STATUSES),
            CallSession.telephony_line_id.is_not(None),
        )
        .group_by(CallSession.telephony_line_id)
    ).all()
    active_by_line = {line_id: int(count) for line_id, count in active_rows}
    candidates = [
        line for line in lines
        if active_by_line.get(line.id, 0) < max(1, int(line.max_concurrency))
    ]
    if not candidates:
        return None
    top_priority = min(int(line.priority) for line in candidates)
    candidates = [line for line in candidates if int(line.priority) == top_priority]
    return min(
        candidates,
        key=lambda line: (
            active_by_line.get(line.id, 0) / max(1, int(line.max_concurrency) * int(line.weight)),
            line.id or 0,
        ),
    )


def _credential_headers(credential_ref: str) -> dict[str, str]:
    ref = re.sub(r"[^A-Za-z0-9_]", "_", (credential_ref or "").strip()).upper()
    if not ref:
        return {}
    token = os.getenv(f"TELEPHONY_SECRET_{ref}", "").strip()
    if not token:
        raise RuntimeError(f"telephony credential reference is not available: {credential_ref}")
    return {"Authorization": f"Bearer {token}"}


class TelephonyAdapter(ABC):
    @abstractmethod
    async def dial(self, *, call_id: str, phone: str, webhook_url: str, metadata: Dict[str, Any]) -> Dict[str, Any]:
        raise NotImplementedError

    @abstractmethod
    async def transfer_to_human(self, *, call_id: str, reason: str, target_group: str | None = None) -> Dict[str, Any]:
        raise NotImplementedError

    @abstractmethod
    async def hangup(self, *, call_id: str, reason: str = "hangup") -> Dict[str, Any]:
        raise NotImplementedError

    @abstractmethod
    async def speak(
        self,
        *,
        call_id: str,
        text: str,
        language: str = "zh-CN",
        voice: str = "",
        provider: str = "",
    ) -> Dict[str, Any]:
        raise NotImplementedError


class MockAdapter(TelephonyAdapter):
    async def dial(self, *, call_id: str, phone: str, webhook_url: str, metadata: Dict[str, Any]) -> Dict[str, Any]:
        async def _simulate() -> None:
            await self._emit(webhook_url, call_id, "dialing", metadata)
            await asyncio.sleep(1)
            await self._emit(webhook_url, call_id, "answered", metadata)
            await asyncio.sleep(2)
            await self._emit(
                webhook_url,
                call_id,
                "ended",
                {"hangup_reason": "normal", **metadata},
            )

        asyncio.create_task(_simulate())
        return {"provider_call_id": f"mock-{call_id}", "state": "accepted"}

    async def transfer_to_human(self, *, call_id: str, reason: str, target_group: str | None = None) -> Dict[str, Any]:
        return {
            "result": "transferred",
            "provider_call_id": f"mock-{call_id}",
            "reason": reason,
            "target_group": target_group,
        }

    async def hangup(self, *, call_id: str, reason: str = "hangup") -> Dict[str, Any]:
        return {"result": "hungup", "provider_call_id": f"mock-{call_id}", "reason": reason}

    async def speak(
        self,
        *,
        call_id: str,
        text: str,
        language: str = "zh-CN",
        voice: str = "",
        provider: str = "",
    ) -> Dict[str, Any]:
        return {
            "result": "spoken_mock",
            "provider_call_id": f"mock-{call_id}",
            "text": text,
            "language": language,
            "voice": voice,
            "provider": provider,
        }

    async def _emit(self, webhook_url: str, call_id: str, status: str, metadata: Dict[str, Any] | None = None) -> None:
        data = {
            "call_id": call_id,
            "kind": "status",
            "payload": {"status": status, **(metadata or {})},
        }
        headers = {}
        if settings.telephony_webhook_token:
            headers["x-webhook-token"] = settings.telephony_webhook_token
        try:
            async with httpx.AsyncClient(timeout=settings.telephony_timeout_sec) as client:
                await client.post(
                    webhook_url,
                    json=data,
                    headers=headers,
                    timeout=settings.telephony_timeout_sec,
                )
        except Exception:
            # in mock mode callback failures do not block call execution
            pass


class HttpAdapter(TelephonyAdapter):
    def __init__(self, endpoint: str, credential_ref: str = ""):
        self.endpoint = endpoint.rstrip("/")
        self.headers = _credential_headers(credential_ref)

    async def _post(self, path: str, payload: dict[str, Any]) -> Dict[str, Any]:
        async with httpx.AsyncClient(timeout=settings.telephony_timeout_sec, headers=self.headers) as client:
            response = await client.post(f"{self.endpoint}{path}", json=payload)
        response.raise_for_status()
        return response.json()

    async def dial(self, *, call_id: str, phone: str, webhook_url: str, metadata: Dict[str, Any]) -> Dict[str, Any]:
        payload = {
            "call_id": call_id,
            "phone": phone,
            "caller_id": metadata.get("caller_id", ""),
            "webhook_url": webhook_url,
            "metadata": metadata,
        }
        return await self._post("/v1/call/dial", payload)

    async def transfer_to_human(self, *, call_id: str, reason: str, target_group: str | None = None) -> Dict[str, Any]:
        payload = {"call_id": call_id, "reason": reason, "target_group": target_group}
        return await self._post("/v1/call/transfer", payload)

    async def hangup(self, *, call_id: str, reason: str = "hangup") -> Dict[str, Any]:
        payload = {"call_id": call_id, "reason": reason}
        return await self._post("/v1/call/hangup", payload)

    async def speak(
        self,
        *,
        call_id: str,
        text: str,
        language: str = "zh-CN",
        voice: str = "",
        provider: str = "",
    ) -> Dict[str, Any]:
        payload = {
            "call_id": call_id,
            "text": text,
            "language": language,
            "voice": voice,
            "provider": provider,
        }
        return await self._post("/v1/call/speak", payload)


class SmsAdapter(ABC):
    @abstractmethod
    async def send_sms(self, phone: str, text: str) -> Dict[str, Any]:
        raise NotImplementedError


class MockSmsAdapter(SmsAdapter):
    async def send_sms(self, phone: str, text: str) -> Dict[str, Any]:
        return {"phone": phone, "state": "sent_mock", "text": text}


class HttpSmsAdapter(SmsAdapter):
    def __init__(self, endpoint: str, api_key: str, sender: str = ""):
        self.endpoint = endpoint.rstrip("/")
        self.api_key = api_key
        self.sender = sender

    async def send_sms(self, phone: str, text: str) -> Dict[str, Any]:
        payload = {
            "to": phone,
            "text": text,
            "from": self.sender,
            "sender_id": self.sender,
            "callback_url": settings.sms_callback_url,
        }
        async with httpx.AsyncClient(timeout=settings.telephony_timeout_sec) as client:
            response = await client.post(
                f"{self.endpoint}/v1/sms/send",
                headers={"Authorization": f"Bearer {self.api_key}"},
                json=payload,
            )
        response.raise_for_status()
        return response.json()


def get_telephony_adapter(
    *,
    session: "Session | None" = None,
    tenant_id: int | None = None,
    line_id: int | None = None,
) -> TelephonyAdapter:
    provider = (settings.telephony_provider or "mock").strip().lower()
    endpoint = settings.telephony_provider_endpoint or settings.sip_provider_endpoint
    line = None
    if session is not None and tenant_id is not None:
        line = get_tenant_telephony_line(
            session,
            tenant_id,
            line_id=line_id,
            enabled_only=line_id is None,
        )
    if line is not None:
        provider = line.provider.strip().lower()
        endpoint = line.gateway_url.strip()
        if provider == "mock":
            return MockAdapter()
        if not endpoint.startswith(("http://", "https://")):
            raise RuntimeError(
                "tenant telephony line must point to an HTTP bridge endpoint; direct SIP dialing is not supported by the control service"
            )
        return HttpAdapter(endpoint, line.credential_ref)
    if provider == "tenant":
        raise RuntimeError("no enabled telephony line configured for tenant")
    if provider == "http":
        if not endpoint:
            raise RuntimeError("telephony provider is HTTP but endpoint is not configured")
        return HttpAdapter(endpoint)
    return MockAdapter()


def get_adapter() -> TelephonyAdapter:
    # backwards compatibility for older route imports
    return get_telephony_adapter()


def get_sms_adapter(tenant_config: Dict[str, Any] | None = None) -> SmsAdapter:
    config = tenant_config or {}
    provider = str(config.get("provider") or settings.sms_provider or "mock").strip().lower()
    if provider == "http":
        sms_endpoint = str(config.get("endpoint") or getattr(settings, "sms_provider_endpoint", "")).strip()
        if not sms_endpoint:
            raise RuntimeError("SMS provider is HTTP but sms_provider_endpoint is not configured")
        sender_id = str(config.get("sender_id") or settings.sms_sender_id)
        return HttpSmsAdapter(sms_endpoint, settings.sms_api_key, sender_id)
    if provider == "mock":
        return MockSmsAdapter()
    raise RuntimeError(f"unsupported SMS provider: {provider}")


async def with_retry(
    coroutine_factory: Callable[[], Awaitable[Any]],
    *,
    retries: int | None = None,
    base_delay: float | None = None,
) -> Any:
    max_retries = settings.telephony_retry_times if retries is None else retries
    delay = settings.telephony_retry_backoff_sec if base_delay is None else base_delay
    last_error: Exception | None = None
    for attempt in range(max_retries + 1):
        try:
            return await coroutine_factory()
        except Exception as exc:
            last_error = exc
            if attempt >= max_retries:
                raise
            await asyncio.sleep(max(0.0, delay) * (attempt + 1))
    if last_error:
        raise last_error
