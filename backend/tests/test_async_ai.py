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
        async def finish(snapshot,result):finished.append(snapshot['call_id'])
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
