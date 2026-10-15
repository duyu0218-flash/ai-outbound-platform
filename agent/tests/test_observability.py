import asyncio
import hashlib
import hmac
import json
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from langfuse import Langfuse
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from app import llm, observability as tracing
from app.config import Settings, settings
from app.main import app


@pytest.fixture
def exported(monkeypatch):
    """Run the production factory with the real SDK and a local test exporter."""
    import langfuse

    exporter = InMemorySpanExporter()
    monkeypatch.setattr(settings, "langfuse_enabled", True)
    monkeypatch.setattr(settings, "langfuse_public_key", "pk-test-" + uuid4().hex)
    monkeypatch.setattr(settings, "langfuse_secret_key", "sk-test-local")
    monkeypatch.setattr(settings, "langfuse_base_url", "http://127.0.0.1:1")
    monkeypatch.setattr(settings, "langfuse_sample_rate", 1.0)
    monkeypatch.setattr(settings, "service_token", "test-service")
    monkeypatch.setattr(settings, "llm_provider", "rule")
    monkeypatch.setattr(langfuse, "Langfuse", lambda **kwargs: Langfuse(
        **kwargs, span_exporter=exporter))
    return exporter


def request(call_id="private-call-id", **overrides):
    return {"call_id": call_id, "phone": "13800138000", "mode": "ai_only",
            "script": "PRIVATE SCRIPT", "transcript": "PRIVATE TRANSCRIPT",
            "context": {}, **overrides}


def post(client, path, payload):
    return client.post(path, json=payload, headers={"Authorization": "Bearer test-service"})


def spans(exporter):
    tracing._client.flush()
    return exporter.get_finished_spans()


def test_real_sdk_generation_parent_usage_and_privacy(exported, monkeypatch):
    monkeypatch.setattr(settings, "openai_base_url", "https://model.example/v1")
    monkeypatch.setattr(settings, "openai_api_key", "PRIVATE API KEY")
    monkeypatch.setattr(settings, "llm_allowed_hosts", "model.example")
    async def run(client):
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(
            200, json={"choices": [{"message": {"content": "PRIVATE REPLY"}}],
                       "usage": {"prompt_tokens": 12, "completion_tokens": 4, "total_tokens": 16}}
        ))) as model:
            monkeypatch.setattr(llm, "_client", model)
            response = await asyncio.to_thread(post, client, "/agent/turn", request(context={
                "llm_provider": "openai-compatible", "external_llm_enabled": True}))
            assert response.status_code == 200
            assert response.json()["tts_text"] == "PRIVATE REPLY"
    with TestClient(app) as client:
        asyncio.run(run(client))
        items = spans(exported)
        assert len(items) == 2
        root = next(s for s in items if s.name == "agent.turn")
        generation = next(s for s in items if s.name == "llm.reply")
        assert generation.parent.span_id == root.context.span_id
        assert generation.context.trace_id == root.context.trace_id
        attrs = generation.attributes
        assert json.loads(attrs["langfuse.observation.usage_details"]) == {"input": 12, "output": 4, "total": 16}
        expected_session = hmac.new(b"sk-test-local", b"private-call-id", hashlib.sha256).hexdigest()
        assert root.attributes["session.id"] == expected_session
        assert generation.attributes["session.id"] == expected_session
        dumped = str([dict(s.attributes) for s in items])
        for private in ("13800138000", "PRIVATE", "private-call-id", "sk-test-local"):
            assert private not in dumped


def test_start_rule_handoff_auth_and_tenant_rejection(exported):
    with TestClient(app) as client:
        assert client.get("/readyz").status_code == 401
        ready = client.get("/readyz", headers={"Authorization": "Bearer test-service"}).json()
        assert ready["observability"] == {"enabled": True, "initialized": True,
                                         "sample_rate": 1.0, "content_capture": False,
                                         "connectivity_verified": False}
        assert client.post("/agent/turn", json=request()).status_code == 401
        assert len(spans(exported)) == 0
        assert post(client, "/agent/start", request()).json()["action"] == "greeting"
        assert post(client, "/agent/turn", request()).json()["action"] == "speak"
        assert post(client, "/agent/turn", request(mode="ai_handoff", transcript="请转人工")).json()["action"] == "handoff"
        assert post(client, "/agent/turn", request(context={"llm_provider": "openai-compatible"})).status_code == 409
        items = spans(exported)
        assert len(items) == 4
        assert all(s.name != "llm.reply" for s in items)
        assert items[-1].attributes["langfuse.observation.level"] == "ERROR"
        assert items[-1].attributes["langfuse.observation.status_message"] == "HTTPException"


