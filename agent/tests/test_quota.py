import asyncio
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest
import multiprocessing
import sqlite3
from pathlib import Path
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


def _process_reservations(config, output, ready):
    quota = AccountQuota(Settings(_env_file=None, **config))
    phase = 'startup'
    try:
        quota.start()
        # Measure the hot two-process burst after both WAL anchors exist.
        # Starting the burst while the peer is still importing tests startup
        # under sustained traffic, which intentionally has a bounded lock wait.
        phase = 'ready'
        ready.wait(timeout=10)
        phase = 'reserve'
        with ThreadPoolExecutor(max_workers=32) as pool:
            results = list(pool.map(lambda _: quota.reserve(1, now=100), range(400)))
        output.put(('ok', results.count(0)))
    except Exception as exc:
        output.put(('error', {'phase': phase, 'type': type(exc).__name__, 'message': str(exc),
                              'sqlite_code': getattr(exc, 'sqlite_errorcode', None)}))
    finally:
        quota.close()


def test_two_process_burst_keeps_exact_budget_without_database_busy(tmp_path):
    ctx = multiprocessing.get_context('spawn')
    output = ctx.Queue()
    ready = ctx.Barrier(2)
    cfg = settings(tmp_path).model_copy(update={'llm_quota_rpm':500, 'llm_quota_tpm':500,
                                               'llm_quota_rps':500})
    processes = [ctx.Process(target=_process_reservations, args=(cfg.model_dump(), output, ready)) for _ in range(2)]
    try:
        for process in processes: process.start()
        results = [output.get(timeout=20) for _ in processes]
        for process in processes:
            process.join(timeout=20)
            assert process.exitcode == 0
        assert all(kind == 'ok' for kind, _ in results), results
        assert sum(value for _, value in results) == 500
    finally:
        for process in processes:
            if process.is_alive(): process.terminate(); process.join(timeout=5)
        output.close()


def test_start_under_an_existing_write_lock_fails_closed_then_recovers(tmp_path):
    cfg = settings(tmp_path)
    AccountQuota(cfg).initialize()
    blocker = sqlite3.connect(cfg.llm_quota_db_path, isolation_level=None)
    quota = AccountQuota(cfg)
    try:
        blocker.execute('BEGIN IMMEDIATE')
        with pytest.raises(sqlite3.OperationalError) as caught:
            quota.start()
        assert caught.value.sqlite_errorcode & 255 in {sqlite3.SQLITE_BUSY, sqlite3.SQLITE_LOCKED}
        assert quota._wal_anchor is None
        blocker.execute('ROLLBACK')
        quota.start()
        assert quota.reserve(200, now=100) == 0
        assert quota.reserve(1, now=100) == 1
    finally:
        if blocker.in_transaction:
            blocker.execute('ROLLBACK')
        blocker.close()
        quota.close()


def test_quota_anchor_has_no_transaction_and_restart_retains_reservation(tmp_path):
    quota = AccountQuota(settings(tmp_path))
    try:
        quota.start()
        assert not quota._wal_anchor.in_transaction
        assert quota._wal_anchor.execute('PRAGMA synchronous').fetchone()[0] == 2
        assert quota.reserve(200, now=100) == 0
        quota.close()
        assert quota._wal_anchor is None
        quota.start()
        assert quota.reserve(1, now=100) == 1
    finally:
        quota.close()


def test_start_does_not_drop_the_wal_between_initialization_and_anchor(monkeypatch, tmp_path):
    cfg = settings(tmp_path)
    quota = AccountQuota(cfg)
    connect = sqlite3.connect
    closed_during_start = []
    starting = True

    class ObservedConnection(sqlite3.Connection):
        def close(self):
            super().close()
            if starting:
                closed_during_start.append(Path(cfg.llm_quota_db_path + '-wal').exists())

    def observed(*args, **kwargs):
        return connect(*args, factory=ObservedConnection, **kwargs)

    monkeypatch.setattr(sqlite3, 'connect', observed)
    try:
        quota.start()
        starting = False
        assert Path(cfg.llm_quota_db_path + '-wal').exists()
        assert all(closed_during_start), 'startup must not close its last WAL connection before the anchor is retained'
        assert not quota._wal_anchor.in_transaction
    finally:
        starting = False
        quota.close()


def test_ambiguous_commit_is_never_replayed_and_preserves_the_reservation(monkeypatch, tmp_path):
    cfg = settings(tmp_path)
    quota = AccountQuota(cfg)
    quota.initialize()
    connect = sqlite3.connect
    commits = []
    fail_once = True

    class AmbiguousCommit(sqlite3.Connection):
        def execute(self, sql, *args, **kwargs):
            nonlocal fail_once
            result = super().execute(sql, *args, **kwargs)
            if sql == 'COMMIT' and fail_once:
                fail_once = False
                commits.append(sql)
                raise sqlite3.OperationalError('synthetic error after durable commit')
            return result

    def ambiguous(*args, **kwargs):
        return connect(*args, factory=AmbiguousCommit, **kwargs)

    monkeypatch.setattr(sqlite3, 'connect', ambiguous)
    with pytest.raises(sqlite3.OperationalError):
        quota.reserve(10, now=100)
    assert commits == ['COMMIT']
    assert quota.reserve(191, now=100) == 1
    assert quota.reserve(190, now=100) == 0


def test_failed_start_closes_connection_and_does_not_retain_an_anchor(monkeypatch, tmp_path):
    cfg = settings(tmp_path)
    AccountQuota(cfg).initialize()
    connect = sqlite3.connect
    connections = []

    def observed(*args, **kwargs):
        db = connect(*args, **kwargs)
        connections.append(db)
        return db

    monkeypatch.setattr(sqlite3, 'connect', observed)
    quota = AccountQuota(cfg.model_copy(update={'llm_quota_rpm': 1000}))
    with pytest.raises(RuntimeError, match='identical'):
        quota.start()
    assert quota._wal_anchor is None
    for db in connections:
        with pytest.raises(sqlite3.ProgrammingError):
            db.execute('SELECT 1')
    restarted = AccountQuota(cfg)
    try:
        restarted.start()
        assert restarted.reserve(200, now=100) == 0
    finally:
        restarted.close()


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
