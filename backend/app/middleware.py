from __future__ import annotations

import asyncio
import ipaddress
import logging
import time
import uuid
import re
import hashlib
import hmac
from collections import defaultdict, deque
from typing import Deque, Dict

from fastapi import Request
from redis import asyncio as redis_async
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse

from .config import get_settings
from .services.runtime_metrics import (
    record_admission_reject,
    record_admission_wait,
    record_request_inflight,
    record_request_timeout,
    RequestExecution,
    request_execution,
)

logger = logging.getLogger(__name__)
REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


class RequestIDMiddleware(BaseHTTPMiddleware):
    """Attach and expose request id for tracing."""

    async def dispatch(self, request: Request, call_next):
        settings = get_settings()
        header_name = settings.request_id_header or "X-Request-ID"
        supplied_id = request.headers.get(header_name) or request.headers.get("X-Correlation-ID")
        request_id = supplied_id if supplied_id and REQUEST_ID_PATTERN.fullmatch(supplied_id) else str(uuid.uuid4())
        request.state.request_id = request_id
        response = await call_next(request)
        response.headers[header_name] = request_id
        response.headers.setdefault("X-Correlation-ID", request_id)
        return response


class TimeoutMiddleware:
    """Return a deadline response while retaining ownership of unfinished work.

    Cancelling an await cannot stop a running synchronous DB transaction. Drain
    it before returning to the outer admission gate, and suppress late responses.
    """

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        settings = get_settings()
        exempt = {p.strip() for p in settings.request_timeout_exempt_paths.split(",")}
        timeout = settings.request_timeout_ms / 1000
        if timeout <= 0 or scope["path"] in exempt:
            return await self.app(scope, receive, send)
        state = RequestExecution()
        token = request_execution.set(state)
        started = asyncio.Event()

        async def guarded_send(message):
            if not state.timed_out:
                if message["type"] == "http.response.start":
                    started.set()
                await send(message)

        work = asyncio.create_task(self.app(scope, receive, guarded_send))
        response_started = asyncio.create_task(started.wait())
        try:
            done, _ = await asyncio.wait({work, response_started}, timeout=timeout,
                                         return_when=asyncio.FIRST_COMPLETED)
            if not done and not started.is_set():
                state.timed_out = True
                bucket = "webhook" if scope["path"].startswith("/api/v1/webhooks/") else "default"
                record_request_timeout(bucket)
                response = JSONResponse(status_code=504, content={"error": "timeout",
                    "message": "request deadline exceeded; retry with the same event ID",
                    "request_id": scope.get("state", {}).get("request_id")},
                    headers={"Retry-After": "1"})
                await response(scope, receive, send)
            # Also covers streaming responses: a started stream retains its slot.
            await asyncio.shield(work)
        except asyncio.CancelledError:
            state.timed_out = True
            try:
                await asyncio.shield(work)
            except Exception:
                logger.exception("request failed while draining cancelled client")
            raise
        except Exception:
            if not state.timed_out:
                raise
            logger.exception("request failed after deadline response")
        finally:
            response_started.cancel()
            await asyncio.gather(response_started, return_exceptions=True)
            request_execution.reset(token)


