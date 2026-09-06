from __future__ import annotations

import asyncio
import time
import hashlib
import json
import logging
from datetime import timedelta
from uuid import UUID, uuid4

from sqlalchemy import or_, update, func
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ..clock import utc_now
from ..config import get_settings
from .leases import monitored_lease, assert_execution_permitted, LeaseLost
from ..db import session_scope
from ..models import CallSession, CallStatus, RecordingAsset, TaskOutbox, TaskState
from .runtime_metrics import record_outbox_duplicate

logger = logging.getLogger(__name__)
settings = get_settings()


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
) -> TaskOutbox:
    if task_type == "ai_turn" and "attempt" not in payload:
        call = session.get(CallSession, UUID(aggregate_id))
        if call is None or call.tenant_id != tenant_id:
            raise ValueError("AI task requires an existing tenant call")
        payload = {**payload, "attempt": call.attempts}
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
) -> TaskOutbox:
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
    if task_type == 'ai_turn':
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
                          durable=True, expected_attempt=attempt)
    elif task_type == 'business_callback':
        from .business_callbacks import deliver_business_callback
        await deliver_business_callback(tenant_id=int(payload['tenant_id']),call_id=UUID(payload['call_id']),
            event_type=payload['event_type'],data=dict(payload.get('data') or {}),raise_on_failure=True,
            delivery_id=str(task_id))
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


async def process_task(task_id: UUID) -> bool:
    now = utc_now()
    ttl = max(2, settings.task_lease_sec)
    token = uuid4().hex
    started = time.monotonic()
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
    limits={'ai_turn':max(1,settings.task_ai_concurrency),'business_callback':max(1,settings.task_callback_concurrency),
            'recording':max(1,settings.task_recording_concurrency)}
    sems={key:asyncio.Semaphore(value) for key,value in limits.items()}
    async def execute(row):
        task_id,kind=row
        async with sems[kind if kind in sems else 'recording']:
            return await asyncio.to_thread(process_task_sync,task_id) if threaded else await process_task(task_id)
    return sum(await asyncio.gather(*(execute(row) for row in rows)))


async def notify_task(task_id):
    # Compatibility mode is explicit and forbidden in production. Normal API
    # requests do no AI, recording or customer callback work after committing.
    if settings.task_inline_execution_enabled and settings.env.lower() not in {'prod','production'}:
        return await process_task(task_id)
    return None
