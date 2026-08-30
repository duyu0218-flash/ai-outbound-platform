from datetime import datetime
from typing import Any, List, Optional
from uuid import UUID
from pydantic import Field
from pydantic import BaseModel
from .models import CallMode, CallStatus, ConsentState, HandoffState, RealtimeState


class ContactCreate(BaseModel):
    phone: str = Field(min_length=6, max_length=32)
    name: Optional[str] = Field(default=None, max_length=200)
    tags: str = Field(default="", max_length=2000)
    consent_state: ConsentState = ConsentState.UNKNOWN
    dnc: bool = False
    dnc_reason: str = Field(default="", max_length=500)
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
    dnc_reason: Optional[str] = None
    timezone: Optional[str] = Field(default=None, max_length=64)


class ContactImportItem(BaseModel):
    phone: str = Field(min_length=1, max_length=32)
    name: str = ""
    tags: str = ""
    consent_state: ConsentState = ConsentState.UNKNOWN
    dnc: bool = False
    dnc_reason: str = ""
    timezone: str = Field(default="Asia/Shanghai", max_length=64)


class ContactImportResult(BaseModel):
    total: int
    created: int
    updated: int
    skipped: int
    failed: int
    errors: list[str]


class ContactBatchDncPatch(BaseModel):
    contact_ids: list[int] = Field(min_length=1)
    dnc: bool
    dnc_reason: str = ""


class AdminCallReportItem(BaseModel):
    key: str
    label: str
    calls: int = 0
    reached: int = 0
    handoff: int = 0
    completed: int = 0
    failed: int = 0
    no_answer: int = 0
    loss: int = 0


class AdminReportTrendPoint(BaseModel):
    bucket: str
    calls: int = 0
    reached: int = 0
    completed: int = 0
    failed: int = 0
    handoff: int = 0


class AdminCallReportPayload(BaseModel):
    dimension: str
    window: dict[str, str | int]
    summary: AdminCallReportItem
    rows: list[AdminCallReportItem]
    trend: list[AdminReportTrendPoint]


class AdminContactGroupItem(BaseModel):
    key: str
    label: str
    contacts: int = 0
    dnc_contacts: int = 0
    calls: int = 0
    reached: int = 0
    handoff: int = 0
    completed: int = 0
    failed: int = 0
    no_answer: int = 0
    loss: int = 0


class AdminContactGroupPayload(BaseModel):
    window: dict[str, str | int]
    summary: AdminContactGroupItem
    rows: list[AdminContactGroupItem]


class AdminBillingRow(BaseModel):
    key: str
    label: str
    calls: int = 0
    billable_calls: int = 0
    reached: int = 0
    handoff: int = 0
    completed: int = 0
    failed: int = 0
    no_answer: int = 0
    loss: int = 0
    ai_minutes: float = 0
    sms_count: int = 0
    estimated_cost: float = 0


class AdminBillingSummary(BaseModel):
    calls: int = 0
    billable_calls: int = 0
    reached: int = 0
    handoff: int = 0
    completed: int = 0
    failed: int = 0
    no_answer: int = 0
    loss: int = 0
    ai_minutes: float = 0
    sms_count: int = 0
    ai_unit_price_per_minute: float = 0
    telephony_unit_price_per_minute: float = 0
    sms_unit_price: float = 0
    estimated_cost: float = 0


class AdminBillingPayload(BaseModel):
    dimension: str
    window: dict[str, str | int]
    rates: dict[str, float]
    summary: AdminBillingSummary
    rows: list[AdminBillingRow]


class ContactBatchDncResult(BaseModel):
    total: int
    updated: int
    skipped: int
    missing_contact_ids: list[int]
