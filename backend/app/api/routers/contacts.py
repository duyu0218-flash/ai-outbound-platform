from __future__ import annotations

import csv
import io
import json

from datetime import datetime
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile, status
from fastapi.responses import Response
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ...api.deps import check_api_key, get_pagination, get_tenant_id_for_request, require_roles_if_authenticated
from ...clock import utc_now
from ...models import AuditLog, CallSession, Campaign, CampaignContact, Contact, ConsentState, User
from ...schemas import (
    ContactBatchDncPatch,
    ContactBatchDncResult,
    ContactCreate,
    ContactImportItem,
    ContactImportResult,
    ContactPatch,
    ContactOut,
)
from ...services.call_service import normalize_phone
from ...db import get_session

router = APIRouter(
    prefix="/api/v1/contacts",
    tags=["contacts"],
    dependencies=[Depends(check_api_key), Depends(require_roles_if_authenticated("admin"))],
)


def _validate_timezone(value: str | None) -> None:
    if not value:
        return
    try:
        ZoneInfo(value)
    except ZoneInfoNotFoundError:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid contact timezone")


def _coerce_bool(value: str | None) -> bool:
    if value is None:
        return False
    value = value.strip().lower()
    return value in {"1", "true", "t", "yes", "y", "on"}


def _coerce_string(value: str | None) -> str:
    return (value or "").strip()


def _parse_contact_row(row: dict[str, str], row_index: int) -> tuple[ContactImportItem | None, str | None]:
    phone = _coerce_string(row.get("phone"))
    if not phone:
        return None, f"row {row_index}: missing phone"

    normalized_phone = normalize_phone(phone)
    if not normalized_phone or not 6 <= len(normalized_phone) <= 15:
        return None, f"row {row_index}: invalid phone"

    if consent := _coerce_string(row.get("consent_state")):
        try:
            consent_state = ConsentState(consent.strip().lower())
        except ValueError:
            return None, f"row {row_index}: invalid consent_state={consent}"
    else:
        consent_state = ConsentState.UNKNOWN

    timezone = _coerce_string(row.get("timezone")) or "Asia/Shanghai"
    try:
        _validate_timezone(timezone)
    except HTTPException as exc:
        return None, f"row {row_index}: {exc.detail}"

    return ContactImportItem(
        phone=normalized_phone,
        name=_coerce_string(row.get("name")),
        tags=_coerce_string(row.get("tags")),
        consent_state=consent_state,
        dnc=_coerce_bool(row.get("dnc")),
        dnc_reason=_coerce_string(row.get("dnc_reason")),
        timezone=timezone,
    ), None


def _audit(
    session: Session,
    tenant_id: int,
    actor: User | None,
    action: str,
    resource_type: str,
    resource_id: str | int | None = None,
    detail: str = "",
) -> None:
    session.add(
        AuditLog(
            tenant_id=tenant_id,
            actor_user_id=actor.id if actor else None,
            actor_username=actor.username if actor else "system",
            action=action,
            resource_type=resource_type,
            resource_id=str(resource_id) if resource_id is not None else None,
            detail=detail[:4000],
        )
    )


