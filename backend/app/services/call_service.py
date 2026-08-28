from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from typing import Optional
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from sqlalchemy import func, update
from sqlmodel import Session, select

from ..config import get_settings
from ..clock import utc_now
from ..models import AdminSetting, CallMode, CallSession, CallStatus, Campaign, CampaignContact, Contact, ConsentState, Tenant, ScriptTemplate
from .telephony import get_telephony_adapter, with_retry
from .admin_settings import get_admin_setting
from ..db import session_scope

settings = get_settings()


class CallPermissionError(ValueError):
    pass


class NotFoundError(ValueError):
    pass


def normalize_phone(phone: str) -> str:
    if not phone:
        return ""
    return "".join(c for c in phone if c.isdigit())


def _now() -> datetime:
    return utc_now()


def _get_tenant(session: Session, tenant_id: int) -> Tenant:
    tenant = session.get(Tenant, tenant_id)
    if not tenant:
        raise NotFoundError("tenant not found")
    return tenant


async def ensure_tenant(session: Session, tenant_id: int) -> Tenant:
    return _get_tenant(session, tenant_id)


def can_call_contact_sync(session: Session, tenant_id: int, phone: str) -> tuple[bool, str]:
    normalized = normalize_phone(phone)
    compliance = get_admin_setting(session, tenant_id, "compliance")
    has_tenant_policy = session.exec(
        select(AdminSetting.id).where(
            AdminSetting.tenant_id == tenant_id,
            AdminSetting.section == "compliance",
        )
    ).first() is not None
    contact = session.exec(
        select(Contact).where(Contact.tenant_id == tenant_id, Contact.phone == normalized)
    ).first()

    if contact and contact.dnc and compliance.get("dnc_enforced", True):
        return False, "contact_dnc"
    if contact and contact.consent_state == ConsentState.NOT_CONSENTED:
        return False, "not_consented"
    if contact and contact.consent_state == ConsentState.REVOKED:
        return False, "consent_revoked"

    # Preserve existing installations until an administrator explicitly saves
    # a tenant compliance policy, then enforce its time window and daily cap.
    if has_tenant_policy:
        try:
            tenant_zone = ZoneInfo(str(compliance.get("timezone") or "Asia/Shanghai"))
        except ZoneInfoNotFoundError:
            return False, "invalid_compliance_timezone"
        local_now = datetime.now(timezone.utc).astimezone(tenant_zone)
        start_hour = int(compliance.get("allowed_start_hour", 9))
        end_hour = int(compliance.get("allowed_end_hour", 20))
        if not start_hour <= local_now.hour < end_hour:
            return False, "outside_calling_hours"
        max_attempts = int(compliance.get("max_attempts_per_day", 3))
        local_day_start = local_now.replace(hour=0, minute=0, second=0, microsecond=0)
        utc_day_start = local_day_start.astimezone(timezone.utc).replace(tzinfo=None)
        attempts_today = session.exec(
            select(func.count(CallSession.id)).where(
                CallSession.tenant_id == tenant_id,
                CallSession.phone == normalized,
                CallSession.created_at >= utc_day_start,
            )
        ).one()
        if attempts_today >= max_attempts:
            return False, "daily_attempt_limit"
    return True, ""


async def can_call_contact(session: Session, tenant_id: int, phone: str) -> tuple[bool, str]:
    return can_call_contact_sync(session, tenant_id, phone)


TERMINAL_STATUSES = {
    CallStatus.COMPLETED,
    CallStatus.FAILED,
    CallStatus.NO_ANSWER,
    CallStatus.BUSY,
    CallStatus.VOICEMAIL,
}
DISPATCHABLE_STATUSES = {CallStatus.QUEUED, CallStatus.CREATED, CallStatus.FAILED}
HANDOVERABLE_STATUSES = {CallStatus.DIALING, CallStatus.ANSWERED, CallStatus.IN_AI, CallStatus.WAITING_HUMAN}


CALL_PRECHECK_ERROR_MAP = {
    "contact_dnc": "CONTACT_DNC",
    "not_consented": "CONTACT_NOT_CONSENTED",
    "consent_revoked": "CONTACT_CONSENT_REVOKED",
    "outside_calling_hours": "OUTSIDE_CALLING_HOURS",
    "daily_attempt_limit": "DAILY_ATTEMPT_LIMIT",
    "invalid_compliance_timezone": "INVALID_COMPLIANCE_TIMEZONE",
}


