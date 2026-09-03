from typing import Any

from pydantic import BaseModel, Field, HttpUrl


class DialRequest(BaseModel):
    call_id: str = Field(min_length=1, max_length=128)
    phone: str = Field(min_length=6, max_length=32)
    webhook_url: HttpUrl
    metadata: dict[str, Any] = Field(default_factory=dict)


class SpeakRequest(BaseModel):
    call_id: str
    tenant_id: int | None = None
    text: str = Field(min_length=1, max_length=50_000)
    language: str = "zh-CN"
    voice: str = ""
    provider: str = ""


class CallRequest(BaseModel):
    call_id: str
    tenant_id: int | None = None
    reason: str = ""
    target_group: str | None = None
