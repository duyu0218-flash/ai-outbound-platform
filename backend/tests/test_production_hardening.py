from __future__ import annotations

import asyncio
import csv
import hashlib
import hmac
import io
import json
import os
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, delete, inspect, text
from sqlmodel import select


TEST_DB = Path("/tmp/ai-outbound-pytest.db")
if "DATABASE_URL" not in os.environ:
    TEST_DB.unlink(missing_ok=True)
    os.environ["DATABASE_URL"] = f"sqlite:///{TEST_DB}"
os.environ.setdefault("REDIS_URL", "")
os.environ.setdefault("RATE_LIMIT_ENABLED", "false")
os.environ.setdefault("DEMO_USERS_ENABLED", "true")
os.environ.setdefault("TELEPHONY_WEBHOOK_BASE", "http://127.0.0.1:9")
os.environ.setdefault("TELEPHONY_TIMEOUT_SEC", "1")
os.environ.setdefault("TELEPHONY_RETRY_TIMES", "0")
os.environ.setdefault("SCHEDULER_ENABLED", "false")

from app.db import engine, session_scope  # noqa: E402
from app import db as db_module  # noqa: E402
from app.clock import utc_now  # noqa: E402
from app.main import app  # noqa: E402
from app import main as app_main  # noqa: E402
from app.models import (  # noqa: E402
    AdminSetting,
    Campaign,
    CallAnalysis,
    CallEvent,
    CallMetric,
    CallMode,
    CallSession,
    CallStatus,
    Contact,
    ContactImportJob,
    HandoffRequest,
    HandoffState,
    KnowledgeItem,
    RecordingAsset,
    RealtimeSession,
    SmsLog,
    SpeechTurn,
    TaskOutbox,
    TaskState,
    TelephonyLine,
    Tenant,
    User,
)
from app.schemas import AiTurnResult  # noqa: E402
from app.schema_migrations import apply_runtime_migrations  # noqa: E402
from app.services import dispatcher, telephony  # noqa: E402
from app.services import business_callbacks, health  # noqa: E402
from app.services import webrtc as webrtc_service  # noqa: E402
from app.api.routers import webrtc as webrtc_router  # noqa: E402
from app.services.admin_settings import SETTING_DEFAULTS  # noqa: E402
from app.services.knowledge import retrieve_knowledge  # noqa: E402
from app.services.retention import purge_expired_voice_data  # noqa: E402
from app.services.script_flow import FlowValidationError, validate_graph  # noqa: E402
from app.services.task_queue import (  # noqa: E402
    enqueue_business_callback,
    enqueue_task,
    process_pending_tasks,
    process_task,
    retry_dead_task,
)
from app.schemas import ScriptFlowGraph  # noqa: E402
from app.services.call_service import (  # noqa: E402
    create_call,
    can_call_contact_sync,
    dispatch_call_ids,
    dispatch_due_retries,
    dispatch_pending_calls,
    expire_stale_calls,
    place_call,
    retry_call,
    schedule_campaign_retry,
    select_voice_ai_pipeline,
    complete_campaign_if_terminal,
)


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture(autouse=True)
def reset_runtime_settings_after_test(monkeypatch):
    """Keep tests independent from saved settings and wall-clock call hours."""

    compliance_defaults = dict(SETTING_DEFAULTS["compliance"])
    compliance_defaults.update(
        allowed_start_hour=0,
        allowed_end_hour=0,
        require_explicit_consent_for_direct_calls=False,
    )
    monkeypatch.setitem(SETTING_DEFAULTS, "compliance", compliance_defaults)

    yield
    with session_scope() as session:
        session.exec(delete(AdminSetting))
        session.commit()


def _login(client: TestClient, username: str, password: str = "12345678") -> str:
    response = client.post("/api/v1/auth/login", json={"username": username, "password": password})
    assert response.status_code == 200, response.text
    return response.json()["access_token"]


def _bearer(token: str, **extra: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}", **extra}


def _voice_security_contract_module():
    # Load only the independent security protocol module, not the gateway's
    # app package/Pipecat runtime, so this test verifies both implementations.
    import importlib.util
    import sys
    name = "voice_security_contract"
    if name not in sys.modules:
        path = Path(__file__).resolve().parents[2] / "voice_gateway/app/security.py"
        spec = importlib.util.spec_from_file_location(name, path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[name] = module
        spec.loader.exec_module(module)
    return sys.modules[name]


def test_gateway_callback_producer_passes_actual_production_hmac_verifier(client, monkeypatch):
    import httpx
    from types import SimpleNamespace
    from starlette.requests import Request
    from app.api.deps import _verify_webhook_request
    security = _voice_security_contract_module()
    token, secret = "synthetic-token-" + "t" * 32, "synthetic-signature-" + "s" * 32
    config = SimpleNamespace(voice_security_db_path="", voice_callback_base_url="http://control-api:8000",
                             webhook_token=token, webhook_secret=secret, request_timeout_sec=1)
    captured = []
    def transport(req):
        captured.append(req)
        return httpx.Response(200)
    original = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kw: original(transport=httpx.MockTransport(transport), **kw))
    monkeypatch.setattr(app_main.settings, "env", "production")
    async def run():
        await security.CallbackSender(config).post(config.voice_callback_base_url + "/api/v1/webhooks/telephony/status",
                                                   {"call_id": "synthetic", "kind": "status", "payload": {"status": "answered", "text": "中文"}})
        produced = captured[0]
        async def receive():
            return {"type": "http.request", "body": produced.content, "more_body": False}
        request = Request({"type": "http", "method": "POST", "path": "/", "headers": []}, receive)
        await _verify_webhook_request(request, token=token, secret=secret, label="telephony",
                                     x_webhook_token=produced.headers["x-webhook-token"],
                                     x_webhook_timestamp=produced.headers["x-webhook-timestamp"],
                                     x_webhook_signature=produced.headers["x-webhook-signature"])
    asyncio.run(run())


def test_backend_commands_pass_gateway_permit_verifier_and_bind_tenant(client, monkeypatch, tmp_path):
    import httpx
    security = _voice_security_contract_module()
    secret = "synthetic-command-" + "s" * 32
    monkeypatch.setattr(app_main.settings, "voice_command_secret", secret)
    ledger = security.Ledger(str(tmp_path / "contract.sqlite3"))
    captured = []
    def transport(req):
        ledger.verify_command(secret, req.url.path, req.content, req.headers)
        captured.append(json.loads(req.content))
        return httpx.Response(200, json={"result": "accepted"})
    original = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kw: original(transport=httpx.MockTransport(transport), **kw))
    async def run():
        adapter = telephony.HttpAdapter("http://synthetic-gateway", bearer_token="synthetic", tenant_id=7)
        await adapter.dial(call_id="c-1", phone="8613800138000", webhook_url="http://control/status", metadata={"tenant_id": 7, "attempt": 1})
        await adapter.hangup(call_id="c-1")
    asyncio.run(run())
    assert all(p["tenant_id"] == 7 for p in captured)


def test_rejection_after_transport_timeout_remains_uncertain(client):
    import httpx
    attempts = 0
    async def operation():
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise httpx.ReadTimeout("synthetic lost response")
        request = httpx.Request("POST", "http://synthetic-gateway/v1/call/dial")
        raise httpx.HTTPStatusError("synthetic key rotation", request=request, response=httpx.Response(401, request=request))
    with pytest.raises(httpx.HTTPStatusError) as exc:
        asyncio.run(telephony.with_retry(operation, retries=2, base_delay=0))
    assert attempts == 2
    assert exc.value.telephony_outcome_unknown is True


def test_security_config_changes_need_independent_approval(client, monkeypatch):
    token = _login(client, "admin")
    headers = _bearer(token)
    monkeypatch.setattr(app_main.settings, "telephony_provider", "http")
    approval = "synthetic-independent-approval-" + "a" * 32
    monkeypatch.setattr(app_main.settings, "outbound_security_approval_token", approval)
    path = "/api/v1/admin/settings/capacity"
    assert client.put(path, headers=headers, json={"data": {"max_concurrent_calls": 2}}).status_code == 403
    approved = {**headers, "x-security-approval": approval}
    assert client.put(path, headers=approved, json={"data": {"max_concurrent_calls": 2}}).status_code == 200
    assert client.get(path, headers=headers).json()["data"]["max_concurrent_calls"] == 2
    assert client.put(path, headers=approved, json={"data": {"max_concurrent_calls": 9999}}).status_code == 422


def test_platform_policy_cannot_be_relaxed_by_saved_tenant_settings(client, monkeypatch):
    from app.services.admin_settings import get_tenant_max_concurrent_calls
    monkeypatch.setattr(app_main.settings, "outbound_platform_max_concurrent", 2)
    monkeypatch.setattr(app_main.settings, "outbound_allowed_phone_prefixes", "86138")
    with session_scope() as session:
        session.add(AdminSetting(tenant_id=1, section="capacity", data_json='{"max_concurrent_calls":9999}'))
        session.add(AdminSetting(tenant_id=1, section="compliance", data_json='{"allowed_phone_prefixes":"1","allowed_start_hour":0,"allowed_end_hour":0}'))
        session.commit()
        assert get_tenant_max_concurrent_calls(session, 1) == 2
        assert can_call_contact_sync(session, 1, "12025550123") == (False, "platform_destination_not_allowed")


def test_transfer_rejects_free_form_unknown_and_foreign_agent(client):
    from app.services.call_service import resolve_handoff_agent, CallPermissionError
    with session_scope() as session:
        agent = session.exec(select(User).where(User.tenant_id == 1, User.role == "agent", User.enabled.is_(True))).first()
        assert resolve_handoff_agent(session, 1, f"agent:{agent.id}") == agent.id
        for target in ("12025550123", "sofia/gateway/carrier/12025550123", "agent:999999999", "default"):
            with pytest.raises(CallPermissionError):
                resolve_handoff_agent(session, 1, target)
        with pytest.raises(CallPermissionError):
            resolve_handoff_agent(session, 999999, f"agent:{agent.id}")


def test_stale_unconfirmed_provider_preserves_capacity(client, monkeypatch):
    from app.services import call_service
    class Unconfirmed:
        async def hangup(self, **kwargs):
            return {"result": "pending", "ended": False}
    monkeypatch.setattr(call_service, "get_telephony_adapter", lambda **kw: Unconfirmed())
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone="8613800138999", mode=CallMode.HUMAN_ONLY,
                           status=CallStatus.DIALING, attempts=1, updated_at=utc_now() - timedelta(seconds=600))
        session.add(call)
        session.commit()
        call_id = call.id
    asyncio.run(expire_stale_calls(batch_size=10000))
    with session_scope() as session:
        call = session.get(CallSession, call_id)
        assert call.status == CallStatus.DIALING
        assert call.finished_at is None and call.next_attempt_at is None
        assert "capacity retained" in call.last_error
        call.status = CallStatus.FAILED  # isolate synthetic test data
        session.add(call)
        session.commit()


def test_production_machine_key_write_scopes_are_explicit(client, monkeypatch):
    from app.api.deps import _check_machine_scope
    from fastapi import HTTPException
    from starlette.requests import Request
    monkeypatch.setattr(app_main.settings, "env", "production")
    monkeypatch.setattr(app_main.settings, "tenant_api_scopes_json", '{}')
    request = Request({"type": "http", "method": "POST", "path": "/api/v1/calls", "headers": []})
    with pytest.raises(HTTPException) as exc:
        _check_machine_scope(request, 1)
    assert exc.value.status_code == 403
    monkeypatch.setattr(app_main.settings, "tenant_api_scopes_json", '{"1":["calls:dial"]}')
    _check_machine_scope(request, 1)
    with pytest.raises(HTTPException):
        _check_machine_scope(request, 2)


def test_script_flow_version_publish_bind_and_simulate(client: TestClient):
    token = _login(client, "admin")
    headers = _bearer(token)
    template_response = client.post(
        "/api/v1/script-templates",
        headers=headers,
        json={"name": "flow acceptance", "content": "您好，这是开场白。", "category": "acceptance"},
    )
    assert template_response.status_code == 200, template_response.text
    template_id = template_response.json()["id"]

    created = client.post(
        f"/api/v1/script-templates/{template_id}/flows",
        headers=headers,
        json={"name": "acceptance v1"},
    )
    assert created.status_code == 200, created.text
    flow = created.json()
    assert flow["status"] == "draft"
    assert len(flow["graph"]["nodes"]) == 4

    graph = flow["graph"]
    graph["edges"][-1] = {
        "id": "e-listen-hangup",
        "source": "listen",
        "target": "hangup",
        "condition": "keyword",
        "keywords": ["不用了"],
    }
    saved = client.put(
        f"/api/v1/script-templates/{template_id}/flows/{flow['id']}",
        headers=headers,
        json={"name": "acceptance v1", "graph": graph},
    )
    assert saved.status_code == 200, saved.text
    published = client.post(
        f"/api/v1/script-templates/{template_id}/flows/{flow['id']}/publish",
        headers=headers,
    )
    assert published.status_code == 200, published.text
    assert published.json()["status"] == "published"

    simulated = client.post(
        f"/api/v1/script-templates/{template_id}/flows/{flow['id']}/simulate",
        headers=headers,
        json={"current_node_id": "listen", "transcript": "不用了，谢谢"},
    )
    assert simulated.status_code == 200, simulated.text
    assert simulated.json()["action"] == "hangup"

    campaign = client.post(
        "/api/v1/campaigns",
        headers=headers,
        json={
            "name": "flow campaign",
            "script_template_id": template_id,
            "script_flow_version_id": flow["id"],
            "mode": "ai_handoff",
            "contact_ids": [],
        },
    )
    assert campaign.status_code == 200, campaign.text
    assert campaign.json()["script_flow_version_id"] == flow["id"]

    with session_scope() as session:
        call = CallSession(
            tenant_id=1,
            campaign_id=campaign.json()["id"],
            phone="13800138222",
            mode=CallMode.AI_HANDOFF,
            status=CallStatus.ANSWERED,
            script_flow_version_id=flow["id"],
            flow_node_key="start",
        )
        session.add(call)
        session.commit()
        session.refresh(call)
        opening = dispatcher._run_script_flow_turn(session=session, call=call, transcript="")
        assert opening is not None
        assert opening.action == "speak"
        assert opening.tts_text == "您好，这是开场白。"
        assert call.flow_node_key == "opening"
        ending = dispatcher._run_script_flow_turn(session=session, call=call, transcript="不用了，谢谢")
        assert ending is not None
        assert ending.action == "hangup"
        assert call.flow_node_key == "hangup"

    immutable = client.put(
        f"/api/v1/script-templates/{template_id}/flows/{flow['id']}",
        headers=headers,
        json={"graph": graph},
    )
    assert immutable.status_code == 409


def test_utc_now_preserves_naive_database_contract():
    before = datetime.now(UTC).replace(tzinfo=None)
    value = utc_now()
    after = datetime.now(UTC).replace(tzinfo=None)

    assert value.tzinfo is None
    assert before <= value <= after


