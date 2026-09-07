"""Behavioral regressions for the 2026-09-07 product review."""
import asyncio
from datetime import timedelta
from uuid import uuid4
from unittest.mock import patch

import pytest
from fastapi import BackgroundTasks
from sqlmodel import select

from test_production_hardening import client, reset_runtime_settings_after_test, _login, _bearer
from app.db import session_scope, create_db_and_tables, WebhookSession, engine
from app.clock import utc_now
from app.models import (CallSession, CallMode, CallStatus, Campaign, User, HandoffRequest,
                        HandoffState, CallAnalysis, RecordingAsset, RealtimeSession,
                        RealtimeState, CallUsage, CallMetric)
from app.schemas import WebhookEvent, MediaWebhookEvent, CallAnalysisReview
from app.api.routers import calls, webhooks, voice_operations
from app.services import call_service, telephony, webrtc
from app.services.call_analysis import analyze_call


def make_call(**kw):
    with session_scope() as session:
        call = CallSession(tenant_id=1, phone='13800000000', mode=CallMode.AI_ONLY,
                           **{'status': CallStatus.IN_AI, 'attempts': 1, 'max_attempts': 3, **kw})
        session.add(call); session.commit(); session.refresh(call)
        return call.id


def deliver(route, payload, *, background=True):
    owned = WebhookSession()
    args = (payload, BackgroundTasks()) if background else (payload,)
    try:
        result = route(*args, session=owned)
        owned.finish(success=True)
        return result
    except BaseException:
        owned.finish(success=False)
        raise


def status(cid, value, **payload):
    return deliver(webhooks.telephony_status, WebhookEvent(call_id=cid, kind='status', payload={
        'attempt': 1, 'status': value, 'event_id': uuid4().hex, **payload}))


def test_schema_bootstrap_is_repeatable(client):
    create_db_and_tables()
    create_db_and_tables()


def test_stale_hangup_cannot_complete_retry(client):
    cid = make_call()
    class Delayed:
        async def hangup(self, **kwargs):
            with session_scope() as s:
                call = s.get(CallSession, cid); call.attempts = 2; call.status = CallStatus.DIALING
                s.add(call); s.commit()
            return {'ended': True}
    async def run():
        with patch.object(telephony, 'get_telephony_adapter', return_value=Delayed()), session_scope() as s:
            result = await calls.hangup_api(cid, tenant_id=1, reason='test', session=s, current=None)
            assert result.attempts == 2 and result.status == CallStatus.DIALING
    asyncio.run(run())


def test_confirmed_hangup_finalizes_without_callback(client):
    with session_scope() as s:
        campaign = Campaign(tenant_id=1, name='hangup regression', status='running', dispatch_enabled=True)
        agent = User(tenant_id=1, username=uuid4().hex, full_name='test', agent_status='busy')
        s.add_all([campaign, agent]); s.commit(); s.refresh(campaign); s.refresh(agent)
        cp, uid = campaign.id, agent.id
    cid = make_call(campaign_id=cp, human_agent_id=uid, status=CallStatus.IN_HUMAN)
    with session_scope() as s:
        h = HandoffRequest(tenant_id=1, call_session_id=cid, assigned_agent_id=uid, state=HandoffState.ACCEPTED)
        s.add(h); s.commit(); s.refresh(h); hid = h.id
    class Confirmed:
        async def hangup(self, **kwargs): return {'ended': True}
    async def run():
        with patch.object(telephony, 'get_telephony_adapter', return_value=Confirmed()), session_scope() as s:
            await calls.hangup_api(cid, tenant_id=1, reason='test', session=s, current=None)
    asyncio.run(run())
    with session_scope() as s:
        assert s.get(CallSession, cid).status == CallStatus.COMPLETED
        assert s.get(User, uid).agent_status == 'ready'
        assert s.get(HandoffRequest, hid).state == HandoffState.COMPLETED
        assert s.get(Campaign, cp).status == 'completed'
        assert s.exec(select(CallAnalysis).where(CallAnalysis.call_session_id == cid)).first()


def test_review_survives_refresh_and_new_evidence(client):
    cid = make_call(status=CallStatus.COMPLETED, summary='客户愿意了解')
    with session_scope() as s:
        voice_operations.review_call_analysis(cid, CallAnalysisReview(intent='manual', summary='人工结论'),
                                              tenant_id=1, session=s, current=None)
        call = s.get(CallSession, cid); call.summary = '客户拒绝'; s.add(call); s.commit()
        analysis = analyze_call(s, call)
        assert analysis.intent == 'manual' and analysis.summary == '人工结论'
        assert analysis.review_state == 'reviewed' and analysis.needs_review
        assert 'not_interested' in analysis.automatic_result_json


def test_historical_recording_is_saved_once_without_replacing_current(client):
    cid = make_call(attempts=2, recording_url='https://example.invalid/current.wav')
    for _ in range(2):
        deliver(webhooks.telephony_recording, WebhookEvent(call_id=cid, kind='recording', payload={
            'attempt': 1, 'event_id': uuid4().hex, 'url': 'https://example.invalid/old.wav'}))
    with session_scope() as s:
        assets = s.exec(select(RecordingAsset).where(RecordingAsset.call_session_id == cid)).all()
        assert len(assets) == 1 and assets[0].attempt == 1
        assert s.get(CallSession, cid).recording_url.endswith('current.wav')


