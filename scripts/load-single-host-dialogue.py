#!/usr/bin/env python3
"""Six API processes, four AI workers, 500 synthetic calls, durable mixed callbacks.
Requires fresh node200single500dialogue PostgreSQL at 15443 and test Redis 16443;
NGINX at 18900 -> 18910..18915. NO SIP, ASR, TTS, real model or existing data.
Set SINGLE500_ISOLATED_MOCK=true. Uses public synthetic credentials only.
"""
import asyncio,json,os,subprocess,sys,time,hashlib,hmac,signal
import importlib.metadata
from pathlib import Path
from collections import Counter
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'artifacts/single-host-500'
import re
LABEL=os.environ.get('SINGLE500_LOAD_LABEL','dialogue')
TEST_DB=os.environ.get('SINGLE500_TEST_DB','node200single500dialogue')
RATE=int(os.environ.get('SINGLE500_TURN_RATE','200'));SECONDS=int(os.environ.get('SINGLE500_LOAD_SECONDS','30'));TOTAL=RATE*SECONDS
BATCH_CALLBACKS=os.environ.get('SINGLE500_BATCH_CALLBACKS','false')=='true'
SCENARIO=os.environ.get('SINGLE500_SCENARIO','mixed')
ROUNDS=int(os.environ.get('SINGLE500_CONVERSATION_ROUNDS','5'))
TURN_GAP=float(os.environ.get('SINGLE500_TURN_GAP_SEC','6.25'))
assert SCENARIO in {'mixed','conversation'} and 1<=ROUNDS<=100 and 1<=TURN_GAP<=60
if SCENARIO=='conversation':TOTAL=500*ROUNDS
EVENT_LOOP=os.environ.get('SINGLE500_EVENT_LOOP','asyncio')
assert EVENT_LOOP in {'asyncio','uvloop'}
assert re.fullmatch(r'[a-z0-9_-]+',LABEL) and re.fullmatch(r'node200single500[a-z0-9_]+',TEST_DB) and 1<=RATE<=400 and 10<=SECONDS<=3600
DEST=OUT/LABEL;DEST.mkdir(parents=True,exist_ok=True)
SOURCE_HASHES={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'backend/app/db.py', ROOT/'backend/app/models.py', ROOT/'backend/app/services/callback_inbox.py', ROOT/'backend/app/callback_inbox_worker.py', ROOT/'backend/app/services/realtime_voice.py', ROOT/'backend/app/services/dispatcher.py', ROOT/'voice_gateway/app/security.py', ROOT/'voice_gateway/app/durable_batch.py', ROOT/'scripts/load-single-host-dialogue.py')}
for service in ('backend','voice_gateway','agent','recording_adapter'):
    p=ROOT/service/'pyproject.toml';SOURCE_HASHES[str(p.relative_to(ROOT))]=hashlib.sha256(p.read_bytes()).hexdigest()
for name in ('backend/app/schemas.py','backend/app/api/routers/webhooks.py','backend/app/main.py','backend/app/config.py','voice_gateway/app/config.py',
             'backend/app/services/telephony.py','backend/app/services/async_ai.py','backend/app/services/worker_runtime.py',
             'backend/app/ai_worker.py',
             'scripts/fixtures/single500_instrumented_ai.py',
             'agent/app/main.py','agent/app/llm.py','agent/app/quota.py','scripts/fixtures/single500_cloud_and_playback.py','scripts/fixtures/single500_real_agent.py'):
    SOURCE_HASHES[name]=hashlib.sha256((ROOT/name).read_bytes()).hexdigest()
for folder in ('backend/app','voice_gateway/app','agent/app','recording_adapter/app','scripts/fixtures'):
    for p in sorted((ROOT/folder).rglob('*.py')):
        SOURCE_HASHES[str(p.relative_to(ROOT))]=hashlib.sha256(p.read_bytes()).hexdigest()
for p in sorted(ROOT.glob('docker-compose*.yml')):
    SOURCE_HASHES[str(p.relative_to(ROOT))]=hashlib.sha256(p.read_bytes()).hexdigest()
RUNTIME_DEPENDENCIES={}
for name in ('fastapi','anyio','httpx','httpcore','sniffio','uvloop','sqlmodel','psycopg'):
    try:RUNTIME_DEPENDENCIES[name]=importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:RUNTIME_DEPENDENCIES[name]='missing'