def test_p0_realtime_speech_is_idempotent_and_final_only_is_structured(client: TestClient):
    token = _login(client, "admin")
    created = client.post(
        "/api/v1/calls",
        headers=_bearer(token),
        json={"phone": "13800138101", "mode": "human_only", "max_attempts": 1},
    )
    assert created.status_code == 200, created.text
    call_id = created.json()["id"]

    partial = {
        "call_id": call_id,
        "event_id": "asr-event-partial-1",
        "transcript": "我想了解",
        "is_final": False,
        "confidence": 0.76,
        "asr_provider": "acceptance-asr",
    }
    assert client.post("/api/v1/webhooks/telephony/speech", json=partial).status_code == 200
    duplicate = client.post("/api/v1/webhooks/telephony/speech", json=partial)
    assert duplicate.status_code == 200
    assert duplicate.json()["duplicate"] is True

    final = {
        **partial,
        "event_id": "asr-event-final-1",
        "transcript": "我想了解这个方案，可以继续介绍。",
        "is_final": True,
        "confidence": 0.94,
        "start_ms": 120,
        "end_ms": 2380,
        "latency_ms": 180,
    }
    assert client.post("/api/v1/webhooks/telephony/speech", json=final).status_code == 200
    turns = client.get(f"/api/v1/calls/{call_id}/speech-turns", headers=_bearer(token))
    assert turns.status_code == 200, turns.text
    assert len(turns.json()) == 2
    assert turns.json()[-1]["is_final"] is True
    assert turns.json()[-1]["confidence"] == 0.94

    media = client.post(
        "/api/v1/webhooks/telephony/media",
        json={
            "call_id": call_id,
            "event_id": "media-speaking-1",
            "state": "speaking",
            "playback_id": "pb-1",
            "codec": "pcm_s16le",
            "sample_rate": 16000,
            "channel_count": 1,
            "duration_ms": 87,
            "provider": "acceptance-gateway",
        },
    )
    assert media.status_code == 200, media.text
    realtime = client.get(f"/api/v1/calls/{call_id}/realtime", headers=_bearer(token))
    assert realtime.status_code == 200, realtime.text
    assert realtime.json()["state"] == "speaking"
    metrics = client.get(f"/api/v1/calls/{call_id}/metrics", headers=_bearer(token))
    assert any(item["stage"] == "media.speaking" for item in metrics.json())
    asr_final = next(item for item in metrics.json() if item["stage"] == "asr.final")
    assert asr_final["duration_ms"] == 180
    assert "audio_span_ms=2260" in asr_final["detail"]


def test_p1_recording_analysis_and_knowledge_crud(client: TestClient):
    token = _login(client, "admin")
    headers = _bearer(token)
    created = client.post(
        "/api/v1/calls",
        headers=headers,
        json={"phone": "13800138102", "mode": "human_only", "max_attempts": 1},
    )
    assert created.status_code == 200, created.text
    call_id = created.json()["id"]

    retention_setting = client.put(
        "/api/v1/admin/settings/compliance",
        headers=headers,
        json={"data": {"recording_retention_days": 7}},
    )
    assert retention_setting.status_code == 200, retention_setting.text

    recording = client.post(
        "/api/v1/webhooks/telephony/recording",
        json={
            "call_id": call_id,
            "kind": "recording",
            "payload": {
                "event_id": "recording-1",
                "recording_id": "rec-1",
                "url": "https://recording.invalid/rec-1.wav",
                "storage_uri": "s3://acceptance/rec-1.wav",
                "duration_sec": 18,
                "format": "wav",
                "channel_count": 2,
            },
        },
    )
    assert recording.status_code == 200, recording.text
    assets = client.get(f"/api/v1/calls/{call_id}/recordings", headers=headers)
    assert assets.status_code == 200, assets.text
    assert assets.json()[0]["storage_uri"] == "s3://acceptance/rec-1.wav"
    assert assets.json()[0]["channel_count"] == 2
    retention_until = datetime.fromisoformat(assets.json()[0]["retention_until"])
    assert timedelta(days=6, hours=23) <= retention_until - utc_now() <= timedelta(days=7, minutes=1)

    analysis = client.get(f"/api/v1/calls/{call_id}/analysis?refresh=true", headers=headers)
    assert analysis.status_code == 200, analysis.text
    assert 0 <= analysis.json()["qa_score"] <= 100
    reviewed = client.put(
        f"/api/v1/calls/{call_id}/analysis",
        headers=headers,
        json={"result_code": "qualified_lead", "qa_score": 95, "qa_flags": []},
    )
    assert reviewed.status_code == 200, reviewed.text
    assert reviewed.json()["review_state"] == "reviewed"
    assert reviewed.json()["result_code"] == "qualified_lead"

    knowledge = client.post(
        "/api/v1/knowledge",
        headers=headers,
        json={
            "title": "产品价格说明",
            "content": "标准版按并发路数报价，正式价格以合同为准。",
            "category": "pricing",
            "keywords": "价格 报价 并发",
        },
    )
    assert knowledge.status_code == 200, knowledge.text
    item_id = knowledge.json()["id"]
    updated = client.put(
        f"/api/v1/knowledge/{item_id}",
        headers=headers,
        json={"content": "标准版按并发路数报价，折扣以审批结果为准。"},
    )
    assert updated.status_code == 200, updated.text
    assert updated.json()["version"] == 2
    assert any(item["id"] == item_id for item in client.get("/api/v1/knowledge", headers=headers).json())
    with session_scope() as session:
        matches = retrieve_knowledge(session, 1, "这个产品的价格和并发怎么计算？")
        assert matches and matches[0]["id"] == str(item_id)


def test_public_runtime_and_admin_business_dashboard(client: TestClient):
    runtime = client.get("/api/v1/runtime")
    assert runtime.status_code == 200
    assert runtime.json() == {"app_name": "AI-Outbound-Platform", "demo_users_enabled": True}
    assert "password" not in runtime.text.lower()

    with session_scope() as session:
        campaign = Campaign(tenant_id=1, name="dashboard-performance", mode=CallMode.AI_HANDOFF, status="completed")
        session.add(campaign)
        session.flush()
        reached = CallSession(
            tenant_id=1,
            campaign_id=campaign.id,
            phone="13800138121",
            mode=CallMode.AI_HANDOFF,
            status=CallStatus.COMPLETED,
        )
        missed = CallSession(
            tenant_id=1,
            campaign_id=campaign.id,
            phone="13800138122",
            mode=CallMode.AI_HANDOFF,
            status=CallStatus.NO_ANSWER,
        )
        session.add(reached)
        session.add(missed)
        session.flush()
        session.add(
            CallAnalysis(
                tenant_id=1,
                call_session_id=reached.id,
                result_code="qualified_lead",
                qa_score=92,
                review_state="reviewed",
            )
        )
        session.commit()
        campaign_id = campaign.id

    token = _login(client, "admin")
    response = client.get("/api/v1/admin/dashboard?days=30", headers=_bearer(token))
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["period"]["calls"] >= 2
    assert payload["period"]["reached"] >= 1
    assert payload["period"]["interested"] >= 1
    assert payload["period"]["reach_rate"] >= 0
    row = next(item for item in payload["campaign_performance"] if item["campaign_id"] == campaign_id)
    assert row["calls"] == 2
    assert row["reached"] == 1
    assert row["interested"] == 1
    assert row["reach_rate"] == 50.0
    assert row["interest_rate"] == 100.0


def test_contact_operations_reports_groups_and_billing(client: TestClient):
    token = _login(client, "admin")
    headers = _bearer(token)
    created = client.post(
        "/api/v1/contacts",
        headers=headers,
        json={
            "phone": "13800138129",
            "name": "report-contact",
            "tags": "ci-report-group,priority",
            "consent_state": "consented",
        },
    )
    assert created.status_code == 200, created.text
    assert created.json()["dnc_reason"] is None
    contact_id = created.json()["id"]

    with session_scope() as session:
        contact = session.get(Contact, contact_id)
        assert contact is not None
        campaign = Campaign(
            tenant_id=1,
            name="report-regression",
            mode=CallMode.HUMAN_ONLY,
            status="completed",
        )
        session.add(campaign)
        session.flush()
        session.add(
            CallSession(
                tenant_id=1,
                campaign_id=campaign.id,
                contact_id=contact_id,
                phone=contact.phone,
                mode=CallMode.HUMAN_ONLY,
                status=CallStatus.COMPLETED,
            )
        )
        session.add(
            CallSession(
                tenant_id=1,
                campaign_id=campaign.id,
                contact_id=contact_id,
                phone=contact.phone,
                mode=CallMode.HUMAN_ONLY,
                status=CallStatus.NO_ANSWER,
            )
        )
        session.commit()
        campaign_id = campaign.id

    report = client.get(
        "/api/v1/admin/call-reports?dimension=campaign&granularity=day&days=30",
        headers=headers,
    )
    assert report.status_code == 200, report.text
    report_row = next(row for row in report.json()["rows"] if row["key"] == str(campaign_id))
    assert report_row["calls"] == 2
    assert report_row["reached"] == 1
    assert report_row["completed"] == 1
    assert report_row["no_answer"] == 1
    assert report_row["loss"] == 1

    groups = client.get("/api/v1/admin/contact-groups?days=30", headers=headers)
    assert groups.status_code == 200, groups.text
    group_row = next(row for row in groups.json()["rows"] if row["key"] == "ci-report-group")
    assert group_row["contacts"] == 1
    assert group_row["calls"] == 2
    assert group_row["reached"] == 1
    assert group_row["completed"] == 1
    assert group_row["no_answer"] == 1

    billing = client.get(
        "/api/v1/admin/billing?dimension=campaign&days=30&telephony_unit_price_per_minute=0.1&ai_unit_price_per_minute=0&sms_unit_price=0",
        headers=headers,
    )
    assert billing.status_code == 200, billing.text
    billing_row = next(row for row in billing.json()["rows"] if row["key"] == str(campaign_id))
    assert billing_row["calls"] == 2
    assert billing_row["billable_calls"] == 1
    assert billing_row["reached"] == 1
    assert billing_row["completed"] == 1
    assert billing_row["no_answer"] == 1
    assert billing_row["estimated_cost"] == 0.1


def test_postgres_demo_user_bootstrap_is_concurrency_safe(monkeypatch):
    if engine.dialect.name != "postgresql":
        pytest.skip("PostgreSQL advisory locks are covered by the integration job")

    admin_username = f"bootstrap-admin-{uuid4().hex}"
    agent_username = f"bootstrap-agent-{uuid4().hex}"
    monkeypatch.setattr(app_main.settings, "demo_admin_username", admin_username)
    monkeypatch.setattr(app_main.settings, "demo_agent_username", agent_username)

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(app_main._bootstrap_default_tenant) for _ in range(2)]
        for future in futures:
            future.result(timeout=20)

    with session_scope() as session:
        users = session.exec(
            select(User).where(User.username.in_([admin_username, agent_username]))
        ).all()
        assert sorted(user.username for user in users) == sorted([admin_username, agent_username])
        session.exec(delete(User).where(User.username.in_([admin_username, agent_username])))
        session.commit()


def test_p0_handoff_accept_and_reject_state_machine(client: TestClient, monkeypatch):
    token = _login(client, "admin")
    headers = _bearer(token)
    created = client.post(
        "/api/v1/calls",
        headers=headers,
        json={"phone": "13800138103", "mode": "human_only", "max_attempts": 1},
    )
    call_id = UUID(created.json()["id"])
    with session_scope() as session:
        call = session.get(CallSession, call_id)
        assert call is not None
        call.status = CallStatus.WAITING_HUMAN
        call.summary = "客户询问了产品价格并希望人工说明"
        session.add(CallAnalysis(
            tenant_id=call.tenant_id,
            call_session_id=call.id,
            intent="pricing",
            summary=call.summary,
            qa_score=82,
        ))
        session.add(SpeechTurn(
            tenant_id=call.tenant_id,
            call_session_id=call.id,
            provider_event_key="handoff-context-1",
            turn_index=1,
            speaker_role="customer",
            transcript="请转人工给我说一下价格",
            normalized_transcript="请转人工给我说一下价格",
            is_final=True,
        ))
        handoff = HandoffRequest(
            tenant_id=call.tenant_id,
            call_session_id=call.id,
            state=HandoffState.WAITING,
            reason="acceptance",
        )
        session.add(call)
        session.add(handoff)
        session.commit()
        session.refresh(handoff)
        handoff_id = handoff.id

    captured: dict[str, object] = {}

    class TransferAdapter:
        async def transfer_to_human(self, **kwargs):
            captured.update(kwargs)
            return {"result": "transferred"}

    monkeypatch.setattr(
        "app.api.routers.voice_operations.get_telephony_adapter",
        lambda **_: TransferAdapter(),
    )
    queued = client.get("/api/v1/handoffs?state=waiting", headers=headers)
    assert queued.status_code == 200
    queued_item = next(item for item in queued.json() if item["id"] == handoff_id)
    assert queued_item["phone"] == "13800138103"
    assert queued_item["mode"] == "human_only"
    assert queued_item["intent"] == "pricing"
    assert queued_item["summary"] == "客户询问了产品价格并希望人工说明"
    assert queued_item["last_customer_utterance"] == "请转人工给我说一下价格"
    assert queued_item["wait_seconds"] >= 0
    agent_token = _login(client, "1001@test")
    agent_queue = client.get("/api/v1/handoffs?state=waiting", headers=_bearer(agent_token))
    assert agent_queue.status_code == 200, agent_queue.text
    assert any(item["id"] == handoff_id for item in agent_queue.json())
    with session_scope() as session:
        agent = session.exec(select(User).where(User.username == "1001@test")).first()
        assert agent is not None
        agent_id = agent.id
    accepted = client.post(
        f"/api/v1/calls/{call_id}/handoffs/{handoff_id}/accept",
        headers=_bearer(agent_token),
    )
    assert accepted.status_code == 200, accepted.text
    assert accepted.json()["state"] == "accepted"
    assert captured["call_id"] == str(call_id)
    assert captured["target_group"] == f"agent:{agent_id}"
    assert client.get(f"/api/v1/calls/{call_id}", headers=headers).json()["status"] == "handoff_transferring"
    with session_scope() as session:
        persisted_call = session.get(CallSession, call_id)
        persisted_agent = session.get(User, agent_id)
        assert persisted_call is not None and persisted_call.human_agent_id == agent_id
        assert persisted_agent is not None and persisted_agent.agent_status == "busy"
    second_accept = client.post(
        f"/api/v1/calls/{call_id}/handoffs/{handoff_id}/accept",
        headers=_bearer(agent_token),
    )
    assert second_accept.status_code == 409
    unavailable = client.post(
        "/api/v1/webhooks/telephony/status",
        json={
            "call_id": str(call_id),
            "kind": "status",
            "payload": {
                "status": "human_unavailable",
                "hangup_reason": "NO_ANSWER",
                "event_id": "browser-agent-no-answer-1",
            },
        },
    )
    assert unavailable.status_code == 200, unavailable.text
    assert unavailable.json() == {"result": "ok", "requeued": True}
    with session_scope() as session:
        requeued_call = session.get(CallSession, call_id)
        requeued_handoff = session.get(HandoffRequest, handoff_id)
        released_agent = session.get(User, agent_id)
        assert requeued_call is not None and requeued_call.status == CallStatus.WAITING_HUMAN
        assert requeued_call.human_agent_id is None
        assert requeued_handoff is not None and requeued_handoff.state == HandoffState.WAITING
        assert requeued_handoff.assigned_agent_id is None
        assert released_agent is not None and released_agent.agent_status == "ready"


