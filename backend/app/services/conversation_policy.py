"""Durable per-attempt product policy. Call row is the serialization boundary."""
from __future__ import annotations

import json
from datetime import timedelta
from uuid import UUID, uuid5, NAMESPACE_URL
from zoneinfo import ZoneInfo

from sqlalchemy import update, or_
from sqlmodel import select

from ..clock import utc_now
from ..models import (CallSession, CallStatus, CallMode, ConversationState, ScenarioVersion,
    PhoneSuppression, Contact, CallbackAppointment, ProductWorkItem, RealtimeSession,
    RealtimeState, SpeechTurn, HandoffRequest, HandoffState, User, Campaign)
from ..product_schemas import ScenarioPolicy
from ..schemas import AiTurnResult
from .dialogue_rules import classify, callback_time, keyword_match


ACTIVE = {CallStatus.ANSWERED,CallStatus.IN_AI}


def encode(value):
    return json.dumps(value,ensure_ascii=False,sort_keys=True,default=str)


def current_policy(session, tenant_id, campaign_id=None):
    row = session.exec(select(ScenarioVersion).where(ScenarioVersion.tenant_id==tenant_id,
        or_(ScenarioVersion.campaign_id==campaign_id,ScenarioVersion.campaign_id.is_(None)))
        .order_by(ScenarioVersion.campaign_id.is_not(None).desc(),ScenarioVersion.id.desc())).first()
    return (ScenarioPolicy.model_validate_json(row.policy_json),row.id) if row else (ScenarioPolicy(),None)


def state_for(session, call):
    state = session.exec(select(ConversationState).where(ConversationState.call_id==call.id,
        ConversationState.attempt==call.attempts).with_for_update()).first()
    if state is None:
        policy,version = current_policy(session,call.tenant_id,call.campaign_id)
        from .admin_settings import get_admin_setting
        from .call_service import resolve_campaign_script
        snapshot=policy.model_dump(mode='json')
        snapshot['_ai']=get_admin_setting(session,call.tenant_id,'ai')
        snapshot['_script']=resolve_campaign_script(session,tenant_id=call.tenant_id,campaign_id=call.campaign_id)
        state=ConversationState(tenant_id=call.tenant_id,call_id=call.id,attempt=call.attempts,
            policy_version_id=version,policy_json=encode(snapshot))
        session.add(state); session.flush()
    return state


def in_hours(policy, moment):
    local=moment.replace(tzinfo=ZoneInfo('UTC')).astimezone(ZoneInfo(policy.timezone))
    return (local.weekday() in policy.weekdays and local.date() not in policy.holidays
            and policy.start_hour<=local.hour<policy.end_hour)


def suppressed(session, tenant_id, phone):
    return session.exec(select(PhoneSuppression.id).where(PhoneSuppression.tenant_id==tenant_id,
        PhoneSuppression.phone==phone)).first() is not None


def suppress_phone(session, call, reason):
    # Concurrent calls to the same number can opt out together. Upsert avoids
    # rolling back a confirmed opt-out on the unique tenant/phone constraint.
    if session.get_bind().dialect.name in {'sqlite','postgresql'}:
        if session.get_bind().dialect.name=='postgresql':
            from sqlalchemy.dialects.postgresql import insert
        else:
            from sqlalchemy.dialects.sqlite import insert
        session.execute(insert(PhoneSuppression).values(tenant_id=call.tenant_id,phone=call.phone,
            reason=reason,source_call_id=call.id).on_conflict_do_nothing(index_elements=['tenant_id','phone']))
    elif not suppressed(session,call.tenant_id,call.phone):
        session.add(PhoneSuppression(tenant_id=call.tenant_id,phone=call.phone,reason=reason,source_call_id=call.id))
    for contact in session.exec(select(Contact).where(Contact.tenant_id==call.tenant_id,Contact.phone==call.phone)).all():
        contact.dnc=True; contact.dnc_reason=reason; session.add(contact)
    session.exec(update(CallSession).where(CallSession.tenant_id==call.tenant_id,
        CallSession.phone==call.phone,CallSession.id!=call.id,
        CallSession.status.in_([CallStatus.CREATED,CallStatus.QUEUED])).values(
            status=CallStatus.FAILED,next_attempt_at=None,last_error='CONTACT_DNC',finished_at=utc_now()))
    from .call_service import TERMINAL_STATUSES
    session.exec(update(CallSession).where(CallSession.tenant_id==call.tenant_id,
        CallSession.phone==call.phone,or_(CallSession.id==call.id,CallSession.status.in_(TERMINAL_STATUSES))).values(next_attempt_at=None))
    source_ids=select(CallSession.id).where(CallSession.tenant_id==call.tenant_id,CallSession.phone==call.phone)
    session.exec(update(CallbackAppointment).where(CallbackAppointment.tenant_id==call.tenant_id,
        CallbackAppointment.source_call_id.in_(source_ids),CallbackAppointment.state=='confirmed').values(
            state='cancelled',revision=CallbackAppointment.revision+1,updated_at=utc_now()))


