from contextlib import asynccontextmanager

from fastapi import FastAPI

from .config import get_settings
from .drivers import make_driver
from .models import CallRequest, DialRequest, SpeakRequest

settings = get_settings()


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings.validate_runtime()
    yield


app = FastAPI(title="AI Outbound Voice Gateway", version="0.1.0", lifespan=lifespan)
driver = make_driver(settings)


@app.get("/health")
@app.get("/readyz")
def health():
    return {"status": "ok", "driver": settings.voice_gateway_driver, "rtp_port_range": [settings.rtp_port_start, settings.rtp_port_end]}


@app.post("/v1/call/dial")
async def dial(payload: DialRequest):
    return await driver.post("dial", payload.model_dump(mode="json"))


@app.post("/v1/call/speak")
async def speak(payload: SpeakRequest):
    return await driver.post("speak", payload.model_dump())


@app.post("/v1/call/stop-speaking")
async def stop_speaking(payload: CallRequest):
    return await driver.post("stop-speaking", payload.model_dump())


@app.post("/v1/call/transfer")
async def transfer(payload: CallRequest):
    return await driver.post("transfer", payload.model_dump())


@app.post("/v1/call/hangup")
async def hangup(payload: CallRequest):
    return await driver.post("hangup", payload.model_dump())
