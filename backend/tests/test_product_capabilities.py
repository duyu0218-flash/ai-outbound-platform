"""Production policy paths with disposable records; no carrier/model calls."""
import asyncio
import json
from contextlib import contextmanager
from datetime import datetime, timedelta
from uuid import uuid4

import pytest
from sqlmodel import SQLModel, Session, create_engine, select
from sqlalchemy.pool import StaticPool

from app.models import (Tenant, CallSession, CallMode, CallStatus, ConversationState,
    ScenarioVersion, RealtimeSession, SpeechTurn, PhoneSuppression, CallbackAppointment,
    Contact, ConsentState, TaskOutbox)
from app.product_schemas import ScenarioPolicy, SlotDefinition
from app.services.conversation_policy import prepare_turn, state_for, current_policy, arm_timer, run_product_task
from app.services.dialogue_rules import classify, keyword_match, callback_time
from app.services.call_analysis import analyze_call


@pytest.fixture
def db():
    engine=create_engine('sqlite://',poolclass=StaticPool)
    SQLModel.metadata.create_all(engine)
    with Session(engine) as session:
        session.add(Tenant(id=1,name='product-test',code='product-test'));session.commit()
        yield session
    engine.dispose()


def call_in(db,policy=None):
    if policy is not None:
        db.add(ScenarioVersion(tenant_id=1,policy_json=policy.model_dump_json()));db.commit()
    call=CallSession(tenant_id=1,phone='8613800000000',mode=CallMode.AI_HANDOFF,status=CallStatus.IN_AI,attempts=1)
    db.add(call);db.commit();db.refresh(call)
    db.add(RealtimeSession(tenant_id=1,call_session_id=call.id));db.commit()
    return call


def say(db,call,text,confidence=1):
    rt=db.exec(select(RealtimeSession).where(RealtimeSession.call_session_id==call.id)).one()
    rt.turn_sequence+=1;db.add(rt)
    db.add(SpeechTurn(tenant_id=1,call_session_id=call.id,attempt=call.attempts,
        provider_event_key=str(uuid4()),turn_index=rt.turn_sequence,speaker_role='customer',
        transcript=text,is_final=True,confidence=confidence))
    db.commit();result=prepare_turn(db,call,text);db.commit()
    return result


@pytest.mark.parametrize('text,expected',[
    ('不用转人工','decline_handoff'),('我需要你们别再打','stop_contact'),
    ('可以听一下','permission_to_listen'),('你说的是需要先交钱吗','question'),
    ('我不需要，不对，我现在需要','interested'),('先别挂，我还有问题','continue'),
    ('谢谢，再见','end'),('不是本人','wrong_person'),('请在滴声后留言','machine_candidate'),
    ('你是电话助手吗','unclear'),('别再打了','stop_contact')])
def test_intents(text,expected):assert classify(text)==expected


@pytest.mark.parametrize('text',['不需要','不用转人工','不想要'])
def test_negation(text):assert not keyword_match(text,{'不需要':'需要','不用转人工':'人工','不想要':'要'}[text])


def test_stop_contact_is_durable_and_cancels_pending(db):
    call=call_in(db)
    contact=Contact(tenant_id=1,phone=call.phone,consent_state=ConsentState.CONSENTED)
    pending=CallSession(tenant_id=1,phone=call.phone,mode=CallMode.AI_ONLY,status=CallStatus.QUEUED,next_attempt_at=datetime.now())
    db.add(contact);db.add(pending);db.commit()
    result=say(db,call,'我需要你们别再打')
    assert result.action=='hangup'
    assert db.exec(select(PhoneSuppression)).one().phone==call.phone
    db.refresh(pending);db.refresh(contact)
    assert contact.dnc and pending.status==CallStatus.FAILED and pending.next_attempt_at is None
    result2=prepare_turn(db,call,'我需要你们别再打')
    assert result2.action=='hangup' and len(db.exec(select(PhoneSuppression)).all())==1


def test_low_confidence_cannot_create_side_effect(db):
    call=call_in(db)
    assert say(db,call,'别再打',.1).action=='speak'
    assert not db.exec(select(PhoneSuppression)).all()


