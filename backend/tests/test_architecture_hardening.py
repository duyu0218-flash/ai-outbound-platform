from __future__ import annotations

import asyncio
import json
from datetime import timedelta
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from fastapi import HTTPException

from test_production_hardening import (client, reset_runtime_settings_after_test,
    cleanup_review_tasks, _review_call_ids)  # noqa: F401
from app.clock import utc_now
from app.db import session_scope
from app.models import CallSession, CallStatus, CallMode, TaskOutbox, TaskState, Tenant
from app.schemas import AiTurnResult
from app.services import dispatcher, task_queue, business_callbacks, retention
from app.api.routers import admin_management


@pytest.fixture(scope="module", autouse=True)
def _ensure_architecture_db():
    from app.db import create_db_and_tables

    create_db_and_tables()
    with session_scope() as session:
        tenant = session.get(Tenant, 1)
        if tenant is None:
            session.add(
                Tenant(
                    id=1,
                    name="Default Tenant",
                    code="default",
                    enabled=True,
                )
            )
            session.commit()
    yield


def make_call(status=CallStatus.IN_AI, **kwargs):
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone='13800000000', mode=CallMode.AI_ONLY,
                           status=status, attempts=1, **kwargs)
        session.add(call)
        session.commit()
        session.refresh(call)
        _review_call_ids.append(call.id)
        return call.id


def make_task(call_id, task_type='ai_turn', max_attempts=5):
    with session_scope() as session:
        task = task_queue.enqueue_task(session, tenant_id=1, task_type=task_type,
            aggregate_id=str(call_id), idempotency_key=f'architecture:{uuid4()}', max_attempts=max_attempts,
            payload={'call_id':str(call_id), 'attempt':1, 'transcript':'old attempt text',
                     'tenant_id':1,'event_type':'synthetic','data':{}})
        return task.id


def test_untrusted_tenant_service_urls_rejected(client, monkeypatch):
    monkeypatch.setattr(admin_management.settings, 'env', 'production')
    for section, value in [('ai', {'agent_url':'https://untrusted.invalid'}),
                           ('sms', {'provider':'http','endpoint':'https://untrusted.invalid'})]:
        with pytest.raises(HTTPException):
            admin_management._validated_setting(section, value)


@pytest.mark.asyncio
async def test_runtime_does_not_send_credential_to_legacy_untrusted_url(client, monkeypatch):
    monkeypatch.setattr(dispatcher.settings, 'ai_agent_service_token', 'synthetic-service-token')
    client_mock = AsyncMock()
    monkeypatch.setattr(dispatcher.httpx, 'AsyncClient', client_mock)
    with pytest.raises((ValueError, RuntimeError)):
        await dispatcher.request_ai_turn(call_id=str(uuid4()),phone='13800000000',mode='ai_only',
                                         agent_url='https://untrusted.invalid')
    client_mock.assert_not_called()


@pytest.mark.asyncio
async def test_old_attempt_task_is_discarded_before_model(client, monkeypatch):
    call_id=make_call(); task_id=make_task(call_id)
    with session_scope() as session:
        call=session.get(CallSession,call_id); call.attempts=2
        session.add(call); session.commit()
    model=AsyncMock(return_value=AiTurnResult(action='continue'))
    monkeypatch.setattr(dispatcher,'request_ai_turn',model)
    monkeypatch.setattr(dispatcher,'_apply_ai_action',AsyncMock())
    await task_queue.process_task(task_id)
    model.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize('initial',[CallStatus.IN_AI,CallStatus.COMPLETED,CallStatus.IN_HUMAN])
async def test_dead_ai_task_preserves_call_and_capacity(client,initial):
    call_id=make_call(initial); task_id=make_task(call_id,max_attempts=1)
    with session_scope() as session:
        task=session.get(TaskOutbox,task_id); task.state=TaskState.PROCESSING
        task.attempts=1; task.locked_at=utc_now()-timedelta(minutes=6)
        session.add(task); session.commit()
    await task_queue.process_pending_tasks(batch_size=1000)
    with session_scope() as session:
        assert session.get(CallSession,call_id).status==initial
        assert session.get(TaskOutbox,task_id).state==TaskState.DEAD


