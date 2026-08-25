from typing import List

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlmodel import Session, select

from ...api.deps import check_api_key, get_pagination, get_tenant_id
from ...db import get_session
from ...models import Contact
from ...schemas import ContactCreate, ContactPatch, ContactOut

router = APIRouter(prefix="/api/v1/contacts", tags=["contacts"], dependencies=[Depends(check_api_key)])


@router.post("", response_model=ContactOut)
def create_contact(payload: ContactCreate, tenant_id: int = Depends(get_tenant_id), session: Session = Depends(get_session)):
    contact = Contact(
        tenant_id=tenant_id,
        phone=payload.phone,
        name=payload.name,
        tags=payload.tags,
        consent_state=payload.consent_state,
        dnc=payload.dnc,
        timezone=payload.timezone,
    )
    session.add(contact)
    session.commit()
    session.refresh(contact)
    return contact


@router.get("", response_model=List[ContactOut])
def list_contacts(
    tenant_id: int = Depends(get_tenant_id),
    page: int = Query(default=1, ge=1),
    size: int = Query(default=100, ge=1, le=200),
    keyword: str | None = Query(default=None),
    dnc: bool | None = Query(default=None),
    consent: str | None = Query(default=None),
    session: Session = Depends(get_session),
):
    skip, limit = get_pagination(page=page, size=size)
    q = select(Contact).where(Contact.tenant_id == tenant_id)
    if keyword:
        like = f"%{keyword}%"
        q = q.where(Contact.phone.like(like) | Contact.name.like(like))
    if dnc is not None:
        q = q.where(Contact.dnc == dnc)
    if consent:
        q = q.where(Contact.consent_state == consent)
    return session.exec(q.order_by(Contact.created_at.desc()).offset(skip).limit(limit)).all()


@router.get("/{contact_id}", response_model=ContactOut)
def get_contact(contact_id: int, tenant_id: int = Depends(get_tenant_id), session: Session = Depends(get_session)):
    contact = session.get(Contact, contact_id)
    if not contact or contact.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="contact not found")
    return contact


@router.patch("/{contact_id}", response_model=ContactOut)
def patch_contact(
    contact_id: int,
    payload: ContactPatch,
    tenant_id: int = Depends(get_tenant_id),
    session: Session = Depends(get_session),
):
    contact = session.get(Contact, contact_id)
    if not contact or contact.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="contact not found")
    data = payload.dict(exclude_unset=True)
    for k, v in data.items():
        setattr(contact, k, v)
    session.add(contact)
    session.commit()
    session.refresh(contact)
    return contact


@router.delete("/{contact_id}")
def delete_contact(contact_id: int, tenant_id: int = Depends(get_tenant_id), session: Session = Depends(get_session)):
    contact = session.get(Contact, contact_id)
    if not contact or contact.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="contact not found")
    session.delete(contact)
    session.commit()
    return {"result": "deleted"}
