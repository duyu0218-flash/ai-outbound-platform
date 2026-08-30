from contextlib import asynccontextmanager
import asyncio
import json
import logging
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.trustedhost import TrustedHostMiddleware
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import ValidationError
from sqlalchemy import text
from sqlmodel import Session

from .api.routers import (
    admin_management_router,
    auth_router,
    calls_router,
    campaigns_router,
    contacts_router,
    script_templates_router,
    script_flows_router,
    pages_router,
    webhooks_router,
    voice_operations_router,
    webrtc_router,
)
from .config import get_settings, setup_logging
from .db import create_db_and_tables, engine, session_scope
from .middleware import (
    LoggingMiddleware,
    RateLimitMiddleware,
    RequestIDMiddleware,
    SecurityHeadersMiddleware,
    TimeoutMiddleware,
)
from .models import Tenant
from .services.auth import ensure_demo_users
from .services.health import ai_agent_health_check, db_health_check, redis_health_check, telephony_http_health_check, tenant_telephony_health_check
from .services.call_service import run_retry_scheduler

settings = get_settings()
setup_logging(settings.log_level)
logger = logging.getLogger(__name__)
config_origins = [x.strip() for x in settings.cors_allow_origins.split(",") if x.strip()]
PROD_ALLOWED_ENVS = {"prod", "production"}


@asynccontextmanager
async def lifespan(app: FastAPI):
    create_db_and_tables()
    _bootstrap_default_tenant()
    retry_stop_event = asyncio.Event()
    retry_task = (
        asyncio.create_task(run_retry_scheduler(retry_stop_event))
        if settings.scheduler_enabled
        else None
    )
    try:
        yield
    finally:
        retry_stop_event.set()
        if retry_task is not None:
            retry_task.cancel()
            try:
                await retry_task
            except asyncio.CancelledError:
                pass


app = FastAPI(title=settings.app_name, version="1.0.0", lifespan=lifespan)
frontend_dir = Path(__file__).resolve().parent / "static"
app.mount("/assets", StaticFiles(directory=frontend_dir / "assets", check_dir=False), name="frontend-assets")


if settings.trusted_hosts:
    app.add_middleware(
        TrustedHostMiddleware,
        allowed_hosts=[h.strip() for h in settings.trusted_hosts.split(",") if h.strip()],
    )


def _is_prod_env() -> bool:
    return settings.env.lower() in PROD_ALLOWED_ENVS


def _validate_production_runtime() -> None:
    if not _is_prod_env():
        return

    issues = []

    def weak_secret(value: str | None, minimum: int) -> bool:
        normalized = str(value or "").strip().lower()
        placeholder_parts = ("change-me", "replace-me", "example", "dev-", "your-")
        return len(normalized) < minimum or any(part in normalized for part in placeholder_parts)

    if weak_secret(settings.secret_key, 32):
        issues.append("SECRET_KEY")
    if weak_secret(settings.jwt_secret, 32) or settings.jwt_secret == settings.secret_key:
        issues.append("JWT_SECRET")
    tenant_api_keys_valid = False
    if settings.tenant_api_keys_json.strip():
        try:
            tenant_key_map = json.loads(settings.tenant_api_keys_json)
            tenant_api_keys_valid = bool(tenant_key_map) and all(
                str(key).isdigit() and int(key) > 0 and not weak_secret(str(value), 24)
                for key, value in tenant_key_map.items()
            )
        except (TypeError, ValueError, json.JSONDecodeError):
            tenant_api_keys_valid = False
        if not tenant_api_keys_valid:
            issues.append("TENANT_API_KEYS_JSON")
    if weak_secret(settings.api_key, 24) and not tenant_api_keys_valid:
        issues.append("API_KEY")
    if settings.cors_allow_origins.strip() == "*":
        issues.append("CORS_ALLOW_ORIGINS=*")
    if not settings.trusted_hosts.strip():
        issues.append("TRUSTED_HOSTS")
    if settings.demo_users_enabled:
        issues.append("DEMO_USERS_ENABLED=true")
    if not settings.telephony_webhook_token.strip():
        issues.append("TELEPHONY_WEBHOOK_TOKEN")
    if weak_secret(settings.telephony_service_token, 24):
        issues.append("TELEPHONY_SERVICE_TOKEN")
    if weak_secret(settings.ai_agent_service_token, 24):
        issues.append("AI_AGENT_SERVICE_TOKEN")
    if settings.ai_agent_service_token and settings.ai_agent_service_token == settings.telephony_service_token:
        issues.append("internal service tokens must be different")
    if settings.sms_provider.strip().lower() == "http":
        if not settings.sms_callback_url.strip():
            issues.append("SMS_CALLBACK_URL")
        if not settings.sms_webhook_token.strip():
            issues.append("SMS_WEBHOOK_TOKEN")
    if settings.database_url.startswith("sqlite"):
        issues.append("DATABASE_URL=sqlite")
    if "replace-db-password" in settings.database_url.lower():
        issues.append("DATABASE_URL placeholder password")
    if not settings.redis_url.strip():
        issues.append("REDIS_URL")
    else:
        redis_password = urlparse(settings.redis_url).password or ""
        if weak_secret(redis_password, 16):
            issues.append("REDIS_URL insecure password")
    if (settings.telephony_provider or "mock").strip().lower() == "mock":
        issues.append("TELEPHONY_PROVIDER=mock")
    if settings.recording_retention_days > 0:
        if not settings.recording_delete_endpoint.strip():
            issues.append("RECORDING_DELETE_ENDPOINT")
        if weak_secret(settings.recording_delete_service_token, 24):
            issues.append("RECORDING_DELETE_SERVICE_TOKEN")
    if settings.webrtc_enabled:
        if not settings.webrtc_wss_url.startswith("wss://"):
            issues.append("WEBRTC_WSS_URL")
        if not settings.webrtc_sip_domain.strip():
            issues.append("WEBRTC_SIP_DOMAIN")
        if not settings.turn_urls.strip():
            issues.append("TURN_URLS")
        if not settings.turn_shared_secret.strip() or len(settings.turn_shared_secret) < 24:
            issues.append("TURN_SHARED_SECRET")
        if not settings.freeswitch_directory_token.strip() or len(settings.freeswitch_directory_token) < 24:
            issues.append("FREESWITCH_DIRECTORY_TOKEN")

    if issues:
        raise RuntimeError(
            "Production runtime validation failed, invalid config: "
            + ", ".join(issues)
            + "。请替换为生产安全值后重启服务。"
        )