REPORT=ROOT/os.environ.get('SINGLE500_REPORT_DIR','docs/reviews/evidence/20260908-single-host-500')/f'{LABEL}-results.json'
assert not REPORT.exists(), 'preserve previous capacity evidence'
assert os.environ.get('SINGLE500_ISOLATED_MOCK')=='true', 'explicit isolated mock confirmation required'
DSN=f'postgresql+psycopg://node200:synthetic-node200-local@127.0.0.1:15443/{TEST_DB}'
env=dict(os.environ,ENV='test',DATABASE_URL=DSN,DATABASE_URL_API=DSN,DATABASE_URL_BOOTSTRAP=DSN,
 REDIS_URL='redis://127.0.0.1:16443/9',TELEPHONY_PROVIDER='mock',TELEPHONY_WEBHOOK_BASE='http://127.0.0.1:18900',
 TELEPHONY_WEBHOOK_TOKEN='node200-synthetic-token',TELEPHONY_WEBHOOK_SECRET='node200-synthetic-secret',
 AI_AGENT_URL='http://127.0.0.1:18941',AI_CALLBACK_TIMEOUT_SEC='30',TASK_TIMEOUT_SEC='60',
 TASK_LEASE_SEC='30',AI_TURN_LOCK_TTL_SEC='30',SCHEDULER_ENABLED='false',TASK_INLINE_EXECUTION_ENABLED='false',
 DEMO_USERS_ENABLED='true',TRUSTED_HOSTS='*',RATE_LIMIT_ENABLED='false',CALLBACK_INBOX_ENABLED='true',DATABASE_POOL_SIZE='5',DATABASE_MAX_OVERFLOW='0',
 REQUEST_ADMISSION_TOTAL_INFLIGHT='5',REQUEST_ADMISSION_WEBHOOK_INFLIGHT='4',REQUEST_ADMISSION_DEFAULT_INFLIGHT='1',
 REQUEST_ADMISSION_MAX_WAITERS='8',REQUEST_ADMISSION_TIMEOUT_SEC='.05',LOG_LEVEL='WARNING',CALLBACK_INBOX_MIN_WORKERS='6',
 PYTHONPATH=str(ROOT/'backend')+':'+str(ROOT/'scripts/fixtures'),LOAD_ARTIFACT_DIR=str(DEST),TASK_POLL_INTERVAL_SEC='.05')
if SCENARIO=='conversation':
    env.update(AI_AGENT_URL='http://127.0.0.1:18940',TELEPHONY_PROVIDER='http',
        TELEPHONY_PROVIDER_ENDPOINT='http://127.0.0.1:18942',TELEPHONY_TIMEOUT_SEC='10',
        AI_AGENT_SERVICE_TOKEN='synthetic-agent-token-'+'a'*32,
        TELEPHONY_SERVICE_TOKEN='synthetic-playback-token-'+'p'*32,VOICE_COMMAND_SECRET='synthetic-command-'+'c'*32)
os.environ.update(env);sys.path.insert(0,str(ROOT/'backend'))
from app.db import create_db_and_tables,session_scope
from app.clock import utc_now
from app.main import _bootstrap_default_tenant
from app.models import CallSession,CallStatus,CallMode,TaskOutbox,TaskState,CallMetric,SpeechTurn,WebhookEventIngest,AdminSetting
from sqlalchemy import func
from sqlmodel import select
import httpx
sys.path.insert(0,str(ROOT))
from voice_gateway.app.security import CallbackSender, canonical
from voice_gateway.app.config import Settings as VoiceSettings
create_db_and_tables();_bootstrap_default_tenant()
with session_scope() as s:
    assert s.exec(select(CallSession.id).limit(1)).first() is None, 'requires fresh dedicated test database'
    calls=[CallSession(tenant_id=1,phone='13800000000',mode=CallMode.AI_ONLY,status=CallStatus.IN_AI,attempts=1,voice_ai_pipeline='pipecat') for _ in range(500)]
    ids=[str(c.id) for c in calls];s.add_all(calls);s.commit()
    if SCENARIO=='conversation':
        from app.services.admin_settings import SETTING_DEFAULTS
        ai={**SETTING_DEFAULTS['ai'],'enabled':True,'external_llm_enabled':True,'llm_provider':'openai-compatible',
            'llm_model':'synthetic-model','agent_url':env['AI_AGENT_URL']}
        s.add(AdminSetting(tenant_id=1,section='ai',data_json=json.dumps(ai)));s.commit()