def test_telephony_readiness_cascades_to_gateway_readyz(monkeypatch):
    settings = health.get_settings()
    monkeypatch.setattr(settings, "telephony_provider", "http")
    monkeypatch.setattr(settings, "telephony_provider_endpoint", "http://voice-gateway:8002")
    probes: list[tuple[str, str]] = []

    def fake_probe(base_url: str, path: str, timeout: float = 2.0) -> str:
        probes.append((base_url, path))
        return "ok"

    monkeypatch.setattr(health, "_probe_http", fake_probe)
    assert health.telephony_http_health_check() == "ok"
    assert probes == [("http://voice-gateway:8002", "/readyz")]

    class Result:
        def all(self):
            return [
                TelephonyLine(
                    tenant_id=1,
                    name="primary",
                    provider="http",
                    gateway_url="https://tenant-gateway.example.com",
                )
            ]

    class FakeSession:
        def exec(self, _query):
            return Result()

    probes.clear()
    monkeypatch.setattr(settings, "telephony_provider", "tenant")
    assert health.tenant_telephony_health_check(FakeSession(), 1) == "ok"
    assert probes == [("https://tenant-gateway.example.com", "/readyz")]


def test_public_handoff_transfer_failure_releases_agent(client: TestClient, monkeypatch):
    admin_token = _login(client, "admin")
    created = client.post(
        "/api/v1/calls",
        headers=_bearer(admin_token),
        json={"phone": "13800138104", "mode": "human_only", "max_attempts": 1},
    )
    assert created.status_code == 200, created.text
    call_id = UUID(created.json()["id"])
    with session_scope() as session:
        call = session.get(CallSession, call_id)
        assert call is not None
        call.status = CallStatus.WAITING_HUMAN
        handoff = HandoffRequest(
            tenant_id=call.tenant_id,
            call_session_id=call.id,
            state=HandoffState.WAITING,
            reason="transfer-failure",
        )
        session.add(call)
        session.add(handoff)
        session.commit()
        session.refresh(handoff)
        handoff_id = handoff.id

    class FailingTransferAdapter:
        async def transfer_to_human(self, **_kwargs):
            raise RuntimeError("PBX transfer unavailable")

    monkeypatch.setattr(
        "app.api.routers.voice_operations.get_telephony_adapter",
        lambda **_: FailingTransferAdapter(),
    )
    agent_token = _login(client, "1001@test")
    failed = client.post(
        f"/api/v1/calls/{call_id}/handoffs/{handoff_id}/accept",
        headers=_bearer(agent_token),
    )
    assert failed.status_code == 502, failed.text
    with session_scope() as session:
        persisted = session.get(HandoffRequest, handoff_id)
        agent = session.exec(select(User).where(User.username == "1001@test")).first()
        call = session.get(CallSession, call_id)
        assert persisted is not None and persisted.state == HandoffState.WAITING
        assert persisted.assigned_agent_id is None
        assert agent is not None and agent.agent_status == "ready"
        assert call is not None and call.status == CallStatus.WAITING_HUMAN


def test_quality_review_queue_prioritizes_pending_and_requires_supervisor(client: TestClient):
    admin_token = _login(client, "admin")
    headers = _bearer(admin_token)
    created = client.post(
        "/api/v1/calls",
        headers=headers,
        json={"phone": "13800138133", "mode": "human_only", "max_attempts": 1},
    )
    assert created.status_code == 200, created.text
    call_id = UUID(created.json()["id"])
    with session_scope() as session:
        session.add(CallAnalysis(
            tenant_id=1,
            call_session_id=call_id,
            result_code="interested",
            sentiment="neutral",
            intent="callback",
            summary="客户要求稍后回拨",
            qa_score=42,
            qa_flags_json='["未确认回拨时间"]',
            review_state="auto",
        ))
        session.commit()

    queue = client.get("/api/v1/quality/reviews?review_state=auto&max_score=50", headers=headers)
    assert queue.status_code == 200, queue.text
    item = next(row for row in queue.json() if row["call_id"] == str(call_id))
    assert item["qa_score"] == 42
    assert item["summary"] == "客户要求稍后回拨"

    agent_token = _login(client, "1001@test")
    forbidden = client.get("/api/v1/quality/reviews", headers=_bearer(agent_token))
    assert forbidden.status_code == 403

    reviewed = client.put(
        f"/api/v1/calls/{call_id}/analysis",
        headers=headers,
        json={"qa_score": 88, "summary": "已确认回拨要求", "qa_flags": []},
    )
    assert reviewed.status_code == 200, reviewed.text
    reviewed_queue = client.get("/api/v1/quality/reviews?review_state=reviewed", headers=headers)
    assert any(row["call_id"] == str(call_id) and row["qa_score"] == 88 for row in reviewed_queue.json())


def test_flow_validation_rejects_unreachable_and_dead_end_nodes():
    unreachable = ScriptFlowGraph.model_validate({
        "nodes": [
            {"id": "start", "type": "start", "label": "start", "position": {"x": 0, "y": 0}},
            {"id": "end", "type": "hangup", "label": "end", "position": {"x": 1, "y": 0}},
            {"id": "orphan", "type": "hangup", "label": "orphan", "position": {"x": 2, "y": 0}},
        ],
        "edges": [{"id": "e1", "source": "start", "target": "end", "condition": "always", "keywords": []}],
    })
    with pytest.raises(FlowValidationError, match="unreachable"):
        validate_graph(unreachable)

    dead_end = ScriptFlowGraph.model_validate({
        "nodes": [
            {"id": "start", "type": "start", "label": "start", "position": {"x": 0, "y": 0}},
            {"id": "listen", "type": "listen", "label": "listen", "position": {"x": 1, "y": 0}},
        ],
        "edges": [{"id": "e1", "source": "start", "target": "listen", "condition": "always", "keywords": []}],
    })
    with pytest.raises(FlowValidationError, match="outgoing edge"):
        validate_graph(dead_end)


@pytest.mark.asyncio
async def test_durable_ai_task_is_idempotent_and_completes(monkeypatch):
    called: list[str] = []

    async def fake_run_ai_turn(*, call_id, transcript, durable=False):
        assert durable is True
        called.append(f"{call_id}:{transcript}")

    monkeypatch.setattr("app.services.dispatcher.run_ai_turn", fake_run_ai_turn)
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone="13800138991", mode=CallMode.AI_ONLY, status=CallStatus.ANSWERED)
        session.add(call)
        session.commit()
        session.refresh(call)
        first = enqueue_task(
            session,
            tenant_id=1,
            task_type="ai_turn",
            aggregate_id=str(call.id),
            idempotency_key=f"test-ai:{call.id}",
            payload={"call_id": str(call.id), "transcript": "hello"},
        )
        duplicate = enqueue_task(
            session,
            tenant_id=1,
            task_type="ai_turn",
            aggregate_id=str(call.id),
            idempotency_key=f"test-ai:{call.id}",
            payload={"call_id": str(call.id), "transcript": "hello"},
        )
        assert first.id == duplicate.id
        task_id = first.id
    assert await process_task(task_id) is True
    assert await process_task(task_id) is False
    assert len(called) == 1
    with session_scope() as session:
        task = session.get(TaskOutbox, task_id)
        assert task is not None and task.state == TaskState.COMPLETED


@pytest.mark.asyncio
async def test_scheduler_reclaims_stale_processing_task(monkeypatch):
    called: list[str] = []

    async def fake_run_ai_turn(*, call_id, transcript, durable=False):
        called.append(str(call_id))

    monkeypatch.setattr("app.services.dispatcher.run_ai_turn", fake_run_ai_turn)
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone="13800138993", mode=CallMode.AI_ONLY)
        session.add(call)
        session.commit()
        session.refresh(call)
        task = enqueue_task(
            session,
            tenant_id=1,
            task_type="ai_turn",
            aggregate_id=str(call.id),
            idempotency_key=f"stale-ai:{call.id}",
            payload={"call_id": str(call.id), "transcript": "recover"},
        )
        task.state = TaskState.PROCESSING
        task.attempts = 1
        task.locked_at = utc_now() - timedelta(minutes=6)
        session.add(task)
        session.commit()
        task_id = task.id

    assert await process_pending_tasks(batch_size=10) == 1
    assert len(called) == 1
    with session_scope() as session:
        recovered = session.get(TaskOutbox, task_id)
        assert recovered is not None
        assert recovered.state == TaskState.COMPLETED
        assert recovered.attempts == 2


@pytest.mark.asyncio
async def test_scheduler_marks_crashed_final_attempt_dead(monkeypatch):
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone="13800138996", mode=CallMode.AI_ONLY)
        session.add(call)
        session.commit()
        session.refresh(call)
        task = enqueue_task(
            session,
            tenant_id=1,
            task_type="ai_turn",
            aggregate_id=str(call.id),
            idempotency_key=f"exhausted-ai:{call.id}",
            payload={"call_id": str(call.id), "transcript": "final attempt"},
            max_attempts=1,
        )
        task.state = TaskState.PROCESSING
        task.attempts = 1
        task.locked_at = utc_now() - timedelta(minutes=6)
        session.add(task)
        session.commit()
        task_id = task.id
        call_id = call.id

    assert await process_pending_tasks(batch_size=10) == 0
    with session_scope() as session:
        exhausted = session.get(TaskOutbox, task_id)
        failed_call = session.get(CallSession, call_id)
        assert exhausted is not None and exhausted.state == TaskState.DEAD
        assert exhausted.locked_at is None
        assert failed_call is not None and failed_call.status == CallStatus.FAILED


@pytest.mark.asyncio
async def test_business_callback_task_is_durable_and_idempotent(monkeypatch):
    delivered: list[str] = []

    async def fake_delivery(*, call_id, raise_on_failure=False, **_):
        assert raise_on_failure is True
        delivered.append(str(call_id))
        return True

    monkeypatch.setattr("app.services.business_callbacks.deliver_business_callback", fake_delivery)
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone="13800138994", mode=CallMode.HUMAN_ONLY)
        session.add(call)
        session.commit()
        session.refresh(call)
        first = enqueue_business_callback(
            session,
            tenant_id=1,
            call_id=call.id,
            event_type="call.status",
            data={"status": "completed"},
        )
        duplicate = enqueue_business_callback(
            session,
            tenant_id=1,
            call_id=call.id,
            event_type="call.status",
            data={"status": "completed"},
        )
        assert first.id == duplicate.id
        task_id = first.id

    assert await process_task(task_id) is True
    assert await process_task(task_id) is False
    assert len(delivered) == 1


@pytest.mark.asyncio
async def test_retention_purges_partial_text_and_tombstones_recording(monkeypatch):
    deleted_assets: list[int] = []
    monkeypatch.setattr(
        "app.services.recording_storage.delete_recording_asset",
        lambda asset: deleted_assets.append(asset.id),
    )
    with session_scope() as session:
        session.add(AdminSetting(
            tenant_id=1,
            section="compliance",
            data_json=json.dumps({"partial_transcript_retention_hours": 1}),
        ))
        call = CallSession(tenant_id=1, phone="13800138992", mode=CallMode.HUMAN_ONLY)
        session.add(call)
        session.commit()
        session.refresh(call)
        partial = SpeechTurn(
            tenant_id=1,
            call_session_id=call.id,
            provider_event_key=f"retention-{call.id}",
            transcript="temporary partial",
            normalized_transcript="temporary partial",
            is_final=False,
            created_at=utc_now() - timedelta(hours=2),
        )
        recording = RecordingAsset(
            tenant_id=1,
            call_session_id=call.id,
            provider_url="https://example.invalid/recording.wav",
            storage_uri="s3://bucket/recording.wav",
            retention_until=utc_now() - timedelta(seconds=1),
        )
        session.add(partial)
        session.add(recording)
        session.commit()
        session.refresh(recording)
        recording_id = recording.id
    result = purge_expired_voice_data()
    assert result["partial_transcripts"] >= 1
    assert result["recordings"] >= 1
    with session_scope() as session:
        recording = session.get(RecordingAsset, recording_id)
        assert recording is not None
        assert recording.state == "deletion_pending"
        assert recording.provider_url
        assert recording.storage_uri
    assert await process_pending_tasks(batch_size=100) >= 1
    with session_scope() as session:
        recording = session.get(RecordingAsset, recording_id)
        assert recording is not None
        assert recording.state == "deleted"
        assert recording.provider_url == ""
        assert recording.storage_uri == ""
        assert recording.deleted_at is not None
    assert recording_id in deleted_assets


def test_partial_transcript_retention_uses_tenant_policy():
    with session_scope() as session:
        session.add(AdminSetting(
            tenant_id=1,
            section="compliance",
            data_json=json.dumps({"partial_transcript_retention_hours": 3}),
        ))
        call = CallSession(tenant_id=1, phone="13800138873", mode=CallMode.HUMAN_ONLY)
        session.add(call)
        session.flush()
        kept = SpeechTurn(
            tenant_id=1,
            call_session_id=call.id,
            provider_event_key=f"retention-kept-{call.id}",
            transcript="recent partial",
            normalized_transcript="recent partial",
            is_final=False,
            created_at=utc_now() - timedelta(hours=2),
        )
        expired = SpeechTurn(
            tenant_id=1,
            call_session_id=call.id,
            provider_event_key=f"retention-expired-{call.id}",
            transcript="expired partial",
            normalized_transcript="expired partial",
            is_final=False,
            created_at=utc_now() - timedelta(hours=4),
        )
        session.add(kept)
        session.add(expired)
        session.commit()
        kept_id = kept.id
        expired_id = expired.id

    result = purge_expired_voice_data(batch_size=500)
    assert result["partial_transcripts"] >= 1
    with session_scope() as session:
        assert session.get(SpeechTurn, kept_id) is not None
        assert session.get(SpeechTurn, expired_id) is None


@pytest.mark.asyncio
async def test_retention_preserves_location_when_remote_deletion_fails(monkeypatch):
    from app.services.recording_storage import RecordingDeletionError

    def fail_delete(_asset):
        raise RecordingDeletionError("storage unavailable")

    monkeypatch.setattr("app.services.recording_storage.delete_recording_asset", fail_delete)
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone="13800138995", mode=CallMode.HUMAN_ONLY)
        session.add(call)
        session.commit()
        session.refresh(call)
        recording = RecordingAsset(
            tenant_id=1,
            call_session_id=call.id,
            provider_url="https://example.invalid/keep.wav",
            storage_uri="s3://bucket/keep.wav",
            retention_until=utc_now() - timedelta(seconds=1),
        )
        session.add(recording)
        session.commit()
        session.refresh(recording)
        recording_id = recording.id

    result = purge_expired_voice_data()
    assert result["recording_deletion_tasks"] >= 1
    with session_scope() as session:
        task = session.exec(
            select(TaskOutbox).where(
                TaskOutbox.task_type == "recording_delete",
                TaskOutbox.aggregate_id == str(recording_id),
            )
        ).one()
        task.max_attempts = 1
        session.add(task)
        session.commit()
    assert await process_pending_tasks(batch_size=100) == 0
    with session_scope() as session:
        preserved = session.get(RecordingAsset, recording_id)
        task = session.exec(
            select(TaskOutbox).where(
                TaskOutbox.task_type == "recording_delete",
                TaskOutbox.aggregate_id == str(recording_id),
            )
        ).one()
        assert preserved is not None
        assert preserved.state == "deletion_failed"
        assert preserved.deleted_at is None
        assert preserved.provider_url == "https://example.invalid/keep.wav"
        assert preserved.storage_uri == "s3://bucket/keep.wav"
        assert task.state == TaskState.DEAD


