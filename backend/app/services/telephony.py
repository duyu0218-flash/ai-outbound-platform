import asyncio
from abc import ABC, abstractmethod
from typing import Any, Dict

import httpx

from ..config import get_settings


settings = get_settings()


class TelephonyAdapter(ABC):
    @abstractmethod
    async def dial(self, call_id: str, phone: str, callback_url: str) -> Dict[str, Any]:
        raise NotImplementedError

    @abstractmethod
    async def transfer_to_human(self, call_id: str, reason: str) -> Dict[str, Any]:
        raise NotImplementedError


class MockAdapter(TelephonyAdapter):
    async def dial(self, call_id: str, phone: str, callback_url: str) -> Dict[str, Any]:
        async def _simulate() -> None:
            await self._emit(callback_url, call_id, "dialing")
            await asyncio.sleep(1)
            await self._emit(callback_url, call_id, "answered")
            await asyncio.sleep(2)
            await self._emit(callback_url, call_id, "ended", {"hangup_reason": "normal"})

        asyncio.create_task(_simulate())
        return {"provider_call_id": f"mock-{call_id}", "state": "accepted"}

    async def transfer_to_human(self, call_id: str, reason: str) -> Dict[str, Any]:
        return {"result": "transferred", "provider_call_id": f"mock-{call_id}", "reason": reason}

    async def _emit(self, callback_url: str, call_id: str, status: str, extra: Dict[str, Any] | None = None) -> None:
        data = {
            "call_id": call_id,
            "kind": "status",
            "payload": {"status": status, **(extra or {})},
        }
        try:
            async with httpx.AsyncClient(timeout=5.0) as client:
                await client.post(callback_url, json=data)
        except Exception:
            # in mock mode we do not block call flow on callback errors
            pass


class FreeSwitchAdapter(TelephonyAdapter):
    def __init__(self, endpoint: str):
        self.endpoint = endpoint

    async def dial(self, call_id: str, phone: str, callback_url: str) -> Dict[str, Any]:
        # Replace with your ESB/FS API call; keep interface stable.
        async with httpx.AsyncClient(timeout=5.0) as client:
            payload = {
                "call_id": call_id,
                "phone": phone,
                "callback_url": callback_url,
            }
            r = await client.post(f"{self.endpoint}/dial", json=payload)
            return r.json()

    async def transfer_to_human(self, call_id: str, reason: str) -> Dict[str, Any]:
        async with httpx.AsyncClient(timeout=5.0) as client:
            payload = {"call_id": call_id, "reason": reason}
            r = await client.post(f"{self.endpoint}/transfer", json=payload)
            return r.json()


def get_adapter() -> TelephonyAdapter:
    provider = (settings.telephony_provider or "mock").lower()
    if provider == "freeswitch":
        return FreeSwitchAdapter(settings.sip_provider_endpoint)
    return MockAdapter()


async def send_sms(phone: str, text: str) -> None:
    # Provider interface placeholder. Mock: directly done.
    # You can add Twilio/云片/阿里云短信 in this method.
    return None
