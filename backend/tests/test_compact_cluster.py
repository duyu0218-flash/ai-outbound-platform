"""Capacity correctness, owner routing, and continuous worker regression tests."""
import asyncio
import json
import threading
from datetime import timedelta
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest
from sqlalchemy import delete, func
from sqlmodel import select

from test_production_hardening import client, reset_runtime_settings_after_test
from app.clock import utc_now
from app.db import session_scope, engine
from app.models import CallSession, CallStatus, CallMode, GatewayNode, TaskOutbox, TaskState, RealtimeSession
from app.services import call_service, gateway_cluster, task_queue, telephony, dispatcher


@pytest.fixture
def cluster(client, monkeypatch):
    marker = uuid4().hex[:10]
    specs = [{'id': f'{marker}-{i}', 'endpoint': f'http://node-{i}.invalid:8002',
              'capacity': 200, 'routes': ['1:0']} for i in range(4)]
    monkeypatch.setattr(gateway_cluster.settings, 'voice_gateway_nodes_json', json.dumps(specs))
    monkeypatch.setattr(gateway_cluster.settings, 'voice_gateway_nodes_file', '')
    monkeypatch.setattr(call_service.settings, 'outbound_platform_max_concurrent', 500)
    monkeypatch.setattr(call_service, 'get_tenant_max_concurrent_calls', lambda *args: 500)
    monkeypatch.setattr(call_service, 'can_call_contact_sync', lambda *args, **kwargs: (True, ''))
    monkeypatch.setattr(call_service, 'list_tenant_telephony_lines', lambda *args: [])
    monkeypatch.setattr(call_service.settings, 'telephony_provider', 'http')
    with session_scope() as session:
        # Other regression modules leave historical live fixtures. Tests scope
        # admission against an otherwise idle isolated test database.
        live = session.exec(select(CallSession).where(CallSession.status.in_(call_service.CAPACITY_STATUSES))).all()
        prior = [(call.id, call.status) for call in live]
        for call in live:
            call.status = CallStatus.COMPLETED; session.add(call)
        for spec in specs:
            session.add(GatewayNode(id=spec['id'], endpoint=spec['endpoint'], capacity=200, ready=True))
        session.commit()
    ids = []
    yield specs, ids
    with session_scope() as session:
        for cid in ids:
            call = session.get(CallSession, cid)
            if call:
                call.status = CallStatus.COMPLETED; session.add(call)
        for cid, state in prior:
            call = session.get(CallSession, cid)
            if call:
                call.status = state; session.add(call)
        session.exec(delete(GatewayNode).where(GatewayNode.id.in_([v['id'] for v in specs])))
        session.commit()


def make_calls(ids, count, **kwargs):
    with session_scope() as session:
        rows = [CallSession(tenant_id=1, phone=f'139{str(i).zfill(8)}', mode=CallMode.AI_ONLY,
                            **{'status': CallStatus.QUEUED, **kwargs}) for i in range(count)]
        session.add_all(rows); session.commit()
        created = [row.id for row in rows]
        ids.extend(created)
        return created


def claim(cid):
    with session_scope() as session:
        return call_service._claim_dispatch_slot(session, session.get(CallSession, cid))


def test_node_201st_admission_is_denied_before_dial_and_unknown_is_retained(cluster):
    specs, ids = cluster
    make_calls(ids, 200, status=CallStatus.DIALING, attempts=1,
               gateway_node_id=specs[0]['id'], gateway_endpoint=specs[0]['endpoint'],
               last_error='dial outcome unknown')
    with session_scope() as session:
        for spec in specs[1:]:
            node = session.get(GatewayNode, spec['id']); node.ready = False; session.add(node)
        session.commit()
    cid = make_calls(ids, 1)[0]
    assert claim(cid) is False
    with session_scope() as session:
        assert session.get(CallSession, cid).attempts == 0
        first = session.get(CallSession, ids[0]); first.status = CallStatus.COMPLETED; session.add(first); session.commit()
    assert claim(cid) is True


def test_global_500_limit_is_atomic_across_concurrent_claims(cluster):
    specs, ids = cluster
    for i, spec in enumerate(specs):
        make_calls(ids, 125 if i < 3 else 124, status=CallStatus.DIALING, attempts=1,
                   gateway_node_id=spec['id'], gateway_endpoint=spec['endpoint'])
    candidates = make_calls(ids, 8)
    with ThreadPoolExecutor(max_workers=8) as pool:
        assert sum(pool.map(claim, candidates)) == 1
    with session_scope() as session:
        claimed = [session.get(CallSession, cid) for cid in candidates]
        selected = next(call for call in claimed if call.attempts)
        assert selected.gateway_node_id == specs[3]['id']