def release_waiting_agents(session, call):
    for handoff in session.exec(select(HandoffRequest).where(HandoffRequest.call_session_id==call.id,
            HandoffRequest.state==HandoffState.WAITING).with_for_update()).all():
        handoff.state=HandoffState.REJECTED;handoff.updated_at=utc_now();session.add(handoff)
        agent=session.exec(select(User).where(User.id==handoff.assigned_agent_id).with_for_update()).first() if handoff.assigned_agent_id else None
        if agent is None or agent.agent_status!='busy':continue
        other=session.exec(select(CallSession.id).where(CallSession.id!=call.id,CallSession.human_agent_id==agent.id,
            CallSession.status.in_([CallStatus.WAITING_HUMAN,CallStatus.IN_HUMAN]))).first()
        pending=session.exec(select(HandoffRequest.id).where(HandoffRequest.call_session_id!=call.id,
            HandoffRequest.assigned_agent_id==agent.id,HandoffRequest.state.in_([HandoffState.WAITING,HandoffState.ACCEPTING]))).first()
        if other is None and pending is None:
            agent.agent_status='ready';session.add(agent)


def add_work(session, call, kind, key, detail):
    event_key=f'{call.tenant_id}:{key}'
    item=session.exec(select(ProductWorkItem).where(ProductWorkItem.event_key==event_key)).first()
    if item is None:
        item=ProductWorkItem(tenant_id=call.tenant_id,call_id=call.id,phone=call.phone,
            kind=kind,event_key=event_key,detail_json=encode(detail))
        session.add(item)
    return item


def reply(text, action='speak'):
    return AiTurnResult(action=action,tts_text=text,handoff_to_human=action=='handoff')


