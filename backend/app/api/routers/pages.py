from datetime import timedelta
from pathlib import Path

from fastapi import APIRouter, Depends, Query
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse
from sqlalchemy import case, func, or_
from sqlmodel import Session, select

from ...api.deps import require_role, require_roles
from ...clock import utc_now
from ...config import get_settings
from ...db import get_session
from ...models import CallAnalysis, CallSession, CallStatus, Campaign, Contact, ScriptTemplate, User


router = APIRouter(tags=["portal-pages"])
FRONTEND_DIR = Path(__file__).resolve().parents[2] / "static"
INDEX_FILE = FRONTEND_DIR / "index.html"
settings = get_settings()

REACHED_STATUSES = {
    CallStatus.ANSWERED,
    CallStatus.IN_AI,
    CallStatus.WAITING_HUMAN,
    CallStatus.HANDOFF_TRANSFERRING,
    CallStatus.IN_HUMAN,
    CallStatus.COMPLETED,
}
INTERESTED_RESULTS = {"interested", "qualified_lead", "positive_lead", "appointment", "converted"}
REACHED_RESULTS = INTERESTED_RESULTS | {"rejected", "completed"}


def _rate(numerator: int, denominator: int) -> float:
    return round(numerator * 100 / denominator, 1) if denominator else 0.0


@router.get("/api/v1/runtime")
def runtime_info():
    """Expose only non-sensitive flags needed to render the public login page."""

    return {
        "app_name": settings.app_name,
        "demo_users_enabled": settings.demo_users_enabled and settings.env.lower() not in {"prod", "production"},
    }


@router.get("/api/v1/admin/dashboard")
def admin_dashboard(
    days: int = Query(default=30, ge=1, le=365),
    current: User = Depends(require_role("admin")),
    session: Session = Depends(get_session),
):
    tenant_id = current.tenant_id
    since = utc_now() - timedelta(days=days)
    period_filter = (CallSession.tenant_id == tenant_id, CallSession.created_at >= since)
    period_calls = session.exec(select(func.count(CallSession.id)).where(*period_filter)).one()
    reached_calls = session.exec(
        select(func.count(func.distinct(CallSession.id)))
        .outerjoin(CallAnalysis, CallAnalysis.call_session_id == CallSession.id)
        .where(*period_filter, or_(CallSession.status.in_(REACHED_STATUSES), CallAnalysis.result_code.in_(REACHED_RESULTS)))
    ).one()
    completed_calls = session.exec(
        select(func.count(CallSession.id)).where(*period_filter, CallSession.status == CallStatus.COMPLETED)
    ).one()
    failed_calls = session.exec(
        select(func.count(CallSession.id)).where(
            *period_filter,
            CallSession.status.in_({CallStatus.FAILED, CallStatus.NO_ANSWER, CallStatus.BUSY, CallStatus.VOICEMAIL}),
        )
    ).one()
    analysis_filter = (CallAnalysis.tenant_id == tenant_id, CallSession.created_at >= since)
    analyzed_calls = session.exec(
        select(func.count(CallAnalysis.id)).join(CallSession, CallSession.id == CallAnalysis.call_session_id).where(*analysis_filter)
    ).one()
    interested_calls = session.exec(
        select(func.count(CallAnalysis.id))
        .join(CallSession, CallSession.id == CallAnalysis.call_session_id)
        .where(*analysis_filter, CallAnalysis.result_code.in_(INTERESTED_RESULTS))
    ).one()
    pending_reviews = session.exec(
        select(func.count(CallAnalysis.id))
        .join(CallSession, CallSession.id == CallAnalysis.call_session_id)
        .where(*analysis_filter, CallAnalysis.review_state != "reviewed")
    ).one()
    average_qa_score = session.exec(
        select(func.avg(CallAnalysis.qa_score))
        .join(CallSession, CallSession.id == CallAnalysis.call_session_id)
        .where(*analysis_filter)
    ).one()

    campaign_rows = session.exec(
        select(
            CallSession.campaign_id,
            func.count(CallSession.id),
            func.sum(case((or_(CallSession.status.in_(REACHED_STATUSES), CallAnalysis.result_code.in_(REACHED_RESULTS)), 1), else_=0)),
            func.sum(case((CallAnalysis.result_code.in_(INTERESTED_RESULTS), 1), else_=0)),
        )
        .outerjoin(CallAnalysis, CallAnalysis.call_session_id == CallSession.id)
        .where(*period_filter, CallSession.campaign_id.is_not(None))
        .group_by(CallSession.campaign_id)
        .order_by(func.count(CallSession.id).desc())
        .limit(8)
    ).all()
    campaign_performance = []
    for campaign_id, call_count, reached_count, interested_count in campaign_rows:
        campaign = session.get(Campaign, campaign_id)
        if campaign is None or campaign.tenant_id != tenant_id:
            continue
        reached = int(reached_count or 0)
        interested = int(interested_count or 0)
        total = int(call_count or 0)
        campaign_performance.append(
            {
                "campaign_id": campaign_id,
                "name": campaign.name,
                "calls": total,
                "reached": reached,
                "interested": interested,
                "reach_rate": _rate(reached, total),
                "interest_rate": _rate(interested, reached),
            }
        )

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
        "period": {
            "days": days,
            "since": since,
            "calls": period_calls,
            "reached": reached_calls,
            "completed": completed_calls,
            "failed": failed_calls,
            "analyzed": analyzed_calls,
            "interested": interested_calls,
            "pending_reviews": pending_reviews,
            "average_qa_score": round(float(average_qa_score or 0), 1),
            "reach_rate": _rate(reached_calls, period_calls),
            "interest_rate": _rate(interested_calls, reached_calls),
            "completion_rate": _rate(completed_calls, period_calls),
        },
        "campaign_performance": campaign_performance,
        "metric_definitions": {
            "reached": "当前状态为已接听及后续阶段，或分析/人工复核结果表明已完成有效沟通的通话",
            "interested": "自动分析或人工复核结果为 interested、qualified_lead、positive_lead、appointment 或 converted",
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
