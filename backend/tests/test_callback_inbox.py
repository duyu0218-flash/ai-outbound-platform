"""Durable ACK, per-call FIFO, bounded capacity and atomic replay regressions."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
import hashlib
import json
from uuid import uuid4

import pytest
from sqlalchemy import delete, update, event
from sqlmodel import select

from test_production_hardening import client, _review_call, reset_runtime_settings_after_test
from app import db
from app.clock import utc_now
from app.config import get_settings
from app.models import CallbackInbox, CallbackInboxPartition, CallbackInboxWorker, CallSession, CallStatus, TaskOutbox, SpeechTurn
from app.schemas import SpeechWebhookEvent, MediaWebhookEvent
from app.services import callback_inbox as inbox


@pytest.fixture(autouse=True)
def clean_inbox(client, monkeypatch):
    monkeypatch.setattr(get_settings(), 'callback_inbox_enabled', True)
    monkeypatch.setattr(get_settings(), 'telephony_webhook_token', '')
    monkeypatch.setattr(get_settings(), 'telephony_webhook_secret', '')
    yield
    with db.session_scope() as s:
        s.execute(delete(CallbackInbox))
        s.execute(delete(CallbackInboxWorker))
        s.execute(update(CallbackInboxPartition).values(pending_count=0, pending_bytes=0))
        s.commit()


def speech(cid, key='event-1'):
    return SpeechWebhookEvent(call_id=cid, event_id=key, transcript='我想了解服务', is_final=True, attempt=1)


def receive(payload, kind='telephony_speech'):
    s = db.WebhookSession()
    try:
        result = inbox.receive(s, kind, payload)
        s.finish(success=True)
        return result
    finally:
        s.finish(success=False)


def rows():
    with db.session_scope() as s:
        return s.exec(select(CallbackInbox).order_by(CallbackInbox.id)).all()


def test_http_ack_only_after_durable_receive_then_worker_updates_business(client):
    cid = _review_call(CallStatus.IN_AI)
    payload = speech(cid)
    response = client.post('/api/v1/webhooks/telephony/speech', json=payload.model_dump(mode='json'))
    assert response.status_code == 200, response.text
    assert response.json()['processing'] == 'pending'
    with db.session_scope() as s:
        assert s.get(CallSession, cid).last_transcript != payload.transcript
        assert s.exec(select(SpeechTurn).where(SpeechTurn.call_session_id == cid)).first() is None
    receipt = rows()[0]
    assert inbox.consume_partition(receipt.partition_id) == 1
    with db.session_scope() as s:
        assert s.get(CallSession, cid).last_transcript == payload.transcript
        assert len(s.exec(select(TaskOutbox).where(TaskOutbox.aggregate_id == str(cid), TaskOutbox.task_type == 'ai_turn')).all()) == 1
        assert inbox.snapshot(s)['pending'] == 0
    assert rows()[0].state == 'done' and rows()[0].body_json == ''
    assert receive(payload)['duplicate']
    assert inbox.consume_partition(receipt.partition_id) == 0


def test_commit_failure_has_no_ack_or_receipt(client, monkeypatch):
    def fail(conn):
        raise RuntimeError('synthetic disk failure')
    monkeypatch.setattr(client._transport, 'raise_server_exceptions', False)
    event.listen(db.engine, 'commit', fail)
    try:
        response = client.post('/api/v1/webhooks/telephony/media', json=MediaWebhookEvent(
            call_id=uuid4(), event_id='disk-full', state='listening').model_dump(mode='json'))
        assert response.status_code >= 500
    finally:
        event.remove(db.engine, 'commit', fail)
    assert not rows()
    with db.session_scope() as s:
        assert inbox.snapshot(s)['pending'] == 0


def test_duplicate_concurrent_receipt_has_one_capacity_charge():
    payload = speech(uuid4())
    with ThreadPoolExecutor(max_workers=6) as pool:
        responses = list(pool.map(lambda _: receive(payload), range(12)))
    assert sum(not r['duplicate'] for r in responses) == 1
    assert len(rows()) == 1
    with db.session_scope() as s:
        result = inbox.snapshot(s)
        assert result['pending'] == 1 and result['pending_bytes'] == rows()[0].body_bytes


def test_full_queue_allows_duplicates_but_rejects_new_identity(monkeypatch):
    monkeypatch.setattr(get_settings(), 'callback_inbox_partition_limit', 1)
    cid = uuid4()
    receive(speech(cid))
    assert receive(speech(cid))['duplicate']
    with pytest.raises(Exception) as caught:
        receive(speech(cid, 'second'))
    assert caught.value.status_code == 503
    changed = speech(cid).model_copy(update={'transcript': 'changed'})
    with pytest.raises(Exception) as caught:
        receive(changed)
    assert caught.value.status_code == 409
    assert len(rows()) == 1


def test_byte_limit_is_independent_of_event_count(monkeypatch):
    payload = speech(uuid4())
    receive(payload)
    monkeypatch.setattr(get_settings(), 'callback_inbox_partition_bytes', rows()[0].body_bytes)
    with pytest.raises(Exception) as caught:
        receive(payload.model_copy(update={'event_id': 'next'}))
    assert caught.value.status_code == 503
    monkeypatch.setattr(get_settings(), 'callback_inbox_body_bytes', 10)
    with pytest.raises(Exception) as caught:
        receive(payload)
    assert caught.value.status_code == 413


def test_batch_limit_and_fifo_even_with_two_consumers(monkeypatch):
    cid = uuid4()
    for i in range(8): receive(speech(cid, str(i)))
    partition = rows()[0].partition_id
    applied = []
    monkeypatch.setattr(inbox, 'apply_receipt', lambda s, r: applied.append(json.loads(r.body_json)['event_id']))
    with ThreadPoolExecutor(max_workers=2) as pool:
        for _ in range(8):
            list(pool.map(lambda _: inbox.consume_partition(partition), range(2)))
    assert applied == list(map(str, range(8)))
    assert all(r.state == 'done' for r in rows())


def test_outer_rollback_reverts_handler_commits_and_completion(monkeypatch):
    cid = _review_call(CallStatus.IN_AI)
    original = None
    with db.session_scope() as s: original = s.get(CallSession, cid).last_transcript
    receive(speech(cid))
    receipt = rows()[0]
    def fail_after_service_commit(s, r):
        call = s.get(CallSession, cid)
        call.last_transcript = 'must rollback'
        s.add(call); s.commit()
        raise ValueError('synthetic poison')
    monkeypatch.setattr(inbox, 'apply_receipt', fail_after_service_commit)
    with pytest.raises(ValueError): inbox.consume_partition(receipt.partition_id)
    with db.session_scope() as s:
        assert s.get(CallSession, cid).last_transcript == original
        assert inbox.snapshot(s)['pending'] == 1
    assert rows()[0].state == 'pending' and rows()[0].attempts == 1


def test_dead_head_blocks_own_call_but_not_neighbor_and_can_retry(monkeypatch):
    cid = uuid4()
    receive(speech(cid, 'poison')); receive(speech(cid, 'later'))
    partition = rows()[0].partition_id
    while True:
        other = uuid4()
        if int.from_bytes(hashlib.sha256(str(other).encode()).digest()[:4], 'big') % inbox.PARTITIONS == partition: break
    receive(speech(other, 'neighbor'))
    monkeypatch.setattr(get_settings(), 'callback_inbox_max_attempts', 1)
    def apply(s, r):
        if json.loads(r.body_json)['event_id'] == 'poison': raise ValueError('bad')
    monkeypatch.setattr(inbox, 'apply_receipt', apply)
    with pytest.raises(ValueError): inbox.consume_partition(partition)
    assert inbox.consume_partition(partition) == 1
    assert [r.state for r in rows()] == ['dead', 'pending', 'done']
    inbox.retry_receipt(rows()[0].receipt_key)
    monkeypatch.setattr(inbox, 'apply_receipt', lambda *args: None)
    assert inbox.consume_partition(partition) == 2
    assert all(r.state == 'done' for r in rows())


def test_worker_heartbeat_and_backlog_gate_new_calls(monkeypatch):
    with db.session_scope() as s: assert not inbox.ready(s)
    inbox.maintenance('test-worker')
    with db.session_scope() as s: assert inbox.ready(s)
    receive(speech(uuid4()))
    with db.session_scope() as s:
        s.execute(update(CallbackInbox).values(received_at=utc_now() - timedelta(seconds=5))); s.commit()
        assert not inbox.ready(s)
    from app.services.call_service import _claim_dispatch_slot
    cid = _review_call(CallStatus.QUEUED)
    with db.session_scope() as s:
        assert not _claim_dispatch_slot(s, s.get(CallSession, cid))
        assert s.get(CallSession, cid).status == CallStatus.QUEUED


def test_disable_requires_drain(monkeypatch):
    receive(speech(uuid4()))
    monkeypatch.setattr(get_settings(), 'callback_inbox_enabled', False)
    with pytest.raises(RuntimeError, match='drain'): inbox.verify_mode()


def test_stale_attempt_is_consumed_without_business_effect():
    cid = _review_call(CallStatus.IN_AI)
    receive(speech(cid).model_copy(update={'attempt': 0}))
    inbox.consume_partition(rows()[0].partition_id)
    with db.session_scope() as s:
        assert s.exec(select(SpeechTurn).where(SpeechTurn.call_session_id == cid)).first() is None
    assert rows()[0].state == 'done'


def test_postgres_receipt_does_not_wait_on_call_business_lock():
    if db.engine.dialect.name != 'postgresql': pytest.skip('real PostgreSQL row locks')
    cid = _review_call(CallStatus.IN_AI)
    with db.session_scope() as blocker, ThreadPoolExecutor(max_workers=1) as pool:
        blocker.exec(select(CallSession).where(CallSession.id == cid).with_for_update()).one()
        try:
            result = pool.submit(receive, speech(cid)).result(timeout=2)
            assert result['processing'] == 'pending'
        finally: blocker.rollback()


def test_batch_rollback_also_reverts_earlier_completed_receipt(monkeypatch):
    cid = uuid4(); receive(speech(cid, 'first'))
    partition = rows()[0].partition_id
    while True:
        other = uuid4()
        if int.from_bytes(hashlib.sha256(str(other).encode()).digest()[:4], 'big') % inbox.PARTITIONS == partition: break
    receive(speech(other, 'second'))
    monkeypatch.setattr(get_settings(), 'callback_inbox_batch_budget_ms', 500)
    def apply(s, r):
        if json.loads(r.body_json)['event_id'] == 'second': raise ValueError('rollback whole batch')
    monkeypatch.setattr(inbox, 'apply_receipt', apply)
    with pytest.raises(ValueError): inbox.consume_partition(partition)
    assert [r.state for r in rows()] == ['pending', 'pending']
    assert all(r.body_json for r in rows())
    with db.session_scope() as s: assert inbox.snapshot(s)['pending'] == 2


def test_barge_in_creates_durable_guarded_action_and_stale_action_is_ignored(monkeypatch):
    from app.models import RealtimeSession, RealtimeState
    from app.services import realtime_voice
    from unittest.mock import AsyncMock
    cid = _review_call(CallStatus.IN_AI)
    with db.session_scope() as s:
        rt = RealtimeSession(tenant_id=1, call_session_id=cid, attempt=1, state=RealtimeState.SPEAKING, playback_id='old')
        s.add(rt); s.commit()
    receive(speech(cid).model_copy(update={'barge_in': True}))
    assert inbox.consume_partition(rows()[0].partition_id) == 1
    with db.session_scope() as s:
        task = s.exec(select(TaskOutbox).where(TaskOutbox.aggregate_id == str(cid), TaskOutbox.task_type == 'after_playback')).one()
        guard = json.loads(task.payload_json)
        assert guard['inbox_interrupt'] and guard['attempt'] == 1 and guard['speech_event_id'] == 'event-1'
        rt = s.exec(select(RealtimeSession).where(RealtimeSession.call_session_id == cid)).one()
        rt.playback_id = 'new'; s.add(rt); s.commit()
    adapter = type('Adapter', (), {'stop_speaking': AsyncMock()})()
    monkeypatch.setattr(realtime_voice, 'get_telephony_adapter', lambda **kw: adapter)
    asyncio.run(realtime_voice.interrupt_playback(cid, receipt_guard=guard, raise_on_failure=True))
    adapter.stop_speaking.assert_not_awaited()


def test_postgres_killed_consumer_releases_partition_and_rolls_back():
    if db.engine.dialect.name != 'postgresql': pytest.skip('real PostgreSQL crash recovery')
    import subprocess, sys, os, selectors
    cid = _review_call(CallStatus.IN_AI); receive(speech(cid))
    receipt = rows()[0]
    script = '''
from app.db import WebhookSession
from app.models import CallbackInbox
from app.services.callback_inbox import _lock_partition, apply_receipt
s=WebhookSession()
assert _lock_partition(s, PARTITION)
r=s.get(CallbackInbox, RECEIPT)
apply_receipt(s,r)
r.state='done';s.add(r);s.commit()
print('UNCOMMITTED',flush=True)
input()
'''.replace('PARTITION',str(receipt.partition_id)).replace('RECEIPT',str(receipt.id))
    child = subprocess.Popen([sys.executable, '-c', script], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=dict(os.environ))
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(child.stdout, selectors.EVENT_READ)
            assert selector.select(10), 'child did not enter uncommitted transaction'
        assert child.stdout.readline().strip() == b'UNCOMMITTED'
        assert inbox.consume_partition(receipt.partition_id) == 0
    finally:
        child.kill(); child.wait(timeout=5)
        child.stdin.close(); child.stdout.close(); child.stderr.close()
    assert rows()[0].state == 'pending'
    assert inbox.consume_partition(receipt.partition_id) == 1
    with db.session_scope() as s:
        assert len(s.exec(select(SpeechTurn).where(SpeechTurn.call_session_id == cid)).all()) == 1
        assert len(s.exec(select(TaskOutbox).where(TaskOutbox.aggregate_id == str(cid), TaskOutbox.task_type == 'ai_turn')).all()) == 1


@pytest.mark.parametrize('existing_model_tables', [False, True], ids=['empty-schema', 'model-created-schema'])
def test_postgres_migration_is_repeatable_and_matches_model_columns(existing_model_tables):
    if db.engine.dialect.name != 'postgresql': pytest.skip('PostgreSQL migration')
    from pathlib import Path
    from sqlalchemy import text, inspect
    schema = 'inbox_migration_' + uuid4().hex
    migration_directory = Path(__file__).resolve().parents[1] / 'migrations/postgresql'
    migrations = [path.read_text() for path in sorted(migration_directory.glob('20260908_callback_*.sql'))]
    with db.engine.connect() as conn:
        transaction = conn.begin()
        try:
            conn.execute(text(f'CREATE SCHEMA {schema}'))
            conn.execute(text(f'SET LOCAL search_path TO {schema}'))
            if existing_model_tables:
                for model in (CallbackInbox, CallbackInboxPartition, CallbackInboxWorker):
                    model.__table__.create(conn)
                conn.execute(CallbackInboxPartition.__table__.insert().values(id=0, pending_count=3, pending_bytes=42))
            for _ in range(2):
                for migration in migrations:
                    conn.execute(text(migration))
            assert conn.execute(text('SELECT count(*) FROM callbackinboxpartition')).scalar_one() == 64
            assert conn.execute(text('SELECT pending_count, pending_bytes FROM callbackinboxpartition WHERE id=0')).one() == ((3, 42) if existing_model_tables else (0, 0))
            assert conn.execute(text('SELECT count(*) FROM callbackinboxpartition WHERE id > 0 AND pending_count=0 AND pending_bytes=0')).scalar_one() == 63
            for model in (CallbackInbox, CallbackInboxPartition, CallbackInboxWorker):
                assert {c['name'] for c in inspect(conn).get_columns(model.__tablename__, schema=schema)} == set(model.__table__.columns.keys())
        finally: transaction.rollback()


def test_auth_and_validation_failures_never_receive(client, monkeypatch):
    monkeypatch.setattr(get_settings(), 'telephony_webhook_token', 'synthetic-required-token')
    assert client.post('/api/v1/webhooks/telephony/speech', json=speech(uuid4()).model_dump(mode='json')).status_code == 401
    monkeypatch.setattr(get_settings(), 'telephony_webhook_token', '')
    assert client.post('/api/v1/webhooks/telephony/speech', json={'call_id':'invalid'}).status_code in {400, 422}
    assert not rows()


def test_batch_limit_is_enforced_for_consecutive_events(monkeypatch):
    cid = uuid4()
    for i in range(6): receive(speech(cid, str(i)))
    partition = rows()[0].partition_id
    monkeypatch.setattr(get_settings(), 'callback_inbox_batch_size', 2)
    monkeypatch.setattr(inbox, 'apply_receipt', lambda *args: None)
    assert inbox.consume_partition(partition) == 2
    assert [r.state for r in rows()] == ['done', 'done', 'pending', 'pending', 'pending', 'pending']


def test_duplicate_savepoint_rollback_preserves_prior_event(monkeypatch):
    from sqlalchemy.exc import IntegrityError
    cid = _review_call(CallStatus.IN_AI)
    receive(speech(cid, 'first')); receive(speech(cid, 'second'))
    partition = rows()[0].partition_id
    def apply(s, r):
        call = s.get(CallSession, cid)
        if json.loads(r.body_json)['event_id'] == 'first':
            call.last_transcript = 'first committed in batch'; s.add(call); s.commit()
        else:
            try:
                s.add(CallbackInboxPartition(id=0)); s.commit()
            except IntegrityError:
                s.rollback()
            call = s.get(CallSession, cid)
            assert call.last_transcript == 'first committed in batch'
    monkeypatch.setattr(inbox, 'apply_receipt', apply)
    assert inbox.consume_partition(partition) == 2
    assert [r.state for r in rows()] == ['done', 'done']


def test_backoff_head_cannot_be_overtaken(monkeypatch):
    cid = uuid4()
    receive(speech(cid, 'first')); receive(speech(cid, 'later'))
    partition = rows()[0].partition_id
    with db.session_scope() as s:
        first = s.get(CallbackInbox, rows()[0].id)
        first.available_at = utc_now() + timedelta(minutes=1)
        s.add(first); s.commit()
    monkeypatch.setattr(inbox, 'apply_receipt', lambda *args: pytest.fail('backoff head was overtaken'))
    assert inbox.consume_partition(partition) == 0


def test_receipt_retention_does_not_purge_unprocessed_data():
    cid = uuid4()
    receive(speech(cid, 'done')); receive(speech(cid, 'pending'))
    with db.session_scope() as s:
        first = s.get(CallbackInbox, rows()[0].id)
        first.state = 'done'; first.completed_at = utc_now() - timedelta(days=8)
        s.add(first)
        s.execute(update(CallbackInbox).values(received_at=utc_now() - timedelta(days=8)))
        partition = s.get(CallbackInboxPartition, first.partition_id)
        partition.pending_count -= 1; partition.pending_bytes -= first.body_bytes; s.add(partition)
        s.commit()
    inbox.maintenance('retention')
    assert len(rows()) == 1 and rows()[0].state == 'pending'


def test_concurrent_distinct_events_cannot_exceed_partition_limit(monkeypatch):
    monkeypatch.setattr(get_settings(), 'callback_inbox_partition_limit', 4)
    cid = uuid4()
    def post(i):
        try:
            receive(speech(cid, str(i)))
            return 200
        except Exception as exc:
            return getattr(exc, 'status_code', type(exc).__name__)
    with ThreadPoolExecutor(max_workers=6) as pool:
        statuses = list(pool.map(post, range(12)))
    assert statuses.count(200) == 4 and statuses.count(503) == 8
    with db.session_scope() as s:
        assert inbox.snapshot(s)['pending'] == 4
        assert inbox.snapshot(s)['pending_bytes'] == sum(r.body_bytes for r in rows())


@pytest.mark.parametrize('pipeline', ['legacy', 'pipecat'])
def test_durable_interrupt_generation_fence_is_only_sent_to_pipecat(monkeypatch, pipeline):
    import httpx
    from app.models import RealtimeSession, RealtimeState
    from app.services import realtime_voice
    from app.services.telephony import HttpAdapter
    cid = _review_call(CallStatus.IN_AI)
    with db.session_scope() as s:
        call = s.get(CallSession, cid); call.voice_ai_pipeline = pipeline; s.add(call)
        s.add(RealtimeSession(tenant_id=1, call_session_id=cid, attempt=1,
            state=RealtimeState.SPEAKING, playback_id='current', turn_sequence=4)); s.commit()
    adapter = HttpAdapter('http://synthetic.invalid', tenant_id=1)
    observed = []
    async def post(path, payload):
        observed.append(payload)
        if pipeline == 'pipecat':
            raise httpx.HTTPStatusError('stale', request=httpx.Request('POST', 'http://synthetic.invalid'),
                response=httpx.Response(409, json={'detail': 'stale speech generation'}))
        return {'result': 'stopped'}
    monkeypatch.setattr(adapter, '_post', post)
    monkeypatch.setattr(realtime_voice, 'get_telephony_adapter', lambda **kw: adapter)
    guard = dict(attempt=1, playback_id='current', turn_sequence=4, speech_event_id='final')
    asyncio.run(realtime_voice.interrupt_playback(cid, receipt_guard=guard, raise_on_failure=True))
    assert len(observed) == 1
    assert ('expected_speech_event_id' in observed[0]) == (pipeline == 'pipecat')


@pytest.mark.parametrize('kind', ['status', 'transcript', 'speech', 'dtmf', 'media', 'recording'])
def test_every_telephony_entry_receives_then_consumes(client, kind):
    from app.models import CallEvent, RecordingAsset, RealtimeSession
    cid = _review_call(CallStatus.IN_AI)
    if kind == 'speech':
        body = speech(cid).model_dump(mode='json')
    elif kind == 'media':
        body = MediaWebhookEvent(call_id=cid, event_id='entry-media', attempt=1, state='listening').model_dump(mode='json')
    else:
        body = dict(call_id=str(cid), kind=kind, transcript='入口异步验证',
            payload=dict(event_id='entry-' + kind, attempt=1, status='answered', digit='1',
                         url='https://synthetic.invalid/test.wav', is_final=True))
    ack = client.post('/api/v1/webhooks/telephony/' + kind, json=body)
    assert ack.status_code == 200 and ack.json()['processing'] == 'pending'
    receipt = rows()[0]
    assert inbox.consume_partition(receipt.partition_id) == 1
    assert rows()[0].state == 'done'
    with db.session_scope() as s:
        if kind in {'speech', 'transcript'}:
            assert s.get(CallSession, cid).last_transcript == body['transcript']
            assert s.exec(select(TaskOutbox).where(TaskOutbox.aggregate_id == str(cid), TaskOutbox.task_type == 'ai_turn')).first()
        elif kind == 'recording':
            assert s.exec(select(RecordingAsset).where(RecordingAsset.call_session_id == cid)).one().provider_url == body['payload']['url']
        elif kind == 'media':
            assert s.exec(select(RealtimeSession).where(RealtimeSession.call_session_id == cid)).one().state.value == 'listening'
        else:
            assert s.exec(select(CallEvent).where(CallEvent.call_session_id == cid, CallEvent.event_type == kind)).first()
