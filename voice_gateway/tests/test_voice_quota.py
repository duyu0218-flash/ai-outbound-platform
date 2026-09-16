import json
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import pytest
from fastapi import HTTPException
from app.security import Ledger
from app import voice_quota as q
from app import action_commands


def settings(limit=2, rpm=2):
    scope = dict(provider='approved', account='account-a', region='region-a', product='asr', concurrency=limit)
    return SimpleNamespace(voice_quota_budgets_json=json.dumps(dict(asr=scope,
        tts={**scope, 'product':'tts', 'requests_per_minute':rpm})))


def test_no_approved_budget_never_reserves(tmp_path):
    ledger = Ledger(str(tmp_path/'quota.db'))
    with pytest.raises(HTTPException) as error, ledger.transaction() as db:
        q.reserve(db, SimpleNamespace(voice_quota_budgets_json='{}'), 'call', 1)
    assert error.value.status_code == 503


def test_competing_reservations_are_atomic(tmp_path):
    ledger = Ledger(str(tmp_path/'quota.db'))
    config = settings()
    def reserve(i):
        try:
            with ledger.transaction() as db:
                q.reserve(db, config, str(i), 1)
            return True
        except HTTPException:
            return False
    with ThreadPoolExecutor(max_workers=8) as pool:
        assert sum(pool.map(reserve, range(20))) == 2


def test_reconnect_unknown_survives_restart_and_call_end(tmp_path):
    path = str(tmp_path/'quota.db')
    ledger = Ledger(path)
    config = settings()
    scope = q.budget(config, 'asr')[0]
    with ledger.transaction() as db:
        q.reserve(db, config, 'c', 1)
        q.acquire(db, config, 'old', 'asr', 'c', 1, 'worker:epoch')
        assert q.usage(db, scope, 'asr') == 1
        q.release(db, 'old', 'worker:epoch', False)
        q.acquire(db, config, 'new', 'asr', 'c', 1, 'worker:epoch2')
        assert q.usage(db, scope, 'asr') == 2
        q.call_ended(db, 'c', 1)
    ledger = Ledger(path)
    with ledger.transaction() as db:
        assert q.usage(db, scope, 'asr') == 2
        with pytest.raises(HTTPException):
            q.reserve(db, config, 'other', 1)
        with pytest.raises(HTTPException):
            q.release(db, 'old', 'different-owner', True)
        q.release(db, 'old', 'worker:epoch', True)
        q.release(db, 'new', 'worker:epoch2', True)
        assert q.usage(db, scope, 'asr') == 0


def test_tts_rpm_not_reset_by_release_and_identity_is_fenced(tmp_path):
    ledger = Ledger(str(tmp_path/'quota.db'))
    config = settings(limit=1)
    with ledger.transaction() as db:
        q.reserve(db, config, 'c', 1)
        q.acquire(db, config, 'one', 'tts', 'c', 1, 'a')
        q.acquire(db, config, 'one', 'tts', 'c', 1, 'a')
        with pytest.raises(HTTPException):
            q.acquire(db, config, 'two', 'tts', 'c', 1, 'a')
        q.release(db, 'one', 'a', True)
        q.acquire(db, config, 'two', 'tts', 'c', 1, 'a')
        q.release(db, 'two', 'a', True)
        with pytest.raises(HTTPException) as error:
            q.acquire(db, config, 'three', 'tts', 'c', 1, 'a')
        assert 'rolling' in error.value.detail
        q.call_ended(db, 'c', 1)
        with pytest.raises(HTTPException):
            q.acquire(db, config, 'later', 'asr', 'c', 1, 'a')


def test_playback_lost_ack_and_restart_never_replay(tmp_path):
    path = str(tmp_path/'command.db')
    ledger = Ledger(path)
    payload = {'call_id':'c', 'tenant_id':1, 'expected_attempt':1, 'text':'hello'}
    assert action_commands.begin(ledger, 'id', 'speak', payload) is None
    with pytest.raises(HTTPException) as error:
        action_commands.begin(Ledger(path), 'id', 'speak', payload)
    assert error.value.headers['X-Voice-Outcome'] == 'unknown'
    action_commands.finish(ledger, 'id', {'playback_id':'p'})
    assert action_commands.begin(Ledger(path), 'id', 'speak', payload) == {'playback_id':'p'}
    with pytest.raises(HTTPException):
        action_commands.begin(ledger, 'id', 'speak', {**payload,'tenant_id':2})


def test_provider_hooks_hold_unknown_and_charge_each_reconnect():
    import asyncio
    from pipecat.frames.frames import ErrorFrame
    from app.voice_quota_client import protect_services
    class Client:
        settings=SimpleNamespace(voice_quota_normal_close_releases=False)
        def __init__(self):self.acquired=[];self.released=[]
        async def acquire(self,kind):
            value=str(len(self.acquired));self.acquired.append(kind);return value
        async def release(self,identity,kind,confirmed):self.released.append((identity,kind,confirmed))
    class STT:
        _websocket=None
        async def _connect(self):pass
        async def _disconnect(self):pass
    class TTS:
        _client=SimpleNamespace(max_retries=2)
        async def run_tts(self,*_):
            yield ErrorFrame(error='synthetic provider failure')
    async def run():
        client=Client();stt=STT();tts=TTS();protect_services(stt,tts,client)
        assert tts._client.max_retries==0
        await stt._connect();await stt._disconnect();await stt._connect()
        assert client.acquired==['asr','asr']
        assert client.released==[('0','asr',False)]
        _=[frame async for frame in tts.run_tts('text','context')]
        assert client.released[-1]==('2','tts',False)
    asyncio.run(run())


def test_scope_change_or_disable_cannot_forget_outstanding_usage(tmp_path):
    ledger=Ledger(str(tmp_path/'quota.db'));config=settings()
    with ledger.transaction() as db:q.reserve(db,config,'call',1)
    with pytest.raises(RuntimeError):
        q.validate_start(ledger,SimpleNamespace(voice_quota_enabled=False))
    changed=json.loads(config.voice_quota_budgets_json)
    changed['asr']['account']='different-account'
    config.voice_quota_budgets_json=json.dumps(changed)
    config.voice_quota_enabled=True
    with pytest.raises(HTTPException):q.validate_start(ledger,config)
