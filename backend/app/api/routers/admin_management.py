import json
import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ...api.deps import get_pagination, require_role
from ...clock import utc_now
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
from ...services.admin_settings import SETTING_DEFAULTS, get_admin_setting
from ...services.health import ai_agent_health_check, db_health_check, redis_health_check, telephony_http_health_check
from ...services.telephony import get_sms_adapter, with_retry


router = APIRouter(
    prefix="/api/v1/admin",
    tags=["admin-management"],
    dependencies=[Depends(require_role("admin"))],
)
logger = logging.getLogger(__name__)

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
    if value and not value.startswith(("http://", "https://", "sip:", "sips:", "ws://", "wss://")):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="gateway_url uses an unsupported protocol")


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
        .order_by(TelephonyLine.created_at.desc())
    ).all()


@router.post("/lines", response_model=TelephonyLineOut)
def create_line(
    payload: TelephonyLineCreate,
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    _validate_gateway_url(payload.gateway_url)
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
    if "gateway_url" in changes:
        _validate_gateway_url(changes["gateway_url"] or "")
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
    sms_log.sent_at = utc_now()
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
        if not isinstance(start, int) or not isinstance(end, int) or not (0 <= start <= 23 and 0 <= end <= 23 and start < end):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid allowed calling hours")
        if not isinstance(attempts, int) or not 1 <= attempts <= 20:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid max attempts per day")
    if section == "integration":
        timeout = merged["webhook_timeout_sec"]
        if not isinstance(timeout, int) or not 1 <= timeout <= 120:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid webhook timeout")
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
            "telephony": telephony_http_health_check(),
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
        "generated_at": utc_now(),
    }