def prepare_turn(session, call, transcript):
    """Called while holding the current call row, before any external model wait."""
    state=state_for(session,call)
    policy=ScenarioPolicy.model_validate_json(state.policy_json)
    data=json.loads(state.data_json)
    realtime=session.exec(select(RealtimeSession).where(RealtimeSession.call_session_id==call.id)).first()
    sequence=realtime.turn_sequence if realtime else 0
    if data.get('sequence')==sequence and data.get('transcript')==transcript and data.get('result'):
        return AiTurnResult.model_validate(data['result'])
    if not transcript.strip():
        if sequence==0:return None
        state.generation+=1;state.deadline=None;state.timer_kind=''
        result=clarify(data,policy)
        data.update(sequence=sequence,transcript=transcript,last_intent='unclear',result=result.model_dump(mode='json'))
        save_state(session,state,data)
        return result
    state.generation+=1; state.deadline=None; state.timer_kind=''
    data['silence_count']=0
    turn=session.exec(select(SpeechTurn).where(SpeechTurn.call_session_id==call.id,
        SpeechTurn.attempt==call.attempts,SpeechTurn.is_final.is_(True),SpeechTurn.speaker_role=='customer')
        .order_by(SpeechTurn.created_at.desc(),SpeechTurn.id.desc())).first()
    intent=classify(transcript)
    if (data.get('pending') or {}).get('kind')=='callback_time_request' and intent not in {'stop_contact','wrong_person','cancel_callback','end'}:
        intent='callback'
    evidence={'text':transcript,'turn_id':turn.id if turn else None,'attempt':call.attempts,
              'start_ms':turn.start_ms if turn else None,'end_ms':turn.end_ms if turn else None}
    result=None
    if turn is not None and turn.confidence is not None and turn.confidence<policy.confidence_threshold:
        result=clarify(data,policy)
    elif intent in {'stop_contact','wrong_person'}:
        suppress_phone(session,call,intent)
        data.update(outcome=intent,pending=None)
        result=reply('好的，已停止对这个号码的后续联系，再见。','hangup')
    elif intent=='cancel_callback':
        for appt in session.exec(select(CallbackAppointment).where(CallbackAppointment.tenant_id==call.tenant_id,
            CallbackAppointment.source_call_id==call.id,CallbackAppointment.state=='confirmed')).all():
            appt.state='cancelled'; appt.revision+=1; session.add(appt)
        data['pending']=None
        result=reply('好的，本次预约回拨已取消。')
    elif intent=='callback':
        when=callback_time(transcript,utc_now(),policy.timezone)
        if when is None:
            data['pending']={'kind':'callback_time_request'}
            result=reply('请告诉我具体日期和上午或下午几点回电。')
        elif not in_hours(policy,when):
            data['pending']={'kind':'callback_time_request'}
            result=reply('这个时间不在服务时段，请您换一个时间，我会再次确认。')
        else:
            data['pending']={'kind':'appointment','value':when.isoformat(),'evidence':evidence}
            display=when.replace(tzinfo=ZoneInfo('UTC')).astimezone(ZoneInfo(policy.timezone)).strftime('%Y年%m月%d日 %H:%M')
            result=reply(f'您希望在{display}回电，对吗？')
    elif intent=='machine_candidate':
        data['machine_hits']=data.get('machine_hits',0)+1
        data['answer_kind']='unknown'
        if policy.machine_action=='end' or data['machine_hits']>=2:
            data.update(answer_kind='machine',outcome='non_human',pending=None)
            result=reply('本次通话先结束，再见。','hangup')
        else:
            data['pending']={'kind':'human_confirmation'}
            result=reply('请问现在是本人在接听吗？')
    elif data.get('pending') and intent in {'affirm','deny'}:
        pending=data.pop('pending')
        if intent=='deny':
            result=reply('好的，请您重新说明正确的信息。')
        elif pending['kind']=='appointment':
            from datetime import datetime
            from .task_queue import enqueue_task
            when=datetime.fromisoformat(pending['value'])
            if when<=utc_now() or not in_hours(policy,when):
                result=reply('该时间已不可用，请重新选择回电时间。')
            else:
                key=f'appointment:{call.id}:{call.attempts}:{sequence}'
                appt=session.exec(select(CallbackAppointment).where(CallbackAppointment.request_key==key)).first()
                if appt is None:
                    appt=CallbackAppointment(id=uuid5(NAMESPACE_URL,key),tenant_id=call.tenant_id,
                        source_call_id=call.id,scheduled_at=when,request_key=key)
                    session.add(appt); session.flush()
                data['outcome']='callback_confirmed'
                result=reply('好的，回拨时间已经保存，再见。','hangup')
                data.update(sequence=sequence,transcript=transcript,last_intent=intent,result=result.model_dump(mode='json'))
                # This outbox shares the call transaction; no success before persistence.
                save_state(session,state,data)
                enqueue_task(session,tenant_id=call.tenant_id,task_type='after_playback',aggregate_id=str(call.id),
                    idempotency_key=f'appointment:{appt.id}:{appt.revision}',available_at=when,
                    payload={'product_kind':'appointment','appointment_id':str(appt.id),'revision':appt.revision})
                result=reply('好的，回拨时间已经保存，再见。','hangup')
        elif pending['kind']=='human_confirmation':
            data['answer_kind']='human'; data['machine_hits']=0
            result=reply('谢谢确认，请问您希望了解什么？')
        elif pending['kind']=='slot':
            data.setdefault('slots',{})[pending['key']]={**pending,'confirmed':True,'confirmation':evidence}
            result=next_slot(data,policy)
    elif intent=='handoff':
        data['outcome']='handoff_requested'
        result=reply(policy.handoff_prompt,'handoff') if call.mode in {CallMode.AI_HANDOFF,CallMode.MIXED_HUMAN_FIRST} else reply('当前无法直接转人工，我会记录您的人工服务请求。')
        if result.action!='handoff':
            add_work(session,call,'human_followup',f'human:{call.id}:{call.attempts}',evidence)
    elif intent=='decline_handoff':
        result=reply('好的，我们继续。')
    elif intent=='end':
        result=reply('谢谢，再见。','hangup')
    elif intent=='wait':
        data['extended_wait']=True
        result=reply('好的，我稍等您。')
    elif intent=='rejected':
        data['outcome']='rejected'
        result=reply('好的，感谢您的时间，再见。','hangup')
    else:
        faq=next((answer for question,answer in policy.faqs.items() if keyword_match(transcript,question)),None)
        if faq:
            active=data.get('active_slot')
            question=next((slot.question for slot in policy.slots if slot.key==active),'')
            result=reply(f'{faq} {question}'.strip())
        elif policy.slots and intent not in {'question','unclear'}:
            import re
            for field in policy.slots:
                match=re.search(re.escape(field.label)+r'(?:改成|更正为|是|为|[:：])\s*([^，,。；;]+)',transcript)
                if match:
                    data.setdefault('proposed_slots',{})[field.key]={'value':match[1].strip(),'evidence':evidence}
                    data.setdefault('slots',{}).pop(field.key,None)
            if data.get('proposed_slots'):
                result=next_slot(data,policy)
            else:
                pending=data.get('pending') or {}
                corrected=re.search(r'(?:不是.+?而是|改成|更正为)(.+)$',transcript)
                if pending.get('kind')=='slot' and corrected:
                    transcript=corrected[1].strip()
                result=collect_slot(data,policy,transcript,evidence)
        elif intent=='unclear':
            result=clarify(data,policy)
    if intent not in {'unclear','machine_candidate'} and not (turn and turn.confidence is not None and turn.confidence<policy.confidence_threshold):
        data['clarifications']=0
    if intent in {'interested','permission_to_listen','question','affirm'} and data.get('answer_kind')!='machine' and not (turn and turn.confidence is not None and turn.confidence<policy.confidence_threshold):
        data['answer_kind']='human'
    data.update(sequence=sequence,transcript=transcript,last_intent=intent,result=result.model_dump(mode='json') if result else None)
    save_state(session,state,data)
    return result