def _bootstrap_default_tenant_data(session: Session) -> None:
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
    if settings.demo_users_enabled:
        ensure_demo_users(session)


def _bootstrap_default_tenant() -> None:
    if settings.secret_key in {"", "change-me", "secret"}:
        logger.warning("secret_key is using default value in settings, update in production")
    if settings.jwt_secret in {"", "change-me", "jwt-change-me"}:
        logger.warning("jwt_secret is using default value in settings, update in production")
    if settings.api_key in {"", "dev-api-key"}:
        logger.warning("api_key looks like demo value, update in production")
    _validate_production_runtime()

    if engine.dialect.name != "postgresql":
        with session_scope() as session:
            _bootstrap_default_tenant_data(session)
        return

    # Every uvicorn worker runs the lifespan hook. Serialize default tenant and
    # demo-user seeding so two fresh workers cannot insert the same unique
    # username concurrently and terminate the whole parent process.
    with engine.connect() as lock_connection:
        lock_connection.execute(text("SELECT pg_advisory_lock(hashtext('ai-outbound-bootstrap-data'))"))
        try:
            with Session(lock_connection) as session:
                _bootstrap_default_tenant_data(session)
        finally:
            lock_connection.execute(text("SELECT pg_advisory_unlock(hashtext('ai-outbound-bootstrap-data'))"))
            lock_connection.commit()


app.add_middleware(
    CORSMiddleware,
    allow_origins=config_origins or ["*"],
    allow_credentials="*" not in (config_origins or ["*"]),
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
    logger.error(
        "unhandled request error request_id=%s",
        request_id,
        exc_info=(type(exc), exc, exc.__traceback__),
    )
    return JSONResponse(
        status_code=500,
        content={
            "error": "internal_error",
            "message": str(exc) if settings.debug and not _is_prod_env() else "internal server error",
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
            "redis": redis_health_check(),
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
    if (settings.telephony_provider or "mock").strip().lower() == "tenant":
        with session_scope() as session:
            telephony_check = tenant_telephony_health_check(session, settings.default_tenant_id)
    else:
        telephony_check = telephony_http_health_check()
    checks = {
        "db": db_health_check(),
        "redis": redis_health_check(),
        "ai_agent": ai_agent_health_check(),
        "telephony": telephony_check,
    }
    payload = {
        "status": "ready",
        "checks": checks,
    }
    if not all(value == "ok" for value in payload["checks"].values()):
        return JSONResponse(status_code=503, content=payload)
    return payload


app.include_router(calls_router)
app.include_router(campaigns_router)
app.include_router(contacts_router)
app.include_router(webhooks_router)
app.include_router(auth_router)
app.include_router(script_templates_router)
app.include_router(script_flows_router)
app.include_router(admin_management_router)
app.include_router(voice_operations_router)
app.include_router(webrtc_router)
app.include_router(pages_router)
