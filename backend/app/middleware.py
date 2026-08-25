from __future__ import annotations

import asyncio
import logging
import time
import uuid
from collections import defaultdict, deque
from typing import Deque, Dict

from fastapi import Request
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse

from .config import get_settings

logger = logging.getLogger(__name__)


class RequestIDMiddleware(BaseHTTPMiddleware):
    """Attach and expose request id for tracing."""

    async def dispatch(self, request: Request, call_next):
        settings = get_settings()
        header_name = settings.request_id_header or "X-Request-ID"
        request_id = request.headers.get(header_name) or request.headers.get("X-Correlation-ID") or str(
            uuid.uuid4()
        )
        request.state.request_id = request_id
        response = await call_next(request)
        response.headers[header_name] = request_id
        response.headers.setdefault("X-Correlation-ID", request_id)
        return response


class TimeoutMiddleware(BaseHTTPMiddleware):
    """Reject requests exceeding configured timeout in production."""

    async def dispatch(self, request: Request, call_next):
        settings = get_settings()
        timeout_ms = int(settings.request_timeout_ms)
        if timeout_ms <= 0:
            return await call_next(request)

        timeout_sec = timeout_ms / 1000

        try:
            return await asyncio.wait_for(call_next(request), timeout=timeout_sec)
        except asyncio.TimeoutError:
            request_id = getattr(request.state, "request_id", None)
            logger.warning("request timeout path=%s request_id=%s", request.url.path, request_id)
            return JSONResponse(
                status_code=503,
                content={
                    "error": "timeout",
                    "message": f"request timeout after {timeout_sec}s",
                    "request_id": request_id,
                },
            )


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    """Add common production security headers."""

    async def dispatch(self, request: Request, call_next):
        response = await call_next(request)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("X-Frame-Options", "DENY")
        response.headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
        response.headers.setdefault("Permissions-Policy", "geolocation=(), microphone=()")
        response.headers.setdefault("Cache-Control", "no-store")
        return response


class LoggingMiddleware(BaseHTTPMiddleware):
    """Log request latency and request id."""

    async def dispatch(self, request: Request, call_next):
        start_ts = time.perf_counter()
        response = await call_next(request)
        elapsed_ms = (time.perf_counter() - start_ts) * 1000
        request_id = getattr(request.state, "request_id", None)
        logger.info(
            "method=%s path=%s status=%s cost_ms=%.2f request_id=%s",
            request.method,
            request.url.path,
            response.status_code,
            elapsed_ms,
            request_id,
        )
        return response


class RateLimitMiddleware(BaseHTTPMiddleware):
    """Simple in-memory sliding-window rate limiter for API paths.

    Notes: this is a single-instance safety valve. For multi-instance deployment,
    pair with Redis (recommended) in a follow-up upgrade.
    """

    def __init__(self, app, path_limits: Dict[str, int] | None = None):
        super().__init__(app)
        settings = get_settings()
        self.enabled = bool(settings.rate_limit_enabled)
        self.default_rpm = max(1, int(settings.rate_limit_default_rpm))
        self.auth_rpm = max(1, int(settings.rate_limit_auth_rpm))
        self.window_sec = max(1, int(settings.rate_limit_window_sec))
        self.path_limits = path_limits or {
            "/api/v1/auth/login": self.auth_rpm,
            "/api/v1/calls": self.default_rpm,
            "/api/v1/campaigns": self.default_rpm,
            "/api/v1/contacts": self.default_rpm,
            "/api/v1/script-templates": self.default_rpm,
        }
        self._hits: Dict[str, Deque[float]] = defaultdict(deque)
        self._lock = asyncio.Lock()

    async def _is_limit_ok(self, key: str, limit: int) -> bool:
        if not self.enabled:
            return True

        window = self.window_sec
        cutoff = time.time() - window

        async with self._lock:
            bucket = self._hits[key]
            while bucket and bucket[0] <= cutoff:
                bucket.popleft()

            if len(bucket) >= limit:
                return False

            bucket.append(time.time())
            return True

    async def dispatch(self, request: Request, call_next):
        settings = get_settings()
        if not self.enabled:
            return await call_next(request)

        path = request.url.path
        method = request.method.upper()

        # only protect API endpoints; leave static pages + docs untouched
        if not path.startswith("/api/"):
            return await call_next(request)

        limit = 0
        for prefix, rpm in self.path_limits.items():
            if path.startswith(prefix):
                limit = rpm
                break
        if limit == 0:
            limit = self.default_rpm

        # keep sensitive POST endpoints stricter than read-only endpoints
        if method in {"GET", "HEAD", "OPTIONS"}:
            limit = max(limit, self.default_rpm)

        client_host = request.client.host if request.client else "unknown"
        key = f"{client_host}:{path}"
        if not await self._is_limit_ok(key, limit):
            request_id = getattr(request.state, "request_id", None)
            headers = {
                "Retry-After": str(self.window_sec),
                "X-RateLimit-Limit": str(limit),
                "X-RateLimit-Window": str(self.window_sec),
            }
            if request_id:
                headers[settings.request_id_header or "X-Request-ID"] = request_id
            return JSONResponse(
                status_code=429,
                content={
                    "error": "rate_limited",
                    "message": "request rate limit exceeded",
                    "request_id": request_id,
                },
                headers=headers,
            )

        return await call_next(request)
