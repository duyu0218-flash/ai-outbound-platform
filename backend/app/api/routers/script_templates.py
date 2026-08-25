from datetime import datetime
from typing import List

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlmodel import Session, select

from ...api.deps import check_api_key, get_pagination, get_tenant_id
from ...db import get_session
from ...models import ScriptTemplate
from ...schemas import ScriptTemplateCreate, ScriptTemplateOut, ScriptTemplateUpdate


router = APIRouter(
    prefix="/api/v1/script-templates",
    tags=["script-templates"],
    dependencies=[Depends(check_api_key)],
)


@router.get("", response_model=List[ScriptTemplateOut])
def list_templates(
    tenant_id: int = Depends(get_tenant_id),
    active_only: bool = Query(default=False),
    category: str | None = Query(default=None),
    page: int = Query(default=1, ge=1),
    size: int = Query(default=50, ge=1, le=200),
    session: Session = Depends(get_session),
):
    skip, limit = get_pagination(page=page, size=size)
    query = select(ScriptTemplate).where(ScriptTemplate.tenant_id == tenant_id).order_by(
        ScriptTemplate.updated_at.desc()
    )
    if category:
        query = query.where(ScriptTemplate.category == category)
    if active_only:
        query = query.where(ScriptTemplate.is_active)
    return session.exec(query.offset(skip).limit(limit)).all()


@router.get("/{template_id}", response_model=ScriptTemplateOut)
def get_template(
    template_id: int,
    tenant_id: int = Depends(get_tenant_id),
    session: Session = Depends(get_session),
):
    template = session.get(ScriptTemplate, template_id)
    if not template or template.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="template not found")
    return template


@router.post("", response_model=ScriptTemplateOut)
def create_template(
    payload: ScriptTemplateCreate,
    tenant_id: int = Depends(get_tenant_id),
    session: Session = Depends(get_session),
):
    template = ScriptTemplate(
        tenant_id=tenant_id,
        name=payload.name,
        content=payload.content,
        category=payload.category,
        description=payload.description,
        tags=payload.tags,
        is_active=payload.is_active,
        version=1,
    )
    session.add(template)
    session.commit()
    session.refresh(template)
    return template


@router.put("/{template_id}", response_model=ScriptTemplateOut)
def update_template(
    template_id: int,
    payload: ScriptTemplateUpdate,
    tenant_id: int = Depends(get_tenant_id),
    session: Session = Depends(get_session),
):
    template = session.get(ScriptTemplate, template_id)
    if not template or template.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="template not found")

    data = payload.dict(exclude_unset=True)
    if "is_active" in data and isinstance(data["is_active"], bool):
        template.is_active = data["is_active"]
    if "name" in data and data["name"] is not None:
        template.name = data["name"]
    if "content" in data and data["content"] is not None:
        template.content = data["content"]
    if "category" in data and data["category"] is not None:
        template.category = data["category"]
    if "description" in data and data["description"] is not None:
        template.description = data["description"]
    if "tags" in data and data["tags"] is not None:
        template.tags = data["tags"]

    template.version += 1
    template.updated_at = datetime.utcnow()
    session.add(template)
    session.commit()
    session.refresh(template)
    return template


@router.delete("/{template_id}")
def delete_template(
    template_id: int,
    tenant_id: int = Depends(get_tenant_id),
    session: Session = Depends(get_session),
):
    template = session.get(ScriptTemplate, template_id)
    if not template or template.tenant_id != tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="template not found")
    # keep historical integrity: soft remove only for campaign references
    template.is_active = False
    template.updated_at = datetime.utcnow()
    session.add(template)
    session.commit()
    return {"result": "deleted"}
