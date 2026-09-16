"""AI actions: thread-owned short transactions and bounded coroutine I/O.

Only scalar snapshots and transport adapters leave DB units. The enclosing AI
claim supplies the execution lease and bounds the number of outstanding actions.
Every external operation is fenced again by the gateway's attempt/speech guard.
"""
import hashlib
import json
import logging
import httpx
from datetime import timedelta
from time import perf_counter

from sqlmodel import select
from sqlalchemy import update

from ..clock import utc_now
from ..db import session_scope
from ..models import (CallSession, CallMetric, RealtimeSession, RealtimeState,
                      SpeechTurn, SmsLog, Campaign, TaskOutbox, TaskState)
from ..schemas import AiTurnResult
from . import dispatcher as d
from .leases import LeaseLost, assert_execution_permitted
from .telephony import HttpAdapter, get_telephony_adapter, get_sms_adapter, with_retry
from .admin_settings import get_admin_setting
from .conversation_policy import state_for
from .task_queue import enqueue_task, notify_task

logger = logging.getLogger(__name__)


def prepare(call_id, attempt, fallback_audio=False):
    with session_scope() as session:
        call = session.get(CallSession, call_id)
        if call is None or not d._ai_call_is_current(session, call, attempt, lock=True):
            return None
        campaign = session.get(Campaign, call.campaign_id) if call.campaign_id else None
        policy = json.loads(state_for(session, call).policy_json)
        config = {**get_admin_setting(session, call.tenant_id, 'ai'), **(policy.get('_ai') or {})}
        for name in ('voice', 'language'):
            if policy.get(name):
                config[name] = policy[name]
        if fallback_audio:
            config['tts_provider'] = 'fallback-audio'
        adapter = get_telephony_adapter(session=session, tenant_id=call.tenant_id,
            line_id=call.telephony_line_id, call_id=call.id)
        guard = ({'expected_speech_event_id': d._expected_speech_event.get() or ''}
                 if isinstance(adapter, HttpAdapter) and call.voice_ai_pipeline == 'pipecat' else {})
        return dict(call_id=call_id, attempt=attempt, adapter=adapter, guard=guard,
            language=str(config.get('language') or 'zh-CN'), voice=str(config.get('voice') or ''),
            provider=str(config.get('tts_provider') or ''),
            turn_sequence=d._expected_turn_sequence.get(),
            sms_allowed=campaign.hangup_sms_enabled if campaign else True)


def record_speech(snapshot, result, response, duration_ms, error=None):
    with session_scope() as session:
        call = session.get(CallSession, snapshot['call_id'])
        if call is None or not d._ai_call_is_current(session, call, snapshot['attempt'], lock=True):
            return False
        session.add(CallMetric(tenant_id=call.tenant_id, call_session_id=call.id,
            stage='tts.dispatch', provider=snapshot['provider'] or 'gateway', duration_ms=duration_ms,
            success=error is None, error_code='TTS_DISPATCH_FAILED' if error else None,
            detail=str(error)[:2000] if error else ''))
        if error is None:
            realtime = session.exec(select(RealtimeSession).where(RealtimeSession.call_session_id == call.id)).first()
            if realtime is not None:
                realtime.state = RealtimeState.SPEAKING
                realtime.playback_id = str(response.get('playback_id') or '') or None
                realtime.updated_at = utc_now()
                session.add(realtime)
            normalized = ' '.join((result.tts_text or '').split())
            key = hashlib.sha256(f"{call.id}:{snapshot['attempt']}:ai:{realtime.turn_sequence if realtime else 0}:{normalized}".encode()).hexdigest()
            if session.exec(select(SpeechTurn.id).where(SpeechTurn.call_session_id == call.id,
                    SpeechTurn.provider_event_key == key)).first() is None:
                session.add(SpeechTurn(tenant_id=call.tenant_id, call_session_id=call.id,
                    provider_event_key=key, turn_index=realtime.turn_sequence if realtime else 0,
                    attempt=snapshot['attempt'], speaker_role='ai', channel_id='outbound',
                    transcript=result.tts_text or '', normalized_transcript=normalized,
                    is_final=True, asr_provider=''))
        session.commit()
        return True


def defer_hangup(snapshot, result, playback_id):
    with session_scope() as session:
        call = session.get(CallSession, snapshot['call_id'])
        if call is None or not d._ai_call_is_current(session, call, snapshot['attempt'], lock=True):
            return
        realtime = session.exec(select(RealtimeSession).where(RealtimeSession.call_session_id == call.id)).first()
        task = enqueue_task(session, tenant_id=call.tenant_id, task_type='after_playback',
            aggregate_id=str(call.id),
            idempotency_key=f"after-playback:{call.id}:{snapshot['attempt']}:{playback_id}",
            available_at=utc_now() + timedelta(seconds=max(1, d.settings.tts_playback_timeout_sec)),
            payload=dict(call_id=str(call.id), attempt=snapshot['attempt'],
                turn_sequence=realtime.turn_sequence if realtime else None,
                playback_id=playback_id, speech_event_id=d._expected_speech_event.get(),
                result=result.model_dump(mode='json')))
        if realtime is not None:
            session.refresh(realtime)
            if realtime.playback_id != playback_id:
                session.exec(update(TaskOutbox).where(TaskOutbox.id == task.id,
                    TaskOutbox.state == TaskState.PENDING).values(available_at=utc_now()))
                session.commit()


