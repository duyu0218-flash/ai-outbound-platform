import asyncio
import threading
import time
from unittest.mock import patch

import pytest
from test_production_hardening import client, reset_runtime_settings_after_test
from test_review_fixes import make_call
from app.services.async_ai import WorkPool
from app.services import dispatcher
from app.db import engine, session_scope
from app.models import CallSession, CallStatus
from app.schemas import AiTurnResult


def test_action_pool_prepares_every_thread_without_network_and_closes_clients(client):
    async def run():
        pool = WorkPool(2, 'prepared-actions')
        clients = []
        try:
            await pool.prepare_http(timeout=8, follow_redirects=False, trust_env=False)
            assert len(pool.runtimes) == 2
            for runtime in pool.runtimes:
                assert len(runtime.resources) == 1
                clients.extend(runtime.resources.values())
            assert all(not client.is_closed for client in clients)
        finally:
            await pool.close()
        assert all(client.is_closed for client in clients)
    asyncio.run(run())


def test_async_ai_wait_does_not_hold_db_or_threads(client, monkeypatch):
    async def run():
        pool=WorkPool(2)
        actions=WorkPool(2,'ai-action-test')
        ids=[make_call() for _ in range(20)]
        active=0;peak=0;finished=[];gate=asyncio.Event();all_started=asyncio.Event()
        async def model(**kw):
            nonlocal active,peak
            active+=1;peak=max(peak,active)
            if active==len(ids):all_started.set()
            await gate.wait();active-=1
            return AiTurnResult(action='continue',tts_text='test')
        async def finish(snapshot,result,prepare_only=False):finished.append(snapshot['call_id'])
        monkeypatch.setattr(dispatcher,'request_ai_turn',model)
        monkeypatch.setattr(dispatcher,'_finish_ai_turn',finish)
        jobs=[asyncio.create_task(dispatcher.run_ai_turn_async(pool=pool,action_pool=actions,
              call_id=cid,expected_attempt=1)) for cid in ids]
        try:
            await asyncio.wait_for(all_started.wait(),5)
            assert peak==20 and engine.pool.checkedout()==0
            assert len(pool.runtimes)<=2 and not actions.runtimes
        finally:
            gate.set();await asyncio.gather(*jobs);await actions.close();await pool.close()
            with session_scope() as session:
                for cid in ids:
                    call=session.get(CallSession,cid);call.status=CallStatus.COMPLETED;session.add(call)
                session.commit()
        assert set(finished)==set(ids)
    asyncio.run(run())


def test_async_ai_cancels_model_after_hangup(client,monkeypatch):
    async def run():
        pool=WorkPool(2);cid=make_call();started=asyncio.Event();cancelled=asyncio.Event()
        async def model(**kw):
            started.set()
            try:await asyncio.Event().wait()
            finally:cancelled.set()
        monkeypatch.setattr(dispatcher,'request_ai_turn',model)
        task=asyncio.create_task(dispatcher.run_ai_turn_async(pool=pool,call_id=cid,expected_attempt=1))
        try:
            await asyncio.wait_for(started.wait(),3)
            with session_scope() as s:
                c=s.get(CallSession,cid);c.status=CallStatus.COMPLETED;s.add(c);s.commit()
            await asyncio.wait_for(task,3)
            assert cancelled.is_set()
        finally:
            if not task.done():task.cancel();await asyncio.gather(task,return_exceptions=True)
            await pool.close()
    asyncio.run(run())


def test_cancelled_work_unit_drains_and_cannot_apply_side_effect():
    from app.services.leases import assert_execution_permitted,LeaseLost
    async def run():
        pool=WorkPool(1);started=threading.Event();release=threading.Event();side_effect=[]
        def work():
            started.set();release.wait(2)
            assert_execution_permitted();side_effect.append(True)
        task=asyncio.create_task(pool.run(work))
        while not started.is_set():await asyncio.sleep(.01)
        task.cancel();await asyncio.sleep(.03)
        assert not task.done()
        release.set()
        with pytest.raises(asyncio.CancelledError):await task
        await pool.close()
        assert not side_effect
    asyncio.run(run())


def test_dedicated_ai_role_cannot_be_reenabled_through_aliases():
    from app.config import Settings
    background=Settings(_env_file=None,task_worker_role='background',task_queue_lanes='ai_turn:128')
    assert 'ai_turn' not in background.resolved_task_queue_lanes()
    background.task_queue_lane_aliases='ai_turn:recording'
    with pytest.raises(ValueError):background.resolved_task_queue_aliases()
    assert Settings(_env_file=None,task_worker_role='ai',task_ai_concurrency=128).resolved_task_queue_lanes()=={'ai_turn':128}