def test_fields_confirm_and_qualify(db):
    policy=ScenarioPolicy(slots=[SlotDefinition(key='plan',label='出售计划',question='您有出售计划吗？',
        kind='choice',choices=['有','没有'],qualifies=['有'])])
    call=call_in(db,policy)
    assert say(db,call,'你好').tts_text=='您有出售计划吗？'
    assert '对吗' in say(db,call,'有').tts_text
    assert say(db,call,'是的').action=='hangup'
    data=json.loads(state_for(db,call).data_json)
    assert data['outcome']=='qualified_lead' and data['slots']['plan']['confirmed']
    assert data['slots']['plan']['evidence']['turn_id']


def test_faq_returns_to_pending_question(db):
    call=call_in(db,ScenarioPolicy(slots=[SlotDefinition(key='city',label='城市',question='您在哪个城市？')],faqs={'你们是谁':'我们是客服团队。'}))
    say(db,call,'你好')
    assert say(db,call,'你们是谁').tts_text=='我们是客服团队。 您在哪个城市？'


def test_appointment_requires_confirmation(db,monkeypatch):
    import app.services.conversation_policy as module
    monkeypatch.setattr(module,'utc_now',lambda:datetime(2026,9,8,1,0))
    call=call_in(db,ScenarioPolicy(start_hour=0,end_hour=24))
    assert '对吗' in say(db,call,'明天下午三点再打').tts_text
    assert not db.exec(select(CallbackAppointment)).all()
    assert say(db,call,'对').action=='hangup'
    appt=db.exec(select(CallbackAppointment)).one()
    assert appt.scheduled_at==datetime(2026,9,9,7)
    assert len(db.exec(select(TaskOutbox)).all())==1
    say(db,call,'取消预约')
    db.refresh(appt);assert appt.state=='cancelled'


def test_ambiguous_time_does_not_schedule(db):
    call=call_in(db)
    assert '具体日期' in say(db,call,'明天下午再打').tts_text
    assert not db.exec(select(CallbackAppointment)).all()


def test_nonhuman_requires_confirmation_and_no_lead(db):
    call=call_in(db)
    assert say(db,call,'请在滴声后留言').action=='speak'
    assert say(db,call,'请在滴声后留言').action=='hangup'
    call.status=CallStatus.COMPLETED;db.add(call);db.commit()
    assert analyze_call(db,call).result_code=='non_human'


@pytest.mark.parametrize('text',['可以听一下','你说的是需要先交钱吗','我需要你们别再打'])
def test_false_leads_removed(db,text):
    call=call_in(db);say(db,call,text)
    call.status=CallStatus.COMPLETED;db.add(call);db.commit()
    assert analyze_call(db,call).result_code not in {'interested','qualified_lead'}


def test_prior_attempt_does_not_contaminate_analysis(db):
    call=call_in(db);say(db,call,'我想卖车')
    call.attempts=2;db.add(call);db.commit();say(db,call,'可以听一下')
    call.status=CallStatus.COMPLETED;db.add(call);db.commit()
    analysis=analyze_call(db,call)
    assert analysis.result_code=='completed'
    assert json.loads(analysis.structured_json)['customer_turn_count']==1


def test_policy_is_frozen_for_attempt(db):
    call=call_in(db,ScenarioPolicy(name='v1'));state=state_for(db,call);db.commit()
    db.add(ScenarioVersion(tenant_id=1,policy_json=ScenarioPolicy(name='v2').model_dump_json()));db.commit()
    assert current_policy(db,1)[0].name=='v2'
    assert json.loads(state_for(db,call).policy_json)['name']=='v1'


def test_old_timer_does_not_act_after_customer_response(db,monkeypatch):
    import app.db as dbmodule
    import app.services.dispatcher as dispatcher
    call=call_in(db);arm_timer(db,call);db.commit()
    payload=json.loads(db.exec(select(TaskOutbox)).one().payload_json)
    say(db,call,'你好')
    @contextmanager
    def scope():yield db
    monkeypatch.setattr(dbmodule,'session_scope',scope)
    async def forbidden(**kwargs):raise AssertionError('stale timer acted')
    monkeypatch.setattr(dispatcher,'_apply_ai_action',forbidden)
    asyncio.run(run_product_task(payload))


def test_callback_time_constraints():
    now=datetime(2026,9,8,1)
    assert callback_time('明天下午',now,'Asia/Shanghai') is None
    assert callback_time('明天三点',now,'Asia/Shanghai') is None
    assert callback_time('2020-01-01 12:00',now,'Asia/Shanghai') is None


def test_invalid_timezone_is_validation_error():
    from pydantic import ValidationError
    with pytest.raises(ValidationError):ScenarioPolicy(timezone='Missing/Zone')


