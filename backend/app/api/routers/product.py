from __future__ import annotations

import hashlib
import json
from datetime import datetime
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field
from sqlmodel import Session, select

from ...api.deps import require_role, require_roles_if_authenticated, check_api_key, get_tenant_id_for_request
from ...db import get_session
from ...clock import utc_now
from ...models import (Tenant, User, Campaign, CallSession, ConversationState, ScenarioVersion,
    CallbackAppointment, ProductWorkItem, KnowledgeItem)
from ...product_schemas import ScenarioSave, ScenarioProbe, ScenarioPolicy, AppointmentPatch
from ...services.conversation_policy import current_policy, encode, in_hours
from ...services.dialogue_rules import classify
from ...services.task_queue import enqueue_task


router=APIRouter(prefix='/api/v1/product',tags=['product-operations'])


def campaign_for(session,user,campaign_id):
    if campaign_id is not None:
        campaign=session.get(Campaign,campaign_id)
        if campaign is None or campaign.tenant_id!=user.tenant_id:raise HTTPException(404,'campaign not found')


@router.get('/policy')
def get_policy(campaign_id:int|None=None,user:User=Depends(require_role('admin')),session:Session=Depends(get_session)):
    campaign_for(session,user,campaign_id)
    policy,version=current_policy(session,user.tenant_id,campaign_id)
    history=session.exec(select(ScenarioVersion).where(ScenarioVersion.tenant_id==user.tenant_id,
        ScenarioVersion.campaign_id==campaign_id).order_by(ScenarioVersion.id.desc()).limit(30)).all()
    return {'policy':policy.model_dump(mode='json'),'version_id':version,
            'history':[{'id':row.id,'created_at':row.created_at,'policy':ScenarioPolicy.model_validate_json(row.policy_json).model_dump(mode='json')} for row in history]}


@router.post('/policy')
def save_policy(payload:ScenarioSave,user:User=Depends(require_role('admin')),session:Session=Depends(get_session)):
    session.exec(select(Tenant).where(Tenant.id==user.tenant_id).with_for_update()).one()
    campaign_for(session,user,payload.campaign_id)
    _,version=current_policy(session,user.tenant_id,payload.campaign_id)
    if version!=payload.expected_version_id:raise HTTPException(409,'策略已更新，请刷新后重试')
    for agent_id in payload.policy.handoff_agent_ids:
        agent=session.get(User,agent_id)
        if agent is None or agent.tenant_id!=user.tenant_id or agent.role!='agent' or not agent.enabled:
            raise HTTPException(422,'转接坐席不属于本租户或不可用')
    from sqlalchemy import or_
    knowledge=session.exec(select(KnowledgeItem).where(KnowledgeItem.tenant_id==user.tenant_id,
        KnowledgeItem.is_active.is_(True),or_(KnowledgeItem.campaign_id==payload.campaign_id,KnowledgeItem.campaign_id.is_(None)))
        .order_by(KnowledgeItem.id).limit(501)).all()
    snapshot=payload.policy.model_dump(mode='json')
    snapshot['_knowledge']=[item.model_dump(mode='json') for item in knowledge]
    if len(knowledge)>500 or len(encode(snapshot).encode())>600000:
        raise HTTPException(422,'知识内容过多，请按活动拆分适用范围后发布')
    samples={'不用转人工':'decline_handoff','我需要你们别再打':'stop_contact','可以听一下':'permission_to_listen',
             '你说的是需要先交钱吗':'question','我不需要，不对，我现在需要':'interested'}
    if any(classify(text)!=expected for text,expected in samples.items()):raise HTTPException(409,'基础业务样例未通过，不能发布')
    snapshot['_checks']={'semantic_examples':len(samples),'passed':True}
    row=ScenarioVersion(tenant_id=user.tenant_id,campaign_id=payload.campaign_id,
        policy_json=encode(snapshot),published_by=user.id)
    session.add(row);session.commit();session.refresh(row)
    return {'version_id':row.id,'policy':payload.policy.model_dump(mode='json')}


