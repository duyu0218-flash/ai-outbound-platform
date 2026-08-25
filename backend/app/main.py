from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api.routers import (
    auth_router,
    calls_router,
    campaigns_router,
    contacts_router,
    script_templates_router,
    pages_router,
    webhooks_router,
)
from .config import get_settings, setup_logging
from .db import create_db_and_tables, get_session
from .models import Tenant
from .services.auth import ensure_demo_users

settings = get_settings()
setup_logging(settings.log_level)
config_origins = [x.strip() for x in settings.cors_allow_origins.split(",") if x.strip()]


@asynccontextmanager
async def lifespan(app: FastAPI):
    create_db_and_tables()
    yield


app = FastAPI(title=settings.app_name, version="1.0.0", lifespan=lifespan)


@app.on_event("startup")
def _bootstrap_default_tenant():
    with get_session() as session:
        existing = session.get(Tenant, settings.default_tenant_id)
        if not existing:
            session.add(
                Tenant(
                    id=settings.default_tenant_id,
                    name="Default Tenant",
                    code="default",
                    enabled=True,
                )
            )
            session.commit()
        ensure_demo_users(session)


app.add_middleware(
    CORSMiddleware,
    allow_origins=config_origins or ["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": settings.app_name,
        "env": settings.env,
        "version": "1.0.0",
    }


@app.get("/healthz")
def healthz():
    return {"status": "ok"}


app.include_router(calls_router)
app.include_router(campaigns_router)
app.include_router(contacts_router)
app.include_router(webhooks_router)
app.include_router(auth_router)
app.include_router(script_templates_router)
app.include_router(pages_router)