def test_result_delivery_changes_on_review_and_is_idempotent(db):
    from app.models import AdminSetting
    from app.services.call_analysis import publish_analysis
    call=call_in(db);say(db,call,'可以听一下');call.status=CallStatus.COMPLETED;db.add(call)
    db.add(AdminSetting(tenant_id=1,section='integration',data_json=json.dumps({'callback_enabled':True,'webhook_base_url':'https://example.com/callback'})));db.commit()
    analysis=analyze_call(db,call)
    assert len(db.exec(select(TaskOutbox).where(TaskOutbox.task_type=='business_callback')).all())==1
    analyze_call(db,call)
    assert len(db.exec(select(TaskOutbox).where(TaskOutbox.task_type=='business_callback')).all())==1
    analysis.review_state='reviewed';analysis.needs_review=False;analysis.intent='not_interested';db.add(analysis)
    publish_analysis(db,call,analysis)
    assert len(db.exec(select(TaskOutbox).where(TaskOutbox.task_type=='business_callback')).all())==2


def test_no_call_sms_opt_out_is_durable(db):
    from types import SimpleNamespace
    from app.services.conversation_policy import suppress_phone,suppressed
    source=SimpleNamespace(tenant_id=1,phone='8613900000000',id=None)
    suppress_phone(db,source,'sms_stop_contact');db.commit()
    suppress_phone(db,source,'sms_stop_contact');db.commit()
    assert suppressed(db,1,source.phone)
    assert len(db.exec(select(PhoneSuppression)).all())==1


def test_knowledge_scope_expiry_and_frozen_version(db):
    from app.models import KnowledgeItem
    from app.services.knowledge import retrieve_knowledge,retrieve_bound_knowledge
    from app.clock import utc_now
    valid=KnowledgeItem(tenant_id=1,title='费用说明',content='评估费用为零',keywords='费用')
    expired=KnowledgeItem(tenant_id=1,title='旧费用',content='费用已过期',keywords='费用',valid_until=utc_now()-timedelta(days=1))
    db.add(valid);db.add(expired);db.commit();db.refresh(valid)
    assert [r['id'] for r in retrieve_knowledge(db,1,'费用')]==[str(valid.id)]
    snapshot=ScenarioPolicy().model_dump();snapshot['_knowledge']=[valid.model_dump(mode='json')]
    version=ScenarioVersion(tenant_id=1,policy_json=json.dumps(snapshot));db.add(version);db.commit();db.refresh(version)
    valid.content='新费用尚未发布';db.add(valid);db.commit()
    assert retrieve_bound_knowledge(db,type('Bound',(),{'tenant_id':1,'policy_version_id':version.id})(),'费用')[0]['content']=='评估费用为零'


def test_flow_variables_collection_and_branch():
    from app.schemas import ScriptFlowGraph
    from app.services.script_flow import simulate
    graph=ScriptFlowGraph.model_validate({'nodes':[
        {'id':'s','position':{'x':0,'y':0},'type':'start','label':'开始'}, {'id':'c','position':{'x':0,'y':0},'type':'collect','label':'城市','variable':'city','prompt':'城市？'},
        {'id':'b','position':{'x':0,'y':0},'type':'branch','label':'判断'}, {'id':'h','position':{'x':0,'y':0},'type':'hangup','label':'结束','prompt':'已记录{city}'},
        {'id':'a','position':{'x':0,'y':0},'type':'handoff','label':'上海服务'}], 'edges':[
        {'id':'1','source':'s','target':'c'}, {'id':'2','source':'c','target':'b'},
        {'id':'3','source':'b','target':'a','condition':'equals','variable':'city','value':'上海'},
        {'id':'4','source':'b','target':'h'}]})
    first=simulate(graph,None,'',False);assert first.next_node_id=='c'
    answer=simulate(graph,'c','上海',False);assert answer.variables=={'city':'上海'}
    assert simulate(graph,'b','',False,answer.variables).action=='handoff'
    assert simulate(graph,'b','',False,{'city':'北京'}).prompt=='已记录北京'