def test_recording_storage_adapter_requires_remote_confirmation(monkeypatch):
    from app.services import recording_storage
    from app.services.recording_storage import RecordingDeletionError

    captured: dict[str, object] = {}

    class FakeResponse:
        content = b'{"deleted":true}'
        headers = {"content-type": "application/json"}

        def raise_for_status(self):
            return None

        def json(self):
            return {"deleted": True}

    class FakeClient:
        def __init__(self, *, timeout):
            captured["timeout"] = timeout

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return None

        def post(self, endpoint, *, headers, json):
            captured.update({"endpoint": endpoint, "headers": headers, "payload": json})
            return FakeResponse()

    monkeypatch.setattr(recording_storage.settings, "recording_delete_endpoint", "https://storage.example/delete")
    monkeypatch.setattr(recording_storage.settings, "recording_delete_service_token", "recording-delete-token")
    monkeypatch.setattr(recording_storage.httpx, "Client", FakeClient)
    asset = RecordingAsset(
        tenant_id=1,
        call_session_id=uuid4(),
        provider_recording_id="provider-recording-1",
        provider_url="https://provider.example/audio.wav",
        storage_uri="s3://bucket/audio.wav",
    )
    recording_storage.delete_recording_asset(asset)
    assert captured["endpoint"] == "https://storage.example/delete"
    assert captured["headers"] == {
        "Content-Type": "application/json",
        "Authorization": "Bearer recording-delete-token",
    }
    assert captured["payload"]["provider_recording_id"] == "provider-recording-1"

    monkeypatch.setattr(recording_storage.settings, "recording_delete_endpoint", "")
    with pytest.raises(RecordingDeletionError, match="not configured"):
        recording_storage.delete_recording_asset(asset)


def test_recording_storage_adapter_ingests_to_managed_storage(monkeypatch):
    from app.services import recording_storage
    from app.services.recording_storage import RecordingIngestError

    captured: dict[str, object] = {}

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"storage_uri": "s3://managed/recording.wav", "checksum_sha256": "a" * 64}

    class FakeClient:
        def __init__(self, *, timeout):
            captured["timeout"] = timeout

        def __enter__(self):
            return self

        def __exit__(self, *_):
            return None

        def post(self, endpoint, *, headers, json):
            captured.update({"endpoint": endpoint, "headers": headers, "payload": json})
            return FakeResponse()

    monkeypatch.setattr(recording_storage.settings, "recording_ingest_endpoint", "https://storage.example/ingest")
    monkeypatch.setattr(recording_storage.settings, "recording_ingest_service_token", "recording-ingest-token")
    monkeypatch.setattr(recording_storage.httpx, "Client", FakeClient)
    asset = RecordingAsset(
        tenant_id=1,
        call_session_id=uuid4(),
        provider_recording_id="provider-recording-2",
        provider_url="https://provider.example/audio.wav",
    )
    result = recording_storage.ingest_recording_asset(asset)
    assert result == {"storage_uri": "s3://managed/recording.wav", "checksum_sha256": "a" * 64}
    assert captured["endpoint"] == "https://storage.example/ingest"
    assert captured["headers"] == {
        "Content-Type": "application/json",
        "Authorization": "Bearer recording-ingest-token",
    }
    assert captured["payload"]["provider_recording_id"] == "provider-recording-2"

    monkeypatch.setattr(recording_storage.settings, "recording_ingest_endpoint", "")
    with pytest.raises(RecordingIngestError, match="not configured"):
        recording_storage.ingest_recording_asset(asset)


@pytest.mark.asyncio
async def test_recording_ingest_task_persists_managed_location(monkeypatch):
    monkeypatch.setattr(
        "app.services.recording_storage.ingest_recording_asset",
        lambda _asset: {"storage_uri": "s3://managed/task.wav", "checksum_sha256": "b" * 64},
    )
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone="13800138997", mode=CallMode.AI_ONLY)
        session.add(call)
        session.commit()
        session.refresh(call)
        asset = RecordingAsset(
            tenant_id=1,
            call_session_id=call.id,
            provider_url="https://provider.example/task.wav",
            state="available",
        )
        session.add(asset)
        session.commit()
        session.refresh(asset)
        asset_id = asset.id
        task = enqueue_task(
            session,
            tenant_id=1,
            task_type="recording_ingest",
            aggregate_id=str(asset_id),
            idempotency_key=f"recording-ingest-test:{asset_id}",
            payload={"recording_asset_id": asset_id},
        )
        task_id = task.id

    assert await process_task(task_id) is True
    with session_scope() as session:
        stored = session.get(RecordingAsset, asset_id)
        task = session.get(TaskOutbox, task_id)
        assert stored is not None
        assert stored.state == "stored"
        assert stored.storage_uri == "s3://managed/task.wav"
        assert stored.checksum_sha256 == "b" * 64
        assert task is not None and task.state == TaskState.COMPLETED


def test_pages_login_and_server_key_not_exposed(client: TestClient):
    for path in ("/admin", "/admin/contacts", "/admin/knowledge", "/agent", "/agent/calls"):
        response = client.get(path)
        assert response.status_code == 200, response.text
        assert '<div id="root"></div>' in response.text
        assert '/assets/' in response.text
        assert "dev-api-key" not in response.text
        assert "12345678" not in response.text

    admin_token = _login(client, "admin")
    agent_token = _login(client, "1001@test")
    assert client.get("/api/v1/auth/me", headers=_bearer(admin_token)).json()["role"] == "admin"
    assert client.get("/api/v1/auth/me", headers=_bearer(agent_token)).json()["role"] == "agent"

    with session_scope() as session:
        admin = session.exec(select(User).where(User.username == "admin")).first()
        assert admin is not None
        assert admin.password_hash.startswith("pbkdf2_sha256$")
        assert "12345678" not in admin.password_hash


def test_role_and_tenant_boundaries(client: TestClient):
    admin_token = _login(client, "admin")
    agent_token = _login(client, "1001@test")

    response = client.post(
        "/api/v1/contacts",
        headers=_bearer(admin_token),
        json={"phone": "+86 138-0013-8000", "name": "tenant-bound", "consent_state": "consented"},
    )
    assert response.status_code == 200, response.text
    assert response.json()["phone"] == "8613800138000"

    response = client.get("/api/v1/contacts", headers=_bearer(agent_token))
    assert response.status_code == 403

    assert client.get("/api/v1/contacts", headers={"x-api-key": "dev-api-key"}).status_code == 200
    api_cross_tenant = client.get(
        "/api/v1/contacts",
        headers={"x-api-key": "dev-api-key", "x-tenant-id": "2"},
    )
    assert api_cross_tenant.status_code == 403

    response = client.get("/api/v1/contacts", headers=_bearer("invalid-token"))
    assert response.status_code == 401

    response = client.get(
        "/api/v1/contacts",
        headers=_bearer(admin_token, **{"x-tenant-id": "2"}),
    )
    assert response.status_code == 403


def test_campaign_requires_explicit_contact_consent(client: TestClient):
    token = _login(client, "admin")
    headers = _bearer(token)
    contact = client.post(
        "/api/v1/contacts",
        headers=headers,
        json={"phone": "13800138881", "name": "unknown-consent", "consent_state": "unknown"},
    )
    assert contact.status_code == 200, contact.text
    campaign = client.post(
        "/api/v1/campaigns",
        headers=headers,
        json={"name": "consent-check", "mode": "human_only", "contact_ids": [contact.json()["id"]]},
    )
    assert campaign.status_code == 200, campaign.text
    started = client.post(f"/api/v1/campaigns/{campaign.json()['id']}/start?auto_dial=false", headers=headers)
    assert started.status_code == 200, started.text
    assert started.json()["result_code"] == "FAILED"
    assert "EXPLICIT_CONSENT_REQUIRED" in started.json()["error_codes"]


def test_max_dials_limits_persisted_async_queue(client: TestClient):
    token = _login(client, "admin")
    headers = _bearer(token)
    contact_ids = []
    for suffix in (3, 4):
        contact = client.post(
            "/api/v1/contacts",
            headers=headers,
            json={"phone": f"1380013888{suffix}", "name": f"dial-limit-{suffix}", "consent_state": "consented"},
        )
        assert contact.status_code == 200, contact.text
        contact_ids.append(contact.json()["id"])
    campaign = client.post(
        "/api/v1/campaigns",
        headers=headers,
        json={"name": "dial-limit", "mode": "human_only", "contact_ids": contact_ids},
    )
    assert campaign.status_code == 200, campaign.text
    started = client.post(
        f"/api/v1/campaigns/{campaign.json()['id']}/start?auto_dial=true&async_dial=true&max_dials=1",
        headers=headers,
    )
    assert started.status_code == 200, started.text
    assert started.json()["dispatch_result"]["target"] == 1
    calls = client.get(f"/api/v1/calls?campaign_id={campaign.json()['id']}", headers=headers)
    assert calls.status_code == 200, calls.text
    assert len(calls.json()) == 2
    assert sorted(call["status"] for call in calls.json()) == ["created", "queued"]


def test_contact_phone_is_unique_and_referenced_contact_cannot_be_deleted(client: TestClient):
    token = _login(client, "admin")
    headers = _bearer(token)
    created = client.post(
        "/api/v1/contacts",
        headers=headers,
        json={"phone": "+86 138 0013 8010", "name": "integrity-contact", "consent_state": "consented"},
    )
    assert created.status_code == 200, created.text
    duplicate = client.post(
        "/api/v1/contacts",
        headers=headers,
        json={"phone": "8613800138010", "name": "duplicate", "consent_state": "consented"},
    )
    assert duplicate.status_code == 409, duplicate.text

    campaign = client.post(
        "/api/v1/campaigns",
        headers=headers,
        json={
            "name": "integrity-campaign",
            "mode": "human_only",
            "contact_ids": [created.json()["id"]],
        },
    )
    assert campaign.status_code == 200, campaign.text
    blocked = client.delete(f"/api/v1/contacts/{created.json()['id']}", headers=headers)
    assert blocked.status_code == 409, blocked.text


def test_campaign_crud_and_structured_sync_dispatch(client: TestClient):
    token = _login(client, "admin")
    headers = _bearer(token)

    contact = client.post(
        "/api/v1/contacts",
        headers=headers,
        json={"phone": "13800138001", "name": "campaign-contact", "consent_state": "consented"},
    )
    assert contact.status_code == 200, contact.text

    template = client.post(
        "/api/v1/script-templates",
        headers=headers,
        json={"name": "acceptance-script", "content": "您好，这是验收话术", "is_active": True},
    )
    assert template.status_code == 200, template.text

    payload = {
        "name": "acceptance-campaign",
        "mode": "ai_handoff",
        "script_template_id": template.json()["id"],
        "contact_ids": [contact.json()["id"]],
        "concurrency": 2,
        "retry_limit": 2,
    }
    campaign = client.post("/api/v1/campaigns", headers=headers, json=payload)
    assert campaign.status_code == 200, campaign.text
    assert campaign.json()["contact_ids"] == [contact.json()["id"]]
    campaign_id = campaign.json()["id"]

    updated = client.put(
        f"/api/v1/campaigns/{campaign_id}",
        headers=headers,
        json={**payload, "name": "acceptance-campaign-updated"},
    )
    assert updated.status_code == 200, updated.text
    assert updated.json()["name"] == "acceptance-campaign-updated"

    started = client.post(
        f"/api/v1/campaigns/{campaign_id}/start?auto_dial=true&async_dial=false&max_dials=1",
        headers=headers,
    )
    assert started.status_code == 200, started.text
    body = started.json()
    assert body["result_code"] == "SUCCESS"
    assert body["dispatch_mode"] == "sync"
    assert body["dispatch_result"]["status"] == "completed"
    assert body["dispatch_result"]["succeeded"] == 1

    calls = client.get(f"/api/v1/calls?campaign_id={campaign_id}", headers=headers)
    assert calls.status_code == 200, calls.text
    assert len(calls.json()) == 1
    assert calls.json()[0]["attempts"] == 1


def test_concurrent_dispatch_claims_once(client: TestClient):
    with session_scope() as session:
        call = create_call(
            session,
            tenant_id=1,
            phone="13800138002",
            mode=CallMode.AI_ONLY,
            campaign_id=None,
            contact_id=None,
            max_attempts=2,
        )
        call_id = str(call.id)

    async def _race():
        return await asyncio.gather(
            dispatch_call_ids([call_id], max_concurrency=1),
            dispatch_call_ids([call_id], max_concurrency=1),
        )

    first, second = asyncio.run(_race())
    assert sorted([first["succeeded"], second["succeeded"]]) == [0, 1]
    loser = first if first["succeeded"] == 0 else second
    assert loser["error_codes"] == ["CALL_NOT_DISPATCHABLE"]

    with session_scope() as session:
        persisted = session.get(CallSession, call.id)
        assert persisted is not None
        assert persisted.attempts == 1


@pytest.mark.asyncio
async def test_retry_attempt_is_included_in_provider_metadata(client: TestClient, monkeypatch):
    captured_attempts: list[int] = []
    captured_metadata: list[dict] = []

    class CapturingAdapter:
        async def dial(self, *, call_id, phone, webhook_url, metadata):
            captured_attempts.append(metadata["attempt"])
            captured_metadata.append(metadata)
            return {"provider_call_id": f"capture-{call_id}-{metadata['attempt']}"}

    monkeypatch.setattr(
        "app.services.call_service.get_telephony_adapter",
        lambda **_: CapturingAdapter(),
    )
    with session_scope() as session:
        call = create_call(
            session,
            tenant_id=1,
            phone="13800138013",
            mode=CallMode.HUMAN_ONLY,
            campaign_id=None,
            contact_id=None,
            max_attempts=2,
        )
        call = await place_call(session, call)
        call.status = CallStatus.COMPLETED
        session.add(call)
        session.commit()
        await retry_call(session, tenant_id=1, call_id=call.id)

    assert captured_attempts == [1, 2]
    assert all(item["speech_webhook_url"].endswith("/api/v1/webhooks/telephony/speech") for item in captured_metadata)
    assert all(item["media_webhook_url"].endswith("/api/v1/webhooks/telephony/media") for item in captured_metadata)