def _map_call_precheck_code(reason: str) -> str:
    normalized = str(reason).strip().lower()
    return CALL_PRECHECK_ERROR_MAP.get(normalized, f"CALL_PRECHECK_{normalized.upper()[:40] or 'UNKNOWN'}")


def _map_dispatch_error_code(message: str | None) -> str:
    msg = str(message or "").lower()
    if "reach max attempts" in msg:
        return "REACH_MAX_ATTEMPTS"
    if "dial failed" in msg:
        return "DIAL_FAILED"
    if "provider" in msg:
        return "PROVIDER_ERROR"
    if not msg:
        return "UNKNOWN_DISPATCH_ERROR"
    return f"DISPATCH_{msg[:40].upper().replace(' ', '_')}"


def _claim_dispatch_slot(session: Session, call: CallSession) -> bool:
    if call.attempts >= call.max_attempts:
        return False

    now = _now()
    stmt = (
        update(CallSession)
        .where(
            CallSession.id == call.id,
            CallSession.status.in_(DISPATCHABLE_STATUSES),
            CallSession.attempts < CallSession.max_attempts,
        )
        .values(
            status=CallStatus.DIALING,
            attempts=CallSession.attempts + 1,
            started_at=now,
            updated_at=now,
            last_error=None,
        )
    )
    result = session.exec(stmt)
    updated = result.rowcount
    if not updated:
        session.rollback()
        return False
    session.commit()
    session.refresh(call)
    return True


def _set_call_if_status_in(
    session: Session,
    *,
    call_id: str,
    allowed_statuses: set[CallStatus],
    expected_attempt: int | None = None,
    **values: object,
) -> bool:
    if not allowed_statuses:
        return False
    conditions = [CallSession.id == UUID(call_id), CallSession.status.in_(allowed_statuses)]
    if expected_attempt is not None:
        conditions.append(CallSession.attempts == expected_attempt)
    stmt = update(CallSession).where(*conditions).values(**values)
    result = session.exec(stmt)
    if result.rowcount:
        session.commit()
        return True
    session.rollback()
    return False


def _set_call_metadata_for_attempt(
    session: Session,
    *,
    call_id: str,
    expected_attempt: int,
    **values: object,
) -> bool:
    if not values:
        return False
    stmt = (
        update(CallSession)
        .where(CallSession.id == UUID(call_id), CallSession.attempts == expected_attempt)
        .values(**values)
    )
    result = session.exec(stmt)
    if result.rowcount:
        session.commit()
        return True
    session.rollback()
    return False


def _set_call_if_status_in_uuid(
    session: Session,
    *,
    call_id: UUID,
    allowed_statuses: set[CallStatus],
    **values: object,
) -> bool:
    if not allowed_statuses:
        return False
    stmt = (
        update(CallSession)
        .where(CallSession.id == call_id, CallSession.status.in_(allowed_statuses))
        .values(**values)
    )
    result = session.exec(stmt)
    if result.rowcount:
        session.commit()
        return True
    session.rollback()
    return False


def can_retry_call(call: CallSession) -> tuple[bool, str]:
    if call.status not in TERMINAL_STATUSES:
        return False, "call is not in terminal state"
    if call.attempts >= call.max_attempts:
        return False, "reach max attempts"
    return True, ""


def _require_campaign(session: Session, tenant_id: int, campaign_id: Optional[int]) -> None:
    if campaign_id is None:
        return
    campaign = session.get(Campaign, campaign_id)
    if not campaign or campaign.tenant_id != tenant_id:
        raise NotFoundError("campaign not found")


def create_call(
    session: Session,
    *,
    tenant_id: int,
    phone: str,
    mode: str,
    campaign_id: Optional[int],
    contact_id: Optional[int],
    max_attempts: int = 1,
) -> CallSession:
    _get_tenant(session, tenant_id)
    _require_campaign(session, tenant_id, campaign_id)

    contact = None
    normalized = normalize_phone(phone)
    if contact_id is not None:
        contact = session.get(Contact, contact_id)
        if not contact or contact.tenant_id != tenant_id:
            raise NotFoundError("contact not found")
        if not normalized:
            normalized = normalize_phone(contact.phone)
    if not normalized:
        raise ValueError("phone is required when contact_id is not provided")
    if not 6 <= len(normalized) <= 15:
        raise ValueError("phone must contain 6 to 15 digits")

    can_call, reason = can_call_contact_sync(session, tenant_id, normalized)
    if not can_call:
        raise CallPermissionError(reason)

    call_mode = CallMode(mode)
    call = CallSession(
        tenant_id=tenant_id,
        campaign_id=campaign_id,
        contact_id=contact_id,
        phone=normalized,
        mode=call_mode,
        status=CallStatus.QUEUED,
        max_attempts=max_attempts,
        attempts=0,
        last_error=None,
    )
    session.add(call)
    session.commit()
    session.refresh(call)
    return call


