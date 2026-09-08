#!/usr/bin/env python3
"""Configurable real async AI processes with delayed synthetic model requests.
Requires a fresh, explicitly prepared localhost node200ai PostgreSQL database
and localhost test Redis at the ports below. Never connects to carrier/model.
Run with the backend Python environment. Test credentials are public fixtures.
"""
import asyncio,json,os,signal,subprocess,sys,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
WORKERS=int(os.environ.get('SYNTHETIC_AI_WORKERS','2'))
SLOTS=int(os.environ.get('SYNTHETIC_AI_SLOTS','128'))
CALLS=int(os.environ.get('SYNTHETIC_AI_CALLS','256'))
if not 1<=WORKERS<=8 or not 1<=SLOTS<=256 or not 1<=CALLS<=WORKERS*SLOTS:
    raise ValueError('invalid synthetic AI workload')
OUT=Path(os.environ.get('SYNTHETIC_OUTPUT_DIR',str(ROOT/'artifacts/node200'))).resolve()
OUT.mkdir(parents=True,exist_ok=True)
import re
TEST_DB=os.environ.get('NODE200_TEST_DB','node200ai')
if not re.fullmatch(r'node200[a-z0-9_-]*',TEST_DB):raise ValueError('dedicated node200 test database required')
DSN=f'postgresql+psycopg://node200:synthetic-node200-local@127.0.0.1:15443/{TEST_DB}'
env=dict(os.environ,ENV='test',DATABASE_URL=DSN,DATABASE_URL_API=DSN,DATABASE_URL_BOOTSTRAP=DSN,
 REDIS_URL='redis://127.0.0.1:16443/4',TASK_WORKER_ROLE='ai',TASK_AI_CONCURRENCY=str(SLOTS),
 AI_DB_THREADS='2',AI_ACTION_THREADS='4',DATABASE_POOL_SIZE='5',DATABASE_MAX_OVERFLOW='0',
 AI_AGENT_URL='http://127.0.0.1:18670',AI_CALLBACK_TIMEOUT_SEC='45',TASK_TIMEOUT_SEC='60',
 TASK_LEASE_SEC='30',AI_TURN_LOCK_TTL_SEC='30',TASK_POLL_INTERVAL_SEC='.05',SCHEDULER_ENABLED='false',
 TASK_INLINE_EXECUTION_ENABLED='false',DEMO_USERS_ENABLED='true',TELEPHONY_PROVIDER='mock',
 TELEPHONY_WEBHOOK_BASE='http://127.0.0.1:9',PYTHONPATH=str(ROOT/'backend'),LOG_LEVEL='WARNING')
os.environ.update(env);sys.path.insert(0,str(ROOT/'backend'))
import httpx,psycopg
from app.db import create_db_and_tables,session_scope
from app.main import _bootstrap_default_tenant
from app.models import CallSession,CallStatus,CallMode,TaskOutbox,TaskState
from sqlmodel import select
from sqlalchemy import func
create_db_and_tables();_bootstrap_default_tenant()
with session_scope() as session:
    if session.exec(select(TaskOutbox.id).limit(1)).first() is not None:
        raise RuntimeError('requires a fresh dedicated node200ai test database; never clears data automatically')
    for i in range(CALLS):
        call=CallSession(tenant_id=1,phone='13800000000',mode=CallMode.AI_ONLY,status=CallStatus.IN_AI,attempts=1)
        session.add(call);session.flush()
        task=TaskOutbox(tenant_id=1,task_type='ai_turn',aggregate_id=str(call.id),idempotency_key=f'node200-{i}',
            payload_json=json.dumps({'call_id':str(call.id),'attempt':1,'transcript':'synthetic'}))
        session.add(task)
    session.commit()
processes=[];logs=[]
def launch(args,cwd,extra={}):
    log=(OUT/f'ai-process-{len(processes)}.log').open('w');logs.append(log)
    p=subprocess.Popen(args,cwd=cwd,env=dict(env,**extra),stdout=log,stderr=subprocess.STDOUT);processes.append(p);return p
async def main():
    observations=[];dbstates=[]
    agent=launch([sys.executable,'-m','uvicorn','node200_agent_fixture:app','--host','127.0.0.1','--port','18670','--no-access-log'],ROOT,{'PYTHONPATH':str(ROOT/'scripts/fixtures')})
    async with httpx.AsyncClient(trust_env=False) as http:
        for _ in range(100):
            try:
                if (await http.get('http://127.0.0.1:18670/stats')).status_code==200:break
            except httpx.HTTPError:pass
            await asyncio.sleep(.1)
        workers=[launch([sys.executable,'-m','app.ai_worker'],ROOT/'backend',{'AI_WORKER_HEALTH_PATH':str(OUT/f'ai-health-{i}.json')}) for i in range(WORKERS)]
        started=time.monotonic()
        conn=await psycopg.AsyncConnection.connect(DSN.replace('postgresql+psycopg:','postgresql:'),autocommit=True)
        try:
            for _ in range(600):
                stats=(await http.get('http://127.0.0.1:18670/stats')).json();observations.append(stats)
                cursor=await conn.execute("SELECT state,count(*) FROM pg_stat_activity WHERE datname=%s AND pid<>pg_backend_pid() GROUP BY state",(TEST_DB,))
                dbstates.append({'model_active':stats['active'],'states':dict(await cursor.fetchall())})
                with session_scope() as s:
                    states={str(k.value):v for k,v in s.exec(select(TaskOutbox.state,func.count()).group_by(TaskOutbox.state)).all()}
                if states.get('completed')==CALLS:break
                if any(p.poll() is not None for p in workers):raise RuntimeError('AI worker exited; inspect logs')
                await asyncio.sleep(.1)
            assert stats['peak']==CALLS and states.get('completed')==CALLS,(stats,states)
            with session_scope() as s:
                attempts=list(s.exec(select(TaskOutbox.attempts)).all())
            assert max(attempts)==1
            result={'ai_worker_processes':WORKERS,'slots_per_process':SLOTS,'model_peak':stats['peak'],
              'unique_calls':stats['unique_calls'],'task_states':states,'max_task_attempts':max(attempts),
              'elapsed_seconds':time.monotonic()-started,'model_delay_seconds':3,
              'db_threads_per_process':2,'action_threads_per_process':4,'pool_connections_per_process':5,
              'db_while_all_models_wait':[s for s in dbstates if s['model_active']==CALLS][:5],
              'real_llm_sip_audio':False,'passed':True}
            (OUT/'ai-processes.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
        finally:await conn.close()
try:asyncio.run(main())
finally:
    for p in processes:
        if p.poll() is None:p.terminate()
    for p in processes:
        try:p.wait(timeout=70)
        except subprocess.TimeoutExpired:p.kill();p.wait()
    for log in logs:log.close()
