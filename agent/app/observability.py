"""Metadata-only Langfuse tracing. No request bodies or exception messages leave here."""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import logging
from contextlib import ExitStack, asynccontextmanager, contextmanager
from contextvars import ContextVar
from functools import wraps

from .config import settings

logger = logging.getLogger(__name__)
_client = None
_current = ContextVar("langfuse_observation", default=None)


def _create_client():
    from langfuse import Langfuse
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.sampling import TraceIdRatioBased

    return Langfuse(
        public_key=settings.langfuse_public_key,
        secret_key=settings.langfuse_secret_key,
        base_url=settings.langfuse_base_url,
        environment=settings.env,
        sample_rate=settings.langfuse_sample_rate,
        # Keep sampling/export independent of global OTel integrations.
        tracer_provider=TracerProvider(sampler=TraceIdRatioBased(settings.langfuse_sample_rate)),
        timeout=2,
        flush_at=64,
        flush_interval=5,
        # Do not export unrelated instrumentors attached to this process.
        should_export_span=lambda span: span.instrumentation_scope.name == "langfuse-sdk",
    )


@asynccontextmanager
async def tracing_lifespan():
    global _client
    if settings.langfuse_enabled:
        opening = asyncio.create_task(asyncio.to_thread(_create_client))
        try:
            _client = await asyncio.shield(opening)
        except asyncio.CancelledError:
            try:
                client = await opening
                await asyncio.to_thread(client.shutdown)
            except Exception:
                logger.warning("Langfuse cancelled startup cleanup failed")
            raise
        except Exception:
            logger.warning("Langfuse initialization failed; tracing disabled")
    try:
        yield
    finally:
        client, _client = _client, None
        if client is not None:
            try:
                await asyncio.to_thread(client.shutdown)
            except Exception:
                logger.warning("Langfuse shutdown failed")


def tracing_status():
    return {"enabled": settings.langfuse_enabled, "initialized": _client is not None,
            "sample_rate": settings.langfuse_sample_rate,
            "content_capture": False, "connectivity_verified": False}


def update(**values):
    observation = _current.get()
    if observation is not None:
        try:
            observation.update(**values)
        except Exception:
            logger.debug("Langfuse observation update failed")


@contextmanager
def observation(name, *, call_id=None, **values):
    span = None
    attributes = ExitStack()
    parent = _current.get()
    if _client is not None:
        try:
            # Explicit parents avoid SDK context-manager exception recording,
            # which could otherwise include customer data in error messages.
            if call_id is not None:
                from langfuse import propagate_attributes

                session_id = hmac.new(settings.langfuse_secret_key.encode(),
                                      call_id.encode(), hashlib.sha256).hexdigest()
                attributes.enter_context(propagate_attributes(session_id=session_id))
            owner = parent if parent is not None else _client
            span = owner.start_observation(name=name, **values)
        except Exception:
            logger.debug("Langfuse observation start failed")
    token = _current.set(span)
    try:
        yield
    except BaseException as exc:
        update(level="ERROR", status_message=type(exc).__name__)
        raise
    finally:
        _current.reset(token)
        if span is not None:
            try:
                span.end()
            except Exception:
                logger.debug("Langfuse observation end failed")
        try:
            attributes.close()
        except Exception:
            logger.debug("Langfuse attribute cleanup failed")


def trace_generation(function):
    @wraps(function)
    async def wrapped(*args, **kwargs):
        with observation("llm.reply", as_type="generation",
                         model=kwargs.get("model") or settings.openai_model,
                         model_parameters={"temperature": 0.3,
                                           "max_tokens": settings.max_output_tokens}):
            return await function(*args, **kwargs)
    return wrapped


def record_usage(data):
    usage = data.get("usage") if isinstance(data, dict) else None
    if not isinstance(usage, dict):
        return
    counts = {}
    for source, target in (("prompt_tokens", "input"), ("completion_tokens", "output"),
                           ("total_tokens", "total")):
        value = usage.get(source)
        if type(value) is int and value >= 0:
            counts[target] = value
    if counts:
        update(usage_details=counts)