def list_calls(
    session: Session,
    tenant_id: int,
    *,
    status: CallStatus | str | None = None,
    campaign_id: int | None = None,
    skip: int = 0,
    limit: int = 50,
) -> list[CallSession]:
    query = select(CallSession).where(CallSession.tenant_id == tenant_id)
    if campaign_id is not None:
        query = query.where(CallSession.campaign_id == campaign_id)
    if status is not None:
        query = query.where(CallSession.status == CallStatus(str(status).lower()))
    return session.exec(query.order_by(CallSession.created_at.desc()).offset(skip).limit(limit)).all()


def get_call(session: Session, tenant_id: int, call_id: UUID) -> CallSession:
    call = session.get(CallSession, call_id)
    if not call or call.tenant_id != tenant_id:
        raise NotFoundError("call not found")
    return call


async def _place_call_with_result(session: Session, call: CallSession) -> tuple[CallSession, bool]:
    if call.status not in DISPATCHABLE_STATUSES:
        return call, False

    if call.attempts >= call.max_attempts:
        _set_call_if_status_in(
            session,
            call_id=str(call.id),
            allowed_statuses=DISPATCHABLE_STATUSES,
            status=CallStatus.FAILED,
            last_error="reach max attempts",
            updated_at=_now(),
        )
        session.refresh(call)
        return call, False

    if not _claim_dispatch_slot(session, call):
        session.refresh(call)
        return call, False

    claimed_attempt = call.attempts

    adapter = get_telephony_adapter(session=session, tenant_id=call.tenant_id)
    callback_url = f"{settings.telephony_webhook_base}/api/v1/webhooks/telephony/status"
    payload = {
        "tenant_id": call.tenant_id,
        "campaign_id": call.campaign_id,
        "contact_id": call.contact_id,
        "mode": call.mode.value,
        # Providers that do not send their own event id still need callbacks
        # from a retry attempt to be distinguishable from the first attempt.
        "attempt": claimed_attempt,
    }
    if call.campaign_id is not None:
        campaign = session.get(Campaign, call.campaign_id)
        if campaign and campaign.tenant_id == call.tenant_id:
            payload["recording_enabled"] = campaign.recording_enabled
            payload["hangup_sms_enabled"] = campaign.hangup_sms_enabled

    try:
        result = await with_retry(
            lambda: adapter.dial(
                call_id=str(call.id),
                phone=call.phone,
                webhook_url=callback_url,
                metadata=payload,
            )
        )
        session.refresh(call)
        if not _set_call_if_status_in(
            session,
            call_id=str(call.id),
            allowed_statuses={CallStatus.DIALING},
            expected_attempt=claimed_attempt,
            telephony_call_id=result.get("provider_call_id"),
            ai_session_id=result.get("provider_call_id"),
            status=CallStatus.DIALING,
            updated_at=_now(),
            last_error=None,
        ):
            _set_call_metadata_for_attempt(
                session,
                call_id=str(call.id),
                expected_attempt=claimed_attempt,
                telephony_call_id=result.get("provider_call_id"),
                ai_session_id=result.get("provider_call_id"),
                updated_at=_now(),
            )
        call = session.get(CallSession, call.id)
        if call is None:
            raise NotFoundError("call not found")
    except Exception as exc:
        _set_call_if_status_in(
            session,
            call_id=str(call.id),
            allowed_statuses={CallStatus.DIALING},
            expected_attempt=claimed_attempt,
            status=CallStatus.FAILED,
            last_error=f"dial failed: {exc}",
            updated_at=_now(),
        )
        session.refresh(call)

    return call, True


async def place_call(session: Session, call: CallSession) -> CallSession:
    call, _ = await _place_call_with_result(session, call)
    return call


