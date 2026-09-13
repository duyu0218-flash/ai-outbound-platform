"""WAL lifecycle, FULL durability and callback restart regression tests."""
import asyncio
import os
from pathlib import Path
import sqlite3
import httpx
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

import pytest

from app.security import Ledger, CallbackSender
from test_security import configuration, request


def test_anchor_retains_wal_without_pinning_snapshot_and_full_commit(tmp_path):
    ledger = Ledger(str(tmp_path / 'ledger.db'))
    anchor = ledger.open_wal_anchor()
    try:
        with ledger.transaction() as db:
            assert db.execute('PRAGMA synchronous').fetchone()[0] == 2
            assert db.execute('PRAGMA wal_autocheckpoint').fetchone()[0] == 1000
            db.execute("INSERT INTO flags VALUES ('committed', 'yes')")
        wal = Path(ledger.path + '-wal')
        assert wal.exists() and wal.stat().st_size > 0
        assert not anchor.in_transaction
        assert anchor.execute('PRAGMA wal_checkpoint(TRUNCATE)').fetchone() == (0, 0, 0)
        with pytest.raises(ValueError):
            with ledger.transaction() as db:
                db.execute("INSERT INTO flags VALUES ('rolled_back', 'no')")
                raise ValueError('abort')
        with ledger.read() as db:
            assert [tuple(row) for row in db.execute('SELECT * FROM flags')] == [('committed', 'yes')]
    finally:
        anchor.close()
    assert not Path(ledger.path + '-wal').exists()


def test_anchor_preserves_serialized_writes_and_independent_readers(tmp_path):
    ledger = Ledger(str(tmp_path / 'ledger.db'))
    anchor = ledger.open_wal_anchor()
    def write(i):
        with ledger.transaction() as db:
            db.execute('INSERT INTO flags VALUES (?, ?)', (str(i), str(i)))
        return ledger.summary()['pending_callbacks']
    try:
        with ThreadPoolExecutor(max_workers=8) as pool:
            assert list(pool.map(write, range(64))) == [0] * 64
        with ledger.read() as db:
            assert db.execute('SELECT count(*) FROM flags').fetchone()[0] == 64
    finally:
        anchor.close()


def test_full_commit_recovers_after_process_exit_without_checkpoint(tmp_path):
    path = str(tmp_path / 'crash.db')
    code = """
import os, sys
from app.security import Ledger, CallbackSender
from app.config import Settings
cfg = Settings(_env_file=None, voice_security_db_path=sys.argv[1])
ledger = Ledger(sys.argv[1])
anchor = ledger.open_wal_anchor()
CallbackSender(cfg, ledger)._persist('http://localhost/callback', b'{"call_id":"replay"}', 'replay')
os._exit(17)
"""
    env = dict(os.environ, PYTHONPATH=str(Path(__file__).resolve().parents[1]))
    result = subprocess.run([sys.executable, '-c', code, path], env=env, timeout=15)
    assert result.returncode == 17
    assert Path(path + '-wal').stat().st_size > 0
    ledger = Ledger(path)
    assert ledger.summary()['pending_callbacks'] == 1
    with ledger.read() as db:
        assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
        assert db.execute('SELECT body FROM outbox').fetchone()[0] == b'{"call_id":"replay"}'


def test_sender_stop_closes_anchor_and_restart_delivers_in_order(tmp_path, monkeypatch):
    async def run():
        cfg = configuration(tmp_path, voice_callback_poll_sec=.01)
        sender = CallbackSender(cfg)
        seen = []
        async def send(url, body):
            import json
            seen.append(json.loads(body)['seq'])
        monkeypatch.setattr(sender, '_send', send)
        for seq in (1, 2):
            await sender.start()
            anchor = sender._wal_anchor
            await sender.post(request()['webhook_url'], dict(call_id='same', seq=seq))
            for _ in range(200):
                if sender.ledger.summary()['pending_callbacks'] == 0:
                    break
                await asyncio.sleep(.01)
            await sender.stop()
            assert sender.ledger.summary()['pending_callbacks'] == 0
            with pytest.raises(sqlite3.ProgrammingError):
                anchor.execute('SELECT 1')
        assert seen == [1, 2]
    asyncio.run(run())


@pytest.mark.parametrize('concurrency', [1, 10, 24, 32])
def test_http_pool_shards_preserve_total_budget_and_release_after_failure(tmp_path, monkeypatch, concurrency):
    async def run():
        entered = 0
        gate = asyncio.Event()
        budgets = []
        instances = []
        original = httpx.AsyncClient
        async def transport(req):
            nonlocal entered
            entered += 1
            if entered == concurrency:
                gate.set()
            await asyncio.wait_for(gate.wait(), 2)
            if req.content == b'fail':
                raise httpx.ReadError('synthetic', request=req)
            return httpx.Response(200)
        def factory(**kwargs):
            budgets.append(kwargs['limits'].max_connections)
            instance = original(transport=httpx.MockTransport(transport), **kwargs)
            instances.append(instance)
            return instance
        monkeypatch.setattr(httpx, 'AsyncClient', factory)
        sender = CallbackSender(configuration(tmp_path, voice_callback_concurrency=concurrency))
        results = await asyncio.gather(*(sender._send(request()['webhook_url'],
                         b'fail' if i == 0 else b'ok') for i in range(concurrency)), return_exceptions=True)
        assert sum(budgets) == concurrency and max(budgets) <= 8
        assert sum(isinstance(r, httpx.ReadError) for r in results) == 1
        assert sender._http_active == [0] * len(budgets)
        await sender._send(request()['webhook_url'], b'ok')
        assert len(instances) == len(budgets)
        await sender.stop()
        assert all(client.is_closed for client in instances)
    asyncio.run(run())