def test_owner_commands_ignore_changed_line_and_node_health(cluster, monkeypatch):
    specs, ids = cluster
    cid = make_calls(ids, 1)[0]
    assert claim(cid)
    with session_scope() as session:
        call = session.get(CallSession, cid)
        owner = call.gateway_endpoint
        monkeypatch.setattr(telephony.settings, 'telephony_provider_endpoint', 'http://wrong.invalid')
        node = session.get(GatewayNode, call.gateway_node_id); node.ready = False; session.add(node); session.commit()
        adapter = telephony.get_telephony_adapter(session=session, tenant_id=1, call_id=cid)
        assert adapter.endpoint == owner and adapter.expected_attempt == 1
        with pytest.raises(RuntimeError, match='tenant'):
            telephony.get_telephony_adapter(session=session, tenant_id=999, call_id=cid)


def test_stale_health_excludes_new_calls(cluster):
    specs, ids = cluster
    with session_scope() as session:
        for spec in specs:
            node = session.get(GatewayNode, spec['id']); node.checked_at = utc_now()-timedelta(minutes=1)
            session.add(node)
        session.commit()
    assert not claim(make_calls(ids, 1)[0])


def test_continuous_pool_refills_before_slow_job_finishes(client, monkeypatch):
    kind = 'compact_' + uuid4().hex
    slow_started = threading.Event(); release = threading.Event(); second_done = threading.Event()
    ids = []
    with session_scope() as session:
        for name in ['slow', 'fast1', 'fast2']:
            task = task_queue.enqueue_task(session, tenant_id=1, task_type=kind, aggregate_id=name,
                idempotency_key=uuid4().hex, payload={'name': name})
            ids.append(task.id)
    async def execute(task_id, token, task_type, payload):
        if payload['name'] == 'slow':
            slow_started.set()
            await asyncio.to_thread(release.wait, 3)
        if payload['name'] == 'fast2':
            second_done.set()
    monkeypatch.setattr(task_queue, '_execute_task', execute)
    async def run():
        stop = asyncio.Event()
        job = asyncio.create_task(task_queue.run_task_lane(stop, task_types=(kind,), concurrency=2))
        try:
            assert await asyncio.to_thread(slow_started.wait, 3)
            assert await asyncio.to_thread(second_done.wait, 3)
            assert not release.is_set()
        finally:
            release.set(); stop.set(); await job
    try:
        asyncio.run(run())
    finally:
        with session_scope() as session:
            session.exec(delete(TaskOutbox).where(TaskOutbox.id.in_(ids))); session.commit()


def test_concurrent_task_claimers_never_take_same_job(client):
    kind = 'compact_' + uuid4().hex
    ids = []
    with session_scope() as session:
        for i in range(30):
            ids.append(task_queue.enqueue_task(session, tenant_id=1, task_type=kind, aggregate_id=str(i),
                idempotency_key=uuid4().hex, payload={}).id)
    try:
        with ThreadPoolExecutor(max_workers=4) as pool:
            batches = list(pool.map(lambda _: task_queue.claim_ready_tasks((kind,), 10), range(4)))
        claimed = [cid for batch in batches for cid, _ in batch]
        assert len(claimed) == len(set(claimed)) == 30
    finally:
        with session_scope() as session:
            session.exec(delete(TaskOutbox).where(TaskOutbox.id.in_(ids))); session.commit()


def test_new_customer_turn_invalidates_pending_ai_output(client):
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone='13900000000', mode=CallMode.AI_ONLY, status=CallStatus.IN_AI, attempts=1)
        session.add(call); session.commit()
        rt = RealtimeSession(tenant_id=1, call_session_id=call.id, turn_sequence=2)
        session.add(rt); session.commit()
        token = dispatcher._expected_turn_sequence.set(1)
        try:
            assert not dispatcher._ai_call_is_current(session, call, 1)
        finally:
            dispatcher._expected_turn_sequence.reset(token)
            call.status = CallStatus.COMPLETED; session.add(call); session.commit()


