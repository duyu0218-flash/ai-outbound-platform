from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import threading
import time
from datetime import datetime, timedelta
from typing import Any

import redis

from ..clock import utc_now
from ..config import get_settings


settings = get_settings()
_memory_lock = threading.Lock()
_memory_values: dict[str, tuple[float, str]] = {}


def agent_extension(agent_id: int) -> str:
    value = settings.webrtc_extension_template.format(agent_id=agent_id)
    if not value or any(char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-" for char in value):
        raise RuntimeError("WEBRTC_EXTENSION_TEMPLATE produced an invalid SIP extension")
    return value


def _redis_client():
    if not settings.redis_url:
        return None
    return redis.from_url(
        settings.redis_url,
        decode_responses=True,
        socket_connect_timeout=0.5,
        socket_timeout=0.5,
    )


def _set_value(key: str, value: str, ttl: int) -> None:
    try:
        client = _redis_client()
        if client is not None:
            client.setex(key, max(1, ttl), value)
            client.close()
            return
    except (OSError, redis.RedisError):
        if settings.env.lower() in {"prod", "production"}:
            raise RuntimeError("Redis is required for WebRTC media sessions")
    with _memory_lock:
        _memory_values[key] = (time.time() + max(1, ttl), value)


def _get_value(key: str) -> str | None:
    try:
        client = _redis_client()
        if client is not None:
            value = client.get(key)
            client.close()
            if value is not None:
                return str(value)
    except (OSError, redis.RedisError):
        if settings.env.lower() in {"prod", "production"}:
            raise RuntimeError("Redis is required for WebRTC media sessions")
    with _memory_lock:
        entry = _memory_values.get(key)
        if entry is None:
            return None
        expires_at, value = entry
        if expires_at <= time.time():
            _memory_values.pop(key, None)
            return None
        return value


def _delete_value(key: str) -> None:
    try:
        client = _redis_client()
        if client is not None:
            client.delete(key)
            client.close()
            return
    except (OSError, redis.RedisError):
        if settings.env.lower() in {"prod", "production"}:
            raise RuntimeError("Redis is required for WebRTC media sessions")
    with _memory_lock:
        _memory_values.pop(key, None)


def issue_sip_credential(*, tenant_id: int, agent_id: int) -> tuple[str, str, datetime]:
    extension = agent_extension(agent_id)
    password = secrets.token_urlsafe(24)
    ttl = max(60, int(settings.webrtc_sip_credential_ttl_sec))
    expires_at = utc_now() + timedelta(seconds=ttl)
    payload = json.dumps(
        {
            "tenant_id": tenant_id,
            "agent_id": agent_id,
            "extension": extension,
            "password": password,
            "expires_at": expires_at.isoformat(),
        },
        separators=(",", ":"),
    )
    _set_value(f"ai-outbound:sip-extension:{extension}", payload, ttl)
    return extension, password, expires_at


def get_sip_credential(extension: str) -> dict[str, Any] | None:
    raw = _get_value(f"ai-outbound:sip-extension:{extension}")
    if not raw:
        return None
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return None
    return payload if isinstance(payload, dict) else None


def build_ice_servers(*, agent_id: int) -> list[dict[str, Any]]:
    urls = [item.strip() for item in settings.turn_urls.split(",") if item.strip()]
    if not urls:
        return []
    stun_urls = [url for url in urls if url.lower().startswith("stun:")]
    turn_urls = [url for url in urls if url.lower().startswith(("turn:", "turns:"))]
    result: list[dict[str, Any]] = []
    if stun_urls:
        result.append({"urls": stun_urls})
    if turn_urls:
        if not settings.turn_shared_secret:
            if settings.env.lower() in {"prod", "production"}:
                raise RuntimeError("TURN_SHARED_SECRET is required when TURN URLs are configured")
            return result
        expires = int(time.time()) + max(60, int(settings.turn_credential_ttl_sec))
        username = f"{expires}:agent-{agent_id}"
        digest = hmac.new(
            settings.turn_shared_secret.encode("utf-8"),
            username.encode("utf-8"),
            hashlib.sha1,
        ).digest()
        result.append(
            {
                "urls": turn_urls,
                "username": username,
                "credential": base64.b64encode(digest).decode("ascii"),
            }
        )
    return result


def save_media_status(*, tenant_id: int, agent_id: int, payload: dict[str, Any]) -> dict[str, Any]:
    extension = agent_extension(agent_id)
    result = {
        **payload,
        "tenant_id": tenant_id,
        "user_id": agent_id,
        "extension": extension,
        "last_seen_at": utc_now().isoformat(),
    }
    ttl = max(30, int(settings.webrtc_media_status_ttl_sec))
    _set_value(
        f"ai-outbound:agent-media:{tenant_id}:{agent_id}",
        json.dumps(result, separators=(",", ":")),
        ttl,
    )
    return result


def get_media_status(*, tenant_id: int, agent_id: int) -> dict[str, Any] | None:
    raw = _get_value(f"ai-outbound:agent-media:{tenant_id}:{agent_id}")
    if not raw:
        return None
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return None
    return payload if isinstance(payload, dict) else None


def clear_media_status(*, tenant_id: int, agent_id: int) -> None:
    _delete_value(f"ai-outbound:agent-media:{tenant_id}:{agent_id}")


def media_is_registered(*, tenant_id: int, agent_id: int) -> bool:
    payload = get_media_status(tenant_id=tenant_id, agent_id=agent_id)
    return bool(
        payload
        and payload.get("registration_state") == "registered"
        and payload.get("microphone_permission") == "granted"
    )
