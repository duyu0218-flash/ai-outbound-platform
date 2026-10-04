import asyncio
from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.schemas import AiTurnResult
from app.services import ai_actions, dispatcher


@pytest.mark.parametrize('current,failed', [(True, False), (False, False), (True, True)])
def test_model_finishing_during_liveness_never_sends_wait_notice(monkeypatch, current, failed):
    async def run():
        release = asyncio.Event()
        model_task = None
        notices = []
        expected = AiTurnResult(action='speak', tts_text='正式回复')

        async def model(**kwargs):
            nonlocal model_task
            model_task = asyncio.current_task()
            await release.wait()
            if failed:
                raise RuntimeError('model failed')
            return expected

        class DelayedLiveness:
            async def current(self, snapshot, sequence):
                release.set()
                await asyncio.wait({model_task})
                return current

        async def notice(*args, **kwargs):
            notices.append(kwargs)

        monkeypatch.setattr(dispatcher, 'request_ai_turn', model)
        monkeypatch.setattr(ai_actions, 'execute_action', notice)
        monkeypatch.setattr(ai_actions, 'execute_prepared_action', notice)
        snapshot = dict(call_id=uuid4(), attempt=1, ai_request={},
                        model_wait_seconds=0, model_wait_prompt='请稍候')
        pool = SimpleNamespace(liveness=DelayedLiveness())
        if failed:
            with pytest.raises(RuntimeError, match='model failed'):
                await dispatcher._wait_for_ai(snapshot, pool=pool)
        else:
            result = await dispatcher._wait_for_ai(snapshot, pool=pool)
            assert result == (expected if current else None)
        assert not notices
        assert model_task.done()
    asyncio.run(run())


@pytest.mark.parametrize('failed', [False, True])
def test_model_finishing_during_notice_preparation_never_dispatches_notice(monkeypatch,failed):
    async def run():
        release=asyncio.Event();model_task=None;notices=[]
        expected=AiTurnResult(action='speak',tts_text='正式回复')
        async def model(**kwargs):
            nonlocal model_task
            model_task=asyncio.current_task();await release.wait()
            if failed:raise RuntimeError('model failed during preparation')
            return expected
        async def current(*args):return True
        class DelayedPreparation:
            liveness=SimpleNamespace(current=current)
            async def run(self,function,*args):
                release.set();await asyncio.wait({model_task})
                return dict(call_id=args[0],attempt=args[1])
        async def notice(*args,**kwargs):notices.append(True)
        monkeypatch.setattr(dispatcher,'request_ai_turn',model)
        monkeypatch.setattr(ai_actions,'execute_prepared_action',notice)
        snapshot=dict(call_id=uuid4(),attempt=1,ai_request={},model_wait_seconds=0,model_wait_prompt='请稍候')
        if failed:
            with pytest.raises(RuntimeError,match='during preparation'):
                await dispatcher._wait_for_ai(snapshot,pool=DelayedPreparation())
        else:
            assert await dispatcher._wait_for_ai(snapshot,pool=DelayedPreparation())==expected
        assert not notices and model_task.done()
    asyncio.run(run())
