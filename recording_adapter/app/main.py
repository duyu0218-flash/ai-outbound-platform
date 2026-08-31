from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, status
from fastapi.responses import PlainTextResponse
from prometheus_client import CollectorRegistry, Counter, generate_latest

from .config import get_settings
from .models import (
    RecordingDeleteRequest,
    RecordingDeleteResponse,
    RecordingIngestRequest,
    RecordingIngestResponse,
)
from .storage import (
    RecordingDownloadError,
    RecordingObjectStorage,
    RecordingSourceRejected,
    RecordingStorageError,
)


settings = get_settings()
storage = RecordingObjectStorage(settings)
registry = CollectorRegistry()
ingest_total = Counter(
    "ai_outbound_recording_adapter_ingest_total",
    "Managed recording ingestion attempts.",
    ["result"],
    registry=registry,
)
delete_total = Counter(
    "ai_outbound_recording_adapter_delete_total",
    "Managed recording deletion attempts.",
    ["result"],
    registry=registry,
)


@asynccontextmanager
async def lifespan(_: FastAPI):
    settings.validate_runtime()
    storage.ensure_bucket()
    yield


app = FastAPI(title="AI Outbound Recording Adapter", version="0.1.0", lifespan=lifespan)


def require_service_token(authorization: str | None = Header(default=None)) -> None:
    try:
        expected = settings.resolved_service_token()
    except RuntimeError:
        expected = ""
    if not expected or authorization != f"Bearer {expected}":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid service token")


def require_metrics_token(authorization: str | None = Header(default=None)) -> None:
    try:
        expected = settings.resolved_metrics_token()
    except RuntimeError:
        expected = ""
    if not expected or authorization != f"Bearer {expected}":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid metrics token")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/readyz")
def ready() -> dict[str, str]:
    if not storage.ready():
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="recording bucket is unavailable")
    return {"status": "ready", "bucket": settings.s3_bucket}


@app.get("/metrics", response_class=PlainTextResponse, dependencies=[Depends(require_metrics_token)], include_in_schema=False)
def metrics() -> PlainTextResponse:
    return PlainTextResponse(generate_latest(registry), media_type="text/plain; version=0.0.4; charset=utf-8")


@app.post(
    "/v1/recordings/ingest",
    response_model=RecordingIngestResponse,
    dependencies=[Depends(require_service_token)],
)
def ingest_recording(payload: RecordingIngestRequest) -> RecordingIngestResponse:
    try:
        result = storage.ingest(payload)
    except RecordingSourceRejected as exc:
        ingest_total.labels(result="rejected").inc()
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    except RecordingDownloadError as exc:
        ingest_total.labels(result="download_failed").inc()
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc)) from exc
    except RecordingStorageError as exc:
        ingest_total.labels(result="storage_failed").inc()
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from exc
    ingest_total.labels(result="success").inc()
    return RecordingIngestResponse.model_validate(result)


@app.post(
    "/v1/recordings/delete",
    response_model=RecordingDeleteResponse,
    dependencies=[Depends(require_service_token)],
)
def delete_recording(payload: RecordingDeleteRequest) -> RecordingDeleteResponse:
    try:
        deleted = storage.delete(payload)
    except RecordingStorageError as exc:
        delete_total.labels(result="failed").inc()
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from exc
    delete_total.labels(result="success").inc()
    return RecordingDeleteResponse(deleted=deleted)
