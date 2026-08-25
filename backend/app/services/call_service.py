from __future__ import annotations

from datetime import datetime
from typing import Optional
from uuid import UUID

from sqlmodel import Session, select

from ..config import get_settings
from ..models import CallMode, CallSession, CallStatus, Campaign, CampaignContact, Contact, ConsentState, Tenant
from .telephony import get_telephony_adapter, with_retry

settings = get_settings()


class CallPermissionError(ValueError):
    pass


class NotFoundError(ValueError):
    pass


def normalize_phone(phone: str) -> str:
    if not phone:
        return ""
    return "".join(c for c in phone if c.isdigit() or c == "+").lstrip("+")


def _now() -> datetime:
    return datetime.utcnow()


def _get_tenant(session: Session, tenant_id: int) -> Tenant:
    tenant = session.get(Tenant, tenant_id)
    if not tenant:
        raise NotFoundError("tenant not found")
    return tenant


async def ensure_tenant(session: Session, tenant_id: int) -> Tenant:
    return _get_tenant(session, tenant_id)


def can_call_contact_sync(session: Session, tenant_id: int, phone: str) -> tuple[bool, str]:
    normalized = normalize_phone(phone)
    contact = session.exec(
        select(Contact).where(Contact.tenant_id == tenant_id, Contact.phone == normalized)
    ).first()

    if not contact:
        return True, ""
    if contact.dnc:
        return False, "contact_dnc"
    if contact.consent_state == ConsentState.NOT_CONSENTED:
        return False, "not_consented"
    if contact.consent_state == ConsentState.REVOKED:
        return False, "consent_revoked"
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


async def place_call(session: Session, call: CallSession) -> CallSession:
    if call.status not in {CallStatus.QUEUED, CallStatus.CREATED, CallStatus.FAILED}:
        return call

    if call.attempts >= call.max_attempts:
        call.status = CallStatus.FAILED
        call.last_error = "reach max attempts"
        call.updated_at = _now()
        session.add(call)
        session.commit()
        return call

    adapter = get_telephony_adapter()
    callback_url = f"{settings.telephony_webhook_base}/api/v1/webhooks/telephony/status"
    payload = {
        "tenant_id": call.tenant_id,
        "campaign_id": call.campaign_id,
        "contact_id": call.contact_id,
        "mode": str(call.mode),
    }

    try:
        result = await with_retry(
            lambda: adapter.dial(
                call_id=str(call.id),
                phone=call.phone,
                webhook_url=callback_url,
                metadata=payload,
            )
        )
        call.telephony_call_id = result.get("provider_call_id")
        call.ai_session_id = result.get("provider_call_id")
        call.status = CallStatus.DIALING
        call.started_at = _now()
        call.attempts += 1
        call.updated_at = _now()
        call.last_error = None
    except Exception as exc:
        call.status = CallStatus.FAILED
        call.last_error = f"dial failed: {exc}"
        call.updated_at = _now()

    session.add(call)
    session.commit()
    session.refresh(call)
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
    adapter = get_telephony_adapter()
    await with_retry(
        lambda: adapter.transfer_to_human(call_id=str(call.id), reason=reason, target_group=target_group)
    )
    call.status = CallStatus.HANDOFF_TRANSFERRING
    call.handoff_reason = reason
    call.updated_at = _now()
    session.add(call)
    session.commit()
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

    call.status = CallStatus.QUEUED
    call.last_error = None
    session.add(call)
    session.commit()
    return await place_call(session, call)


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
    call_ids: list[str] = []

    for rel in rels:
        contact = session.get(Contact, rel.contact_id)
        if not contact or contact.tenant_id != tenant_id:
            skipped += 1
            continue
        if only_active_contacts and not rel.is_active:
            skipped += 1
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
        except CallPermissionError:
            skipped += 1
        except ValueError:
            skipped += 1
            continue

    return {"total_contacts": total, "created": created, "skipped": skipped, "call_ids": call_ids}
