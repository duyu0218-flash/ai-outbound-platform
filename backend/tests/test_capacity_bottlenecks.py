import asyncio
import json
from datetime import timedelta
from uuid import uuid4

import pytest
from sqlmodel import select

from test_production_hardening import client, reset_runtime_settings_after_test
from test_compact_cluster import cluster, make_calls, claim
from app.clock import utc_now
from app.db import engine, session_scope
from app.models import CallSession, CallStatus, CallMode, TaskOutbox, TaskState, KnowledgeItem, GatewayNode, RealtimeSession
from app.services import task_queue, dispatcher, gateway_cluster
from app.services.knowledge import retrieve_knowledge
from app.schemas import AiTurnResult


def test_archived_task_replay_keeps_identity_and_never_reexecutes(client):
    from app.models import TaskReceipt, CallMetric, CallEvent, WebhookEventIngest
    from app.services.retention import archive_expired_operational_rows
    old = utc_now() - timedelta(days=181)
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone="redacted:synthetic", mode=CallMode.AI_ONLY,
                           status=CallStatus.COMPLETED, finished_at=old)
        session.add(call); session.commit(); cid = call.id
        task = task_queue.enqueue_task(session, tenant_id=1, task_type="business_callback", aggregate_id=str(cid),
            idempotency_key=f"archive:{cid}", payload={"synthetic": True})
        tid = task.id
        task.state = TaskState.COMPLETED; task.updated_at = old
        session.add(task)
        session.add(CallMetric(tenant_id=1, call_session_id=cid, stage="asr.final", created_at=old))
        session.add(CallEvent(call_session_id=cid, event_type="speech_final", created_at=old))
        session.commit()
    result = archive_expired_operational_rows()
    assert result["archived_tasks"] >= 1 and result["expired_events"] >= 1 and result["expired_metrics"] >= 1
    with session_scope() as session:
        assert session.get(TaskOutbox, tid) is None
        assert session.get(TaskReceipt, tid) is not None
        replay = task_queue.enqueue_task(session, tenant_id=1, task_type="business_callback", aggregate_id=str(cid),
            idempotency_key=f"archive:{cid}", payload={"synthetic": True})
        assert replay.id == tid and replay.state == TaskState.COMPLETED
        assert session.get(TaskOutbox, tid) is None


@pytest.mark.asyncio
async def test_dial_task_deferral_does_not_exhaust_attempts(client, monkeypatch):
    from app.services import call_service
    async def defer(session, call):
        return call, False
    monkeypatch.setattr(call_service, "_place_call_with_result", defer)
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone="13800000000", mode=CallMode.AI_ONLY, status=CallStatus.QUEUED)
        session.add(call); session.commit(); cid = call.id
    call_service.enqueue_dial_calls([cid])
    with session_scope() as session:
        task = session.exec(select(TaskOutbox).where(TaskOutbox.aggregate_id == str(cid), TaskOutbox.task_type == "dial_call")).one()
        tid = task.id
    assert await task_queue.process_task(tid) is False
    with session_scope() as session:
        task = session.get(TaskOutbox, tid)
        assert task.state == TaskState.PENDING and task.attempts == 0
        assert task.available_at > utc_now()
        assert session.get(CallSession, cid).attempts == 0
        task.state = TaskState.COMPLETED
        call = session.get(CallSession, cid); call.status = CallStatus.COMPLETED
        session.add_all([task, call]); session.commit()


def test_new_final_supersedes_delayed_ai_retry_but_preserves_callbacks(client):
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone="13800000000", mode=CallMode.AI_ONLY,
                           status=CallStatus.IN_AI, attempts=1)
        session.add(call); session.commit()
        cid = call.id
        old = task_queue.enqueue_task(session, tenant_id=1, task_type="ai_turn", aggregate_id=str(cid),
            idempotency_key=f"old:{cid}", payload={"call_id": str(cid), "attempt": 1, "turn_sequence": 1})
        old.state = TaskState.FAILED; old.available_at = utc_now() + timedelta(seconds=30)
        session.add(old); session.commit()
        old_id = old.id
        newer = task_queue.enqueue_task(session, tenant_id=1, task_type="ai_turn", aggregate_id=str(cid),
            idempotency_key=f"new:{cid}", payload={"call_id": str(cid), "attempt": 1, "turn_sequence": 2})
        assert session.get(TaskOutbox, old_id).state == TaskState.COMPLETED
        new_id = newer.id
    claims = task_queue.claim_ready_tasks(("ai_turn",), 1000)
    assert new_id in {row[0] for row in claims}


def test_disabled_callback_creates_no_task(client, monkeypatch):
    monkeypatch.setattr("app.services.admin_settings.get_admin_setting", lambda *args: {"callback_enabled": False})
    with session_scope() as session:
        cid = uuid4()
        assert task_queue.enqueue_business_callback(session, tenant_id=1, call_id=cid,
            event_type="call.speech_final", data={}) is None
        assert not session.exec(select(TaskOutbox).where(TaskOutbox.aggregate_id == str(cid))).all()