def prepare_sms(snapshot, result):
    with session_scope() as session:
        call = session.get(CallSession, snapshot['call_id'])
        if call is None or not d._ai_call_is_current(session, call, snapshot['attempt']):
            return None
        from .ai_claim_state import owned
        task = owned(session)
        payload = json.loads(task.payload_json) if task is not None else {}
        if payload.get('sms_log_id') is not None:
            # A crashed/unknown send is audited, never sent a second time.
            return None
        config = get_admin_setting(session, call.tenant_id, 'sms')
        enabled = config.get('enabled', True)
        text = str(config.get('hangup_template') or result.hangup_sms)
        log = SmsLog(tenant_id=call.tenant_id, call_session_id=call.id, to_phone=call.phone,
            template_code='hangup_sms', content=text,
            state='outcome_unknown' if enabled else 'disabled')
        session.add(log)
        session.flush()
        sms = dict(adapter=get_sms_adapter(config) if enabled else None, phone=call.phone,
            text=text, enabled=enabled, tenant_id=call.tenant_id, log_id=log.id)
        if task is not None:
            payload['sms_log_id'] = log.id
            task.payload_json = json.dumps(payload, ensure_ascii=False)
            session.add(task)
        session.commit()
        return sms


def record_sms(snapshot, sms, response, error):
    # Audit the result without changing call lifecycle after a racing hangup.
    with session_scope() as session:
        log = session.get(SmsLog, sms['log_id'])
        if log is None:
            return
        log.state = 'outcome_unknown' if error else str(response.get('state', 'sent'))
        log.provider_message_id = str(response.get('message_id') or response.get('provider_message_id') or '') or None
        log.provider_error = type(error).__name__ if error else None
        log.sent_at = utc_now() if not error and sms['enabled'] else None
        session.add(log)
        session.commit()


async def finish(snapshot, result, hangup_confirmed, playback_complete, durable=True):
    from ..db import WebhookSession
    from .ai_claim_state import mark_committed
    session = WebhookSession()
    try:
        call = session.get(CallSession, snapshot['call_id'])
        if call is None or not d._ai_call_is_current(session, call, snapshot['attempt'], lock=True):
            return None
        callback_id = await d._commit_ai_decision(session, call, result, snapshot['attempt'],
            hangup_confirmed, playback_complete, snapshot['sms_allowed'])
        if durable:
            mark_committed(session)
        session.finish(success=True)
        return callback_id
    finally:
        session.finish(success=False)


async def execute_action(pool, call_id, attempt, result, fallback_audio=False, durable=True):
    snapshot = await pool.run(prepare, call_id, attempt, fallback_audio)
    if snapshot is None:
        return
    adapter = snapshot['adapter']
    response = {}
    if result.tts_text:
        guard = dict(snapshot['guard'])
        if isinstance(adapter, HttpAdapter):
            identity = [str(call_id), attempt, snapshot['turn_sequence'], guard,
                        result.tts_text, snapshot['provider'], snapshot['voice'], snapshot['language']]
            guard['command_id'] = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
        started = perf_counter()
        try:
            assert_execution_permitted()
            response = await with_retry(lambda: adapter.speak(call_id=str(call_id),
                text=result.tts_text, language=snapshot['language'], voice=snapshot['voice'],
                provider=snapshot['provider'], **guard))
        except LeaseLost:
            raise
        except Exception as exc:
            if (isinstance(exc, (httpx.TimeoutException, httpx.NetworkError))
                    or isinstance(exc, httpx.HTTPStatusError) and exc.response.headers.get('X-Voice-Outcome') == 'unknown'):
                raise LeaseLost('playback outcome unknown; retain command for reconciliation') from exc
            await pool.run(record_speech, snapshot, result, {}, int((perf_counter()-started)*1000), exc)
            raise
        pool.record_timing('network.speak', (perf_counter()-started)*1000)
        current = await pool.run(record_speech, snapshot, result, response, int((perf_counter()-started)*1000))
        if not current:
            return
    playback_id = str(response.get('playback_id') or '')
    playback_complete = bool(response.get('playback_complete', False))
    hangup_confirmed = False
    if result.action == 'hangup':
        if playback_id and not playback_complete:
            await pool.run(defer_hangup, snapshot, result, playback_id)
            return
        if not await pool.run(d._ai_snapshot_current, snapshot):
            return
        assert_execution_permitted()
        hangup_started = perf_counter()
        ended = await with_retry(lambda: adapter.hangup(call_id=str(call_id), reason='ai_decision', **snapshot['guard']))
        pool.record_timing('network.hangup', (perf_counter()-hangup_started)*1000)
        hangup_confirmed = ended.get('ended') is True
    if result.hangup_sms and snapshot['sms_allowed']:
        sms = await pool.run(prepare_sms, snapshot, result)
        if sms is not None:
            error = None
            response = {'state': 'disabled'}
            if sms['enabled']:
                try:
                    assert_execution_permitted()
                    response = await sms['adapter'].send_sms(sms['phone'], sms['text'])
                except LeaseLost:
                    raise
                except Exception as exc:
                    error = exc
            await pool.run(record_sms, snapshot, sms, response, error)
    callback_id = await pool.run(finish, snapshot, result, hangup_confirmed, playback_complete, durable)
    if callback_id is not None:
        await notify_task(callback_id)


async def execute_failure(pool, call_id, attempt, result):
    for fallback, audio in ((result, False), (result, True), (AiTurnResult(action='hangup'), False)):
        try:
            await execute_action(pool, call_id, attempt, fallback, audio)
            return
        except LeaseLost:
            raise
        except Exception:
            continue
    logger.warning('fallback termination unconfirmed; PBX reconciliation retains capacity')
