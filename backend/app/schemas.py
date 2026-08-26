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
    script_template_id: Optional[int] = None
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


class CampaignDispatchError(BaseModel):
    code: str
    message: str
    phone: Optional[str] = None
    contact_id: Optional[int] = None
    call_id: Optional[str] = None


class CampaignDispatchResult(BaseModel):
    total: int
    target: int
    succeeded: int
    failed: int
    skipped: int
    status: str
    errors: List[CampaignDispatchError] = Field(default_factory=list)
    error_codes: List[str] = Field(default_factory=list)

    model_config = {
        "json_schema_extra": {
            "examples": [
                {
                    "total": 100,
                    "target": 10,
                    "succeeded": 8,
                    "failed": 1,
                    "skipped": 1,
                    "status": "completed",
                    "errors": [
                        {
                            "code": "dial_failed",
                            "message": "telephony provider return 503",
                            "call_id": "3f9f8f3d-5a66-4bb1-8f2c-2b4de3f9db3d",
                        }
                    ],
                    "error_codes": ["dial_failed"],
                }
            ]
        }
    }


class CampaignStartResponse(BaseModel):
    id: int
    tenant_id: int
    name: str
    status: str
    total_contacts: int
    created: int
    skipped: int
    campaign_status: str
    auto_dial_requested: bool
    auto_dial_count: int
    dispatch_mode: str
    dispatch_result: CampaignDispatchResult
    result_code: str
    result_message: str
    error_codes: List[str] = Field(default_factory=list)
    skip_reasons: List[CampaignDispatchError] = Field(default_factory=list)

    model_config = {
        "json_schema_extra": {
            "example": {
                "id": 11,
                "tenant_id": 1,
                "name": "demo campaign",
                "status": "running",
                "total_contacts": 20,
                "created": 10,
                "skipped": 2,
                "campaign_status": "running",
                "auto_dial_requested": True,
                "auto_dial_count": 10,
                "dispatch_mode": "sync",
                "dispatch_result": {
                    "total": 12,
                    "target": 10,
                    "succeeded": 9,
                    "failed": 1,
                    "skipped": 2,
                    "status": "completed",
                    "errors": [
                        {
                            "code": "dial_failed",
                            "message": "provider timeout",
                            "call_id": "3f9f8f3d-5a66-4bb1-8f2c-2b4de3f9db3d",
                        }
                    ],
                    "error_codes": ["dial_failed"],
                },
                "result_code": "PARTIAL_SUCCESS",
                "result_message": "campaign started with partial success",
                "error_codes": ["dial_failed", "contact_dnc"],
                "skip_reasons": [
                    {
                        "code": "contact_dnc",
                        "message": "contact dnc",
                        "phone": "13800138000",
                        "contact_id": 8,
                    }
                ],
            }
        }
    }


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


class WebhookEventIngestOut(BaseModel):
    id: int
    call_session_id: UUID
    event_type: str
    source: str
    provider_event_key: str
    repeat_count: int = 1
    created_at: datetime


class CallWebhookStatsItem(BaseModel):
    event_type: str
    source: str
    count: int


class CallWebhookStatsOut(BaseModel):
    total: int
    unique: int
    duplicate_estimate: int
    buckets: list[CallWebhookStatsItem]


class WebhookEvent(BaseModel):
    call_id: UUID
    kind: str
    payload: dict = Field(default_factory=dict)
    transcript: Optional[str] = None


class LoginRequest(BaseModel):
    username: str
    password: str


class LoginResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    username: str
    role: str
    tenant_id: int


class UserOut(BaseModel):
    id: int
    tenant_id: int
    username: str
    full_name: str
    role: str
    is_supervisor: bool
    enabled: bool


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
    script: str = ""
    transcript: Optional[str] = ""
    context: dict = Field(default_factory=dict)


class AiTurnResult(BaseModel):
    action: str
    tts_text: Optional[str] = None
    handoff_to_human: bool = False
    hangup_sms: Optional[str] = None
    next_keywords: List[str] = Field(default_factory=list)
    escalate_priority: int = 0


class ScriptTemplateCreate(BaseModel):
    name: str
    content: str
    category: str = "default"
    description: str = ""
    tags: str = ""
    is_active: bool = True


class ScriptTemplateUpdate(BaseModel):
    name: str | None = None
    content: str | None = None
    category: str | None = None
    description: str | None = None
    tags: str | None = None
    is_active: bool | None = None


class ScriptTemplateOut(ScriptTemplateCreate):
    id: int
    tenant_id: int
    version: int
    created_by: int | None = None
    created_at: datetime
    updated_at: datetime
