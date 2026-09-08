#!/usr/bin/env python3
"""Six API processes, four AI workers, 500 synthetic calls, durable mixed callbacks.
Requires fresh node200single500dialogue PostgreSQL at 15443 and test Redis 16443;
NGINX at 18900 -> 18910..18915. NO SIP, ASR, TTS, real model or existing data.
Set SINGLE500_ISOLATED_MOCK=true. Uses public synthetic credentials only.
"""
import asyncio,json,os,subprocess,sys,time,hashlib,hmac,signal
from pathlib import Path
from collections import Counter
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'artifacts/single-host-500'
import re
LABEL=os.environ.get('SINGLE500_LOAD_LABEL','dialogue')
TEST_DB=os.environ.get('SINGLE500_TEST_DB','node200single500dialogue')
RATE=int(os.environ.get('SINGLE500_TURN_RATE','200'));SECONDS=30;TOTAL=RATE*SECONDS
assert re.fullmatch(r'[a-z0-9_-]+',LABEL) and re.fullmatch(r'node200single500[a-z0-9_]+',TEST_DB) and 1<=RATE<=400
DEST=OUT/LABEL;DEST.mkdir(parents=True,exist_ok=True)
REPORT=ROOT/'docs/reviews/evidence/20260908-single-host-500'/f'{LABEL}-results.json'
assert os.environ.get('SINGLE500_ISOLATED_MOCK')=='true', 'explicit isolated mock confirmation required'
DSN=f'postgresql+psycopg://node200:synthetic-node200-local@127.0.0.1:15443/{TEST_DB}'
env=dict(os.environ,ENV='test',DATABASE_URL=DSN,DATABASE_URL_API=DSN,DATABASE_URL_BOOTSTRAP=DSN,
 REDIS_URL='redis://127.0.0.1:16443/9',TELEPHONY_PROVIDER='mock',TELEPHONY_WEBHOOK_BASE='http://127.0.0.1:18900',
 TELEPHONY_WEBHOOK_TOKEN='node200-synthetic-token',TELEPHONY_WEBHOOK_SECRET='node200-synthetic-secret',
 AI_AGENT_URL='http://127.0.0.1:18941',AI_CALLBACK_TIMEOUT_SEC='30',TASK_TIMEOUT_SEC='60',
 TASK_LEASE_SEC='30',AI_TURN_LOCK_TTL_SEC='30',SCHEDULER_ENABLED='false',TASK_INLINE_EXECUTION_ENABLED='false',
 DEMO_USERS_ENABLED='true',TRUSTED_HOSTS='*',RATE_LIMIT_ENABLED='false',DATABASE_POOL_SIZE='6',DATABASE_MAX_OVERFLOW='0',
 REQUEST_ADMISSION_TOTAL_INFLIGHT='6',REQUEST_ADMISSION_WEBHOOK_INFLIGHT='5',REQUEST_ADMISSION_DEFAULT_INFLIGHT='1',
 REQUEST_ADMISSION_MAX_WAITERS='8',REQUEST_ADMISSION_TIMEOUT_SEC='.05',LOG_LEVEL='WARNING',
 PYTHONPATH=str(ROOT/'backend')+':'+str(ROOT/'scripts/fixtures'),LOAD_ARTIFACT_DIR=str(DEST),TASK_POLL_INTERVAL_SEC='.05')
os.environ.update(env);sys.path.insert(0,str(ROOT/'backend'))
from app.db import create_db_and_tables,session_scope
from app.main import _bootstrap_default_tenant
from app.models import CallSession,CallStatus,CallMode,TaskOutbox,TaskState,CallMetric,SpeechTurn,WebhookEventIngest
from sqlalchemy import func
from sqlmodel import select
import httpx
sys.path.insert(0,str(ROOT))
from voice_gateway.app.security import CallbackSender
from voice_gateway.app.config import Settings as VoiceSettings
create_db_and_tables();_bootstrap_default_tenant()
with session_scope() as s:
    assert s.exec(select(CallSession.id).limit(1)).first() is None, 'requires fresh dedicated test database'
    calls=[CallSession(tenant_id=1,phone='13800000000',mode=CallMode.AI_ONLY,status=CallStatus.IN_AI,attempts=1,voice_ai_pipeline='pipecat') for _ in range(500)]
    ids=[str(c.id) for c in calls];s.add_all(calls);s.commit()
