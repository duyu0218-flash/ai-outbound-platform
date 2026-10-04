"""AI execution failure must stop new dialing without releasing active calls."""
import asyncio
import json
import os
import time
from datetime import timedelta
from uuid import uuid4

import pytest
import redis
from sqlalchemy import delete

from test_production_hardening import client
from app.clock import utc_now
from app.config import Settings
from app.db import session_scope
from app.models import TaskOutbox, TaskState, CallSession, CallStatus
from app.services import ai_capacity, call_service
from test_compact_cluster import cluster, make_calls, claim


def configuration(**kwargs):
    return Settings(_env_file=None, ai_worker_requirements_json=json.dumps(
        {f'ai-worker-{i}':160 for i in range(1,5)}), **kwargs)


def healthy_values():
    values=[]
    for i in range(1,5):
        owner=f'epoch-{i}'
        values.extend([owner,json.dumps(dict(worker_id=f'ai-worker-{i}',epoch=owner,
            limit=160,inflight=0,ready=True,updated_at=time.time()))])
    return values


def mock_registry(monkeypatch, values):
    class Reader:
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def mget(self,keys):
            assert len(keys)==8 and len(set(keys))==8
            return values
    monkeypatch.setattr(ai_capacity.redis.Redis,'from_url',lambda *a,**kw:Reader())


@pytest.mark.parametrize('fault', ['missing','wrong_epoch','wrong_slots','future','stale','nan','not_ready','inflight','wrong_id'])
def test_invalid_worker_state_stops_admission(monkeypatch, fault):
    values=healthy_values()
    state=json.loads(values[-1])
    updates={'wrong_epoch':dict(epoch='old'), 'wrong_slots':dict(limit=159),
        'future':dict(updated_at=time.time()+100), 'stale':dict(updated_at=time.time()-30),
        'nan':dict(updated_at=float('nan')), 'not_ready':dict(ready=False),
        'inflight':dict(inflight=161), 'wrong_id':dict(worker_id='unapproved')}
    if fault=='missing':values[-1]=None
    else:
        state.update(updates[fault]);values[-1]=json.dumps(state)
    mock_registry(monkeypatch,values)
    assert not ai_capacity.workers_ready(configuration(redis_url='redis://registry.invalid'))


def test_redis_unavailability_and_invalid_roster_fail_closed(monkeypatch):
    def unavailable(*args,**kwargs):raise redis.ConnectionError('synthetic failure')
    monkeypatch.setattr(ai_capacity.redis.Redis,'from_url',unavailable)
    assert not ai_capacity.workers_ready(configuration(redis_url='redis://registry.invalid'))
    cfg=configuration()
    cfg.ai_worker_requirements_json='{"worker":true}'
    assert not ai_capacity.workers_ready(cfg)
    assert ai_capacity.workers_ready(Settings(_env_file=None,redis_url=''))


def test_actual_claim_stops_on_worker_loss_and_recovers(cluster, monkeypatch):
    _,ids=cluster
    cfg=configuration(redis_url='redis://registry.invalid')
    for field in ('ai_worker_requirements_json','redis_url'):
        monkeypatch.setattr(call_service.settings,field,getattr(cfg,field))
    values=healthy_values();mock_registry(monkeypatch,values)
    active=make_calls(ids,1,status=CallStatus.IN_AI,attempts=1)[0]
    candidate=make_calls(ids,1)[0]
    original=values[-1];values[-1]=None
    assert claim(candidate) is False
    with session_scope() as session:
        assert session.get(CallSession,candidate).attempts==0
        assert session.get(CallSession,active).status==CallStatus.IN_AI
    values[-1]=original
    assert claim(candidate) is True


def test_heartbeat_expiring_during_admission_lock_cannot_dial(cluster, monkeypatch):
    from app.services import gateway_cluster
    _,ids=cluster
    candidate=make_calls(ids,1)[0]
    # Give the admission snapshot a deadline which becomes expired while locked.
    monkeypatch.setattr(ai_capacity,'workers_valid_until',lambda _:time.monotonic()+.001)
    original=gateway_cluster.lock_platform_admission
    def delayed(session):
        original(session)
        time.sleep(.01)
    monkeypatch.setattr(gateway_cluster,'lock_platform_admission',delayed)
    assert claim(candidate) is False
    with session_scope() as session:
        call=session.get(CallSession,candidate)
        assert call.attempts==0 and call.status==CallStatus.QUEUED


