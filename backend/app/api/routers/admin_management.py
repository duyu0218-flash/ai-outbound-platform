import json
import logging
import re
from datetime import timedelta
from collections import defaultdict
import csv
import io
from uuid import UUID
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import Response
from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ...api.deps import get_pagination, require_role
from ...clock import utc_now
from ...config import get_settings
from ...db import get_session
from ...models import (
    AdminSetting,
    AuditLog,
    Campaign,
    CallAnalysis,
    CallMetric,
    CallSession,
    CallStatus,
    Contact,
    RecordingAsset,
    SmsLog,
    SpeechTurn,
    TaskOutbox,
    TaskState,
    TelephonyLine,
    User,
)
from ...schemas import (
    AdminBillingPayload,
    AdminBillingRow,
    AdminBillingSummary,
    AdminCallReportItem,
    AdminCallReportPayload,
    AdminContactGroupItem,
    AdminContactGroupPayload,
    AdminReportTrendPoint,
    AdminPasswordReset,
    AdminSettingOut,
    AdminSettingUpdate,
    AdminUserCreate,
    AdminUserOut,
    AdminUserUpdate,
    AuditLogOut,
    TelephonyLineCreate,
    TelephonyLineOut,
    TelephonyLineUpdate,
    SmsLogOut,
)
from ...services.auth import hash_password
from ...services.admin_settings import SETTING_DEFAULTS, get_admin_setting, get_tenant_max_concurrent_calls
from ...services.call_service import CAPACITY_STATUSES
from ...services.health import ai_agent_health_check, db_health_check, redis_health_check, tenant_telephony_health_check
from ...services.telephony import get_sms_adapter, list_tenant_telephony_lines, with_retry
from ...services.task_queue import retry_dead_task


router = APIRouter(
    prefix="/api/v1/admin",
    tags=["admin-management"],
    dependencies=[Depends(require_role("admin"))],
)
logger = logging.getLogger(__name__)
settings = get_settings()
REACHED_STATUSES = {"answered", "in_ai", "waiting_human", "handoff_transferring", "in_human", "completed"}
LOSS_STATUSES = {"failed", "no_answer", "busy", "voicemail"}
REPORT_DIMENSIONS = {"campaign", "agent", "line"}


@router.post("/tasks/{task_id}/retry")
def retry_task(
    task_id: UUID,
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    task = retry_dead_task(session, tenant_id=current.tenant_id, task_id=task_id)
    if task is None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="dead task not found or task is not retryable",
        )
    _audit(session, current, "retry", "task_outbox", str(task.id), f"task_type={task.task_type}")
    session.commit()
    return {"result": "queued", "task_id": str(task.id), "state": task.state.value}
REPORT_GRANULARITIES = {"day", "hour"}
CONTACT_GROUP_ORPHAN_KEY = "0"
CONTACT_GROUP_DEFAULT_LABEL = "未分组"
BILLING_RATES = {
    "telephony_unit_price_per_minute": 0.0,
    "ai_unit_price_per_minute": 0.0,
    "sms_unit_price": 0.0,
}


def _call_status_value(call: CallSession) -> str:
    return call.status.value if hasattr(call.status, "value") else str(call.status)


def _csv_safe(value: Any) -> Any:
    """Prevent spreadsheet formula execution when exported data is opened."""

    if value is None or isinstance(value, (int, float)):
        return value
    text = str(value)
    return f"'{text}" if text.startswith(("=", "+", "-", "@", "\t", "\r")) else text


def _count_row_stats(calls: list[CallSession]) -> tuple[int, int, int, int, int, int]:
    calls_count = 0
    reached = 0
    handoff = 0
    completed = 0
    failed = 0
    no_answer = 0
    loss = 0
    for call in calls:
        calls_count += 1
        status = _call_status_value(call)
        if status in REACHED_STATUSES:
            reached += 1
        if call.handoff_reason:
            handoff += 1
        if status == "completed":
            completed += 1
        if status == "failed":
            failed += 1
        if status == "no_answer":
            no_answer += 1
        if status in LOSS_STATUSES:
            loss += 1
    return calls_count, reached, handoff, completed, failed, no_answer, loss


def _bucket(created_at, granularity: str) -> str:
    return created_at.strftime("%Y-%m-%d %H:00") if granularity == "hour" else created_at.strftime("%Y-%m-%d")


def _primary_group_from_tags(tags: str | None) -> str:
    if not tags:
        return CONTACT_GROUP_DEFAULT_LABEL
    parts = [part.strip() for part in re.split(r"[;,，]", tags) if part.strip()]
    return parts[0] if parts else CONTACT_GROUP_DEFAULT_LABEL


def _duration_ms_sum(session: Session, tenant_id: int, since: Any, stage: str) -> dict[str, int]:
    rows = session.exec(
        select(CallMetric.call_session_id, func.sum(func.coalesce(CallMetric.duration_ms, 0)))
        .where(
            CallMetric.tenant_id == tenant_id,
            CallMetric.created_at >= since,
            CallMetric.stage == stage,
        )
        .group_by(CallMetric.call_session_id)
    ).all()
    return {str(call_id): int(total_ms or 0) for call_id, total_ms in rows}


def _sms_counts(session: Session, tenant_id: int, since: Any) -> dict[str, int]:
    rows = session.exec(
        select(SmsLog.call_session_id, func.count(SmsLog.id))
        .where(SmsLog.tenant_id == tenant_id, SmsLog.created_at >= since)
        .group_by(SmsLog.call_session_id)
    ).all()
    return {str(call_session_id): int(count) for call_session_id, count in rows if call_session_id is not None}


def _dimension_key(call: CallSession, dimension: str) -> str:
    if dimension == "campaign":
        return str(call.campaign_id or 0)
    if dimension == "agent":
        return str(call.human_agent_id or 0)
    return str(call.telephony_line_id or 0)