def test_tenant_telephony_mode_selects_line_and_rejects_direct_sip(monkeypatch):
    class Result:
        def __init__(self, line):
            self.line = line

        def first(self):
            return self.line

    class FakeSession:
        def __init__(self, line):
            self.line = line

        def exec(self, _query):
            return Result(self.line)

    monkeypatch.setattr(telephony.settings, "telephony_provider", "tenant")
    mock_line = TelephonyLine(
        tenant_id=1,
        name="tenant-mock",
        provider="mock",
        gateway_url="",
    )
    assert isinstance(
        telephony.get_telephony_adapter(session=FakeSession(mock_line), tenant_id=1),
        telephony.MockAdapter,
    )

    sip_line = TelephonyLine(
        tenant_id=1,
        name="tenant-sip",
        provider="sip",
        gateway_url="sip:carrier.example.com",
    )
    with pytest.raises(RuntimeError, match="HTTP bridge"):
        telephony.get_telephony_adapter(session=FakeSession(sip_line), tenant_id=1)


def test_webhook_is_idempotent_and_late_answer_cannot_reopen_call(client: TestClient):
    token = _login(client, "admin")
    created = client.post(
        "/api/v1/calls",
        headers=_bearer(token),
        json={"phone": "13800138003", "mode": "human_only", "max_attempts": 1},
    )
    assert created.status_code == 200, created.text
    call_id = created.json()["id"]

    answered = {
        "call_id": call_id,
        "kind": "status",
        "payload": {"status": "answered", "event_id": "provider-answer-1"},
    }
    assert client.post("/api/v1/webhooks/telephony/status", json=answered).status_code == 200
    assert client.post("/api/v1/webhooks/telephony/status", json=answered).status_code == 200

    ended = {
        "call_id": call_id,
        "kind": "status",
        "payload": {"status": "ended", "event_id": "provider-ended-1"},
    }
    assert client.post("/api/v1/webhooks/telephony/status", json=ended).status_code == 200

    late_answer = {
        "call_id": call_id,
        "kind": "status",
        "payload": {"status": "answered", "event_id": "provider-late-answer-2"},
    }
    assert client.post("/api/v1/webhooks/telephony/status", json=late_answer).status_code == 200

    call = client.get(f"/api/v1/calls/{call_id}", headers=_bearer(token))
    assert call.status_code == 200, call.text
    assert call.json()["status"] == CallStatus.COMPLETED.value

    stats = client.get(f"/api/v1/calls/{call_id}/webhook-stats", headers=_bearer(token))
    assert stats.status_code == 200, stats.text
    assert stats.json()["duplicate_estimate"] >= 1
    events = client.get(f"/api/v1/calls/{call_id}/events", headers=_bearer(token))
    assert events.status_code == 200, events.text


def test_stale_attempt_webhooks_are_recorded_but_cannot_mutate_current_call(client: TestClient):
    token = _login(client, "admin")
    created = client.post(
        "/api/v1/calls",
        headers=_bearer(token),
        json={"phone": "13800138014", "mode": "human_only", "max_attempts": 2},
    )
    assert created.status_code == 200, created.text
    call_id = UUID(created.json()["id"])
    with session_scope() as session:
        call = session.get(CallSession, call_id)
        assert call is not None
        call.attempts = 2
        call.status = CallStatus.DIALING
        session.add(call)
        session.commit()

    stale = client.post(
        "/api/v1/webhooks/telephony/status",
        json={
            "call_id": str(call_id),
            "kind": "status",
            "payload": {"status": "ended", "attempt": 1, "event_id": "old-attempt-ended"},
        },
    )
    assert stale.status_code == 200, stale.text
    assert stale.json() == {"result": "ignored", "reason": "stale_attempt"}
    with session_scope() as session:
        call = session.get(CallSession, call_id)
        assert call is not None
        assert call.status == CallStatus.DIALING


@pytest.mark.asyncio
async def test_dispatch_respects_effective_line_concurrency(client: TestClient, monkeypatch):
    active = 0
    max_active = 0

    class SlowAdapter:
        async def dial(self, *, call_id, phone, webhook_url, metadata):
            nonlocal active, max_active
            active += 1
            max_active = max(max_active, active)
            await asyncio.sleep(0.02)
            active -= 1
            return {"provider_call_id": f"slow-{call_id}"}

    monkeypatch.setattr("app.services.call_service.get_telephony_adapter", lambda **_: SlowAdapter())
    monkeypatch.setattr("app.services.call_service.get_telephony_concurrency_limit", lambda **_: 1)
    call_ids: list[str] = []
    with session_scope() as session:
        for suffix in range(3):
            call = create_call(
                session,
                tenant_id=1,
                phone=f"138001381{suffix:02d}",
                mode=CallMode.HUMAN_ONLY,
                campaign_id=None,
                contact_id=None,
            )
            call_ids.append(str(call.id))

    result = await dispatch_call_ids(call_ids, max_concurrency=10)
    assert result["succeeded"] == 3
    assert max_active == 1


@pytest.mark.asyncio
async def test_tenant_line_capacity_is_enforced_across_dispatch_batches(client: TestClient, monkeypatch):
    monkeypatch.setattr(telephony.settings, "telephony_provider", "tenant")

    class CapturingAdapter:
        async def dial(self, *, call_id, phone, webhook_url, metadata):
            return {"provider_call_id": f"capacity-{call_id}"}

    monkeypatch.setattr("app.services.call_service.get_telephony_adapter", lambda **_: CapturingAdapter())
    call_ids: list[str] = []
    with session_scope() as session:
        for existing_call in session.exec(
            select(CallSession).where(
                CallSession.tenant_id == 1,
                CallSession.status.in_(
                    {
                        CallStatus.DIALING,
                        CallStatus.ANSWERED,
                        CallStatus.IN_AI,
                        CallStatus.WAITING_HUMAN,
                        CallStatus.HANDOFF_TRANSFERRING,
                        CallStatus.IN_HUMAN,
                    }
                ),
            )
        ).all():
            existing_call.status = CallStatus.COMPLETED
            session.add(existing_call)
        for line in session.exec(select(TelephonyLine).where(TelephonyLine.tenant_id == 1)).all():
            line.enabled = False
            session.add(line)
        session.add(
            TelephonyLine(
                tenant_id=1,
                name="hard-cap-one",
                provider="mock",
                gateway_url="",
                max_concurrency=1,
                enabled=True,
                created_at=utc_now() + timedelta(days=1),
            )
        )
        session.commit()
        for suffix in range(2):
            call = create_call(
                session,
                tenant_id=1,
                phone=f"138001382{suffix:02d}",
                mode=CallMode.HUMAN_ONLY,
                campaign_id=None,
                contact_id=None,
            )
            call_ids.append(str(call.id))

    first = await dispatch_call_ids([call_ids[0]], max_concurrency=10)
    second = await dispatch_call_ids([call_ids[1]], max_concurrency=10)
    assert first["succeeded"] == 1
    assert second["succeeded"] == 0
    with session_scope() as session:
        assert session.get(CallSession, UUID(call_ids[0])).status == CallStatus.DIALING
        assert session.get(CallSession, UUID(call_ids[1])).status == CallStatus.QUEUED


@pytest.mark.asyncio
async def test_admin_capacity_setting_takes_effect_without_restart(client: TestClient, monkeypatch):
    token = _login(client, "admin")
    headers = _bearer(token)
    saved = client.put(
        "/api/v1/admin/settings/capacity",
        headers=headers,
        json={"data": {"max_concurrent_calls": 1}},
    )
    assert saved.status_code == 200, saved.text
    assert saved.json()["data"]["max_concurrent_calls"] == 1

    overview = client.get("/api/v1/admin/system-overview", headers=headers)
    assert overview.status_code == 200, overview.text
    assert overview.json()["capacity"]["configured_max_concurrent_calls"] == 1
    assert overview.json()["capacity"]["effective_max_concurrent_calls"] == 1
    assert overview.json()["operations"]["stale_processing_tasks"] >= 0
    assert overview.json()["operations"]["oldest_open_task_age_sec"] >= 0
    assert overview.json()["operations"]["recording_deletion_failures"] >= 0

    class CapturingAdapter:
        async def dial(self, *, call_id, phone, webhook_url, metadata):
            return {"provider_call_id": f"runtime-capacity-{call_id}"}

    monkeypatch.setattr("app.services.call_service.get_telephony_adapter", lambda **_: CapturingAdapter())
    call_ids: list[str] = []
    with session_scope() as session:
        for existing_call in session.exec(
            select(CallSession).where(
                CallSession.tenant_id == 1,
                CallSession.status.in_(
                    {
                        CallStatus.DIALING,
                        CallStatus.ANSWERED,
                        CallStatus.IN_AI,
                        CallStatus.WAITING_HUMAN,
                        CallStatus.HANDOFF_TRANSFERRING,
                        CallStatus.IN_HUMAN,
                    }
                ),
            )
        ).all():
            existing_call.status = CallStatus.COMPLETED
            session.add(existing_call)
        session.commit()
        for suffix in range(2):
            call = create_call(
                session,
                tenant_id=1,
                phone=f"138001383{suffix:02d}",
                mode=CallMode.HUMAN_ONLY,
                campaign_id=None,
                contact_id=None,
            )
            call_ids.append(str(call.id))

    try:
        result = await dispatch_call_ids(call_ids, max_concurrency=10)
        assert result["succeeded"] == 1
        assert result["failed"] == 1
        with session_scope() as session:
            states = [session.get(CallSession, UUID(call_id)).status for call_id in call_ids]
            assert states.count(CallStatus.DIALING) == 1
            assert states.count(CallStatus.QUEUED) == 1
    finally:
        reset = client.put(
            "/api/v1/admin/settings/capacity",
            headers=headers,
            json={"data": {"max_concurrent_calls": 20}},
        )
        assert reset.status_code == 200, reset.text


@pytest.mark.asyncio
async def test_due_campaign_retry_is_persisted_and_dispatched(client: TestClient, monkeypatch):
    token = _login(client, "admin")
    headers = _bearer(token)
    contact = client.post(
        "/api/v1/contacts",
        headers=headers,
        json={"phone": "13800138015", "name": "retry-contact", "consent_state": "consented"},
    ).json()
    campaign = client.post(
        "/api/v1/campaigns",
        headers=headers,
        json={
            "name": "scheduled-retry",
            "mode": "human_only",
            "contact_ids": [contact["id"]],
            "retry_limit": 2,
            "retry_interval_sec": 2,
            "attempt_interval_sec": 3,
        },
    ).json()
    started = client.post(f"/api/v1/campaigns/{campaign['id']}/start?auto_dial=true", headers=headers)
    assert started.status_code == 200, started.text
    call_id = UUID(client.get(f"/api/v1/calls?campaign_id={campaign['id']}", headers=headers).json()[0]["id"])

    with session_scope() as session:
        call = session.get(CallSession, call_id)
        assert call is not None
        call.attempts = 1
        call.status = CallStatus.BUSY
        assert schedule_campaign_retry(session, call, CallStatus.BUSY) is True
        assert call.next_attempt_at is not None
        assert call.next_attempt_at >= utc_now() + timedelta(seconds=2)
        call.next_attempt_at = utc_now() - timedelta(seconds=1)
        session.add(call)
        session.commit()

    attempts: list[int] = []

    class CapturingAdapter:
        async def dial(self, *, call_id, phone, webhook_url, metadata):
            attempts.append(metadata["attempt"])
            return {"provider_call_id": f"retry-{call_id}-{metadata['attempt']}"}

    monkeypatch.setattr("app.services.call_service.get_telephony_adapter", lambda **_: CapturingAdapter())
    with session_scope() as session:
        for active_call in session.exec(
            select(CallSession).where(
                CallSession.tenant_id == 1,
                CallSession.status.in_(
                    {
                        CallStatus.DIALING,
                        CallStatus.ANSWERED,
                        CallStatus.IN_AI,
                        CallStatus.WAITING_HUMAN,
                        CallStatus.HANDOFF_TRANSFERRING,
                        CallStatus.IN_HUMAN,
                    }
                ),
            )
        ).all():
            if active_call.id != call_id:
                active_call.status = CallStatus.COMPLETED
                session.add(active_call)
        session.commit()
    claimed = await dispatch_due_retries()
    assert claimed == 1
    assert attempts == [2]
    with session_scope() as session:
        call = session.get(CallSession, call_id)
        assert call is not None
        assert call.attempts == 2
        assert call.status == CallStatus.DIALING
        assert call.next_attempt_at is None
        assert session.get(Campaign, campaign["id"]).status == "running"


@pytest.mark.asyncio
async def test_ai_events_are_extensible_and_mode_uses_wire_value(client: TestClient, monkeypatch):
    with session_scope() as session:
        call = create_call(
            session,
            tenant_id=1,
            phone="13800138004",
            mode=CallMode.AI_ONLY,
            campaign_id=None,
            contact_id=None,
            max_attempts=1,
        )
        call.status = CallStatus.ANSWERED
        session.add(call)
        session.commit()
        call_id = call.id

    captured: dict[str, str] = {}

    async def _fake_ai_turn(**kwargs):
        captured["mode"] = kwargs["mode"]
        return AiTurnResult(action="speak", tts_text="ok")

    class SpeakingAdapter:
        async def speak(self, **kwargs):
            captured["spoken"] = kwargs["text"]
            return {"result": "spoken"}

    monkeypatch.setattr(dispatcher, "request_ai_turn", _fake_ai_turn)
    monkeypatch.setattr(dispatcher, "get_telephony_adapter", lambda **_: SpeakingAdapter())
    await dispatcher.run_ai_turn(call_id=call_id, transcript="hello")

    with session_scope() as session:
        persisted = session.get(CallSession, call_id)
        assert persisted is not None
        assert persisted.status == CallStatus.IN_AI
        event_types = session.exec(
            select(CallEvent.event_type).where(CallEvent.call_session_id == call_id)
        ).all()
        assert "ai_start" in event_types
    assert captured["mode"] == "ai_only"
    assert captured["spoken"] == "ok"


@pytest.mark.asyncio
async def test_business_callback_posts_and_records_delivery(client: TestClient, monkeypatch):
    token = _login(client, "admin")
    headers = _bearer(token)
    updated = client.put(
        "/api/v1/admin/settings/integration",
        headers=headers,
        json={
            "data": {
                "callback_enabled": True,
                "webhook_base_url": "https://customer.example.com/callback",
                "webhook_timeout_sec": 5,
            }
        },
    )
    assert updated.status_code == 200, updated.text
    with session_scope() as session:
        call = create_call(
            session,
            tenant_id=1,
            phone="13800138014",
            mode=CallMode.HUMAN_ONLY,
            campaign_id=None,
            contact_id=None,
            max_attempts=1,
        )
        call_id = call.id

    delivered: dict[str, object] = {}

    class FakeResponse:
        status_code = 204

        def raise_for_status(self):
            return None

    class FakeClient:
        def __init__(self, **kwargs):
            delivered["timeout"] = kwargs["timeout"]

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_):
            return None

        async def post(self, url, content, headers):
            delivered["url"] = url
            delivered["payload"] = business_callbacks.json.loads(content)
            delivered["headers"] = headers
            return FakeResponse()

    monkeypatch.setattr(business_callbacks.httpx, "AsyncClient", FakeClient)
    await business_callbacks.deliver_business_callback(
        tenant_id=1,
        call_id=call_id,
        event_type="call.status",
        data={"status": "completed"},
    )
    assert delivered["url"] == "https://customer.example.com/callback"
    assert delivered["timeout"] == 5
    assert delivered["payload"]["call_id"] == str(call_id)
    with session_scope() as session:
        events = session.exec(select(CallEvent).where(CallEvent.call_session_id == call_id)).all()
        assert any(event.event_type == "business_callback_delivered" for event in events)
    disabled = client.put(
        "/api/v1/admin/settings/integration",
        headers=headers,
        json={
            "data": {
                "callback_enabled": False,
                "webhook_base_url": "",
                "webhook_timeout_sec": 10,
            }
        },
    )
    assert disabled.status_code == 200, disabled.text