@router.post('/simulate')
def simulate_scenario(payload:ScenarioProbe,user:User=Depends(require_role('admin'))):
    # Use the exact production policy evaluator in a disposable database.
    from sqlmodel import SQLModel, create_engine
    from ...models import CallMode,CallStatus,RealtimeSession,SpeechTurn
    from ...services.conversation_policy import prepare_turn,state_for
    from sqlalchemy.pool import StaticPool
    engine=create_engine('sqlite://',poolclass=StaticPool)
    SQLModel.metadata.create_all(engine)
    trace=[]
    try:
        with Session(engine) as isolated:
            isolated.add(Tenant(id=1,name='simulation',code='simulation'));isolated.commit()
            isolated.add(ScenarioVersion(tenant_id=1,policy_json=payload.policy.model_dump_json()));isolated.commit()
            call=CallSession(tenant_id=1,phone='simulation',mode=CallMode.AI_HANDOFF,status=CallStatus.IN_AI,attempts=1)
            isolated.add(call);isolated.commit();isolated.refresh(call)
            realtime=RealtimeSession(tenant_id=1,call_session_id=call.id)
            isolated.add(realtime);isolated.commit()
            for i,text in enumerate(payload.utterances,1):
                realtime.turn_sequence=i;isolated.add(realtime)
                isolated.add(SpeechTurn(tenant_id=1,call_session_id=call.id,attempt=1,turn_index=i,
                    provider_event_key=f'sim:{i}',speaker_role='customer',transcript=text,is_final=True))
                isolated.commit()
                result=prepare_turn(isolated,call,text);isolated.commit()
                trace.append({'utterance':text,'intent':classify(text),
                    'action':result.action if result else 'model_required','reply':result.tts_text if result else '',
                    'state':json.loads(state_for(isolated,call).data_json)})
                if result and result.action in {'hangup','handoff'}:break
    finally:engine.dispose()
    return {'trace':trace,'external_actions_executed':False,'policy_hash':hashlib.sha256(payload.policy.model_dump_json().encode()).hexdigest()}


@router.get('/appointments')
def list_appointments(user:User=Depends(require_role('admin')),session:Session=Depends(get_session),page:int=Query(1,ge=1)):
    rows=session.exec(select(CallbackAppointment,CallSession.phone).join(CallSession,CallSession.id==CallbackAppointment.source_call_id)
        .where(CallbackAppointment.tenant_id==user.tenant_id).order_by(CallbackAppointment.scheduled_at.desc()).offset((page-1)*50).limit(50)).all()
    return [{**row.model_dump(mode='json'),'phone':phone} for row,phone in rows]


@router.patch('/appointments/{appointment_id}')
def patch_appointment(appointment_id:UUID,payload:AppointmentPatch,user:User=Depends(require_role('admin')),session:Session=Depends(get_session)):
    row=session.exec(select(CallbackAppointment).where(CallbackAppointment.id==appointment_id,
        CallbackAppointment.tenant_id==user.tenant_id).with_for_update()).first()
    if row is None:raise HTTPException(404,'appointment not found')
    if row.revision!=payload.revision or row.state!='confirmed':raise HTTPException(409,'预约状态已变化')
    if payload.cancel:row.state='cancelled'
    elif payload.scheduled_at:
        value=payload.scheduled_at
        if value.tzinfo is None:raise HTTPException(422,'回拨时间必须包含时区')
        from datetime import timezone,timedelta
        value=value.astimezone(timezone.utc).replace(tzinfo=None)
        source=session.get(CallSession,row.source_call_id)
        policy,_=current_policy(session,user.tenant_id,source.campaign_id)
        if value<=utc_now() or value>utc_now()+timedelta(days=90) or not in_hours(policy,value):raise HTTPException(422,'预约时间不在可用服务时段')
        row.scheduled_at=value
    else:raise HTTPException(422,'需要填写时间或选择取消')
    row.revision+=1;row.updated_at=utc_now();session.add(row)
    if not payload.cancel:
        enqueue_task(session,tenant_id=user.tenant_id,task_type='after_playback',aggregate_id=str(row.source_call_id),
            idempotency_key=f'appointment:{row.id}:{row.revision}',available_at=row.scheduled_at,
            payload={'product_kind':'appointment','appointment_id':str(row.id),'revision':row.revision})
    session.commit();session.refresh(row)
    return row


