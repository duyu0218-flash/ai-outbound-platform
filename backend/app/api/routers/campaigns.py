from typing import List

from fastapi import APIRouter, Depends, HTTPException, Query, status, BackgroundTasks
from sqlmodel import Session, select

from ...api.deps import check_api_key, get_pagination, get_tenant_id_for_request, require_roles_if_authenticated
from ...db import get_session
from ...clock import utc_now
from ...models import CallSession, CallStatus, Campaign, CampaignContact, Contact, ScriptTemplate
from ...services.call_service import (
    NotFoundError,
    resolve_campaign_script,
    start_campaign as start_campaign_service,
    dispatch_call_ids,
)
from ...schemas import (
    CampaignCreate,
    CampaignDispatchError,
    CampaignDispatchResult,
    CampaignOut,
    CampaignStartResponse,
)

router = APIRouter(
    prefix="/api/v1/campaigns",
    tags=["campaigns"],
    dependencies=[Depends(check_api_key), Depends(require_roles_if_authenticated("admin"))],
)


def _validate_contacts(session: Session, tenant_id: int, contact_ids: list[int]) -> list[Contact]:
    if len(contact_ids) != len(set(contact_ids)):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="duplicate contact id")
    contacts: list[Contact] = []
    for contact_id in contact_ids:
        contact = session.get(Contact, contact_id)
        if not contact or contact.tenant_id != tenant_id:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"contact not found: {contact_id}")
        contacts.append(contact)
    return contacts


def _validate_script_template(session: Session, tenant_id: int, template_id: int | None) -> None:
    if template_id is None:
        return
    template = session.get(ScriptTemplate, template_id)
    if not template or template.tenant_id != tenant_id or not template.is_active:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="script template not found or inactive")


def _campaign_out(session: Session, campaign: Campaign) -> CampaignOut:
    rels = session.exec(
        select(CampaignContact)
        .where(CampaignContact.campaign_id == campaign.id)
        .order_by(CampaignContact.contact_order.asc())
    ).all()
    return CampaignOut(
        **campaign.model_dump(),
        contact_ids=[rel.contact_id for rel in rels],
    )


@router.post("", response_model=CampaignOut)
def create_campaign(
    payload: CampaignCreate,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
):
    contacts = _validate_contacts(session, tenant_id, payload.contact_ids)
    _validate_script_template(session, tenant_id, payload.script_template_id)
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
    session.flush()

    for index, contact in enumerate(contacts):
        rel = CampaignContact(campaign_id=campaign.id, contact_id=contact.id, contact_order=index)
        session.add(rel)

    session.commit()
    session.refresh(campaign)
    return _campaign_out(session, campaign)


@router.get("", response_model=List[CampaignOut])
def list_campaigns(
    tenant_id: int = Depends(get_tenant_id_for_request),
    page: int = Query(default=1, ge=1),
    size: int = Query(default=50, ge=1, le=200),
    session: Session = Depends(get_session),
):
    skip, limit = get_pagination(page=page, size=size)
    query = select(Campaign).where(Campaign.tenant_id == tenant_id).order_by(Campaign.created_at.desc())
    campaigns = session.exec(query.offset(skip).limit(limit)).all()
    return [_campaign_out(session, campaign) for campaign in campaigns]


@router.get("/{campaign_id}", response_model=CampaignOut)
def get_campaign(campaign_id: int, tenant_id: int = Depends(get_tenant_id_for_request), session: Session = Depends(get_session)):
    campaign = session.get(Campaign, campaign_id)
    if not campaign or campaign.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="campaign not found")
    if campaign.status == "deleted":
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="campaign not found")
    if not campaign.script and campaign.script_template_id is not None:
        campaign.script = resolve_campaign_script(session, tenant_id=tenant_id, campaign_id=campaign.id)
    return _campaign_out(session, campaign)


@router.put("/{campaign_id}", response_model=CampaignOut)
def update_campaign(
    campaign_id: int,
    payload: CampaignCreate,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
):
    campaign = session.get(Campaign, campaign_id)
    if not campaign or campaign.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="campaign not found")
    if campaign.status == "deleted":
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="deleted campaign cannot be updated")
    if campaign.status in {"running", "paused"}:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="pause or stop campaign before editing")
    contacts = _validate_contacts(session, tenant_id, payload.contact_ids)
    _validate_script_template(session, tenant_id, payload.script_template_id)
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
        if not payload.script:
            campaign.script = resolve_campaign_script(
                session=session,
                tenant_id=tenant_id,
                campaign_id=campaign.id,
            )

    existing_rels = session.exec(
        select(CampaignContact).where(CampaignContact.campaign_id == campaign.id)
    ).all()
    for rel in existing_rels:
        session.delete(rel)
    for index, contact in enumerate(contacts):
        session.add(CampaignContact(campaign_id=campaign.id, contact_id=contact.id, contact_order=index))

    campaign.updated_at = utc_now()
    session.add(campaign)
    session.commit()
    session.refresh(campaign)
    return _campaign_out(session, campaign)


