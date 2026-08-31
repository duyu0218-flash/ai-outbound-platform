from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, WebSocket, status

from .config import get_settings
from .drivers import make_driver
from .models import CallRequest, DialRequest, SpeakRequest

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


def require_service_token(authorization: str | None = Header(default=None)) -> None:
    expected = settings.service_token.strip()
    if not expected and settings.env.lower() not in {"prod", "production"}:
        return
    if not expected or authorization != f"Bearer {expected}":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid service token")


@app.get("/health")
def health():
    pipecat_manager = getattr(driver, "pipecat_manager", None)
    return {
        "status": "ok",
        "driver": settings.voice_gateway_driver,
        "voice_ai_pipeline": settings.voice_ai_pipeline,
        "pipecat_version": settings.pipecat_version if settings.voice_ai_pipeline == "pipecat" else "",
        "pipecat_active_sessions": len(pipecat_manager.sessions_by_call) if pipecat_manager else 0,
        "rtp_port_range": [settings.rtp_port_start, settings.rtp_port_end],
    }


@app.get("/readyz")
async def ready():
    if not await driver.ready():
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="PBX driver is not ready")
    return {
        "status": "ready",
        "driver": settings.voice_gateway_driver,
        "voice_ai_pipeline": settings.voice_ai_pipeline,
    }


@app.websocket("/v1/pipecat/media/{session_token}")
async def pipecat_media(websocket: WebSocket, session_token: str):
    manager = getattr(driver, "pipecat_manager", None)
    if manager is None:
        await websocket.close(code=4404, reason="Pipecat pipeline is disabled")
        return
    await manager.run_websocket(websocket, session_token)


@app.post("/v1/call/dial", dependencies=[Depends(require_service_token)])
async def dial(payload: DialRequest):
    return await driver.post("dial", payload.model_dump(mode="json"))


@app.post("/v1/call/speak", dependencies=[Depends(require_service_token)])
async def speak(payload: SpeakRequest):
    return await driver.post("speak", payload.model_dump())


@app.post("/v1/call/stop-speaking", dependencies=[Depends(require_service_token)])
async def stop_speaking(payload: CallRequest):
    return await driver.post("stop-speaking", payload.model_dump())


@app.post("/v1/call/transfer", dependencies=[Depends(require_service_token)])
async def transfer(payload: CallRequest):
    return await driver.post("transfer", payload.model_dump())


@app.post("/v1/call/hangup", dependencies=[Depends(require_service_token)])
async def hangup(payload: CallRequest):
    return await driver.post("hangup", payload.model_dump())
