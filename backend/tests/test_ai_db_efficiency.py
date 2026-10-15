from sqlalchemy import event, update, text
from sqlmodel import select
from test_production_hardening import client, _review_call
from app.db import engine, session_scope
from app.models import CallSession, CallStatus, RealtimeSession
from app.services import dispatcher, db_work_observation
import pytest


def test_current_call_refreshes_cached_object_without_flushing_stale_changes(client):
    cid = _review_call()
    with session_scope() as stale:
        cached = stale.get(CallSession, cid)
        with session_scope() as fresh:
            fresh.exec(update(CallSession).where(CallSession.id == cid).values(status=CallStatus.COMPLETED))
            fresh.commit()
        cached.status = CallStatus.ANSWERED
        assert dispatcher._load_current_ai_call(stale, cid, 1, lock=True) is None
        assert cached.status == CallStatus.COMPLETED
    with session_scope() as fresh:
        assert fresh.get(CallSession, cid).status == CallStatus.COMPLETED


def test_current_call_reads_once_and_keeps_attempt_guard(client):
    cid = _review_call()
    queries = []
    def record(conn, cursor, statement, *args):
        if statement.lstrip().upper().startswith('SELECT'):
            queries.append(statement)
    event.listen(engine, 'before_cursor_execute', record)
    try:
        with session_scope() as s:
            assert dispatcher._load_current_ai_call(s, cid, 1, lock=True).id == cid
        assert len(queries) == 1
        with session_scope() as s:
            assert dispatcher._load_current_ai_call(s, cid, 2, lock=True) is None
    finally:
        event.remove(engine, 'before_cursor_execute', record)


def test_failed_sql_is_observed_and_context_does_not_escape(client):
    stats = dict(sql_ms=0., sql_count=0, sql_error_count=0)
    token = db_work_observation.current.set(stats)
    try:
        with pytest.raises(Exception):
            with session_scope() as s:
                s.execute(text('SELECT * FROM nonexistent_stability_test_table'))
    finally:
        db_work_observation.current.reset(token)
    assert stats['sql_count'] == 1 and stats['sql_error_count'] == 1
    assert stats['sql_ms'] >= 0
    with session_scope() as s:s.execute(text('SELECT 1'))
    assert stats['sql_count'] == 1


def test_current_turn_refreshes_cached_realtime_state(client):
    cid = _review_call()
    with session_scope() as s:
        s.add(RealtimeSession(tenant_id=1, call_session_id=cid, turn_sequence=1));s.commit()
    with session_scope() as stale:
        realtime = stale.exec(select(RealtimeSession).where(RealtimeSession.call_session_id == cid)).one()
        with session_scope() as fresh:
            fresh.exec(update(RealtimeSession).where(RealtimeSession.call_session_id == cid).values(turn_sequence=2));fresh.commit()
        token = dispatcher._expected_turn_sequence.set(1)
        try:
            assert dispatcher._load_current_ai_call(stale, cid, 1, lock=True) is None
            assert realtime.turn_sequence == 2
        finally:
            dispatcher._expected_turn_sequence.reset(token)


def test_sampled_work_trace_contains_timings_but_not_payload_or_lease(monkeypatch):
    import asyncio
    import json
    from uuid import uuid4
    from app.services import async_ai, ai_claim_state
    monkeypatch.setattr(async_ai.settings, 'stability_trace_sample_every', 1)
    cid = uuid4()
    async def run():
        pool = async_ai.WorkPool(1)
        token = ai_claim_state.current_claim.set((17, 'private-lease'))
        def observe(snapshot):return 42
        try:
            assert await pool.run(observe, dict(call_id=cid, phone='private-phone')) == 42
            traces = pool.pressure_snapshot()['recent_traces']
            assert len(traces) == 1 and traces[0]['call_id'] == str(cid)
            assert traces[0]['queue_ms'] >= 0 and traces[0]['execute_ms'] >= 0
            assert 'private-phone' not in json.dumps(traces) and 'private-lease' not in json.dumps(traces)
        finally:
            ai_claim_state.current_claim.reset(token)
            await pool.close()
    asyncio.run(run())