@router.delete("/{campaign_id}")
def delete_campaign(
    campaign_id: int,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
):
    campaign = session.get(Campaign, campaign_id)
    if not campaign or campaign.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="campaign not found")
    if campaign.status == "deleted":
        return {"result": "deleted"}
    if campaign.status in {"running", "paused"}:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="stop campaign before deleting")
    campaign.status = "deleted"
    campaign.updated_at = utc_now()
    session.add(campaign)
    session.commit()
    return {"result": "deleted"}


@router.post("/{campaign_id}/start", response_model=CampaignStartResponse)
async def start_campaign(
    campaign_id: int,
    background_tasks: BackgroundTasks,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
    auto_dial: bool = True,
    max_dials: int | None = Query(default=None, ge=1),
    async_dial: bool = Query(default=True),
):
    campaign = session.get(Campaign, campaign_id)
    if not campaign or campaign.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="campaign not found")
    if campaign.status == "deleted":
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="deleted campaign cannot be started")
    if campaign.status not in {"draft", "failed", "stopped"}:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"campaign cannot be started from status {campaign.status}",
        )

    try:
        result = start_campaign_service(session, tenant_id=tenant_id, campaign_id=campaign_id, only_active_contacts=True)
    except NotFoundError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="campaign not found")

    if max_dials is not None:
        result_call_ids = result["call_ids"][:max_dials]
    else:
        result_call_ids = result["call_ids"]

    skip_reasons = [CampaignDispatchError(**item) for item in result.get("skip_reasons", [])]
    precheck_error_codes = sorted(
        {str(item.get("code")) for item in result.get("skip_reasons", []) if item.get("code")}
    )

    dialed = 0
    dispatch_result = CampaignDispatchResult(
        total=result.get("total_contacts", 0),
        target=min(len(result_call_ids), max_dials or len(result_call_ids)),
        succeeded=0,
        failed=0,
        skipped=result.get("skipped", 0),
        status="not_dispatched",
        errors=[],
        error_codes=precheck_error_codes,
    )
    if result.get("created", 0) > 0:
        # Persist the running state before synchronous workers inspect it.
        # Background tasks are started after the response, but sync dispatch
        # happens inside this request.
        campaign.status = "running"
        campaign.updated_at = utc_now()
        session.add(campaign)
        session.commit()
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
            dispatch_result = CampaignDispatchResult(
                total=len(result_call_ids),
                target=len(target_call_ids),
                succeeded=0,
                failed=0,
                skipped=0,
                status="queued",
                errors=[],
                error_codes=precheck_error_codes,
            )
        else:
            call_list = [str(call_id) for call_id in target_call_ids]
            _dispatch_result = await dispatch_call_ids(call_list, max_concurrency=campaign.concurrency)
            dispatch_result = CampaignDispatchResult(
                total=_dispatch_result.get("total", 0),
                target=_dispatch_result.get("target", 0),
                succeeded=_dispatch_result.get("succeeded", 0),
                failed=_dispatch_result.get("failed", 0),
                skipped=_dispatch_result.get("skipped", 0),
                status=_dispatch_result.get("status", "completed"),
                errors=[CampaignDispatchError(**item) for item in _dispatch_result.get("errors", [])],
                error_codes=_dispatch_result.get("error_codes", []),
            )
            dialed = dispatch_result.succeeded
    if not auto_dial:
        dispatch_result.target = 0

    all_sync_dispatches_failed = (
        auto_dial
        and not async_dial
        and dispatch_result.target > 0
        and dispatch_result.succeeded == 0
    )
    has_scheduled_retries = session.exec(
        select(CallSession.id).where(
            CallSession.campaign_id == campaign_id,
            CallSession.next_attempt_at.is_not(None),
        )
    ).first() is not None
    campaign.status = (
        "failed"
        if result.get("created", 0) == 0 or (all_sync_dispatches_failed and not has_scheduled_retries)
        else "running"
    )
    campaign.updated_at = utc_now()
    session.add(campaign)
    session.commit()

    result["campaign_status"] = campaign.status
    result["auto_dial_requested"] = auto_dial
    result["auto_dial_count"] = dialed
    result["dispatch_mode"] = "async" if auto_dial and async_dial else "sync"
    result["dispatch_result"] = dispatch_result

    result_code = "SUCCESS"
    result_message = "campaign started"
    error_codes = sorted(set(dispatch_result.error_codes + precheck_error_codes))
    has_precheck_error = bool(precheck_error_codes) or result.get("skipped", 0) > 0 and result.get("created", 0) == 0

    if has_precheck_error and result.get("created", 0) == 0:
        result_code = "FAILED"
        result_message = "campaign started with precheck blocking"
    elif has_precheck_error:
        result_code = "PARTIAL_SUCCESS"
        result_message = "campaign started with precheck warnings"

    if dispatch_result.failed > 0:
        result_code = "PARTIAL_SUCCESS" if dispatch_result.succeeded > 0 else "FAILED"
        result_message = "campaign started with errors"
        error_codes = sorted(set(error_codes))
    elif not has_precheck_error and dispatch_result.succeeded == 0 and dispatch_result.status == "not_dispatched":
        result_code = "NOT_DISPATCHED"
        result_message = "campaign prepared, auto dial disabled"

    response = CampaignStartResponse(
        id=campaign.id,
        tenant_id=campaign.tenant_id,
        name=campaign.name,
        status=campaign.status,
        total_contacts=result["total_contacts"],
        created=result["created"],
        skipped=result["skipped"],
        campaign_status=result["campaign_status"],
        auto_dial_requested=result["auto_dial_requested"],
        auto_dial_count=result["auto_dial_count"],
        dispatch_mode=result["dispatch_mode"],
        dispatch_result=dispatch_result,
        result_code=result_code,
        result_message=result_message,
        error_codes=error_codes,
        skip_reasons=skip_reasons,
    )
    return response