@router.get('/work-items')
def work_items(user:User=Depends(require_role('admin')),session:Session=Depends(get_session),page:int=Query(1,ge=1)):
    return session.exec(select(ProductWorkItem).where(ProductWorkItem.tenant_id==user.tenant_id)
        .order_by(ProductWorkItem.created_at.desc()).offset((page-1)*50).limit(50)).all()


class WorkPatch(BaseModel):
    state:str=Field(pattern=r'^(open|in_progress|completed)$')
    assigned_to:int|None=None


@router.patch('/work-items/{item_id}')
def patch_work(item_id:UUID,payload:WorkPatch,user:User=Depends(require_role('admin')),session:Session=Depends(get_session)):
    row=session.exec(select(ProductWorkItem).where(ProductWorkItem.id==item_id,ProductWorkItem.tenant_id==user.tenant_id).with_for_update()).first()
    if row is None:raise HTTPException(404,'work item not found')
    if payload.assigned_to:
        agent=session.get(User,payload.assigned_to)
        if agent is None or agent.tenant_id!=user.tenant_id or not agent.enabled:raise HTTPException(422,'负责人不可用')
    row.state=payload.state;row.assigned_to=payload.assigned_to;row.updated_at=utc_now();session.add(row);session.commit()
    return row


class Incoming(BaseModel):
    event_id:str=Field(min_length=1,max_length=100)
    phone:str=Field(min_length=6,max_length=32)
    kind:str=Field(pattern=r'^(sms_reply|inbound_call)$')
    text:str=Field(default='',max_length=4000)


@router.post('/incoming',dependencies=[Depends(check_api_key),Depends(require_roles_if_authenticated('admin'))])
def incoming(payload:Incoming,tenant_id:int=Depends(get_tenant_id_for_request),session:Session=Depends(get_session)):
    from ...services.call_service import normalize_phone
    session.exec(select(Tenant).where(Tenant.id==tenant_id).with_for_update()).one()
    key=f'{tenant_id}:{payload.kind}:{payload.event_id}'
    old=session.exec(select(ProductWorkItem).where(ProductWorkItem.event_key==key)).first()
    if old:
        if old.phone!=normalize_phone(payload.phone) or json.loads(old.detail_json).get('text')!=payload.text:raise HTTPException(409,'事件标识与内容冲突')
        return {'item_id':old.id,'duplicate':True,'route':'human_queue'}
    phone=normalize_phone(payload.phone)
    call=session.exec(select(CallSession).where(CallSession.tenant_id==tenant_id,CallSession.phone==phone)
        .order_by(CallSession.created_at.desc()).limit(1)).first()
    if payload.kind=='sms_reply' and classify(payload.text)=='stop_contact':
        from ...services.conversation_policy import suppress_phone
        from types import SimpleNamespace
        suppress_phone(session,call or SimpleNamespace(tenant_id=tenant_id,phone=phone,id=None),'sms_stop_contact')
    row=ProductWorkItem(tenant_id=tenant_id,event_key=key,call_id=call.id if call else None,
        kind=payload.kind,phone=phone,detail_json=encode({'text':payload.text,'campaign_id':call.campaign_id if call else None}))
    session.add(row);session.commit();session.refresh(row)
    return {'item_id':row.id,'duplicate':False,'route':'human_queue'}


