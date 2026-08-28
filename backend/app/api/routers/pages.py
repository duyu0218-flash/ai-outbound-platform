from pathlib import Path

from fastapi import APIRouter, Depends
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse
from sqlalchemy import func
from sqlmodel import Session, select

from ...api.deps import require_role, require_roles
from ...db import get_session
from ...models import CallSession, Campaign, Contact, ScriptTemplate, User


router = APIRouter(tags=["portal-pages"])
FRONTEND_DIR = Path(__file__).resolve().parents[2] / "static"
INDEX_FILE = FRONTEND_DIR / "index.html"


@router.get("/api/v1/admin/dashboard")
def admin_dashboard(
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    tenant_id = current.tenant_id
    return {
        "scope": "admin",
        "message": "管理员控制台",
        "stats": {
            "contacts": session.exec(select(func.count(Contact.id)).where(Contact.tenant_id == tenant_id)).one(),
            "active_scripts": session.exec(
                select(func.count(ScriptTemplate.id)).where(
                    ScriptTemplate.tenant_id == tenant_id,
                    ScriptTemplate.is_active.is_(True),
                )
            ).one(),
            "campaigns": session.exec(
                select(func.count(Campaign.id)).where(
                    Campaign.tenant_id == tenant_id,
                    Campaign.status != "deleted",
                )
            ).one(),
            "calls": session.exec(select(func.count(CallSession.id)).where(CallSession.tenant_id == tenant_id)).one(),
        },
    }


@router.get("/api/v1/agent/dashboard")
def agent_dashboard(_=Depends(require_roles("agent", "admin"))):
    return {"scope": "agent", "message": "座席工作台"}


def _spa_index():
    if INDEX_FILE.is_file():
        return FileResponse(INDEX_FILE, media_type="text/html")
    return HTMLResponse(
        status_code=503,
        content=(
            "<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>"
            "<title>AI 外呼平台</title></head><body><h1>前端资源尚未构建</h1>"
            "<p>请进入 frontend 目录执行 pnpm install && pnpm build。</p></body></html>"
        ),
    )


@router.get("/admin", include_in_schema=False)
@router.get("/admin/{path:path}", include_in_schema=False)
def admin_page(path: str = ""):
    return _spa_index()


@router.get("/agent", include_in_schema=False)
@router.get("/agent/{path:path}", include_in_schema=False)
def agent_page(path: str = ""):
    return _spa_index()


@router.get("/docs.html", include_in_schema=False)
def docs_page():
    return RedirectResponse(url="/docs")
