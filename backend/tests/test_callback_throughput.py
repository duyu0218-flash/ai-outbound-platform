"""Transaction and real PostgreSQL lock regressions from mixed callback load."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import threading
import time

import pytest
from sqlalchemy import event, text
from sqlmodel import select

from test_production_hardening import client, _review_call
from app.db import engine, session_scope, WebhookSession
from app.models import CallSession, CallStatus, CallMetric
from app.schemas import AiTurnResult
from app.services import dispatcher
from app.services.conversation_policy import state_for


def test_service_commit_reuses_locked_state_but_outer_failure_rolls_back(client):
    cid = _review_call(CallStatus.IN_AI)
    statements = []
    def observe(conn, cursor, statement, *args):
        if statement.lstrip().upper().startswith('SELECT') and 'FROM callsession' in statement:
            statements.append(statement)
    session = WebhookSession()
    event.listen(engine, 'before_cursor_execute', observe)
    try:
        call = session.exec(select(CallSession).where(CallSession.id == cid).with_for_update()).one()
        original = call.last_transcript
        for i in range(3):
            call.last_transcript = f'transaction-{i}'
            session.add(call)
            session.commit()  # service boundary, not a durable event ACK
            assert call.last_transcript == f'transaction-{i}'
        assert len(statements) == 1, 'service boundaries must not reread the locked call'
    finally:
        session.finish(success=False)
        event.remove(engine, 'before_cursor_execute', observe)
    with session_scope() as independent:
        assert independent.get(CallSession, cid).last_transcript == original


def test_ai_result_waits_for_call_before_locking_conversation(client, monkeypatch):
    if engine.dialect.name != 'postgresql':
        pytest.skip('requires real PostgreSQL row locks')
    cid = _review_call(CallStatus.IN_AI)
    with session_scope() as session:
        call = session.get(CallSession, cid)
        flow = call.flow_node_key
        state = state_for(session, call)
        state_id = state.id
        session.commit()
    ready = threading.Event()
    pid = []
    @contextmanager
    def observed_scope():
        with session_scope() as session:
            pid.append(session.execute(text('SELECT pg_backend_pid()')).scalar_one())
            ready.set()
            yield session
    monkeypatch.setattr(dispatcher, 'session_scope', observed_scope)
    async def no_action(**kwargs):
        pass
    monkeypatch.setattr(dispatcher, '_apply_ai_action', no_action)
    snapshot = dict(call_id=cid, attempt=1, flow_node_key=flow, ai_config={},
                    provider='synthetic', started=time.perf_counter(), knowledge_count=0)
    with ThreadPoolExecutor(max_workers=1) as pool, session_scope() as blocker:
        blocker.exec(select(CallSession).where(CallSession.id == cid).with_for_update()).one()
        future = pool.submit(lambda: asyncio.run(dispatcher._finish_ai_turn(snapshot, AiTurnResult(action='continue'))))
        try:
            assert ready.wait(3)
            deadline = time.monotonic() + 3
            with engine.connect().execution_options(isolation_level='AUTOCOMMIT') as observer:
                while time.monotonic() < deadline:
                    row = observer.execute(text('SELECT wait_event_type, query FROM pg_stat_activity WHERE pid=:pid'),
                                           {'pid':pid[0]}).one()
                    if row[0] == 'Lock':
                        break
                    time.sleep(.01)
                else:
                    pytest.fail('AI result did not reach the held row lock')
            assert 'callsession' in row[1] and 'FOR UPDATE' in row[1].upper()
            # Old code held this row and waited on the metric FK call lock.
            blocker.execute(text('SELECT id FROM conversationstate WHERE id=:id FOR UPDATE NOWAIT'),
                            {'id':state_id}).one()
        finally:
            blocker.rollback()
        future.result(timeout=5)
    with session_scope() as session:
        assert session.exec(select(CallMetric).where(CallMetric.call_session_id == cid,
                                                    CallMetric.stage == 'ai.turn')).one().success
