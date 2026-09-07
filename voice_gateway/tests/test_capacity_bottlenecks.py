import asyncio
import threading
import time
import json
import pytest

from test_security import gateway, request


def test_wal_reads_do_not_wait_for_writer(tmp_path):
    secured, _ = gateway(tmp_path)
    secured.ledger.summary()
    held = threading.Event()
    release = threading.Event()
    def writer():
        with secured.ledger.transaction():
            held.set()
            release.wait(2)
    thread = threading.Thread(target=writer)
    thread.start(); assert held.wait(1)
    try:
        started = time.monotonic()
        assert secured.ledger.summary()["active_attempts"] == 0
        assert time.monotonic() - started < .2
    finally:
        release.set(); thread.join()


def test_esl_slow_call_does_not_block_another_and_keeps_order(tmp_path, monkeypatch):
    async def run():
        secured, _ = gateway(tmp_path)
        driver = secured.driver
        blocked, fast = asyncio.Event(), asyncio.Event()
        seen = []
        async def handle(event):
            if event["Unique-ID"] == "a":
                await blocked.wait()
            seen.append((event["Unique-ID"], event["seq"]))
            if event["Unique-ID"] == "b" and event["seq"] == 2:
                fast.set()
        monkeypatch.setattr(driver, "_handle_event", handle)
        workers = [asyncio.create_task(driver._event_worker(i)) for i in range(len(driver.event_queues))]
        try:
            for cid, seq in [("a", 1), ("b", 1), ("b", 2)]:
                await driver._enqueue_event({"Unique-ID": cid, "seq": seq})
            await asyncio.wait_for(fast.wait(), 1)
            assert seen == [("b", 1), ("b", 2)]
            assert driver.event_metrics()["oldest_age_sec"] > 0
        finally:
            blocked.set()
            for worker in workers: worker.cancel()
            await asyncio.gather(*workers, return_exceptions=True)
    asyncio.run(run())


def test_admission_capacity_rejection_proves_no_dial_intent(tmp_path):
    from fastapi import HTTPException
    async def run():
        secured, _ = gateway(tmp_path)
        secured.settings.voice_cps = 1
        await secured.post("dial", request())
        second = request(); second["call_id"] = "second"
        try:
            await secured.post("dial", second)
            raise AssertionError("second call must be deferred")
        except HTTPException as exc:
            assert exc.status_code == 429
            assert exc.headers["X-Voice-Dial-Admitted"] == "false"
            assert secured.ledger.lookup("second", 1) is None
    asyncio.run(run())


def test_cluster_agent_route_is_explicit_and_not_taken_from_request(tmp_path):
    async def run():
        secured, fake = gateway(tmp_path, voice_node_id="node-a",
            voice_agent_registrars_json=json.dumps({"1:23": "10.20.0.12:5060"}))
        payload = request(); payload["metadata"]["gateway_node_id"] = "node-a"
        await secured.post("dial", payload)
        binding = secured.driver.calls_by_id[payload["call_id"]]
        await secured.post("transfer", {"call_id": payload["call_id"], "tenant_id": 1,
            "target_group": "agent:23", "registrar": "attacker.invalid:5060"})
        assert f"uuid_setvar {binding.fs_uuid} platform_agent_route sofia/internal/agent_23@10.20.0.12:5060" in fake.api_commands
        assert not any("attacker" in command for command in fake.api_commands)
        with pytest.raises(ValueError, match="no approved registrar"):
            secured.driver._agent_destination(2, 23)
    asyncio.run(run())


def test_stale_speech_generation_cannot_speak_or_hangup(tmp_path):
    from fastapi import HTTPException
    from types import SimpleNamespace
    async def run():
        secured, fake = gateway(tmp_path)
        payload = request(); await secured.post("dial", payload)
        secured.driver.pipecat_manager = SimpleNamespace(sessions_by_call={payload["call_id"]:
            SimpleNamespace(latest_final_event_id="new-final")})
        before = list(fake.api_commands)
        for action in ("speak", "hangup"):
            with pytest.raises(HTTPException) as caught:
                await secured.post(action, {"call_id": payload["call_id"], "tenant_id": 1,
                    "expected_speech_event_id": "old-final", "text": "obsolete"})
            assert caught.value.status_code == 409
        assert fake.api_commands == before
        row = secured.ledger.lookup(payload["call_id"], 1)
        secured.ledger.finish(row["uuid"])
        assert (await secured.post("hangup", {"call_id": payload["call_id"], "tenant_id": 1,
                    "expected_speech_event_id": "old-final"}))["ended"] is True
    asyncio.run(run())


def test_recording_download_is_signed_expiring_and_node_bound(tmp_path, monkeypatch):
    from urllib.parse import urlsplit, parse_qs
    from fastapi import HTTPException
    from app import main
    from app.recording_source import recording_url, authorized_path
    import httpx
    settings = main.settings
    monkeypatch.setattr(settings, "voice_command_secret", "synthetic-recording-key-" * 2)
    monkeypatch.setattr(settings, "voice_node_id", "node-a")
    monkeypatch.setattr(settings, "voice_recording_source_base_url", "http://owner.invalid")
    monkeypatch.setattr(settings, "freeswitch_recording_dir", str(tmp_path))
    (tmp_path / "synthetic.wav").write_bytes(b"RIFFsynthetic")
    url = recording_url(settings, "synthetic.wav")
    args = parse_qs(urlsplit(url).query)
    expires, permit = int(args["expires"][0]), args["signature"][0]
    assert authorized_path(settings, "synthetic.wav", expires, permit) == tmp_path / "synthetic.wav"
    with pytest.raises(HTTPException):
        authorized_path(settings, "../synthetic.wav", expires, permit)
    with pytest.raises(HTTPException):
        authorized_path(settings, "synthetic.wav", int(time.time()) - 1, permit)
    async def get():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(main.app), base_url="http://owner.invalid") as client:
            response = await client.get(url)
            assert response.status_code == 200 and response.content == b"RIFFsynthetic"
            assert (await client.get(url.replace(permit, "invalid"))).status_code == 403
    asyncio.run(get())
    monkeypatch.setattr(settings, "voice_node_id", "node-b")
    with pytest.raises(HTTPException):
        authorized_path(settings, "synthetic.wav", expires, permit)


def test_more_than_64_events_for_one_call_do_not_block_hash_collision(tmp_path, monkeypatch):
    async def run():
        secured,_=gateway(tmp_path);driver=secured.driver
        blocked,fast=asyncio.Event(),asyncio.Event();seen=[]
        async def handle(event):
            if event['Unique-ID']=='ab':await blocked.wait()
            seen.append((event['Unique-ID'],event['seq']))
            if event['Unique-ID']=='ba':fast.set()
        monkeypatch.setattr(driver,'_handle_event',handle)
        workers=[asyncio.create_task(driver._event_worker(i)) for i in range(32)]
        try:
            async def enqueue():
                for index in range(100):await driver._enqueue_event({'Unique-ID':'ab','seq':index})
                await driver._enqueue_event({'Unique-ID':'ba','seq':0})
            await asyncio.wait_for(enqueue(),1)
            await asyncio.wait_for(fast.wait(),1)
            blocked.set();await asyncio.wait_for(driver.event_ready.join(),2)
            assert [seq for cid,seq in seen if cid=='ab']==list(range(100))
            assert driver.event_metrics()['queue_depth']==0
        finally:
            blocked.set()
            for worker in workers:worker.cancel()
            await asyncio.gather(*workers,return_exceptions=True)
    asyncio.run(run())
