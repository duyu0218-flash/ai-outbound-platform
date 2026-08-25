from datetime import datetime
from typing import List, Optional
from uuid import UUID
from pydantic import Field
from pydantic import BaseModel
from .models import CallMode, CallStatus, ConsentState


class ContactCreate(BaseModel):
    phone: str
    name: Optional[str] = None
    tags: str = ""
    consent_state: ConsentState = ConsentState.UNKNOWN
    dnc: bool = False
    timezone: Optional[str] = "Asia/Shanghai"


class ContactOut(ContactCreate):
    id: int
    tenant_id: int
    created_at: datetime
    updated_at: datetime


class ContactPatch(BaseModel):
    name: Optional[str] = None
    tags: Optional[str] = None
    consent_state: Optional[ConsentState] = None
    dnc: Optional[bool] = None


class CampaignCreate(BaseModel):
    name: str
    script: str = ""
    mode: CallMode
    concurrency: int = 10
    retry_limit: int = 1
    retry_interval_sec: int = 1800
    attempt_interval_sec: int = 1800
    recording_enabled: bool = True
    hangup_sms_enabled: bool = True
    contact_ids: List[int] = Field(default_factory=list)


class CampaignOut(CampaignCreate):
    id: int
    tenant_id: int
    status: str
    created_at: datetime
    updated_at: datetime


class StartCallRequest(BaseModel):
    phone: Optional[str] = None
    mode: CallMode = CallMode.AI_HANDOFF
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
    campaign_id: Optional[int] = None
    contact_id: Optional[int] = None
    handoff_reason: Optional[str] = None
    recording_url: Optional[str] = None
    ai_session_id: Optional[str] = None
    telephony_call_id: Optional[str] = None
    last_error: Optional[str] = None
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None
    created_at: datetime
    updated_at: datetime


class CallEventOut(BaseModel):
    id: int
    call_session_id: UUID
    event_type: str
    source: str
    payload: str
    created_at: datetime


class WebhookEvent(BaseModel):
    call_id: UUID
    kind: str
    payload: dict = Field(default_factory=dict)
    transcript: Optional[str] = None


class WebhookStatusPayload(BaseModel):
    status: str
    hangup_reason: Optional[str] = None
    telephony_call_id: Optional[str] = None


class WebhookRecordingPayload(BaseModel):
    url: str
    duration_sec: Optional[int] = None
    format: Optional[str] = None


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
    escalate_priority: int = 0
