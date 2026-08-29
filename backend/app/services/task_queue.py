from __future__ import annotations

import json
import logging
from datetime import timedelta
from uuid import UUID

from sqlalchemy import or_, update
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ..clock import utc_now
from ..db import session_scope
from ..models import CallSession, CallStatus, TaskOutbox, TaskState

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
) -> TaskOutbox:
    existing = session.exec(select(TaskOutbox).where(TaskOutbox.idempotency_key == idempotency_key)).first()
    if existing is not None:
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
            .values(state=TaskState.PROCESSING, locked_at=now, updated_at=now)
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
        else:
            raise RuntimeError(f"unsupported durable task type: {task_type}")
    except Exception as exc:
        logger.exception("durable task failed: %s", task_id)
        with session_scope() as session:
            task = session.get(TaskOutbox, task_id)
            if task is not None:
                task.attempts += 1
                task.state = TaskState.DEAD if task.attempts >= task.max_attempts else TaskState.FAILED
                task.available_at = utc_now() + timedelta(seconds=min(300, 2 ** task.attempts))
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
    with session_scope() as session:
        ids = session.exec(
            select(TaskOutbox.id)
            .where(
                TaskOutbox.state.in_([TaskState.PENDING, TaskState.FAILED]),
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