processes=[];logs=[];roles={name:[] for name in ('api','support','ai','inbox')}
def launch(args,extra={},role='support',cwd=None):
    log=(DEST/f'process-{len(processes)}.log').open('w');logs.append(log)
    p=subprocess.Popen(args,cwd=cwd or ROOT/'backend',env=dict(env,**extra),stdout=log,stderr=subprocess.STDOUT);processes.append(p);roles[role].append(p);return p
async def main():
    for port in (18910,18911,18912,18913,18914,18915):launch([sys.executable,'-m','uvicorn','single500_instrumented_api:app','--host','127.0.0.1','--port',str(port),'--no-access-log'],role='api')
    model_port=18942 if SCENARIO=='conversation' else 18941
    launch([sys.executable,'-m','uvicorn','single500_cloud_and_playback:app' if SCENARIO=='conversation' else 'single500_model_fixture:app','--host','127.0.0.1','--port',str(model_port),'--no-access-log'])
    if SCENARIO=='conversation':
        account_dir=Path(os.environ.get('SINGLE500_LEDGER_DIR',str(DEST)));account_dir.mkdir(parents=True,exist_ok=True)
        for port in (18941,18943):
            launch([sys.executable,'-m','uvicorn','single500_real_agent:app','--host','127.0.0.1','--port',str(port),'--no-access-log'],
                dict(PYTHONPATH=str(ROOT/'agent')+':'+str(ROOT/'scripts/fixtures'),SERVICE_TOKEN=env['AI_AGENT_SERVICE_TOKEN'],
                    LLM_PROVIDER='openai-compatible',OPENAI_BASE_URL='http://127.0.0.1:18942/v1',OPENAI_API_KEY='synthetic-key',
                    OPENAI_MODEL='synthetic-model',LLM_ALLOWED_HOSTS='127.0.0.1',LLM_REQUIRE_HTTPS='false',
                    LLM_QUOTA_DB_PATH=str(account_dir/'model-account.db'),LLM_QUOTA_SCOPE='synthetic-shared-account',
                    LLM_QUOTA_RPM='20000',LLM_QUOTA_TPM='100000000',LLM_QUOTA_RPS='1000',
                    LLM_MAX_CONNECTIONS='320',LLM_MAX_KEEPALIVE_CONNECTIONS='160',MAX_OUTPUT_TOKENS='200',OPENAI_TIMEOUT_SEC='15'),cwd=ROOT/'agent')
    for i in range(4):launch([sys.executable,'-m','single500_instrumented_ai'],dict(TASK_WORKER_ROLE='ai',TASK_AI_CONCURRENCY='160',DATABASE_POOL_SIZE='5',AI_DB_THREADS='2',AI_ACTION_THREADS='8',AI_WORKER_HEALTH_PATH=str(DEST/f'health-{i}.json')),role='ai')
    for i in range(6):launch([sys.executable,'-m','app.callback_inbox_worker','--shards','6','--shard-index',str(i)],dict(DATABASE_POOL_SIZE='1',CALLBACK_INBOX_HEALTH_PATH=str(DEST/f'inbox-health-{i}.json')),role='inbox')
    statuses=Counter();http_statuses=Counter();batch_sizes=[];retries=Counter();latencies=[];lags=[];pids=Counter();failed=[];jobs=set();sem=asyncio.Semaphore(64)
    async with httpx.AsyncClient(trust_env=False,timeout=10,limits=httpx.Limits(max_connections=100)) as http:
        for _ in range(100):
            try:
                # Do not send load to the proxy until every API is ready.
                # One healthy upstream previously hid five starting processes.
                direct = await asyncio.gather(*(http.get(f'http://127.0.0.1:{port}/readyz') for port in range(18910,18916)))
                ready=all(r.status_code==200 for r in direct) and (await http.get(f'http://127.0.0.1:{model_port}/stats')).status_code==200
                if ready and SCENARIO=='conversation':
                    ready=all(r.status_code==200 for r in await asyncio.gather(*(http.get(f'http://127.0.0.1:{p}/fixture/stats') for p in (18941,18943))))
                if (ready and all((DEST/f'health-{i}.json').exists() for i in range(4))
                        and all((DEST/f'inbox-health-{i}.json').exists() for i in range(6))):break
            except httpx.HTTPError:pass
            await asyncio.sleep(.1)
        else:raise RuntimeError('dialogue fixture not ready: ' + repr([(r.status_code,r.text[:500]) for r in direct]))
        ledger_dir=Path(os.environ.get('SINGLE500_LEDGER_DIR',str(DEST)))
        ledger_dir.mkdir(parents=True,exist_ok=True)
        cfg=VoiceSettings(_env_file=None,voice_security_db_path=str(ledger_dir/'callback-ledger.db'),
            voice_callback_base_url='http://127.0.0.1:18900',voice_callback_concurrency=24,
            voice_callback_batch_enabled=BATCH_CALLBACKS,
            voice_callback_poll_sec=.01,webhook_token=env['TELEPHONY_WEBHOOK_TOKEN'],
            webhook_secret=env['TELEPHONY_WEBHOOK_SECRET'])
        assert not Path(cfg.voice_security_db_path).exists(), 'requires fresh callback journal'
        sender=CallbackSender(cfg)
        direct = os.environ.get('SINGLE500_DIRECT_API') == 'true'
        if direct:
            class DirectAPIs(httpx.AsyncHTTPTransport):
                index = 0
                async def handle_async_request(self, request):
                    assert request.url.host == '127.0.0.1' and request.url.port == 18900
                    request.url = request.url.copy_with(port=18910+self.index%6)
                    self.index += 1
                    return await super().handle_async_request(request)
            sender.client = httpx.AsyncClient(transport=DirectAPIs(limits=httpx.Limits(max_connections=32,max_keepalive_connections=32)),
                                             trust_env=False,timeout=10,follow_redirects=False)
        sender_timings = {'commit': [], 'claim': []}
        for name, attribute in [('commit', '_commit_batch'), ('claim', '_ready_rows')]:
            original = getattr(sender, attribute)
            def timed(*args, _name=name, _original=original):
                begin = time.monotonic()
                try: return _original(*args)
                finally: sender_timings[_name].append((time.monotonic()-begin)*1000)
            setattr(sender, attribute, timed)
        sender.writer.commit = sender._commit_batch
        original_send=sender._send
        enqueued_at={};delivery_ages=[]
        async def observe(url,body):
            begin=time.monotonic()
            wire=json.loads(body)
            events=[(url.rsplit('/',1)[0]+'/'+e['kind'],canonical(e['payload'])) for e in wire['events']] if url.endswith('/batch') else [(url,body)]
            batch_sizes.append(len(events))
            try:
                response=await original_send(url,body);statuses['200']+=len(events);http_statuses['200']+=1
                latencies.append((time.monotonic()-begin)*1000)
                for key in events:
                    enqueued=enqueued_at.pop(key,None)
                    if enqueued is not None:delivery_ages.append((time.monotonic()-enqueued)*1000)
                return response
            except httpx.HTTPStatusError as exc:
                statuses[str(exc.response.status_code)]+=len(events);http_statuses[str(exc.response.status_code)]+=1;raise
            except httpx.HTTPError:
                statuses['network_error']+=len(events);http_statuses['network_error']+=1;raise
        sender._send=observe
        await sender.start()
        queue_samples=[]
        from app.services.callback_inbox import snapshot as inbox_snapshot
        def read_inbox():
            with session_scope() as s: return inbox_snapshot(s)
        inbox_samples=[]
        async def sample_queue():
            while True:
                sample=await asyncio.to_thread(sender.ledger.summary)
                sample.update(at=time.time(), delivery_inflight=len(sender._inflight),
                              writer_queued=sender.writer.queue.qsize());queue_samples.append(sample)
                inbox_samples.append(await asyncio.to_thread(read_inbox))
                await asyncio.sleep(1)
        observer=asyncio.create_task(sample_queue())
        sent_speech=0
        async def post(kind,body):
            nonlocal sent_speech
            url='http://127.0.0.1:18900/api/v1/webhooks/telephony/'+kind
            enqueued_at[(url,canonical(body))]=time.monotonic()
            await sender.post(url,body)
            if kind=='speech':sent_speech+=1
        start=time.monotonic()
        reply_latencies=[];reply_events={};dialogue_errors=[];round_latencies={i:[] for i in range(1,ROUNDS+1)}
        awaiting_replies={}
        deadline_misses=[]
        rate_windows={}
        def count_window(kind, stamp):
            bucket=int(max(0,stamp-start)//10)*10
            counters=rate_windows.setdefault(bucket,dict(planned=0,emitted=0,completed=0))
            counters[kind]+=1
        async def conversation(index):
            cid=ids[index]
            for round_index in range(1,ROUNDS+1):
                scheduled=start+index/RATE+(round_index-1)*TURN_GAP
                await asyncio.sleep(max(0,scheduled-time.monotonic()))
                begin=time.monotonic();lags.append((begin-scheduled)*1000)
                count_window('emitted',begin)
                event_id=f'conversation-{cid}-{round_index}'
                completed=reply_events.setdefault((cid,round_index),asyncio.Event())
                awaiting_replies[(cid,round_index)]=utc_now()
                response=await http.post('http://127.0.0.1:18942/fixture/head',json=dict(call_id=cid,event_id=event_id))
                response.raise_for_status()
                await post('speech',dict(call_id=cid,event_id=event_id,attempt=1,
                    transcript='我想了解这项服务的具体安排',is_final=True,confidence=.99))
                try:await asyncio.wait_for(completed.wait(),45)
                except asyncio.TimeoutError:
                    dialogue_errors.append(dict(call_id=cid,round=round_index,error='no committed AI reply'));return
                finally:awaiting_replies.pop((cid,round_index),None)
                if time.monotonic()>scheduled+TURN_GAP:
                    deadline_misses.append(dict(call_id=cid,round=round_index,late_ms=(time.monotonic()-scheduled-TURN_GAP)*1000))
                count_window('completed',time.monotonic())
                reply_latencies.append((time.monotonic()-begin)*1000)
                round_latencies[round_index].append(reply_latencies[-1])
                for step,state in enumerate(('speaking','listening')):
                    await post('media',dict(call_id=cid,event_id=f'{event_id}-media-{step}',attempt=1,
                        event_sequence=round_index*2+step,state=state,provider_session_id='synthetic-'+cid))

        async def observe_replies():
            # Only a committed AI SpeechTurn can advance the customer. The HTTP
            # playback fixture has already completed before that row is written.
            while True:
                # Only inspect the time window of outstanding customer turns.
                # Scanning every historical reply each 100ms made the load
                # observer itself increasingly expensive in long conversations.
                # A turn's reply cannot predate its input; no ID watermark is
                # used because concurrent transactions can commit out of order.
                since=min(awaiting_replies.values()) if awaiting_replies else None
                def read():
                    if since is None:return []
                    with session_scope() as s:
                        return s.exec(select(SpeechTurn.call_session_id,SpeechTurn.turn_index).where(
                            SpeechTurn.speaker_role=='ai',SpeechTurn.is_final.is_(True),
                            SpeechTurn.created_at>=since,
                            SpeechTurn.transcript=='这项服务支持按需求设置，下面为您介绍具体安排。')).all()
                for cid,sequence in await asyncio.to_thread(read):
                    reply_events.setdefault((str(cid),sequence),asyncio.Event()).set()
                await asyncio.sleep(.1)
        async def turn(index,scheduled):
            async with sem:
                lags.append((time.monotonic()-scheduled)*1000)
                cid=ids[index%500];event=f'dialogue-{index}'
                await post('speech',dict(call_id=cid,event_id=event,attempt=1,transcript='您好，我想了解服务内容',is_final=True,confidence=.99))
                for step,state in enumerate(('speaking','listening')):
                    await post('media',dict(call_id=cid,event_id=f'{event}-media-{step}',attempt=1,event_sequence=index*2+step+1,state=state,provider_session_id='synthetic-'+cid))
        if SCENARIO=='conversation':
            for index in range(500):
                for round_index in range(ROUNDS):
                    count_window('planned',start+index/RATE+round_index*TURN_GAP)
            replies_observer=asyncio.create_task(observe_replies())
            try:await asyncio.gather(*(conversation(i) for i in range(500)))
            finally:replies_observer.cancel();await asyncio.gather(replies_observer,return_exceptions=True)
        else:
            for index in range(TOTAL):
                scheduled=start+index/RATE;await asyncio.sleep(max(0,scheduled-time.monotonic()))
                job=asyncio.create_task(turn(index,scheduled));jobs.add(job)
            await asyncio.gather(*jobs)
        generation_seconds=time.monotonic()-start
        for _ in range(600):
            with session_scope() as s:
                states={k.value:v for k,v in s.exec(select(TaskOutbox.state,func.count()).where(TaskOutbox.task_type=='ai_turn').group_by(TaskOutbox.state)).all()}
            if (await asyncio.to_thread(read_inbox))['pending']==0 and states.get('completed')==TOTAL and (await asyncio.to_thread(sender.ledger.summary))['pending_callbacks']==0:break
            await asyncio.sleep(.1)
        await asyncio.sleep(1.1)  # Flush consumer commit-inclusive latency heartbeats.
        observer.cancel();await asyncio.gather(observer,return_exceptions=True)
        await sender.stop()
        queue=sender.ledger.summary()
        stats=(await http.get(f'http://127.0.0.1:{model_port}/stats')).json()
        agents=[(await http.get(f'http://127.0.0.1:{p}/fixture/stats')).json() for p in (18941,18943)] if SCENARIO=='conversation' else []
        with session_scope() as s:
            turns=s.exec(select(func.count()).select_from(SpeechTurn).where(SpeechTurn.is_final.is_(True),SpeechTurn.speaker_role=='customer')).one()
            ai_turns=s.exec(select(func.count()).select_from(SpeechTurn).where(SpeechTurn.is_final.is_(True),SpeechTurn.speaker_role=='ai')).one()
            media=s.exec(select(func.count()).select_from(WebhookEventIngest).where(WebhookEventIngest.event_type=='media')).one()
            call_states={k.value:v for k,v in s.exec(select(CallSession.status,func.count()).group_by(CallSession.status)).all()}
            failures=s.exec(select(func.count()).select_from(CallMetric).where(CallMetric.success.is_(False))).one()
            attempts=s.exec(select(func.max(TaskOutbox.attempts)).where(TaskOutbox.task_type=='ai_turn')).one()
            metrics=s.exec(select(func.count()).select_from(CallMetric).where(CallMetric.stage=='ai.turn',CallMetric.success.is_(True))).one()
            durations=s.exec(select(CallMetric.stage,CallMetric.duration_ms).where(CallMetric.success.is_(True),CallMetric.duration_ms.is_not(None))).all()
        inbox_final=await asyncio.to_thread(read_inbox)
        model_transport_retries=sum(p.read_text().count('AI transport retry error_type=')
                                    for p in DEST.glob('process-*.log'))
        q=lambda a,p:sorted(a)[min(len(a)-1,int(len(a)*p))] if a else 0
        stage_timings={stage:{'count':len(values),'p99_ms':q(values,.99),'max_ms':max(values)}
                       for stage in {stage for stage,_ in durations}
                       for values in [[duration for name,duration in durations if name==stage]]}
        result=dict(source_sha256=SOURCE_HASHES,runtime_dependencies=RUNTIME_DEPENDENCIES,generator_event_loop=EVENT_LOOP,synthetic_active_calls=500,final_transcripts_per_second=RATE if SCENARIO=='mixed' else 500/TURN_GAP,media_events_per_second=RATE*2 if SCENARIO=='mixed' else 1000/TURN_GAP,duration_seconds=SECONDS if SCENARIO=='mixed' else None,
            batch_callbacks_enabled=BATCH_CALLBACKS,http_request_statuses=dict(http_statuses),mean_events_per_http_request=sum(batch_sizes)/len(batch_sizes) if batch_sizes else 0,
            scenario=SCENARIO,effective_dialogue_capacity_verified=False,
            observer_query='outstanding-turn-time-window-v2',
            generation_duration_seconds=generation_seconds,
            emitted_final_transcripts=sent_speech,
            generated_transcripts_per_elapsed_second=sent_speech/generation_seconds,
            initial_speech_start_rate=RATE,
            topology={'api_processes':6,'callback_workers':6,'ai_workers':4,'ai_slots':640,
                      'ai_db_threads_per_worker':2,'ai_action_threads_per_worker':8,
                      'real_agent_processes':2 if SCENARIO=='conversation' else 0,
                      'task_workers':0,'pbx_processes':0,'media_workers':0,
                      'application_db_pool_budget':56,'test_observer_db_pool_budget':5},
            conversation_rounds=ROUNDS if SCENARIO=='conversation' else None,
            conversation_errors=dialogue_errors,committed_replies_observed=len(reply_latencies),
            absolute_schedule=True,deadline_misses=deadline_misses,ten_second_windows=rate_windows,
            synthetic_reply_p95_ms=q(reply_latencies,.95),synthetic_reply_p99_ms=q(reply_latencies,.99),
            production_agents=agents,playback_is_simulated=True,
            network_path='loopback-direct-round-robin' if direct else os.environ.get('SINGLE500_NETWORK_LABEL','docker-desktop-nginx-to-host'),
            callback_ledger_storage='explicit-local-volume' if 'SINGLE500_LEDGER_DIR' in os.environ else 'artifact-directory',
            all_delivery_statuses_including_retries=dict(statuses),pending_callbacks=queue['pending_callbacks'],oldest_callback_age_sec=queue['oldest_callback_age_sec'],durable_commit_batches=sender.writer.batches,durable_operations=sender.writer.operations,
            delivery_http_p99_ms=q(latencies,.99),generator_lag_p99_ms=q(lags,.99),queue_samples=queue_samples,
            gateway_delivery_p99_ms=q(delivery_ages,.99),gateway_delivery_max_ms=max(delivery_ages,default=0),
            model_transport_retries=model_transport_retries,
            inbox_final=inbox_final,inbox_samples=inbox_samples,
            capacity_slo_passed=inbox_final['pending']==0 and inbox_final['max_completion_latency_ms']<=1000 and queue['pending_callbacks']==0 and sum(v for k,v in statuses.items() if k!='200')==0 and max((r['oldest_callback_age_sec'] for r in queue_samples),default=0)<=1 and max(delivery_ages,default=0)<=1000 and model_transport_retries==0,
            task_states=states,final_transcripts=turns,successful_ai_metrics=metrics,model=stats,
            ai_transcripts=ai_turns,media_ingest_events=media,call_states=call_states,failed_metrics=failures,ai_max_attempts=attempts,
            stage_timings=stage_timings,
            reply_round_timings={k:{'count':len(v),'p99_ms':q(v,.99),'max_ms':max(v,default=0)} for k,v in round_latencies.items()} if SCENARIO=='conversation' else {},
            ai_work_pool_timings=[json.loads(p.read_text()) for p in DEST.glob('ai-stages-*.json')],
            sender_stage_ms={k:{'count':len(v),'sum':sum(v),'p50':q(v,.5),'p99':q(v,.99)} for k,v in sender_timings.items()},
            elapsed_with_drain_seconds=time.monotonic()-start,real_sip_rtp_asr_tts_llm=False,
            correctness_passed=inbox_final['pending']==0 and inbox_final['dead']==0 and inbox_final['processed']==TOTAL*3 and queue['pending_callbacks']==0 and statuses['200']==TOTAL*3 and states.get('completed')==TOTAL
                and turns==TOTAL and media==TOTAL*2 and call_states=={'in_ai':500} and failures==0)
        if SCENARIO=='conversation':
            result['conversation_correctness_passed']=(not dialogue_errors and len(reply_latencies)==TOTAL
                and metrics==TOTAL and ai_turns==TOTAL+stats['notice_count'] and stats['total']==TOTAL
                and stats['playback_count']==TOTAL and stats['playback_calls']==500
                and sum(a['requests'] for a in agents)==TOTAL and all(a['requests']>0 for a in agents))
            result['synthetic_reply_control_budget_ms']=(stats['model_delay_sec']+stats['playback_delay_sec']+1)*1000
            result['conversation_control_slo_passed']=(result['conversation_correctness_passed'] and not deadline_misses
                and result['synthetic_reply_p99_ms']<=result['synthetic_reply_control_budget_ms'])
            result['correctness_passed'] &= result['conversation_correctness_passed']
        result['runtime_source_unchanged_during_test']=all(hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==value
                                                         for name,value in SOURCE_HASHES.items())
        result['correctness_passed'] &= result['runtime_source_unchanged_during_test']
        REPORT.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
        if (not result['correctness_passed'] or not result['capacity_slo_passed']
                or (SCENARIO=='conversation' and not result['conversation_control_slo_passed'])):
            raise SystemExit('mixed callback correctness or capacity SLO failed')
try:
    if EVENT_LOOP == 'uvloop':
        import uvloop
        uvloop.run(main())
    else:
        asyncio.run(main())
finally:
    # Drain workers while API and synthetic model are still reachable.
    for group in (roles['inbox'],roles['ai'],roles['support'],roles['api']):
        for p in group:
            if p.poll() is None:p.terminate()
        for p in group:
            try:p.wait(timeout=40)
            except subprocess.TimeoutExpired:p.kill();p.wait()
    for log in logs:log.close()