def _iter_csv_rows(file: UploadFile) -> list[tuple[int, dict[str, str]]]:
    try:
        text = file.file.read().decode("utf-8-sig")
    except UnicodeDecodeError:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="csv must be UTF-8 encoded")

    try:
        rows = list(csv.reader(io.StringIO(text)))
    except csv.Error as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"invalid CSV format: {exc}")

    if not rows:
        return []

    header = [_coerce_string(cell).lower() for cell in rows[0]]
    has_header = "phone" in header
    if has_header:
        items: list[tuple[int, dict[str, str]]] = []
        normalized_header = [cell.strip().lower() for cell in rows[0]]
        if normalized_header and not normalized_header[0]:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid CSV header")
        for row_index, row in enumerate(rows[1:], start=2):
            if not any(_coerce_string(cell) for cell in row):
                continue
            payload = {name: _coerce_string(row[i]) for i, name in enumerate(normalized_header) if i < len(row) and name}
            payload.setdefault("phone", "")
            payload.setdefault("name", "")
            payload.setdefault("tags", "")
            payload.setdefault("consent_state", "")
            payload.setdefault("dnc", "")
            payload.setdefault("dnc_reason", "")
            payload.setdefault("timezone", "")
            items.append((row_index, payload))
        return items

    # header-less csv format: phone,name,tags,consent_state,dnc,dnc_reason,timezone
    items: list[tuple[int, dict[str, str]]] = []
    for row_index, row in enumerate(rows, start=1):
        if not any(_coerce_string(cell) for cell in row):
            continue
        if len(row) < 1:
            continue
        items.append(
            (row_index, {
                "phone": _coerce_string(row[0]) if len(row) > 0 else "",
                "name": _coerce_string(row[1]) if len(row) > 1 else "",
                "tags": _coerce_string(row[2]) if len(row) > 2 else "",
                "consent_state": _coerce_string(row[3]) if len(row) > 3 else "",
                "dnc": _coerce_string(row[4]) if len(row) > 4 else "",
                "dnc_reason": _coerce_string(row[5]) if len(row) > 5 else "",
                "timezone": _coerce_string(row[6]) if len(row) > 6 else "Asia/Shanghai",
            }),
        )
    return items


@router.post("/import", response_model=ContactImportResult)
def import_contacts(
    file: UploadFile = File(..., media_type="text/csv", description="CSV file with fields phone,name,tags,consent_state,dnc,dnc_reason,timezone"),
    upsert: bool = Query(default=True),
    current: User | None = Depends(require_roles_if_authenticated("admin")),
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
):
    items = _iter_csv_rows(file)
    total_rows = len(items)
    if total_rows == 0:
        return ContactImportResult(total=0, created=0, updated=0, skipped=0, failed=0, errors=[])

    created = 0
    updated = 0
    skipped = 0
    failed = 0
    errors: list[str] = []
    seen_phones: set[str] = set()

    for index, row in items:
        parsed, parse_error = _parse_contact_row(row, index)
        if parse_error:
            failed += 1
            errors.append(parse_error)
            continue

        if not parsed:
            failed += 1
            continue

        phone = parsed.phone
        if phone in seen_phones:
            skipped += 1
            errors.append(f"row {index}: duplicate phone in uploaded file")
            continue
        seen_phones.add(phone)

        existing_contact = session.exec(
            select(Contact).where(Contact.tenant_id == tenant_id, Contact.phone == phone)
        ).first()

        if existing_contact is None:
            contact = Contact(
                tenant_id=tenant_id,
                phone=phone,
                name=parsed.name or None,
                tags=parsed.tags,
                consent_state=parsed.consent_state,
                dnc=parsed.dnc,
                dnc_reason=parsed.dnc_reason or None,
                timezone=parsed.timezone,
                consented_at=utc_now() if parsed.consent_state == ConsentState.CONSENTED else None,
            )
            session.add(contact)
            created += 1
            continue

        if not upsert:
            skipped += 1
            continue

        existing_contact.name = parsed.name or existing_contact.name
        existing_contact.tags = parsed.tags
        existing_contact.consent_state = parsed.consent_state
        existing_contact.dnc = parsed.dnc
        existing_contact.dnc_reason = parsed.dnc_reason or None
        existing_contact.timezone = parsed.timezone
        existing_contact.consented_at = utc_now() if parsed.consent_state == ConsentState.CONSENTED else None
        session.add(existing_contact)
        updated += 1

    _audit(
        session,
        tenant_id=tenant_id,
        actor=current,
        action="import",
        resource_type="contact",
        detail=(
            f"imported_file_rows={total_rows}, created={created}, updated={updated}, "
            f"skipped={skipped}, failed={failed}, upsert={upsert}"
        ),
    )
    try:
        session.commit()
    except IntegrityError:
        session.rollback()
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="duplicate phone detected in current tenant")

    return ContactImportResult(total=total_rows, created=created, updated=updated, skipped=skipped, failed=failed, errors=errors)


