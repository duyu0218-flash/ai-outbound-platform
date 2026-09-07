"""Regression tests for the connection/thread starvation and timeout boundaries."""
import asyncio
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest
from fastapi import Depends, FastAPI
from sqlalchemy import event, text

from test_production_hardening import client, reset_runtime_settings_after_test  # noqa: F401
from app import db
from app.config import get_settings
from app.middleware import AdmissionControlMiddleware, TimeoutMiddleware
from app.services.runtime_metrics import snapshot


@pytest.mark.asyncio
async def test_static_chunks_have_a_separate_bounded_budget(monkeypatch):
    cfg = get_settings()
    monkeypatch.setattr(cfg, 'request_admission_total_inflight', 1)
    monkeypatch.setattr(cfg, 'request_admission_default_inflight', 1)
    monkeypatch.setattr(cfg, 'request_admission_static_inflight', 8)
    monkeypatch.setattr(cfg, 'request_admission_max_waiters', 0)
    entered, release = asyncio.Event(), asyncio.Event()
    app = FastAPI()
    @app.get('/control')
    async def control():
        entered.set(); await release.wait(); return {}
    @app.get('/assets/{name}')
    async def chunk(name):
        await asyncio.sleep(.01); return {'chunk': name}
    app.add_middleware(AdmissionControlMiddleware)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url='http://test') as http:
        busy = asyncio.create_task(http.get('/control'))
        await entered.wait()
        results = await asyncio.gather(*(http.get(f'/assets/{i}.js') for i in range(8)))
        assert all(response.status_code == 200 for response in results)
        assert (await http.get('/control')).status_code == 503
        release.set(); assert (await busy).status_code == 200


def test_all_session_dependencies_are_lazy(client):
    before = snapshot()['db_checked_out']
    for dependency in (db.get_session, db.get_webhook_session):
        dep = dependency()
        next(dep)
        assert snapshot()['db_checked_out'] == before
        dep.close()


def test_webhook_transaction_connection_is_owned_by_route_thread(client):
    seen = []
    def checkout(*args): seen.append(('checkout', threading.get_ident()))
    def checkin(*args): seen.append(('checkin', threading.get_ident()))
    def commit(*args): seen.append(('commit', threading.get_ident()))
    event.listen(db.engine, 'checkout', checkout)
    event.listen(db.engine, 'checkin', checkin)
    event.listen(db.engine, 'commit', commit)
    dependency = db.get_webhook_session()
    session = next(dependency)
    @db.webhook_transaction
    def route(*, session):
        seen.append(('route', threading.get_ident()))
        session.execute(text('SELECT 1'))
        session.commit()
        return 'ok'
    try:
        with ThreadPoolExecutor(max_workers=1) as executor:
            assert executor.submit(route, session=session).result() == 'ok'
        dependency.close()
        assert {kind for kind, _ in seen} == {'checkout', 'route', 'commit', 'checkin'}
        assert len({tid for _, tid in seen}) == 1
        assert seen[-1][0] == 'checkin'
    finally:
        event.remove(db.engine, 'checkout', checkout)
        event.remove(db.engine, 'checkin', checkin)
        event.remove(db.engine, 'commit', commit)


@pytest.mark.asyncio
async def test_default_and_webhook_share_total_budget_and_recover(monkeypatch):
    cfg = get_settings()
    monkeypatch.setattr(cfg, 'request_admission_total_inflight', 1)
    monkeypatch.setattr(cfg, 'request_admission_max_waiters', 0)
    started, release = asyncio.Event(), asyncio.Event()
    entered = []
    app = FastAPI()
    @app.get('/control')
    async def control():
        started.set()
        await release.wait()
        return {}
    @app.get('/api/v1/webhooks/probe')
    async def webhook():
        entered.append(True)
        return {}
    app.add_middleware(AdmissionControlMiddleware)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url='http://test') as http:
        first = asyncio.create_task(http.get('/control'))
        await started.wait()
        before = snapshot()['admission_rejections'].get('webhook:capacity', 0)
        responses = await asyncio.gather(*(http.get('/api/v1/webhooks/probe') for _ in range(50)))
        assert all(r.status_code == 503 and r.headers['Retry-After'] == '1' for r in responses)
        assert not entered
        assert snapshot()['admission_rejections']['webhook:capacity'] - before == 50
        release.set()
        assert (await first).status_code == 200
        assert (await http.get('/api/v1/webhooks/probe')).status_code == 200


@pytest.mark.asyncio
async def test_timeout_retains_slot_until_commit_and_cleanup(monkeypatch, client):
    cfg = get_settings()
    monkeypatch.setattr(cfg, 'request_timeout_ms', 20)
    monkeypatch.setattr(cfg, 'request_admission_total_inflight', 1)
    monkeypatch.setattr(cfg, 'request_admission_max_waiters', 0)
    entered, release = threading.Event(), threading.Event()
    before = snapshot()
    app = FastAPI()
    @app.get('/slow')
    @db.webhook_transaction
    def slow(session=Depends(db.get_webhook_session, scope='function')):
        session.execute(text('SELECT 1'))
        entered.set()
        assert release.wait(3)
        return {'ok': True}
    @app.get('/fast')
    async def fast(): return {}
    app.add_middleware(TimeoutMiddleware)
    app.add_middleware(AdmissionControlMiddleware)
    messages, deadline_seen = [], asyncio.Event()
    async def send(message):
        messages.append(message)
        if message['type'] == 'http.response.start' and message['status'] == 504:
            deadline_seen.set()
    async def receive():
        await asyncio.sleep(10)
        return {'type': 'http.disconnect'}
    scope = {'type':'http','asgi':{'version':'3.0'},'http_version':'1.1',
             'method':'GET','path':'/slow','raw_path':b'/slow','query_string':b'',
             'headers':[],'scheme':'http','server':('test',80),'client':('test',1)}
    first = asyncio.create_task(app(scope, receive, send))
    try:
        await asyncio.wait_for(deadline_seen.wait(), 2)
        assert entered.is_set()
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url='http://test') as http:
            assert (await http.get('/fast')).status_code == 503
            assert snapshot()['db_checked_out'] == before['db_checked_out'] + 1
            release.set()
            await first
            assert (await http.get('/fast')).status_code == 200
        assert [m['status'] for m in messages if m['type']=='http.response.start'] == [504]
        after = snapshot()
        assert after['late_commit_attempts'] == before['late_commit_attempts'] + 1
        assert after['db_checked_out'] == before['db_checked_out']
        assert after['execution_threads'] == before['execution_threads']
    finally:
        release.set()
        await first


