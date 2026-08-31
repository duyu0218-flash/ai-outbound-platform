from datetime import datetime
from enum import Enum
from typing import Optional
from uuid import UUID, uuid4

from sqlalchemy import UniqueConstraint
from sqlmodel import Field, SQLModel

from .clock import utc_now


class CallMode(str, Enum):
    HUMAN_ONLY = "human_only"
    AI_ONLY = "ai_only"
    AI_HANDOFF = "ai_handoff"
    AI_WITH_SMS = "ai_with_sms"
    MIXED_HUMAN_FIRST = "mixed_human_first"


class CallStatus(str, Enum):
    CREATED = "created"
    QUEUED = "queued"
    DIALING = "dialing"
    ANSWERED = "answered"
    IN_AI = "in_ai"
    WAITING_HUMAN = "waiting_human"
    HANDOFF_TRANSFERRING = "handoff_transferring"
    IN_HUMAN = "in_human"
    COMPLETED = "completed"
    FAILED = "failed"
    NO_ANSWER = "no_answer"
    BUSY = "busy"
    VOICEMAIL = "voicemail"


class EventType(str, Enum):
    STATUS = "status"
    TRANSCRIPT = "transcript"
    RECORDING = "recording"
    SMS = "sms"
    AI_DECISION = "ai_decision"
    ERROR = "error"


class RealtimeState(str, Enum):
    CREATED = "created"
    LISTENING = "listening"
    THINKING = "thinking"
    SPEAKING = "speaking"
    INTERRUPTED = "interrupted"
    CLOSED = "closed"


class HandoffState(str, Enum):
    WAITING = "waiting"
    ACCEPTING = "accepting"
    ACCEPTED = "accepted"
    REJECTED = "rejected"
    COMPLETED = "completed"
    EXPIRED = "expired"


class ConsentState(str, Enum):
    CONSENTED = "consented"
    NOT_CONSENTED = "not_consented"
    REVOKED = "revoked"
    UNKNOWN = "unknown"


class TaskState(str, Enum):
    PENDING = "pending"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"
    DEAD = "dead"