def save_state(session,state,data):
    state.data_json=encode(data); state.updated_at=utc_now(); session.add(state); session.flush()


def clarify(data,policy):
    data['clarifications']=data.get('clarifications',0)+1
    return reply(policy.clarify_prompt if data['clarifications']<=policy.max_clarifications else
                 '抱歉，暂时无法确认您的意思，本次先结束，再见。',
                 'speak' if data['clarifications']<=policy.max_clarifications else 'hangup')


def next_slot(data,policy):
    slot=next((s for s in policy.slots if s.required and not data.get('slots',{}).get(s.key,{}).get('confirmed')),None)
    if slot:
        data['active_slot']=slot.key
        proposed=data.get('proposed_slots',{}).pop(slot.key,None)
        if proposed:
            return collect_slot(data,policy,proposed['value'],proposed['evidence'])
        return reply(slot.question)
    data['active_slot']=None
    qualifies=all(not s.qualifies or data.get('slots',{}).get(s.key,{}).get('value') in s.qualifies for s in policy.slots)
    data['outcome']='qualified_lead' if qualifies and any(s.qualifies for s in policy.slots) else 'information_collected'
    return reply('信息已经记录，感谢您的配合，再见。','hangup')


def collect_slot(data,policy,text,evidence):
    import re
    slot=next((s for s in policy.slots if s.key==data.get('active_slot')),None)
    if slot is None:
        return next_slot(data,policy)
    value=text.strip()
    if slot.kind in {'integer','digits'}:
        match=re.fullmatch(r'\d{1,20}',value)
        if not match:
            return reply(f'请用数字回答：{slot.question}')
    if slot.kind=='date':
        from datetime import date
        try:date.fromisoformat(value)
        except ValueError:return reply('请按年-月-日说明日期。')
    if slot.kind=='choice':
        matches=[choice for choice in slot.choices if keyword_match(value,choice)]
        if len(matches)!=1:
            return reply(f'{slot.question} 可选：'+ '、'.join(slot.choices))
        value=matches[0]
    if len(value)>500:
        return clarify(data,policy)
    pending={'kind':'slot','key':slot.key,'value':value,'evidence':evidence}
    if slot.confirm:
        data['pending']=pending
        return reply(f'您说的{slot.label}是{value}，对吗？')
    data.setdefault('slots',{})[slot.key]={**pending,'confirmed':True}
    return next_slot(data,policy)


