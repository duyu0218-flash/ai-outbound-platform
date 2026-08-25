from typing import List

from fastapi import APIRouter, Depends
from sqlmodel import Session, select

from ...db import get_session
from ...models import Contact
from ...schemas import ContactCreate, ContactOut
from ...api.deps import check_api_key

router = APIRouter(prefix="/api/v1/contacts", tags=["contacts"], dependencies=[Depends(check_api_key)])


@router.post("", response_model=ContactOut)
def create_contact(payload: ContactCreate, session: Session = Depends(get_session)):
    contact = Contact(tenant_id=1, phone=payload.phone, name=payload.name, tags=payload.tags)
    session.add(contact)
    session.commit()
    session.refresh(contact)
    return contact


@router.get("", response_model=List[ContactOut])
def list_contacts(session: Session = Depends(get_session)):
    return session.exec(select(Contact).order_by(Contact.created_at.desc())).all()