class CampaignCreate(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    script: str = Field(default="", max_length=50_000)
    script_template_id: Optional[int] = None
    script_flow_version_id: Optional[int] = None
    mode: CallMode
    concurrency: int = Field(default=5, ge=1, le=10_000)
    retry_limit: int = Field(default=1, ge=1, le=10)
    retry_interval_sec: int = Field(default=30, ge=1, le=604_800)
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
    script_flow_version_id: Optional[int] = None
    flow_node_key: Optional[str] = None
    contact_id: Optional[int] = None
    handoff_reason: Optional[str] = None
    human_agent_id: Optional[int] = None
    recording_url: Optional[str] = None
    ai_session_id: Optional[str] = None
    telephony_call_id: Optional[str] = None
    telephony_line_id: Optional[int] = None
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
    agent_status: str
    last_seen_at: Optional[datetime] = None
    enabled: bool


class AgentPresenceUpdate(BaseModel):
    status: str = Field(pattern=r"^(ready|busy|offline)$")


class SmsStatusWebhook(BaseModel):
    sms_log_id: Optional[int] = Field(default=None, ge=1)
    provider_message_id: Optional[str] = Field(default=None, max_length=255)
    state: str = Field(min_length=1, max_length=64)
    error: Optional[str] = Field(default=None, max_length=2000)


class WebhookStatusPayload(BaseModel):
    status: str
    hangup_reason: Optional[str] = None
    telephony_call_id: Optional[str] = None


class WebhookRecordingPayload(BaseModel):
    url: str
    duration_sec: Optional[int] = None
    format: Optional[str] = None


class SpeechWebhookEvent(BaseModel):
    call_id: UUID
    event_id: str = Field(min_length=1, max_length=255)
    transcript: str = Field(default="", max_length=100_000)
    is_final: bool = False
    speaker_role: str = Field(default="customer", pattern=r"^(customer|ai|agent|system)$")
    channel_id: str = Field(default="inbound", max_length=64)
    confidence: Optional[float] = Field(default=None, ge=0, le=1)
    start_ms: Optional[int] = Field(default=None, ge=0)
    end_ms: Optional[int] = Field(default=None, ge=0)
    asr_provider: str = Field(default="", max_length=100)
    barge_in: bool = False
    attempt: Optional[int] = Field(default=None, ge=0)


class MediaWebhookEvent(BaseModel):
    call_id: UUID
    event_id: str = Field(min_length=1, max_length=255)
    state: RealtimeState
    provider_session_id: Optional[str] = Field(default=None, max_length=255)
    playback_id: Optional[str] = Field(default=None, max_length=255)
    codec: str = Field(default="pcm_s16le", max_length=32)
    sample_rate: int = Field(default=16000, ge=8000, le=192000)
    channel_count: int = Field(default=1, ge=1, le=8)
    duration_ms: Optional[int] = Field(default=None, ge=0)
    provider: str = Field(default="", max_length=100)
    error_code: Optional[str] = Field(default=None, max_length=100)


class RealtimeSessionOut(BaseModel):
    id: int
    call_session_id: UUID
    provider_session_id: Optional[str] = None
    state: RealtimeState
    codec: str
    sample_rate: int
    channel_count: int
    turn_sequence: int
    playback_id: Optional[str] = None
    started_at: Optional[datetime] = None
    ended_at: Optional[datetime] = None
    created_at: datetime
    updated_at: datetime


class SpeechTurnOut(BaseModel):
    id: int
    call_session_id: UUID
    provider_event_key: str
    turn_index: int
    speaker_role: str
    channel_id: str
    transcript: str
    normalized_transcript: str
    is_final: bool
    confidence: Optional[float] = None
    start_ms: Optional[int] = None
    end_ms: Optional[int] = None
    asr_provider: str
    created_at: datetime


class CallMetricOut(BaseModel):
    id: int
    call_session_id: UUID
    stage: str
    provider: str
    duration_ms: Optional[int] = None
    success: bool
    error_code: Optional[str] = None
    detail: str
    created_at: datetime


class RecordingAssetOut(BaseModel):
    id: int
    call_session_id: UUID
    provider_recording_id: Optional[str] = None
    provider_url: str
    storage_uri: str
    state: str
    duration_sec: Optional[int] = None
    media_format: str
    channel_count: int
    checksum_sha256: Optional[str] = None
    retention_until: Optional[datetime] = None
    deleted_at: Optional[datetime] = None
    created_at: datetime
    updated_at: datetime


class CallAnalysisOut(BaseModel):
    id: int
    call_session_id: UUID
    result_code: str
    sentiment: str
    intent: str
    summary: str
    qa_score: int
    qa_flags_json: str
    structured_json: str
    review_state: str
    reviewed_by: Optional[int] = None
    reviewed_at: Optional[datetime] = None
    created_at: datetime
    updated_at: datetime


class CallAnalysisReview(BaseModel):
    result_code: Optional[str] = Field(default=None, max_length=64)
    sentiment: Optional[str] = Field(default=None, max_length=32)
    intent: Optional[str] = Field(default=None, max_length=100)
    summary: Optional[str] = Field(default=None, max_length=10_000)
    qa_score: Optional[int] = Field(default=None, ge=0, le=100)
    qa_flags: Optional[list[str]] = None


class KnowledgeItemCreate(BaseModel):
    title: str = Field(min_length=1, max_length=300)
    content: str = Field(min_length=1, max_length=50_000)
    category: str = Field(default="default", max_length=100)
    keywords: str = Field(default="", max_length=2000)
    is_active: bool = True


class KnowledgeItemUpdate(BaseModel):
    title: Optional[str] = Field(default=None, min_length=1, max_length=300)
    content: Optional[str] = Field(default=None, min_length=1, max_length=50_000)
    category: Optional[str] = Field(default=None, max_length=100)
    keywords: Optional[str] = Field(default=None, max_length=2000)
    is_active: Optional[bool] = None


class KnowledgeItemOut(KnowledgeItemCreate):
    id: int
    tenant_id: int
    version: int
    created_by: Optional[int] = None
    created_at: datetime
    updated_at: datetime


class HandoffRequestOut(BaseModel):
    id: int
    call_session_id: UUID
    assigned_agent_id: Optional[int] = None
    state: HandoffState
    reason: str
    target_group: str
    requested_at: datetime
    responded_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    updated_at: datetime


class HandoffQueueItemOut(HandoffRequestOut):
    phone: str
    mode: CallMode
    campaign_id: Optional[int] = None
    contact_name: Optional[str] = None
    campaign_name: Optional[str] = None
    intent: Optional[str] = None
    summary: str = ""
    last_customer_utterance: str = ""
    wait_seconds: int = 0


class QualityReviewQueueItemOut(BaseModel):
    call_id: UUID
    phone: str
    call_status: CallStatus
    campaign_id: Optional[int] = None
    campaign_name: Optional[str] = None
    result_code: str
    sentiment: str
    intent: str
    summary: str
    qa_score: int
    qa_flags_json: str
    review_state: str
    reviewed_by: Optional[int] = None
    reviewed_at: Optional[datetime] = None
    updated_at: datetime


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


class FlowPosition(BaseModel):
    x: float = Field(ge=0, le=5000)
    y: float = Field(ge=0, le=5000)


class FlowNode(BaseModel):
    id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")
    type: str = Field(pattern=r"^(start|message|listen|handoff|hangup)$")
    label: str = Field(min_length=1, max_length=200)
    prompt: str = Field(default="", max_length=20_000)
    position: FlowPosition


class FlowEdge(BaseModel):
    id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")
    source: str = Field(min_length=1, max_length=128)
    target: str = Field(min_length=1, max_length=128)
    condition: str = Field(default="always", pattern=r"^(always|keyword|silence)$")
    keywords: List[str] = Field(default_factory=list, max_length=50)


class ScriptFlowGraph(BaseModel):
    nodes: List[FlowNode] = Field(default_factory=list, max_length=200)
    edges: List[FlowEdge] = Field(default_factory=list, max_length=500)


class ScriptFlowCreate(BaseModel):
    name: str = Field(default="", max_length=200)
    description: str = Field(default="", max_length=2000)
    clone_version_id: Optional[int] = None


class ScriptFlowUpdate(BaseModel):
    name: Optional[str] = Field(default=None, max_length=200)
    description: Optional[str] = Field(default=None, max_length=2000)
    graph: ScriptFlowGraph


class ScriptFlowOut(BaseModel):
    id: int
    tenant_id: int
    script_template_id: int
    version: int
    name: str
    description: str
    status: str
    graph: ScriptFlowGraph
    published_at: Optional[datetime] = None
    created_at: datetime
    updated_at: datetime


class ScriptFlowSimulateRequest(BaseModel):
    current_node_id: Optional[str] = None
    transcript: str = Field(default="", max_length=20_000)
    silence: bool = False


class ScriptFlowSimulateOut(BaseModel):
    current_node_id: str
    next_node_id: Optional[str] = None
    action: str
    prompt: str = ""
    matched_edge_id: Optional[str] = None


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
    priority: int = Field(default=100, ge=1, le=10_000)
    weight: int = Field(default=1, ge=1, le=100)
    credential_ref: str = Field(default="", max_length=128, pattern=r"^[A-Za-z0-9_-]*$")
    enabled: bool = True


class TelephonyLineUpdate(BaseModel):
    name: Optional[str] = Field(default=None, min_length=1, max_length=200)
    provider: Optional[str] = Field(default=None, min_length=1, max_length=100)
    gateway_url: Optional[str] = Field(default=None, max_length=1000)
    caller_id: Optional[str] = Field(default=None, max_length=64)
    max_concurrency: Optional[int] = Field(default=None, ge=1, le=10_000)
    priority: Optional[int] = Field(default=None, ge=1, le=10_000)
    weight: Optional[int] = Field(default=None, ge=1, le=100)
    credential_ref: Optional[str] = Field(default=None, max_length=128, pattern=r"^[A-Za-z0-9_-]*$")
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
    provider_message_id: Optional[str] = None
    provider_error: Optional[str] = None
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