def test_network_actions_leave_db_pool_available(client, monkeypatch):
    from app.services import ai_actions
    from app.services.telephony import MockAdapter
    async def run():
        pool = WorkPool(2)
        ids = [make_call() for _ in range(12)]
        started = 0
        all_started = asyncio.Event()
        release = asyncio.Event()
        async def speak(self, **kwargs):
            nonlocal started
            started += 1
            if started == len(ids):
                all_started.set()
            await release.wait()
            return {'playback_complete': True}
        monkeypatch.setattr(MockAdapter, 'speak', speak)
        monkeypatch.setattr(ai_actions, 'get_telephony_adapter', lambda **kwargs: MockAdapter())
        jobs = [asyncio.create_task(ai_actions.execute_action(pool, cid, 1,
            AiTurnResult(action='continue', tts_text='异步动作验证'))) for cid in ids]
        try:
            await asyncio.wait_for(all_started.wait(), 5)
            assert engine.pool.checkedout() == 0
            assert await asyncio.wait_for(pool.run(lambda: True), .5)
        finally:
            release.set()
            await asyncio.gather(*jobs)
            await pool.close()
            with session_scope() as session:
                for cid in ids:
                    call = session.get(CallSession, cid)
                    call.status = CallStatus.COMPLETED
                    session.add(call)
                session.commit()
    asyncio.run(run())


def test_prepared_action_survives_retry_without_model_or_duplicate_decision(client, monkeypatch):
    import json
    from uuid import uuid4
    from app.models import TaskOutbox, TaskState, CallEvent
    from app.services import ai_actions
    from app.services.ai_claim_state import current_claim, save_action
    from app.services.telephony import MockAdapter
    from sqlmodel import select
    cid = make_call()
    tid = uuid4()
    with session_scope() as session:
        session.add(TaskOutbox(id=tid, tenant_id=1, task_type='ai_turn', aggregate_id=str(cid),
            idempotency_key='prepared-test:'+str(tid), state=TaskState.PROCESSING,
            lease_token='test-owner', payload_json=json.dumps({'call_id':str(cid),'attempt':1})))
        session.commit()
    speech = []
    class Adapter(MockAdapter):
        async def speak(self, **kw):
            speech.append(kw['text'])
            return {'playback_complete':True}
    monkeypatch.setattr(ai_actions, 'get_telephony_adapter', lambda **_: Adapter())
    async def forbidden(**kw):
        pytest.fail('prepared action must not regenerate the model response')
    monkeypatch.setattr(dispatcher, 'request_ai_turn', forbidden)
    async def run():
        pool = WorkPool(2)
        token = current_claim.set((tid,'test-owner'))
        try:
            with session_scope() as session:
                save_action(session, AiTurnResult(action='continue',tts_text='已持久化的回答'))
                session.commit()
            await dispatcher.run_ai_turn_async(pool=pool,call_id=cid,expected_attempt=1)
            await dispatcher.run_ai_turn_async(pool=pool,call_id=cid,expected_attempt=1)
        finally:
            current_claim.reset(token)
            await pool.close()
    try:
        asyncio.run(run())
        assert speech == ['已持久化的回答']
        with session_scope() as session:
            assert len(session.exec(select(CallEvent).where(CallEvent.call_session_id==cid,
                CallEvent.event_type=='ai_decision')).all()) == 1
    finally:
        with session_scope() as session:
            call=session.get(CallSession,cid);call.status=CallStatus.COMPLETED;session.add(call)
            task=session.get(TaskOutbox,tid);task.state=TaskState.COMPLETED;session.add(task);session.commit()


def test_liveness_queries_are_batched_and_detect_ended_calls(client, monkeypatch):
    from app.services import ai_liveness
    cid=make_call()
    batches=[]
    original=ai_liveness.read_current
    def read(checks):
        batches.append(len(checks))
        return original(checks)
    monkeypatch.setattr(ai_liveness,'read_current',read)
    async def run():
        pool=WorkPool(2);batcher=ai_liveness.LivenessBatcher(pool)
        try:
            values=await asyncio.gather(*(batcher.current({'call_id':cid,'attempt':1},None) for _ in range(30)))
            assert all(values) and batches == [30]
            with session_scope() as session:
                call=session.get(CallSession,cid);call.status=CallStatus.COMPLETED;session.add(call);session.commit()
            assert not await batcher.current({'call_id':cid,'attempt':1},None)
        finally:
            await pool.close()
    asyncio.run(run())


def test_model_failure_fallback_does_not_block_db_or_overwrite_hangup(client, monkeypatch):
    from app.services import ai_actions
    from app.services.telephony import MockAdapter
    async def run():
        pool=WorkPool(1);cid=make_call();started=asyncio.Event();release=asyncio.Event()
        async def fail(**kw):raise RuntimeError('synthetic model outage')
        class Adapter(MockAdapter):
            async def speak(self,**kw):
                started.set();await release.wait()
                return {'playback_complete':True}
            async def hangup(self,**kw):pytest.fail('late fallback must not touch a completed call')
        monkeypatch.setattr(dispatcher,'request_ai_turn',fail)
        monkeypatch.setattr(ai_actions,'get_telephony_adapter',lambda **_:Adapter())
        task=asyncio.create_task(dispatcher.run_ai_turn_async(pool=pool,call_id=cid,expected_attempt=1))
        try:
            await asyncio.wait_for(started.wait(),3)
            assert engine.pool.checkedout()==0
            assert await asyncio.wait_for(pool.run(lambda:True),.5)
            with session_scope() as session:
                call=session.get(CallSession,cid);call.status=CallStatus.COMPLETED;session.add(call);session.commit()
        finally:
            release.set()
            result=await asyncio.gather(task,return_exceptions=True)
            await pool.close()
        assert isinstance(result[0],RuntimeError)
        with session_scope() as session:assert session.get(CallSession,cid).status==CallStatus.COMPLETED
    asyncio.run(run())