def _to_dimension_id(key: str) -> int:
    try:
        return int(key)
    except ValueError:
        return 0


def _dimension_label(key: str, dimension: str, campaign_map: dict[int, str], user_map: dict[int, str], line_map: dict[int, str]) -> str:
    if key == CONTACT_GROUP_ORPHAN_KEY:
        return {"campaign": "未绑定任务", "agent": "未分配座席", "line": "未绑定线路"}[dimension]
    if dimension == "campaign":
        return campaign_map.get(_to_dimension_id(key), f"Campaign {key}")
    if dimension == "agent":
        return user_map.get(_to_dimension_id(key), f"Agent {key}")
    return line_map.get(_to_dimension_id(key), f"Line {key}")

def _audit(
    session: Session,
    current: User,
    action: str,
    resource_type: str,
    resource_id: str | int | None = None,
    detail: str = "",
) -> None:
    session.add(
        AuditLog(
            tenant_id=current.tenant_id,
            actor_user_id=current.id,
            actor_username=current.username,
            action=action,
            resource_type=resource_type,
            resource_id=str(resource_id) if resource_id is not None else None,
            detail=detail[:4000],
        )
    )


def _admin_count(session: Session, tenant_id: int) -> int:
    return session.exec(
        select(func.count(User.id)).where(
            User.tenant_id == tenant_id,
            User.role == "admin",
            User.enabled.is_(True),
        )
    ).one()


def _get_tenant_user(session: Session, tenant_id: int, user_id: int) -> User:
    user = session.get(User, user_id)
    if not user or user.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="user not found")
    return user


def _validate_gateway_url(value: str) -> None:
    if value and not value.startswith(("http://", "https://")):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="gateway_url must point to an HTTP bridge")


def _validate_line_provider(provider: str) -> None:
    if provider.strip().lower() not in {"http", "mock"}:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="provider must be http or mock")


def _validate_line_configuration(provider: str, gateway_url: str) -> None:
    _validate_line_provider(provider)
    _validate_gateway_url(gateway_url)
    if provider.strip().lower() == "http" and not gateway_url.strip():
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="HTTP bridge requires gateway_url")


@router.get("/users", response_model=list[AdminUserOut])
def list_users(
    page: int = Query(default=1, ge=1),
    size: int = Query(default=100, ge=1, le=200),
    keyword: str | None = Query(default=None, max_length=200),
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    skip, limit = get_pagination(page=page, size=size)
    query = select(User).where(User.tenant_id == current.tenant_id)
    if keyword:
        like = f"%{keyword}%"
        query = query.where(User.username.like(like) | User.full_name.like(like))
    return session.exec(query.order_by(User.created_at.desc()).offset(skip).limit(limit)).all()


@router.post("/users", response_model=AdminUserOut)
def create_user(
    payload: AdminUserCreate,
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    existing = session.exec(select(User).where(User.username == payload.username)).first()
    if existing:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="username already exists")
    user = User(
        tenant_id=current.tenant_id,
        username=payload.username,
        password_hash=hash_password(payload.password),
        full_name=payload.full_name,
        phone=payload.phone,
        role=payload.role,
        is_supervisor=payload.is_supervisor,
        enabled=payload.enabled,
    )
    session.add(user)
    session.flush()
    _audit(session, current, "create", "user", user.id, f"username={user.username}, role={user.role}")
    session.commit()
    session.refresh(user)
    return user


@router.put("/users/{user_id}", response_model=AdminUserOut)
def update_user(
    user_id: int,
    payload: AdminUserUpdate,
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    user = _get_tenant_user(session, current.tenant_id, user_id)
    changes = payload.model_dump(exclude_unset=True)
    if user.id == current.id and (changes.get("enabled") is False or changes.get("role") == "agent"):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="cannot disable or demote current account")
    removes_admin = user.role == "admin" and user.enabled and (
        changes.get("role") == "agent" or changes.get("enabled") is False
    )
    if removes_admin and _admin_count(session, current.tenant_id) <= 1:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="at least one enabled administrator is required")
    if any(key in changes for key in ("enabled", "role")):
        user.token_version = int(user.token_version or 0) + 1
    for key, value in changes.items():
        setattr(user, key, value)
    user.updated_at = utc_now()
    session.add(user)
    _audit(session, current, "update", "user", user.id, ", ".join(sorted(changes.keys())))
    session.commit()
    session.refresh(user)
    return user