@pytest.mark.asyncio
async def test_old_task_owner_cannot_overwrite_reclaimed_success(client,monkeypatch):
    task_id=make_task(make_call(),task_type='business_callback')
    entered=asyncio.Event(); release=asyncio.Event(); calls=0
    async def deliver(**kwargs):
        nonlocal calls
        calls+=1
        if calls==1:
            entered.set(); await release.wait()
            raise RuntimeError('synthetic old owner failed late')
        return True
    monkeypatch.setattr(business_callbacks,'deliver_business_callback',deliver)
    first=asyncio.create_task(task_queue.process_task(task_id))
    await asyncio.wait_for(entered.wait(),5)
    try:
        with session_scope() as session:
            task=session.get(TaskOutbox,task_id); task.locked_at=utc_now()-timedelta(minutes=6)
            session.add(task); session.commit()
        assert await task_queue.process_task(task_id)
    finally:
        release.set(); await first
    with session_scope() as session:
        assert session.get(TaskOutbox,task_id).state==TaskState.COMPLETED


def test_retention_scrubs_task_payload_and_legacy_recording_url(client):
    call_id=make_call(CallStatus.COMPLETED,finished_at=utc_now()-timedelta(days=181),
                      summary='private text',recording_url='https://synthetic.invalid/token')
    task_id=make_task(call_id)
    with session_scope() as session:
        task=session.get(TaskOutbox,task_id); task.state=TaskState.COMPLETED
        session.add(task); session.commit()
    retention.purge_expired_voice_data(batch_size=1000)
    with session_scope() as session:
        call=session.get(CallSession,call_id); task=session.get(TaskOutbox,task_id)
        assert call.phone.startswith('redacted:')
        assert not call.recording_url
        assert 'old attempt text' not in task.payload_json


@pytest.mark.asyncio
async def test_callback_workers_have_bounded_parallelism(client,monkeypatch):
    # One slow customer must not force every other customer's callback to wait.
    ids=[make_task(make_call(),task_type='business_callback') for _ in range(3)]
    active=0; peak=0
    async def deliver(**kwargs):
        nonlocal active,peak
        active+=1; peak=max(peak,active)
        await asyncio.sleep(.05); active-=1
        return True
    monkeypatch.setattr(business_callbacks,'deliver_business_callback',deliver)
    await task_queue.process_pending_tasks(batch_size=1000)
    assert 1 < peak <= 4
    with session_scope() as session:
        assert all(session.get(TaskOutbox,i).state==TaskState.COMPLETED for i in ids)

@pytest.mark.asyncio
async def test_lease_loss_cancels_before_later_side_effect():
    import time
    from app.services.leases import monitored_lease, LeaseLost, assert_execution_permitted
    action = AsyncMock()
    with pytest.raises(LeaseLost):
        async with monitored_lease(AsyncMock(return_value=False), ttl=.3, initial_until=time.monotonic()+.3):
            await asyncio.sleep(.5)
            assert_execution_permitted()
            await action()
    action.assert_not_awaited()


@pytest.mark.asyncio
async def test_redis_lease_renews_and_excludes_second_owner(client):
    from app.services.leases import redis_lease
    from app.config import get_settings
    if not get_settings().redis_url:
        pytest.skip('requires isolated Redis')
    key=f'architecture-test:{uuid4()}'
    async with redis_lease(url=get_settings().redis_url,key=key,ttl=2) as acquired:
        assert acquired
        await asyncio.sleep(2.2)
        async with redis_lease(url=get_settings().redis_url,key=key,ttl=2) as second:
            assert not second
    async with redis_lease(url=get_settings().redis_url,key=key,ttl=2) as acquired:
        assert acquired


