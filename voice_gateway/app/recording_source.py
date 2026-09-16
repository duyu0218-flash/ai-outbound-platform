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


def disk_status(settings):
    import shutil
    reserve = settings.voice_recording_reserve_bytes
    if reserve <= 0:
        return {'enabled': False, 'safe': True}
    try:
        usage = shutil.disk_usage(settings.freeswitch_recording_dir)
    except OSError:
        return {'enabled': True, 'safe': False, 'error': 'source disk unavailable'}
    return {'enabled': True, 'safe': usage.free >= reserve,
            'free_bytes': usage.free, 'reserve_bytes': reserve}


def _source_path(settings, filename):
    if not re.fullmatch(r'[A-Za-z0-9_-][A-Za-z0-9_.-]{0,240}\.wav', filename):
        raise HTTPException(400, 'invalid source filename')
    root = Path(settings.freeswitch_recording_dir).resolve()
    path = root / filename
    if path.is_symlink() or path.resolve().parent != root:
        raise HTTPException(403, 'source must be a regular owner-node recording')
    return path


def mark_complete(settings, filename):
    """Called only after PBX channel termination, never on a download request."""
    import json
    import os
    path = _source_path(settings, filename)
    if not path.is_file():
        return
    marker = path.with_suffix('.wav.complete')
    if marker.exists():
        return
    data = json.dumps({'completed_at': time.time(), 'size_bytes': path.stat().st_size})
    # Unique filename belongs to one PBX attempt. Crash before marker means retain.
    with marker.open('x') as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


def reclaim(settings, filename, checksum_sha256, size_bytes):
    """Independently authenticated upload role asserts verified remote readability.

    A retained receipt makes lost cleanup ACKs retryable. No TTL grants deletion.
    """
    import fcntl
    import json
    import os
    path = _source_path(settings, filename)
    marker = path.with_suffix('.wav.complete')
    if not marker.is_file() or marker.is_symlink():
        raise HTTPException(409, 'recording has no PBX completion receipt')
    with marker.open('r+') as receipt:
        fcntl.flock(receipt, fcntl.LOCK_EX)
        data = json.load(receipt)
        if not path.exists():
            if data.get('checksum_sha256') == checksum_sha256 and data.get('size_bytes') == size_bytes:
                return {'deleted': True}
            raise HTTPException(409, 'missing source without matching deletion receipt')
        if time.time() - data['completed_at'] < settings.voice_recording_retention_sec:
            raise HTTPException(409, 'source retention period has not elapsed')
        with path.open('rb') as source:
            stat = os.fstat(source.fileno())
            checksum = hashlib.file_digest(source, 'sha256').hexdigest()
            if stat.st_size != size_bytes or checksum != checksum_sha256 or data['size_bytes'] != size_bytes:
                raise HTTPException(409, 'source differs from verified object')
        data['checksum_sha256'] = checksum
        receipt.seek(0)
        json.dump(data, receipt)
        receipt.truncate()
        receipt.flush()
        os.fsync(receipt.fileno())
        if path.stat().st_ino != stat.st_ino or path.stat().st_mtime_ns != stat.st_mtime_ns:
            raise HTTPException(409, 'source changed during verification')
        path.unlink()
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
        return {'deleted': True}