@router.get("/export")
def export_contacts(
    tenant_id: int = Depends(get_tenant_id_for_request),
    current: User | None = Depends(require_roles_if_authenticated("admin")),
    dnc: bool | None = Query(default=None),
    consent: str | None = Query(default=None),
    keyword: str | None = Query(default=None),
    session: Session = Depends(get_session),
):
    query = select(Contact).where(Contact.tenant_id == tenant_id)
    if keyword:
        like = f"%{keyword}%"
        query = query.where(Contact.phone.like(like) | Contact.name.like(like))
    if dnc is not None:
        query = query.where(Contact.dnc == dnc)
    if consent:
        query = query.where(Contact.consent_state == consent)

    contacts = session.exec(query.order_by(Contact.created_at.desc())).all()

    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(["phone", "name", "tags", "consent_state", "dnc", "dnc_reason", "timezone", "created_at", "updated_at"])
    for contact in contacts:
        writer.writerow(
            [
                contact.phone,
                contact.name or "",
                contact.tags or "",
                str(contact.consent_state),
                "true" if contact.dnc else "false",
                contact.dnc_reason or "",
                contact.timezone or "Asia/Shanghai",
                contact.created_at.isoformat() if isinstance(contact.created_at, datetime) else contact.created_at,
                contact.updated_at.isoformat() if isinstance(contact.updated_at, datetime) else contact.updated_at,
            ]
        )

    _audit(
        session,
        tenant_id=tenant_id,
        actor=current,
        action="export",
        resource_type="contact",
        detail=f"exported_filters={json.dumps({'dnc': dnc, 'consent': consent, 'keyword': keyword}, ensure_ascii=False)}",
    )
    session.commit()

    output.seek(0)
    filename = f"contacts-{tenant_id}-{utc_now().strftime('%Y%m%d-%H%M%S')}.csv"
    return Response(
        content=output.getvalue(),
        media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@router.patch("/batch-dnc", response_model=ContactBatchDncResult)
def batch_dnc(
    payload: ContactBatchDncPatch,
    current: User | None = Depends(require_roles_if_authenticated("admin")),
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
):
    contact_ids = sorted(set(payload.contact_ids))
    contacts = session.exec(
        select(Contact).where(Contact.tenant_id == tenant_id, Contact.id.in_(contact_ids))
    ).all()
    found_ids = {contact.id for contact in contacts}
    missing_contact_ids = [contact_id for contact_id in contact_ids if contact_id not in found_ids]

    for contact in contacts:
        contact.dnc = payload.dnc
        contact.dnc_reason = payload.dnc_reason or None
        if payload.dnc:
            contact.consented_at = None

    if contacts:
        session.add_all(contacts)
    _audit(
        session,
        tenant_id=tenant_id,
        actor=current,
        action="batch_update",
        resource_type="contact",
        detail=(
            f"request_total={len(contact_ids)}, updated={len(contacts)}, "
            f"dnc={payload.dnc}, dnc_reason={payload.dnc_reason}"
        ),
    )
    session.commit()

    return ContactBatchDncResult(
        total=len(contact_ids),
        updated=len(contacts),
        skipped=len(missing_contact_ids),
        missing_contact_ids=missing_contact_ids,
    )


@router.post("", response_model=ContactOut)
def create_contact(
    payload: ContactCreate,
    current: User | None = Depends(require_roles_if_authenticated("admin")),
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
):
    normalized_phone = normalize_phone(payload.phone)
    _validate_timezone(payload.timezone)
    if not normalized_phone or not 6 <= len(normalized_phone) <= 15:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="phone must contain 6 to 15 digits")
    existing = session.exec(
        select(Contact).where(Contact.tenant_id == tenant_id, Contact.phone == normalized_phone)
    ).first()
    if existing:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="contact phone already exists")
    contact = Contact(
        tenant_id=tenant_id,
        phone=normalized_phone,
        name=payload.name,
        tags=payload.tags,
        consent_state=payload.consent_state,
        dnc=payload.dnc,
        dnc_reason=payload.dnc_reason or None,
        timezone=payload.timezone,
        consented_at=utc_now() if payload.consent_state == ConsentState.CONSENTED else None,
    )
    _audit(
        session,
        tenant_id=tenant_id,
        actor=current,
        action="create",
        resource_type="contact",
        detail=(
            f"phone={contact.phone}, name={contact.name or ''}, consent_state={contact.consent_state}, "
            f"dnc={contact.dnc}, dnc_reason={contact.dnc_reason or ''}, timezone={contact.timezone}"
        ),
    )
    session.add(contact)
    try:
        session.commit()
    except IntegrityError:
        session.rollback()
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="contact phone already exists")
    session.refresh(contact)
    return contact