async def handover_to_human(
    session: Session,
    *,
    tenant_id: int,
    call_id: UUID,
    reason: str,
    target_group: str | None = None,
) -> CallSession:
    call = get_call(session, tenant_id, call_id)
    if not _set_call_if_status_in_uuid(
        session,
        call_id=call.id,
        allowed_statuses=HANDOVERABLE_STATUSES,
        status=CallStatus.HANDOFF_TRANSFERRING,
        handoff_reason=reason,
        updated_at=_now(),
    ):
        raise CallPermissionError("call status not handover-able")

    adapter = get_telephony_adapter(session=session, tenant_id=tenant_id)
    try:
        await with_retry(
            lambda: adapter.transfer_to_human(call_id=str(call.id), reason=reason, target_group=target_group)
        )
    except Exception as exc:
        _set_call_if_status_in_uuid(
            session,
            call_id=call.id,
            allowed_statuses={CallStatus.HANDOFF_TRANSFERRING},
            status=CallStatus.FAILED,
            last_error=f"handover failed: {exc}",
            updated_at=_now(),
        )
        raise

    _set_call_if_status_in_uuid(
        session,
        call_id=call.id,
        allowed_statuses={CallStatus.HANDOFF_TRANSFERRING},
        status=CallStatus.WAITING_HUMAN,
        handoff_reason=reason,
        updated_at=_now(),
    )
    session.refresh(call)
    return call


async def retry_call(
    session: Session,
    *,
    tenant_id: int,
    call_id: UUID,
) -> CallSession:
    call = get_call(session, tenant_id, call_id)
    can_retry, reason = can_retry_call(call)
    if not can_retry:
        raise CallPermissionError(reason)

    if not _set_call_if_status_in_uuid(
        session,
        call_id=call.id,
        allowed_statuses=TERMINAL_STATUSES,
        status=CallStatus.QUEUED,
        last_error=None,
        updated_at=_now(),
    ):
        raise CallPermissionError("call status changed, retry denied")

    call = session.get(CallSession, call.id)
    if call is None:
        raise NotFoundError("call not found")

    return await place_call(session, call)


async def dispatch_call_ids(
    call_ids: list[str],
    *,
    max_concurrency: int | None = None,
) -> dict[str, object]:
    if not call_ids:
        return {
            "total": 0,
            "target": 0,
            "succeeded": 0,
            "failed": 0,
            "skipped": 0,
            "status": "completed",
            "errors": [],
            "error_codes": [],
        }

    deduped: list[str] = []
    seen: set[str] = set()
    input_errors: list[dict[str, object]] = []
    for raw_id in call_ids:
        normalized_id = str(raw_id)
        try:
            UUID(normalized_id)
        except (TypeError, ValueError):
            input_errors.append(
                {
                    "code": "INVALID_CALL_ID",
                    "message": "call id is not a valid UUID",
                    "call_id": normalized_id,
                }
            )
            continue
        if normalized_id in seen:
            input_errors.append(
                {
                    "code": "DUPLICATE_CALL_ID",
                    "message": "duplicate call id ignored in batch",
                    "call_id": normalized_id,
                }
            )
            continue
        seen.add(normalized_id)
        deduped.append(normalized_id)

    if not deduped:
        return {
            "total": len(call_ids),
            "target": 0,
            "succeeded": 0,
            "failed": 0,
            "skipped": len(call_ids),
            "status": "completed",
            "errors": input_errors,
            "error_codes": sorted({str(item["code"]) for item in input_errors}),
        }

    skipped_count = len(input_errors)
    concurrency = max(1, int(max_concurrency or settings.max_concurrent_calls))

    async def _dispatch_one(call_id: str) -> tuple[str, str | None, str | None]:
        with session_scope() as session:
            call = session.get(CallSession, UUID(call_id))
            if not call:
                return call_id, "CALL_NOT_FOUND", "call session not found"
            if call.campaign_id is not None:
                campaign = session.get(Campaign, call.campaign_id)
                if not campaign or campaign.status != "running":
                    return call_id, "CAMPAIGN_NOT_RUNNING", "campaign is not running"
            try:
                call, attempted = await _place_call_with_result(session, call)
                if call.status == CallStatus.FAILED:
                    return call_id, _map_dispatch_error_code(call.last_error), call.last_error
                if not attempted:
                    return call_id, "CALL_NOT_DISPATCHABLE", f"call status is {call.status.value}"
                return call_id, None, None
            except Exception:
                return call_id, "DISPATCH_EXCEPTION", "failed to dispatch call"

    sem = asyncio.Semaphore(concurrency)

    async def _worker(call_id: str) -> tuple[str, str | None, str | None]:
        async with sem:
            return await _dispatch_one(call_id)

    results = await asyncio.gather(*(_worker(call_id) for call_id in deduped), return_exceptions=True)
    succeeded = 0
    errors: list[dict[str, object]] = list(input_errors)
    for item in results:
        if not isinstance(item, tuple) or len(item) != 3:
            errors.append(
                {
                    "code": "UNKNOWN_DISPATCH_ERROR",
                    "message": "unexpected dispatch worker result",
                    "call_id": "",
                }
            )
            continue

        call_id, error_code, message = item
        if error_code is None:
            succeeded += 1
            continue
        errors.append({"code": error_code, "message": message, "call_id": str(call_id)})

    failed = len(errors) - skipped_count
    return {
        "total": len(call_ids),
        "target": len(deduped),
        "succeeded": succeeded,
        "failed": failed,
        "skipped": skipped_count,
        "status": "completed",
        "errors": errors,
        "error_codes": sorted({item["code"] for item in errors}),
    }