def test_runtime_metrics_do_not_need_database(client, monkeypatch):
    monkeypatch.setattr(get_settings(), 'metrics_token', 'synthetic-test-token')
    monkeypatch.setattr(get_settings(), 'metrics_token_file', '')
    def unavailable(*args, **kwargs): raise AssertionError('metrics tried to acquire DB')
    with monkeypatch.context() as patch:
        patch.setattr(db.engine, 'connect', unavailable)
        assert client.get('/metrics/runtime').status_code == 401
        r = client.get('/metrics/runtime', headers={'Authorization':'Bearer synthetic-test-token'})
        assert r.status_code == 200
        assert 'ai_outbound_thread_tokens_borrowed' in r.text
        assert 'ai_outbound_db_pool_checked_out' in r.text


@pytest.mark.asyncio
async def test_admission_waiters_are_bounded_and_cancelled_waiter_releases_budget(monkeypatch):
    cfg = get_settings()
    monkeypatch.setattr(cfg, 'request_admission_total_inflight', 1)
    monkeypatch.setattr(cfg, 'request_admission_max_waiters', 1)
    monkeypatch.setattr(cfg, 'request_admission_timeout_sec', 1)
    entered, release = asyncio.Event(), asyncio.Event()
    async def inner(scope, receive, send):
        entered.set()
        await release.wait()
        await send({'type':'http.response.start','status':200,'headers':[]})
        await send({'type':'http.response.body','body':b'ok'})
    gate = AdmissionControlMiddleware(inner)
    scope={'type':'http','path':'/work'}
    async def receive(): return {'type':'http.disconnect'}
    messages=[]
    async def send(message): messages.append(message)
    first=asyncio.create_task(gate(scope,receive,send))
    await entered.wait()
    waiting=asyncio.create_task(gate(scope,receive,send))
    for _ in range(100):
        if gate.waiting==1:break
        await asyncio.sleep(.001)
    assert gate.waiting==1
    await gate(scope,receive,send)
    assert messages[0]['status']==503
    waiting.cancel()
    with pytest.raises(asyncio.CancelledError):await waiting
    assert gate.waiting==0 and gate.total==1
    release.set();await first
    assert gate.total==0
    await gate(scope,receive,send)
    assert gate.total==0


def test_pool_timeout_and_checkin_metrics_reconcile():
    from sqlalchemy import create_engine
    from sqlalchemy.exc import TimeoutError
    measured=create_engine('sqlite://',poolclass=db.ObservedQueuePool,pool_size=1,max_overflow=0,pool_timeout=.02)
    before=snapshot()
    try:
        with measured.connect():
            with pytest.raises(TimeoutError):
                measured.connect()
        after=snapshot()
        assert after['db_checkout_failures']==before['db_checkout_failures']+1
        assert after['db_checkout_wait_records']==before['db_checkout_wait_records']+2
        assert after['db_checked_out']==before['db_checked_out']
    finally:
        measured.dispose()


@pytest.mark.asyncio
async def test_stream_connections_do_not_exhaust_control_request_budget(monkeypatch):
    cfg=get_settings()
    monkeypatch.setattr(cfg,'request_admission_total_inflight',1)
    monkeypatch.setattr(cfg,'request_admission_stream_inflight',1)
    monkeypatch.setattr(cfg,'request_admission_max_waiters',0)
    entered,release=asyncio.Event(),asyncio.Event()
    app=FastAPI()
    @app.get('/api/v1/agent/events/stream')
    async def stream():
        entered.set();await release.wait();return {}
    @app.get('/control')
    async def control():return {}
    app.add_middleware(AdmissionControlMiddleware)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app),base_url='http://test') as http:
        first=asyncio.create_task(http.get('/api/v1/agent/events/stream'))
        await entered.wait()
        assert (await http.get('/control')).status_code==200
        assert (await http.get('/api/v1/agent/events/stream')).status_code==503
        release.set();assert (await first).status_code==200


@pytest.mark.asyncio
async def test_agent_stream_releases_auth_connection_and_bounds_snapshot_work(client):
    from types import SimpleNamespace
    from app.api.routers.webrtc import stream_agent_events
    from app.models import User
    from sqlmodel import select
    before=snapshot()['db_checked_out']
    with db.session_scope() as session:
        user=session.exec(select(User).where(User.role=='agent')).first()
        assert user is not None
        assert snapshot()['db_checked_out']==before+1
        request=SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(agent_snapshot_slots=asyncio.Semaphore(2))))
        response=await stream_agent_events(request,current=user,session=session)
        assert response.media_type=='application/x-ndjson'
        assert snapshot()['db_checked_out']==before
        await response.body_iterator.aclose()