class AdmissionControlMiddleware:
    """Bound active AND waiting requests before rate limiting, auth or DB work."""

    def __init__(self, app):
        self.app = app
        settings = get_settings()
        self.enabled = bool(settings.request_admission_enabled)
        pool_budget = max(1, settings.database_pool_size + settings.database_max_overflow)
        self.total_limit = settings.request_admission_total_inflight or pool_budget
        if self.total_limit < 1:
            raise ValueError("request admission total must be positive")
        self.limits = {
            "default": settings.request_admission_default_inflight or self.total_limit,
            "webhook": settings.request_admission_webhook_inflight or self.total_limit,
            "probe": max(1, settings.request_admission_metrics_inflight),
            "stream": max(1, settings.request_admission_stream_inflight),
            "static": max(1, settings.request_admission_static_inflight),
        }
        if min(self.limits.values()) < 1:
            raise ValueError("request admission budgets must be positive")
        self.active = defaultdict(int)
        self.total = 0
        self.waiting = 0
        self.max_waiters = max(0, settings.request_admission_max_waiters)
        self.timeout = max(0.001, settings.request_admission_timeout_sec)
        self.retry_after = max(1, settings.request_admission_retry_after_sec)
        self.changed = asyncio.Event()

    def available(self, bucket):
        return self.active[bucket] < self.limits[bucket] and (bucket in {"probe", "stream", "static"} or self.total < self.total_limit)

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or not self.enabled or scope["path"] == "/healthz":
            return await self.app(scope, receive, send)
        path = scope["path"]
        # Vite loads several JS/CSS chunks in parallel. These file responses use
        # no DB connection and must not compete for the two control-API slots.
        static_path = path in {"/", "/admin", "/agent"} or path.startswith(("/assets/", "/admin/", "/agent/"))
        bucket = ("static" if static_path and scope.get("method") in {"GET", "HEAD"} else
                  "stream" if path == "/api/v1/agent/events/stream" else
                  "probe" if path in {"/metrics", "/metrics/runtime", "/readyz"} else
                  "webhook" if path.startswith("/api/v1/webhooks/") else "default")
        started_at = time.perf_counter()
        reason = None
        if not self.available(bucket):
            if self.waiting >= self.max_waiters:
                reason = "capacity"
            else:
                self.waiting += 1
                try:
                    async with asyncio.timeout(self.timeout):
                        while not self.available(bucket):
                            self.changed.clear()
                            await self.changed.wait()
                except TimeoutError:
                    reason = "timeout"
                finally:
                    self.waiting -= 1
        record_admission_wait(bucket, time.perf_counter() - started_at, accepted=reason is None)
        if reason:
            record_admission_reject(bucket, reason)
            return await JSONResponse(status_code=503, content={"error": "admission_limit_reached",
                "message": "system at capacity, retry soon",
                "request_id": scope.get("state", {}).get("request_id")},
                headers={"Retry-After": str(self.retry_after)})(scope, receive, send)
        self.active[bucket] += 1
        if bucket not in {"probe", "stream", "static"}:
            self.total += 1
        record_request_inflight(bucket, 1)
        try:
            await self.app(scope, receive, send)
        finally:
            self.active[bucket] -= 1
            if bucket not in {"probe", "stream", "static"}:
                self.total -= 1
            record_request_inflight(bucket, -1)
            self.changed.set()


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    """Add common production security headers."""

    async def dispatch(self, request: Request, call_next):
        response = await call_next(request)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("X-Frame-Options", "DENY")
        response.headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
        response.headers.setdefault("Permissions-Policy", "geolocation=(), camera=(), microphone=(self)")
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
        self.webhook_rpm = max(1, settings.rate_limit_webhook_rpm)
        self.webhook_control_rpm = max(1, settings.rate_limit_webhook_control_rpm)
        self.unverified_webhook_rpm = max(1, settings.rate_limit_unverified_webhook_rpm)
        self.max_memory_keys = max(100, settings.rate_limit_memory_max_keys)
        self._redis_retry_at = 0.0
        self._memory_sweep_at = 0.0
        self._hits: Dict[str, Deque[float]] = defaultdict(deque)
        self._lock = asyncio.Lock()
        self._key_prefix = "ai-outbound:rate-limit"
        self._redis = self._connect_redis_client(settings.redis_url)
        self._trusted_proxy_ips = {
            item.strip() for item in settings.trusted_proxy_ips.split(",") if item.strip()
        }
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
            if time.monotonic() >= self._redis_retry_at:
                try:
                    result = await asyncio.wait_for(self._is_limit_ok_redis(key, limit), 1.0)
                    self._redis_retry_at = 0
                    return result
                except Exception:
                    self._redis_retry_at = time.monotonic() + 1
                    logger.warning("distributed rate limiter temporarily unavailable")
            if get_settings().env.lower() in {"prod", "production"}:
                raise RuntimeError("distributed rate limiter unavailable")
        elif get_settings().env.lower() in {"prod", "production"}:
            raise RuntimeError("distributed rate limiter unavailable")
        return await self._is_limit_ok_memory(key, limit)

    async def _is_limit_ok_memory(self, key: str, limit: int) -> bool:
        window = self.window_sec
        cutoff = time.time() - window

        async with self._lock:
            if time.monotonic() >= self._memory_sweep_at:
                for existing in list(self._hits):
                    if not self._hits[existing] or self._hits[existing][-1] <= cutoff:
                        del self._hits[existing]
                self._memory_sweep_at = time.monotonic() + min(10, window)
            if key not in self._hits and len(self._hits) >= self.max_memory_keys:
                return False
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

        script = """
        redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
        local count = redis.call('ZCARD', KEYS[1])
        if count >= tonumber(ARGV[2]) then
            return 0
        end
        redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
        redis.call('EXPIRE', KEYS[1], ARGV[5])
        return 1
        """
        allowed = await self._redis.eval(
            script,
            1,
            redis_key,
            cutoff,
            limit,
            now,
            member,
            self.window_sec,
        )
        return bool(allowed)

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
        bucket_path = "/api"
        for prefix, rpm in self.path_limits.items():
            if path.startswith(prefix):
                limit = rpm
                bucket_path = prefix
                break
        if limit == 0:
            limit = self.default_rpm

        # keep read-only endpoints at default limit and stricter endpoints at configured cap
        if method in {"GET", "HEAD", "OPTIONS"}:
            limit = max(limit, self.default_rpm)

        client_host = request.client.host if request.client else "unknown"
        if client_host in self._trusted_proxy_ips:
            forwarded = request.headers.get("x-forwarded-for", "").split(",", 1)[0].strip()
            if forwarded:
                try:
                    client_host = str(ipaddress.ip_address(forwarded))
                except ValueError:
                    logger.warning("ignored invalid X-Forwarded-For from trusted proxy")
        key = f"{client_host}:{bucket_path}"
        if path.startswith("/api/v1/webhooks/"):
            label = "sms" if path.startswith("/api/v1/webhooks/sms/") else "telephony"
            secret = settings.sms_webhook_secret if label == "sms" else settings.telephony_webhook_secret
            token = settings.sms_webhook_token if label == "sms" else settings.telephony_webhook_token
            stamp = request.headers.get("x-webhook-timestamp", "")
            supplied = request.headers.get("x-webhook-signature", "").removeprefix("sha256=")
            authenticated = False
            if secret and token and hmac.compare_digest(request.headers.get("x-webhook-token", "").encode(), token.encode()):
                try:
                    age_ok = abs(time.time() - int(stamp)) <= max(30, min(3600, settings.webhook_signature_max_age_sec))
                    if age_ok:
                        body = await request.body()
                        expected = hmac.new(secret.encode(), stamp.encode("ascii") + b"." + body, hashlib.sha256).hexdigest()
                        authenticated = hmac.compare_digest(supplied.encode(), expected.encode())
                except (ValueError, UnicodeError):
                    pass
            if authenticated:
                control = path.endswith(("/status", "/media", "/recording"))
                bucket = "control" if control else "speech"
                key = f"verified-webhook:{label}:{bucket}"
                limit = self.webhook_control_rpm if control else self.webhook_rpm
            else:
                key = f"unverified-webhook:{client_host}"
                limit = self.unverified_webhook_rpm
        try:
            allowed = await self._is_limit_ok(key, limit)
        except RuntimeError:
            return JSONResponse(status_code=503, content={"error": "rate_limiter_unavailable"}, headers={"Retry-After": "1"})
        if not allowed:
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
