from datetime import datetime
from typing import List

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlmodel import Session, select

from ...api.deps import check_api_key, get_pagination, get_tenant_id
from ...db import get_session
from ...models import Campaign, CampaignContact, CallSession, Contact
from ...services.call_service import NotFoundError, start_campaign as start_campaign_service, place_call
from ...schemas import CampaignCreate, CampaignOut

router = APIRouter(prefix="/api/v1/campaigns", tags=["campaigns"], dependencies=[Depends(check_api_key)])


@router.post("", response_model=CampaignOut)
def create_campaign(
    payload: CampaignCreate,
    tenant_id: int = Depends(get_tenant_id),
    session: Session = Depends(get_session),
):
    campaign = Campaign(
        tenant_id=tenant_id,
        name=payload.name,
        script=payload.script,
        mode=payload.mode,
        concurrency=payload.concurrency,
        retry_limit=payload.retry_limit,
        retry_interval_sec=payload.retry_interval_sec,
        attempt_interval_sec=payload.attempt_interval_sec,
        recording_enabled=payload.recording_enabled,
        hangup_sms_enabled=payload.hangup_sms_enabled,
        status="draft",
    )
    session.add(campaign)
    session.commit()
    session.refresh(campaign)

    for index, contact_id in enumerate(payload.contact_ids):
        contact = session.get(Contact, contact_id)
        if not contact or contact.tenant_id != tenant_id:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"contact not found: {contact_id}")
        rel = CampaignContact(campaign_id=campaign.id, contact_id=contact_id, contact_order=index)
        session.add(rel)

    session.commit()
    return campaign


@router.get("", response_model=List[CampaignOut])
def list_campaigns(
    tenant_id: int = Depends(get_tenant_id),
    page: int = Query(default=1, ge=1),
    size: int = Query(default=50, ge=1, le=200),
    session: Session = Depends(get_session),
):
    skip, limit = get_pagination(page=page, size=size)
    query = select(Campaign).where(Campaign.tenant_id == tenant_id).order_by(Campaign.created_at.desc())
    return session.exec(query.offset(skip).limit(limit)).all()


@router.get("/{campaign_id}", response_model=CampaignOut)
def get_campaign(campaign_id: int, tenant_id: int = Depends(get_tenant_id), session: Session = Depends(get_session)):
    campaign = session.get(Campaign, campaign_id)
    if not campaign or campaign.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="campaign not found")
    return campaign


@router.delete("/{campaign_id}")
def delete_campaign(
    campaign_id: int,
    tenant_id: int = Depends(get_tenant_id),
    session: Session = Depends(get_session),
):
    campaign = session.get(Campaign, campaign_id)
    if not campaign or campaign.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="campaign not found")
    campaign.status = "deleted"
    campaign.updated_at = datetime.utcnow()
    session.add(campaign)
    session.commit()
    return {"result": "deleted"}


@router.post("/{campaign_id}/start")
async def start_campaign(
    campaign_id: int,
    tenant_id: int = Depends(get_tenant_id),
    session: Session = Depends(get_session),
    auto_dial: bool = True,
    max_dials: int | None = Query(default=None, ge=1),
):
    try:
        result = start_campaign_service(session, tenant_id=tenant_id, campaign_id=campaign_id, only_active_contacts=True)
    except NotFoundError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="campaign not found")

    campaign = session.get(Campaign, campaign_id)
    if not campaign or campaign.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="campaign not found")
    if max_dials is not None:
        result_call_ids = result["call_ids"][:max_dials]
    else:
        result_call_ids = result["call_ids"]

    dialed = 0
    if auto_dial:
        for call_id in result_call_ids:
            call = session.get(CallSession, call_id)
            if not call:
                continue
            await place_call(session=session, call=call)
            dialed += 1

    campaign.status = "running"
    campaign.updated_at = datetime.utcnow()
    session.add(campaign)
    session.commit()
    result["campaign_status"] = "running"
    result["auto_dial_requested"] = auto_dial
    result["auto_dial_count"] = dialed
    return result
