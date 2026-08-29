from __future__ import annotations

import asyncio
import os
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import UUID

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, inspect, text
from sqlmodel import select


TEST_DB = Path("/tmp/ai-outbound-pytest.db")
TEST_DB.unlink(missing_ok=True)
os.environ["DATABASE_URL"] = f"sqlite:///{TEST_DB}"
os.environ["REDIS_URL"] = ""
os.environ["RATE_LIMIT_ENABLED"] = "false"
os.environ["DEMO_USERS_ENABLED"] = "true"
os.environ["TELEPHONY_WEBHOOK_BASE"] = "http://127.0.0.1:9"
os.environ["TELEPHONY_TIMEOUT_SEC"] = "1"
os.environ["TELEPHONY_RETRY_TIMES"] = "0"
os.environ["SCHEDULER_ENABLED"] = "false"

from app.db import session_scope  # noqa: E402
from app.clock import utc_now  # noqa: E402
from app.main import app  # noqa: E402
from app import main as app_main  # noqa: E402
from app.models import (  # noqa: E402
    Campaign,
    CallAnalysis,
    CallEvent,
    CallMode,
    CallSession,
    CallStatus,
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
    User,
)
from app.schemas import AiTurnResult  # noqa: E402
from app.schema_migrations import apply_runtime_migrations  # noqa: E402
from app.services import dispatcher, telephony  # noqa: E402
from app.services import business_callbacks  # noqa: E402
from app.services.knowledge import retrieve_knowledge  # noqa: E402
from app.services.retention import purge_expired_voice_data  # noqa: E402
from app.services.script_flow import FlowValidationError, validate_graph  # noqa: E402
from app.services.task_queue import enqueue_task, process_task  # noqa: E402
from app.schemas import ScriptFlowGraph  # noqa: E402
from app.services.call_service import (  # noqa: E402
    create_call,
    dispatch_call_ids,
    dispatch_due_retries,
    expire_stale_calls,
    place_call,
    retry_call,
    schedule_campaign_retry,
)


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as test_client:
        yield test_client


def _login(client: TestClient, username: str, password: str = "12345678") -> str:
    response = client.post("/api/v1/auth/login", json={"username": username, "password": password})
    assert response.status_code == 200, response.text
    return response.json()["access_token"]


def _bearer(token: str, **extra: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}", **extra}


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
    assert any(item["id"] == handoff_id for item in queued.json())
    accepted = client.post(f"/api/v1/calls/{call_id}/handoffs/{handoff_id}/accept", headers=headers)
    assert accepted.status_code == 200, accepted.text
    assert accepted.json()["state"] == "accepted"
    assert captured["call_id"] == str(call_id)
    assert client.get(f"/api/v1/calls/{call_id}", headers=headers).json()["status"] == "handoff_transferring"
    second_accept = client.post(f"/api/v1/calls/{call_id}/handoffs/{handoff_id}/accept", headers=headers)
    assert second_accept.status_code == 409


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


def test_retention_purges_partial_text_and_tombstones_recording(monkeypatch):
    monkeypatch.setattr("app.services.retention.settings.partial_transcript_retention_hours", 1)
    with session_scope() as session:
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
        assert recording.state == "deleted"
        assert recording.provider_url == ""
        assert recording.storage_uri == ""


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
    assert len(calls.json()) == 1


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

    class CapturingAdapter:
        async def dial(self, *, call_id, phone, webhook_url, metadata):
            captured_attempts.append(metadata["attempt"])
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
    started = client.post(f"/api/v1/campaigns/{campaign['id']}/start?auto_dial=false", headers=headers)
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
    assert started.json()["campaign_status"] == "running"
    assert client.post(f"/api/v1/campaigns/{campaign['id']}/start", headers=headers).status_code == 409
    paused = client.post(f"/api/v1/campaigns/{campaign['id']}/pause", headers=headers)
    assert paused.status_code == 200, paused.text
    assert paused.json()["status"] == "paused"
    resumed = client.post(f"/api/v1/campaigns/{campaign['id']}/resume", headers=headers)
    assert resumed.status_code == 200, resumed.text
    assert resumed.json()["status"] == "running"
    stopped = client.post(f"/api/v1/campaigns/{campaign['id']}/stop", headers=headers)
    assert stopped.status_code == 200, stopped.text
    assert stopped.json()["status"] == "stopped"


def test_admin_management_crud_settings_and_audit(client: TestClient):
    admin_token = _login(client, "admin")
    agent_token = _login(client, "1001@test")
    headers = _bearer(admin_token)

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
            }
        },
    )
    assert setting.status_code == 200, setting.text
    assert setting.json()["data"]["allowed_end_hour"] == 19
    assert client.get("/api/v1/admin/settings/compliance", headers=headers).json()["data"]["max_attempts_per_day"] == 2

    invalid_setting = client.put(
        "/api/v1/admin/settings/ai",
        headers=headers,
        json={"data": {"api_key": "must-not-be-stored"}},
    )
    assert invalid_setting.status_code == 400

    overview = client.get("/api/v1/admin/system-overview", headers=headers)
    assert overview.status_code == 200, overview.text
    assert overview.json()["resources"]["users"] >= 3
    assert overview.json()["resources"]["lines"] >= 1

    audits = client.get("/api/v1/admin/audit-logs", headers=headers)
    assert audits.status_code == 200, audits.text
    actions = {item["action"] for item in audits.json()}
    assert {"create", "update", "reset_password"}.issubset(actions)


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
        for existing in session.exec(select(CallSession).where(CallSession.status.in_({CallStatus.DIALING, CallStatus.ANSWERED, CallStatus.IN_AI, CallStatus.WAITING_HUMAN, CallStatus.HANDOFF_TRANSFERRING}))).all():
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
    assert expire_stale_calls(batch_size=100) >= 1
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