def test_timer_retry_preserves_action_without_double_increment(db,monkeypatch):
    from unittest.mock import AsyncMock
    import app.db as module
    import app.services.dispatcher as dispatcher
    from app.clock import utc_now
    from app.models import RealtimeState
    call=call_in(db)
    rt=db.exec(select(RealtimeSession).where(RealtimeSession.call_session_id==call.id)).one()
    rt.state=RealtimeState.LISTENING;db.add(rt);db.commit()
    arm_timer(db,call)
    task=db.exec(select(TaskOutbox)).one();payload=json.loads(task.payload_json)
    state=state_for(db,call);state.deadline=utc_now()-timedelta(seconds=1);db.add(state);db.commit()
    @contextmanager
    def scope():yield db
    monkeypatch.setattr(module,'session_scope',scope)
    action=AsyncMock(side_effect=[RuntimeError('temporary transport failure'),None])
    monkeypatch.setattr(dispatcher,'_apply_ai_action',action)
    with pytest.raises(RuntimeError):asyncio.run(run_product_task(payload))
    db.refresh(state);assert state.generation==payload['generation']
    asyncio.run(run_product_task(payload));db.refresh(state)
    assert json.loads(state.data_json)['silence_count']==1
    assert state.deadline is None
    asyncio.run(run_product_task(payload));assert action.await_count==2


def test_agent_release_does_not_free_agent_owned_by_another_call(db):
    from app.models import User,HandoffRequest,HandoffState
    from app.services.conversation_policy import release_waiting_agents
    agent=User(tenant_id=1,username='product-agent',full_name='测试坐席',password_hash='unused',role='agent',agent_status='busy')
    db.add(agent);db.commit();db.refresh(agent)
    call=call_in(db)
    handoff=HandoffRequest(tenant_id=1,call_session_id=call.id,assigned_agent_id=agent.id,state=HandoffState.WAITING)
    other=CallSession(tenant_id=1,phone='13900000009',mode=CallMode.HUMAN_ONLY,status=CallStatus.IN_HUMAN,human_agent_id=agent.id)
    db.add(handoff);db.add(other);db.commit()
    release_waiting_agents(db,call);db.commit();db.refresh(agent);db.refresh(handoff)
    assert handoff.state==HandoffState.REJECTED and agent.agent_status=='busy'



def test_callback_time_followup_is_confirmed_not_lost(db,monkeypatch):
    import app.services.conversation_policy as module
    monkeypatch.setattr(module,'utc_now',lambda:datetime(2026,9,8,1))
    call=call_in(db,ScenarioPolicy(start_hour=0,end_hour=24))
    assert '具体日期' in say(db,call,'明天下午再打').tts_text
    assert '对吗' in say(db,call,'明天下午三点').tts_text
    assert say(db,call,'对').action=='hangup'
    assert db.exec(select(CallbackAppointment)).one().scheduled_at==datetime(2026,9,9,7)


def test_multiple_explicit_fields_are_individually_confirmed(db):
    call=call_in(db,ScenarioPolicy(slots=[SlotDefinition(key='city',label='城市',question='城市？'),
        SlotDefinition(key='name',label='姓名',question='姓名？')]))
    assert '上海' in say(db,call,'城市是上海，姓名是张三').tts_text
    assert '张三' in say(db,call,'对').tts_text
    assert say(db,call,'对').action=='hangup'
    data=json.loads(state_for(db,call).data_json)
    assert {key:value['value'] for key,value in data['slots'].items()}=={'city':'上海','name':'张三'}


def test_low_confidence_never_becomes_analysis_consent(db):
    call=call_in(db);say(db,call,'我想卖车',.1)
    call.status=CallStatus.COMPLETED;db.add(call);db.commit()
    analysis=analyze_call(db,call)
    assert analysis.result_code=='completed' and analysis.intent=='unclear'
    assert 'low_confidence_transcript' in analysis.qa_flags_json


def test_funnel_counts_attempts_separately_from_unique_numbers(db):
    from app.api.routers.product import funnel
    from types import SimpleNamespace
    for _ in range(2):
        call=call_in(db);say(db,call,'可以听一下')
    result=funnel(days=7,user=SimpleNamespace(tenant_id=1),session=db)
    assert result['groups']==[{'outcome':'pending','answer_kind':'human','attempts':2,'unique_phones':1}]


def test_dtmf_collects_once_and_respects_attempt(db):
    from fastapi import BackgroundTasks
    from app.api.routers.webhooks import telephony_dtmf
    from app.schemas import WebhookEvent
    call=call_in(db,ScenarioPolicy(slots=[SlotDefinition(key='code',label='编号',question='请输入编号后按井号',kind='digits')]))
    say(db,call,'你好')
    def digit(value,event,attempt=1):
        return telephony_dtmf(WebhookEvent(call_id=call.id,kind='dtmf',payload={'digit':value,'event_id':event,'attempt':attempt}),BackgroundTasks(),session=db)
    assert digit('1','key1')['result']=='collecting'
    assert digit('1','key1')['duplicate']
    assert digit('2','key2')['result']=='collecting'
    assert digit('9','old',0)['result']=='ignored'
    assert digit('#','end')['result']=='ok'
    turns=db.exec(select(SpeechTurn).where(SpeechTurn.transcript=='12')).all()
    assert len(turns)==1
    assert digit('#','end')['duplicate']


