import asyncio
from abc import ABC, abstractmethod
from typing import Any, Callable, Dict, Awaitable

import httpx

from ..config import get_settings

settings = get_settings()


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
    def __init__(self, endpoint: str):
        self.endpoint = endpoint.rstrip("/")
        self.client = httpx.AsyncClient(timeout=settings.telephony_timeout_sec)

    async def dial(self, *, call_id: str, phone: str, webhook_url: str, metadata: Dict[str, Any]) -> Dict[str, Any]:
        payload = {
            "call_id": call_id,
            "phone": phone,
            "webhook_url": webhook_url,
            "metadata": metadata,
        }
        response = await self.client.post(f"{self.endpoint}/v1/call/dial", json=payload)
        response.raise_for_status()
        return response.json()

    async def transfer_to_human(self, *, call_id: str, reason: str, target_group: str | None = None) -> Dict[str, Any]:
        payload = {"call_id": call_id, "reason": reason, "target_group": target_group}
        response = await self.client.post(f"{self.endpoint}/v1/call/transfer", json=payload)
        response.raise_for_status()
        return response.json()

    async def hangup(self, *, call_id: str, reason: str = "hangup") -> Dict[str, Any]:
        payload = {"call_id": call_id, "reason": reason}
        response = await self.client.post(f"{self.endpoint}/v1/call/hangup", json=payload)
        response.raise_for_status()
        return response.json()


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
        self.client = httpx.AsyncClient(timeout=settings.telephony_timeout_sec)

    async def send_sms(self, phone: str, text: str) -> Dict[str, Any]:
        payload = {
            "to": phone,
            "text": text,
            "from": self.sender,
            "sender_id": self.sender,
            "callback_url": settings.sms_callback_url,
        }
        response = await self.client.post(
            f"{self.endpoint}/v1/sms/send",
            headers={"Authorization": f"Bearer {self.api_key}"},
            json=payload,
        )
        response.raise_for_status()
        return response.json()


def get_telephony_adapter() -> TelephonyAdapter:
    provider = (settings.telephony_provider or "mock").strip().lower()
    if provider == "http":
        endpoint = settings.telephony_provider_endpoint or settings.sip_provider_endpoint
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
    return MockSmsAdapter()


async def with_retry(
    coroutine_factory: Callable[[], Awaitable[Any]],
    *,
    retries: int | None = None,
    base_delay: float = 0.5,
) -> Any:
    max_retries = settings.telephony_retry_times if retries is None else retries
    last_error: Exception | None = None
    for attempt in range(max_retries + 1):
        try:
            return await coroutine_factory()
        except Exception as exc:
            last_error = exc
            if attempt >= max_retries:
                raise
            await asyncio.sleep(base_delay * (attempt + 1))
    if last_error:
        raise last_error
