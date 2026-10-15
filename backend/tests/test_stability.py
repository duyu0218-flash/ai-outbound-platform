import asyncio
import json
from types import SimpleNamespace

import pytest
import redis

from test_production_hardening import client, _review_call
from app.services.work_scheduling import WorkGate, WorkQueueFull, Histogram
from app.services import stability


def test_priority_aging_bound_and_cancelled_grant(monkeypatch):
    async def run():
        from app.services import work_scheduling
        now = [0.]
        monkeypatch.setattr(work_scheduling, 'time', SimpleNamespace(monotonic=lambda: now[0]))
        gate = WorkGate(1, limit=2)
        await gate.acquire(2)
        order = []
        async def wait(name, priority):
            await gate.acquire(priority)
            order.append(name)
            gate.release()
        low = asyncio.create_task(wait('low', 3))
        await asyncio.sleep(0)
        urgent = asyncio.create_task(wait('urgent', 0))
        await asyncio.sleep(0)
        with pytest.raises(WorkQueueFull):
            await gate.acquire(2)
        gate.release()
        await asyncio.gather(low, urgent)
        assert order == ['urgent', 'low']
        await gate.acquire(2)
        low = asyncio.create_task(wait('aged', 3))
        await asyncio.sleep(0)
        now[0] = 1
        urgent = asyncio.create_task(wait('new', 0))
        await asyncio.sleep(0)
        gate.release()
        await asyncio.gather(low, urgent)
        assert order[-2:] == ['aged', 'new']
        await gate.acquire(2)
        cancelled = asyncio.create_task(gate.acquire(1))
        await asyncio.sleep(0)
        gate.release()  # grant before the waiter resumes
        cancelled.cancel()
        await asyncio.gather(cancelled, return_exceptions=True)
        assert gate.active == 0 and not gate.waiters
        await gate.acquire(2)
        waiting = asyncio.create_task(gate.acquire(2))
        await asyncio.sleep(0)
        waiting.cancel()
        await asyncio.gather(waiting, return_exceptions=True)
        gate.release()
        assert gate.active == 0 and not gate.waiters
    asyncio.run(run())


def test_work_pool_cancellation_drains_thread_before_releasing():
    import threading
    from app.services.async_ai import WorkPool
    async def run():
        pool = WorkPool(1)
        started, release = threading.Event(), threading.Event()
        def blocking():
            started.set()
            release.wait(2)
        job = asyncio.create_task(pool.run(blocking))
        await asyncio.to_thread(started.wait, 2)
        job.cancel()
        await asyncio.sleep(.02)
        assert not job.done() and pool.gate.active == 1
        release.set()
        await asyncio.gather(job, return_exceptions=True)
        assert pool.gate.active == 0
        await pool.close()
    asyncio.run(run())


def test_histogram_retains_all_counts():
    histogram = Histogram()
    for value in range(10000):
        histogram.observe(value)
    snapshot = histogram.snapshot()
    assert snapshot['count'] == snapshot['buckets']['+Inf'] == 10000
    assert snapshot['sum_ms'] == sum(range(10000))
    assert snapshot['buckets']['100'] == 101


def test_completed_model_does_not_wait_for_queued_liveness_hint(monkeypatch):
    from app.services import dispatcher
    from app.schemas import AiTurnResult
    async def run():
        hint_started = asyncio.Event()
        model_complete = asyncio.Event()
        hint_cancelled = asyncio.Event()
        async def model(**kwargs):
            await model_complete.wait()
            return AiTurnResult(action='continue')
        async def hint(*args):
            hint_started.set()
            try:
                await asyncio.Event().wait()
            finally:
                hint_cancelled.set()
        monkeypatch.setattr(dispatcher, 'request_ai_turn', model)
        pool=SimpleNamespace(liveness=SimpleNamespace(current=hint))
        task=asyncio.create_task(dispatcher._wait_for_ai(dict(ai_request={}), pool=pool))
        await asyncio.wait_for(hint_started.wait(), 3)
        model_complete.set()
        result=await asyncio.wait_for(task, .5)
        assert result.action == 'continue' and hint_cancelled.is_set()
    asyncio.run(run())


def test_control_requires_healthy_window_and_recovers_in_steps():
    def step(state, now, **changes):
        return stability.advance(state, now=now, **dict(queue_ms=0, age_sec=0, ready=True, **changes))
    state = step(None, 0)
    assert state['mode'] == 'PAUSED'
    state = step(state, 59)
    assert state['fraction'] == 0
    state = step(state, 60)
    assert state['mode'] == 'RECOVERING' and state['fraction'] == .5
    state = step(state, 120)
    assert state['fraction'] == .6
    state = stability.advance(state, now=121, queue_ms=0, age_sec=.7, ready=True)
    assert state['mode'] == 'PAUSED' and state['fraction'] == 0
    state = step(state, 122)
    assert step(state, 181)['fraction'] == 0
    assert step(state, 182)['fraction'] == .5


