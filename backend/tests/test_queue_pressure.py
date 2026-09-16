"""Regressions for bounded DB scheduling and callback lock contention."""
import asyncio
import time
from uuid import uuid4

import pytest
from sqlalchemy import event, delete, update
from sqlmodel import select

from test_production_hardening import client, _review_call
from app.db import engine, session_scope
from app.models import CallSession, CallStatus, TaskOutbox, TaskState, CallbackInbox, CallbackInboxPartition
from app.services import ai_liveness, task_queue, callback_inbox


def test_ai_lane_coalesces_slots_freed_by_individual_completions(monkeypatch, tmp_path):
    from app.services import async_ai
    async def run():
        stop = asyncio.Event()
        polls = []
        loop = asyncio.get_running_loop()
        def claim(kinds, available):
            polls.append(time.monotonic())
            if len(polls) == 1:
                return [(i, None) for i in range(3)]
            loop.call_soon_threadsafe(stop.set)
            return []
        async def process(task_id, *args):
            await asyncio.sleep(.005 * (task_id + 1))
        monkeypatch.setattr(async_ai, 'claim_ready_tasks', claim)
        monkeypatch.setattr(async_ai, 'process_ai_claim', process)
        monkeypatch.setattr(callback_inbox, 'prepare_handlers', lambda: None)
        monkeypatch.setattr(async_ai.settings, 'task_poll_interval_sec', .08)
        monkeypatch.setattr(async_ai.settings, 'ai_worker_health_path', str(tmp_path/'health.json'))
        await asyncio.wait_for(async_ai.run_async_ai_lane(stop, concurrency=4), 3)
        assert len(polls) == 2
        assert polls[1] - polls[0] >= .079
    asyncio.run(run())


def test_failed_claim_poll_does_not_refresh_readiness(monkeypatch):
    from app.services import async_ai
    writes = []
    class HealthPath:
        def with_suffix(self, suffix): return self
        def write_text(self, data): writes.append(data)
        def replace(self, path): pass
        def unlink(self, **kwargs): pass
    async def run():
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        def claim(*args):
            loop.call_soon_threadsafe(stop.set)
            raise RuntimeError('database unavailable')
        monkeypatch.setattr(async_ai, 'claim_ready_tasks', claim)
        monkeypatch.setattr(async_ai, 'Path', lambda path: HealthPath())
        monkeypatch.setattr(callback_inbox, 'prepare_handlers', lambda: None)
        await asyncio.wait_for(async_ai.run_async_ai_lane(stop, concurrency=4), 3)
        assert writes == []
    asyncio.run(run())


def test_liveness_stays_single_flight_while_db_is_queued(monkeypatch):
    async def run():
        release = asyncio.Event()
        started = asyncio.Event()
        sizes = []
        class Pool:
            async def run(self, function, checks):
                sizes.append(len(checks))
                started.set()
                await release.wait()
                return [True] * len(checks)
        batcher = ai_liveness.LivenessBatcher(Pool())
        snapshot = dict(call_id=uuid4(), attempt=1)
        jobs = [asyncio.create_task(batcher.current(snapshot, 1))]
        await started.wait()
        # Arrivals spread beyond the coalescing window must not enqueue
        # additional DB work until the first query has finished.
        for _ in range(5):
            jobs.extend(asyncio.create_task(batcher.current(snapshot, 1)) for _ in range(6))
            await asyncio.sleep(.01)
        jobs[-1].cancel()
        assert sizes == [1]
        release.set()
        results = await asyncio.gather(*jobs, return_exceptions=True)
        assert results[:-1] == [True] * 30
        assert isinstance(results[-1], asyncio.CancelledError)
        assert sizes == [1, 29]
        assert batcher.task is None
    asyncio.run(run())


def test_liveness_error_does_not_strand_next_batch():
    async def run():
        class Pool:
            failed = False
            async def run(self, function, checks):
                if not self.failed:
                    self.failed = True
                    raise RuntimeError('database unavailable')
                return [False] * len(checks)
        batcher = ai_liveness.LivenessBatcher(Pool())
        snapshot = dict(call_id=uuid4(), attempt=1)
        with pytest.raises(RuntimeError):
            await batcher.current(snapshot, 1)
        assert not await batcher.current(snapshot, 1)
    asyncio.run(run())