def test_simultaneous_same_phone_claim_respects_daily_cap(client,monkeypatch):
    import threading
    from concurrent.futures import ThreadPoolExecutor
    from app.services import call_service
    from app.services.admin_settings import SETTING_DEFAULTS
    from app.models import Tenant
    from app.db import engine
    if engine.dialect.name != 'postgresql':
        pytest.skip('requires isolated PostgreSQL row locking')
    cfg={**SETTING_DEFAULTS['compliance'],'allowed_start_hour':0,'allowed_end_hour':0,
         'require_explicit_consent_for_direct_calls':False,'max_attempts_per_day':1,'min_attempt_interval_sec':0}
    monkeypatch.setitem(SETTING_DEFAULTS,'compliance',cfg)
    with session_scope() as session:
        tenant=Tenant(name='Synthetic phone concurrency',code=f'architecture-{uuid4().hex}')
        session.add(tenant);session.commit();session.refresh(tenant)
        calls=[CallSession(tenant_id=tenant.id,phone='13800000000',mode=CallMode.AI_ONLY) for _ in range(2)]
        session.add_all(calls);session.commit()
        ids=[call.id for call in calls];_review_call_ids.extend(ids)
    barrier=threading.Barrier(2)
    def claim(call_id):
        with session_scope() as session:
            call=session.get(CallSession,call_id)
            barrier.wait(timeout=5)
            return call_service._claim_dispatch_slot(session,call)
    with ThreadPoolExecutor(max_workers=2) as pool:
        claimed=list(pool.map(claim,ids))
    assert sum(claimed)==1
    with session_scope() as session:
        assert sum(session.get(CallSession,i).attempts for i in ids)==1


@pytest.mark.asyncio
async def test_signed_webhooks_have_independent_control_capacity(client,monkeypatch):
    import hashlib,hmac,time,httpx
    from fastapi import FastAPI
    from app.middleware import RateLimitMiddleware
    from app.config import get_settings
    cfg=get_settings()
    for key,value in {'rate_limit_enabled':True,'redis_url':'','telephony_webhook_token':'synthetic-token',
                      'telephony_webhook_secret':'synthetic-secret','env':'development'}.items():
        monkeypatch.setattr(cfg,key,value)
    app=FastAPI()
    @app.post('/api/v1/webhooks/telephony/status')
    async def status(): return {'ok':True}
    app.add_middleware(RateLimitMiddleware)
    body=b'{}';stamp=str(int(time.time()))
    headers={'x-webhook-token':cfg.telephony_webhook_token,'x-webhook-timestamp':stamp,
             'x-webhook-signature':hmac.new(cfg.telephony_webhook_secret.encode(),stamp.encode()+b'.'+body,hashlib.sha256).hexdigest()}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://isolated') as http:
        for _ in range(601):
            assert (await http.post('/api/v1/webhooks/telephony/status',content=body,headers=headers)).status_code==200


@pytest.mark.asyncio
async def test_production_limiter_fails_closed_then_recovers(client,monkeypatch):
    from fastapi import FastAPI
    from app.middleware import RateLimitMiddleware
    from app.config import get_settings
    monkeypatch.setattr(get_settings(),'env','production')
    limiter=RateLimitMiddleware(FastAPI());limiter.enabled=True;limiter._redis=object()
    counter=AsyncMock(side_effect=[ConnectionError('synthetic outage'),True])
    monkeypatch.setattr(limiter,'_is_limit_ok_redis',counter)
    with pytest.raises(RuntimeError): await limiter._is_limit_ok('key',10)
    assert limiter._redis is not None
    limiter._redis_retry_at=0
    assert await limiter._is_limit_ok('key',10)


@pytest.mark.asyncio
async def test_callback_dns_private_rejected_and_public_pinned(client,monkeypatch):
    import socket,httpx
    from app.services.outbound_policy import CallbackTransport
    from app.config import get_settings
    monkeypatch.setattr(get_settings(),'business_callback_allowed_origins','https://customer.example')
    monkeypatch.setattr(get_settings(),'business_callback_private_origins','')
    loop=asyncio.get_running_loop()
    dns=AsyncMock(return_value=[(socket.AF_INET,socket.SOCK_STREAM,6,'',('127.0.0.1',443))])
    monkeypatch.setattr(loop,'getaddrinfo',dns)
    captured=[]
    async def send(self,request):
        captured.append((request.url.host,request.headers['Host'],request.extensions['sni_hostname']))
        return httpx.Response(200)
    monkeypatch.setattr(httpx.AsyncHTTPTransport,'handle_async_request',send)
    async with CallbackTransport() as transport:
        with pytest.raises(ValueError,match='unauthorized network'):
            await transport.handle_async_request(httpx.Request('POST','https://customer.example/events'))
        assert not captured
        dns.return_value=[(socket.AF_INET,socket.SOCK_STREAM,6,'',('93.184.216.34',443))]
        await transport.handle_async_request(httpx.Request('POST','https://customer.example/events'))
        assert captured==[('93.184.216.34','customer.example','customer.example')]


