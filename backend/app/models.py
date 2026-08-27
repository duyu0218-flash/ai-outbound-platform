from datetime import datetime
from enum import Enum
from typing import Optional
from uuid import UUID, uuid4

from sqlmodel import Field, SQLModel


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


class ConsentState(str, Enum):
    CONSENTED = "consented"
    NOT_CONSENTED = "not_consented"
    REVOKED = "revoked"
    UNKNOWN = "unknown"


class Tenant(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    name: str
    code: str = Field(index=True, unique=True)
    enabled: bool = True
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class User(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    username: str = Field(index=True, unique=True)
    password_hash: str = ""
    full_name: str
    phone: Optional[str] = None
    role: str = "agent"
    is_supervisor: bool = False
    enabled: bool = True
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class Contact(SQLModel, table=True):
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
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class Campaign(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    name: str
    script: str = ""
    script_template_id: Optional[int] = Field(default=None, foreign_key="scripttemplate.id")
    mode: CallMode = Field(default=CallMode.AI_HANDOFF)
    concurrency: int = 5
    retry_limit: int = 1
    retry_interval_sec: int = 30
    attempt_interval_sec: int = 1800
    recording_enabled: bool = True
    hangup_sms_enabled: bool = True
    created_by: Optional[int] = Field(default=None, foreign_key="user.id")
    status: str = "draft"
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


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
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class CallSession(SQLModel, table=True):
    id: UUID = Field(default_factory=uuid4, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    campaign_id: Optional[int] = Field(default=None, foreign_key="campaign.id")
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
    conversation_id: Optional[str] = None
    last_transcript: Optional[str] = None
    summary: Optional[str] = None
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow, index=True)


class CallEvent(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    call_session_id: UUID = Field(index=True, foreign_key="callsession.id")
    event_type: EventType
    source: str = "platform"
    payload: str = "{}"
    created_at: datetime = Field(default_factory=datetime.utcnow)


class WebhookEventIngest(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    call_session_id: UUID = Field(index=True, foreign_key="callsession.id")
    event_type: str = Field(index=True)
    source: str = Field(index=True)
    provider_event_key: str = Field(index=True, unique=True)
    repeat_count: int = 1
    created_at: datetime = Field(default_factory=datetime.utcnow)


class SmsLog(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    call_session_id: Optional[UUID] = Field(default=None, foreign_key="callsession.id")
    to_phone: str = Field(index=True)
    template_code: Optional[str] = None
    content: str
    state: str = "pending"
    sent_at: Optional[datetime] = None
    created_at: datetime = Field(default_factory=datetime.utcnow)
