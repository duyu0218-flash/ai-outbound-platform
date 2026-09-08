import asyncio
import time
import httpx
from app.security import CallbackSender, canonical
from test_security import gateway, request


def test_slow_call_does_not_block_another_call_and_retries_keep_stream_order(tmp_path, monkeypatch):
    async def run():
        driver, _ = gateway(tmp_path)
        driver.settings.voice_callback_concurrency = 2
        driver.settings.voice_callback_poll_sec = .01
        sender = CallbackSender(driver.settings, driver.ledger)
        slow = asyncio.Event()
        fast_done = asyncio.Event()
        seen = []
        async def send(url, body):
            import json
            payload = json.loads(body)
            name = payload['call_id']
            seen.append((name, payload['seq']))
            if name == 'slow':
                await slow.wait()
            if name == 'fast' and payload['seq'] == 2:
                fast_done.set()
        monkeypatch.setattr(sender, '_send', send)
        url = request()['webhook_url']
        await sender.post(url, {'call_id': 'slow', 'seq': 1})
        await sender.post(url, {'call_id': 'fast', 'seq': 1})
        await sender.post(url, {'call_id': 'fast', 'seq': 2})
        await sender.start()
        try:
            await asyncio.wait_for(fast_done.wait(), 2)
            assert not slow.is_set()
            assert seen.index(('fast', 1)) < seen.index(('fast', 2))
        finally:
            slow.set()
            await sender.stop()
    asyncio.run(run())


def test_backoff_head_blocks_only_its_own_call(tmp_path, monkeypatch):
    async def run():
        driver, _ = gateway(tmp_path)
        sender = driver.sender
        url = request()['webhook_url']
        await sender.post(url, {'call_id': 'a', 'seq': 1})
        await sender.post(url, {'call_id': 'a', 'seq': 2})
        await sender.post(url, {'call_id': 'b', 'seq': 1})
        sent = []
        async def send(url, body):
            import json
            item = json.loads(body)
            sent.append((item['call_id'], item['seq']))
            if item['call_id'] == 'a':
                req = httpx.Request('POST', url)
                raise httpx.HTTPStatusError('busy', request=req, response=httpx.Response(429, headers={'Retry-After': '10'}, request=req))
        monkeypatch.setattr(sender, '_send', send)
        await sender.flush()
        await sender.flush()
        assert sent == [('a', 1), ('b', 1)]
        with driver.ledger.transaction() as db:
            rows = db.execute('SELECT * FROM outbox ORDER BY created').fetchall()
            assert len(rows) == 2 and rows[0]['due'] >= time.time() + 9
        # Restart retains head-of-line order even when its successor is due.
        restarted = CallbackSender(driver.settings, driver.ledger)
        monkeypatch.setattr(restarted, '_send', send)
        assert await restarted.flush() == 0
    asyncio.run(run())


def test_media_200_session_limit_is_atomic_and_reusable(tmp_path):
    from app.pipecat_pipeline import PipecatPipelineManager
    async def run():
        driver, _ = gateway(tmp_path)
        driver.settings.pipecat_max_active_sessions = 200
        manager = PipecatPipelineManager(driver.settings)
        async def create(i):
            return await manager.create_session(call_id=str(i), speech_webhook_url=request()['webhook_url'],
                                                media_webhook_url=request()['webhook_url'], metadata={})
        results = await asyncio.gather(*(create(i) for i in range(201)), return_exceptions=True)
        assert len(manager.sessions_by_call) == 200
        assert sum(isinstance(v, RuntimeError) for v in results) == 1
        # An idempotent duplicate consumes no additional slot.
        assert await create(0) is manager.sessions_by_call['0']
    asyncio.run(run())
