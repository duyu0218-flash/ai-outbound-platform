from __future__ import annotations

import asyncio
from abc import ABC, abstractmethod
from typing import Any

import httpx

from .config import Settings
from .models import CallRequest, DialRequest, SpeakRequest


class VoiceDriver(ABC):
    @abstractmethod
    async def post(self, action: str, payload: dict[str, Any]) -> dict[str, Any]: ...

    @abstractmethod
    async def ready(self) -> bool: ...


class MockDriver(VoiceDriver):
    def __init__(self, settings: Settings):
        self.settings = settings

    async def post(self, action: str, payload: dict[str, Any]) -> dict[str, Any]:
        if action == "dial":
            asyncio.create_task(self._callbacks(DialRequest.model_validate(payload)))
        return {"result": "accepted", "action": action, "provider_call_id": f"gateway-{payload['call_id']}"}

    async def ready(self) -> bool:
        return True

    async def _callbacks(self, request: DialRequest) -> None:
        headers = {"x-webhook-token": self.settings.webhook_token} if self.settings.webhook_token else {}
        async with httpx.AsyncClient(timeout=self.settings.request_timeout_sec) as client:
            for state in ("dialing", "answered"):
                try:
                    await client.post(str(request.webhook_url), headers=headers, json={
                        "call_id": request.call_id,
                        "kind": "status",
                        "payload": {"status": state, **request.metadata},
                    })
                except httpx.HTTPError:
                    return


class PbxHttpDriver(VoiceDriver):
    def __init__(self, settings: Settings):
        self.settings = settings

    async def post(self, action: str, payload: dict[str, Any]) -> dict[str, Any]:
        headers = {"Authorization": f"Bearer {self.settings.pbx_bearer_token}"} if self.settings.pbx_bearer_token else {}
        async with httpx.AsyncClient(timeout=self.settings.request_timeout_sec, headers=headers) as client:
            response = await client.post(f"{self.settings.pbx_base_url.rstrip('/')}/v1/call/{action}", json=payload)
        response.raise_for_status()
        return response.json()

    async def ready(self) -> bool:
        headers = {"Authorization": f"Bearer {self.settings.pbx_bearer_token}"} if self.settings.pbx_bearer_token else {}
        try:
            async with httpx.AsyncClient(timeout=self.settings.request_timeout_sec, headers=headers) as client:
                response = await client.get(f"{self.settings.pbx_base_url.rstrip('/')}/readyz")
            return 200 <= response.status_code < 300
        except httpx.HTTPError:
            return False


def make_driver(settings: Settings) -> VoiceDriver:
    return PbxHttpDriver(settings) if settings.voice_gateway_driver.strip().lower() == "pbx_http" else MockDriver(settings)
