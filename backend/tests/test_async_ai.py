import asyncio
import threading
import time
from unittest.mock import patch

import pytest
from test_production_hardening import client, reset_runtime_settings_after_test
from test_review_fixes import make_call as _make_call
from app.services.async_ai import WorkPool
from app.services import dispatcher
from app.db import engine, session_scope
from app.models import CallSession, CallStatus, TaskOutbox
from app.schemas import AiTurnResult
from sqlalchemy import delete


@pytest.fixture(autouse=True)
def cleanup_ai_test_tasks(client, monkeypatch):
    """A later scheduler test must not consume this test's durable side effects."""
    call_ids = []
    def tracked_call(**kwargs):
        call_id = _make_call(**kwargs)
        call_ids.append(call_id)
        return call_id
    monkeypatch.setattr(__name__ + '.make_call', tracked_call)
    yield
    if call_ids:
        with session_scope() as session:
            for call_id in call_ids:
                call=session.get(CallSession,call_id)
                if call is not None:
                    call.status=CallStatus.COMPLETED
                    session.add(call)
            session.execute(delete(TaskOutbox).where(
                TaskOutbox.aggregate_id.in_([str(call_id) for call_id in call_ids])))
            session.commit()


make_call = _make_call


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


def test_fresh_ai_lookup_rejects_cached_call_and_realtime_state(client):
    from app.models import RealtimeSession
    cid=make_call()
    with session_scope() as s:
        s.add(RealtimeSession(tenant_id=1,call_session_id=cid,turn_sequence=1));s.commit()
    with session_scope() as stale:
        cached=stale.get(CallSession,cid)
        with session_scope() as changed:
            call=changed.get(CallSession,cid);call.status=CallStatus.COMPLETED;changed.add(call);changed.commit()
        assert cached.status!=CallStatus.COMPLETED
        assert dispatcher._load_current_ai_call(stale,cid,1,lock=True) is None
    cid=make_call()
    with session_scope() as s:
        rt=RealtimeSession(tenant_id=1,call_session_id=cid,turn_sequence=1);s.add(rt);s.commit();rid=rt.id
    token=dispatcher._expected_turn_sequence.set(1)
    try:
        with session_scope() as stale:
            cached=stale.get(RealtimeSession,rid)
            with session_scope() as changed:
                rt=changed.get(RealtimeSession,rid);rt.turn_sequence=2;changed.add(rt);changed.commit()
            assert cached.turn_sequence==1
            assert dispatcher._load_current_ai_call(stale,cid,1,lock=True) is None
    finally:dispatcher._expected_turn_sequence.reset(token)


def test_ai_lookup_rechecks_lease_after_database_read(client):
    from sqlalchemy import event
    from app.services.leases import _leases,ExecutionLease,LeaseLost
    cid=make_call();lease=ExecutionLease(float('inf'))
    def after_read(connection,cursor,statement,parameters,context,executemany):
        if statement.lstrip().upper().startswith('SELECT') and 'callsession' in statement:
            lease.lost=True
    token=_leases.set((lease,));event.listen(engine,'after_cursor_execute',after_read)
    try:
        with session_scope() as s:
            with pytest.raises(LeaseLost):dispatcher._load_current_ai_call(s,cid,1,lock=True)
    finally:
        event.remove(engine,'after_cursor_execute',after_read);_leases.reset(token)


def test_combined_speak_units_preserve_durable_replay(client,monkeypatch):
    import json
    from uuid import uuid4
    from sqlmodel import select
    from app.models import TaskState,SpeechTurn,CallEvent
    from app.services import ai_actions
    from app.services.ai_claim_state import current_claim
    from app.services.telephony import MockAdapter
    cid=make_call();tid=uuid4();spoken=[];models=[]
    with session_scope() as s:
        s.add(TaskOutbox(id=tid,tenant_id=1,task_type='ai_turn',aggregate_id=str(cid),
            idempotency_key='combined:'+str(tid),state=TaskState.PROCESSING,lease_token='owner',
            payload_json=json.dumps(dict(call_id=str(cid),attempt=1))))
        s.commit()
    class Adapter(MockAdapter):
        async def speak(self,**kwargs):
            spoken.append(kwargs['text']);return dict(playback_complete=True)
    async def model(**kwargs):
        models.append(True);return AiTurnResult(action='continue',tts_text='组合工作单元回复')
    monkeypatch.setattr(dispatcher,'request_ai_turn',model)
    monkeypatch.setattr(ai_actions,'get_telephony_adapter',lambda **kwargs:Adapter())
    async def run():
        pool=WorkPool(1);token=current_claim.set((tid,'owner'))
        try:
            await dispatcher.run_ai_turn_async(pool=pool,call_id=cid,expected_attempt=1)
            await dispatcher.run_ai_turn_async(pool=pool,call_id=cid,expected_attempt=1)
        finally:current_claim.reset(token);await pool.close()
    asyncio.run(run())
    assert models==[True] and spoken==['组合工作单元回复']
    with session_scope() as s:
        assert json.loads(s.get(TaskOutbox,tid).payload_json)['action_committed'] is True
        assert len(s.exec(select(SpeechTurn).where(SpeechTurn.call_session_id==cid,SpeechTurn.speaker_role=='ai')).all())==1
        assert len(s.exec(select(CallEvent).where(CallEvent.call_session_id==cid,CallEvent.event_type=='ai_decision')).all())==1