def test_agent_handover_assigns_authenticated_agent(client: TestClient):
    agent_token = _login(client, "1001@test")
    with session_scope() as session:
        agent = session.exec(select(User).where(User.username == "1001@test")).first()
        assert agent is not None
        call = create_call(
            session,
            tenant_id=agent.tenant_id,
            phone="13800138015",
            mode=CallMode.AI_HANDOFF,
            campaign_id=None,
            contact_id=None,
            max_attempts=1,
        )
        call.status = CallStatus.IN_AI
        session.add(call)
        session.commit()
        call_id = call.id
        agent_id = agent.id

    response = client.post(
        f"/api/v1/calls/{call_id}/handover?reason=agent_accept",
        headers=_bearer(agent_token),
    )
    assert response.status_code == 200, response.text
    assert response.json()["human_agent_id"] == agent_id
    assert response.json()["status"] == "waiting_human"


@pytest.mark.asyncio
async def test_campaign_disables_hangup_sms_and_passes_language_context(client: TestClient, monkeypatch):
    token = _login(client, "admin")
    headers = _bearer(token)
    contact = client.post(
        "/api/v1/contacts",
        headers=headers,
        json={"phone": "13800138011", "name": "flag-contact", "consent_state": "consented"},
    ).json()
    campaign = client.post(
        "/api/v1/campaigns",
        headers=headers,
        json={
            "name": "flag-campaign",
            "mode": "ai_with_sms",
            "contact_ids": [contact["id"]],
            "recording_enabled": False,
            "hangup_sms_enabled": False,
        },
    ).json()
    started = client.post(
        f"/api/v1/campaigns/{campaign['id']}/start?auto_dial=false",
        headers=headers,
    )
    assert started.status_code == 200, started.text
    calls = client.get(f"/api/v1/calls?campaign_id={campaign['id']}", headers=headers).json()
    assert len(calls) == 1

    captured: dict[str, object] = {}

    async def _fake_ai_turn(**kwargs):
        captured.update(kwargs["context"])
        return AiTurnResult(action="speak", tts_text="ok", hangup_sms="must be suppressed")

    monkeypatch.setattr(dispatcher, "request_ai_turn", _fake_ai_turn)
    with session_scope() as session:
        call = session.get(CallSession, UUID(calls[0]["id"]))
        assert call is not None
        call.status = CallStatus.ANSWERED
        session.add(call)
        session.commit()
    await dispatcher.run_ai_turn(call_id=UUID(calls[0]["id"]), transcript="hello")
    assert captured["recording_enabled"] is False
    assert captured["hangup_sms_enabled"] is False
    assert captured["language"] in {"zh-CN", "en-US"}
    with session_scope() as session:
        sms_count = session.exec(select(SmsLog).where(SmsLog.call_session_id == UUID(calls[0]["id"]))).all()
        assert sms_count == []


def test_campaign_pause_resume_stop_lifecycle(client: TestClient):
    token = _login(client, "admin")
    headers = _bearer(token)
    contact = client.post(
        "/api/v1/contacts",
        headers=headers,
        json={"phone": "13800138012", "name": "lifecycle-contact", "consent_state": "consented"},
    ).json()
    campaign = client.post(
        "/api/v1/campaigns",
        headers=headers,
        json={"name": "lifecycle", "mode": "human_only", "contact_ids": [contact["id"]]},
    ).json()
    started = client.post(f"/api/v1/campaigns/{campaign['id']}/start?auto_dial=false", headers=headers)
    assert started.status_code == 200, started.text
    assert started.json()["campaign_status"] == "prepared"
    repeated = client.post(f"/api/v1/campaigns/{campaign['id']}/start?auto_dial=false", headers=headers)
    assert repeated.status_code == 200, repeated.text
    assert repeated.json()["created"] == 0
    resumed = client.post(f"/api/v1/campaigns/{campaign['id']}/resume", headers=headers)
    assert resumed.status_code == 200, resumed.text
    assert resumed.json()["status"] == "running"
    paused = client.post(f"/api/v1/campaigns/{campaign['id']}/pause", headers=headers)
    assert paused.status_code == 200, paused.text
    assert paused.json()["status"] == "paused"
    resumed_again = client.post(f"/api/v1/campaigns/{campaign['id']}/resume", headers=headers)
    assert resumed_again.status_code == 200, resumed_again.text
    assert resumed_again.json()["status"] == "running"
    stopped = client.post(f"/api/v1/campaigns/{campaign['id']}/stop", headers=headers)
    assert stopped.status_code == 200, stopped.text
    assert stopped.json()["status"] == "stopped"


def test_admin_management_crud_settings_and_audit(client: TestClient, monkeypatch):
    approval = "synthetic-independent-security-approval-token"
    monkeypatch.setattr(app_main.settings, "outbound_security_approval_token", approval)
    admin_token = _login(client, "admin")
    agent_token = _login(client, "1001@test")
    headers = _bearer(admin_token)

    headers["x-security-approval"] = approval

    assert client.get("/api/v1/admin/users", headers=_bearer(agent_token)).status_code == 403

    created_user = client.post(
        "/api/v1/admin/users",
        headers=headers,
        json={
            "username": "acceptance-agent",
            "password": "acceptance-pass-123",
            "full_name": "验收座席",
            "phone": "13800138999",
            "role": "agent",
            "enabled": True,
        },
    )
    assert created_user.status_code == 200, created_user.text
    user_id = created_user.json()["id"]

    updated_user = client.put(
        f"/api/v1/admin/users/{user_id}",
        headers=headers,
        json={"full_name": "验收座席已更新", "is_supervisor": True},
    )
    assert updated_user.status_code == 200, updated_user.text
    assert updated_user.json()["full_name"] == "验收座席已更新"
    assert updated_user.json()["is_supervisor"] is True

    password_reset = client.post(
        f"/api/v1/admin/users/{user_id}/reset-password",
        headers=headers,
        json={"password": "new-acceptance-pass-123"},
    )
    assert password_reset.status_code == 200, password_reset.text
    assert _login(client, "acceptance-agent", "new-acceptance-pass-123")

    created_line = client.post(
        "/api/v1/admin/lines",
        headers=headers,
        json={
            "name": "acceptance-http-bridge",
            "provider": "http",
            "gateway_url": "https://voice.example.com",
            "caller_id": "4008000000",
            "max_concurrency": 20,
            "enabled": True,
        },
    )
    assert created_line.status_code == 200, created_line.text
    line_id = created_line.json()["id"]
    updated_line = client.put(
        f"/api/v1/admin/lines/{line_id}",
        headers=headers,
        json={"max_concurrency": 30},
    )
    assert updated_line.status_code == 200, updated_line.text
    assert updated_line.json()["max_concurrency"] == 30

    setting = client.put(
        "/api/v1/admin/settings/compliance",
        headers=headers,
        json={
            "data": {
                "dnc_enforced": True,
                "recording_notice": True,
                "allowed_start_hour": 8,
                "allowed_end_hour": 19,
                "timezone": "Asia/Shanghai",
                "max_attempts_per_day": 2,
                "min_attempt_interval_sec": 900,
                "recording_retention_days": 60,
                "partial_transcript_retention_hours": 12,
            }
        },
    )
    assert setting.status_code == 200, setting.text
    assert setting.json()["data"]["allowed_end_hour"] == 19
    assert setting.json()["data"]["min_attempt_interval_sec"] == 900
    assert setting.json()["data"]["recording_retention_days"] == 60
    assert setting.json()["data"]["partial_transcript_retention_hours"] == 12
    assert client.get("/api/v1/admin/settings/compliance", headers=headers).json()["data"]["max_attempts_per_day"] == 2

    invalid_retention = client.put(
        "/api/v1/admin/settings/compliance",
        headers=headers,
        json={"data": {"recording_retention_days": 0}},
    )
    assert invalid_retention.status_code == 400

    invalid_setting = client.put(
        "/api/v1/admin/settings/ai",
        headers=headers,
        json={"data": {"api_key": "must-not-be-stored"}},
    )
    assert invalid_setting.status_code == 400

    pipeline_setting = client.put(
        "/api/v1/admin/settings/ai",
        headers=headers,
        json={"data": {"voice_ai_pipeline": "legacy", "pipecat_canary_percent": 0}},
    )
    assert pipeline_setting.status_code == 200, pipeline_setting.text
    assert pipeline_setting.json()["data"]["voice_ai_pipeline"] == "legacy"
    assert pipeline_setting.json()["data"]["pipecat_canary_percent"] == 0

    overview = client.get("/api/v1/admin/system-overview", headers=headers)
    assert overview.status_code == 200, overview.text
    assert overview.json()["resources"]["users"] >= 3
    assert overview.json()["resources"]["lines"] >= 1

    audits = client.get("/api/v1/admin/audit-logs", headers=headers)
    assert audits.status_code == 200, audits.text
    actions = {item["action"] for item in audits.json()}
    assert {"create", "update", "reset_password"}.issubset(actions)
    # This test creates an HTTP line; do not route later mock tests to it.
    assert client.delete(f"/api/v1/admin/lines/{line_id}", headers=headers).status_code == 200


def test_compliance_interval_blocks_repeat_but_not_current_dispatch(client: TestClient):
    # Other scenarios intentionally leave active mock calls. This test is
    # about per-number intervals, not exhaustion of those scenarios' lines.
    from app.services.call_service import CAPACITY_STATUSES
    with session_scope() as session:
        for prior in session.exec(select(CallSession).where(CallSession.status.in_(CAPACITY_STATUSES))).all():
            prior.status = CallStatus.COMPLETED
            session.add(prior)
        session.commit()
    token = _login(client, "admin")
    headers = _bearer(token)
    setting = client.put(
        "/api/v1/admin/settings/compliance",
        headers=headers,
        json={"data": {"min_attempt_interval_sec": 3600}},
    )
    assert setting.status_code == 200, setting.text

    first = client.post(
        "/api/v1/calls",
        headers=headers,
        json={"phone": "13800138871", "mode": "human_only", "max_attempts": 1},
    )
    assert first.status_code == 200, first.text
    assert first.json()["attempts"] == 1, first.json()

    repeated = client.post(
        "/api/v1/calls",
        headers=headers,
        json={"phone": "13800138871", "mode": "human_only", "max_attempts": 1},
    )
    assert repeated.status_code == 403, repeated.text
    assert repeated.json()["message"] == "retry_interval_not_elapsed"


def test_admin_call_evidence_export_contains_voice_qa_and_audit(client: TestClient):
    token = _login(client, "admin")
    agent_token = _login(client, "1001@test")
    with session_scope() as session:
        call = CallSession(
            tenant_id=1,
            phone="13800138872",
            mode=CallMode.AI_HANDOFF,
            status=CallStatus.COMPLETED,
            attempts=1,
            max_attempts=2,
            started_at=utc_now() - timedelta(seconds=20),
            finished_at=utc_now(),
        )
        session.add(call)
        session.flush()
        session.add(CallMetric(tenant_id=1, call_session_id=call.id, stage="asr.final", duration_ms=135))
        session.add(SpeechTurn(
            tenant_id=1,
            call_session_id=call.id,
            provider_event_key=f"export-{call.id}",
            transcript="有兴趣，请联系我",
            normalized_transcript="有兴趣，请联系我",
            is_final=True,
        ))
        session.add(CallAnalysis(
            tenant_id=1,
            call_session_id=call.id,
            result_code="qualified_lead",
            intent="interested",
            sentiment="positive",
            qa_score=96,
            summary='=HYPERLINK("https://unsafe.invalid","customer")',
        ))
        session.add(RecordingAsset(
            tenant_id=1,
            call_session_id=call.id,
            provider_url="https://recording.invalid/evidence.wav",
            storage_uri="s3://evidence/evidence.wav",
            state="available",
            retention_until=utc_now() + timedelta(days=30),
        ))
        session.commit()
        call_id = str(call.id)

    assert client.get("/api/v1/admin/calls/export", headers=_bearer(agent_token)).status_code == 403
    invalid = client.get("/api/v1/admin/calls/export?status=not-a-status", headers=_bearer(token))
    assert invalid.status_code == 400, invalid.text
    exported = client.get("/api/v1/admin/calls/export?days=30&status=completed", headers=_bearer(token))
    assert exported.status_code == 200, exported.text
    assert "text/csv" in exported.headers["content-type"]
    rows = list(csv.DictReader(io.StringIO(exported.text)))
    row = next(item for item in rows if item["call_id"] == call_id)
    assert row["status"] == "completed"
    assert row["mode"] == "ai_handoff"
    assert row["result_code"] == "qualified_lead"
    assert row["metric_total_duration_ms"] == "135"
    assert row["final_speech_turn_count"] == "1"
    assert row["recording_storage_uris"] == "s3://evidence/evidence.wav"
    assert row["analysis_summary"].startswith("'=HYPERLINK")

    audits = client.get("/api/v1/admin/audit-logs", headers=_bearer(token)).json()
    assert any(item["action"] == "export" and item["resource_type"] == "call_evidence" for item in audits)


def test_runtime_migration_upgrades_existing_tables(tmp_path):
    migration_db = tmp_path / "legacy.db"
    migration_engine = create_engine(f"sqlite:///{migration_db}")
    with migration_engine.begin() as connection:
        connection.execute(text("CREATE TABLE callsession (id VARCHAR PRIMARY KEY, status VARCHAR)"))
        connection.execute(text("CREATE TABLE telephonyline (id INTEGER PRIMARY KEY, name VARCHAR)"))
        connection.execute(text('CREATE TABLE "user" (id INTEGER PRIMARY KEY, username VARCHAR)'))
        connection.execute(text("CREATE TABLE smslog (id INTEGER PRIMARY KEY, created_at TIMESTAMP)"))
    apply_runtime_migrations(migration_engine)
    call_columns = {item["name"] for item in inspect(migration_engine).get_columns("callsession")}
    line_columns = {item["name"] for item in inspect(migration_engine).get_columns("telephonyline")}
    user_columns = {item["name"] for item in inspect(migration_engine).get_columns("user")}
    sms_columns = {item["name"] for item in inspect(migration_engine).get_columns("smslog")}
    assert {"human_agent_id", "telephony_line_id"}.issubset(call_columns)
    assert {"priority", "weight", "credential_ref"}.issubset(line_columns)
    assert {"agent_status", "last_seen_at"}.issubset(user_columns)
    assert {"provider_message_id", "provider_error", "updated_at"}.issubset(sms_columns)


