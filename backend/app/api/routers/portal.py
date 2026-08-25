from fastapi import APIRouter, Depends

from ...api.deps import current_user, require_any_role, require_role
from ...models import User

admin_router = APIRouter(
    prefix="/api/v1/admin",
    tags=["portal-admin"],
    dependencies=[Depends(require_role("admin"))],
)


agent_router = APIRouter(
    prefix="/api/v1/agent",
    tags=["portal-agent"],
    dependencies=[Depends(require_any_role("admin", "agent"))],
)


@admin_router.get("/dashboard")
def admin_dashboard(current: User = Depends(current_user)):
    return {
        "area": "admin_dashboard",
        "role": current.role,
        "username": current.username,
        "tenant_id": current.tenant_id,
        "message": "管理员端鉴权通过，可继续接入管理类功能 API",
    }


@agent_router.get("/dashboard")
def agent_dashboard(current: User = Depends(current_user)):
    return {
        "area": "agent_workbench",
        "role": current.role,
        "username": current.username,
        "tenant_id": current.tenant_id,
        "message": "座席端鉴权通过，可继续接入座席类功能 API",
    }

