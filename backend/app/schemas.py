from datetime import datetime
from typing import Any, List, Optional
from uuid import UUID
from pydantic import Field
from pydantic import BaseModel
from .models import CallMode, CallStatus, ConsentState


class ContactCreate(BaseModel):
    phone: str = Field(min_length=6, max_length=32)
    name: Optional[str] = Field(default=None, max_length=200)
    tags: str = Field(default="", max_length=2000)
    consent_state: ConsentState = ConsentState.UNKNOWN
    dnc: bool = False
    timezone: Optional[str] = Field(default="Asia/Shanghai", max_length=64)


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
    name: str = Field(min_length=1, max_length=200)
    script: str = Field(default="", max_length=50_000)
    script_template_id: Optional[int] = None
    mode: CallMode
    concurrency: int = Field(default=10, ge=1, le=1000)
    retry_limit: int = Field(default=1, ge=1, le=10)
    retry_interval_sec: int = Field(default=1800, ge=1, le=604_800)
    attempt_interval_sec: int = Field(default=1800, ge=1, le=604_800)
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
                            "code": "DIAL_FAILED",
                            "message": "telephony provider return 503",
                            "call_id": "3f9f8f3d-5a66-4bb1-8f2c-2b4de3f9db3d",
                        }
                    ],
                    "error_codes": ["DIAL_FAILED"],
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
                            "code": "DIAL_FAILED",
                            "message": "provider timeout",
                            "call_id": "3f9f8f3d-5a66-4bb1-8f2c-2b4de3f9db3d",
                        }
                    ],
                    "error_codes": ["DIAL_FAILED"],
                },
                "result_code": "PARTIAL_SUCCESS",
                "result_message": "campaign started with partial success",
                "error_codes": ["CONTACT_DNC", "DIAL_FAILED"],
                "skip_reasons": [
                    {
                        "code": "CONTACT_DNC",
                        "message": "contact dnc",
                        "phone": "13800138000",
                        "contact_id": 8,
                    }
                ],
            }
        }
    }


class StartCallRequest(BaseModel):
    phone: Optional[str] = Field(default=None, min_length=6, max_length=32)
    mode: CallMode = CallMode.AI_HANDOFF
    campaign_id: Optional[int] = None
    contact_id: Optional[int] = None
    max_attempts: int = Field(default=1, ge=1, le=10)


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
    human_agent_id: Optional[int] = None
    recording_url: Optional[str] = None
    ai_session_id: Optional[str] = None
    telephony_call_id: Optional[str] = None
    last_error: Optional[str] = None
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None
    next_attempt_at: Optional[datetime] = None
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
    transcript: Optional[str] = Field(default=None, max_length=100_000)


class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=200)
    password: str = Field(min_length=1, max_length=1024)


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
    name: str = Field(min_length=1, max_length=200)
    content: str = Field(min_length=1, max_length=50_000)
    category: str = Field(default="default", max_length=100)
    description: str = Field(default="", max_length=2000)
    tags: str = Field(default="", max_length=2000)
    is_active: bool = True


class ScriptTemplateUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)
    content: str | None = Field(default=None, min_length=1, max_length=50_000)
    category: str | None = Field(default=None, max_length=100)
    description: str | None = Field(default=None, max_length=2000)
    tags: str | None = Field(default=None, max_length=2000)
    is_active: bool | None = None


class ScriptTemplateOut(ScriptTemplateCreate):
    id: int
    tenant_id: int
    version: int
    created_by: int | None = None
    created_at: datetime
    updated_at: datetime


class AdminUserCreate(BaseModel):
    username: str = Field(min_length=3, max_length=200, pattern=r"^[A-Za-z0-9@._-]+$")
    password: str = Field(min_length=8, max_length=1024)
    full_name: str = Field(min_length=1, max_length=200)
    phone: Optional[str] = Field(default=None, max_length=32)
    role: str = Field(default="agent", pattern=r"^(admin|agent)$")
    is_supervisor: bool = False
    enabled: bool = True


class AdminUserUpdate(BaseModel):
    full_name: Optional[str] = Field(default=None, min_length=1, max_length=200)
    phone: Optional[str] = Field(default=None, max_length=32)
    role: Optional[str] = Field(default=None, pattern=r"^(admin|agent)$")
    is_supervisor: Optional[bool] = None
    enabled: Optional[bool] = None


class AdminPasswordReset(BaseModel):
    password: str = Field(min_length=8, max_length=1024)


class AdminUserOut(UserOut):
    phone: Optional[str] = None
    created_at: datetime
    updated_at: datetime


class TelephonyLineCreate(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    provider: str = Field(default="http", min_length=1, max_length=100)
    gateway_url: str = Field(default="", max_length=1000)
    caller_id: str = Field(default="", max_length=64)
    max_concurrency: int = Field(default=10, ge=1, le=10_000)
    enabled: bool = True


class TelephonyLineUpdate(BaseModel):
    name: Optional[str] = Field(default=None, min_length=1, max_length=200)
    provider: Optional[str] = Field(default=None, min_length=1, max_length=100)
    gateway_url: Optional[str] = Field(default=None, max_length=1000)
    caller_id: Optional[str] = Field(default=None, max_length=64)
    max_concurrency: Optional[int] = Field(default=None, ge=1, le=10_000)
    enabled: Optional[bool] = None


class TelephonyLineOut(TelephonyLineCreate):
    id: int
    tenant_id: int
    created_at: datetime
    updated_at: datetime


class SmsLogOut(BaseModel):
    id: int
    tenant_id: int
    call_session_id: Optional[UUID] = None
    to_phone: str
    template_code: Optional[str] = None
    content: str
    state: str
    sent_at: Optional[datetime] = None
    created_at: datetime


class AdminSettingUpdate(BaseModel):
    data: dict[str, Any]


class AdminSettingOut(BaseModel):
    section: str
    data: dict[str, Any]
    updated_at: Optional[datetime] = None


class AuditLogOut(BaseModel):
    id: int
    tenant_id: int
    actor_user_id: Optional[int] = None
    actor_username: str
    action: str
    resource_type: str
    resource_id: Optional[str] = None
    detail: str
    created_at: datetime
