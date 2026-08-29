from __future__ import annotations

import httpx

from ..config import get_settings
from ..models import RecordingAsset

settings = get_settings()


class RecordingDeletionError(RuntimeError):
    """Raised when a recording cannot be confirmed as deleted remotely."""


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