def test_only_executable_ai_backlog_blocks_dialing(client, monkeypatch):
    cfg=configuration()
    kind='ai_turn'
    now=utc_now()
    tasks=[TaskOutbox(tenant_id=1,task_type=kind,aggregate_id=uuid4().hex,
        idempotency_key=uuid4().hex,payload_json='{}',available_at=now-timedelta(seconds=3))
        for _ in range(5)]
    tasks[1].state=TaskState.COMPLETED
    tasks[2].state=TaskState.DEAD
    tasks[3].state=TaskState.FAILED;tasks[3].available_at=now+timedelta(seconds=30)
    tasks[4].state=TaskState.PROCESSING;tasks[4].locked_at=now;tasks[4].attempts=1
    task_ids=[t.id for t in tasks]
    with session_scope() as session:
        session.add_all(tasks);session.commit()
    try:
        with session_scope() as session:
            assert not ai_capacity.queue_ready(session,cfg)
            session.get(TaskOutbox,task_ids[0]).state=TaskState.COMPLETED;session.commit()
            assert ai_capacity.queue_ready(session,cfg)
            processing=session.get(TaskOutbox,task_ids[4])
            processing.locked_at=now-timedelta(seconds=cfg.task_lease_sec+3)
            session.add(processing);session.commit()
            assert not ai_capacity.queue_ready(session,cfg)
    finally:
        with session_scope() as session:
            session.execute(delete(TaskOutbox).where(TaskOutbox.id.in_(task_ids)));session.commit()


def test_failed_claim_withdraws_shared_readiness(monkeypatch,tmp_path):
    from app.services import async_ai, callback_inbox
    published=[]
    class Heartbeat:
        def __init__(self,*args):pass
        async def start(self):pass
        async def publish(self,**state):published.append(state['ready'])
        async def withdraw(self,**kwargs):pass
    async def run():
        stop=asyncio.Event();loop=asyncio.get_running_loop()
        def unavailable(*args):
            loop.call_soon_threadsafe(stop.set)
            raise RuntimeError('synthetic database failure')
        monkeypatch.setattr(ai_capacity,'WorkerHeartbeat',Heartbeat)
        monkeypatch.setattr(async_ai,'claim_ready_tasks',unavailable)
        monkeypatch.setattr(callback_inbox,'prepare_handlers',lambda:None)
        monkeypatch.setattr(async_ai.settings,'ai_worker_health_path',str(tmp_path/'health.json'))
        await asyncio.wait_for(async_ai.run_async_ai_lane(stop,concurrency=4),3)
        assert published==[False]
        assert not (tmp_path/'health.json').exists()
    asyncio.run(run())


def test_real_redis_generation_fencing_withdrawal_and_ttl():
    url=os.environ.get('SINGLE500_REDIS_TEST_URL')
    if not url:pytest.skip('requires an isolated Redis test server')
    cfg=configuration(redis_url=url,ai_worker_id='ai-worker-1',ai_worker_health_prefix='single500-test:'+uuid4().hex)
    async def run():
        heartbeats=[]
        try:
            for worker in range(1,5):
                h=ai_capacity.WorkerHeartbeat(cfg.model_copy(update=dict(ai_worker_id=f'ai-worker-{worker}')),160)
                heartbeats.append(h)
                await h.start();await h.publish(inflight=0,ready=True)
            assert await asyncio.to_thread(ai_capacity.workers_ready,cfg)
            duplicate=ai_capacity.WorkerHeartbeat(cfg,160)
            try:
                with pytest.raises(RuntimeError,match='already owned'):await duplicate.start()
            finally:await duplicate.withdraw(close=True)
            old=heartbeats[0]
            await old.withdraw(close=True)
            new=ai_capacity.WorkerHeartbeat(cfg,160);heartbeats[0]=new
            await new.start();await new.publish(inflight=1,ready=True)
            # A delayed old generation cannot remove replacement state.
            old.client=new.client
            await old.withdraw()
            assert await asyncio.to_thread(ai_capacity.workers_ready,cfg)
            with pytest.raises(RuntimeError,match='ownership was lost'):
                await old.publish(inflight=0,ready=True)
            await new.withdraw()
            assert not await asyncio.to_thread(ai_capacity.workers_ready,cfg)
            await new.publish(inflight=0,ready=True)
            # Redis TTL is authoritative even if the JSON timestamp is recent.
            owner,state=ai_capacity.worker_keys(cfg,'ai-worker-1')
            await new.client.pexpire(state,1)
            await asyncio.sleep(.01)
            assert not await asyncio.to_thread(ai_capacity.workers_ready,cfg)
        finally:
            for heartbeat in heartbeats:await heartbeat.withdraw(close=True)
    asyncio.run(run())