def test_appointment_cancellation_is_revision_guarded(db,monkeypatch):
    from types import SimpleNamespace
    from fastapi import HTTPException
    from app.api.routers.product import patch_appointment
    from app.product_schemas import AppointmentPatch
    call=call_in(db)
    appointment=CallbackAppointment(tenant_id=1,source_call_id=call.id,request_key='cancel-test',scheduled_at=datetime(2026,9,9,7))
    db.add(appointment);db.commit();db.refresh(appointment)
    actor=SimpleNamespace(tenant_id=1)
    cancelled=patch_appointment(appointment.id,AppointmentPatch(revision=1,cancel=True),user=actor,session=db)
    assert cancelled.state=='cancelled' and cancelled.revision==2
    with pytest.raises(HTTPException) as error:patch_appointment(appointment.id,AppointmentPatch(revision=1,cancel=True),user=actor,session=db)
    assert error.value.status_code==409


def test_policy_cannot_reference_another_tenants_campaign(db):
    from fastapi import HTTPException
    from types import SimpleNamespace
    from app.models import Campaign
    from app.api.routers.product import get_policy
    db.add(Tenant(id=2,name='other',code='other'));db.commit()
    campaign=Campaign(tenant_id=2,name='private',script='private',mode=CallMode.AI_ONLY)
    db.add(campaign);db.commit();db.refresh(campaign)
    with pytest.raises(HTTPException) as error:get_policy(campaign_id=campaign.id,user=SimpleNamespace(tenant_id=1),session=db)
    assert error.value.status_code==404


def test_slow_model_gets_one_notice_and_stale_model_is_cancelled(monkeypatch):
    from unittest.mock import AsyncMock
    import app.services.dispatcher as dispatcher
    from app.schemas import AiTurnResult
    async def slow(**kwargs):
        await asyncio.sleep(1.05)
        return AiTurnResult(action='speak',tts_text='已确认')
    notice=AsyncMock()
    monkeypatch.setattr(dispatcher,'request_ai_turn',slow)
    monkeypatch.setattr(dispatcher,'_speak_wait_notice',notice)
    monkeypatch.setattr(dispatcher,'_ai_snapshot_current',lambda snapshot:True)
    result=asyncio.run(dispatcher._wait_for_ai({'ai_request':{},'model_wait_seconds':0}))
    assert result.tts_text=='已确认' and notice.await_count==1
    monkeypatch.setattr(dispatcher,'_ai_snapshot_current',lambda snapshot:False)
    assert asyncio.run(dispatcher._wait_for_ai({'ai_request':{},'model_wait_seconds':0})) is None
    assert notice.await_count==1


def test_empty_final_clarifies_without_advancing_slots(db):
    call=call_in(db,ScenarioPolicy(slots=[SlotDefinition(key='city',label='城市',question='城市？')]))
    assert say(db,call,'').action=='speak'
    assert not json.loads(state_for(db,call).data_json).get('slots')


def test_analysis_outbox_failure_can_roll_back_the_result(db,monkeypatch):
    import app.services.task_queue as tasks
    from app.models import CallAnalysis
    call=call_in(db);say(db,call,'可以听一下')
    def failed(*args,**kwargs):raise RuntimeError('outbox unavailable')
    monkeypatch.setattr(tasks,'enqueue_business_callback',failed)
    with pytest.raises(RuntimeError):analyze_call(db,call)
    db.rollback()
    assert db.exec(select(CallAnalysis).where(CallAnalysis.call_session_id==call.id)).first() is None


def test_duplicate_waiting_event_does_not_extend_handoff_deadline(db):
    call=call_in(db);call.status=CallStatus.WAITING_HUMAN;db.add(call);db.commit()
    arm_timer(db,call,'handoff')
    state=state_for(db,call);deadline=state.deadline
    arm_timer(db,call,'handoff');db.refresh(state)
    assert state.deadline==deadline and len(db.exec(select(TaskOutbox)).all())==1
