import asyncio
import copy
import hashlib
import hmac
import json
import time
from concurrent.futures import ThreadPoolExecutor
from secrets import token_urlsafe

import httpx
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.config import Settings
from app.freeswitch import FreeswitchEslDriver
from app.security import CallbackSender, Ledger, SecureDriver, canonical, routes, validate_callback_url
from security_fixtures import SECURITY_SETTINGS
from test_freeswitch import FakeEslClient


def configuration(tmp_path, **overrides):
    route = dict(gateway="approved", caller_id="861055550000", allowed_prefixes=["86138"], denied_prefixes=["8613899"],
                 max_concurrent=10, cps=10, calls_per_day=100, hour_budget_minor=10000, day_budget_minor=50000,
                 rate_minor_per_minute=10, max_duration_sec=120)
    values = {**SECURITY_SETTINGS, "voice_gateway_driver": "freeswitch_esl", "freeswitch_gateway": "approved",
              "freeswitch_esl_password": "synthetic-esl-" + "e" * 32,
              "freeswitch_tts_engine": "flite", "freeswitch_tts_voice": "slt",
              "voice_security_db_path": str(tmp_path / "ledger.sqlite3"),
              "voice_security_routes_json": json.dumps({"1:0": route}), "voice_cps": 10}
    values.update(overrides)
    return Settings(_env_file=None, **values)


def request(call_id="audit-1"):
    return dict(call_id=call_id, phone="8613800138000", webhook_url="http://control-api:8000/api/v1/webhooks/telephony/status",
                metadata={"tenant_id": 1, "attempt": 1, "recording_enabled": False})


def gateway(tmp_path, **overrides):
    settings = configuration(tmp_path, **overrides)
    fake = FakeEslClient()
    return SecureDriver(settings, FreeswitchEslDriver(settings, client=fake)), fake


def signed(settings, path, payload, *, stamp=None, nonce=None):
    body = canonical(payload)
    stamp, nonce = str(int(time.time()) if stamp is None else stamp), nonce or token_urlsafe(24)
    signature = hmac.new(settings.voice_command_secret.encode(), f"{stamp}.{nonce}.{path}.".encode() + body, hashlib.sha256).hexdigest()
    return body, {"Authorization": f"Bearer {settings.service_token}", "Content-Type": "application/json",
                  "x-voice-timestamp": stamp, "x-voice-nonce": nonce, "x-voice-signature": signature}


@pytest.mark.parametrize("field", ["service_token", "voice_command_secret", "voice_security_admin_token", "webhook_token", "webhook_secret", "voice_security_db_path", "freeswitch_esl_password"])
def test_real_driver_requires_security_in_dev_too(tmp_path, field):
    with pytest.raises(RuntimeError):
        configuration(tmp_path, **{field: ""}).validate_runtime()


def test_safety_configuration_validates_and_duplicate_secrets_fail(tmp_path):
    settings = configuration(tmp_path)
    settings.validate_runtime()
    settings.voice_command_secret = settings.service_token
    with pytest.raises(RuntimeError, match="distinct"):
        settings.validate_runtime()


def test_hundred_retries_and_restart_only_originate_once(tmp_path):
    async def run():
        driver, fake = gateway(tmp_path)
        results = await asyncio.gather(*(driver.post("dial", request()) for _ in range(100)))
        assert len(fake.bgapi_commands) == 1
        assert len({r["provider_call_id"] for r in results}) == 1
        restarted, new_fake = gateway(tmp_path)
        assert await restarted.post("dial", request()) == results[0]
        assert not new_fake.bgapi_commands
        assert "execute_on_answer='sched_hangup +120 ALLOTTED_TIMEOUT'" in fake.bgapi_commands[0]
        assert "hangup_after_bridge=true" in fake.bgapi_commands[0]
    asyncio.run(run())


