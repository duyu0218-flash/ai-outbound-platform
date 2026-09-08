from __future__ import annotations

import asyncio
import time
import hashlib
import json
import logging
import threading
from datetime import timedelta
from uuid import UUID, uuid4

from sqlalchemy import or_, update, func
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ..clock import utc_now
from ..config import get_settings
from .leases import monitored_lease, assert_execution_permitted, LeaseLost
from ..db import session_scope
from ..models import CallSession, CallStatus, RecordingAsset, TaskOutbox, TaskState, TaskReceipt, Tenant
from .runtime_metrics import record_outbox_duplicate

logger = logging.getLogger(__name__)
settings = get_settings()
_claim_cursors = {}
_claim_cursor_lock = threading.Lock()


class TaskDeferred(Exception):
    """No external admission occurred; retry later without spending an attempt."""


def lock_task_identity(session, key):
    # Shared by enqueue and archive: an insert cannot slip between archiving
    # the completed row and recording its replay tombstone.
    if session.get_bind().dialect.name == 'postgresql':
        from sqlalchemy import text
        value = int.from_bytes(hashlib.sha256(key.encode()).digest()[:8], 'big', signed=True)
        session.execute(text('SELECT pg_advisory_xact_lock(:key)'), {'key': value})
    else:
        session.exec(update(TaskOutbox).where(TaskOutbox.id == UUID(int=0)).values(updated_at=TaskOutbox.updated_at))


def enqueue_task(
    session: Session,
    *,
    tenant_id: int,
    task_type: str,
    aggregate_id: str,
    idempotency_key: str,
    payload: dict,
    max_attempts: int = 5,
    revive_dead: bool = False,
    available_at=None,
) -> TaskOutbox:
    lock_task_identity(session, idempotency_key)
    receipt = session.exec(select(TaskReceipt).where(TaskReceipt.idempotency_key == idempotency_key)).first()
    if receipt is not None:
        record_outbox_duplicate(task_type)
        return TaskOutbox(id=receipt.id, tenant_id=receipt.tenant_id, task_type=receipt.task_type,
            aggregate_id=receipt.aggregate_id, idempotency_key=receipt.idempotency_key,
            state=TaskState.COMPLETED, payload_json='{}')
    if task_type == "ai_turn" and "attempt" not in payload:
        call = session.get(CallSession, UUID(aggregate_id))
        if call is None or call.tenant_id != tenant_id:
            raise ValueError("AI task requires an existing tenant call")
        payload = {**payload, "attempt": call.attempts}
    if task_type == "ai_turn" and type(payload.get("turn_sequence")) is int:
        older = session.exec(select(TaskOutbox).where(
            TaskOutbox.tenant_id == tenant_id, TaskOutbox.aggregate_id == aggregate_id,
            TaskOutbox.task_type == "ai_turn",
            TaskOutbox.state.in_([TaskState.PENDING, TaskState.FAILED]),
        ).with_for_update()).all()
        for previous in older:
            old = json.loads(previous.payload_json)
            if (old.get("attempt") == payload.get("attempt")
                    and type(old.get("turn_sequence")) is int
                    and old["turn_sequence"] < payload["turn_sequence"]):
                previous.state = TaskState.COMPLETED
                previous.last_error = "superseded by newer final transcript"
                previous.updated_at = utc_now()
                session.add(previous)
        session.flush()
    existing = session.exec(select(TaskOutbox).where(TaskOutbox.idempotency_key == idempotency_key)).first()
    if existing is not None:
        if not revive_dead or existing.state != TaskState.DEAD:
            record_outbox_duplicate(task_type)
        if revive_dead and existing.state == TaskState.DEAD:
            existing.state = TaskState.PENDING
            existing.attempts = 0
            existing.max_attempts = max(1, max_attempts)
            existing.available_at = utc_now()
            existing.locked_at = None
            existing.lease_token = None
            existing.last_error = ""
            existing.payload_json = json.dumps(payload, ensure_ascii=False)
            existing.updated_at = utc_now()
            session.add(existing)
            session.commit()
            session.refresh(existing)
        return existing
    task = TaskOutbox(
        tenant_id=tenant_id,
        task_type=task_type,
        aggregate_id=aggregate_id,
        idempotency_key=idempotency_key,
        payload_json=json.dumps(payload, ensure_ascii=False),
        max_attempts=max(1, max_attempts),
        available_at=available_at or utc_now(),
    )
    session.add(task)
    try:
        session.commit()
    except IntegrityError:
        session.rollback()
        existing = session.exec(select(TaskOutbox).where(TaskOutbox.idempotency_key == idempotency_key)).first()
        if existing is None:
            raise
        record_outbox_duplicate(task_type)
        return existing
    session.refresh(task)
    return task