@pytest.mark.parametrize('loss', ['cancel', 'lease'])
def test_combined_speech_stops_between_commits_after_execution_loss(client,monkeypatch,loss):
    from sqlmodel import select
    from app.models import SpeechTurn,CallEvent
    from app.services import ai_actions
    from app.services.leases import ExecutionLease,LeaseLost,_leases
    cid=make_call();recorded=threading.Event();release=threading.Event()
    original=ai_actions.record_speech;lease=ExecutionLease(float('inf'))
    def record_then_lose(*args,**kwargs):
        result=original(*args,**kwargs)
        recorded.set()
        if loss=='lease':lease.lost=True
        else:release.wait(3)
        return result
    monkeypatch.setattr(ai_actions,'record_speech',record_then_lose)
    async def run():
        pool=WorkPool(1)
        snapshot=await pool.run(ai_actions.prepare,cid,1)
        token=_leases.set((lease,))
        job=asyncio.create_task(pool.run(ai_actions._record_and_finish_speech,snapshot,
            AiTurnResult(action='continue',tts_text='事务间中止验证'),dict(playback_complete=True),1,False))
        try:
            if loss=='cancel':
                while not recorded.is_set():await asyncio.sleep(.01)
                job.cancel();await asyncio.sleep(.02)
                assert not job.done()
                release.set()
                with pytest.raises(asyncio.CancelledError):await job
            else:
                with pytest.raises(LeaseLost):await job
        finally:
            release.set();_leases.reset(token);await asyncio.gather(job,return_exceptions=True);await pool.close()
    asyncio.run(run())
    with session_scope() as s:
        assert len(s.exec(select(SpeechTurn).where(SpeechTurn.call_session_id==cid,SpeechTurn.speaker_role=='ai')).all())==1
        assert not s.exec(select(CallEvent).where(CallEvent.call_session_id==cid,CallEvent.event_type=='ai_decision')).all()
        assert s.get(CallSession,cid).status==CallStatus.IN_AI


def test_prepared_http_unknown_outcome_reuses_command_without_model_or_decision(client,monkeypatch):
    import httpx,json
    from uuid import uuid4
    from sqlmodel import select
    from app.models import TaskState,SpeechTurn,CallEvent
    from app.services import ai_actions
    from app.services.ai_claim_state import current_claim
    from app.services.leases import LeaseLost
    from app.services.telephony import HttpAdapter
    cid=make_call();tid=uuid4();commands=[];models=[]
    with session_scope() as s:
        s.add(TaskOutbox(id=tid,tenant_id=1,task_type='ai_turn',aggregate_id=str(cid),
            idempotency_key='unknown:'+str(tid),state=TaskState.PROCESSING,lease_token='owner',
            payload_json=json.dumps(dict(call_id=str(cid),attempt=1))))
        s.commit()
    class Adapter(HttpAdapter):
        async def speak(self,**kwargs):
            commands.append(kwargs['command_id'])
            if len(commands)==1:raise httpx.ReadTimeout('playback response was lost')
            response=httpx.Response(409,headers={'X-Voice-Outcome':'unknown'},
                request=httpx.Request('POST','http://127.0.0.1/playback'))
            raise httpx.HTTPStatusError('gateway retains unknown command',request=response.request,response=response)
    async def model(**kwargs):
        models.append(True);return AiTurnResult(action='continue',tts_text='保留未知播放结果')
    adapter=Adapter('http://127.0.0.1')
    monkeypatch.setattr(dispatcher,'request_ai_turn',model)
    monkeypatch.setattr(ai_actions,'get_telephony_adapter',lambda **kwargs:adapter)
    async def run():
        pool=WorkPool(1);token=current_claim.set((tid,'owner'))
        try:
            for _ in range(2):
                with pytest.raises(LeaseLost,match='outcome unknown'):
                    await dispatcher.run_ai_turn_async(pool=pool,call_id=cid,expected_attempt=1)
        finally:current_claim.reset(token);await pool.close()
    asyncio.run(run())
    assert models==[True] and len(commands)==2 and commands[0]==commands[1]
    with session_scope() as s:
        assert json.loads(s.get(TaskOutbox,tid).payload_json)['action_committed'] is False
        assert not s.exec(select(SpeechTurn).where(SpeechTurn.call_session_id==cid,SpeechTurn.speaker_role=='ai')).all()
        assert not s.exec(select(CallEvent).where(CallEvent.call_session_id==cid,CallEvent.event_type=='ai_decision')).all()