@pytest.mark.asyncio
async def test_api_notification_leaves_execution_to_worker(client,monkeypatch):
    monkeypatch.setattr(task_queue.settings,'task_inline_execution_enabled',False)
    execute=AsyncMock()
    monkeypatch.setattr(task_queue,'process_task',execute)
    await task_queue.notify_task(uuid4())
    execute.assert_not_awaited()


@pytest.mark.asyncio
async def test_long_task_renews_database_lease(client,monkeypatch):
    task_id=make_task(make_call(),task_type='business_callback')
    monkeypatch.setattr(task_queue.settings,'task_lease_sec',2)
    entered=asyncio.Event()
    async def delivery(**kwargs):
        entered.set();await asyncio.sleep(2.8);return True
    monkeypatch.setattr(business_callbacks,'deliver_business_callback',delivery)
    first=asyncio.create_task(task_queue.process_task(task_id))
    await entered.wait();await asyncio.sleep(2.2)
    assert not await task_queue.process_task(task_id)
    assert await first
    with session_scope() as session:
        row=session.get(TaskOutbox,task_id)
        assert row.attempts==1 and row.state==TaskState.COMPLETED


def test_metrics_show_queue_age_and_bounded_latency_samples(client):
    from app.models import CallMetric
    from app.services.metrics import render_prometheus_metrics
    call_id=make_call();task_id=make_task(call_id)
    with session_scope() as session:
        task=session.get(TaskOutbox,task_id);task.available_at=utc_now()-timedelta(seconds=10)
        session.add(task)
        session.add(CallMetric(tenant_id=1,call_session_id=call_id,stage='ai.turn',duration_ms=1700))
        session.commit()
        body=render_prometheus_metrics(session)
    assert 'ai_outbound_task_oldest_ready_seconds{type="ai_turn"}' in body
    assert 'ai_outbound_stage_recent_seconds{stage="ai.turn",quantile="0.95"}' in body


def test_slot_claim_does_not_change_a_call_already_in_progress(client,monkeypatch):
    from app.services import call_service
    call_id=make_call(CallStatus.DIALING,max_attempts=3)
    monkeypatch.setattr(call_service,'can_call_contact_sync',lambda *a,**k:(False,'synthetic new refusal'))
    with session_scope() as session:
        call=session.get(CallSession,call_id)
        assert not call_service._claim_dispatch_slot(session,call)
        session.refresh(call)
        assert call.status==CallStatus.DIALING and call.attempts==1


def test_webhook_dependency_does_not_reserve_connection_before_route(client,monkeypatch):
    from app import db
    connections=[]
    connect=db.engine.connect
    def tracked(*args,**kwargs):
        connections.append(True)
        return connect(*args,**kwargs)
    monkeypatch.setattr(db.engine,'connect',tracked)
    dependency=db.get_webhook_session()
    try:
        next(dependency)
        assert not connections, 'connection must be acquired by route work, not the dependency thread'
    finally:
        dependency.close()


