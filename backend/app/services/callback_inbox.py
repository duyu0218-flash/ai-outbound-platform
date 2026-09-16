"""Durable telephony receipt and bounded, ordered business consumption.

A partition row serializes receipt commits, not business execution. A transaction
advisory lock grants one consumer a partition until commit/rollback (including
process death). Business changes, outbox tasks and receipt completion share the
WebhookSession outer transaction. No provider I/O runs inside that transaction.
"""
from datetime import timedelta
import hashlib
import inspect
import json
import logging
import time

from fastapi import HTTPException
from sqlalchemy import func, text, update, delete, or_, insert
from collections import Counter, defaultdict, deque
from uuid import UUID
from functools import lru_cache
from sqlalchemy.orm import aliased
from sqlalchemy.exc import DBAPIError
from sqlmodel import select

from ..clock import utc_now
from ..config import get_settings
from ..models import CallbackInbox, CallbackInboxPartition, CallbackInboxWorker, CallSession, RealtimeSession

PARTITIONS = 64
LOCK_NAMESPACE = 19483921
settings = get_settings()
_committed_latency_ms = {}
# Last completed transaction per partition; bounded to the fixed partition count.
_transaction_timings = {}
_batch_limits = {}
_transaction_samples = defaultdict(lambda: deque(maxlen=2048))
logger = logging.getLogger(__name__)


def seed_partitions(connection):
    table = CallbackInboxPartition.__table__
    if connection.dialect.name == 'postgresql':
        from sqlalchemy.dialects.postgresql import insert
    else:
        from sqlalchemy.dialects.sqlite import insert
    connection.execute(insert(table).values([
        dict(id=i, pending_count=0, pending_bytes=0) for i in range(PARTITIONS)
    ]).on_conflict_do_nothing(index_elements=['id']))