def test_media_dedup_order_and_terminal_guard(client):
    cid = make_call()
    def media(state, seq, event_id=None):
        return deliver(webhooks.telephony_media, MediaWebhookEvent(call_id=cid, attempt=1,
            event_id=event_id or uuid4().hex, state=state, event_sequence=seq), background=False)
    media('speaking', 20, 'same')
    assert media('speaking', 20, 'same')['duplicate']
    media('listening', 10)
    with session_scope() as s:
        rt = s.exec(select(RealtimeSession).where(RealtimeSession.call_session_id == cid)).one()
        assert rt.state == RealtimeState.SPEAKING
    media('closed', 30)
    media('speaking', 40)
    with session_scope() as s:
        rt = s.exec(select(RealtimeSession).where(RealtimeSession.call_session_id == cid)).one()
        assert rt.state == RealtimeState.CLOSED
        assert len(s.exec(select(CallMetric).where(CallMetric.call_session_id == cid)).all()) == 2


def test_billing_sums_attempt_cdr_seconds_and_ignores_llm_latency(client):
    cid = make_call(status=CallStatus.DIALING)
    status(cid, 'answered')
    status(cid, 'completed', billable_duration_sec=10)
    with session_scope() as s:
        call = s.get(CallSession, cid); call.attempts = 2; call.status = CallStatus.DIALING
        s.add(call); s.add(CallMetric(tenant_id=1, call_session_id=cid, stage='ai.turn', duration_ms=600000)); s.commit()
    status(cid, 'answered', attempt=2)
    status(cid, 'completed', attempt=2, billable_duration_sec=600)
    status(cid, 'completed', attempt=2, billable_duration_sec=600)
    from app.services.billing_usage import usage_by_call
    with session_scope() as s:
        value = usage_by_call(s, 1, [cid])[str(cid)]
        assert value['telephony_minutes'] == pytest.approx(610 / 60)
        assert value['ai_minutes'] < 1
        assert value['missing_duration_count'] == 0


def test_credential_renewal_preserves_current_registration_secret(client):
    first = webrtc.issue_sip_credential(tenant_id=1, agent_id=87654)
    second = webrtc.issue_sip_credential(tenant_id=1, agent_id=87654)
    assert first[:2] == second[:2]
    assert second[2] >= first[2]


@pytest.mark.skipif(engine.dialect.name != 'postgresql', reason='requires bounded PostgreSQL pool')
def test_dial_io_does_not_hold_pool_connection(client, monkeypatch):
    from app.models import Tenant
    with session_scope() as s:
        tenant = Tenant(name='pool regression', code=uuid4().hex)
        s.add(tenant); s.commit(); s.refresh(tenant); tid = tenant.id
        ids = []
        for index in range(3):
            call = CallSession(tenant_id=tid, phone=f'139{uuid4().int % 100000000:08d}',
                               mode=CallMode.AI_ONLY, status=CallStatus.QUEUED, attempts=0)
            s.add(call); s.flush(); ids.append(call.id)
        s.commit()
    observed = []
    class Slow:
        async def dial(self, **kwargs):
            observed.append(engine.pool.checkedout())
            await asyncio.sleep(.05)
            return {'provider_call_id': uuid4().hex}
    monkeypatch.setattr(call_service, 'get_telephony_adapter', lambda **kw: Slow())
    result = asyncio.run(call_service.dispatch_call_ids([str(cid) for cid in ids], max_concurrency=3))
    assert result['succeeded'] == 3, str(result)
    assert observed == [0, 0, 0]

@pytest.mark.parametrize('identity,expected_status', [({'expected_attempt': 1}, 409), ({'provider_call_id': 'old'}, 409), ({'expected_attempt': 2, 'provider_call_id': 'current'}, 200)])
def test_gateway_hangup_checks_attempt_before_pbx_command(identity, expected_status):
    from test_production_hardening import _voice_security_contract_module
    from fastapi import HTTPException
    security = _voice_security_contract_module()
    driver = object.__new__(security.SecureDriver)
    class Ledger:
        def lookup(self, *args): return {'attempt': 2, 'uuid': 'current', 'state': 'ended'}
        def rejected(self, *args): pass
    driver.ledger = Ledger()
    async def run():
        if expected_status == 409:
            with pytest.raises(HTTPException) as error:
                await driver.post('hangup', {'call_id': 'logical', 'tenant_id': 1, **identity})
            assert error.value.status_code == 409
        else:
            assert (await driver.post('hangup', {'call_id': 'logical', 'tenant_id': 1, **identity}))['ended']
    asyncio.run(run())


def test_billing_marks_older_attempts_without_usage_evidence(client):
    from app.services.billing_usage import usage_by_call
    cid = make_call(attempts=3, status=CallStatus.COMPLETED)
    with session_scope() as s:
        s.add(CallUsage(tenant_id=1, call_session_id=cid, attempt=3,
                       telephony_seconds=10, ai_seconds=2, ended_at=utc_now(), duration_source='provider_cdr'))
        s.commit()
        value = usage_by_call(s, 1, [cid])[str(cid)]
        assert value['missing_duration_count'] == 2
        assert value['missing_ai_duration_count'] == 2
        assert value['telephony_minutes'] == pytest.approx(1 / 6)
