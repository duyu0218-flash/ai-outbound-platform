"""Start the separate synthetic browser fixture after load tests finish."""
import os, sys, subprocess, signal, json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'artifacts/single-host-500'
DSN='postgresql+psycopg://node200:synthetic-node200-local@127.0.0.1:15443/node200single500inboxui'
assert os.environ.get('SINGLE500_ISOLATED_MOCK') == 'true', 'explicit isolated fixture acknowledgement required'
env=dict(os.environ, ENV='test', DATABASE_URL=DSN, DATABASE_URL_API=DSN,DATABASE_URL_BOOTSTRAP=DSN,
 REDIS_URL='redis://127.0.0.1:16443/8', TELEPHONY_PROVIDER='mock',
 TELEPHONY_WEBHOOK_BASE='http://127.0.0.1:18800',TELEPHONY_PROVIDER_ENDPOINT='',
 TELEPHONY_WEBHOOK_TOKEN='admission-synthetic-token',TELEPHONY_WEBHOOK_SECRET='admission-synthetic-secret',
 AI_AGENT_URL='http://127.0.0.1:18841',LLM_PROVIDER='rule',SCHEDULER_ENABLED='false',
 CALLBACK_INBOX_ENABLED='true',TASK_INLINE_EXECUTION_ENABLED='false',DEMO_USERS_ENABLED='true',TRUSTED_HOSTS='*',RATE_LIMIT_ENABLED='false',
 DATABASE_POOL_SIZE='6',DATABASE_MAX_OVERFLOW='0',REQUEST_ADMISSION_TOTAL_INFLIGHT='6',
 REQUEST_ADMISSION_WEBHOOK_INFLIGHT='5',REQUEST_ADMISSION_DEFAULT_INFLIGHT='1',
 REQUEST_ADMISSION_MAX_WAITERS='8',REQUEST_ADMISSION_TIMEOUT_SEC='.05',LOG_LEVEL='WARNING',
 PYTHONPATH=str(ROOT/'backend'))
os.environ.update(env);sys.path.insert(0,str(ROOT/'backend'))
from app.db import create_db_and_tables,session_scope
from app.main import _bootstrap_default_tenant
create_db_and_tables();_bootstrap_default_tenant()
from app.models import AdminSetting,Contact,ConsentState,Tenant,User,CallbackAppointment,CallSession,CallMode,CallStatus
from app.clock import utc_now
from datetime import timedelta
from app.services.auth import hash_password
from app.services.admin_settings import SETTING_DEFAULTS
from sqlmodel import select
with session_scope() as s:
    assert s.exec(select(CallSession.id).limit(1)).first() is None, 'requires a fresh dedicated test database'
    values={**SETTING_DEFAULTS['compliance'],'allowed_start_hour':0,'allowed_end_hour':0,
        'require_explicit_consent_for_direct_calls':False,'require_explicit_consent':False}
    s.add(AdminSetting(tenant_id=1,section='compliance',data_json=json.dumps(values)))
    tenant=Tenant(name='Synthetic B',code='synthetic-b');s.add(tenant);s.flush()
    s.add(User(tenant_id=tenant.id,username='review-b',password_hash=hash_password('12345678'),full_name='Synthetic B',role='admin'))
    for tid,name in [(1,'SyntheticTenantA'),(tenant.id,'SyntheticTenantB')]:
        s.add(Contact(tenant_id=tid,name=name,phone='13800000001',consent_state=ConsentState.CONSENTED))
    call=CallSession(tenant_id=1,phone='13998765432',mode=CallMode.AI_ONLY,status=CallStatus.COMPLETED,attempts=1)
    s.add(call);s.flush()
    s.add(CallbackAppointment(tenant_id=1,source_call_id=call.id,request_key='browser-appointment',scheduled_at=utc_now()+timedelta(days=1)))
    s.commit()
api_log=(OUT/'inbox-ui-api.log').open('w');agent_log=(OUT/'inbox-ui-agent.log').open('w')
agent=subprocess.Popen([str(ROOT/'.venv-agent/bin/python'),'-m','uvicorn','app.main:app','--host','127.0.0.1','--port','18841','--no-access-log'],cwd=ROOT/'agent',env=dict(env,PYTHONPATH=str(ROOT/'agent')),stdout=agent_log,stderr=subprocess.STDOUT)
apis=[subprocess.Popen([str(ROOT/'.venv-backend/bin/python'),'-m','uvicorn','app.main:app','--host','127.0.0.1','--port',str(port),'--workers','1','--no-access-log'],cwd=ROOT/'backend',env=env,stdout=api_log,stderr=subprocess.STDOUT) for port in (18810,18811,18812,18813,18814,18815)]
api=apis[0]
background_code = "import asyncio; from app.services.task_queue import process_pending_tasks; exec('async def run():\\n while True:\\n  await process_pending_tasks(batch_size=32, threaded=True)\\n  await asyncio.sleep(.05)'); asyncio.run(run())"
workers=[subprocess.Popen([str(ROOT/'.venv-backend/bin/python'),'-m','app.callback_inbox_worker'],cwd=ROOT/'backend',env=env,stdout=api_log,stderr=subprocess.STDOUT),
    subprocess.Popen([str(ROOT/'.venv-backend/bin/python'),'-c',background_code],cwd=ROOT/'backend',env=env,stdout=api_log,stderr=subprocess.STDOUT)]
def stop(*_):
    for process in apis + workers:process.terminate()
    agent.terminate()
    for process in apis + workers:process.wait(timeout=20)
    agent.wait(timeout=20)
    raise SystemExit(0)
signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
print(json.dumps({'api_parent_pid':api.pid,'agent_pid':agent.pid}),flush=True)
try:api.wait()
finally:
    for process in apis + workers:
        if process.poll() is None:process.terminate();process.wait(timeout=20)
    if agent.poll() is None:agent.terminate();agent.wait(timeout=20)
