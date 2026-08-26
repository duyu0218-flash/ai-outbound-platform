from datetime import datetime
from typing import List

from fastapi import APIRouter, Depends, HTTPException, Query, status, BackgroundTasks
from sqlmodel import Session, select

from ...api.deps import check_api_key, get_pagination, get_tenant_id, require_roles_if_authenticated
from ...db import get_session
from ...models import Campaign, CampaignContact, CallSession, Contact, ScriptTemplate
from ...services.call_service import (
    NotFoundError,
    resolve_campaign_script,
    start_campaign as start_campaign_service,
    dispatch_call_ids,
)
from ...schemas import CampaignCreate, CampaignOut

router = APIRouter(
    prefix="/api/v1/campaigns",
    tags=["campaigns"],
    dependencies=[Depends(check_api_key), Depends(require_roles_if_authenticated("admin"))],
)


@router.post("", response_model=CampaignOut)
def create_campaign(
    payload: CampaignCreate,
    tenant_id: int = Depends(get_tenant_id),
    session: Session = Depends(get_session),
):
    campaign = Campaign(
        tenant_id=tenant_id,
        name=payload.name,
        script=payload.script or "",
        script_template_id=payload.script_template_id,
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
    if not campaign.script and campaign.script_template_id is not None:
        campaign.script = resolve_campaign_script(session, tenant_id=tenant_id, campaign_id=campaign.id)
    return campaign


@router.put("/{campaign_id}", response_model=CampaignOut)
def update_campaign(
    campaign_id: int,
    payload: CampaignCreate,
    tenant_id: int = Depends(get_tenant_id),
    session: Session = Depends(get_session),
):
    campaign = session.get(Campaign, campaign_id)
    if not campaign or campaign.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="campaign not found")
    if payload.name:
        campaign.name = payload.name
    campaign.script = payload.script or ""
    campaign.script_template_id = payload.script_template_id
    campaign.mode = payload.mode
    campaign.concurrency = payload.concurrency
    campaign.retry_limit = payload.retry_limit
    campaign.retry_interval_sec = payload.retry_interval_sec
    campaign.attempt_interval_sec = payload.attempt_interval_sec
    campaign.recording_enabled = payload.recording_enabled
    campaign.hangup_sms_enabled = payload.hangup_sms_enabled

    if campaign.script_template_id is not None:
        template = session.get(ScriptTemplate, campaign.script_template_id)
        if not template or template.tenant_id != tenant_id:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="script template not found")
        if not payload.script:
            campaign.script = resolve_campaign_script(
                session=session,
                tenant_id=tenant_id,
                campaign_id=campaign.id,
            )

    campaign.updated_at = datetime.utcnow()
    session.add(campaign)
    session.commit()
    session.refresh(campaign)
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
    background_tasks: BackgroundTasks,
    tenant_id: int = Depends(get_tenant_id),
    session: Session = Depends(get_session),
    auto_dial: bool = True,
    max_dials: int | None = Query(default=None, ge=1),
    async_dial: bool = Query(default=True),
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
    dispatch_result: dict[str, object] = {"total": 0, "succeeded": 0, "failed": 0, "skipped": 0}
    if auto_dial:
        target_call_ids = result_call_ids
        if max_dials is not None:
            target_call_ids = target_call_ids[:max_dials]

        if async_dial:
            background_tasks.add_task(
                dispatch_call_ids,
                [str(call_id) for call_id in target_call_ids],
                max_concurrency=campaign.concurrency,
            )
            dialed = len(target_call_ids)
            dispatch_result = {
                "total": len(result_call_ids),
                "target": len(target_call_ids),
                "succeeded": 0,
                "failed": 0,
                "skipped": 0,
                "status": "queued",
            }
        else:
            call_list: list[str] = []
            for call_id in target_call_ids:
                call = session.get(CallSession, call_id)
                if not call:
                    continue
                call_list.append(str(call.id))
            dispatch_result = await dispatch_call_ids(call_list, max_concurrency=campaign.concurrency)
            dialed = dispatch_result.get("succeeded", 0)

    campaign.status = "running"
    campaign.updated_at = datetime.utcnow()
    session.add(campaign)
    session.commit()
    result["campaign_status"] = "running"
    result["auto_dial_requested"] = auto_dial
    result["auto_dial_count"] = dialed
    result["dispatch_mode"] = "async" if auto_dial and async_dial else "sync"
    result["dispatch_result"] = dispatch_result
    return result