def retry_dead_task(session: Session, *, tenant_id: int, task_id: UUID) -> TaskOutbox | None:
    task = session.get(TaskOutbox, task_id)
    if task is None or task.tenant_id != tenant_id or task.state != TaskState.DEAD:
        return None
    task.state = TaskState.PENDING
    task.attempts = 0
    task.available_at = utc_now()
    task.locked_at = None
    task.lease_token = None
    task.last_error = ""
    task.updated_at = utc_now()
    session.add(task)
    if task.task_type == "recording_delete":
        asset = session.get(RecordingAsset, int(task.aggregate_id))
        if asset is not None and asset.deleted_at is None:
            asset.state = "deletion_pending"
            asset.updated_at = utc_now()
            session.add(asset)
    if task.task_type == "recording_ingest":
        asset = session.get(RecordingAsset, int(task.aggregate_id))
        if asset is not None and asset.deleted_at is None and not asset.storage_uri:
            asset.state = "available"
            asset.updated_at = utc_now()
            session.add(asset)
    session.commit()
    session.refresh(task)
    return task


def enqueue_business_callback(
    session: Session,
    *,
    tenant_id: int,
    call_id: UUID,
    event_type: str,
    data: dict,
    idempotency_key: str | None = None,
) -> TaskOutbox | None:
    from .admin_settings import get_admin_setting
    config = get_admin_setting(session, tenant_id, "integration")
    if not config.get("callback_enabled") or not str(config.get("webhook_base_url") or "").strip():
        return None
    canonical = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    digest = hashlib.sha256(f"{call_id}:{event_type}:{canonical}".encode()).hexdigest()
    return enqueue_task(
        session,
        tenant_id=tenant_id,
        task_type="business_callback",
        aggregate_id=str(call_id),
        idempotency_key=idempotency_key or f"callback:{digest}",
        payload={
            "tenant_id": tenant_id,
            "call_id": str(call_id),
            "event_type": event_type,
            "data": data,
        },
    )


def _owned_task(session, task_id, token):
    return session.exec(select(TaskOutbox).where(
        TaskOutbox.id == task_id, TaskOutbox.lease_token == token,
        TaskOutbox.state == TaskState.PROCESSING,
    ).with_for_update()).first()


def _renew_task(task_id, token) -> bool:
    with session_scope() as session:
        now = utc_now()
        result = session.exec(update(TaskOutbox).where(
            TaskOutbox.id == task_id, TaskOutbox.lease_token == token,
            TaskOutbox.state == TaskState.PROCESSING,
            TaskOutbox.locked_at > now - timedelta(seconds=max(2, settings.task_lease_sec)),
        ).values(locked_at=now, updated_at=now))
        session.commit()
        return result.rowcount == 1


def _record_dead_task(session, task):
    # Task death never proves PBX termination. Preserve ALL call lifecycle states.
    if task.task_type in {'recording_delete', 'recording_ingest'}:
        asset = session.get(RecordingAsset, int(task.aggregate_id))
        if asset is not None and asset.deleted_at is None:
            if task.task_type == 'recording_delete':
                asset.state = 'deletion_failed'
            elif not asset.storage_uri:
                asset.state = 'ingestion_failed'
            asset.updated_at = utc_now()
            session.add(asset)


