from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func
from sqlmodel import Session, select

from ...api.deps import check_api_key, get_tenant_id_for_request, require_roles_if_authenticated
from ...clock import utc_now
from ...db import get_session
from ...models import ScriptFlowVersion, ScriptTemplate
from ...schemas import (
    ScriptFlowCreate,
    ScriptFlowOut,
    ScriptFlowSimulateOut,
    ScriptFlowSimulateRequest,
    ScriptFlowUpdate,
)
from ...services.script_flow import FlowValidationError, default_graph, dump_graph, load_graph, simulate

router = APIRouter(
    prefix="/api/v1/script-templates/{template_id}/flows",
    tags=["script-flows"],
    dependencies=[Depends(check_api_key), Depends(require_roles_if_authenticated("admin"))],
)


def _template(session: Session, tenant_id: int, template_id: int) -> ScriptTemplate:
    item = session.get(ScriptTemplate, template_id)
    if not item or item.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="template not found")
    return item


def _version(session: Session, tenant_id: int, template_id: int, version_id: int) -> ScriptFlowVersion:
    item = session.get(ScriptFlowVersion, version_id)
    if not item or item.tenant_id != tenant_id or item.script_template_id != template_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="flow version not found")
    return item


def _out(item: ScriptFlowVersion) -> ScriptFlowOut:
    try:
        graph = load_graph(item.graph_json)
    except FlowValidationError as exc:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(exc)) from exc
    return ScriptFlowOut(**item.model_dump(exclude={"graph_json"}), graph=graph)


@router.get("", response_model=list[ScriptFlowOut])
def list_versions(template_id: int, tenant_id: int = Depends(get_tenant_id_for_request), session: Session = Depends(get_session)):
    _template(session, tenant_id, template_id)
    items = session.exec(
        select(ScriptFlowVersion).where(
            ScriptFlowVersion.tenant_id == tenant_id,
            ScriptFlowVersion.script_template_id == template_id,
        ).order_by(ScriptFlowVersion.version.desc())
    ).all()
    return [_out(item) for item in items]


@router.post("", response_model=ScriptFlowOut)
def create_version(payload: ScriptFlowCreate, template_id: int, tenant_id: int = Depends(get_tenant_id_for_request), session: Session = Depends(get_session)):
    template = _template(session, tenant_id, template_id)
    latest = session.exec(
        select(func.max(ScriptFlowVersion.version)).where(ScriptFlowVersion.script_template_id == template_id)
    ).one() or 0
    graph = default_graph(template.content)
    if payload.clone_version_id is not None:
        source = _version(session, tenant_id, template_id, payload.clone_version_id)
        graph = load_graph(source.graph_json)
    item = ScriptFlowVersion(
        tenant_id=tenant_id,
        script_template_id=template_id,
        version=int(latest) + 1,
        name=payload.name or f"{template.name} v{int(latest) + 1}",
        description=payload.description,
        graph_json=dump_graph(graph),
    )
    session.add(item)
    session.commit()
    session.refresh(item)
    return _out(item)


@router.get("/{version_id}", response_model=ScriptFlowOut)
def get_version(version_id: int, template_id: int, tenant_id: int = Depends(get_tenant_id_for_request), session: Session = Depends(get_session)):
    return _out(_version(session, tenant_id, template_id, version_id))


@router.put("/{version_id}", response_model=ScriptFlowOut)
def update_version(payload: ScriptFlowUpdate, version_id: int, template_id: int, tenant_id: int = Depends(get_tenant_id_for_request), session: Session = Depends(get_session)):
    item = _version(session, tenant_id, template_id, version_id)
    if item.status != "draft":
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="published flow is immutable; clone a new draft")
    try:
        item.graph_json = dump_graph(payload.graph)
    except FlowValidationError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    if payload.name is not None:
        item.name = payload.name
    if payload.description is not None:
        item.description = payload.description
    item.updated_at = utc_now()
    session.add(item)
    session.commit()
    session.refresh(item)
    return _out(item)


@router.post("/{version_id}/publish", response_model=ScriptFlowOut)
def publish_version(version_id: int, template_id: int, tenant_id: int = Depends(get_tenant_id_for_request), session: Session = Depends(get_session)):
    item = _version(session, tenant_id, template_id, version_id)
    try:
        dump_graph(load_graph(item.graph_json))
    except FlowValidationError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    if item.status == "draft":
        item.status = "published"
        item.published_at = utc_now()
        item.updated_at = utc_now()
        session.add(item)
        session.commit()
        session.refresh(item)
    return _out(item)


@router.post("/{version_id}/simulate", response_model=ScriptFlowSimulateOut)
def simulate_version(payload: ScriptFlowSimulateRequest, version_id: int, template_id: int, tenant_id: int = Depends(get_tenant_id_for_request), session: Session = Depends(get_session)):
    item = _version(session, tenant_id, template_id, version_id)
    try:
        return simulate(load_graph(item.graph_json), payload.current_node_id, payload.transcript, payload.silence)
    except FlowValidationError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
