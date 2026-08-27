from __future__ import annotations

import asyncio
import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
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

from app.db import session_scope  # noqa: E402
from app.main import app  # noqa: E402
from app.models import CallEvent, CallMode, CallSession, CallStatus, User  # noqa: E402
from app.schemas import AiTurnResult  # noqa: E402
from app.services import dispatcher  # noqa: E402
from app.services.call_service import create_call, dispatch_call_ids  # noqa: E402


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


def test_pages_login_and_server_key_not_exposed(client: TestClient):
    for path in ("/admin", "/agent"):
        response = client.get(path)
        assert response.status_code == 200
        assert 'id="apiKey" value=""' in response.text
        assert 'id="apiKey" value="dev-api-key"' not in response.text

    agent_page = client.get("/agent")
    assert 'class="card col-4 hidden"' in agent_page.text
    assert 'class="card col-8 hidden"' in agent_page.text
    assert 'class="card col-12 hidden"' in agent_page.text

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

    response = client.get("/api/v1/contacts", headers=_bearer("invalid-token"))
    assert response.status_code == 401

    response = client.get(
        "/api/v1/contacts",
        headers=_bearer(admin_token, **{"x-tenant-id": "2"}),
    )
    assert response.status_code == 403


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

    monkeypatch.setattr(dispatcher, "request_ai_turn", _fake_ai_turn)
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
