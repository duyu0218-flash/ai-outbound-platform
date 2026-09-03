from contextlib import asynccontextmanager
import secrets

from fastapi import Depends, FastAPI, Header, HTTPException, Request, WebSocket, status
from pydantic import BaseModel, Field
from fastapi.responses import PlainTextResponse

from .config import get_settings
from .drivers import make_driver
from .models import CallRequest, DialRequest, SpeakRequest
from .pipecat_pipeline import MediaPlaybackBusyError

settings = get_settings()


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings.validate_runtime()
    await driver.start()
    try:
        yield
    finally:
        await driver.stop()


app = FastAPI(title="AI Outbound Voice Gateway", version="0.1.0", lifespan=lifespan)
driver = make_driver(settings)
draining = False


async def require_service_token(request: Request, authorization: str | None = Header(default=None)) -> None:
    expected = settings.service_token.strip()
    real = settings.voice_gateway_driver.strip().lower() != "mock"
    if not expected and not real and settings.env.lower() not in {"prod", "production"}:
        return
    if not expected or not secrets.compare_digest(authorization or "", f"Bearer {expected}"):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid service token")
    if real and request.url.path.startswith("/v1/call/"):
        ledger = getattr(driver, "ledger", None)
        if ledger is None or not settings.voice_command_secret:
            raise HTTPException(503, "signed voice command enforcement is not configured")
        ledger.verify_command(settings.voice_command_secret, request.url.path, await request.body(), request.headers)


async def require_security_admin(request: Request, authorization: str | None = Header(default=None)):
    if settings.voice_gateway_driver == "mock":
        return await require_service_token(request, authorization)
    expected = settings.voice_security_admin_token
    if not expected or not secrets.compare_digest(authorization or "", f"Bearer {expected}"):
        raise HTTPException(403, "independent security administrator credential required")


def require_metrics_token(authorization: str | None = Header(default=None)) -> None:
    try:
        expected = settings.resolved_metrics_token()
    except RuntimeError:
        expected = ""
    if not expected or authorization != f"Bearer {expected}":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid metrics token")


@app.get("/health")
def health():
    pipecat_manager = getattr(driver, "pipecat_manager", None)
    return {
        "status": "ok",
        "draining": draining,
        "driver": settings.voice_gateway_driver,
        "voice_ai_pipeline": settings.voice_ai_pipeline,
        "media_protocol": settings.pipecat_media_protocol,
        "pipecat_version": settings.pipecat_version if settings.voice_ai_pipeline in {"pipecat", "hybrid"} else "",
        "pipecat_stt_provider": settings.pipecat_stt_provider
        if settings.voice_ai_pipeline in {"pipecat", "hybrid"}
        else "",
        "pipecat_active_sessions": len(pipecat_manager.sessions_by_call) if pipecat_manager else 0,
        "pipecat_max_active_sessions": settings.pipecat_max_active_sessions,
        "rtp_port_range": [settings.rtp_port_start, settings.rtp_port_end],
    }


@app.get("/readyz")
async def ready():
    if draining:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="voice gateway is draining")
    if not await driver.ready():
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="PBX driver is not ready")
    return {
        "status": "ready",
        "driver": settings.voice_gateway_driver,
        "voice_ai_pipeline": settings.voice_ai_pipeline,
    }


