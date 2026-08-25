from datetime import datetime
from enum import Enum
from typing import Optional
from uuid import uuid4, UUID

from sqlmodel import Field, SQLModel


class CallMode(str, Enum):
    HUMAN_ONLY = "human_only"
    AI_ONLY = "ai_only"
    AI_HANDOFF = "ai_handoff"
    AI_WITH_SMS = "ai_with_sms"


class CallStatus(str, Enum):
    CREATED = "created"
    DIALING = "dialing"
    CONNECTED = "connected"
    IN_AI = "in_ai"
    WAITING_HUMAN = "waiting_human"
    COMPLETED = "completed"
    FAILED = "failed"
    NO_ANSWER = "no_answer"


class EventType(str, Enum):
    STATUS = "status"
    TRANSCRIPT = "transcript"
    RECORDING = "recording"


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
    full_name: str
    role: str = "agent"
    enabled: bool = True
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class Contact(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    phone: str = Field(index=True)
    name: Optional[str] = None
    tags: str = ""
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class Campaign(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    name: str
    script: str = ""
    mode: CallMode = Field(default=CallMode.AI_HANDOFF)
    concurrency: int = 5
    retry_limit: int = 1
    retry_interval_sec: int = 30
    created_by: Optional[int] = Field(default=None, foreign_key="user.id")
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class CampaignContact(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    campaign_id: int = Field(foreign_key="campaign.id", index=True)
    contact_id: int = Field(foreign_key="contact.id", index=True)
    contact_order: int = 0


class CallSession(SQLModel, table=True):
    id: UUID = Field(default_factory=uuid4, primary_key=True)
    tenant_id: int = Field(index=True, foreign_key="tenant.id")
    campaign_id: Optional[int] = Field(default=None, foreign_key="campaign.id")
    contact_id: Optional[int] = Field(default=None, foreign_key="contact.id")
    phone: str
    mode: CallMode
    status: CallStatus = Field(default=CallStatus.CREATED)
    attempts: int = 0
    max_attempts: int = 1
    human_agent_id: Optional[int] = Field(default=None, foreign_key="user.id")
    handoff_reason: Optional[str] = None
    recording_url: Optional[str] = None
    ai_session_id: Optional[str] = None
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class CallEvent(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    call_session_id: UUID = Field(index=True, foreign_key="callsession.id")
    event_type: EventType
    source: str = "platform"
    payload: str = "{}"
    created_at: datetime = Field(default_factory=datetime.utcnow)
