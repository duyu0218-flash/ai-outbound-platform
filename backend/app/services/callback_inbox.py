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
import time

from fastapi import HTTPException
from sqlalchemy import func, text, update, delete, or_
from sqlalchemy.orm import aliased
from sqlmodel import select

from ..clock import utc_now
from ..config import get_settings
from ..models import CallbackInbox, CallbackInboxPartition, CallbackInboxWorker, CallSession, RealtimeSession

PARTITIONS = 64
LOCK_NAMESPACE = 19483921
settings = get_settings()
_committed_latency_ms = {}


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


def apply_receipt(session, receipt):
    from ..api.routers import webhooks
    handler = getattr(webhooks, receipt.kind).__wrapped__
    annotation = inspect.signature(handler).parameters['payload'].annotation
    # Router annotations may be postponed strings.
    if isinstance(annotation, str):
        annotation = getattr(webhooks, annotation)
    payload = annotation.model_validate_json(receipt.body_json)
    kwargs = dict(payload=payload, session=session, _=None)
    if 'background_tasks' in inspect.signature(handler).parameters:
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
    try:
        if not _lock_partition(session, partition_id):
            session.finish(success=False)
            return 0
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
            .limit(settings.callback_inbox_batch_size)).all()
        if not receipts:
            session.finish(success=False)
            return 0
        # Every business path observes the same lock order across calls.
        session.exec(select(CallSession).where(CallSession.id.in_([r.call_id for r in receipts]))
            .order_by(CallSession.id).with_for_update()).all()
        started = time.monotonic()
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
        session.execute(update(CallbackInboxPartition).where(CallbackInboxPartition.id == partition_id).values(
            pending_count=CallbackInboxPartition.pending_count - processed,
            pending_bytes=CallbackInboxPartition.pending_bytes - released_bytes))
        if worker_id:
            session.execute(update(CallbackInboxWorker).where(CallbackInboxWorker.id == worker_id).values(
                processed=CallbackInboxWorker.processed + processed,
                heartbeat_at=utc_now(), max_latency_ms=func.max(CallbackInboxWorker.max_latency_ms, max_latency)
                if session.get_bind().dialect.name == 'sqlite' else func.greatest(CallbackInboxWorker.max_latency_ms, max_latency)))
        received_times = [r.received_at for r in receipts[:processed]]
        session.finish(success=True)
        if worker_id:
            # Include outer commit and its fsync/lock wait in observed latency.
            latency = (utc_now() - min(received_times)).total_seconds() * 1000
            _committed_latency_ms[worker_id] = max(_committed_latency_ms.get(worker_id, 0), latency)
        return processed
    except Exception as exc:
        session.finish(success=False)
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
        ready=bool(live and not dead and age <= settings.callback_inbox_max_age_sec))


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
