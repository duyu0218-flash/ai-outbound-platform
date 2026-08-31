from __future__ import annotations

from pydantic import BaseModel, Field, HttpUrl


class RecordingIngestRequest(BaseModel):
    recording_asset_id: int = Field(gt=0)
    tenant_id: int = Field(gt=0)
    call_id: str = Field(min_length=1, max_length=128)
    provider_recording_id: str | None = Field(default=None, max_length=256)
    provider_url: HttpUrl


class RecordingIngestResponse(BaseModel):
    storage_uri: str
    checksum_sha256: str
    size_bytes: int


class RecordingDeleteRequest(BaseModel):
    recording_asset_id: int = Field(gt=0)
    tenant_id: int = Field(gt=0)
    call_id: str = Field(min_length=1, max_length=128)
    provider_recording_id: str | None = Field(default=None, max_length=256)
    provider_url: str | None = None
    storage_uri: str | None = None


class RecordingDeleteResponse(BaseModel):
    deleted: bool