def start_campaign(
    session: Session,
    *,
    tenant_id: int,
    campaign_id: int,
    only_active_contacts: bool = True,
) -> dict[str, object]:
    campaign = session.get(Campaign, campaign_id)
    if not campaign or campaign.tenant_id != tenant_id:
        raise NotFoundError("campaign not found")

    rels = session.exec(
        select(CampaignContact)
        .where(CampaignContact.campaign_id == campaign_id)
        .order_by(CampaignContact.contact_order.asc())
    ).all()

    total = len(rels)
    created = 0
    skipped = 0
    skip_reasons: list[dict[str, object]] = []
    skipped_reason_counter: dict[str, int] = {}
    call_ids: list[str] = []

    for rel in rels:
        contact = session.get(Contact, rel.contact_id)
        if not contact or contact.tenant_id != tenant_id:
            skipped += 1
            reason = {"code": "CONTACT_NOT_FOUND", "message": "contact not found", "contact_id": rel.contact_id}
            skip_reasons.append(reason)
            skipped_reason_counter["CONTACT_NOT_FOUND"] = skipped_reason_counter.get("CONTACT_NOT_FOUND", 0) + 1
            continue
        if only_active_contacts and not rel.is_active:
            skipped += 1
            reason = {
                "code": "CONTACT_INACTIVE",
                "message": "contact inactive in campaign",
                "contact_id": contact.id,
            }
            skip_reasons.append(reason)
            skipped_reason_counter["CONTACT_INACTIVE"] = skipped_reason_counter.get("CONTACT_INACTIVE", 0) + 1
            continue

        try:
            call = create_call(
                session=session,
                tenant_id=tenant_id,
                phone=contact.phone,
                mode=campaign.mode,
                campaign_id=campaign.id,
                contact_id=contact.id,
                max_attempts=campaign.retry_limit,
            )
            session.refresh(call)
            call_ids.append(str(call.id))
            created += 1
        except CallPermissionError as error:
            skipped += 1
            code = _map_call_precheck_code(str(error))
            reason = {
                "code": code,
                "message": str(error),
                "phone": contact.phone,
                "contact_id": contact.id,
            }
            skip_reasons.append(reason)
            skipped_reason_counter[code] = skipped_reason_counter.get(code, 0) + 1
        except ValueError:
            skipped += 1
            reason = {
                "code": "INVALID_PHONE",
                "message": "invalid phone or missing contact",
                "phone": contact.phone if contact else None,
                "contact_id": contact.id if contact else rel.contact_id,
            }
            skip_reasons.append(reason)
            skipped_reason_counter["INVALID_PHONE"] = skipped_reason_counter.get("INVALID_PHONE", 0) + 1
            continue

    return {
        "total_contacts": total,
        "created": created,
        "skipped": skipped,
        "call_ids": call_ids,
        "skip_reasons": skip_reasons,
        "skipped_reason_codes": sorted(skipped_reason_counter.keys()),
    }


def resolve_campaign_script(session: Session, tenant_id: int, campaign_id: int | None) -> str:
    if campaign_id is None:
        return ""
    campaign = session.get(Campaign, campaign_id)
    if not campaign or campaign.tenant_id != tenant_id:
        return ""
    if campaign.script:
        return campaign.script
    if campaign.script_template_id is None:
        return ""
    template = session.get(ScriptTemplate, campaign.script_template_id)
    if not template or not template.is_active or template.tenant_id != tenant_id:
        return ""
    return template.content