def test_async_analysis_is_in_same_transaction_as_terminal_status(client, monkeypatch):
    from test_review_fixes import make_call, status
    from app.api.routers import webhooks
    from app.models import CallAnalysis
    monkeypatch.setattr(webhooks.settings, 'terminal_analysis_async', True)
    cid = make_call()
    status(cid, 'completed', billsec=10)
    with session_scope() as session:
        assert session.get(CallSession, cid).status == CallStatus.COMPLETED
        task = session.exec(select(TaskOutbox).where(TaskOutbox.aggregate_id == str(cid), TaskOutbox.task_type == 'call_analysis')).one()
        assert session.exec(select(CallAnalysis).where(CallAnalysis.call_session_id == cid)).first() is None
        tid = task.id
    assert asyncio.run(task_queue.process_task(tid))
    with session_scope() as session:
        assert session.exec(select(CallAnalysis).where(CallAnalysis.call_session_id == cid)).first() is not None
    cid2 = make_call()
    def fail(*args, **kwargs):
        raise RuntimeError('outbox unavailable')
    monkeypatch.setattr(webhooks, 'enqueue_task', fail)
    try:
        with pytest.raises(RuntimeError, match='outbox unavailable'):
            status(cid2, 'completed')
        with session_scope() as session:
            assert session.get(CallSession, cid2).status == CallStatus.IN_AI
    finally:
        with session_scope() as session:
            call = session.get(CallSession, cid2); call.status = CallStatus.COMPLETED; session.add(call)
            session.exec(delete(TaskOutbox).where(TaskOutbox.aggregate_id.in_([str(cid), str(cid2)])))
            session.commit()


def test_speak_and_transfer_are_fenced_by_attempt(monkeypatch):
    import httpx
    sent = []
    def respond(request):
        sent.append(json.loads(request.content))
        return httpx.Response(200, json={'result': 'ok'})
    original = httpx.AsyncClient
    monkeypatch.setattr(httpx, 'AsyncClient', lambda **kw: original(transport=httpx.MockTransport(respond), **kw))
    monkeypatch.setattr(telephony.settings, 'voice_command_secret', 'compact-test-command-key')
    async def run():
        adapter = telephony.HttpAdapter('http://owner.invalid', tenant_id=1, expected_attempt=3, provider_call_id='pbx-3')
        await adapter.speak(call_id='call', text='hello')
        await adapter.transfer_to_human(call_id='call', reason='test', target_group='agent:1')
    asyncio.run(run())
    assert all(row['expected_attempt'] == 3 and row['provider_call_id'] == 'pbx-3' for row in sent)


def test_empty_configured_roster_never_falls_back_to_single_gateway(tmp_path, monkeypatch):
    path = tmp_path / 'nodes.json'; path.write_text('[]')
    monkeypatch.setattr(gateway_cluster.settings, 'voice_gateway_nodes_file', str(path))
    with pytest.raises(ValueError, match='must not be empty'):
        gateway_cluster.node_specs()


def test_gateway_probe_checks_identity_and_reported_capacity(cluster, monkeypatch):
    import httpx
    specs, ids = cluster
    original = httpx.AsyncClient
    def respond(request):
        i = int(request.url.host.split('-')[1].split('.')[0])
        return httpx.Response(200, json={'status':'ready', 'node_id':specs[i]['id'] if i else 'wrong-node', 'call_capacity':100})
    monkeypatch.setattr(httpx, 'AsyncClient', lambda **kw: original(transport=httpx.MockTransport(respond), **kw))
    asyncio.run(gateway_cluster.probe_gateways())
    with session_scope() as session:
        assert not session.get(GatewayNode, specs[0]['id']).ready
        assert session.get(GatewayNode, specs[1]['id']).capacity == 100
    cid = make_calls(ids, 1)[0]
    assert claim(cid)
    with session_scope() as session:
        assert session.get(CallSession, cid).gateway_node_id != specs[0]['id']


def test_first_gateway_failure_does_not_remove_all_api_replicas(cluster):
    from app.services.health import telephony_http_health_check, tenant_telephony_health_check
    specs, ids = cluster
    with session_scope() as session:
        first = session.get(GatewayNode, specs[0]['id']); first.ready = False; session.add(first); session.commit()
        assert telephony_http_health_check() == 'ok'
        assert tenant_telephony_health_check(session, 1) == 'ok'
        assert tenant_telephony_health_check(session, 999) == 'unavailable'
        for spec in specs:
            node = session.get(GatewayNode, spec['id']); node.ready = False; session.add(node)
        session.commit()
        assert telephony_http_health_check() == 'unavailable'
