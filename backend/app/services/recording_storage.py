from __future__ import annotations

import httpx

from ..config import get_settings
from ..models import RecordingAsset

settings = get_settings()


class RecordingDeletionError(RuntimeError):
    """Raised when a recording cannot be confirmed as deleted remotely."""


class RecordingIngestError(RuntimeError):
    """Raised when a provider recording cannot be copied to managed storage."""


def ingest_recording_asset(asset: RecordingAsset) -> dict[str, str]:
    if asset.storage_uri:
        return {"storage_uri": asset.storage_uri, "checksum_sha256": asset.checksum_sha256 or ""}
    if not asset.provider_url:
        raise RecordingIngestError("recording provider URL is missing")
    endpoint = settings.recording_ingest_endpoint.strip()
    if not endpoint:
        raise RecordingIngestError("RECORDING_INGEST_ENDPOINT is not configured")
    headers = {"Content-Type": "application/json"}
    if settings.recording_ingest_service_token.strip():
        headers["Authorization"] = f"Bearer {settings.recording_ingest_service_token.strip()}"
    payload = {
        "recording_asset_id": asset.id,
        "tenant_id": asset.tenant_id,
        "call_id": str(asset.call_session_id),
        "provider_recording_id": asset.provider_recording_id,
        "provider_url": asset.provider_url,
        "media_format": asset.media_format,
        "channel_count": asset.channel_count,
    }
    try:
        with httpx.Client(timeout=max(1, settings.recording_ingest_timeout_sec)) as client:
            response = client.post(endpoint, headers=headers, json=payload)
            response.raise_for_status()
            result = response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise RecordingIngestError(f"recording ingestion failed: {exc}") from exc
    storage_uri = str(result.get("storage_uri") or "") if isinstance(result, dict) else ""
    checksum = str(result.get("checksum_sha256") or "") if isinstance(result, dict) else ""
    if not storage_uri:
        raise RecordingIngestError("recording adapter did not return storage_uri")
    if checksum and (len(checksum) != 64 or any(ch not in "0123456789abcdefABCDEF" for ch in checksum)):
        raise RecordingIngestError("recording adapter returned an invalid SHA-256 checksum")
    return {"storage_uri": storage_uri, "checksum_sha256": checksum.lower()}


def delete_recording_asset(asset: RecordingAsset) -> None:
    """Delete an external recording through the configured storage adapter.

    The database location is deliberately preserved until this function returns
    successfully.  A download URL is never used as a DELETE target because it is
    commonly a signed, read-only URL rather than a provider management endpoint.
    """

    if not asset.provider_url and not asset.storage_uri and not asset.provider_recording_id:
        return

    endpoint = settings.recording_delete_endpoint.strip()
    if not endpoint:
        raise RecordingDeletionError("RECORDING_DELETE_ENDPOINT is not configured")

    headers = {"Content-Type": "application/json"}
    if settings.recording_delete_service_token.strip():
        headers["Authorization"] = f"Bearer {settings.recording_delete_service_token.strip()}"
    payload = {
        "recording_asset_id": asset.id,
        "tenant_id": asset.tenant_id,
        "call_id": str(asset.call_session_id),
        "provider_recording_id": asset.provider_recording_id,
        "provider_url": asset.provider_url,
        "storage_uri": asset.storage_uri,
    }
    try:
        with httpx.Client(timeout=max(1, settings.recording_delete_timeout_sec)) as client:
            response = client.post(endpoint, headers=headers, json=payload)
            response.raise_for_status()
            if response.content and "application/json" in response.headers.get("content-type", ""):
                result = response.json()
                if isinstance(result, dict) and result.get("deleted") is False:
                    raise RecordingDeletionError("recording adapter did not confirm deletion")
    except RecordingDeletionError:
        raise
    except (httpx.HTTPError, ValueError) as exc:
        raise RecordingDeletionError(f"recording deletion failed: {exc}") from exc
