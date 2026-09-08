"""Synthetic control/disk correctness; never a real audio capacity claim."""
import asyncio
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest
from fastapi import HTTPException

from app.durable_batch import DurableBatch
from app.media_cluster import RemoteMediaManager
from app.security import Ledger, routes
from test_media_cluster import settings_for
from test_security import configuration, request


def test_501st_intent_rejected_unknown_retained_and_retry_idempotent(tmp_path):
    cfg = configuration(tmp_path, voice_max_concurrent=500, voice_cps=1000,
                        voice_daily_call_limit=100000, voice_hour_budget_minor=10000000,
                        voice_day_budget_minor=10000000)
    policy = routes(cfg)['1:0'].model_copy(update=dict(max_concurrent=500, cps=1000,
        calls_per_day=100000, hour_budget_minor=10000000, day_budget_minor=10000000))
    ledger = Ledger(cfg.voice_security_db_path)
    def admit(i):
        try:
            return ledger.admit(request(str(i)), policy, cfg)
        except HTTPException as exc:
            assert exc.status_code == 429 and exc.headers['X-Voice-Dial-Admitted'] == 'false'
            return None
    with ThreadPoolExecutor(max_workers=16) as pool:
        results = list(pool.map(admit, range(516)))
    assert sum(r is not None for r in results) == 500
    # Timeout alone must not release the pending PBX intent.
    with ledger.transaction() as db:
        db.execute("UPDATE attempts SET deadline=0")
    assert admit(1000) is None
    first = next(i for i, r in enumerate(results) if r)
    assert admit(first)[1] is False
    ledger.finish(results[first][0]['uuid'])
    assert admit(1000)[1] is True
    assert ledger.summary()['active_attempts'] == 500


def test_gateway_rechecks_media_before_persisting_a_dial_intent(tmp_path):
    from types import SimpleNamespace
    from test_security import gateway
    async def run():
        secured, fake = gateway(tmp_path)
        payload=request('existing')
        await secured.post('dial', payload)
        before=list(fake.api_commands)
        secured.driver.pipecat_manager=SimpleNamespace(ready=lambda:False, admission_capacity=lambda:0)
        # A retry must retain the first result, even while degraded.
        assert (await secured.post('dial',payload))['provider_call_id']
        with pytest.raises(HTTPException) as caught:
            await secured.post('dial',request('new'))
        assert caught.value.status_code==429
        assert caught.value.headers['X-Voice-Dial-Admitted']=='false'
        assert secured.ledger.lookup('new',1) is None and fake.api_commands==before
    asyncio.run(run())


def test_degraded_media_stale_health_and_unknown_worker_sessions(tmp_path):
    async def run():
        cfg = settings_for(tmp_path)
        specs = [dict(id=f'media-{i}', endpoint=f'http://127.0.0.1:{8100+i}',
                      ws_base=f'ws://127.0.0.1:{8100+i}/v1/pipecat/media', capacity=50) for i in range(1, 13)]
        cfg = cfg.model_copy(update=dict(media_workers_json=json.dumps(specs),
            pipecat_max_active_sessions=600, media_allow_degraded_admission=True))
        manager = RemoteMediaManager(cfg)
        manager.store.initialize()
        states = {s['id']:dict(worker_id=s['id'], epoch='e1', ready=True, capacity=50, sessions={}) for s in specs}
        async def transport(req):
            state = states[f'media-{req.url.port-8100}']
            if req.method == 'GET':
                return httpx.Response(200, json=state)
            payload = json.loads(req.content)
            state['sessions'][payload['call_id']] = {'session_id':payload['session_id']}
            return httpx.Response(200, json={})
        async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
            manager.client = client
            await manager.refresh()
            create = lambda i: manager.create_session(call_id=str(i), speech_webhook_url='',
                                                       media_webhook_url='', metadata={'attempt':1})
            await create(0)
            owner = manager.owners['0']
            states[owner.spec['id']]['ready'] = False
            await manager.refresh()
            assert manager.ready() and manager.admission_capacity() == 550
            assert owner.session.terminated.is_set() and '0' in manager.owners
            await create(1)
            assert manager.owners['1'].spec['id'] != owner.spec['id']
            # A worker reports orphaned sessions; never count it as empty.
            for state in states.values():
                state['sessions'] = {str(i):{'session_id':str(i)} for i in range(50)}
            await manager.refresh()
            with pytest.raises(RuntimeError, match='no media process'):
                await create(1000)
            manager.health_checked_at = {s['id']:time.monotonic()-10 for s in specs}
            assert not manager.ready() and manager.admission_capacity() == 0
            with pytest.raises(RuntimeError, match='no media process'):
                await create(1001)
            assert len(manager.store.initialize()) == 2
    asyncio.run(run())


def test_group_commit_waits_for_disk_and_cancelled_caller_is_durable():
    async def run():
        entered, release = threading.Event(), threading.Event()
        committed = []
        def commit(items):
            entered.set()
            assert release.wait(5)
            committed.extend(items)
        writer = DurableBatch(commit)
        tasks = [asyncio.create_task(writer.submit(i)) for i in range(32)]
        assert await asyncio.to_thread(entered.wait, 2)
        assert not any(t.done() for t in tasks)
        tasks[0].cancel()
        closing = asyncio.create_task(writer.close())
        await asyncio.sleep(.01)
        assert not closing.done()
        release.set()
        await asyncio.gather(*tasks, return_exceptions=True)
        await closing
        assert committed == list(range(32))
        assert writer.batches == 1
    asyncio.run(run())


def test_group_commit_failure_and_backpressure_have_no_false_ack():
    async def run():
        def fail(_):
            raise OSError('synthetic full disk')
        writer = DurableBatch(fail, capacity=2, delay=.02)
        results = await asyncio.gather(*(writer.submit(i) for i in range(3)), return_exceptions=True)
        assert sum(isinstance(r, OSError) for r in results) == 2
        assert sum(isinstance(r, RuntimeError) for r in results) == 1
        assert writer.operations == 0
        await writer.close()
    asyncio.run(run())


def test_callback_batch_restart_preserves_all_acked_events(tmp_path):
    from app.security import CallbackSender
    async def run():
        cfg = configuration(tmp_path)
        sender = CallbackSender(cfg)
        await asyncio.gather(*(sender.post(request()['webhook_url'], {'call_id':str(i), 'seq':1}) for i in range(500)))
        await sender.stop()
        restarted = Ledger(cfg.voice_security_db_path)
        assert restarted.summary()['pending_callbacks'] == 500
        assert sender.writer.batches < 20
    asyncio.run(run())