def test_soft_pressure_is_debounced_and_hysteresis_blocks_recovery():
    state = dict(mode='NORMAL', fraction=1., changed_at=0)
    state = stability.advance(state, now=1, queue_ms=120, age_sec=0, ready=True)
    assert state['fraction'] == 1
    state = stability.advance(state, now=11, queue_ms=120, age_sec=0, ready=True)
    assert state['fraction'] == .8
    state = stability.advance(state, now=100, queue_ms=70, age_sec=.2, ready=True)
    assert state['fraction'] == .8
    state = stability.advance(state, now=101, queue_ms=0, age_sec=0, ready=False)
    assert state['fraction'] == 0


def test_shared_gate_fails_closed_on_missing_stale_or_unavailable_telemetry(monkeypatch):
    from app.config import get_settings
    from app.services import gateway_cluster
    settings = get_settings()
    monkeypatch.setattr(settings, 'stability_admission_enabled', True)
    monkeypatch.setattr(settings, 'stability_min_ai_workers', 1)
    monkeypatch.setattr(gateway_cluster, 'node_specs', lambda: [SimpleNamespace(id='one', enabled=True, cps=5)])
    clock = [100.]
    monkeypatch.setattr(stability, 'time', SimpleNamespace(time=lambda: clock[0]))
    class Store:
        control = None
        rows = {}
        def hgetall(self, key): return self.rows
        def hdel(self, key, *names):
            self.rows = {k:v for k,v in self.rows.items() if k not in names}
        def get(self, key): return self.control
        def set(self, key, value, **kw): self.control = value
    store = Store()
    monkeypatch.setattr(stability, 'client', lambda url: store)
    session = SimpleNamespace(info={})
    inbox = dict(ready=True, oldest_age_sec=0)
    assert not stability.check(session, inbox)
    def signals():
        store.rows = {'ai:a':json.dumps(dict(at=clock[0], queue_p95_ms=0, oldest_wait_ms=0)),
                      'gateway:one':json.dumps(dict(at=clock[0], ready=True, age_sec=0))}
    signals()
    assert not stability.check(session, inbox)
    clock[0] = 161
    signals()
    assert stability.check(session, inbox) and session.info['stability_fraction'] == .5
    clock[0] = 180
    assert not stability.check(session, inbox)
    def fail(url): raise redis.ConnectionError('offline')
    monkeypatch.setattr(stability, 'client', fail)
    assert not stability.check(session, inbox)


def test_new_dial_cannot_bypass_stability_gate(client, monkeypatch):
    from app.services import call_service, callback_inbox
    from app.db import session_scope
    from app.models import CallSession, CallStatus
    cid = _review_call(CallStatus.QUEUED)
    monkeypatch.setattr(call_service.settings, 'stability_admission_enabled', True)
    monkeypatch.setattr(callback_inbox, 'ready', lambda session: True)
    monkeypatch.setattr(callback_inbox, 'snapshot', lambda session: dict(ready=True, oldest_age_sec=0))
    monkeypatch.setattr(stability, 'check', lambda session, inbox: False)
    with session_scope() as session:
        call = session.get(CallSession, cid)
        attempts = call.attempts
        assert not call_service._claim_dispatch_slot(session, call)
        session.refresh(call)
        assert call.status == CallStatus.QUEUED and call.attempts == attempts


def test_shared_control_roundtrip_with_isolated_redis_namespace(monkeypatch):
    import os
    import uuid
    url = os.environ.get('REDIS_URL', '')
    if not url:
        pytest.skip('requires isolated Redis test service')
    key = 'stability-regression:' + uuid.uuid4().hex
    settings = SimpleNamespace(stability_admission_enabled=True, redis_url=url,
        stability_redis_key=key, stability_signal_ttl_sec=15, stability_min_ai_workers=4)
    monkeypatch.setattr(stability, 'get_settings', lambda: settings)
    from app.services import gateway_cluster
    monkeypatch.setattr(gateway_cluster, 'node_specs', lambda: [SimpleNamespace(id='test', enabled=True, cps=10)])
    now = [1000.]
    monkeypatch.setattr(stability, 'time', SimpleNamespace(time=lambda: now[0]))
    session = SimpleNamespace(info={})
    r = stability.client(url)
    try:
        assert not stability.check(session, dict(ready=True, oldest_age_sec=0))
        def signals():
            for i in range(4):
                stability.publish('ai', str(i), dict(queue_p95_ms=0, oldest_wait_ms=0))
            stability.publish('gateway', 'test', dict(ready=True, age_sec=0))
        signals()
        assert not stability.check(session, dict(ready=True, oldest_age_sec=0))
        now[0] += 61
        signals()
        assert stability.check(session, dict(ready=True, oldest_age_sec=0))
        assert session.info['stability_fraction'] == .5
        assert stability.status()['mode'] == 'RECOVERING'
        assert not stability.check(session, dict(ready=True, oldest_age_sec=.7))
        assert json.loads(r.get(key+':control'))['mode'] == 'PAUSED'
        now[0] += 16
        assert not stability.check(session, dict(ready=True, oldest_age_sec=0))
        assert session.info['stability_fraction'] == 0
    finally:
        r.delete(key+':signals', key+':control')