@router.get('/outcomes')
def outcomes(user:User=Depends(require_role('admin')),session:Session=Depends(get_session),page:int=Query(1,ge=1)):
    rows=session.exec(select(ConversationState,CallSession).join(CallSession,ConversationState.call_id==CallSession.id)
        .where(ConversationState.tenant_id==user.tenant_id).order_by(ConversationState.updated_at.desc())
        .offset((page-1)*50).limit(50)).all()
    return [{'call_id':call.id,'phone':call.phone,'attempt':state.attempt,'policy_version_id':state.policy_version_id,
             'status':call.status,'data':json.loads(state.data_json)} for state,call in rows]


class KnowledgeImport(BaseModel):
    title:str=Field(min_length=1,max_length=200)
    content:str=Field(min_length=1,max_length=50000)
    keywords:str=Field(default='',max_length=2000)
    category:str=Field(default='default',max_length=100)
    source:str=Field(default='',max_length=2000)
    valid_from:datetime|None=None
    valid_until:datetime|None=None
    campaign_id:int|None=None


@router.post('/knowledge/import')
def import_knowledge(payload:KnowledgeImport,user:User=Depends(require_role('admin')),session:Session=Depends(get_session)):
    campaign_for(session,user,payload.campaign_id)
    from datetime import timezone
    for field in ('valid_from','valid_until'):
        value=getattr(payload,field)
        if value:
            if value.tzinfo is None:raise HTTPException(422,'有效时间需要时区')
            setattr(payload,field,value.astimezone(timezone.utc).replace(tzinfo=None))
    if payload.valid_from and payload.valid_until and payload.valid_from>=payload.valid_until:raise HTTPException(422,'知识有效期无效')
    row=KnowledgeItem(tenant_id=user.tenant_id,created_by=user.id,**payload.model_dump())
    session.add(row);session.commit();session.refresh(row)
    return row


class KnowledgeProbe(BaseModel):
    query:str=Field(min_length=1,max_length=2000)
    campaign_id:int|None=None


@router.post('/knowledge/search')
def probe_knowledge(payload:KnowledgeProbe,user:User=Depends(require_role('admin')),session:Session=Depends(get_session)):
    from ...services.knowledge import retrieve_knowledge
    campaign_for(session,user,payload.campaign_id)
    return retrieve_knowledge(session,user.tenant_id,payload.query,campaign_id=payload.campaign_id)


@router.get('/funnel')
def funnel(days:int=Query(7,ge=1,le=90),user:User=Depends(require_role('admin')),session:Session=Depends(get_session)):
    from sqlalchemy import func,cast,JSON
    from datetime import timedelta
    from ...models import CallUsage
    cutoff=utc_now()-timedelta(days=days)
    # Group in SQL rather than loading all transcripts into the API process.
    data=cast(ConversationState.data_json,JSON) if session.get_bind().dialect.name=='postgresql' else ConversationState.data_json
    outcome=data.op('->>')('outcome')
    kind=data.op('->>')('answer_kind')
    groups=session.exec(select(outcome,kind,func.count(ConversationState.id),func.count(func.distinct(CallSession.phone)))
        .join(CallSession,CallSession.id==ConversationState.call_id)
        .where(ConversationState.tenant_id==user.tenant_id,ConversationState.updated_at>=cutoff)
        .group_by(outcome,kind)).all()
    answered=session.exec(select(func.count(CallUsage.id),func.count(func.distinct(CallSession.phone)))
        .join(CallSession,CallSession.id==CallUsage.call_session_id)
        .where(CallUsage.tenant_id==user.tenant_id,CallUsage.answered_at>=cutoff)).one()
    return {'days':days,'answered_attempts':answered[0],'answered_phones':answered[1],
        'groups':[{'outcome':o or 'pending','answer_kind':k or 'unknown','attempts':n,'unique_phones':phones} for o,k,n,phones in groups],
        'definitions':'接通按计费接听记录；业务结果按最近更新时间，每组号码去重；同一号码可跨组出现，不能相加作为总人数。'}
