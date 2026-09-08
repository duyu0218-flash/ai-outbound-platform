import asyncio
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest
from fastapi import HTTPException
from app.config import Settings
from app.quota import AccountQuota


def settings(tmp_path, **kwargs):
    return Settings(_env_file=None, llm_quota_db_path=str(tmp_path/'quota.db'),
                    llm_quota_rpm=20, llm_quota_tpm=200, llm_quota_rps=20, **kwargs)


def test_two_instances_reserve_one_shared_budget_and_survive_restart(tmp_path):
    cfg = settings(tmp_path)
    a, b = AccountQuota(cfg), AccountQuota(cfg)
    a.initialize(); b.initialize()
    def acquire(i):
        return (a if i%2 else b).reserve(10, now=100)
    with ThreadPoolExecutor(max_workers=16) as pool:
        results = list(pool.map(acquire, range(40)))
    assert results.count(0) == 20
    restarted = AccountQuota(cfg); restarted.initialize()
    assert restarted.reserve(1, now=160.99) == 1
    assert restarted.reserve(200, now=161) == 0
    assert restarted.reserve(1, now=161) == 1


def test_changed_account_budget_fails_closed(tmp_path):
    cfg = settings(tmp_path); AccountQuota(cfg).initialize()
    with pytest.raises(RuntimeError, match='identical'):
        AccountQuota(cfg.model_copy(update={'llm_quota_rpm':1000})).initialize()


def test_inflight_cancel_and_disk_failure_do_not_leak_slots(tmp_path):
    async def run():
        quota = AccountQuota(settings(tmp_path, llm_max_connections=1))
        quota.initialize()
        await quota.acquire(10)
        with pytest.raises(HTTPException) as caught:
            await quota.acquire(10)
        assert caught.value.status_code == 429 and quota.inflight == 1
        quota.release()
        quota.settings.llm_quota_db_path = str(tmp_path/'missing'/'no.db')
        with pytest.raises(HTTPException) as caught:
            await quota.acquire(10)
        assert caught.value.status_code == 503 and quota.inflight == 0
        assert not await quota.ready()
    asyncio.run(run())


def test_provider_cooldown_is_shared(tmp_path):
    async def run():
        cfg = settings(tmp_path)
        a,b = AccountQuota(cfg),AccountQuota(cfg)
        a.initialize();b.initialize()
        assert await b.ready()
        a.block(5)
        assert not await b.ready()
        with pytest.raises(HTTPException):
            await b.acquire(1)
        assert b.inflight == 0
    asyncio.run(run())


def test_llm_limit_rejects_before_network_and_cancellation_releases(monkeypatch, tmp_path):
    from app import llm
    async def run():
        cfg=llm.settings
        for k,v in dict(openai_base_url='https://model.invalid/v1',openai_api_key='synthetic',
                        llm_allowed_hosts='model.invalid',llm_quota_db_path=str(tmp_path/'quota.db'),
                        llm_quota_rpm=20,llm_quota_tpm=1,llm_quota_rps=20).items():
            monkeypatch.setattr(cfg,k,v)
        quota=AccountQuota(cfg);quota.initialize()
        monkeypatch.setattr(llm,'quota',quota)
        calls=[];entered=asyncio.Event()
        async def respond(req):
            calls.append(req);entered.set();await asyncio.Event().wait()
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            monkeypatch.setattr(llm,'_client',client)
            with pytest.raises(HTTPException):
                await llm.generate_reply(script='',transcript='hello',language='en')
            assert not calls
            # Disable disk quota only for the cancellation fixture.
            monkeypatch.setattr(cfg,'llm_quota_db_path','')
            task=asyncio.create_task(llm.generate_reply(script='',transcript='hello',language='en'))
            await entered.wait();task.cancel()
            with pytest.raises(asyncio.CancelledError):await task
            assert quota.inflight==0
    asyncio.run(run())
