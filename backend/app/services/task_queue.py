from __future__ import annotations

import hashlib
import json
import logging
from datetime import timedelta
from uuid import UUID

from sqlalchemy import or_, update
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ..clock import utc_now
from ..db import session_scope
from ..models import CallSession, CallStatus, RecordingAsset, TaskOutbox, TaskState

logger = logging.getLogger(__name__)


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
    existing = session.exec(select(TaskOutbox).where(TaskOutbox.idempotency_key == idempotency_key)).first()
    if existing is not None:
        if revive_dead and existing.state == TaskState.DEAD:
            existing.state = TaskState.PENDING
            existing.attempts = 0
            existing.max_attempts = max(1, max_attempts)
            existing.available_at = utc_now()
            existing.locked_at = None
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


async def process_task(task_id: UUID) -> bool:
    now = utc_now()
    stale_cutoff = now - timedelta(minutes=5)
    with session_scope() as session:
        result = session.execute(
            update(TaskOutbox)
            .where(
                TaskOutbox.id == task_id,
                TaskOutbox.attempts < TaskOutbox.max_attempts,
                or_(
                    TaskOutbox.state.in_([TaskState.PENDING, TaskState.FAILED]),
                    (TaskOutbox.state == TaskState.PROCESSING) & (TaskOutbox.locked_at <= stale_cutoff),
                ),
                TaskOutbox.available_at <= now,
            )
            .values(
                state=TaskState.PROCESSING,
                attempts=TaskOutbox.attempts + 1,
                locked_at=now,
                updated_at=now,
            )
        )
        if result.rowcount != 1:
            session.rollback()
            return False
        session.commit()
        task = session.get(TaskOutbox, task_id)
        if task is None:
            return False
        task_type = task.task_type
        payload = json.loads(task.payload_json or "{}")
    try:
        if task_type == "ai_turn":
            from .dispatcher import run_ai_turn

            await run_ai_turn(
                call_id=UUID(str(payload["call_id"])),
                transcript=str(payload.get("transcript") or ""),
                durable=True,
            )
        elif task_type == "business_callback":
            from .business_callbacks import deliver_business_callback

            await deliver_business_callback(
                tenant_id=int(payload["tenant_id"]),
                call_id=UUID(str(payload["call_id"])),
                event_type=str(payload["event_type"]),
                data=dict(payload.get("data") or {}),
                raise_on_failure=True,
            )
        elif task_type == "recording_delete":
            from .recording_storage import delete_recording_asset

            with session_scope() as session:
                asset = session.get(RecordingAsset, int(payload["recording_asset_id"]))
                if asset is None or asset.deleted_at is not None:
                    asset = None
            if asset is not None:
                delete_recording_asset(asset)
                with session_scope() as session:
                    persisted = session.get(RecordingAsset, asset.id)
                    if persisted is not None and persisted.deleted_at is None:
                        persisted.provider_url = ""
                        persisted.storage_uri = ""
                        persisted.state = "deleted"
                        persisted.deleted_at = utc_now()
                        persisted.updated_at = utc_now()
                        session.add(persisted)
                        session.commit()
        elif task_type == "recording_ingest":
            from .recording_storage import ingest_recording_asset

            with session_scope() as session:
                asset = session.get(RecordingAsset, int(payload["recording_asset_id"]))
                if asset is None or asset.deleted_at is not None or asset.storage_uri:
                    asset = None
            if asset is not None:
                result = ingest_recording_asset(asset)
                with session_scope() as session:
                    persisted = session.get(RecordingAsset, asset.id)
                    if persisted is not None and persisted.deleted_at is None:
                        persisted.storage_uri = result["storage_uri"]
                        persisted.checksum_sha256 = result.get("checksum_sha256") or persisted.checksum_sha256
                        persisted.state = "stored"
                        persisted.updated_at = utc_now()
                        session.add(persisted)
                        session.commit()
        else:
            raise RuntimeError(f"unsupported durable task type: {task_type}")
    except Exception as exc:
        logger.exception("durable task failed: %s", task_id)
        with session_scope() as session:
            task = session.get(TaskOutbox, task_id)
            if task is not None:
                task.state = TaskState.DEAD if task.attempts >= task.max_attempts else TaskState.FAILED
                task.available_at = utc_now() + timedelta(seconds=min(300, 2 ** task.attempts))
                task.locked_at = None
                task.last_error = str(exc)[:2000]
                task.updated_at = utc_now()
                session.add(task)
                if task.state == TaskState.DEAD and task.task_type == "ai_turn":
                    call = session.get(CallSession, UUID(task.aggregate_id))
                    if call is not None:
                        call.status = CallStatus.FAILED
                        call.last_error = f"AI durable task exhausted retries: {task.last_error}"
                        call.updated_at = utc_now()
                        session.add(call)
                if task.state == TaskState.DEAD and task.task_type == "recording_delete":
                    asset = session.get(RecordingAsset, int(task.aggregate_id))
                    if asset is not None and asset.deleted_at is None:
                        asset.state = "deletion_failed"
                        asset.updated_at = utc_now()
                        session.add(asset)
                if task.state == TaskState.DEAD and task.task_type == "recording_ingest":
                    asset = session.get(RecordingAsset, int(task.aggregate_id))
                    if asset is not None and asset.deleted_at is None and not asset.storage_uri:
                        asset.state = "ingestion_failed"
                        asset.updated_at = utc_now()
                        session.add(asset)
                session.commit()
        return False
    with session_scope() as session:
        task = session.get(TaskOutbox, task_id)
        if task is not None:
            task.state = TaskState.COMPLETED
            task.locked_at = None
            task.last_error = ""
            task.updated_at = utc_now()
            session.add(task)
            session.commit()
    return True