@router.post("/users/{user_id}/reset-password")
def reset_user_password(
    user_id: int,
    payload: AdminPasswordReset,
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    user = _get_tenant_user(session, current.tenant_id, user_id)
    user.password_hash = hash_password(payload.password)
    user.failed_login_attempts = 0
    user.locked_until = None
    user.token_version = int(user.token_version or 0) + 1
    user.updated_at = utc_now()
    session.add(user)
    _audit(session, current, "reset_password", "user", user.id, f"username={user.username}")
    session.commit()
    return {"result": "updated"}


@router.post("/users/{user_id}/unlock")
def unlock_user(
    user_id: int,
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    user = _get_tenant_user(session, current.tenant_id, user_id)
    user.failed_login_attempts = 0
    user.locked_until = None
    user.token_version = int(user.token_version or 0) + 1
    user.updated_at = utc_now()
    session.add(user)
    _audit(session, current, "unlock", "user", user.id, f"username={user.username}")
    session.commit()
    return {"result": "unlocked"}


@router.delete("/users/{user_id}")
def disable_user(
    user_id: int,
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    user = _get_tenant_user(session, current.tenant_id, user_id)
    if user.id == current.id:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="cannot disable current account")
    if user.role == "admin" and user.enabled and _admin_count(session, current.tenant_id) <= 1:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="at least one enabled administrator is required")
    user.enabled = False
    user.token_version = int(user.token_version or 0) + 1
    user.updated_at = utc_now()
    session.add(user)
    _audit(session, current, "disable", "user", user.id, f"username={user.username}")
    session.commit()
    return {"result": "disabled"}


@router.get("/lines", response_model=list[TelephonyLineOut])
def list_lines(
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    return session.exec(
        select(TelephonyLine)
        .where(TelephonyLine.tenant_id == current.tenant_id)
        .order_by(TelephonyLine.priority.asc(), TelephonyLine.created_at.asc())
    ).all()


@router.post("/lines", response_model=TelephonyLineOut)
def create_line(
    payload: TelephonyLineCreate,
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    _validate_line_configuration(payload.provider, payload.gateway_url)
    line = TelephonyLine(tenant_id=current.tenant_id, **payload.model_dump())
    session.add(line)
    try:
        session.flush()
    except IntegrityError:
        session.rollback()
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="line name already exists")
    _audit(session, current, "create", "telephony_line", line.id, f"name={line.name}")
    session.commit()
    session.refresh(line)
    return line


@router.put("/lines/{line_id}", response_model=TelephonyLineOut)
def update_line(
    line_id: int,
    payload: TelephonyLineUpdate,
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    line = session.get(TelephonyLine, line_id)
    if not line or line.tenant_id != current.tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="line not found")
    changes = payload.model_dump(exclude_unset=True)
    if "provider" in changes or "gateway_url" in changes:
        _validate_line_configuration(
            str(changes.get("provider", line.provider) or ""),
            str(changes.get("gateway_url", line.gateway_url) or ""),
        )
    for key, value in changes.items():
        setattr(line, key, value)
    line.updated_at = utc_now()
    session.add(line)
    try:
        session.flush()
    except IntegrityError:
        session.rollback()
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="line name already exists")
    _audit(session, current, "update", "telephony_line", line.id, ", ".join(sorted(changes.keys())))
    session.commit()
    session.refresh(line)
    return line


@router.delete("/lines/{line_id}")
def disable_line(
    line_id: int,
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    line = session.get(TelephonyLine, line_id)
    if not line or line.tenant_id != current.tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="line not found")
    line.enabled = False
    line.updated_at = utc_now()
    session.add(line)
    _audit(session, current, "disable", "telephony_line", line.id, f"name={line.name}")
    session.commit()
    return {"result": "disabled"}


@router.get("/sms-logs", response_model=list[SmsLogOut])
def list_sms_logs(
    page: int = Query(default=1, ge=1),
    size: int = Query(default=100, ge=1, le=200),
    state: str | None = Query(default=None, max_length=64),
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    skip, limit = get_pagination(page=page, size=size)
    query = select(SmsLog).where(SmsLog.tenant_id == current.tenant_id)
    if state:
        query = query.where(SmsLog.state == state)
    return session.exec(query.order_by(SmsLog.created_at.desc()).offset(skip).limit(limit)).all()


@router.post("/sms-logs/{sms_log_id}/retry", response_model=SmsLogOut)
async def retry_sms_log(
    sms_log_id: int,
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    sms_log = session.get(SmsLog, sms_log_id)
    if not sms_log or sms_log.tenant_id != current.tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="sms log not found")
    if sms_log.state not in {"failed", "disabled"}:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="only failed or disabled SMS can be retried")
    sms_config = get_admin_setting(session, current.tenant_id, "sms")
    if not sms_config.get("enabled", True):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="SMS service is disabled")
    adapter = get_sms_adapter(sms_config)
    try:
        result = await with_retry(lambda: adapter.send_sms(sms_log.to_phone, sms_log.content))
    except Exception as exc:
        logger.warning("SMS retry failed tenant_id=%s sms_log_id=%s error=%s", current.tenant_id, sms_log.id, exc)
        sms_log.state = "failed"
        session.add(sms_log)
        _audit(session, current, "retry_failed", "sms_log", sms_log.id, "provider request failed")
        session.commit()
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail="SMS provider request failed")
    sms_log.state = str(result.get("state", "sent"))
    sms_log.provider_message_id = str(result.get("message_id") or result.get("provider_message_id") or "") or sms_log.provider_message_id
    sms_log.provider_error = None
    sms_log.sent_at = utc_now()
    sms_log.updated_at = utc_now()
    session.add(sms_log)
    _audit(session, current, "retry", "sms_log", sms_log.id, f"state={sms_log.state}")
    session.commit()
    session.refresh(sms_log)
    return sms_log