class Tenant(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    name: str
    code: str = Field(index=True, unique=True)
    enabled: bool = True
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class User(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    username: str = Field(index=True, unique=True)
    password_hash: str = ""
    full_name: str
    phone: Optional[str] = None
    role: str = "agent"
    is_supervisor: bool = False
    agent_status: str = Field(default="offline", max_length=32)
    last_seen_at: Optional[datetime] = None
    failed_login_attempts: int = 0
    locked_until: Optional[datetime] = None
    token_version: int = 0
    last_login_at: Optional[datetime] = None
    enabled: bool = True
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class Contact(SQLModel, table=True):
    __table_args__ = (UniqueConstraint("tenant_id", "phone", name="uq_contact_tenant_phone"),)

    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    phone: str = Field(index=True)
    name: Optional[str] = None
    tags: str = ""
    consent_state: ConsentState = Field(default=ConsentState.UNKNOWN)
    consented_at: Optional[datetime] = None
    consented_by: Optional[str] = None
    dnc: bool = False
    dnc_reason: Optional[str] = None
    timezone: Optional[str] = "Asia/Shanghai"
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class Campaign(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    name: str
    script: str = ""
    script_template_id: Optional[int] = Field(default=None, foreign_key="scripttemplate.id")
    script_flow_version_id: Optional[int] = Field(default=None, foreign_key="scriptflowversion.id")
    mode: CallMode = Field(default=CallMode.AI_HANDOFF)
    concurrency: int = 5
    retry_limit: int = 1
    retry_interval_sec: int = 30
    attempt_interval_sec: int = 1800
    recording_enabled: bool = True
    hangup_sms_enabled: bool = True
    voice_ai_pipeline: str = Field(default="inherit", max_length=16)
    created_by: Optional[int] = Field(default=None, foreign_key="user.id")
    status: str = "draft"
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class CampaignContact(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    campaign_id: int = Field(foreign_key="campaign.id", index=True)
    contact_id: int = Field(foreign_key="contact.id", index=True)
    contact_order: int = 0
    is_active: bool = True


class ScriptTemplate(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    name: str
    content: str
    category: str = "default"
    description: str = ""
    tags: str = ""
    is_active: bool = True
    version: int = 1
    created_by: Optional[int] = Field(default=None, foreign_key="user.id")
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class ScriptFlowVersion(SQLModel, table=True):
    __table_args__ = (
        UniqueConstraint("script_template_id", "version", name="uq_script_flow_template_version"),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    script_template_id: int = Field(index=True, foreign_key="scripttemplate.id")
    version: int = Field(default=1)
    name: str = ""
    description: str = ""
    status: str = Field(default="draft", index=True, max_length=32)
    graph_json: str = "{}"
    created_by: Optional[int] = Field(default=None, foreign_key="user.id")
    published_at: Optional[datetime] = None
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class CallSession(SQLModel, table=True):
    id: UUID = Field(default_factory=uuid4, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    campaign_id: Optional[int] = Field(default=None, foreign_key="campaign.id")
    script_flow_version_id: Optional[int] = Field(default=None, foreign_key="scriptflowversion.id")
    flow_node_key: Optional[str] = Field(default=None, max_length=128)
    contact_id: Optional[int] = Field(default=None, foreign_key="contact.id")
    phone: str
    mode: CallMode
    status: CallStatus = Field(default=CallStatus.CREATED, index=True)
    attempts: int = 0
    max_attempts: int = 1
    last_error: Optional[str] = None
    human_agent_id: Optional[int] = Field(default=None, foreign_key="user.id")
    handoff_reason: Optional[str] = None
    recording_url: Optional[str] = None
    ai_session_id: Optional[str] = None
    telephony_call_id: Optional[str] = None
    telephony_line_id: Optional[int] = Field(default=None, foreign_key="telephonyline.id", index=True)
    conversation_id: Optional[str] = None
    voice_ai_pipeline: str = Field(default="pending", max_length=16)
    last_transcript: Optional[str] = None
    summary: Optional[str] = None
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None
    next_attempt_at: Optional[datetime] = Field(default=None, index=True)
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now, index=True)


class CallEvent(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    call_session_id: UUID = Field(index=True, foreign_key="callsession.id")
    # Event names are extensible (for example ai_start / ai_decision), so this
    # column must not be constrained to the original telephony-only enum.
    event_type: str = Field(index=True, max_length=64)
    source: str = "platform"
    payload: str = "{}"
    created_at: datetime = Field(default_factory=utc_now)


class RealtimeSession(SQLModel, table=True):
    __table_args__ = (UniqueConstraint("call_session_id", name="uq_realtime_call"),)

    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    call_session_id: UUID = Field(index=True, foreign_key="callsession.id")
    provider_session_id: Optional[str] = Field(default=None, index=True, max_length=255)
    state: RealtimeState = Field(default=RealtimeState.CREATED, index=True)
    codec: str = Field(default="pcm_s16le", max_length=32)
    sample_rate: int = 16000
    channel_count: int = 1
    turn_sequence: int = 0
    playback_id: Optional[str] = Field(default=None, max_length=255)
    started_at: Optional[datetime] = None
    ended_at: Optional[datetime] = None
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class SpeechTurn(SQLModel, table=True):
    __table_args__ = (
        UniqueConstraint("call_session_id", "provider_event_key", name="uq_speechturn_call_event"),
    )

    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    call_session_id: UUID = Field(index=True, foreign_key="callsession.id")
    provider_event_key: str = Field(max_length=128)
    turn_index: int = Field(default=0, index=True)
    speaker_role: str = Field(default="customer", max_length=32)
    channel_id: str = Field(default="inbound", max_length=64)
    transcript: str = Field(default="", max_length=100_000)
    normalized_transcript: str = Field(default="", max_length=100_000)
    is_final: bool = False
    confidence: Optional[float] = None
    start_ms: Optional[int] = None
    end_ms: Optional[int] = None
    asr_provider: str = Field(default="", max_length=100)
    created_at: datetime = Field(default_factory=utc_now, index=True)


class CallMetric(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    call_session_id: UUID = Field(index=True, foreign_key="callsession.id")
    stage: str = Field(index=True, max_length=64)
    provider: str = Field(default="", max_length=100)
    duration_ms: Optional[int] = None
    success: bool = True
    error_code: Optional[str] = Field(default=None, max_length=100)
    detail: str = Field(default="", max_length=2000)
    created_at: datetime = Field(default_factory=utc_now, index=True)


class TaskOutbox(SQLModel, table=True):
    __table_args__ = (UniqueConstraint("idempotency_key", name="uq_taskoutbox_idempotency"),)

    id: UUID = Field(default_factory=uuid4, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    task_type: str = Field(index=True, max_length=64)
    aggregate_id: str = Field(index=True, max_length=128)
    idempotency_key: str = Field(max_length=255)
    payload_json: str = "{}"
    state: TaskState = Field(default=TaskState.PENDING, index=True)
    attempts: int = 0
    max_attempts: int = 5
    available_at: datetime = Field(default_factory=utc_now, index=True)
    locked_at: Optional[datetime] = None
    last_error: str = Field(default="", max_length=2000)
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class RecordingAsset(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    call_session_id: UUID = Field(index=True, foreign_key="callsession.id")
    provider_recording_id: Optional[str] = Field(default=None, index=True, max_length=255)
    provider_url: str = Field(default="", max_length=2000)
    storage_uri: str = Field(default="", max_length=2000)
    state: str = Field(default="available", index=True, max_length=32)
    duration_sec: Optional[int] = None
    media_format: str = Field(default="", max_length=32)
    channel_count: int = 1
    checksum_sha256: Optional[str] = Field(default=None, max_length=64)
    retention_until: Optional[datetime] = None
    deleted_at: Optional[datetime] = None
    created_at: datetime = Field(default_factory=utc_now, index=True)
    updated_at: datetime = Field(default_factory=utc_now)


class CallAnalysis(SQLModel, table=True):
    __table_args__ = (UniqueConstraint("call_session_id", name="uq_callanalysis_call"),)

    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    call_session_id: UUID = Field(index=True, foreign_key="callsession.id")
    result_code: str = Field(default="unknown", index=True, max_length=64)
    sentiment: str = Field(default="neutral", max_length=32)
    intent: str = Field(default="unknown", max_length=100)
    summary: str = Field(default="", max_length=10_000)
    qa_score: int = 0
    qa_flags_json: str = "[]"
    structured_json: str = "{}"
    review_state: str = Field(default="auto", max_length=32)
    reviewed_by: Optional[int] = Field(default=None, foreign_key="user.id")
    reviewed_at: Optional[datetime] = None
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class KnowledgeItem(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    title: str = Field(max_length=300)
    content: str = Field(max_length=50_000)
    category: str = Field(default="default", index=True, max_length=100)
    keywords: str = Field(default="", max_length=2000)
    is_active: bool = True
    version: int = 1
    created_by: Optional[int] = Field(default=None, foreign_key="user.id")
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class HandoffRequest(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    call_session_id: UUID = Field(index=True, foreign_key="callsession.id")
    assigned_agent_id: Optional[int] = Field(default=None, index=True, foreign_key="user.id")
    state: HandoffState = Field(default=HandoffState.WAITING, index=True)
    reason: str = Field(default="", max_length=500)
    target_group: str = Field(default="", max_length=200)
    requested_at: datetime = Field(default_factory=utc_now)
    responded_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    updated_at: datetime = Field(default_factory=utc_now)


class WebhookEventIngest(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    call_session_id: UUID = Field(index=True, foreign_key="callsession.id")
    event_type: str = Field(index=True)
    source: str = Field(index=True)
    provider_event_key: str = Field(index=True, unique=True)
    repeat_count: int = 1
    created_at: datetime = Field(default_factory=utc_now)


class SmsLog(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    call_session_id: Optional[UUID] = Field(default=None, foreign_key="callsession.id")
    to_phone: str = Field(index=True)
    template_code: Optional[str] = None
    content: str
    state: str = "pending"
    provider_message_id: Optional[str] = Field(default=None, index=True, max_length=255)
    provider_error: Optional[str] = Field(default=None, max_length=2000)
    sent_at: Optional[datetime] = None
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class TelephonyLine(SQLModel, table=True):
    __table_args__ = (UniqueConstraint("tenant_id", "name", name="uq_telephonyline_tenant_name"),)

    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    name: str = Field(index=True, max_length=200)
    provider: str = Field(default="http", max_length=100)
    gateway_url: str = Field(default="", max_length=1000)
    caller_id: str = Field(default="", max_length=64)
    max_concurrency: int = 10
    priority: int = 100
    weight: int = 1
    credential_ref: str = Field(default="", max_length=128)
    enabled: bool = True
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class AdminSetting(SQLModel, table=True):
    __table_args__ = (UniqueConstraint("tenant_id", "section", name="uq_adminsetting_tenant_section"),)

    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    section: str = Field(index=True, max_length=64)
    data_json: str = "{}"
    updated_by: Optional[int] = Field(default=None, foreign_key="user.id")
    updated_at: datetime = Field(default_factory=utc_now)


class AuditLog(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    actor_user_id: Optional[int] = Field(default=None, foreign_key="user.id")
    actor_username: str = Field(default="system", index=True, max_length=200)
    action: str = Field(index=True, max_length=100)
    resource_type: str = Field(index=True, max_length=100)
    resource_id: Optional[str] = Field(default=None, max_length=200)
    detail: str = Field(default="", max_length=4000)
    created_at: datetime = Field(default_factory=utc_now, index=True)