@pytest.mark.asyncio
async def test_admission_control_rejects_when_inflight_budget_exhausted(monkeypatch):
    import httpx
    from fastapi import FastAPI

    from app.config import get_settings
    from app.middleware import AdmissionControlMiddleware, RequestIDMiddleware

    settings = get_settings()
    monkeypatch.setattr(settings, "request_admission_enabled", True)
    monkeypatch.setattr(settings, "request_admission_default_inflight", 1)
    monkeypatch.setattr(settings, "request_admission_webhook_inflight", 1)
    monkeypatch.setattr(settings, "request_admission_timeout_sec", 0.01)
    monkeypatch.setattr(settings, "request_admission_retry_after_sec", 1)

    started = asyncio.Event()
    released = asyncio.Event()

    app = FastAPI()
    app.add_middleware(RequestIDMiddleware)
    app.add_middleware(AdmissionControlMiddleware)

    @app.get("/runtime")
    async def runtime_blocking():
        started.set()
        await released.wait()
        return {"ok": True}

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://isolated") as http:
        first = asyncio.create_task(http.get("/runtime"))
        await asyncio.wait_for(started.wait(), 1)
        second = await http.get("/runtime")
        released.set()
        first_response = await first

    assert first_response.status_code == 200
    assert second.status_code == 503
    assert second.headers.get("Retry-After") == "1"
    assert "admission_limit_reached" in second.json().get("error", "")


@pytest.mark.asyncio
async def test_request_timeout_records_metric(monkeypatch):
    import httpx
    from fastapi import FastAPI

    from app.config import get_settings
    from app.middleware import TimeoutMiddleware
    from app.services.runtime_metrics import snapshot_for_metrics

    settings = get_settings()
    monkeypatch.setattr(settings, "request_timeout_ms", 10)
    monkeypatch.setattr(settings, "request_admission_enabled", False)

    before = sum(value for metric_type, metric, value in snapshot_for_metrics() if metric == "ai_outbound_request_timeout_all_total")

    app = FastAPI()
    app.add_middleware(TimeoutMiddleware)

    @app.get("/sleep")
    async def sleepy():
        await asyncio.sleep(0.05)
        return {"ok": True}

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://isolated") as http:
        response = await http.get("/sleep")

    after = sum(value for metric_type, metric, value in snapshot_for_metrics() if metric == "ai_outbound_request_timeout_all_total")
    assert response.status_code == 504
    assert int(after - before) >= 1
    assert response.headers.get("Retry-After", "")


def _runtime_metric_counter(name: str, bucket: str | None = None) -> int:
    from app.services.runtime_metrics import snapshot_for_metrics

    total = 0
    for metric_type, metric, value in snapshot_for_metrics():
        if metric.split("{", 1)[0] != name:
            continue
        if bucket is not None:
            if f'"{bucket}"' not in metric:
                continue
        total += int(value)
    return total


def test_duplicate_event_and_task_paths_increment_runtime_counters():
    from app.api.routers import webhooks
    from app.services.runtime_metrics import snapshot_for_metrics

    call_id = make_call()
    payload = {"status": "completed", "attempt": 1, "event_id": f"dup-{call_id}"}
    before_events = _runtime_metric_counter('ai_outbound_webhook_duplicate_events_total', 'status')
    before_tasks = _runtime_metric_counter('ai_outbound_outbox_duplicate_tasks_total', 'ai_turn')

    with session_scope() as session:
        first_call = session.get(CallSession, call_id)
        assert first_call is not None
        dup1 = webhooks._add_event(session, first_call.id, "status", "telephony", payload)
        dup2 = webhooks._add_event(session, first_call.id, "status", "telephony", payload)
        assert not dup1
        assert dup2
        task = task_queue.enqueue_task(
            session,
            tenant_id=1,
            task_type="ai_turn",
            aggregate_id=str(first_call.id),
            idempotency_key=f"dup-task-{first_call.id}",
            payload={"call_id": str(first_call.id), "attempt": 1, "transcript": "ok", "tenant_id": 1},
        )
        session.refresh(task)
        duplicate_task = task_queue.enqueue_task(
            session,
            tenant_id=1,
            task_type="ai_turn",
            aggregate_id=str(first_call.id),
            idempotency_key=f"dup-task-{first_call.id}",
            payload={"call_id": str(first_call.id), "attempt": 1, "transcript": "ok", "tenant_id": 1},
        )
        assert duplicate_task.id == task.id

    after_events = _runtime_metric_counter('ai_outbound_webhook_duplicate_events_total', 'status')
    after_tasks = _runtime_metric_counter('ai_outbound_outbox_duplicate_tasks_total', 'ai_turn')
    assert after_events > before_events
    assert after_tasks > before_tasks