def _validated_setting(section: str, data: dict[str, Any]) -> dict[str, Any]:
    if section not in SETTING_DEFAULTS:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="setting section not found")
    unknown = sorted(set(data) - set(SETTING_DEFAULTS[section]))
    if unknown:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"unsupported setting fields: {', '.join(unknown)}")
    merged = {**SETTING_DEFAULTS[section], **data}
    for key, default_value in SETTING_DEFAULTS[section].items():
        value = merged[key]
        if type(value) is not type(default_value):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"invalid setting type: {key}")
        if isinstance(value, str) and len(value) > 4000:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"setting value too large: {key}")
    if len(json.dumps(merged, ensure_ascii=False)) > 20_000:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="setting payload too large")
    if section == "compliance":
        start = merged["allowed_start_hour"]
        end = merged["allowed_end_hour"]
        attempts = merged["max_attempts_per_day"]
        min_interval = merged["min_attempt_interval_sec"]
        recording_retention_days = merged["recording_retention_days"]
        partial_retention_hours = merged["partial_transcript_retention_hours"]
        if not isinstance(start, int) or not isinstance(end, int) or not (0 <= start <= 23 and 0 <= end <= 23):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid allowed calling hours")
        if not isinstance(attempts, int) or not 1 <= attempts <= 20:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid max attempts per day")
        if not isinstance(min_interval, int) or not 0 <= min_interval <= 604_800:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid minimum attempt interval")
        if not isinstance(recording_retention_days, int) or not 1 <= recording_retention_days <= 3_650:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid recording retention days")
        if not isinstance(partial_retention_hours, int) or not 1 <= partial_retention_hours <= 720:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid partial transcript retention")
        try:
            ZoneInfo(str(merged["timezone"]))
        except ZoneInfoNotFoundError:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid compliance timezone")
    if section == "integration":
        timeout = merged["webhook_timeout_sec"]
        if not isinstance(timeout, int) or not 1 <= timeout <= 120:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid webhook timeout")
        retry_times = merged["webhook_retry_times"]
        retry_backoff = merged["webhook_retry_backoff_sec"]
        if not isinstance(retry_times, int) or not 0 <= retry_times <= 10:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid webhook retry times")
        if not isinstance(retry_backoff, int) or not 1 <= retry_backoff <= 60:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid webhook retry backoff")
        secret_ref = str(merged["webhook_secret_ref"])
        if secret_ref and (not secret_ref.replace("_", "").isalnum() or secret_ref.upper() != secret_ref):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid webhook secret reference")
        if merged["callback_enabled"] and not str(merged["webhook_base_url"]).strip():
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="enabled callback requires webhook_base_url")
        if settings.env.lower() in {"prod", "production"} and merged["callback_enabled"] and not secret_ref:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="production callback requires a signing secret reference")
    if section == "ai":
        if merged["llm_provider"] not in {"rule", "openai-compatible"}:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="unsupported llm provider")
        if merged["enabled"] and not str(merged["agent_url"]).strip():
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="enabled AI requires agent_url")
        history_turns = merged["conversation_history_turns"]
        max_reply_chars = merged["max_reply_chars"]
        if not isinstance(history_turns, int) or not 1 <= history_turns <= 50:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid conversation history turns")
        if not isinstance(max_reply_chars, int) or not 20 <= max_reply_chars <= 2000:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid max reply chars")
        if not str(merged["fallback_reply"]).strip():
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="fallback reply is required")
        if merged["voice_ai_pipeline"] not in {"legacy", "pipecat"}:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="unsupported voice AI pipeline")
        canary_percent = merged["pipecat_canary_percent"]
        if not isinstance(canary_percent, int) or not 0 <= canary_percent <= 100:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid Pipecat canary percent")
    if section == "sms":
        if merged["provider"] not in {"mock", "http"}:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="unsupported SMS provider")
        if merged["enabled"] and merged["provider"] == "http" and not str(merged["endpoint"]).strip():
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="HTTP SMS provider requires endpoint")
    if section == "capacity":
        max_calls = merged["max_concurrent_calls"]
        if not isinstance(max_calls, int) or not 1 <= max_calls <= 10_000:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid max concurrent calls")
    for key in ("agent_url", "endpoint", "webhook_base_url"):
        value = merged.get(key)
        if value and not str(value).startswith(("http://", "https://")):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"{key} must use http or https")
    return merged


@router.get("/settings/{section}", response_model=AdminSettingOut)
def get_setting(
    section: str,
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    if section not in SETTING_DEFAULTS:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="setting section not found")
    record = session.exec(
        select(AdminSetting).where(
            AdminSetting.tenant_id == current.tenant_id,
            AdminSetting.section == section,
        )
    ).first()
    data = get_admin_setting(session, current.tenant_id, section) if not record else _validated_setting(section, json.loads(record.data_json))
    return AdminSettingOut(section=section, data=data, updated_at=record.updated_at if record else None)