def test_durable_intent_survives_crash_before_esl_response(tmp_path):
    async def run():
        driver, fake = gateway(tmp_path)
        async def uncertain(command):
            fake.bgapi_commands.append(command)
            raise TimeoutError("synthetic response loss")
        fake.bgapi = uncertain
        with pytest.raises(TimeoutError):
            await driver.post("dial", request())
        restarted, new_fake = gateway(tmp_path)
        result = await restarted.post("dial", request())
        assert result["result"] == "pending_reconciliation"
        assert not new_fake.bgapi_commands
        assert restarted.ledger.summary()["active_attempts"] == 1
    asyncio.run(run())


@pytest.mark.parametrize("mutation", [
    {"phone": "0012025550123"}, {"phone": "8613899000000"}, {"phone": "１２３４５６７"}, {"phone": "8613\nstatus"},
    {"webhook_url": "http://untrusted.example/hook"},
    {"metadata": {"tenant_id": 2, "attempt": 1}}, {"metadata": {"tenant_id": 1, "attempt": 1, "telephony_line_id": 99}},
    {"metadata": {"tenant_id": 1, "attempt": 1, "freeswitch_gateway": "other"}},
    {"metadata": {"tenant_id": 1, "attempt": 1, "caller_id": "861055550001"}},
    {"metadata": {"tenant_id": 1, "attempt": 1, "speech_webhook_url": "http://127.0.0.1/admin"}},
    {"metadata": {}},
])
def test_policy_rejections_emit_zero_esl_commands(tmp_path, mutation):
    async def run():
        driver, fake = gateway(tmp_path)
        with pytest.raises(HTTPException) as exc:
            await driver.post("dial", {**request(), **mutation})
        assert exc.value.status_code == 403
        assert not fake.bgapi_commands
    asyncio.run(run())


def test_changed_payload_same_key_and_overlapping_attempt_are_rejected(tmp_path):
    async def run():
        driver, fake = gateway(tmp_path)
        await driver.post("dial", request())
        for payload in ({**request(), "phone": "8613800138001"}, {**request(), "metadata": {"tenant_id": 1, "attempt": 2}}):
            with pytest.raises(HTTPException) as exc:
                await driver.post("dial", payload)
            assert exc.value.status_code == 409
        assert len(fake.bgapi_commands) == 1
    asyncio.run(run())


@pytest.mark.parametrize("override", [{"voice_max_concurrent": 1}, {"voice_cps": 1}, {"voice_daily_call_limit": 1},
                                      {"voice_hour_budget_minor": 40}, {"voice_day_budget_minor": 40}])
def test_independent_hard_limits_are_persistent(tmp_path, override):
    async def run():
        driver, _ = gateway(tmp_path, **override)
        await driver.post("dial", request())
        restarted, fake = gateway(tmp_path, **override)
        with pytest.raises(HTTPException) as exc:
            await restarted.post("dial", request("audit-2"))
        assert exc.value.status_code == 429
        assert not fake.bgapi_commands
    asyncio.run(run())


def test_parallel_ledger_claims_cannot_oversubscribe(tmp_path):
    settings = configuration(tmp_path, voice_max_concurrent=1)
    Ledger(settings.voice_security_db_path).summary()  # initialize before race
    def admit(i):
        ledger = Ledger(settings.voice_security_db_path)
        try:
            ledger.admit(request(f"call-{i}"), routes(settings)["1:0"], settings)
            return True
        except HTTPException as exc:
            assert exc.status_code == 429
            return False
    with ThreadPoolExecutor(max_workers=8) as pool:
        assert sum(pool.map(admit, range(20))) == 1