def test_agent_presence_heartbeat_and_logout(client: TestClient):
    token = _login(client, "1001@test")
    headers = _bearer(token)
    profile = client.get("/api/v1/auth/me", headers=headers)
    assert profile.status_code == 200, profile.text
    assert profile.json()["agent_status"] == "ready"
    assert profile.json()["last_seen_at"] is not None

    busy = client.put("/api/v1/auth/presence", headers=headers, json={"status": "busy"})
    assert busy.status_code == 200, busy.text
    assert busy.json()["agent_status"] == "busy"
    assert client.put("/api/v1/auth/presence", headers=headers, json={"status": "invalid"}).status_code == 400

    logged_out = client.post("/api/v1/auth/logout", headers=headers)
    assert logged_out.status_code == 200, logged_out.text
    with session_scope() as session:
        agent = session.exec(select(User).where(User.username == "1001@test")).first()
        assert agent is not None
        assert agent.agent_status == "offline"


@pytest.mark.asyncio
async def test_ai_handoff_assigns_recent_ready_agent(client: TestClient, monkeypatch):
    with session_scope() as session:
        for candidate in session.exec(select(User).where(User.role == "agent")).all():
            candidate.agent_status = "offline"
            session.add(candidate)
        agent = session.exec(select(User).where(User.username == "1001@test")).first()
        assert agent is not None
        agent.agent_status = "ready"
        agent.last_seen_at = utc_now()
        session.add(agent)
        call = create_call(
            session,
            tenant_id=agent.tenant_id,
            phone="13800138884",
            mode=CallMode.AI_HANDOFF,
            campaign_id=None,
            contact_id=None,
            max_attempts=1,
        )
        call.status = CallStatus.ANSWERED
        session.add(call)
        session.commit()
        call_id = call.id
        agent_id = agent.id

    captured: dict[str, object] = {}

    async def fake_ai_turn(**_):
        return AiTurnResult(action="handoff", tts_text="转接人工", handoff_to_human=True)

    class HandoffAdapter:
        async def speak(self, **_):
            return {"result": "spoken"}

        async def transfer_to_human(self, **kwargs):
            captured.update(kwargs)
            return {"result": "transferred"}

    monkeypatch.setattr(dispatcher, "request_ai_turn", fake_ai_turn)
    monkeypatch.setattr(dispatcher, "get_telephony_adapter", lambda **_: HandoffAdapter())
    await dispatcher.run_ai_turn(call_id=call_id, transcript="请转人工")

    assert captured == {}
    with session_scope() as session:
        persisted_call = session.get(CallSession, call_id)
        persisted_agent = session.get(User, agent_id)
        handoff = session.exec(
            select(HandoffRequest).where(HandoffRequest.call_session_id == call_id)
        ).first()
        assert persisted_call is not None and persisted_call.human_agent_id == agent_id
        assert persisted_call.status == CallStatus.WAITING_HUMAN
        assert persisted_agent is not None and persisted_agent.agent_status == "busy"
        assert handoff is not None and handoff.state == HandoffState.WAITING
        assert handoff.target_group == f"agent:{agent_id}"


def test_sms_delivery_receipt_updates_log_and_blocks_terminal_regression(client: TestClient):
    with session_scope() as session:
        sms_log = SmsLog(
            tenant_id=1,
            to_phone="13800138883",
            content="receipt test",
            state="sent",
            provider_message_id="provider-message-1",
            sent_at=utc_now(),
        )
        session.add(sms_log)
        session.commit()
        session.refresh(sms_log)
        sms_log_id = sms_log.id

    delivered = client.post(
        "/api/v1/webhooks/sms/status",
        json={"provider_message_id": "provider-message-1", "state": "delivered"},
    )
    assert delivered.status_code == 200, delivered.text
    assert delivered.json()["result"] == "ok"
    regression = client.post(
        "/api/v1/webhooks/sms/status",
        json={"sms_log_id": sms_log_id, "state": "failed", "error": "late failure"},
    )
    assert regression.json() == {"result": "ignored", "reason": "terminal_state"}
    with session_scope() as session:
        persisted = session.get(SmsLog, sms_log_id)
        assert persisted is not None
        assert persisted.state == "delivered"
        assert persisted.provider_error is None


def test_stale_provider_call_releases_capacity(client: TestClient):
    with session_scope() as session:
        for existing in session.exec(select(CallSession).where(CallSession.status.in_({CallStatus.DIALING, CallStatus.ANSWERED, CallStatus.IN_AI, CallStatus.WAITING_HUMAN, CallStatus.HANDOFF_TRANSFERRING, CallStatus.IN_HUMAN}))).all():
            existing.status = CallStatus.COMPLETED
            session.add(existing)
        call = create_call(
            session,
            tenant_id=1,
            phone="13800138882",
            mode=CallMode.HUMAN_ONLY,
            campaign_id=None,
            contact_id=None,
        )
        call.status = CallStatus.DIALING
        call.attempts = 1
        call.updated_at = utc_now() - timedelta(seconds=300)
        session.add(call)
        session.commit()
        call_id = call.id
    assert asyncio.run(expire_stale_calls(batch_size=100)) >= 1
    with session_scope() as session:
        persisted = session.get(CallSession, call_id)
        assert persisted is not None
        assert persisted.status == CallStatus.FAILED
        assert persisted.last_error == "provider status timeout"


def test_production_validation_rejects_placeholder_secrets(monkeypatch):
    safe_values = {
        "env": "production",
        "secret_key": "replace-me-even-though-this-is-long-enough-123456",
        "jwt_secret": "a-different-production-jwt-secret-value-123456",
        "api_key": "a-production-api-key-value-123456",
        "tenant_api_keys_json": "",
        "cors_allow_origins": "https://console.example.com",
        "trusted_hosts": "console.example.com",
        "demo_users_enabled": False,
        "telephony_webhook_token": "a-production-webhook-token",
        "database_url": "postgresql+psycopg://user:pass@db/app",
        "redis_url": "redis://redis:6379/0",
        "telephony_provider": "tenant",
    }
    for key, value in safe_values.items():
        monkeypatch.setattr(app_main.settings, key, value)
    with pytest.raises(RuntimeError, match="SECRET_KEY"):
        app_main._validate_production_runtime()


def test_agent_webrtc_session_media_readiness_and_freeswitch_directory(client: TestClient, monkeypatch):
    for target in (app_main.settings, webrtc_router.settings, webrtc_service.settings):
        monkeypatch.setattr(target, "webrtc_enabled", True)
        monkeypatch.setattr(target, "webrtc_wss_url", "wss://voice.example.test:7443")
        monkeypatch.setattr(target, "webrtc_sip_domain", "voice.example.test")
        monkeypatch.setattr(target, "turn_urls", "stun:voice.example.test:3478,turn:voice.example.test:3478?transport=udp")
        monkeypatch.setattr(target, "turn_shared_secret", "turn-shared-secret-for-tests")
        monkeypatch.setattr(target, "freeswitch_directory_token", "directory-token-for-tests")
        monkeypatch.setattr(target, "redis_url", "")
    webrtc_service._memory_values.clear()

    token = _login(client, "1001@test")
    headers = _bearer(token)
    profile = client.get("/api/v1/auth/me", headers=headers)
    assert profile.status_code == 200
    assert profile.json()["agent_status"] == "offline"
    blocked_call = client.post(
        "/api/v1/calls",
        headers=headers,
        json={"phone": "13800138999", "mode": "human_only", "max_attempts": 1},
    )
    assert blocked_call.status_code == 409
    assert "not registered" in blocked_call.text

    issued = client.post("/api/v1/agent/webrtc/session", headers=headers)
    assert issued.status_code == 200, issued.text
    config = issued.json()
    assert config["enabled"] is True
    assert config["wss_url"].startswith("wss://")
    assert config["sip_uri"].startswith("sip:agent_")
    assert config["authorization_password"]
    assert len(config["ice_servers"]) == 2

    rejected_presence = client.put("/api/v1/auth/presence", headers=headers, json={"status": "ready"})
    assert rejected_presence.status_code == 409

    media = client.put(
        "/api/v1/agent/media/status",
        headers=headers,
        json={
            "registration_state": "registered",
            "media_state": "idle",
            "microphone_permission": "granted",
            "input_device_id": "mic-1",
            "output_device_id": "speaker-1",
            "muted": False,
            "held": False,
            "network_quality": "good",
            "round_trip_time_ms": 30,
            "jitter_ms": 4,
            "packets_lost": 0,
            "last_error": "",
        },
    )
    assert media.status_code == 200, media.text
    assert media.json()["registration_state"] == "registered"
    ready = client.put("/api/v1/auth/presence", headers=headers, json={"status": "ready"})
    assert ready.status_code == 200

    directory = client.post(
        "/internal/freeswitch/directory?token=directory-token-for-tests",
        data={"user": config["authorization_username"], "domain": "voice.example.test"},
    )
    assert directory.status_code == 200
    assert config["authorization_password"] in directory.text
    assert "<section name=\"directory\">" in directory.text


def test_microphone_is_allowed_for_same_origin_browser(client: TestClient):
    response = client.get("/agent/login")
    assert response.status_code == 200
    assert response.headers["permissions-policy"] == "geolocation=(), camera=(), microphone=(self)"


def test_voice_pipeline_selection_is_stable_and_campaign_override_wins():
    suffix = f"{uuid4().int % 100_000_000:08d}"
    with session_scope() as session:
        legacy_call = create_call(
            session,
            tenant_id=1,
            phone=f"139{suffix[:8]}",
            mode=CallMode.AI_ONLY,
            campaign_id=None,
            contact_id=None,
        )
        assert select_voice_ai_pipeline(
            session,
            legacy_call,
            ai_config={"voice_ai_pipeline": "legacy", "pipecat_canary_percent": 0},
        ) == "legacy"
        legacy_call.voice_ai_pipeline = "pipecat"
        session.add(legacy_call)
        session.commit()
        assert select_voice_ai_pipeline(
            session,
            legacy_call,
            ai_config={"voice_ai_pipeline": "legacy", "pipecat_canary_percent": 0},
        ) == "pipecat"

        campaign = Campaign(
            tenant_id=1,
            name=f"pipeline-{suffix}",
            mode=CallMode.AI_ONLY,
            voice_ai_pipeline="pipecat",
        )
        session.add(campaign)
        session.commit()
        session.refresh(campaign)
        overridden = CallSession(
            tenant_id=1,
            campaign_id=campaign.id,
            phone="13900138000",
            mode=CallMode.AI_ONLY,
            voice_ai_pipeline="pending",
        )
        session.add(overridden)
        session.commit()
        assert select_voice_ai_pipeline(
            session,
            overridden,
            ai_config={"voice_ai_pipeline": "legacy", "pipecat_canary_percent": 0},
        ) == "pipecat"


def test_login_lockout_unlock_and_logout_revoke_token(client: TestClient, monkeypatch):
    monkeypatch.setattr(app_main.settings, "auth_max_failed_attempts", 2)
    monkeypatch.setattr(app_main.settings, "auth_lockout_seconds", 60)
    from app.services import auth as auth_service

    monkeypatch.setattr(auth_service.settings, "auth_max_failed_attempts", 2)
    monkeypatch.setattr(auth_service.settings, "auth_lockout_seconds", 60)
    admin_token = _login(client, "admin")
    username = f"lock-{uuid4().hex[:10]}"
    created = client.post(
        "/api/v1/admin/users",
        headers=_bearer(admin_token),
        json={
            "username": username,
            "password": "lock-test-password-123",
            "full_name": "Lock Test",
            "role": "agent",
            "is_supervisor": False,
            "enabled": True,
        },
    )
    assert created.status_code == 200, created.text
    user_id = created.json()["id"]
    for _ in range(2):
        failed = client.post("/api/v1/auth/login", json={"username": username, "password": "wrong-password"})
        assert failed.status_code == 401
    assert client.post(
        "/api/v1/auth/login",
        json={"username": username, "password": "lock-test-password-123"},
    ).status_code == 401
    users = client.get("/api/v1/admin/users?page=1&size=200", headers=_bearer(admin_token)).json()
    locked = next(item for item in users if item["id"] == user_id)
    assert locked["failed_login_attempts"] == 2
    assert locked["locked_until"] is not None

    unlocked = client.post(f"/api/v1/admin/users/{user_id}/unlock", headers=_bearer(admin_token))
    assert unlocked.status_code == 200
    token = _login(client, username, "lock-test-password-123")
    assert client.get("/api/v1/auth/me", headers=_bearer(token)).status_code == 200
    assert client.post("/api/v1/auth/logout", headers=_bearer(token)).status_code == 200
    assert client.get("/api/v1/auth/me", headers=_bearer(token)).status_code == 401


def test_prometheus_metrics_require_token(client: TestClient, monkeypatch):
    monkeypatch.setattr(app_main.settings, "metrics_token", "metrics-token-for-tests")
    monkeypatch.setattr(app_main.settings, "metrics_token_file", "")
    assert client.get("/metrics").status_code == 401
    response = client.get("/metrics", headers={"Authorization": "Bearer metrics-token-for-tests"})
    assert response.status_code == 200
    assert "ai_outbound_calls_by_pipeline" in response.text
    assert "ai_outbound_tasks" in response.text


def test_prometheus_metrics_accept_mounted_token_file(client: TestClient, monkeypatch, tmp_path):
    token_file = tmp_path / "metrics-token"
    token_file.write_text("mounted-metrics-token\n", encoding="utf-8")
    monkeypatch.setattr(app_main.settings, "metrics_token", "")
    monkeypatch.setattr(app_main.settings, "metrics_token_file", str(token_file))

    response = client.get("/metrics", headers={"Authorization": "Bearer mounted-metrics-token"})

    assert response.status_code == 200


@pytest.mark.asyncio
async def test_prepared_campaign_is_not_picked_up_by_scheduler(client: TestClient):
    token = _login(client, "admin")
    headers = _bearer(token)
    contact = client.post(
        "/api/v1/contacts",
        headers=headers,
        json={"phone": "13900139001", "name": "prepared-only", "consent_state": "consented"},
    ).json()
    campaign = client.post(
        "/api/v1/campaigns",
        headers=headers,
        json={"name": "prepared-only", "mode": "human_only", "contact_ids": [contact["id"]]},
    ).json()

    started = client.post(f"/api/v1/campaigns/{campaign['id']}/start?auto_dial=false", headers=headers)
    assert started.status_code == 200, started.text
    assert started.json()["campaign_status"] == "prepared"
    assert started.json()["dispatch_mode"] == "prepared"
    await dispatch_pending_calls(batch_size=10)

    with session_scope() as session:
        saved_campaign = session.get(Campaign, campaign["id"])
        calls = session.exec(select(CallSession).where(CallSession.campaign_id == campaign["id"])).all()
        assert saved_campaign is not None and saved_campaign.dispatch_enabled is False
        assert [call.status for call in calls] == [CallStatus.CREATED]


