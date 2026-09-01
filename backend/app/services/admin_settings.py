import json
from typing import Any

from sqlmodel import Session, select

from ..config import get_settings
from ..models import AdminSetting


settings = get_settings()


SETTING_DEFAULTS: dict[str, dict[str, Any]] = {
    "capacity": {
        "max_concurrent_calls": max(1, int(settings.max_concurrent_calls)),
    },
    "ai": {
        "enabled": True,
        "agent_url": settings.ai_agent_url,
        "llm_provider": settings.llm_provider,
        "llm_model": settings.openai_model,
        "asr_provider": "provider-default",
        "tts_provider": "provider-default",
        "voice": "female-1",
        "language": "zh-CN",
        "conversation_history_turns": 12,
        "max_reply_chars": 240,
        "forbidden_phrases": "保证收益,百分百,绝对有效",
        "fallback_reply": "抱歉，这个问题需要由人工客服为您确认。",
        "voice_ai_pipeline": "legacy",
        "pipecat_canary_percent": 0,
    },
    "sms": {
        "enabled": True,
        "provider": settings.sms_provider,
        "endpoint": settings.sms_provider_endpoint,
        "sender_id": settings.sms_sender_id,
        "hangup_template": "感谢您的接听，如需人工服务请回复 1。",
    },
    "compliance": {
        "dnc_enforced": True,
        "require_explicit_consent": True,
        "recording_notice": True,
        "recording_notice_text": "本次通话可能会被录音，用于服务和质量管理。",
        "allowed_start_hour": 9,
        "allowed_end_hour": 20,
        "timezone": "Asia/Shanghai",
        "max_attempts_per_day": 3,
        "min_attempt_interval_sec": 0,
        "recording_retention_days": int(settings.recording_retention_days),
        "partial_transcript_retention_hours": int(settings.partial_transcript_retention_hours),
    },
    "integration": {
        "callback_enabled": False,
        "webhook_base_url": "",
        "webhook_timeout_sec": 10,
        "webhook_secret_ref": "",
        "webhook_retry_times": 2,
        "webhook_retry_backoff_sec": 1,
    },
}


def get_admin_setting(session: Session, tenant_id: int, section: str) -> dict[str, Any]:
    defaults = SETTING_DEFAULTS.get(section)
    if defaults is None:
        return {}
    record = session.exec(
        select(AdminSetting).where(
            AdminSetting.tenant_id == tenant_id,
            AdminSetting.section == section,
        )
    ).first()
    if record is None:
        return dict(defaults)
    try:
        saved = json.loads(record.data_json)
    except (TypeError, ValueError, json.JSONDecodeError):
        return dict(defaults)
    if not isinstance(saved, dict):
        return dict(defaults)
    return {**defaults, **{key: value for key, value in saved.items() if key in defaults}}


def get_admin_int_setting(
    session: Session,
    tenant_id: int,
    section: str,
    key: str,
    fallback: int,
    *,
    minimum: int | None = None,
    maximum: int | None = None,
) -> int:
    """Read a tenant integer setting defensively, including legacy/corrupt rows."""

    raw_value = get_admin_setting(session, tenant_id, section).get(key, fallback)
    try:
        value = int(raw_value)
    except (TypeError, ValueError):
        value = int(fallback)
    if minimum is not None:
        value = max(minimum, value)
    if maximum is not None:
        value = min(maximum, value)
    return value


def get_tenant_max_concurrent_calls(session: Session, tenant_id: int) -> int:
    """Return the runtime tenant capacity saved by an administrator.

    The environment setting remains the default for tenants that have not saved
    a capacity policy. Once saved, the database value takes effect immediately
    across API workers because dispatch reads it for every capacity claim.
    """

    configured = get_admin_setting(session, tenant_id, "capacity").get(
        "max_concurrent_calls",
        settings.max_concurrent_calls,
    )
    try:
        return min(10_000, max(1, int(configured)))
    except (TypeError, ValueError):
        return max(1, int(settings.max_concurrent_calls))