def receive(session, kind, payload):
    body = payload.model_dump(mode='json')
    encoded = json.dumps(body, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
    size = len(encoded.encode())
    if size > settings.callback_inbox_body_bytes:
        raise HTTPException(413, 'callback body exceeds durable receipt limit')
    digest = hashlib.sha256(encoded.encode()).hexdigest()
    event_id = body.get('event_id') or (body.get('payload') or {}).get('event_id')
    key = hashlib.sha256(json.dumps([kind, str(payload.call_id), event_id or digest], separators=(',', ':')).encode()).hexdigest()
    partition_id = int.from_bytes(hashlib.sha256(str(payload.call_id).encode()).digest()[:4], 'big') % PARTITIONS
    if session.get_bind().dialect.name == 'postgresql':
        # One round trip for the normal path. The materialized row lock precedes
        # INSERT; counter charging occurs only for a newly inserted receipt.
        inserted = session.execute(text("""
            WITH capacity AS MATERIALIZED (
                SELECT id, pending_count, pending_bytes FROM callbackinboxpartition
                WHERE id=:partition_id FOR UPDATE
            ), received AS (
                INSERT INTO callbackinbox
                (receipt_key, partition_id, call_id, kind, body_json, body_digest,
                 body_bytes, state, received_at, available_at, attempts, error_type)
                SELECT :key, id, :call_id, :kind, :body, :digest, :size,
                       'pending', :now, :now, 0, ''
                FROM capacity WHERE pending_count < :count_limit
                    AND pending_bytes + :size <= :byte_limit
                ON CONFLICT (receipt_key) DO NOTHING RETURNING id
            )
            UPDATE callbackinboxpartition SET pending_count=pending_count+1,
                pending_bytes=pending_bytes+:size
            WHERE id=:partition_id AND EXISTS (SELECT 1 FROM received) RETURNING id
        """), dict(partition_id=partition_id, key=key, call_id=payload.call_id, kind=kind,
            body=encoded, digest=digest, size=size, now=utc_now(),
            count_limit=settings.callback_inbox_partition_limit,
            byte_limit=settings.callback_inbox_partition_bytes)).first()
        if inserted is not None:
            return dict(result='received', receipt_id=key, duplicate=False, processing='pending')
    else:
        # SQLite development mode is serialized by BEGIN IMMEDIATE.
        partition = session.exec(select(CallbackInboxPartition).where(
            CallbackInboxPartition.id == partition_id).with_for_update()).one()
    existing = session.exec(select(CallbackInbox).where(CallbackInbox.receipt_key == key)).first()
    if existing:
        if existing.body_digest != digest:
            raise HTTPException(409, 'event identity reused with different callback body')
        return dict(result='received', receipt_id=key, duplicate=True, processing=existing.state)
    if session.get_bind().dialect.name == 'postgresql' or (
            partition.pending_count >= settings.callback_inbox_partition_limit
            or partition.pending_bytes + size > settings.callback_inbox_partition_bytes):
        raise HTTPException(503, 'callback inbox partition is full; retry with the same event identity', headers={'Retry-After': '1'})
    session.add(CallbackInbox(receipt_key=key, partition_id=partition_id, call_id=payload.call_id,
        kind=kind, body_json=encoded, body_digest=digest, body_bytes=size))
    partition.pending_count += 1
    partition.pending_bytes += size
    session.add(partition)
    session.flush()
    # The route decorator commits the outer transaction BEFORE returning HTTP 200.
    return dict(result='received', receipt_id=key, duplicate=False, processing='pending')


def receive_batch(session, batch):
    """Atomic bounded receipt, with the same identities as individual routes.

    Lock partitions in ascending order before checking identities/capacity.
    This shares the single-event receiver's lock and permits one bulk insert
    and one counter update without per-event transactions or provider I/O.
    """
    encoded_batch = json.dumps(batch.model_dump(mode='json'), ensure_ascii=False, separators=(',', ':'))
    if len(encoded_batch.encode()) > 65536:
        raise HTTPException(413, 'callback batch exceeds 64 KiB')
    rows = []
    now = utc_now()
    for item in batch.events:
        body = item.payload
        encoded = json.dumps(body, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
        size = len(encoded.encode())
        if size > settings.callback_inbox_body_bytes:
            raise HTTPException(413, 'callback body exceeds durable receipt limit')
        digest = hashlib.sha256(encoded.encode()).hexdigest()
        event_id = body.get('event_id') or (body.get('payload') or {}).get('event_id')
        kind, call_id = 'telephony_' + item.kind, body['call_id']
        key = hashlib.sha256(json.dumps([kind, call_id, event_id or digest], separators=(',', ':')).encode()).hexdigest()
        partition_id = int.from_bytes(hashlib.sha256(call_id.encode()).digest()[:4], 'big') % PARTITIONS
        rows.append(dict(receipt_key=key, partition_id=partition_id, call_id=UUID(call_id),
            kind=kind, body_json=encoded, body_digest=digest, body_bytes=size,
            state='pending', received_at=now, available_at=now, attempts=0, error_type=''))
    partitions = session.exec(select(CallbackInboxPartition).where(
        CallbackInboxPartition.id.in_(sorted({r['partition_id'] for r in rows})))
        .order_by(CallbackInboxPartition.id).with_for_update()).all()
    existing = {key: digest for key, digest in session.exec(select(
        CallbackInbox.receipt_key, CallbackInbox.body_digest).where(
        CallbackInbox.receipt_key.in_([r['receipt_key'] for r in rows]))).all()}
    for row in rows:
        if row['receipt_key'] in existing and existing[row['receipt_key']] != row['body_digest']:
            raise HTTPException(409, 'event identity reused with different callback body')
    new = [r for r in rows if r['receipt_key'] not in existing]
    counts, sizes = Counter(), Counter()
    for row in new:
        counts[row['partition_id']] += 1
        sizes[row['partition_id']] += row['body_bytes']
    if len(partitions) != len({r['partition_id'] for r in rows}):
        raise HTTPException(503, 'callback partitions are not initialized')
    for part in partitions:
        if (counts[part.id] and (part.pending_count + counts[part.id] > settings.callback_inbox_partition_limit
                or part.pending_bytes + sizes[part.id] > settings.callback_inbox_partition_bytes)):
            raise HTTPException(503, 'callback inbox partition is full; retry original identities',
                headers={'Retry-After': '1', 'X-Callback-Batch-Split': 'true'})
    if not new:
        return
    session.execute(insert(CallbackInbox), new)
    if session.get_bind().dialect.name == 'postgresql':
        params, values = {}, []
        for i, part in enumerate(sorted(counts)):
            values.append(f'(:p{i}, :c{i}, :b{i})')
            params.update({f'p{i}': part, f'c{i}': counts[part], f'b{i}': sizes[part]})
        session.execute(text('UPDATE callbackinboxpartition AS p SET '
            'pending_count=p.pending_count+d.n, pending_bytes=p.pending_bytes+d.b '
            'FROM (VALUES ' + ','.join(values) + ') AS d(id,n,b) WHERE p.id=d.id'), params)
    else:
        for part in partitions:
            part.pending_count += counts[part.id]
            part.pending_bytes += sizes[part.id]
            session.add(part)
        session.flush()


class DurableBackground:
    def __init__(self, session, receipt):
        self.session, self.receipt = session, receipt

    def add_task(self, func, *args, **kwargs):
        from .task_queue import notify_task, enqueue_task
        from .realtime_voice import interrupt_playback
        if func is notify_task:
            # Outbox workers poll durably; a Redis wakeup is only a hint.
            return
        if func is not interrupt_playback:
            raise RuntimeError('unsupported callback background action')
        call = self.session.get(CallSession, args[0])
        realtime = self.session.exec(select(RealtimeSession).where(RealtimeSession.call_session_id == call.id)).one()
        body = json.loads(self.receipt.body_json)
        enqueue_task(self.session, tenant_id=call.tenant_id, task_type='after_playback',
            aggregate_id=str(call.id), idempotency_key='inbox-interrupt:' + self.receipt.receipt_key,
            payload=dict(inbox_interrupt=True, call_id=str(call.id), attempt=call.attempts,
                playback_id=realtime.playback_id, turn_sequence=realtime.turn_sequence,
                speech_event_id=body.get('event_id') or (body.get('payload') or {}).get('event_id')))


@lru_cache(maxsize=6)
def _receipt_handler(kind):
    from ..api.routers import webhooks
    if kind not in {'telephony_status', 'telephony_transcript', 'telephony_speech',
                    'telephony_dtmf', 'telephony_media', 'telephony_recording'}:
        raise ValueError('unknown callback receipt kind')
    handler = getattr(webhooks, kind).__wrapped__
    annotation = inspect.signature(handler).parameters['payload'].annotation
    # Router annotations may be postponed strings.
    if isinstance(annotation, str):
        annotation = getattr(webhooks, annotation)
    return handler, annotation, 'background_tasks' in inspect.signature(handler).parameters


def prepare_handlers():
    # A worker is not ready until its business modules and validators are loaded.
    # Previously the first receipt held locks while importing this entire graph.
    from . import conversation_policy, dialogue_rules  # noqa: F401
    for kind in ('status', 'transcript', 'speech', 'dtmf', 'media', 'recording'):
        _receipt_handler('telephony_' + kind)


def apply_receipt(session, receipt):
    handler, annotation, needs_background = _receipt_handler(receipt.kind)
    payload = annotation.model_validate_json(receipt.body_json)
    kwargs = dict(payload=payload, session=session, _=None)
    if needs_background:
        kwargs['background_tasks'] = DurableBackground(session, receipt)
    handler(**kwargs)


def _lock_partition(session, partition_id):
    if session.get_bind().dialect.name == 'postgresql':
        session.execute(text("SET LOCAL lock_timeout = '200ms'"))
        session.execute(text("SET LOCAL statement_timeout = '2000ms'"))
        return session.execute(text('SELECT pg_try_advisory_xact_lock(:ns, :part)'),
            dict(ns=LOCK_NAMESPACE, part=partition_id)).scalar_one()
    return True  # WebhookSession already holds SQLite BEGIN IMMEDIATE.


def consume_partition(partition_id, worker_id=None):
    from ..db import InboxBatchSession
    session = InboxBatchSession()
    failed_id = None
    processed = 0
    started = time.monotonic()
    timings = {}
    try:
        if not _lock_partition(session, partition_id):
            session.finish(success=False)
            return 0
        timings["partition_lock_ms"] = (time.monotonic() - started) * 1000
        selection_started = time.monotonic()
        older = aliased(CallbackInbox)
        # Include consecutive ready events of the same call in one batch.
        # Global id order + the partition lock prevent overtaking. A dead or
        # backoff head excludes later events of its call, not its neighbors.
        now = utc_now()
        unblocked = ~select(older.id).where(older.call_id == CallbackInbox.call_id,
            older.id < CallbackInbox.id, older.state != 'done',
            or_(older.state == 'dead', older.available_at > now)).exists()
        receipts = session.exec(select(CallbackInbox).where(
            CallbackInbox.partition_id == partition_id, CallbackInbox.state == 'pending',
            CallbackInbox.available_at <= now, unblocked).order_by(CallbackInbox.id)
            .limit(min(settings.callback_inbox_batch_size, _batch_limits.get(partition_id, settings.callback_inbox_batch_size)))).all()
        if not receipts:
            session.finish(success=False)
            return 0
        timings["select_ms"] = (time.monotonic() - selection_started) * 1000
        lock_started = time.monotonic()
        # Every business path observes the same lock order across calls.
        call_ids = {r.call_id for r in receipts}
        locked_calls = select(CallSession.id).where(CallSession.id.in_(call_ids))\
            .order_by(CallSession.id).with_for_update(skip_locked=True).subquery()
        call_locks = session.exec(select(CallSession.id, locked_calls.c.id)
            .outerjoin(locked_calls, locked_calls.c.id == CallSession.id)
            .where(CallSession.id.in_(call_ids))).all()
        # Defer ALL events of a busy call, preserving its FIFO while unrelated
        # calls can commit. Missing calls still reach the normal handler.
        busy_ids = {call_id for call_id, locked_id in call_locks if locked_id is None}
        receipts = [receipt for receipt in receipts if receipt.call_id not in busy_ids]
        if not receipts:
            session.finish(success=False)
            return 0
        timings["call_lock_ms"] = (time.monotonic() - lock_started) * 1000
        business_started = time.monotonic()
        released_bytes = 0
        max_latency = 0
        for receipt in receipts:
            failed_id = receipt.id
            session.begin_event()
            apply_receipt(session, receipt)
            now = utc_now()
            receipt.state, receipt.completed_at = 'done', now
            receipt.error_type = ''
            receipt.body_json = ''  # Retain only the replay digest after consumption.
            max_latency = max(max_latency, (now - receipt.received_at).total_seconds() * 1000)
            released_bytes += receipt.body_bytes
            session.add(receipt)
            session.end_event()  # A later duplicate rollback cannot erase earlier receipts.
            processed += 1
            if (time.monotonic() - started) * 1000 >= settings.callback_inbox_batch_budget_ms:
                break
        timings["business_ms"] = (time.monotonic() - business_started) * 1000
        accounting_started = time.monotonic()
        session.execute(update(CallbackInboxPartition).where(CallbackInboxPartition.id == partition_id).values(
            pending_count=CallbackInboxPartition.pending_count - processed,
            pending_bytes=CallbackInboxPartition.pending_bytes - released_bytes))
        if worker_id:
            session.execute(update(CallbackInboxWorker).where(CallbackInboxWorker.id == worker_id).values(
                processed=CallbackInboxWorker.processed + processed,
                heartbeat_at=utc_now(), max_latency_ms=func.max(CallbackInboxWorker.max_latency_ms, max_latency)
                if session.get_bind().dialect.name == 'sqlite' else func.greatest(CallbackInboxWorker.max_latency_ms, max_latency)))
        received_times = [r.received_at for r in receipts[:processed]]
        timings["accounting_ms"] = (time.monotonic() - accounting_started) * 1000
        commit_started = time.monotonic()
        session.finish(success=True)
        timings["commit_ms"] = (time.monotonic() - commit_started) * 1000
        timings.update(total_ms=(time.monotonic() - started) * 1000, processed=processed)
        _transaction_timings[partition_id] = timings
        for name, value in timings.items():
            if name.endswith('_ms'):
                _transaction_samples[name].append(value)
        previous_limit = min(settings.callback_inbox_batch_size, _batch_limits.get(partition_id, settings.callback_inbox_batch_size))
        if timings['total_ms'] > settings.callback_inbox_batch_budget_ms:
            _batch_limits[partition_id] = max(1, min(processed, previous_limit // 2))
        elif timings['total_ms'] < settings.callback_inbox_batch_budget_ms / 2:
            _batch_limits[partition_id] = min(settings.callback_inbox_batch_size, previous_limit + 1)
        logger.info("callback transaction partition=%s timings=%s", partition_id, timings)
        if worker_id:
            # Include outer commit and its fsync/lock wait in observed latency.
            latency = (utc_now() - min(received_times)).total_seconds() * 1000
            _committed_latency_ms[worker_id] = max(_committed_latency_ms.get(worker_id, 0), latency)
        return processed
    except Exception as exc:
        session.finish(success=False)
        # Lock contention is a transaction scheduling failure, not evidence
        # that this call's event is poisonous. Charging it to the last receipt
        # caused healthy calls to back off for seconds and eventually go dead.
        # Roll back the WHOLE batch and let the bounded worker poll retry it.
        sqlstate = getattr(getattr(exc, 'orig', None), 'sqlstate', None)
        if isinstance(exc, DBAPIError) and sqlstate in {'55P03', '40P01', '40001'}:
            logger.warning('callback batch deferred for database contention sqlstate=%s', sqlstate)
            return 0
        # No partially committed batch: replay all its events. Persist failure
        # separately, conditionally, so a concurrent successful replay wins.
        if failed_id is not None:
            mark_failure(partition_id, failed_id, type(exc).__name__)
        raise
    finally:
        session.finish(success=False)


def mark_failure(partition_id, receipt_id, error_type):
    from ..db import WebhookSession
    session = WebhookSession()
    try:
        if not _lock_partition(session, partition_id):
            return
        receipt = session.get(CallbackInbox, receipt_id)
        if receipt and receipt.state == 'pending':
            receipt.attempts += 1
            receipt.error_type = error_type[:128]
            receipt.available_at = utc_now() + timedelta(seconds=min(30, 2 ** receipt.attempts))
            if receipt.attempts >= settings.callback_inbox_max_attempts:
                receipt.state = 'dead'
            session.add(receipt)
        session.finish(success=True)
    finally:
        session.finish(success=False)


def snapshot(session):
    pending, size = session.exec(select(func.coalesce(func.sum(CallbackInboxPartition.pending_count), 0),
        func.coalesce(func.sum(CallbackInboxPartition.pending_bytes), 0))).one()
    oldest = session.exec(select(func.min(CallbackInbox.received_at)).where(CallbackInbox.state.in_(['pending', 'dead']))).one()
    dead = session.exec(select(func.count()).select_from(CallbackInbox).where(CallbackInbox.state == 'dead')).one()
    live = session.exec(select(func.count()).select_from(CallbackInboxWorker).where(
        CallbackInboxWorker.heartbeat_at >= utc_now() - timedelta(seconds=settings.callback_inbox_worker_ttl_sec))).one()
    processed, latency = session.exec(select(func.coalesce(func.sum(CallbackInboxWorker.processed), 0),
        func.coalesce(func.max(CallbackInboxWorker.max_latency_ms), 0))).one()
    age = max(0, (utc_now() - oldest).total_seconds()) if oldest else 0
    return dict(pending=int(pending), pending_bytes=int(size), dead=int(dead), oldest_age_sec=age,
        live_workers=int(live), processed=int(processed), max_completion_latency_ms=float(latency),
        ready=bool(live >= settings.callback_inbox_min_workers and not dead and age <= settings.callback_inbox_max_age_sec))


def ready(session):
    return not settings.callback_inbox_enabled or snapshot(session)['ready']


def maintenance(worker_id):
    from ..db import session_scope
    with session_scope() as session:
        worker = session.get(CallbackInboxWorker, worker_id) or CallbackInboxWorker(id=worker_id)
        worker.heartbeat_at = utc_now()
        worker.max_latency_ms = max(worker.max_latency_ms, _committed_latency_ms.get(worker_id, 0))
        session.add(worker)
        # Bounded receipt retention. Dead/pending events are NEVER purged.
        ids = select(CallbackInbox.id).where(CallbackInbox.state == 'done',
            CallbackInbox.completed_at < utc_now() - timedelta(days=settings.callback_inbox_receipt_days)).limit(100)
        session.execute(delete(CallbackInbox).where(CallbackInbox.id.in_(ids)))
        stale_workers = select(CallbackInboxWorker.id).where(
            CallbackInboxWorker.heartbeat_at < utc_now() - timedelta(days=settings.callback_inbox_receipt_days)).limit(100)
        session.execute(delete(CallbackInboxWorker).where(CallbackInboxWorker.id.in_(stale_workers)))
        session.commit()


def retry_receipt(receipt_key):
    from ..db import WebhookSession
    session = WebhookSession()
    try:
        receipt = session.exec(select(CallbackInbox).where(CallbackInbox.receipt_key == receipt_key)).one()
        if not _lock_partition(session, receipt.partition_id):
            raise RuntimeError('partition is busy; retry later')
        session.refresh(receipt)
        if receipt.state != 'dead':
            raise ValueError('only dead receipts can be requeued')
        receipt.state, receipt.attempts, receipt.error_type = 'pending', 0, ''
        receipt.available_at = utc_now()
        session.add(receipt)
        session.finish(success=True)
    finally:
        session.finish(success=False)


def verify_mode():
    """Never let a restart into synchronous mode overtake outstanding receipts."""
    from sqlalchemy import inspect as db_inspect
    from ..db import engine, session_scope
    tables = set(db_inspect(engine).get_table_names())
    if 'callbackinbox' not in tables:
        if settings.callback_inbox_enabled:
            raise RuntimeError('callback Inbox migration required')
        return
    with session_scope() as session:
        if settings.callback_inbox_enabled:
            ids = set(session.exec(select(CallbackInboxPartition.id)).all())
            if ids != set(range(PARTITIONS)):
                raise RuntimeError('callback Inbox partition migration required')
        elif session.exec(select(CallbackInbox.id).where(CallbackInbox.state != 'done').limit(1)).first() is not None:
            raise RuntimeError('drain callback Inbox before disabling async reception')


def transaction_timing_snapshot():
    result = {}
    for name, samples in _transaction_samples.items():
        values = sorted(samples)
        if values:
            result[name] = dict(sample_count=len(values), p99_ms=values[min(len(values)-1,int(len(values)*.99))], max_ms=values[-1])
    return dict(rolling_stages=result, last_partition_transactions=dict(_transaction_timings),
                next_partition_batch_limits=dict(_batch_limits))