@router.get("", response_model=list[ContactOut])
def list_contacts(
    tenant_id: int = Depends(get_tenant_id_for_request),
    page: int = Query(default=1, ge=1),
    size: int = Query(default=100, ge=1, le=200),
    keyword: str | None = Query(default=None),
    dnc: bool | None = Query(default=None),
    consent: str | None = Query(default=None),
    session: Session = Depends(get_session),
):
    skip, limit = get_pagination(page=page, size=size)
    query = select(Contact).where(Contact.tenant_id == tenant_id)
    if keyword:
        like = f"%{keyword}%"
        query = query.where(Contact.phone.like(like) | Contact.name.like(like))
    if dnc is not None:
        query = query.where(Contact.dnc == dnc)
    if consent:
        query = query.where(Contact.consent_state == consent)
    return session.exec(query.order_by(Contact.created_at.desc()).offset(skip).limit(limit)).all()


@router.get("/{contact_id}", response_model=ContactOut)
def get_contact(contact_id: int, tenant_id: int = Depends(get_tenant_id_for_request), session: Session = Depends(get_session)):
    contact = session.get(Contact, contact_id)
    if not contact or contact.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="contact not found")
    return contact


@router.patch("/{contact_id}", response_model=ContactOut)
def patch_contact(
    contact_id: int,
    payload: ContactPatch,
    current: User | None = Depends(require_roles_if_authenticated("admin")),
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
):
    contact = session.get(Contact, contact_id)
    if not contact or contact.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="contact not found")
    data = payload.dict(exclude_unset=True)
    if "timezone" in data:
        _validate_timezone(data["timezone"])
    for key, value in data.items():
        setattr(contact, key, value)
    if payload.consent_state is not None:
        if payload.consent_state == ConsentState.CONSENTED:
            contact.consented_at = utc_now()
        else:
            contact.consented_at = None
    _audit(
        session,
        tenant_id=tenant_id,
        actor=current,
        action="update",
        resource_type="contact",
        resource_id=contact_id,
        detail=f"contact_id={contact_id}, fields={','.join(sorted(payload.dict(exclude_unset=True).keys()))}",
    )
    session.add(contact)
    session.commit()
    session.refresh(contact)
    return contact


@router.delete("/{contact_id}")
def delete_contact(
    contact_id: int,
    current: User | None = Depends(require_roles_if_authenticated("admin")),
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
):
    contact = session.get(Contact, contact_id)
    if not contact or contact.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="contact not found")
    campaign_reference = session.exec(
        select(CampaignContact)
        .join(Campaign, Campaign.id == CampaignContact.campaign_id)
        .where(
            CampaignContact.contact_id == contact_id,
            Campaign.tenant_id == tenant_id,
            Campaign.status != "deleted",
        )
    ).first()
    call_reference = session.exec(
        select(CallSession.id).where(
            CallSession.tenant_id == tenant_id,
            CallSession.contact_id == contact_id,
        )
    ).first()
    if campaign_reference or call_reference:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="contact is referenced by campaign or call history; mark it DNC instead",
        )
    _audit(
        session,
        tenant_id=tenant_id,
        actor=current,
        action="delete",
        resource_type="contact",
        resource_id=contact_id,
        detail=f"phone={contact.phone}",
    )
    session.delete(contact)
    session.commit()
    return {"result": "deleted"}
