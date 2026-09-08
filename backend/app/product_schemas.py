from __future__ import annotations
from datetime import date, datetime
from typing import Literal
from zoneinfo import ZoneInfo
from pydantic import BaseModel, Field, model_validator


class SlotDefinition(BaseModel):
    key: str = Field(pattern=r'^[a-z][a-z0-9_]{0,39}$')
    label: str = Field(min_length=1, max_length=100)
    question: str = Field(min_length=1, max_length=500)
    required: bool = True
    kind: Literal['text','integer','choice','date','digits'] = 'text'
    choices: list[str] = Field(default_factory=list, max_length=50)
    confirm: bool = True
    qualifies: list[str] = Field(default_factory=list, max_length=50)

    @model_validator(mode='after')
    def validate_choices(self):
        if self.kind == 'choice' and not self.choices:
            raise ValueError('choice field requires choices')
        if self.qualifies and not set(self.qualifies).issubset(self.choices):
            raise ValueError('qualifying values must be configured choices')
        return self


class ScenarioPolicy(BaseModel):
    name: str = Field(default='默认业务策略', min_length=1, max_length=100)
    timezone: str = 'Asia/Shanghai'
    weekdays: list[int] = Field(default_factory=lambda:list(range(7)), max_length=7)
    holidays: list[date] = Field(default_factory=list,max_length=366)
    start_hour: int = Field(default=9,ge=0,le=23)
    end_hour: int = Field(default=20,ge=1,le=24)
    silence_seconds: int = Field(default=10,ge=3,le=120)
    wait_seconds: int = Field(default=30,ge=5,le=180)
    max_clarifications: int = Field(default=2,ge=1,le=5)
    confidence_threshold: float = Field(default=0.55,ge=0,le=1)
    handoff_wait_seconds: int = Field(default=45,ge=5,le=300)
    handoff_agent_ids: list[int] = Field(default_factory=list,max_length=100)
    handoff_fallback: Literal['end','ai'] = 'end'
    machine_action: Literal['confirm','end'] = 'confirm'
    silence_prompt: str = Field(default='您好，您还在听吗？',min_length=1,max_length=500)
    clarify_prompt: str = Field(default='抱歉，我没有听清楚，请您再说一次。',min_length=1,max_length=500)
    model_wait_seconds: int = Field(default=4,ge=1,le=20)
    model_wait_prompt: str = Field(default='请稍等，我正在为您确认。',min_length=1,max_length=200)
    failure_prompt: str = Field(default='抱歉，当前服务暂时无法继续，我们先结束本次通话。',min_length=1,max_length=500)
    handoff_prompt: str = Field(default='正在为您联系人工客服，请稍候。',min_length=1,max_length=500)
    handoff_timeout_prompt: str = Field(default='抱歉，暂时没有客服接听，本次先结束，您可以稍后联系。',min_length=1,max_length=500)
    opening_delay_ms: int = Field(default=0,ge=0,le=5000)
    allow_interruptions: bool = True
    min_interrupt_chars: int = Field(default=1,ge=1,le=10)
    sentence_silence_ms: int = Field(default=800,ge=200,le=2000)
    vocabulary_id: str = Field(default='',max_length=128,pattern=r'^[A-Za-z0-9_-]*$')
    voice: str = Field(default='',max_length=128)
    language: str = Field(default='zh-CN',pattern=r'^[a-z]{2,3}(?:-[A-Za-z]{2,4})?$')
    slots: list[SlotDefinition] = Field(default_factory=list,max_length=30)
    faqs: dict[str,str] = Field(default_factory=dict)
    retry_seconds: dict[str,int] = Field(default_factory=lambda:{'busy':1800,'no_answer':3600,'voicemail':86400})
    permanent_causes: list[str] = Field(default_factory=lambda:['UNALLOCATED_NUMBER','INVALID_NUMBER_FORMAT','NUMBER_CHANGED','CALL_REJECTED'])
    dtmf_end: Literal['#','*'] = '#'
    dtmf_max_digits: int = Field(default=20,ge=1,le=32)

    @model_validator(mode='after')
    def validate_policy(self):
        try:
            ZoneInfo(self.timezone)
        except (KeyError, ValueError) as exc:
            raise ValueError("invalid timezone") from exc
        if self.start_hour >= self.end_hour or any(day not in range(7) for day in self.weekdays):
            raise ValueError('invalid business calendar')
        if len({slot.key for slot in self.slots}) != len(self.slots):
            raise ValueError('duplicate field key')
        if len(self.faqs)>50 or any(not k or len(k)>100 or len(v)>2000 for k,v in self.faqs.items()):
            raise ValueError('invalid FAQ')
        if set(self.retry_seconds)-{'busy','no_answer','voicemail','failed'} or any(not 60<=v<=604800 for v in self.retry_seconds.values()):
            raise ValueError('invalid retry policy')
        return self


class ScenarioSave(BaseModel):
    campaign_id: int | None = None
    policy: ScenarioPolicy
    expected_version_id: int | None = None


class ScenarioProbe(BaseModel):
    policy: ScenarioPolicy
    utterances: list[str] = Field(min_length=1,max_length=100)


class AppointmentPatch(BaseModel):
    revision: int = Field(ge=1)
    scheduled_at: datetime | None = None
    cancel: bool = False