def test_knowledge_cache_refreshes_edits_activation_and_isolates_tenants(client):
    from app.services import knowledge
    knowledge._cache.clear()
    marker = uuid4().hex
    with session_scope() as session:
        row = KnowledgeItem(tenant_id=1, title=marker, content="first", keywords=marker)
        session.add(row); session.commit(); kid = row.id
        assert retrieve_knowledge(session, 1, marker)[0]["content"] == "first"
        assert retrieve_knowledge(session, 999999, marker) == []
        row.content = "updated"; row.version += 1; row.updated_at = utc_now()
        session.add(row); session.commit()
        assert retrieve_knowledge(session, 1, marker)[0]["content"] == "updated"
        row.is_active = False; session.add(row); session.commit()
        assert retrieve_knowledge(session, 1, marker) == []
        session.delete(session.get(KnowledgeItem, kid)); session.commit()


def test_cps_is_reserved_before_attempt_and_shared_across_claims(cluster, monkeypatch):
    specs, ids = cluster
    for spec in specs:
        spec["cps"] = 1
    monkeypatch.setattr(gateway_cluster.settings, "voice_gateway_nodes_json", json.dumps(specs))
    calls = make_calls(ids, 5)
    assert sum(claim(cid) for cid in calls) == 4
    with session_scope() as session:
        deferred = session.get(CallSession, calls[-1])
        assert deferred.status == CallStatus.QUEUED and deferred.attempts == 0
        node = session.get(GatewayNode, specs[0]["id"])
        node.next_dial_at = utc_now() - timedelta(seconds=1)
        session.add(node); session.commit()
    assert claim(calls[-1]) is True


def test_heartbeat_does_not_take_platform_admission_lock(cluster, monkeypatch):
    specs, _ = cluster
    def unexpected(*args):
        raise AssertionError("normal health update took global admission lock")
    monkeypatch.setattr(gateway_cluster, "lock_platform_admission", unexpected)
    gateway_cluster._store_probe(gateway_cluster.NodeSpec.model_validate(specs[0]), utc_now(), True, 200)


@pytest.mark.asyncio
async def test_hangup_await_has_no_checked_out_connection(client, monkeypatch):
    checked = []
    class Adapter:
        async def hangup(self, **kwargs):
            checked.append(engine.pool.checkedout())
            await asyncio.sleep(.01)
            return {"ended": True}
    monkeypatch.setattr(dispatcher, "get_telephony_adapter", lambda **kwargs: Adapter())
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone="13800000000", mode=CallMode.AI_ONLY,
                           status=CallStatus.IN_AI, attempts=1)
        session.add(call); session.commit()
        await dispatcher._apply_ai_action(session=session, call=call, result=AiTurnResult(action="hangup"))
    assert checked == [0]


def test_worker_reuses_loop_and_http_client_and_closes_resources(monkeypatch):
    from app.services import worker_runtime
    created, closed, loops = [], [], []
    class Client:
        def __init__(self, **kwargs): created.append(self)
        async def aclose(self): closed.append(self)
    monkeypatch.setattr(worker_runtime.httpx, "AsyncClient", Client)
    runtime = worker_runtime.WorkerRuntime()
    async def job():
        loops.append(asyncio.get_running_loop())
        async with worker_runtime.http_client(timeout=1) as client:
            return client
    first = runtime.run(job()); second = runtime.run(job())
    assert first is second and len(created) == 1 and loops[0] is loops[1]
    runtime.close()
    assert closed == created and loops[0].is_closed()


@pytest.mark.asyncio
async def test_playback_continuation_does_not_hangup_a_new_user_turn(client, monkeypatch):
    called = []
    class Adapter:
        async def speak(self, **kwargs): return {"playback_id": "old-playback"}
        async def hangup(self, **kwargs): called.append(kwargs); return {"ended": True}
    monkeypatch.setattr(dispatcher, "get_telephony_adapter", lambda **kwargs: Adapter())
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone="13800000000", mode=CallMode.AI_ONLY,
                           status=CallStatus.IN_AI, attempts=1)
        session.add(call); session.commit(); cid = call.id
        rt = RealtimeSession(tenant_id=1, call_session_id=cid, attempt=1, turn_sequence=1)
        session.add(rt); session.commit()
        await dispatcher._apply_ai_action(session=session, call=call,
                                         result=AiTurnResult(action="hangup", tts_text="bye"))
        task = session.exec(select(TaskOutbox).where(TaskOutbox.aggregate_id == str(cid),
            TaskOutbox.task_type == "after_playback")).one()
        tid = task.id; task.available_at = utc_now()
        rt.turn_sequence = 2
        session.add_all([rt, task]); session.commit()
    assert await task_queue.process_task(tid) is True
    assert called == []
