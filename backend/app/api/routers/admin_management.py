import json
import logging
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ...api.deps import get_pagination, require_role
from ...clock import utc_now
from ...config import get_settings
from ...db import get_session
from ...models import AdminSetting, AuditLog, CallSession, SmsLog, TelephonyLine, User
from ...schemas import (
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


router = APIRouter(
    prefix="/api/v1/admin",
    tags=["admin-management"],
    dependencies=[Depends(require_role("admin"))],
)
logger = logging.getLogger(__name__)
settings = get_settings()

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
    user.updated_at = utc_now()
    session.add(user)
    _audit(session, current, "reset_password", "user", user.id, f"username={user.username}")
    session.commit()
    return {"result": "updated"}


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
        if not isinstance(start, int) or not isinstance(end, int) or not (0 <= start <= 23 and 0 <= end <= 23):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid allowed calling hours")
        if not isinstance(attempts, int) or not 1 <= attempts <= 20:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid max attempts per day")
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
        "generated_at": utc_now(),
    }
