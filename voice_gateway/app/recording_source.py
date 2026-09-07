"""Owner-node recording downloads, authenticated by short-lived signed URLs."""
import hashlib
import hmac
import re
import time
from pathlib import Path
from urllib.parse import urlencode

from fastapi import HTTPException


def signature(settings, filename, expires):
    if not settings.voice_command_secret:
        raise HTTPException(503, "recording signing key unavailable")
    message = f"recording-source:{settings.voice_node_id}:{filename}:{expires}".encode()
    return hmac.new(settings.voice_command_secret.encode(), message, hashlib.sha256).hexdigest()


def recording_url(settings, filename):
    expires = int(time.time()) + 86400
    query = urlencode({"expires": expires, "signature": signature(settings, filename, expires)})
    return settings.voice_recording_source_base_url.rstrip('/') + f"/v1/recordings/{filename}?{query}"


def authorized_path(settings, filename, expires, supplied):
    now = int(time.time())
    if (not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_.-]{0,240}\.wav", filename)
            or not now < expires <= now + 86400
            or not hmac.compare_digest(signature(settings, filename, expires), supplied)):
        raise HTTPException(403, "invalid or expired recording download permit")
    root = Path(settings.freeswitch_recording_dir).resolve()
    target = (root / filename).resolve()
    if target.parent != root or not target.is_file():
        raise HTTPException(404, "recording unavailable on owner node")
    return target