@router.put("/settings/{section}", response_model=AdminSettingOut)
def update_setting(
    section: str,
    payload: AdminSettingUpdate,
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    data = _validated_setting(section, payload.data)
    record = session.exec(
        select(AdminSetting).where(
            AdminSetting.tenant_id == current.tenant_id,
            AdminSetting.section == section,
        )
    ).first()
    if record is None:
        record = AdminSetting(tenant_id=current.tenant_id, section=section)
    record.data_json = json.dumps(data, ensure_ascii=False, sort_keys=True)
    record.updated_by = current.id
    record.updated_at = utc_now()
    session.add(record)
    _audit(session, current, "update", "setting", section, ", ".join(sorted(payload.data.keys())))
    session.commit()
    session.refresh(record)
    return AdminSettingOut(section=section, data=data, updated_at=record.updated_at)


@router.get("/calls/export")
def export_calls_csv(
    days: int = Query(default=30, ge=1, le=3650),
    call_status: str | None = Query(default=None, alias="status", max_length=32),
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    if call_status is not None and call_status not in {state.value for state in CallStatus}:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid call status")
    since = utc_now() - timedelta(days=days)
    calls_query = select(CallSession).where(CallSession.tenant_id == current.tenant_id, CallSession.created_at >= since)
    if call_status is not None:
        calls_query = calls_query.where(CallSession.status == call_status)
    calls = session.exec(calls_query.order_by(CallSession.created_at.desc())).all()
    call_ids = [call.id for call in calls]

    analysis_by_call: dict[str, CallAnalysis] = {}
    metric_duration: dict[str, int] = defaultdict(int)
    metric_count: dict[str, int] = defaultdict(int)
    speech_counts: dict[str, int] = defaultdict(int)
    final_speech_counts: dict[str, int] = defaultdict(int)
    recordings_by_call: dict[str, list[RecordingAsset]] = defaultdict(list)
    contact_ids = [call.contact_id for call in calls if call.contact_id is not None]
    contacts = {
        item.id: item.phone
        for item in session.exec(
            select(Contact).where(Contact.tenant_id == current.tenant_id, Contact.id.in_(contact_ids))
        ).all()
    } if contact_ids else {}

    if call_ids:
        for analysis in session.exec(
            select(CallAnalysis).where(
                CallAnalysis.tenant_id == current.tenant_id,
                CallAnalysis.call_session_id.in_(call_ids),
            )
        ).all():
            analysis_by_call[str(analysis.call_session_id)] = analysis

        for call_session_id, total, total_duration in session.exec(
            select(
                CallMetric.call_session_id,
                func.count(CallMetric.id),
                func.coalesce(func.sum(func.coalesce(CallMetric.duration_ms, 0)), 0),
            ).where(
                CallMetric.tenant_id == current.tenant_id,
                CallMetric.call_session_id.in_(call_ids)
            ).group_by(CallMetric.call_session_id)
        ).all():
            metric_count[str(call_session_id)] = int(total)
            metric_duration[str(call_session_id)] = int(total_duration or 0)

        for call_session_id, total in session.exec(
            select(SpeechTurn.call_session_id, func.count(SpeechTurn.id)).where(
                SpeechTurn.tenant_id == current.tenant_id,
                SpeechTurn.call_session_id.in_(call_ids)
            ).group_by(SpeechTurn.call_session_id)
        ).all():
            speech_counts[str(call_session_id)] = int(total)

        for call_session_id, total in session.exec(
            select(SpeechTurn.call_session_id, func.count(SpeechTurn.id)).where(
                SpeechTurn.tenant_id == current.tenant_id,
                SpeechTurn.call_session_id.in_(call_ids),
                SpeechTurn.is_final.is_(True),
            ).group_by(SpeechTurn.call_session_id)
        ).all():
            final_speech_counts[str(call_session_id)] = int(total)

        for asset in session.exec(
            select(RecordingAsset).where(
                RecordingAsset.tenant_id == current.tenant_id,
                RecordingAsset.call_session_id.in_(call_ids),
            ).order_by(RecordingAsset.created_at.asc())
        ).all():
            recordings_by_call[str(asset.call_session_id)].append(asset)

    output = io.StringIO()
    writer = csv.writer(output)
    headers = [
        "call_id",
        "phone",
        "status",
        "mode",
        "campaign_id",
        "contact_phone",
        "attempts",
        "max_attempts",
        "started_at",
        "finished_at",
        "created_at",
        "updated_at",
        "duration_ms",
        "metric_count",
        "metric_total_duration_ms",
        "speech_turn_count",
        "final_speech_turn_count",
        "result_code",
        "intent",
        "sentiment",
        "qa_score",
        "qa_flags",
        "analysis_summary",
        "human_agent_id",
        "handoff_reason",
        "last_error",
        "recording_urls",
        "recording_storage_uris",
        "recording_states",
        "recording_retention_untils",
    ]
    writer.writerow(headers)

    for call in calls:
        analysis = analysis_by_call.get(str(call.id))
        duration_ms = None
        if call.started_at and call.finished_at:
            duration_ms = int((call.finished_at - call.started_at).total_seconds() * 1000)
        assets = recordings_by_call.get(str(call.id), [])
        writer.writerow(
            [_csv_safe(value) for value in [
                str(call.id),
                call.phone,
                _call_status_value(call),
                call.mode.value if hasattr(call.mode, "value") else str(call.mode),
                call.campaign_id or "",
                contacts.get(call.contact_id or 0, ""),
                call.attempts,
                call.max_attempts,
                call.started_at.isoformat() if call.started_at else "",
                call.finished_at.isoformat() if call.finished_at else "",
                call.created_at.isoformat() if call.created_at else "",
                call.updated_at.isoformat() if call.updated_at else "",
                duration_ms if duration_ms is not None else "",
                metric_count.get(str(call.id), 0),
                metric_duration.get(str(call.id), 0),
                speech_counts.get(str(call.id), 0),
                final_speech_counts.get(str(call.id), 0),
                analysis.result_code if analysis else "",
                analysis.intent if analysis else "",
                analysis.sentiment if analysis else "",
                analysis.qa_score if analysis else "",
                analysis.qa_flags_json if analysis else "",
                analysis.summary if analysis else "",
                call.human_agent_id or "",
                call.handoff_reason or "",
                call.last_error or "",
                " | ".join(asset.provider_url for asset in assets),
                " | ".join(asset.storage_uri for asset in assets),
                " | ".join(asset.state for asset in assets),
                " | ".join(
                    str(asset.retention_until) for asset in assets if asset.retention_until is not None
                ),
            ]]
        )

    output.seek(0)
    _audit(
        session,
        current,
        "export",
        "call_evidence",
        detail=f"days={days}, status={call_status or 'all'}, rows={len(calls)}",
    )
    session.commit()
    filename = f"call-evidence-{current.tenant_id}-{utc_now().strftime('%Y%m%d-%H%M%S')}.csv"
    return Response(
        content=output.getvalue(),
        media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@router.get("/audit-logs", response_model=list[AuditLogOut])
def list_audit_logs(
    page: int = Query(default=1, ge=1),
    size: int = Query(default=100, ge=1, le=200),
    action: str | None = Query(default=None, max_length=100),
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    skip, limit = get_pagination(page=page, size=size)
    query = select(AuditLog).where(AuditLog.tenant_id == current.tenant_id)
    if action:
        query = query.where(AuditLog.action == action)
    return session.exec(query.order_by(AuditLog.created_at.desc()).offset(skip).limit(limit)).all()


@router.get("/call-reports", response_model=AdminCallReportPayload)
def call_reports(
    dimension: str = Query(default="campaign"),
    granularity: str = Query(default="day"),
    days: int = Query(default=30, ge=1, le=3650),
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    if dimension not in REPORT_DIMENSIONS:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="unsupported dimension")
    if granularity not in REPORT_GRANULARITIES:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="unsupported granularity")

    tenant_id = current.tenant_id
    since = utc_now() - timedelta(days=days)
    calls = session.exec(
        select(CallSession)
        .where(CallSession.tenant_id == tenant_id, CallSession.created_at >= since)
        .order_by(CallSession.created_at.asc())
    ).all()

    if not calls:
        return AdminCallReportPayload(
            dimension=dimension,
            window={"days": days, "granularity": granularity, "start": since.isoformat(), "end": utc_now().isoformat()},
            summary=AdminCallReportItem(key="summary", label="全部", calls=0, reached=0, handoff=0, completed=0, failed=0, no_answer=0, loss=0),
            rows=[],
            trend=[],
        )

    campaign_ids = sorted(set(call.campaign_id for call in calls if call.campaign_id))
    agent_ids = sorted(set(call.human_agent_id for call in calls if call.human_agent_id))
    line_ids = sorted(set(call.telephony_line_id for call in calls if call.telephony_line_id))

    campaign_map = {
        campaign_id: campaign_name
        for campaign_id, campaign_name in session.exec(select(Campaign.id, Campaign.name).where(Campaign.id.in_(campaign_ids))).all()
    }
    user_map = {
        user_id: full_name
        for user_id, full_name in session.exec(select(User.id, User.full_name).where(User.id.in_(agent_ids))).all()
    }
    line_map = {
        line_id: line_name
        for line_id, line_name in session.exec(select(TelephonyLine.id, TelephonyLine.name).where(TelephonyLine.id.in_(line_ids))).all()
    }

    rows_by_key: dict[str, list[CallSession]] = {}
    trend_by_bucket: dict[str, list[CallSession]] = {}

    for item in calls:
        key = _dimension_key(item, dimension)
        rows_by_key.setdefault(key, []).append(item)
        trend_by_bucket.setdefault(_bucket(item.created_at, granularity), []).append(item)

    rows: list[AdminCallReportItem] = []
    for key, items in rows_by_key.items():
        label = _dimension_label(key, dimension, campaign_map, user_map, line_map)
        calls_count, reached, handoff, completed, failed, no_answer, loss = _count_row_stats(items)
        rows.append(
            AdminCallReportItem(
                key=key,
                label=label,
                calls=calls_count,
                reached=reached,
                handoff=handoff,
                completed=completed,
                failed=failed,
                no_answer=no_answer,
                loss=loss,
            )
        )

    rows.sort(key=lambda row: row.calls, reverse=True)

    trend = []
    for bucket, items in sorted(trend_by_bucket.items()):
        calls_count, reached, handoff, completed, failed, no_answer, loss = _count_row_stats(items)
        trend.append(
            AdminReportTrendPoint(
                bucket=bucket,
                calls=calls_count,
                reached=reached,
                completed=completed,
                failed=failed,
                handoff=handoff,
            )
        )

    summary_calls, summary_reached, summary_handoff, summary_completed, summary_failed, summary_no_answer, summary_loss = _count_row_stats(calls)

    return AdminCallReportPayload(
        dimension=dimension,
        window={"days": days, "granularity": granularity, "start": since.isoformat(), "end": utc_now().isoformat()},
        summary=AdminCallReportItem(
            key="summary",
            label="全部",
            calls=summary_calls,
            reached=summary_reached,
            handoff=summary_handoff,
            completed=summary_completed,
            failed=summary_failed,
            no_answer=summary_no_answer,
            loss=summary_loss,
        ),
        rows=rows,
        trend=trend,
    )


@router.get("/contact-groups", response_model=AdminContactGroupPayload)
def contact_groups(
    days: int = Query(default=30, ge=1, le=3650),
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    tenant_id = current.tenant_id
    since = utc_now() - timedelta(days=days)

    contact_rows = session.exec(
        select(Contact.id, Contact.tags, Contact.dnc).where(Contact.tenant_id == tenant_id)
    ).all()
    contact_group_by_contact: dict[int, str] = {
        contact_id: _primary_group_from_tags(tags)
        for contact_id, tags, _ in contact_rows
    }
    dnc_by_contact: dict[int, bool] = {contact_id: dnc for contact_id, _, dnc in contact_rows}

    rows_by_key: dict[str, AdminContactGroupItem] = {}
    for contact_id, group_key in contact_group_by_contact.items():
        item = rows_by_key.setdefault(
            group_key,
            AdminContactGroupItem(
                key=group_key,
                label=group_key,
                contacts=0,
                dnc_contacts=0,
                calls=0,
                reached=0,
                handoff=0,
                completed=0,
                failed=0,
                no_answer=0,
                loss=0,
            ),
        )
        item.contacts += 1
        if dnc_by_contact.get(contact_id):
            item.dnc_contacts += 1

    if CONTACT_GROUP_DEFAULT_LABEL not in rows_by_key:
        rows_by_key[CONTACT_GROUP_DEFAULT_LABEL] = AdminContactGroupItem(
            key=CONTACT_GROUP_DEFAULT_LABEL,
            label=CONTACT_GROUP_DEFAULT_LABEL,
            contacts=0,
            dnc_contacts=0,
            calls=0,
            reached=0,
            handoff=0,
            completed=0,
            failed=0,
            no_answer=0,
            loss=0,
        )

    calls = session.exec(
        select(CallSession)
        .where(CallSession.tenant_id == tenant_id, CallSession.created_at >= since)
        .order_by(CallSession.created_at.asc())
    ).all()

    summary = AdminContactGroupItem(
        key="summary",
        label="全部",
        contacts=0,
        dnc_contacts=0,
        calls=0,
        reached=0,
        handoff=0,
        completed=0,
        failed=0,
        no_answer=0,
        loss=0,
    )

    for call in calls:
        group_key = CONTACT_GROUP_DEFAULT_LABEL
        if call.contact_id is not None and call.contact_id in contact_group_by_contact:
            group_key = contact_group_by_contact.get(call.contact_id, CONTACT_GROUP_DEFAULT_LABEL)
        item = rows_by_key.setdefault(
            group_key,
            AdminContactGroupItem(key=group_key, label=group_key, contacts=0, dnc_contacts=0, calls=0, reached=0, handoff=0, completed=0, failed=0, no_answer=0, loss=0),
        )

        item.calls += 1
        summary.calls += 1
        status = _call_status_value(call)
        if status in REACHED_STATUSES:
            item.reached += 1
            summary.reached += 1
        if call.handoff_reason:
            item.handoff += 1
            summary.handoff += 1
        if status == "completed":
            item.completed += 1
            summary.completed += 1
        if status == "failed":
            item.failed += 1
            summary.failed += 1
        if status == "no_answer":
            item.no_answer += 1
            summary.no_answer += 1
        if status in LOSS_STATUSES:
            item.loss += 1
            summary.loss += 1

    for contact_id, is_dnc in dnc_by_contact.items():
        summary.contacts += 1
        summary.dnc_contacts += 1 if is_dnc else 0

    return AdminContactGroupPayload(
        window={"days": days, "start": since.isoformat(), "end": utc_now().isoformat()},
        summary=summary,
        rows=sorted(rows_by_key.values(), key=lambda item: item.calls, reverse=True),
    )


@router.get("/billing", response_model=AdminBillingPayload)
def billing(
    dimension: str = Query(default="campaign"),
    days: int = Query(default=30, ge=1, le=3650),
    ai_unit_price_per_minute: float = Query(default=BILLING_RATES["ai_unit_price_per_minute"], ge=0),
    telephony_unit_price_per_minute: float = Query(default=BILLING_RATES["telephony_unit_price_per_minute"], ge=0),
    sms_unit_price: float = Query(default=BILLING_RATES["sms_unit_price"], ge=0),
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    if dimension not in REPORT_DIMENSIONS:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="unsupported dimension")

    tenant_id = current.tenant_id
    since = utc_now() - timedelta(days=days)

    calls = session.exec(
        select(CallSession)
        .where(CallSession.tenant_id == tenant_id, CallSession.created_at >= since)
        .order_by(CallSession.created_at.asc())
    ).all()

    campaign_ids = sorted(set(call.campaign_id for call in calls if call.campaign_id))
    user_ids = sorted(set(call.human_agent_id for call in calls if call.human_agent_id))
    line_ids = sorted(set(call.telephony_line_id for call in calls if call.telephony_line_id))

    campaign_map = {
        campaign_id: campaign_name
        for campaign_id, campaign_name in session.exec(select(Campaign.id, Campaign.name).where(Campaign.id.in_(campaign_ids))).all()
    }
    user_map = {
        user_id: full_name
        for user_id, full_name in session.exec(select(User.id, User.full_name).where(User.id.in_(user_ids))).all()
    }
    line_map = {
        line_id: line_name
        for line_id, line_name in session.exec(select(TelephonyLine.id, TelephonyLine.name).where(TelephonyLine.id.in_(line_ids))).all()
    }

    ai_stage_ms_by_call = _duration_ms_sum(session, tenant_id, since, "ai.turn")
    sms_counts_by_call = _sms_counts(session, tenant_id, since)

    rows_by_key: dict[str, dict[str, float | int]] = defaultdict(lambda: {
        "calls": 0,
        "billable_calls": 0,
        "reached": 0,
        "handoff": 0,
        "completed": 0,
        "failed": 0,
        "no_answer": 0,
        "loss": 0,
        "ai_minutes": 0.0,
        "sms_count": 0,
        "estimated_cost": 0.0,
    })
    summary = AdminBillingSummary(
        calls=0,
        billable_calls=0,
        reached=0,
        handoff=0,
        completed=0,
        failed=0,
        no_answer=0,
        loss=0,
        ai_minutes=0.0,
        sms_count=0,
        ai_unit_price_per_minute=ai_unit_price_per_minute,
        telephony_unit_price_per_minute=telephony_unit_price_per_minute,
        sms_unit_price=sms_unit_price,
        estimated_cost=0.0,
    )

    for call in calls:
        key = _dimension_key(call, dimension)
        row = rows_by_key[key]
        row["calls"] = int(row["calls"]) + 1
        status = _call_status_value(call)
        if status in REACHED_STATUSES:
            row["reached"] = int(row["reached"]) + 1
            summary.reached += 1
        if status in {"answered", "in_ai", "waiting_human", "handoff_transferring", "in_human", "completed"}:
            row["billable_calls"] = int(row["billable_calls"]) + 1
            summary.billable_calls += 1
        if status == "completed":
            row["completed"] = int(row["completed"]) + 1
            summary.completed += 1
        if status == "failed":
            row["failed"] = int(row["failed"]) + 1
            summary.failed += 1
        if status == "no_answer":
            row["no_answer"] = int(row["no_answer"]) + 1
            summary.no_answer += 1
        if status in LOSS_STATUSES:
            row["loss"] = int(row["loss"]) + 1
            summary.loss += 1
        if call.handoff_reason:
            row["handoff"] = int(row["handoff"]) + 1
            summary.handoff += 1

        ai_ms = ai_stage_ms_by_call.get(str(call.id), 0)
        ai_minutes = ai_ms / 1000 / 60
        row["ai_minutes"] = float(row["ai_minutes"]) + ai_minutes
        summary.ai_minutes = round(summary.ai_minutes + ai_minutes, 4)

        sms_count = sms_counts_by_call.get(str(call.id), 0)
        row["sms_count"] = int(row["sms_count"]) + sms_count
        summary.sms_count = int(summary.sms_count + sms_count)

        call_cost = ai_minutes * ai_unit_price_per_minute + sms_count * sms_unit_price
        if status in {"answered", "in_ai", "waiting_human", "handoff_transferring", "in_human", "completed"}:
            call_cost += telephony_unit_price_per_minute
        row["estimated_cost"] = float(row["estimated_cost"]) + call_cost
        summary.estimated_cost = round(summary.estimated_cost + call_cost, 4)

        summary.calls += 1

    rows = []
    for key, metrics in rows_by_key.items():
        rows.append(
            AdminBillingRow(
                key=key,
                label=_dimension_label(key, dimension, campaign_map, user_map, line_map),
                calls=int(metrics["calls"]),
                billable_calls=int(metrics["billable_calls"]),
                reached=int(metrics["reached"]),
                handoff=int(metrics["handoff"]),
                completed=int(metrics["completed"]),
                failed=int(metrics["failed"]),
                no_answer=int(metrics["no_answer"]),
                loss=int(metrics["loss"]),
                ai_minutes=round(float(metrics["ai_minutes"]), 4),
                sms_count=int(metrics["sms_count"]),
                estimated_cost=round(float(metrics["estimated_cost"]), 4),
            )
        )

    rows.sort(key=lambda row: row.calls, reverse=True)

    return AdminBillingPayload(
        dimension=dimension,
        window={"days": days, "start": since.isoformat(), "end": utc_now().isoformat()},
        rates={
            "telephony_unit_price_per_minute": telephony_unit_price_per_minute,
            "ai_unit_price_per_minute": ai_unit_price_per_minute,
            "sms_unit_price": sms_unit_price,
        },
        summary=summary,
        rows=rows,
    )


@router.get("/system-overview")
def system_overview(
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    tenant_id = current.tenant_id
    ai_config = get_admin_setting(session, tenant_id, "ai")
    configured_capacity = get_tenant_max_concurrent_calls(session, tenant_id)
    active_calls = session.exec(
        select(func.count(CallSession.id)).where(
            CallSession.tenant_id == tenant_id,
            CallSession.status.in_(CAPACITY_STATUSES),
        )
    ).one()
    enabled_lines = list_tenant_telephony_lines(session, tenant_id)
    line_capacity = (
        sum(max(1, int(line.max_concurrency)) for line in enabled_lines)
        if enabled_lines
        else None
    )
    effective_capacity = min(configured_capacity, line_capacity) if line_capacity is not None else configured_capacity
    if line_capacity is None:
        limiting_source = "tenant_capacity"
    elif line_capacity < configured_capacity:
        limiting_source = "telephony_line"
    elif line_capacity == configured_capacity:
        limiting_source = "tenant_and_line"
    else:
        limiting_source = "tenant_capacity"
    status_rows = session.exec(
        select(CallSession.status, func.count(CallSession.id))
        .where(CallSession.tenant_id == tenant_id)
        .group_by(CallSession.status)
    ).all()
    task_rows = session.exec(
        select(TaskOutbox.state, func.count(TaskOutbox.id))
        .where(TaskOutbox.tenant_id == tenant_id)
        .group_by(TaskOutbox.state)
    ).all()
    stale_task_cutoff = utc_now() - timedelta(minutes=5)
    stale_processing_tasks = session.exec(
        select(func.count(TaskOutbox.id)).where(
            TaskOutbox.tenant_id == tenant_id,
            TaskOutbox.state == TaskState.PROCESSING,
            TaskOutbox.locked_at <= stale_task_cutoff,
        )
    ).one()
    oldest_open_task = session.exec(
        select(func.min(TaskOutbox.created_at)).where(
            TaskOutbox.tenant_id == tenant_id,
            TaskOutbox.state.in_([TaskState.PENDING, TaskState.FAILED, TaskState.PROCESSING]),
        )
    ).one()
    recording_deletion_failures = session.exec(
        select(func.count(RecordingAsset.id)).where(
            RecordingAsset.tenant_id == tenant_id,
            RecordingAsset.state == "deletion_failed",
        )
    ).one()
    ai_latency_ms = session.exec(
        select(func.avg(CallMetric.duration_ms)).where(
            CallMetric.tenant_id == tenant_id,
            CallMetric.stage == "ai.turn",
            CallMetric.success.is_(True),
        )
    ).one()
    return {
        "services": {
            "database": db_health_check(),
            "redis": redis_health_check(),
            "ai_agent": ai_agent_health_check(base_url=str(ai_config.get("agent_url") or "")),
            "telephony": tenant_telephony_health_check(session, tenant_id),
        },
        "resources": {
            "users": session.exec(select(func.count(User.id)).where(User.tenant_id == tenant_id)).one(),
            "enabled_users": session.exec(
                select(func.count(User.id)).where(User.tenant_id == tenant_id, User.enabled.is_(True))
            ).one(),
            "lines": session.exec(select(func.count(TelephonyLine.id)).where(TelephonyLine.tenant_id == tenant_id)).one(),
            "enabled_lines": session.exec(
                select(func.count(TelephonyLine.id)).where(
                    TelephonyLine.tenant_id == tenant_id,
                    TelephonyLine.enabled.is_(True),
                )
            ).one(),
        },
        "call_statuses": {
            str(call_status.value if hasattr(call_status, "value") else call_status): count
            for call_status, count in status_rows
        },
        "capacity": {
            "configured_max_concurrent_calls": configured_capacity,
            "line_max_concurrency": line_capacity,
            "effective_max_concurrent_calls": effective_capacity,
            "active_calls": active_calls,
            "available_slots": max(0, effective_capacity - active_calls),
            "limiting_source": limiting_source,
            "telephony_provider": (settings.telephony_provider or "mock").strip().lower(),
            "environment_default": max(1, int(settings.max_concurrent_calls)),
        },
        "operations": {
            "durable_tasks": {
                str(task_state.value if hasattr(task_state, "value") else task_state): count
                for task_state, count in task_rows
            },
            "average_ai_turn_ms": round(float(ai_latency_ms), 1) if ai_latency_ms is not None else None,
            "stale_processing_tasks": stale_processing_tasks,
            "oldest_open_task_age_sec": max(0, int((utc_now() - oldest_open_task).total_seconds())) if oldest_open_task else 0,
            "recording_deletion_failures": recording_deletion_failures,
        },
        "generated_at": utc_now(),
    }