async def process_pending_tasks(*, batch_size: int = 100) -> int:
    now = utc_now()
    stale_cutoff = now - timedelta(minutes=5)
    with session_scope() as session:
        exhausted = session.exec(
            select(TaskOutbox).where(
                TaskOutbox.state == TaskState.PROCESSING,
                TaskOutbox.locked_at <= stale_cutoff,
                TaskOutbox.attempts >= TaskOutbox.max_attempts,
            )
        ).all()
        for task in exhausted:
            task.state = TaskState.DEAD
            task.locked_at = None
            task.last_error = task.last_error or "worker stopped during final task attempt"
            task.updated_at = now
            session.add(task)
            if task.task_type == "ai_turn":
                call = session.get(CallSession, UUID(task.aggregate_id))
                if call is not None:
                    call.status = CallStatus.FAILED
                    call.last_error = f"AI durable task exhausted retries: {task.last_error}"
                    call.updated_at = now
                    session.add(call)
            if task.task_type == "recording_delete":
                asset = session.get(RecordingAsset, int(task.aggregate_id))
                if asset is not None and asset.deleted_at is None:
                    asset.state = "deletion_failed"
                    asset.updated_at = now
                    session.add(asset)
            if task.task_type == "recording_ingest":
                asset = session.get(RecordingAsset, int(task.aggregate_id))
                if asset is not None and asset.deleted_at is None and not asset.storage_uri:
                    asset.state = "ingestion_failed"
                    asset.updated_at = now
                    session.add(asset)
        if exhausted:
            session.commit()
        ids = session.exec(
            select(TaskOutbox.id)
            .where(
                or_(
                    TaskOutbox.state.in_([TaskState.PENDING, TaskState.FAILED]),
                    (TaskOutbox.state == TaskState.PROCESSING) & (TaskOutbox.locked_at <= stale_cutoff),
                ),
                TaskOutbox.available_at <= now,
                TaskOutbox.attempts < TaskOutbox.max_attempts,
            )
            .order_by(TaskOutbox.available_at.asc())
            .limit(max(1, batch_size))
        ).all()
    processed = 0
    for task_id in ids:
        if await process_task(task_id):
            processed += 1
    return processed
