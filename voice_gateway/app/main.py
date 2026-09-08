from contextlib import asynccontextmanager
import asyncio
import secrets

from fastapi import Depends, FastAPI, Header, HTTPException, Request, WebSocket, status
from pydantic import BaseModel, Field
from fastapi.responses import PlainTextResponse, FileResponse

from .config import get_settings
from .drivers import make_driver
from .models import CallRequest, DialRequest, SpeakRequest
from .pipecat_pipeline import MediaPlaybackBusyError

settings = get_settings()


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings.validate_runtime()
    await driver.start()
    async def measure_lag():
        global event_loop_lag_sec
        loop = asyncio.get_running_loop()
        while True:
            started = loop.time()
            await asyncio.sleep(.1)
            event_loop_lag_sec = max(0, loop.time() - started - .1)
    lag_task = asyncio.create_task(measure_lag())
    try:
        yield
    finally:
        lag_task.cancel()
        await asyncio.gather(lag_task, return_exceptions=True)
        await driver.stop()


app = FastAPI(title="AI Outbound Voice Gateway", version="0.1.0", lifespan=lifespan)
driver = make_driver(settings)
draining = False
event_loop_lag_sec = 0.0
recording_download_slots = asyncio.Semaphore(2)


class RecordingResponse(FileResponse):
    async def __call__(self, scope, receive, send):
        from starlette.responses import JSONResponse
        if recording_download_slots.locked():
            return await JSONResponse({"error": "recording download capacity"}, status_code=503,
                                      headers={"Retry-After": "1"})(scope, receive, send)
        async with recording_download_slots:
            await super().__call__(scope, receive, send)


@app.get("/v1/recordings/{filename}")
async def recording_source(filename: str, expires: int, signature: str):
    from .recording_source import authorized_path
    path = await asyncio.to_thread(authorized_path, settings, filename, expires, signature)
    return RecordingResponse(path, media_type="audio/wav", headers={"Cache-Control": "no-store"})


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
        await asyncio.to_thread(ledger.verify_command, settings.voice_command_secret, request.url.path, await request.body(), request.headers)


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
    manager = getattr(driver, 'pipecat_manager', None)
    media_capacity = manager.admission_capacity() if hasattr(manager, 'admission_capacity') else settings.pipecat_max_active_sessions
    return {
        "status": "ready",
        "node_id": settings.voice_node_id,
        "call_capacity": min(settings.voice_max_concurrent, media_capacity)
        if settings.voice_ai_pipeline in {"pipecat", "hybrid"} else settings.voice_max_concurrent,
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
    sender = getattr(driver, 'sender', None)
    if sender is not None:
        for name, value, kind in (
            ('commit_batches_total', sender.writer.batches, 'counter'),
            ('committed_operations_total', sender.writer.operations, 'counter'),
            ('pending_commit_operations', sender.writer.queue.qsize(), 'gauge'),
            ('http_inflight', len(sender._inflight), 'gauge'),
        ):
            body += f'# TYPE ai_outbound_voice_callback_{name} {kind}\nai_outbound_voice_callback_{name} {value}\n'
    body += f"# TYPE ai_outbound_voice_event_loop_lag_seconds gauge\nai_outbound_voice_event_loop_lag_seconds {event_loop_lag_sec}\n"
    event_metrics = getattr(driver, "event_metrics", None)
    if event_metrics:
        for name, value in event_metrics().items():
            kind = "counter" if name.endswith("_total") else "gauge"
            body += f"# TYPE ai_outbound_voice_esl_{name} {kind}\nai_outbound_voice_esl_{name} {value}\n"
    if pipecat_manager is not None:
        for name, value in getattr(pipecat_manager, "metrics", {}).items():
            kind = "counter" if name.endswith(("_total", "_count", "_sum")) else "gauge"
            body += f"# TYPE ai_outbound_voice_{name} {kind}\nai_outbound_voice_{name} {value}\n"
    if ledger is not None:
        summary = await asyncio.to_thread(ledger.summary)
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
    await asyncio.to_thread(ledger.set_stopped, payload.stopped, payload.reason)
    return await asyncio.to_thread(ledger.summary)


@app.get("/v1/admin/security", dependencies=[Depends(require_security_admin)])
async def security_status():
    ledger = getattr(driver, "ledger", None)
    if ledger is None:
        raise HTTPException(409, "security ledger requires real gateway driver")
    return await asyncio.to_thread(ledger.summary)


@app.get("/v1/admin/capacity", dependencies=[Depends(require_security_admin)])
async def capacity_policy():
    """Effective approved limits only; no credentials or assumed PBX capacity."""
    from .security import routes
    policies = await asyncio.to_thread(routes, settings)
    return {"node_id": settings.voice_node_id,
            "call_capacity": min(settings.voice_max_concurrent, settings.pipecat_max_active_sessions),
            "cps": settings.voice_cps, "daily_calls": settings.voice_daily_call_limit,
            "hour_budget_minor": settings.voice_hour_budget_minor,
            "day_budget_minor": settings.voice_day_budget_minor,
            "routes": {key: {name: getattr(policy, name) for name in (
                "max_concurrent", "cps", "calls_per_day", "hour_budget_minor", "day_budget_minor",
                "max_duration_sec", "rate_minor_per_minute", "billing_multiplier")}
                for key, policy in policies.items()}}


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


class MediaEvent(BaseModel):
    worker_id: str = Field(max_length=128)
    epoch: str = Field(max_length=128)
    call_id: str = Field(max_length=128)
    session_id: str = Field(max_length=128)
    url: str = Field(max_length=2048)
    payload: dict


@app.post('/v1/internal/media-events', include_in_schema=False)
async def media_event(event: MediaEvent, authorization: str | None = Header(default=None)):
    if len(settings.media_rpc_token) < 32 or not secrets.compare_digest(
            authorization or '', 'Bearer ' + settings.media_rpc_token):
        raise HTTPException(401, 'media RPC credential required')
    manager = getattr(driver, 'pipecat_manager', None)
    if not hasattr(manager, 'validate_event'):
        raise HTTPException(503, 'media cluster is disabled')
    manager.validate_event(event)
    # The controller's FULL-synchronous journal is the acceptance boundary.
    # Worker retries retain the original event_id; backend dedup remains final.
    await driver.sender.post(event.url, event.payload)
    return {'accepted': True}