def _get_mutable_campaign(session: Session, tenant_id: int, campaign_id: int) -> Campaign:
    campaign = session.get(Campaign, campaign_id)
    if not campaign or campaign.tenant_id != tenant_id or campaign.status == "deleted":
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="campaign not found")
    return campaign


@router.post("/{campaign_id}/pause", response_model=CampaignOut)
def pause_campaign(
    campaign_id: int,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
):
    campaign = _get_mutable_campaign(session, tenant_id, campaign_id)
    if campaign.status != "running":
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="only a running campaign can be paused")
    campaign.status = "paused"
    campaign.updated_at = utc_now()
    session.add(campaign)
    session.commit()
    session.refresh(campaign)
    return _campaign_out(session, campaign)


@router.post("/{campaign_id}/resume", response_model=CampaignOut)
def resume_campaign(
    campaign_id: int,
    background_tasks: BackgroundTasks,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
):
    campaign = _get_mutable_campaign(session, tenant_id, campaign_id)
    if campaign.status != "paused":
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="only a paused campaign can be resumed")
    campaign.status = "running"
    campaign.updated_at = utc_now()
    session.add(campaign)
    session.commit()
    queued_ids = session.exec(
        select(CallSession.id).where(
            CallSession.tenant_id == tenant_id,
            CallSession.campaign_id == campaign_id,
            CallSession.status.in_({CallStatus.CREATED, CallStatus.QUEUED, CallStatus.FAILED}),
            CallSession.attempts < CallSession.max_attempts,
        )
    ).all()
    if queued_ids:
        background_tasks.add_task(
            dispatch_call_ids,
            [str(call_id) for call_id in queued_ids],
            max_concurrency=campaign.concurrency,
        )
    session.refresh(campaign)
    return _campaign_out(session, campaign)


@router.post("/{campaign_id}/stop", response_model=CampaignOut)
def stop_campaign(
    campaign_id: int,
    tenant_id: int = Depends(get_tenant_id_for_request),
    session: Session = Depends(get_session),
):
    campaign = _get_mutable_campaign(session, tenant_id, campaign_id)
    if campaign.status not in {"running", "paused"}:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="only a running or paused campaign can be stopped")
    queued_calls = session.exec(
        select(CallSession).where(
            CallSession.tenant_id == tenant_id,
            CallSession.campaign_id == campaign_id,
            CallSession.status.in_({CallStatus.CREATED, CallStatus.QUEUED}),
        )
    ).all()
    for call in queued_calls:
        call.status = CallStatus.FAILED
        call.last_error = "campaign stopped before dispatch"
        call.updated_at = utc_now()
        session.add(call)
    scheduled_calls = session.exec(
        select(CallSession).where(
            CallSession.tenant_id == tenant_id,
            CallSession.campaign_id == campaign_id,
            CallSession.next_attempt_at.is_not(None),
        )
    ).all()
    for call in scheduled_calls:
        call.next_attempt_at = None
        session.add(call)
    campaign.status = "stopped"
    campaign.updated_at = utc_now()
    session.add(campaign)
    session.commit()
    session.refresh(campaign)
    return _campaign_out(session, campaign)