async def _execute_task(task_id, token, task_type, payload):
    assert_execution_permitted()
    if task_type == 'dial_call':
        from .call_service import _place_call_with_result
        with session_scope() as session:
            call = session.get(CallSession, UUID(payload['call_id']))
            if call is None or call.attempts != payload['attempt'] or call.status != CallStatus.QUEUED:
                return
            call, attempted = await _place_call_with_result(session, call)
            if not attempted and call.status == CallStatus.QUEUED:
                raise TaskDeferred()
    elif task_type == 'ai_turn':
        from .dispatcher import run_ai_turn
        attempt = payload.get('attempt')
        if type(attempt) is not int:
            # Legacy tasks cannot safely infer the original dial attempt.
            raise ValueError('legacy AI task has no attempt; manual review required')
        with session_scope() as session:
            call = session.get(CallSession, UUID(payload['call_id']))
            if call is None or call.attempts != attempt or call.status not in {CallStatus.ANSWERED, CallStatus.IN_AI}:
                return
        await run_ai_turn(call_id=UUID(payload['call_id']), transcript=str(payload.get('transcript') or ''),
                          durable=True, expected_attempt=attempt,
                          **({'expected_speech_event_id': payload['speech_event_id']} if 'speech_event_id' in payload else {}),
                          **({'expected_turn_sequence': payload['turn_sequence']} if 'turn_sequence' in payload else {}))
    elif task_type == 'after_playback':
        if payload.get('product_kind'):
            from .conversation_policy import run_product_task
            await run_product_task(payload)
        else:
            from .dispatcher import resume_after_playback
            await resume_after_playback(payload)
    elif task_type == 'business_callback':
        from .business_callbacks import deliver_business_callback
        await deliver_business_callback(tenant_id=int(payload['tenant_id']),call_id=UUID(payload['call_id']),
            event_type=payload['event_type'],data=dict(payload.get('data') or {}),raise_on_failure=True,
            delivery_id=str(task_id))
    elif task_type == 'call_analysis':
        from .call_analysis import analyze_call
        with session_scope() as session:
            call = session.get(CallSession, UUID(payload['call_id']))
            if call is None or call.attempts != payload.get('attempt'):
                return
            analyze_call(session, call)
    elif task_type in {'recording_ingest', 'recording_delete'}:
        from .recording_storage import ingest_recording_asset, delete_recording_asset
        from .leases import redis_lease
        # Serialize ingest/delete of the same object, including across worker processes.
        async with redis_lease(url=settings.redis_url, key=f"ai-outbound:recording:{payload['recording_asset_id']}",
                               ttl=max(2, settings.task_lease_sec), wait_sec=5) as acquired:
            if not acquired:
                raise RuntimeError('recording asset is being processed')
            with session_scope() as session:
                asset = session.get(RecordingAsset, int(payload['recording_asset_id']))
                if asset is None or asset.deleted_at is not None:
                    return
                if task_type == 'recording_ingest' and (asset.storage_uri or
                    (asset.retention_until is not None and asset.retention_until <= utc_now())):
                    return
                session.expunge(asset)
            assert_execution_permitted()
            if task_type == 'recording_ingest':
                result = await asyncio.to_thread(ingest_recording_asset, asset)
            else:
                await asyncio.to_thread(delete_recording_asset, asset)
            assert_execution_permitted()
            with session_scope() as session:
                if _owned_task(session, task_id, token) is None:
                    raise LeaseLost('recording task no longer owns its lease')
                current = session.get(RecordingAsset, asset.id)
                if current is not None and current.deleted_at is None:
                    if task_type == 'recording_delete':
                        current.provider_url = current.storage_uri = ''
                        current.provider_recording_id = None
                        current.state = 'deleted'
                        current.deleted_at = utc_now()
                        call = session.get(CallSession, current.call_session_id)
                        if call is not None and call.recording_url == asset.provider_url:
                            call.recording_url = None
                            session.add(call)
                    else:
                        current.storage_uri = result['storage_uri']
                        current.checksum_sha256 = result.get('checksum_sha256') or current.checksum_sha256
                        current.state = 'stored'
                    current.updated_at = utc_now()
                    session.add(current)
                    session.commit()
    else:
        raise ValueError('unsupported durable task type')