def arm_timer(session,call,kind='silence'):
    from .task_queue import enqueue_task
    state=state_for(session,call); data=json.loads(state.data_json)
    policy=ScenarioPolicy.model_validate_json(state.policy_json)
    if kind=='silence' and data.get('model_pending'):
        return
    if state.deadline is not None and state.timer_kind==kind:
        return
    seconds=policy.handoff_wait_seconds if kind=='handoff' else policy.wait_seconds if data.pop('extended_wait',False) else policy.silence_seconds
    state.generation+=1; state.timer_kind=kind; state.deadline=utc_now()+timedelta(seconds=seconds)
    save_state(session,state,data)
    enqueue_task(session,tenant_id=call.tenant_id,task_type='after_playback',aggregate_id=str(call.id),
        idempotency_key=f'conversation:{call.id}:{call.attempts}:{state.generation}',available_at=state.deadline,
        payload={'product_kind':'timer','call_id':str(call.id),'attempt':call.attempts,'generation':state.generation,'kind':kind})


def on_media(session,call,payload):
    state=state_for(session,call)
    if call.status not in ACTIVE | {CallStatus.WAITING_HUMAN}:
        state.generation+=1;state.deadline=None;session.add(state);return
    if payload.state==RealtimeState.LISTENING and call.status in ACTIVE:
        arm_timer(session,call,'failure' if payload.error_code else 'silence')
    elif payload.state in {RealtimeState.SPEAKING,RealtimeState.INTERRUPTED,RealtimeState.CLOSED}:
        state.generation+=1;state.deadline=None;state.timer_kind='';session.add(state)