@app.get("/metrics", response_class=PlainTextResponse, dependencies=[Depends(require_metrics_token)], include_in_schema=False)
async def metrics() -> PlainTextResponse:
    pipecat_manager = getattr(driver, "pipecat_manager", None)
    calls = getattr(driver, "calls_by_id", {})
    ready_value = 1 if not draining and await driver.ready() else 0
    body = "\n".join([
        "# HELP ai_outbound_voice_gateway_ready Voice gateway downstream readiness.",
        "# TYPE ai_outbound_voice_gateway_ready gauge",
        f"ai_outbound_voice_gateway_ready {ready_value}",
        "# HELP ai_outbound_voice_gateway_draining Whether this process rejects new calls for shutdown.",
        "# TYPE ai_outbound_voice_gateway_draining gauge",
        f"ai_outbound_voice_gateway_draining {1 if draining else 0}",
        "# HELP ai_outbound_voice_gateway_calls Active call bindings in this process.",
        "# TYPE ai_outbound_voice_gateway_calls gauge",
        f"ai_outbound_voice_gateway_calls {len(calls)}",
        "# HELP ai_outbound_pipecat_sessions Active Pipecat sessions in this process.",
        "# TYPE ai_outbound_pipecat_sessions gauge",
        f"ai_outbound_pipecat_sessions {len(pipecat_manager.sessions_by_call) if pipecat_manager else 0}",
        "# HELP ai_outbound_pipecat_session_capacity Configured Pipecat session hard limit.",
        "# TYPE ai_outbound_pipecat_session_capacity gauge",
        f"ai_outbound_pipecat_session_capacity {settings.pipecat_max_active_sessions}",
        "",
    ])
    ledger = getattr(driver, "ledger", None)
    if ledger is not None:
        summary = ledger.summary()
        for name, value in summary.items():
            metric_type = "counter" if name == "rejected_commands" else "gauge"
            body += f"# TYPE ai_outbound_voice_security_{name} {metric_type}\nai_outbound_voice_security_{name} {int(value) if isinstance(value, bool) else value}\n"
    return PlainTextResponse(body, media_type="text/plain; version=0.0.4; charset=utf-8")


@app.websocket("/v1/pipecat/media/{session_token}")
async def pipecat_media(websocket: WebSocket, session_token: str):
    manager = getattr(driver, "pipecat_manager", None)
    if manager is None:
        await websocket.close(code=4404, reason="Pipecat pipeline is disabled")
        return
    await manager.run_websocket(websocket, session_token)


@app.post("/v1/call/dial", dependencies=[Depends(require_service_token)])
async def dial(payload: DialRequest):
    if draining:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="voice gateway is draining")
    return await driver.post("dial", payload.model_dump(mode="json"))


@app.post("/v1/admin/drain", dependencies=[Depends(require_security_admin)])
async def set_drain(enabled: bool = True):
    global draining
    draining = enabled
    pipecat_manager = getattr(driver, "pipecat_manager", None)
    calls = getattr(driver, "calls_by_id", {})
    return {
        "draining": draining,
        "active_calls": len(calls),
        "pipecat_active_sessions": len(pipecat_manager.sessions_by_call) if pipecat_manager else 0,
    }


class SecurityStopRequest(BaseModel):
    stopped: bool
    reason: str = Field(min_length=1, max_length=300)


@app.post("/v1/admin/security/stop", dependencies=[Depends(require_security_admin)])
async def security_stop(payload: SecurityStopRequest):
    ledger = getattr(driver, "ledger", None)
    if ledger is None:
        raise HTTPException(409, "security ledger requires real gateway driver")
    ledger.set_stopped(payload.stopped, payload.reason)
    return ledger.summary()


@app.get("/v1/admin/security", dependencies=[Depends(require_security_admin)])
async def security_status():
    ledger = getattr(driver, "ledger", None)
    if ledger is None:
        raise HTTPException(409, "security ledger requires real gateway driver")
    return ledger.summary()


async def _media_action(action: str, payload: CallRequest | SpeakRequest):
    try:
        return await driver.post(action, payload.model_dump())
    except MediaPlaybackBusyError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    except KeyError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="call or media session is no longer active") from exc
    except TimeoutError as exc:
        raise HTTPException(status_code=status.HTTP_504_GATEWAY_TIMEOUT, detail="media operation did not complete in time") from exc


@app.post("/v1/call/speak", dependencies=[Depends(require_service_token)])
async def speak(payload: SpeakRequest):
    return await _media_action("speak", payload)


@app.post("/v1/call/stop-speaking", dependencies=[Depends(require_service_token)])
async def stop_speaking(payload: CallRequest):
    return await _media_action("stop-speaking", payload)


@app.post("/v1/call/transfer", dependencies=[Depends(require_service_token)])
async def transfer(payload: CallRequest):
    return await _media_action("transfer", payload)


@app.post("/v1/call/hangup", dependencies=[Depends(require_service_token)])
async def hangup(payload: CallRequest):
    return await _media_action("hangup", payload)


@app.post("/v1/call/status", dependencies=[Depends(require_service_token)])
async def call_status(payload: CallRequest):
    return await _media_action("status", payload)