async def process_task(task_id: UUID, *, claimed=None) -> bool:
    now = utc_now()
    ttl = max(2, settings.task_lease_sec)
    token = uuid4().hex
    started = time.monotonic()
    if claimed is not None:
        token, task_type, raw_payload, started = claimed
    else:
        with session_scope() as session:
            result = session.exec(update(TaskOutbox).where(
                TaskOutbox.id == task_id,
                TaskOutbox.attempts < TaskOutbox.max_attempts,
                or_(TaskOutbox.state.in_([TaskState.PENDING, TaskState.FAILED]),
                    (TaskOutbox.state == TaskState.PROCESSING) & (TaskOutbox.locked_at <= now - timedelta(seconds=ttl))),
                TaskOutbox.available_at <= now,
            ).values(state=TaskState.PROCESSING, attempts=TaskOutbox.attempts+1,
                     locked_at=now, lease_token=token, updated_at=now))
            if result.rowcount != 1:
                session.rollback()
                return False
            session.commit()
            task = session.get(TaskOutbox, task_id)
            task_type = task.task_type
            raw_payload = task.payload_json
    try:
        payload = json.loads(raw_payload or '{}')
        async def renew():
            return await asyncio.to_thread(_renew_task, task_id, token)
        async with monitored_lease(renew, ttl=ttl, initial_until=started+ttl):
            await asyncio.wait_for(_execute_task(task_id,token,task_type,payload),timeout=max(1,settings.task_timeout_sec))
            assert_execution_permitted()
            with session_scope() as session:
                task = _owned_task(session, task_id, token)
                if task is None:
                    return False
                task.state=TaskState.COMPLETED
                task.locked_at=None
                task.lease_token=None
                task.last_error=''
                task.updated_at=utc_now()
                session.add(task); session.commit()
        return True
    except asyncio.CancelledError:
        # Shutdown leaves the durable claim for bounded lease recovery.
        raise
    except TaskDeferred:
        with session_scope() as session:
            task = _owned_task(session, task_id, token)
            if task is not None:
                task.state = TaskState.PENDING
                task.attempts = max(0, task.attempts - 1)
                task.available_at = utc_now() + timedelta(seconds=1)
                task.locked_at = task.lease_token = None
                task.last_error = 'waiting for dial admission'
                task.updated_at = utc_now()
                session.add(task); session.commit()
        return False
    except Exception as exc:
        logger.warning('durable task failed id=%s type=%s error_type=%s',task_id,task_type,type(exc).__name__)
        with session_scope() as session:
            task = _owned_task(session, task_id, token)
            if task is None:
                return False
            task.state = TaskState.DEAD if task.attempts >= task.max_attempts else TaskState.FAILED
            task.available_at=utc_now()+timedelta(seconds=min(300,2**task.attempts))
            task.locked_at=None
            task.lease_token=None
            task.last_error=type(exc).__name__  # no URL tokens or transcript in error strings
            task.updated_at=utc_now()
            session.add(task)
            if task.state == TaskState.DEAD:
                _record_dead_task(session,task)
            session.commit()
        return False


def process_task_sync(task_id):
    """A lane thread owns its event loop and every SQLModel session it uses."""
    return asyncio.run(process_task(task_id))


async def process_pending_tasks(*, batch_size: int = 100, task_types: tuple[str, ...] | None = None,
                                threaded: bool = False) -> int:
    now=utc_now()
    cutoff=now-timedelta(seconds=max(2,settings.task_lease_sec))
    with session_scope() as session:
        exhausted_query=select(TaskOutbox).where(TaskOutbox.state==TaskState.PROCESSING,
            TaskOutbox.locked_at<=cutoff,TaskOutbox.attempts>=TaskOutbox.max_attempts)
        if task_types:
            exhausted_query=exhausted_query.where(TaskOutbox.task_type.in_(task_types))
        exhausted=session.exec(exhausted_query.limit(max(1,batch_size)).with_for_update(skip_locked=True)).all()
        for task in exhausted:
            task.state=TaskState.DEAD;task.locked_at=None;task.lease_token=None
            task.last_error='worker stopped during final task attempt';task.updated_at=now
            session.add(task);_record_dead_task(session,task)
        session.commit()
        ready=select(TaskOutbox.id,TaskOutbox.task_type,TaskOutbox.available_at,
            func.row_number().over(partition_by=TaskOutbox.tenant_id,order_by=(TaskOutbox.available_at,TaskOutbox.id)).label('tenant_rank')).where(
            or_(TaskOutbox.state.in_([TaskState.PENDING,TaskState.FAILED]),
                (TaskOutbox.state==TaskState.PROCESSING)&(TaskOutbox.locked_at<=cutoff)),
            TaskOutbox.available_at<=now,TaskOutbox.attempts<TaskOutbox.max_attempts)
        if task_types:
            ready=ready.where(TaskOutbox.task_type.in_(task_types))
        ranked=ready.subquery()
        rows=session.exec(select(ranked.c.id,ranked.c.task_type).order_by(ranked.c.tenant_rank,ranked.c.available_at).limit(max(1,batch_size))).all()
    limits=settings.resolved_task_queue_lanes()
    aliases=settings.resolved_task_queue_aliases()
    sems={key:asyncio.Semaphore(value) for key,value in limits.items()}
    default_bucket=next(iter(sems), "recording")
    if "recording" in sems:
        default_bucket="recording"

    def _bucket_name(task_type: str) -> str:
        bucket=aliases.get(task_type, task_type)
        return bucket if bucket in sems else default_bucket

    async def execute(row):
        task_id,kind=row
        effective=_bucket_name(kind)
        async with sems[effective]:
            return await asyncio.to_thread(process_task_sync,task_id) if threaded else await process_task(task_id)
    return sum(await asyncio.gather(*(execute(row) for row in rows)))