def test_errors_cancellation_and_concurrent_parents(exported):
    async def work(index):
        with tracing.observation("turn", call_id=str(index)):
            with tracing.observation("generation", as_type="generation"):
                await asyncio.sleep(0)
                if index == 0:
                    raise ValueError("PRIVATE ERROR 13800138000")
                if index == 1:
                    raise asyncio.CancelledError("PRIVATE CANCEL")
    async def run():
        return await asyncio.gather(*(work(i) for i in range(8)), return_exceptions=True)
    with TestClient(app):
        results = asyncio.run(run())
        assert isinstance(results[0], ValueError)
        assert isinstance(results[1], asyncio.CancelledError)
        items = spans(exported)
        roots = {s.context.span_id: s for s in items if s.name == "turn"}
        assert len(roots) == 8
        for child in (s for s in items if s.name == "generation"):
            root = roots[child.parent.span_id]
            assert root.context.trace_id == child.context.trace_id
            assert root.attributes["session.id"] == child.attributes["session.id"]
        assert "PRIVATE" not in str([dict(s.attributes) for s in items])
        assert tracing._current.get() is None


@pytest.mark.parametrize("failure", ["start_observation", "update", "end"])
def test_telemetry_failure_never_changes_result(monkeypatch, failure):
    class Broken:
        def start_observation(self, **kwargs):
            if failure == "start_observation":
                raise RuntimeError("private")
            return self
        def update(self, **kwargs):
            if failure == "update":
                raise RuntimeError("private")
        def end(self):
            if failure == "end":
                raise RuntimeError("private")
    monkeypatch.setattr(tracing, "_client", Broken())
    with tracing.observation("turn"):
        tracing.update(metadata={"action": "speak"})
    with pytest.raises(ValueError, match="original"):
        with tracing.observation("turn"):
            raise ValueError("original")
    assert tracing._current.get() is None


def test_disabled_and_failed_initialization(monkeypatch):
    def fail():
        raise AssertionError("must not initialize when disabled")
    monkeypatch.setattr(tracing, "_create_client", fail)
    monkeypatch.setattr(settings, "langfuse_enabled", False)
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        assert tracing._client is None
    monkeypatch.setattr(settings, "langfuse_enabled", True)
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        assert tracing._client is None


@pytest.mark.parametrize("values", [
    {"langfuse_base_url": ""}, {"langfuse_base_url": "https://u:p@example.org"},
    {"langfuse_base_url": "https://example.org?secret=1"},
    {"langfuse_secret_key": ""}, {"langfuse_sample_rate": -1},
    {"langfuse_sample_rate": 2}, {"langfuse_sample_rate": float("nan")},
    {"env": "production", "langfuse_base_url": "http://example.org"},
])
def test_invalid_enabled_config(values):
    config = dict(langfuse_enabled=True, langfuse_base_url="https://example.org",
                  langfuse_public_key="pk-test", langfuse_secret_key="sk-test")
    with pytest.raises(RuntimeError):
        Settings(_env_file=None, **(config | values)).validate_runtime()


def test_usage_ignores_invalid_provider_fields(monkeypatch):
    received = []
    monkeypatch.setattr(tracing, "update", lambda **kwargs: received.append(kwargs))
    for data in (None, [], {"usage": []}, {"usage": {"prompt_tokens": True,
                 "completion_tokens": -1, "total_tokens": "100"}}):
        tracing.record_usage(data)
    assert received == []
    tracing.record_usage({"usage": {"prompt_tokens": 0}})
    assert received == [{"usage_details": {"input": 0}}]


def test_startup_cancellation_closes_created_sdk(monkeypatch):
    import threading
    started, release, closed = threading.Event(), threading.Event(), threading.Event()
    class Client:
        def shutdown(self):
            closed.set()
    def create():
        started.set()
        release.wait(3)
        return Client()
    monkeypatch.setattr(settings, "langfuse_enabled", True)
    monkeypatch.setattr(tracing, "_create_client", create)
    async def run():
        async def opening():
            async with tracing.tracing_lifespan():
                pytest.fail("cancelled startup must not enter application lifespan")
        task = asyncio.create_task(opening())
        await asyncio.to_thread(started.wait, 3)
        task.cancel()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
    asyncio.run(run())
    assert closed.is_set()
    assert tracing._client is None
