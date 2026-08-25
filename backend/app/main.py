from contextlib import asynccontextmanager
import logging
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.trustedhost import TrustedHostMiddleware
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import ValidationError

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
from .middleware import (
    LoggingMiddleware,
    RateLimitMiddleware,
    RequestIDMiddleware,
    SecurityHeadersMiddleware,
    TimeoutMiddleware,
)
from .models import Tenant
from .services.auth import ensure_demo_users
from .services.health import db_health_check

settings = get_settings()
setup_logging(settings.log_level)
logger = logging.getLogger(__name__)
config_origins = [x.strip() for x in settings.cors_allow_origins.split(",") if x.strip()]


@asynccontextmanager
async def lifespan(app: FastAPI):
    create_db_and_tables()
    yield


app = FastAPI(title=settings.app_name, version="1.0.0", lifespan=lifespan)


if settings.trusted_hosts:
    app.add_middleware(
        TrustedHostMiddleware,
        allowed_hosts=[h.strip() for h in settings.trusted_hosts.split(",") if h.strip()],
    )


@app.on_event("startup")
def _bootstrap_default_tenant():
    if settings.secret_key in {"", "change-me", "secret"}:
        logger.warning("secret_key is using default value in settings, update in production")
    if settings.jwt_secret in {"", "change-me", "jwt-change-me"}:
        logger.warning("jwt_secret is using default value in settings, update in production")
    if settings.api_key in {"", "dev-api-key"}:
        logger.warning("api_key looks like demo value, update in production")

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
app.add_middleware(RequestIDMiddleware)
app.add_middleware(TimeoutMiddleware)
app.add_middleware(LoggingMiddleware)
app.add_middleware(SecurityHeadersMiddleware)
app.add_middleware(
    RateLimitMiddleware,
)


@app.exception_handler(RequestValidationError)
@app.exception_handler(ValidationError)
def _validation_error_handler(request: Request, exc: Exception) -> JSONResponse:
    request_id = getattr(request.state, "request_id", None)
    return JSONResponse(
        status_code=400,
        content={
            "error": "validation_error",
            "message": str(exc),
            "request_id": request_id,
        },
    )


@app.exception_handler(HTTPException)
def _http_error_handler(request: Request, exc: HTTPException) -> JSONResponse:
    request_id = getattr(request.state, "request_id", None)
    return JSONResponse(
        status_code=exc.status_code,
        content={
            "error": "http_error",
            "message": exc.detail,
            "request_id": request_id,
        },
    )


@app.exception_handler(Exception)
def _unhandled_error_handler(request: Request, exc: Exception) -> JSONResponse:
    request_id = getattr(request.state, "request_id", None)
    return JSONResponse(
        status_code=500,
        content={
            "error": "internal_error",
            "message": str(exc),
            "request_id": request_id,
        },
    )


@app.get("/health")
def health():
    payload = {
        "status": "ok",
        "service": settings.app_name,
        "env": settings.env,
        "version": "1.0.0",
        "checks": {
            "db": db_health_check(),
        },
    }
    if all(value == "ok" for value in payload["checks"].values()):
        return payload
    return JSONResponse(status_code=503, content=payload)


@app.get("/healthz")
def healthz():
    return {"status": "ok"}


@app.get("/readyz")
def readyz() -> dict[str, Any]:
    payload = {
        "status": "ready",
        "checks": {
            "db": db_health_check(),
        },
    }
    if payload["checks"]["db"] != "ok":
        return JSONResponse(status_code=503, content=payload)
    return payload


app.include_router(calls_router)
app.include_router(campaigns_router)
app.include_router(contacts_router)
app.include_router(webhooks_router)
app.include_router(auth_router)
app.include_router(script_templates_router)
app.include_router(pages_router)