async def notify_task(task_id):
    # Compatibility mode is explicit and forbidden in production. Normal API
    # requests do no AI, recording or customer callback work after committing.
    if settings.task_inline_execution_enabled and settings.env.lower() not in {'prod','production'}:
        return await process_task(task_id)
    return None


def claim_ready_tasks(task_types: tuple[str, ...], limit: int):
    """Claim only free execution slots in one short transaction, not a batch backlog."""
    from sqlalchemy import exists, and_
    from sqlalchemy.orm import aliased
    now = utc_now()
    cutoff = now - timedelta(seconds=max(2, settings.task_lease_sec))
    earlier = aliased(TaskOutbox)
    with session_scope() as session:
        if session.get_bind().dialect.name == 'sqlite':
            session.exec(update(TaskOutbox).where(TaskOutbox.id == UUID(int=0)).values(updated_at=TaskOutbox.updated_at))
        # Recover workers lost on their last permitted attempt without stranding
        # every later event for that call behind a permanently processing row.
        exhausted = session.exec(select(TaskOutbox).where(
            TaskOutbox.task_type.in_(task_types), TaskOutbox.state == TaskState.PROCESSING,
            TaskOutbox.locked_at <= cutoff, TaskOutbox.attempts >= TaskOutbox.max_attempts,
        ).limit(max(1, limit)).with_for_update(skip_locked=True)).all()
        for task in exhausted:
            task.state = TaskState.DEAD
            task.locked_at = task.lease_token = None
            task.last_error = 'worker stopped during final task attempt'
            task.updated_at = now
            session.add(task)
            _record_dead_task(session, task)
        session.flush()
        no_earlier = ~exists(select(earlier.id).where(
            earlier.aggregate_id == TaskOutbox.aggregate_id,
            earlier.task_type == TaskOutbox.task_type,
            earlier.state.in_([TaskState.PENDING, TaskState.FAILED, TaskState.PROCESSING]),
            or_(earlier.created_at < TaskOutbox.created_at,
                and_(earlier.created_at == TaskOutbox.created_at, earlier.id < TaskOutbox.id))))
        eligible = (
            TaskOutbox.task_type.in_(task_types), TaskOutbox.available_at <= now,
            TaskOutbox.attempts < TaskOutbox.max_attempts,
            or_(TaskOutbox.state.in_([TaskState.PENDING, TaskState.FAILED]),
                (TaskOutbox.state == TaskState.PROCESSING) & (TaskOutbox.locked_at <= cutoff)))
        # Enumerate tenant heads via EXISTS/index probes, then read only a
        # bounded number per tenant. Never sort the entire ready backlog with
        # ROW_NUMBER on every 50 ms poll. Rotate the starting tenant each poll.
        tenant_query = select(Tenant.id).where(exists(select(TaskOutbox.id).where(
            TaskOutbox.tenant_id == Tenant.id, *eligible, no_earlier)))
        with _claim_cursor_lock:
            cursor = _claim_cursors.get(task_types, 0)
        tenant_limit = min(32, max(1, limit))
        tenant_ids = list(session.exec(tenant_query.where(Tenant.id > cursor)
            .order_by(Tenant.id).limit(tenant_limit)).all())
        if len(tenant_ids) < tenant_limit:
            tenant_ids.extend(session.exec(tenant_query.where(Tenant.id <= cursor)
                .order_by(Tenant.id).limit(tenant_limit - len(tenant_ids))).all())
        rows = []
        for index, tenant_id in enumerate(tenant_ids):
            per_tenant = max(1, (limit - len(rows)) // (len(tenant_ids) - index))
            rows.extend(session.exec(select(TaskOutbox).where(
                TaskOutbox.tenant_id == tenant_id, *eligible, no_earlier)
                .order_by(TaskOutbox.available_at, TaskOutbox.id).limit(per_tenant)
                .with_for_update(skip_locked=True, of=TaskOutbox)).all())
        if tenant_ids:
            with _claim_cursor_lock:
                if len(_claim_cursors) >= 64 and task_types not in _claim_cursors:
                    _claim_cursors.clear()
                _claim_cursors[task_types] = tenant_ids[-1]
        claims = []
        for task in rows:
            token = uuid4().hex
            # Conditional update also protects SQLite, whose SELECT has no row lock.
            result = session.exec(update(TaskOutbox).where(
                TaskOutbox.id == task.id, TaskOutbox.attempts == task.attempts,
                or_(TaskOutbox.state.in_([TaskState.PENDING, TaskState.FAILED]),
                    (TaskOutbox.state == TaskState.PROCESSING) & (TaskOutbox.locked_at <= cutoff)),
            ).values(state=TaskState.PROCESSING, attempts=TaskOutbox.attempts + 1,
                     locked_at=now, lease_token=token, updated_at=now))
            if result.rowcount == 1:
                claims.append((task.id, (token, task.task_type, task.payload_json, time.monotonic())))
        session.commit()
        return claims


async def run_task_lane(stop_event: asyncio.Event, *, task_types: tuple[str, ...], concurrency: int):
    """Continuous per-lane pool; a slow job never holds back free slots.

    Existing synchronous ORM work stays in bounded lane threads, with independent
    sessions/event loops. A recording lane cannot exhaust the AI lane's executor.
    """
    from concurrent.futures import ThreadPoolExecutor
    import threading
    from .worker_runtime import WorkerRuntime
    local = threading.local()
    runtimes = []
    runtime_lock = threading.Lock()
    def execute(task_id, claim):
        if not hasattr(local, "runtime"):
            local.runtime = WorkerRuntime()
            with runtime_lock:
                runtimes.append(local.runtime)
        return local.runtime.run(process_task(task_id, claimed=claim))
    executor = ThreadPoolExecutor(max_workers=max(1, concurrency), thread_name_prefix=task_types[0])
    pending = set()
    loop = asyncio.get_running_loop()
    try:
        while not stop_event.is_set():
            done = {job for job in pending if job.done()}
            pending.difference_update(done)
            for job in done:
                try:
                    job.result()
                except Exception:
                    logger.exception('task lane execution failed')
            available = concurrency - len(pending)
            if available > 0:
                try:
                    claims = await asyncio.to_thread(claim_ready_tasks, task_types, available)
                    for task_id, claim in claims:
                        pending.add(loop.run_in_executor(executor, execute, task_id, claim))
                except Exception:
                    logger.exception('task lane claim failed')
            if pending:
                await asyncio.wait(pending, timeout=max(.01, settings.task_poll_interval_sec),
                                   return_when=asyncio.FIRST_COMPLETED)
            else:
                try:
                    await asyncio.wait_for(stop_event.wait(), max(.01, settings.task_poll_interval_sec))
                except asyncio.TimeoutError:
                    pass
    finally:
        # No new claims while draining; accepted work retains its lease renewal.
        await asyncio.gather(*pending, return_exceptions=True)
        executor.shutdown(wait=True)
        # All jobs are drained; close each client on its original loop.
        for runtime in runtimes:
            await asyncio.to_thread(runtime.close)