def test_claim_batch_uses_one_update_and_unique_tokens(client):
    if engine.dialect.name != 'postgresql':
        pytest.skip('requires PostgreSQL batch claim path')
    kind = 'pressure_' + uuid4().hex
    with session_scope() as session:
        tasks = [TaskOutbox(tenant_id=1, task_type=kind, aggregate_id=str(i),
            idempotency_key=uuid4().hex, payload_json='{}') for i in range(40)]
        session.add_all(tasks)
        session.commit()
        ids = {task.id for task in tasks}
    updates = []
    def observe(conn, cursor, statement, *args):
        if statement.lstrip().upper().startswith('UPDATE TASKOUTBOX'):
            updates.append(statement)
    event.listen(engine, 'before_cursor_execute', observe)
    try:
        assert task_queue.claim_ready_tasks((kind,), 0) == []
        claims = task_queue.claim_ready_tasks((kind,), 40)
        assert len(updates) == 1
        assert {tid for tid, _ in claims} == ids
        assert len({claim[0] for _, claim in claims}) == 40
        with session_scope() as session:
            for tid, claim in claims:
                task = session.get(TaskOutbox, tid)
                assert task.state == TaskState.PROCESSING and task.attempts == 1
                assert task.lease_token == claim[0]
        assert task_queue.claim_ready_tasks((kind,), 40) == []
    finally:
        event.remove(engine, 'before_cursor_execute', observe)
        with session_scope() as session:
            session.execute(delete(TaskOutbox).where(TaskOutbox.id.in_(ids)))
            session.commit()


def test_busy_call_does_not_block_neighbor_and_keeps_its_fifo(client, monkeypatch):
    if engine.dialect.name != 'postgresql':
        pytest.skip('requires real PostgreSQL SKIP LOCKED')
    busy, free = [_review_call(CallStatus.IN_AI) for _ in range(2)]
    partition = 0
    seen = []
    with session_scope() as session:
        receipts = [CallbackInbox(receipt_key=uuid4().hex, partition_id=partition,
            call_id=cid, kind='telephony_speech', body_json='{}', body_digest='test', body_bytes=2)
            for cid in (busy, free, busy)]
        session.add_all(receipts)
        session.execute(update(CallbackInboxPartition).where(CallbackInboxPartition.id == partition)
            .values(pending_count=3, pending_bytes=6))
        session.commit()
        ids = [receipt.id for receipt in receipts]
    def apply(session, receipt):
        seen.append(receipt.id)
        call = session.get(CallSession, receipt.call_id)
        call.last_transcript = str(receipt.id)
        session.add(call)
    monkeypatch.setattr(callback_inbox, 'apply_receipt', apply)
    monkeypatch.setattr(callback_inbox.settings, 'callback_inbox_batch_budget_ms', 1000)
    monkeypatch.setattr(callback_inbox.settings, 'callback_inbox_batch_size', 32)
    monkeypatch.setattr(callback_inbox, '_batch_limits', {})
    try:
        with session_scope() as blocker:
            blocker.exec(select(CallSession).where(CallSession.id == busy).with_for_update()).one()
            assert callback_inbox.consume_partition(partition) == 1
            assert seen == [ids[1]]
            assert callback_inbox.consume_partition(partition) == 0
            with session_scope() as observer:
                assert observer.get(CallbackInboxPartition, partition).pending_count == 2
                assert observer.get(CallbackInbox, ids[0]).attempts == 0
                assert observer.get(CallbackInbox, ids[2]).state == 'pending'
            blocker.rollback()
        assert callback_inbox.consume_partition(partition) == 2
        assert seen == [ids[1], ids[0], ids[2]]
        with session_scope() as session:
            part = session.get(CallbackInboxPartition, partition)
            assert (part.pending_count, part.pending_bytes) == (0, 0)
            assert session.get(CallSession, busy).last_transcript == str(ids[2])
    finally:
        with session_scope() as session:
            session.execute(delete(CallbackInbox).where(CallbackInbox.id.in_(ids)))
            session.execute(update(CallbackInboxPartition).where(CallbackInboxPartition.id == partition)
                .values(pending_count=0, pending_bytes=0))
            session.commit()