def test_pending_uuid_absence_does_not_release_and_verified_deadline_does(tmp_path):
    async def run():
        driver, fake = gateway(tmp_path)
        await driver.post("dial", request())
        async def no_channel(command):
            return "false" if command.startswith("uuid_exists") else "+OK"
        fake.api = no_channel
        payload = {"call_id": "audit-1", "tenant_id": 1}
        assert not (await driver.post("hangup", payload))["ended"]
        assert driver.ledger.summary()["active_attempts"] == 1
        with driver.ledger.transaction() as db:
            db.execute("UPDATE attempts SET deadline=?", (time.time() - 1,))
        assert not (await driver.post("hangup", payload))["ended"]  # possibly queued PBX job
        row = driver.ledger.lookup("audit-1", 1)
        driver.ledger.mark_seen(row["uuid"])  # prior positive PBX channel evidence
        assert (await driver.post("hangup", payload))["ended"]
        assert driver.ledger.summary()["active_attempts"] == 0
        with driver.ledger.transaction() as db:
            assert db.execute("SELECT cost FROM attempts").fetchone()[0] == 40  # no CDR => no refund
    asyncio.run(run())


def test_pbx_unavailable_keeps_capacity_and_cannot_be_controlled_cross_tenant(tmp_path):
    async def run():
        driver, fake = gateway(tmp_path)
        await driver.post("dial", request())
        with pytest.raises(HTTPException) as exc:
            await driver.post("hangup", {"call_id": "audit-1", "tenant_id": 2})
        assert exc.value.status_code == 404
        async def unavailable(_):
            raise TimeoutError
        fake.api = unavailable
        with pytest.raises(TimeoutError):
            await driver.post("hangup", {"call_id": "audit-1", "tenant_id": 1})
        assert driver.ledger.summary()["active_attempts"] == 1
    asyncio.run(run())


def test_terminal_cdr_settles_once_and_old_events_cannot_release_new_call(tmp_path):
    async def run():
        driver, _ = gateway(tmp_path)
        result = await driver.post("dial", request())
        driver.ledger.finish(result["provider_call_id"], 61)
        driver.ledger.finish(result["provider_call_id"], 0)
        with driver.ledger.transaction() as db:
            assert db.execute("SELECT cost FROM attempts").fetchone()[0] == 40
        payload = request()
        payload["metadata"]["attempt"] = 2
        await driver.post("dial", payload)
        driver.ledger.finish(result["provider_call_id"], 0)
        assert driver.ledger.summary()["active_attempts"] == 1
    asyncio.run(run())


def test_budget_counts_calls_crossing_utc_boundary(tmp_path, monkeypatch):
    driver, _ = gateway(tmp_path, voice_hour_budget_minor=40)
    clock = 86400 * 100 + 3599
    monkeypatch.setattr("app.security.time.time", lambda: clock)
    row, _ = driver.ledger.admit(request(), routes(driver.settings)["1:0"], driver.settings)
    clock += 2
    driver.ledger.finish(row["uuid"], 120)
    with pytest.raises(HTTPException, match="budget"):
        driver.ledger.admit(request("new-hour"), routes(driver.settings)["1:0"], driver.settings)


def test_http_permits_replay_expiry_body_tamper_and_emergency_key(tmp_path, monkeypatch):
    from app import main
    driver, fake = gateway(tmp_path)
    monkeypatch.setattr(main, "settings", driver.settings)
    monkeypatch.setattr(main, "driver", driver)
    # No lifespan: in-memory ASGI requests exercise real endpoint dependencies,
    # while fake ESL cannot place a real call or contact any external service.
    client = TestClient(main.app)
    path, payload = "/v1/call/dial", request()
    assert client.post(path, json=payload).status_code == 401
    assert client.post(path, json=payload, headers={"Authorization": f"Bearer {driver.settings.service_token}"}).status_code == 401
    body, headers = signed(driver.settings, path, payload, stamp=int(time.time()) - 61)
    assert client.post(path, content=body, headers=headers).status_code == 401
    body, headers = signed(driver.settings, path, payload)
    assert client.post(path, content=body + b" ", headers=headers).status_code == 401
    assert client.post(path, content=body, headers=headers).status_code == 200
    assert client.post(path, content=body, headers=headers).status_code == 409
    assert len(fake.bgapi_commands) == 1
    stop = {"stopped": True, "reason": "synthetic safety drill"}
    assert client.post("/v1/admin/security/stop", json=stop, headers=headers).status_code == 403
    admin = {"Authorization": f"Bearer {driver.settings.voice_security_admin_token}"}
    assert client.post("/v1/admin/security/stop", json=stop, headers=admin).json()["stopped"]
    assert Ledger(driver.settings.voice_security_db_path).summary()["stopped"]
    body, headers = signed(driver.settings, path, request("new-call"))
    assert client.post(path, content=body, headers=headers).status_code == 503
    assert client.post("/v1/admin/security/stop", json={**stop, "stopped": False}, headers=admin).status_code == 200
    body, headers = signed(driver.settings, path, request("new-call"))
    assert client.post(path, content=body, headers=headers).status_code == 200


