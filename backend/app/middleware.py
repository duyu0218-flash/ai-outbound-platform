from __future__ import annotations

import asyncio
import logging
import time
import uuid
from collections import defaultdict, deque
from typing import Deque, Dict

from fastapi import Request
from redis import asyncio as redis_async
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
                status_code=504,
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
    """Distributed rate limiting for API paths with in-memory fallback."""

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
        self._key_prefix = "ai-outbound:rate-limit"
        self._redis = self._connect_redis_client(settings.redis_url)
        if self._redis is None:
            logger.warning("rate limit uses in-memory fallback (redis unavailable or not configured)")
        else:
            logger.info("rate limit uses redis backend")

    def _connect_redis_client(self, redis_url: str):
        if not redis_url:
            return None
        try:
            client = redis_async.from_url(redis_url, decode_responses=True)
            # lazy-check: ensure endpoint is reachable at startup
            return client
        except Exception:
            logger.exception("failed to initialize redis client, fallback to memory limiter")
            return None

    async def _is_limit_ok(self, key: str, limit: int) -> bool:
        if not self.enabled:
            return True

        if self._redis is not None:
            try:
                return await self._is_limit_ok_redis(key, limit)
            except Exception:
                logger.exception("redis rate-limit unavailable, fallback to memory limiter")
                self._redis = None
                return await self._is_limit_ok_memory(key, limit)
        return await self._is_limit_ok_memory(key, limit)

    async def _is_limit_ok_memory(self, key: str, limit: int) -> bool:
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

    async def _is_limit_ok_redis(self, key: str, limit: int) -> bool:
        if not self._redis:
            return await self._is_limit_ok_memory(key, limit)

        now = time.time()
        cutoff = now - self.window_sec
        redis_key = f"{self._key_prefix}:{key}"
        member = f"{now}:{uuid.uuid4().hex}"

        async with self._redis.pipeline() as pipe:
            await pipe.zremrangebyscore(redis_key, 0, cutoff)
            await pipe.zcard(redis_key)
            cardinality = await pipe.execute()

            if not cardinality or len(cardinality) < 2:
                logger.warning("redis rate-limit pipeline result abnormal, fallback to memory limiter: key=%s", redis_key)
                return await self._is_limit_ok_memory(key, limit)

            if int(cardinality[1]) >= limit:
                return False

            await self._redis.zadd(redis_key, {member: now})
            await self._redis.expire(redis_key, self.window_sec)
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

        # keep read-only endpoints at default limit and stricter endpoints at configured cap
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
