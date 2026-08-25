from datetime import datetime
from typing import List, Optional
from uuid import UUID
from pydantic import Field

from pydantic import BaseModel

from .models import CallMode, CallStatus


class ContactCreate(BaseModel):
    phone: str
    name: Optional[str] = None
    tags: str = ""


class ContactOut(ContactCreate):
    id: int
    tenant_id: int


class CampaignCreate(BaseModel):
    name: str
    script: str = ""
    mode: CallMode
    concurrency: int = 5
    retry_limit: int = 1
    retry_interval_sec: int = 30
    contact_ids: List[int] = Field(default_factory=list)


class CampaignOut(CampaignCreate):
    id: int
    tenant_id: int


class StartCallRequest(BaseModel):
    phone: str
    mode: CallMode
    campaign_id: Optional[int] = None
    contact_id: Optional[int] = None
    max_attempts: int = 1


class CallSessionOut(BaseModel):
    id: UUID
    phone: str
    mode: CallMode
    status: CallStatus
    attempts: int
    max_attempts: int
    handoff_reason: Optional[str] = None
    recording_url: Optional[str] = None
    ai_session_id: Optional[str] = None
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None


class WebhookEvent(BaseModel):
    call_id: UUID
    kind: str
    payload: dict = Field(default_factory=dict)
    transcript: Optional[str] = None


class AiTurnRequest(BaseModel):
    call_id: UUID
    phone: str
    mode: CallMode
    transcript: Optional[str] = ""
    context: dict = Field(default_factory=dict)


class AiTurnResult(BaseModel):
    action: str
    tts_text: Optional[str] = None
    handoff_to_human: bool = False
    hangup_sms: Optional[str] = None
    next_keywords: List[str] = Field(default_factory=list)