def test_callback_destination_restrictions_do_not_send_any_network_request(tmp_path, monkeypatch):
    settings = configuration(tmp_path)
    for url in ("http://control-api:8000/api/v1/webhooks/telephony/status?x=1", "http://evil.example/api/v1/webhooks/telephony/status",
                "http://control-api:8000/admin", "http://control-api:8000/api/v1/webhooks/telephony/status#fragment"):
        with pytest.raises(HTTPException):
            validate_callback_url(settings, url)


def test_mock_gateway_cannot_exfiltrate_callback_credentials(tmp_path):
    from app.drivers import MockDriver
    async def run():
        settings = configuration(tmp_path, voice_gateway_driver="mock")
        driver = MockDriver(settings)
        with pytest.raises(HTTPException) as exc:
            await driver.post("dial", {**request(), "webhook_url": "http://untrusted.example/hook"})
        assert exc.value.status_code == 403
    asyncio.run(run())


def test_callback_outbox_signing_retry_and_failure_circuit(tmp_path, monkeypatch):
    async def run():
        driver, _ = gateway(tmp_path)
        payload = {"call_id": "audit-1", "kind": "status", "payload": {"status": "answered", "event_id": "stable-id"}}
        url = request()["webhook_url"]
        seen, fail = [], True
        def transport(req):
            seen.append(req)
            return httpx.Response(503 if fail else 200)
        original = httpx.AsyncClient
        monkeypatch.setattr(httpx, "AsyncClient", lambda **kw: original(transport=httpx.MockTransport(transport), **kw))
        await driver.sender.post(url, payload)
        await driver.sender.flush()
        assert driver.ledger.summary()["pending_callbacks"] == 1
        with driver.ledger.transaction() as db:
            db.execute("UPDATE outbox SET created=?,due=0", (time.time() - 35,))
        with pytest.raises(HTTPException, match="callback"):
            await driver.post("dial", request())
        fail = False
        restarted_sender = CallbackSender(driver.settings, Ledger(driver.settings.voice_security_db_path))
        await restarted_sender.flush()
        assert driver.ledger.summary()["pending_callbacks"] == 0
        assert seen[0].content == seen[1].content == canonical(payload)
        for req in seen:
            stamp = req.headers["x-webhook-timestamp"]
            expected = hmac.new(driver.settings.webhook_secret.encode(), stamp.encode() + b"." + req.content, hashlib.sha256).hexdigest()
            assert req.headers["x-webhook-signature"] == expected
            assert req.headers["x-webhook-token"] == driver.settings.webhook_token
    asyncio.run(run())


def test_second_media_worker_cannot_open_same_ledger(tmp_path):
    async def run():
        first, fake = gateway(tmp_path)
        async def idle_events(_):
            await asyncio.Event().wait()
            if False:
                yield {}
        fake.events = idle_events
        second, _ = gateway(tmp_path)
        await first.start()
        try:
            with pytest.raises(BlockingIOError):
                await second.start()
        finally:
            await first.stop()
    asyncio.run(run())
