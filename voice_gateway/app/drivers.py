from __future__ import annotations

import asyncio
from abc import ABC, abstractmethod
from typing import Any

import httpx

from .config import Settings
from .freeswitch import FreeswitchEslDriver
from .models import CallRequest, DialRequest, SpeakRequest
from .security import CallbackSender, validate_callback_url


class VoiceDriver(ABC):
    @abstractmethod
    async def post(self, action: str, payload: dict[str, Any]) -> dict[str, Any]: ...

    @abstractmethod
    async def ready(self) -> bool: ...

    async def start(self) -> None:
        return None

    async def stop(self) -> None:
        return None


class MockDriver(VoiceDriver):
    def __init__(self, settings: Settings):
        self.settings = settings
        self.sender = CallbackSender(settings)

    async def start(self) -> None:
        await self.sender.start()

    async def stop(self) -> None:
        await self.sender.stop()

    async def post(self, action: str, payload: dict[str, Any]) -> dict[str, Any]:
        if action == "dial":
            validate_callback_url(self.settings, str(payload["webhook_url"]))
            asyncio.create_task(self._callbacks(DialRequest.model_validate(payload)))
        return {"result": "accepted", "action": action, "provider_call_id": f"gateway-{payload['call_id']}"}

    async def ready(self) -> bool:
        return True

    async def _callbacks(self, request: DialRequest) -> None:
        for state in ("dialing", "answered"):
            try:
                await self.sender.post(str(request.webhook_url), {
                    "call_id": request.call_id, "kind": "status",
                    "payload": {**request.metadata, "status": state},
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
    driver = settings.voice_gateway_driver.strip().lower()
    if driver == "pbx_http":
        return PbxHttpDriver(settings)
    if driver == "freeswitch_esl":
        from .security import SecureDriver

        return SecureDriver(settings, FreeswitchEslDriver(settings))  # type: ignore[return-value]
    return MockDriver(settings)
