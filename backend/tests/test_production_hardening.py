from __future__ import annotations

import asyncio
import os
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import UUID

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
from app.clock import utc_now  # noqa: E402
from app.main import app  # noqa: E402
from app.models import Campaign, CallEvent, CallMode, CallSession, CallStatus, SmsLog, TelephonyLine, User  # noqa: E402
from app.schemas import AiTurnResult  # noqa: E402
from app.services import dispatcher, telephony  # noqa: E402
from app.services.call_service import (  # noqa: E402
    create_call,
    dispatch_call_ids,
    dispatch_due_retries,
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


def test_utc_now_preserves_naive_database_contract():
    before = datetime.now(UTC).replace(tzinfo=None)
    value = utc_now()
    after = datetime.now(UTC).replace(tzinfo=None)

    assert value.tzinfo is None
    assert before <= value <= after


def test_pages_login_and_server_key_not_exposed(client: TestClient):
    for path in ("/admin", "/admin/contacts", "/agent", "/agent/calls"):
        response = client.get(path)
        assert response.status_code == 200
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

    response = client.get("/api/v1/contacts", headers=_bearer("invalid-token"))
    assert response.status_code == 401

    response = client.get(
        "/api/v1/contacts",
        headers=_bearer(admin_token, **{"x-tenant-id": "2"}),
    )
    assert response.status_code == 403


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
            "name": "acceptance-sip",
            "provider": "sip",
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