async def run_product_task(payload):
    from ..db import session_scope
    if payload['product_kind']=='appointment':
        await run_appointment(payload)
        return
    from .dispatcher import _apply_ai_action, _expected_turn_sequence, _expected_speech_event
    with session_scope() as session:
        call=session.exec(select(CallSession).where(CallSession.id==UUID(payload['call_id'])).with_for_update()).first()
        if call is None or call.attempts!=payload['attempt'] or call.status not in ACTIVE|{CallStatus.WAITING_HUMAN}:
            return
        state=state_for(session,call)
        if state.generation!=payload['generation'] or state.timer_kind!=payload['kind']:
            return
        if state.deadline is None or state.deadline>utc_now():return
        policy=ScenarioPolicy.model_validate_json(state.policy_json);data=json.loads(state.data_json)
        realtime=session.exec(select(RealtimeSession).where(RealtimeSession.call_session_id==call.id)).first()
        if payload['kind']=='silence' and realtime and realtime.state not in {RealtimeState.LISTENING,RealtimeState.INTERRUPTED}:
            return
        cached=data.get('timer_action',{})
        if cached.get('generation')==state.generation:
            result=AiTurnResult.model_validate(cached['result'])
        elif payload['kind']=='handoff':
            if call.status!=CallStatus.WAITING_HUMAN:return
            release_waiting_agents(session,call)
            call.status=CallStatus.IN_AI;call.human_agent_id=None;session.add(call)
            result=reply(policy.handoff_timeout_prompt,'hangup' if policy.handoff_fallback=='end' else 'speak')
            data['outcome']='handoff_timeout'
        elif payload['kind']=='failure':
            result=reply(policy.failure_prompt,'hangup')
            data['outcome']='service_failure'
            add_work(session,call,'service_failure',f'media-failure:{call.id}:{call.attempts}',{'error':'speech_service_unavailable'})
        else:
            data['silence_count']=data.get('silence_count',0)+1
            exhausted=data['silence_count']>policy.max_clarifications
            result=reply('暂时没有收到您的回复，本次先结束，再见。' if exhausted else policy.silence_prompt,'hangup' if exhausted else 'speak')
            if exhausted:data['outcome']='no_response'
        sequence=realtime.turn_sequence if realtime else None
        data['timer_action']={'generation':state.generation,'result':result.model_dump(mode='json')}
        save_state(session,state,data);session.commit()
        token=_expected_turn_sequence.set(sequence)
        speech_token=_expected_speech_event.set(data.get('speech_event_id'))
        try:
            await _apply_ai_action(session=session,call=call,result=result,expected_attempt=payload['attempt'])
            session.refresh(call,with_for_update=True)
            session.refresh(state)
            if state.generation==payload['generation']:
                state.generation+=1;state.deadline=None;state.timer_kind='';session.add(state);session.commit()
        finally:
            _expected_turn_sequence.reset(token)
            _expected_speech_event.reset(speech_token)


async def run_appointment(payload):
    from ..db import session_scope
    from .call_service import _place_call_with_result
    with session_scope() as session:
        appt=session.exec(select(CallbackAppointment).where(CallbackAppointment.id==UUID(payload['appointment_id'])).with_for_update()).first()
        if appt is None or appt.revision!=payload['revision'] or appt.state not in {'confirmed','dispatching'}:return
        if appt.scheduled_at>utc_now():return
        source=session.get(CallSession,appt.source_call_id)
        if source is None or suppressed(session,appt.tenant_id,source.phone):
            appt.state='cancelled';session.add(appt);session.commit();return
        policy,_=current_policy(session,appt.tenant_id,source.campaign_id)
        if not in_hours(policy,utc_now()) or utc_now()>appt.scheduled_at+timedelta(minutes=15):
            appt.state='needs_review';session.add(appt)
            add_work(session,source,'missed_callback',f'missed:{appt.id}',{'appointment_id':str(appt.id)})
            session.commit();return
        cid=appt.dial_call_id or uuid5(NAMESPACE_URL,f'callback-dial:{appt.id}')
        call=session.get(CallSession,cid)
        if call is None:
            call=CallSession(id=cid,tenant_id=source.tenant_id,phone=source.phone,contact_id=source.contact_id,
                mode=source.mode,status=CallStatus.QUEUED,max_attempts=1)
            session.add(call);session.flush()
            original=state_for(session,source)
            session.add(ConversationState(tenant_id=source.tenant_id,call_id=cid,attempt=1,
                policy_version_id=original.policy_version_id,policy_json=original.policy_json))
        appt.dial_call_id=cid;appt.state='dispatching';session.add(appt);session.commit()
        if call.status==CallStatus.QUEUED:
            call,attempted=await _place_call_with_result(session,call)
            if not attempted and call.status==CallStatus.QUEUED:
                from .task_queue import TaskDeferred
                raise TaskDeferred()
        session.refresh(appt);appt.state='dispatched' if call.attempts else 'needs_review';session.add(appt);session.commit()
