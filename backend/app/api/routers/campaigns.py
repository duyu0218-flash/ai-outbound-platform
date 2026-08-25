from datetime import datetime
from typing import List

from fastapi import APIRouter, Depends
from sqlmodel import Session, select

from ...db import get_session
from ...models import Campaign, CampaignContact, CallMode, Contact
from ...schemas import CampaignCreate, CampaignOut
from ...api.deps import check_api_key

router = APIRouter(prefix="/api/v1/campaigns", tags=["campaigns"], dependencies=[Depends(check_api_key)])


@router.post("", response_model=CampaignOut)
def create_campaign(payload: CampaignCreate, session: Session = Depends(get_session)):
    campaign = Campaign(
        tenant_id=1,
        name=payload.name,
        script=payload.script,
        mode=payload.mode,
        concurrency=payload.concurrency,
        retry_limit=payload.retry_limit,
        retry_interval_sec=payload.retry_interval_sec,
    )
    session.add(campaign)
    session.commit()
    session.refresh(campaign)

    for index, contact_id in enumerate(payload.contact_ids):
        rel = CampaignContact(campaign_id=campaign.id, contact_id=contact_id, contact_order=index)
        session.add(rel)

    session.commit()
    return campaign


@router.get("", response_model=List[CampaignOut])
def list_campaigns(session: Session = Depends(get_session)):
    return session.exec(select(Campaign).order_by(Campaign.created_at.desc())).all()