def test_campaign_batch_continues_without_duplicate_calls(client: TestClient):
    token = _login(client, "admin")
    headers = _bearer(token)
    contact_ids = []
    for suffix in (2, 3):
        contact = client.post(
            "/api/v1/contacts",
            headers=headers,
            json={"phone": f"1390013900{suffix}", "name": f"batch-{suffix}", "consent_state": "consented"},
        ).json()
        contact_ids.append(contact["id"])
    campaign = client.post(
        "/api/v1/campaigns",
        headers=headers,
        json={"name": "batch-continuation", "mode": "human_only", "contact_ids": contact_ids},
    ).json()
    first = client.post(
        f"/api/v1/campaigns/{campaign['id']}/start?auto_dial=true&max_dials=1",
        headers=headers,
    )
    assert first.status_code == 200, first.text

    with session_scope() as session:
        queued = session.exec(
            select(CallSession).where(
                CallSession.campaign_id == campaign["id"],
                CallSession.status == CallStatus.QUEUED,
            )
        ).one()
        queued.status = CallStatus.COMPLETED
        queued.finished_at = utc_now()
        session.add(queued)
        session.commit()
        complete_campaign_if_terminal(session, campaign["id"])
        saved_campaign = session.get(Campaign, campaign["id"])
        assert saved_campaign is not None and saved_campaign.status == "prepared"

    second = client.post(
        f"/api/v1/campaigns/{campaign['id']}/start?auto_dial=true&max_dials=1",
        headers=headers,
    )
    assert second.status_code == 200, second.text
    assert second.json()["created"] == 0
    with session_scope() as session:
        calls = session.exec(select(CallSession).where(CallSession.campaign_id == campaign["id"])).all()
        assert len(calls) == 2
        assert sorted(call.status.value for call in calls) == ["completed", "queued"]
        assert len({call.campaign_contact_key for call in calls}) == 2


def test_media_callback_rejects_stale_attempt(client: TestClient):
    with session_scope() as session:
        call = CallSession(
            tenant_id=1,
            phone="13900139004",
            mode=CallMode.AI_ONLY,
            status=CallStatus.IN_AI,
            attempts=2,
            max_attempts=3,
        )
        session.add(call)
        session.commit()
        session.refresh(call)
        call_id = str(call.id)

    payload = {
        "call_id": call_id,
        "event_id": "stale-media",
        "state": "closed",
        "attempt": 1,
        "provider_session_id": "old-session",
    }
    stale = client.post("/api/v1/webhooks/telephony/media", json=payload)
    assert stale.status_code == 200
    assert stale.json() == {"result": "ignored", "reason": "stale_attempt"}

    current = client.post(
        "/api/v1/webhooks/telephony/media",
        json={**payload, "event_id": "current-media", "state": "listening", "attempt": 2, "provider_session_id": "new-session"},
    )
    assert current.status_code == 200, current.text
    with session_scope() as session:
        realtime = session.exec(
            select(RealtimeSession).where(RealtimeSession.call_session_id == UUID(call_id))
        ).one()
        assert realtime.attempt == 2
        assert realtime.provider_session_id == "new-session"


def test_dead_task_can_be_replayed_by_admin(client: TestClient):
    token = _login(client, "admin")
    with session_scope() as session:
        task = TaskOutbox(
            tenant_id=1,
            task_type="business_callback",
            aggregate_id="manual-retry",
            idempotency_key=f"manual-retry:{uuid4()}",
            payload_json="{}",
            state=TaskState.DEAD,
            attempts=5,
            max_attempts=5,
            last_error="temporary provider failure",
        )
        session.add(task)
        session.commit()
        session.refresh(task)
        task_id = task.id

    retried = client.post(f"/api/v1/admin/tasks/{task_id}/retry", headers=_bearer(token))
    assert retried.status_code == 200, retried.text
    assert retried.json()["state"] == "pending"
    with session_scope() as session:
        task = session.get(TaskOutbox, task_id)
        assert task is not None
        assert task.attempts == 0
        assert task.last_error == ""


@pytest.mark.asyncio
async def test_terminal_prompt_waits_for_playback_before_hangup(monkeypatch):
    events: list[str] = []

    class OrderedAdapter:
        async def speak(self, **_kwargs):
            events.append("speak")
            return {"playback_id": "terminal-playback"}

        async def hangup(self, **_kwargs):
            events.append("hangup")
            return {"result": "hungup"}

    async def wait_for_playback(_call_id, playback_id):
        assert playback_id == "terminal-playback"
        events.append("playback-complete")
        return True

    monkeypatch.setattr(dispatcher, "get_telephony_adapter", lambda **_kwargs: OrderedAdapter())
    monkeypatch.setattr(dispatcher, "_wait_for_playback_completion", wait_for_playback)
    with session_scope() as session:
        call = CallSession(
            tenant_id=1,
            phone="13900139005",
            mode=CallMode.AI_ONLY,
            status=CallStatus.IN_AI,
        )
        session.add(call)
        session.commit()
        session.refresh(call)
        await dispatcher._apply_ai_action(
            session=session,
            call=call,
            result=AiTurnResult(action="hangup", tts_text="感谢接听，再见。"),
        )
    assert events == ["speak", "playback-complete", "hangup"]


def test_contact_export_escapes_spreadsheet_formulas(client: TestClient):
    token = _login(client, "admin")
    headers = _bearer(token)
    created = client.post(
        "/api/v1/contacts",
        headers=headers,
        json={
            "phone": "13900139006",
            "name": "=HYPERLINK(\"https://example.invalid\")",
            "tags": "+SUM(1,1)",
            "consent_state": "consented",
        },
    )
    assert created.status_code == 200, created.text
    exported = client.get("/api/v1/contacts/export?keyword=13900139006", headers=headers)
    assert exported.status_code == 200, exported.text
    assert "'=HYPERLINK" in exported.text
    assert "'+SUM(1,1)" in exported.text


def test_direct_calls_require_consent_and_respect_destination_allowlist():
    suffix = f"{uuid4().int % 10_000_000:07d}"
    allowed_phone = f"8613{suffix}"
    with session_scope() as session:
        session.add(
            AdminSetting(
                tenant_id=1,
                section="compliance",
                data_json=json.dumps(
                    {
                        "require_explicit_consent_for_direct_calls": True,
                        "allowed_phone_prefixes": "86",
                    }
                ),
            )
        )
        session.commit()
        allowed, reason = can_call_contact_sync(session, 1, allowed_phone)
        assert allowed is False
        assert reason == "explicit_consent_required"
        allowed, reason = can_call_contact_sync(session, 1, f"63{suffix}")
        assert allowed is False
        assert reason == "destination_not_allowed"
        session.add(
            Contact(
                tenant_id=1,
                phone=allowed_phone,
                consent_state="consented",
                consented_at=utc_now(),
                consented_by="compliance-test",
            )
        )
        session.commit()
        allowed, reason = can_call_contact_sync(session, 1, allowed_phone)
        assert allowed is True
        assert reason == ""


@pytest.mark.asyncio
async def test_daily_tenant_dial_limit_blocks_before_provider_call(monkeypatch):
    called = False

    class UnexpectedAdapter:
        async def dial(self, **_kwargs):
            nonlocal called
            called = True
            return {"provider_call_id": "unexpected"}

    monkeypatch.setattr("app.services.call_service.get_telephony_adapter", lambda **_: UnexpectedAdapter())
    with session_scope() as session:
        session.add(
            AdminSetting(
                tenant_id=1,
                section="compliance",
                data_json=json.dumps(
                    {
                        "require_explicit_consent_for_direct_calls": False,
                        "max_calls_per_day": 1,
                    }
                ),
            )
        )
        session.add(
            CallSession(
                tenant_id=1,
                phone="13900139990",
                mode=CallMode.HUMAN_ONLY,
                status=CallStatus.COMPLETED,
                attempts=1,
                started_at=utc_now(),
                finished_at=utc_now(),
            )
        )
        session.commit()
        call = create_call(
            session,
            tenant_id=1,
            phone="13900139991",
            mode=CallMode.HUMAN_ONLY,
            campaign_id=None,
            contact_id=None,
        )
        call = await place_call(session, call)
        assert call.status == CallStatus.FAILED
        assert call.last_error == "precheck failed: tenant_daily_call_limit"
    assert called is False


@pytest.mark.asyncio
async def test_daily_tenant_dial_limit_counts_retry_attempts(monkeypatch):
    dial_count = 0

    class CountingAdapter:
        async def dial(self, **_kwargs):
            nonlocal dial_count
            dial_count += 1
            return {"provider_call_id": f"provider-{dial_count}"}

    monkeypatch.setattr("app.services.call_service.get_telephony_adapter", lambda **_: CountingAdapter())
    with session_scope() as session:
        tenant = Tenant(name="daily-limit-retry", code=f"daily-limit-retry-{uuid4().hex}")
        session.add(tenant)
        session.commit()
        session.refresh(tenant)
        session.add(
            AdminSetting(
                tenant_id=tenant.id,
                section="compliance",
                data_json=json.dumps(
                    {
                        "require_explicit_consent_for_direct_calls": False,
                        "max_calls_per_day": 1,
                    }
                ),
            )
        )
        session.commit()
        call = create_call(
            session,
            tenant_id=tenant.id,
            phone="13900139989",
            mode=CallMode.HUMAN_ONLY,
            campaign_id=None,
            contact_id=None,
            max_attempts=2,
        )
        call = await place_call(session, call)
        assert call.status == CallStatus.DIALING
        assert dial_count == 1

        call.status = CallStatus.QUEUED
        call.telephony_call_id = None
        session.add(call)
        session.commit()
        call = await place_call(session, call)
        assert call.status == CallStatus.FAILED
        assert call.last_error == "precheck failed: tenant_daily_call_limit"
        assert dial_count == 1


def test_retention_removes_final_text_and_redacts_expired_call_pii():
    old = utc_now() - timedelta(days=2)
    with session_scope() as session:
        session.add(
            AdminSetting(
                tenant_id=1,
                section="compliance",
                data_json=json.dumps(
                    {
                        "final_transcript_retention_days": 1,
                        "call_sensitive_data_retention_days": 1,
                    }
                ),
            )
        )
        call = CallSession(
            tenant_id=1,
            phone="13900139992",
            mode=CallMode.AI_ONLY,
            status=CallStatus.COMPLETED,
            last_transcript="customer secret",
            summary="sensitive summary",
            started_at=old,
            finished_at=old,
        )
        session.add(call)
        session.flush()
        turn = SpeechTurn(
            tenant_id=1,
            call_session_id=call.id,
            provider_event_key=f"final-retention-{call.id}",
            transcript="customer secret",
            normalized_transcript="customer secret",
            is_final=True,
            created_at=old,
        )
        session.add(turn)
        session.add(CallEvent(call_session_id=call.id, event_type="status", payload='{"phone":"13900139992"}'))
        session.add(CallMetric(tenant_id=1, call_session_id=call.id, stage="test", detail="secret detail"))
        session.add(
            CallAnalysis(
                tenant_id=1,
                call_session_id=call.id,
                summary="sensitive summary",
                qa_flags_json='["secret"]',
                structured_json='{"secret":true}',
            )
        )
        session.commit()
        call_id = call.id
        turn_id = turn.id

    result = purge_expired_voice_data(batch_size=500)
    assert result["final_transcripts"] >= 1
    assert result["redacted_calls"] >= 1
    with session_scope() as session:
        assert session.get(SpeechTurn, turn_id) is None
        call = session.get(CallSession, call_id)
        assert call is not None
        assert call.phone.startswith("redacted:")
        assert call.last_transcript is None
        assert call.summary is None
        event = session.exec(select(CallEvent).where(CallEvent.call_session_id == call_id)).first()
        assert event is not None and event.payload == "{}"
        analysis = session.exec(select(CallAnalysis).where(CallAnalysis.call_session_id == call_id)).first()
        assert analysis is not None
        assert analysis.summary == ""
        assert analysis.structured_json == "{}"


def test_contact_import_idempotency_returns_original_result(client: TestClient):
    token = _login(client, "admin")
    request_key = f"contacts-{uuid4().hex}"
    phone = f"139{uuid4().int % 100_000_000:08d}"
    payload = f"phone,name,consent_state\n{phone},idempotent,consented\n"
    headers = _bearer(token, **{"Idempotency-Key": request_key})
    first = client.post(
        "/api/v1/contacts/import",
        headers=headers,
        files={"file": ("contacts.csv", payload.encode(), "text/csv")},
    )
    second = client.post(
        "/api/v1/contacts/import",
        headers=headers,
        files={"file": ("contacts.csv", payload.encode(), "text/csv")},
    )
    assert first.status_code == 200, first.text
    assert second.status_code == 200, second.text
    assert first.json() == second.json()
    assert first.json()["created"] == 1
    with session_scope() as session:
        jobs = session.exec(
            select(ContactImportJob).where(ContactImportJob.request_key == request_key)
        ).all()
        assert len(jobs) == 1
        assert jobs[0].state == "completed"


def test_webhook_hmac_rejects_expired_signature_and_accepts_valid(client: TestClient, monkeypatch):
    token = _login(client, "admin")
    created = client.post(
        "/api/v1/calls",
        headers=_bearer(token),
        json={"phone": "13900139993", "mode": "human_only", "max_attempts": 1},
    )
    assert created.status_code == 200, created.text
    payload = {
        "call_id": created.json()["id"],
        "kind": "status",
        "payload": {"status": "answered", "event_id": f"signed-{uuid4().hex}"},
    }
    body = json.dumps(payload, separators=(",", ":")).encode()
    secret = "signed-webhook-secret-that-is-long-enough"
    webhook_token = "signed-webhook-token"
    monkeypatch.setattr(app_main.settings, "telephony_webhook_secret", secret)
    monkeypatch.setattr(app_main.settings, "telephony_webhook_token", webhook_token)
    timestamp = str(int(time.time()))
    signature = hmac.new(secret.encode(), timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()
    valid_headers = {
        "Content-Type": "application/json",
        "X-Webhook-Token": webhook_token,
        "X-Webhook-Timestamp": timestamp,
        "X-Webhook-Signature": f"sha256={signature}",
    }
    expired_timestamp = str(int(time.time()) - 10_000)
    expired_signature = hmac.new(
        secret.encode(), expired_timestamp.encode() + b"." + body, hashlib.sha256
    ).hexdigest()
    expired_headers = {
        **valid_headers,
        "X-Webhook-Timestamp": expired_timestamp,
        "X-Webhook-Signature": f"sha256={expired_signature}",
    }
    assert client.post("/api/v1/webhooks/telephony/status", content=body, headers=expired_headers).status_code == 401
    assert client.post("/api/v1/webhooks/telephony/status", content=body, headers=valid_headers).status_code == 200


def test_production_startup_verifies_schema_without_running_ddl(monkeypatch):
    verified: list[bool] = []
    monkeypatch.setattr(db_module.settings, "env", "production")
    monkeypatch.setattr(db_module.settings, "auto_migrate", False)
    monkeypatch.setattr(db_module, "verify_database_schema", lambda: verified.append(True))
    monkeypatch.setattr(
        db_module.SQLModel.metadata,
        "create_all",
        lambda *_args, **_kwargs: pytest.fail("production startup must not mutate schema"),
    )
    db_module.create_db_and_tables()
    assert verified == [True]