processes=[];logs=[]
def launch(args,extra={}):
    log=(DEST/f'process-{len(processes)}.log').open('w');logs.append(log)
    p=subprocess.Popen(args,cwd=ROOT/'backend',env=dict(env,**extra),stdout=log,stderr=subprocess.STDOUT);processes.append(p);return p
async def main():
    for port in (18910,18911,18912,18913,18914,18915):launch([sys.executable,'-m','uvicorn','single500_instrumented_api:app','--host','127.0.0.1','--port',str(port),'--no-access-log'])
    launch([sys.executable,'-m','uvicorn','single500_model_fixture:app','--host','127.0.0.1','--port','18941','--no-access-log'])
    for i in range(4):launch([sys.executable,'-m','app.ai_worker'],dict(TASK_WORKER_ROLE='ai',TASK_AI_CONCURRENCY='160',DATABASE_POOL_SIZE='5',AI_ACTION_THREADS='4',AI_WORKER_HEALTH_PATH=str(DEST/f'health-{i}.json')))
    statuses=Counter();retries=Counter();latencies=[];lags=[];pids=Counter();failed=[];jobs=set();sem=asyncio.Semaphore(64)
    async with httpx.AsyncClient(trust_env=False,timeout=10,limits=httpx.Limits(max_connections=100)) as http:
        for _ in range(100):
            try:
                # Do not send load to the proxy until every API is ready.
                # One healthy upstream previously hid five starting processes.
                direct = await asyncio.gather(*(http.get(f'http://127.0.0.1:{port}/readyz') for port in range(18910,18916)))
                ready=all(r.status_code==200 for r in direct) and (await http.get('http://127.0.0.1:18941/stats')).status_code==200
                if ready and all((DEST/f'health-{i}.json').exists() for i in range(4)):break
            except httpx.HTTPError:pass
            await asyncio.sleep(.1)
        else:raise RuntimeError('dialogue fixture not ready')
        ledger_dir=Path(os.environ.get('SINGLE500_LEDGER_DIR',str(DEST)))
        ledger_dir.mkdir(parents=True,exist_ok=True)
        cfg=VoiceSettings(_env_file=None,voice_security_db_path=str(ledger_dir/'callback-ledger.db'),
            voice_callback_base_url='http://127.0.0.1:18900',voice_callback_concurrency=32,
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
        async def observe(url,body):
            begin=time.monotonic()
            try:
                await original_send(url,body);statuses['200']+=1
                latencies.append((time.monotonic()-begin)*1000)
            except httpx.HTTPStatusError as exc:
                statuses[str(exc.response.status_code)]+=1;raise
            except httpx.HTTPError:
                statuses['network_error']+=1;raise
        sender._send=observe
        await sender.start()
        queue_samples=[]
        async def sample_queue():
            while True:
                sample=await asyncio.to_thread(sender.ledger.summary)
                sample['at']=time.time();queue_samples.append(sample)
                await asyncio.sleep(1)
        observer=asyncio.create_task(sample_queue())
        async def post(kind,body):
            await sender.post('http://127.0.0.1:18900/api/v1/webhooks/telephony/'+kind,body)
        start=time.monotonic()
        async def turn(index,scheduled):
            async with sem:
                lags.append((time.monotonic()-scheduled)*1000)
                cid=ids[index%500];event=f'dialogue-{index}'
                await post('speech',dict(call_id=cid,event_id=event,attempt=1,transcript='您好，我想了解服务内容',is_final=True,confidence=.99))
                for step,state in enumerate(('speaking','listening')):
                    await post('media',dict(call_id=cid,event_id=f'{event}-media-{step}',attempt=1,event_sequence=index*2+step+1,state=state,provider_session_id='synthetic-'+cid))
        for index in range(TOTAL):
            scheduled=start+index/RATE;await asyncio.sleep(max(0,scheduled-time.monotonic()))
            job=asyncio.create_task(turn(index,scheduled));jobs.add(job)
        await asyncio.gather(*jobs)
        for _ in range(600):
            with session_scope() as s:
                states={k.value:v for k,v in s.exec(select(TaskOutbox.state,func.count()).where(TaskOutbox.task_type=='ai_turn').group_by(TaskOutbox.state)).all()}
            if states.get('completed')==TOTAL and (await asyncio.to_thread(sender.ledger.summary))['pending_callbacks']==0:break
            await asyncio.sleep(.1)
        observer.cancel();await asyncio.gather(observer,return_exceptions=True)
        await sender.stop()
        queue=sender.ledger.summary()
        stats=(await http.get('http://127.0.0.1:18941/stats')).json()
        with session_scope() as s:
            turns=s.exec(select(func.count()).select_from(SpeechTurn).where(SpeechTurn.is_final.is_(True),SpeechTurn.speaker_role=='customer')).one()
            ai_turns=s.exec(select(func.count()).select_from(SpeechTurn).where(SpeechTurn.is_final.is_(True),SpeechTurn.speaker_role=='ai')).one()
            media=s.exec(select(func.count()).select_from(WebhookEventIngest).where(WebhookEventIngest.event_type=='media')).one()
            call_states={k.value:v for k,v in s.exec(select(CallSession.status,func.count()).group_by(CallSession.status)).all()}
            failures=s.exec(select(func.count()).select_from(CallMetric).where(CallMetric.success.is_(False))).one()
            attempts=s.exec(select(func.max(TaskOutbox.attempts)).where(TaskOutbox.task_type=='ai_turn')).one()
            metrics=s.exec(select(func.count()).select_from(CallMetric).where(CallMetric.stage=='ai.turn',CallMetric.success.is_(True))).one()
        q=lambda a,p:sorted(a)[min(len(a)-1,int(len(a)*p))] if a else 0
        result=dict(synthetic_active_calls=500,final_transcripts_per_second=RATE,media_events_per_second=RATE*2,duration_seconds=30,
            network_path='loopback-direct-round-robin' if direct else os.environ.get('SINGLE500_NETWORK_LABEL','docker-desktop-nginx-to-host'),
            callback_ledger_storage='explicit-local-volume' if 'SINGLE500_LEDGER_DIR' in os.environ else 'artifact-directory',
            all_delivery_statuses_including_retries=dict(statuses),pending_callbacks=queue['pending_callbacks'],oldest_callback_age_sec=queue['oldest_callback_age_sec'],durable_commit_batches=sender.writer.batches,durable_operations=sender.writer.operations,
            delivery_http_p99_ms=q(latencies,.99),generator_lag_p99_ms=q(lags,.99),queue_samples=queue_samples,
            capacity_slo_passed=queue['pending_callbacks']==0 and sum(v for k,v in statuses.items() if k!='200')==0 and max((r['oldest_callback_age_sec'] for r in queue_samples),default=0)<=1,
            task_states=states,final_transcripts=turns,successful_ai_metrics=metrics,model=stats,
            ai_transcripts=ai_turns,media_ingest_events=media,call_states=call_states,failed_metrics=failures,ai_max_attempts=attempts,
            sender_stage_ms={k:{'count':len(v),'sum':sum(v),'p50':q(v,.5),'p99':q(v,.99)} for k,v in sender_timings.items()},
            elapsed_with_drain_seconds=time.monotonic()-start,real_sip_rtp_asr_tts_llm=False,
            correctness_passed=queue['pending_callbacks']==0 and statuses['200']==TOTAL*3 and states.get('completed')==TOTAL
                and turns==TOTAL and media==TOTAL*2 and call_states=={'in_ai':500} and failures==0)
        REPORT.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
        if not result['correctness_passed'] or not result['capacity_slo_passed']:
            raise SystemExit('mixed callback correctness or capacity SLO failed')
try:asyncio.run(main())
finally:
    # Drain workers while API and synthetic model are still reachable.
    for group in (processes[7:],processes[:7]):
        for p in group:
            if p.poll() is None:p.terminate()
        for p in group:
            try:p.wait(timeout=40)
            except subprocess.TimeoutExpired:p.kill();p.wait()
    for log in logs:log.close()
