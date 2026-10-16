"""Run the baseline Python and Java APIs against independent synthetic DB copies.

No production URL is accepted. Java and its SQLite database are created here;
Python TestClient has no lifespan, scheduler, telephony or model workers.
"""
import json
import hashlib
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from pathlib import Path
from uuid import UUID

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'backend-java/validation'
OUT.mkdir(exist_ok=True)
scratch = tempfile.TemporaryDirectory(prefix='java-python-parity-')
folder = Path(scratch.name)
python_db, java_db = folder / 'python.db', folder / 'java.db'
os.environ.update(DATABASE_URL='sqlite:///' + str(python_db), ENV='test', API_KEY='parity-machine-key',
    JWT_SECRET='isolated-parity-secret-never-for-production', SCHEDULER_ENABLED='false',
    TELEPHONY_PROVIDER='mock', WEBRTC_ENABLED='false', DEMO_USERS_ENABLED='false',
    RATE_LIMIT_RPS='100000', RATE_LIMIT_BURST='100000', CALLBACK_INBOX_ENABLED='false',
    REDIS_URL='', VOICE_GATEWAY_NODES_JSON='[]', STABILITY_ADMISSION_ENABLED='false')
sys.path.insert(0, str(ROOT / 'backend'))
import httpx
from fastapi.testclient import TestClient
from sqlmodel import SQLModel, Session
from app.main import app
from app.db import engine
from app.models import Tenant, User, Contact, ConsentState, CallSession, CallMode, CallStatus
from app.services.auth import hash_password

fixed = datetime(2026, 1, 2, 3, 4, 5)
call_id = '11111111-2222-4333-8444-555555555555'
SQLModel.metadata.create_all(engine)
with Session(engine) as session:
    session.add_all([Tenant(id=1, name='Parity A', code='parity-a', created_at=fixed, updated_at=fixed),
                     Tenant(id=2, name='Parity B', code='parity-b', created_at=fixed, updated_at=fixed)])
    session.commit()
    password = hash_password('parity-test-123')
    session.add_all([User(id=1, tenant_id=1, username='parity-admin', password_hash=password, full_name='Admin', role='admin', created_at=fixed, updated_at=fixed),
                     User(id=2, tenant_id=1, username='parity-agent', password_hash=password, full_name='Agent', role='agent', created_at=fixed, updated_at=fixed),
                     User(id=3, tenant_id=2, username='parity-other', password_hash=password, full_name='Other', role='admin', created_at=fixed, updated_at=fixed),
                     Contact(id=1, tenant_id=1, phone='13900000001', name='Historical contact', consent_state=ConsentState.CONSENTED, created_at=fixed, updated_at=fixed),
                     Contact(id=2, tenant_id=2, phone='13900000002', name='Other tenant', created_at=fixed, updated_at=fixed)])
    session.add(CallSession(id=UUID(call_id), tenant_id=1, contact_id=1, phone='13900000001', mode=CallMode.HUMAN_ONLY,
        status=CallStatus.COMPLETED, attempts=1, started_at=fixed, finished_at=fixed, created_at=fixed, updated_at=fixed))
    session.commit()
engine.dispose()
shutil.copyfile(python_db, java_db)

java = Path(os.environ['JAVA_HOME']) / 'bin/java'
jar = ROOT / 'backend-java/target/outbound-control-0.1.0-SNAPSHOT.jar'
with socket.socket() as s:
    s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
env = dict(os.environ, JAVA_DATABASE_URL='jdbc:sqlite:' + str(java_db), PORT=str(port),
    JAVA_INITIALIZE_SCHEMA='false', JAVA_DISPATCH_ENABLED='true', JAVA_WORKERS_ENABLED='false')
log = (OUT / 'behavior-java.log').open('w')
process = subprocess.Popen([str(java), '-jar', str(jar)], env=env, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
records = []
coverage = set()
uuid_maps = [{}, {}]
volatile = {'created_at', 'updated_at', 'last_login_at', 'last_seen_at', 'consented_at', 'sent_at', 'timestamp', 'expires_at', 'access_token', 'since', 'end', 'start', 'finished_at','reviewed_at','published_at'}

def canonical(value, side, key=''):
    if key in volatile:
        return value if value is None or isinstance(value,str) and value.startswith('2026-01-02T03:04:05') else '<generated>'
    if key.endswith('_json') and isinstance(value,str):
        try: return canonical(json.loads(value),side)
        except ValueError: pass
    if isinstance(value, dict):
        return {k: canonical(v, side, k) for k, v in sorted(value.items())}
    if isinstance(value, list):
        return [canonical(v, side) for v in value]
    if isinstance(value, str) and re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', value) and value != call_id:
        return uuid_maps[side].setdefault(value, '<generated-uuid-%d>' % len(uuid_maps[side]))
    return value

def body(response):
    try: return response.json()
    except ValueError: return response.text

def compare(name, method, path, payload=None, auth=True, template=None, params=None, fields=None, files=None):
    result = []
    for side, client in enumerate(clients):
        kwargs = dict(headers=headers[side] if auth else {}, params=params)
        if payload is not None: kwargs['json'] = payload
        if files is not None: kwargs['files'] = files
        response = client.request(method, path[side] if isinstance(path,list) else path, **kwargs)
        result.append((response.status_code, body(response)))
    matched = result[0][0] == result[1][0]
    if matched and result[0][0] < 400:
        values = [v[1] for v in result]
        if fields is not None: values = [{k: v.get(k) for k in fields} for v in values]
        matched = canonical(values[0], 0) == canonical(values[1], 1)
    entry = dict(case=name, method=method, route=template or path, matched=matched,
        statuses=[v[0] for v in result], comparison='status/error-boundary' if result[0][0]>=400 else 'body' if fields is None else 'selected-fields')
    if not matched: entry.update(python=result[0][1], java=result[1][1])
    records.append(entry); coverage.add((method, template or path))
    return result

try:
    for _ in range(100):
        if process.poll() is not None: raise RuntimeError('Java startup failed; see validation/behavior-java.log')
        try:
            if httpx.get('http://127.0.0.1:%s/health' % port).status_code==200: break
        except httpx.HTTPError: pass
        time.sleep(.1)
    clients = [TestClient(app, raise_server_exceptions=False), httpx.Client(base_url='http://127.0.0.1:%s' % port, timeout=30)]
    headers = []
    for client in clients:
        response = client.post('/api/v1/auth/login', json={'username':'parity-admin','password':'parity-test-123'})
        if response.status_code != 200: raise RuntimeError('fixture login failed: '+response.text)
        headers.append({'Authorization':'Bearer '+response.json()['access_token']})
    contract = json.loads((ROOT / 'backend-java/src/main/resources/contracts/openapi-baseline.json').read_text())
    def concrete(path):
        return re.sub(r'\{([^}]+)\}', lambda m: call_id if m[1]=='call_id' else 'compliance' if m[1]=='section' else '1' if m[1] in {'contact_id','user_id'} else '999999', path)
    for path, methods in contract['paths'].items():
        for method in methods:
            if method not in {'get','post','put','patch','delete'}: continue
            if path in {'/health','/healthz','/readyz','/api/v1/runtime'}: continue
            compare('unauthenticated', method.upper(), concrete(path), {} if method not in {'get','delete'} else None, auth=False, template=path)
    for path, methods in contract['paths'].items():
        if 'get' not in methods or path in {'/health','/healthz','/readyz','/api/v1/admin/system-overview','/api/v1/agent/events/stream','/api/v1/admin/audit-logs'}: continue
        compare('historical-read', 'GET', concrete(path), template=path)
    # Invalid request schemas and query bounds are exercised with authentication,
    # so a 401 cannot conceal validation mismatches. Required fields are omitted;
    # no outbound request can be triggered by these malformed requests.
    for path, methods in contract['paths'].items():
        for method, operation in methods.items():
            if method not in {'get','post','put','patch'}: continue
            schema=operation.get('requestBody',{}).get('content',{}).get('application/json',{}).get('schema',{})
            if '$ref' in schema: schema=contract['components']['schemas'][schema['$ref'].split('/')[-1]]
            if schema.get('required') and '/webhooks/' not in path:
                compare('missing-required-body',method.upper(),concrete(path),{},template=path)
            if method=='get' and '/events/stream' not in path:
                for parameter in operation.get('parameters',[]):
                    detail=parameter.get('schema',{})
                    if parameter.get('in')=='query' and 'minimum' in detail:
                        compare('query-minimum:'+parameter['name'],'GET',concrete(path),params={parameter['name']:int(detail['minimum'])-1},template=path)
    compare('liveness','GET','/healthz',auth=False)
    compare('health','GET','/health',auth=False)
    compare('readiness-status','GET','/readyz',auth=False)
    compare('foreign-contact', 'GET', '/api/v1/contacts/2', template='/api/v1/contacts/{contact_id}')
    compare('invalid-phone', 'POST', '/api/v1/contacts', {'phone':'12'})
    compare('create-contact', 'POST', '/api/v1/contacts', {'phone':'+86 139 0000 0010','name':'Parity new','consent_state':'consented'})
    compare('duplicate-contact', 'POST', '/api/v1/contacts', {'phone':'8613900000010'})
    compare('edit-contact', 'PATCH', '/api/v1/contacts/3', {'name':'Updated','dnc':True,'dnc_reason':'Requested'}, template='/api/v1/contacts/{contact_id}')
    compare('read-contact', 'GET', '/api/v1/contacts/3', template='/api/v1/contacts/{contact_id}')
    compare('batch-dnc', 'PATCH', '/api/v1/contacts/batch-dnc', {'contact_ids':[1,3,99999], 'dnc':True,'dnc_reason':'Parity'})
    compare('import-contact', 'POST', '/api/v1/contacts/import', files={'file':('contacts.csv',b'phone,name\n13900000020,CSV\ninvalid,Bad\n','text/csv')})
    compare('create-template', 'POST', '/api/v1/script-templates', {'name':'Parity script','content':'Hello'})
    compare('update-template', 'PUT', '/api/v1/script-templates/1', {'name':'Parity revised','content':'Revised'}, template='/api/v1/script-templates/{template_id}')
    compare('create-campaign', 'POST', '/api/v1/campaigns', {'name':'Parity campaign','mode':'human_only','contact_ids':[1], 'retry_limit':0,'script_template_id':1})
    compare('campaign-read', 'GET', '/api/v1/campaigns/1', template='/api/v1/campaigns/{campaign_id}')
    compare('campaign-prepare', 'POST', '/api/v1/campaigns/1/start', template='/api/v1/campaigns/{campaign_id}/start', params={'auto_dial':'false'})
    compare('campaign-stop', 'POST', '/api/v1/campaigns/1/stop', template='/api/v1/campaigns/{campaign_id}/stop')
    compare('referenced-contact-delete', 'DELETE', '/api/v1/contacts/1', template='/api/v1/contacts/{contact_id}')
    compare('create-knowledge', 'POST', '/api/v1/knowledge', {'title':'Parity knowledge','content':'Known content','tags':'test'})
    compare('knowledge-search', 'POST', '/api/v1/product/knowledge/search', {'query':'Known content'})
    for text in ['不用转人工','我需要你们别再打','可以听一下','我不需要，不对，我现在需要','谢谢，再见','不是本人','请在滴声后留言']:
        compare('policy-simulate:'+text, 'POST', '/api/v1/product/simulate', {'policy':{},'utterances':[text]})
    for state, event in [('answered','late-answer'),('completed','duplicate-end')]:
        payload={'call_id':call_id,'kind':'status','payload':{'status':state,'attempt':1,'event_id':event}}
        compare('terminal-callback:'+state,'POST','/api/v1/webhooks/telephony/status',payload,auth=False)
    compare('terminal-read','GET','/api/v1/calls/'+call_id,template='/api/v1/calls/{call_id}')
    compare('exhausted-retry','POST','/api/v1/calls/'+call_id+'/retry',template='/api/v1/calls/{call_id}/retry')
    compare('terminal-hangup','POST','/api/v1/calls/'+call_id+'/hangup',template='/api/v1/calls/{call_id}/hangup')
    compare('review-analysis','PUT','/api/v1/calls/'+call_id+'/analysis',{'result_code':'interested','qa_score':88,'summary':'Reviewed','qa_flags':['manual']},template='/api/v1/calls/{call_id}/analysis')
    compare('review-persisted','GET','/api/v1/calls/'+call_id+'/analysis',template='/api/v1/calls/{call_id}/analysis')
    compare('create-user','POST','/api/v1/admin/users',{'username':'parity-new-agent','password':'synthetic-123','full_name':'Created agent'})
    compare('update-user','PUT','/api/v1/admin/users/4',{'full_name':'Updated agent'},template='/api/v1/admin/users/{user_id}')
    compare('reset-password','POST','/api/v1/admin/users/4/reset-password',{'password':'replacement-123'},template='/api/v1/admin/users/{user_id}/reset-password')
    compare('unlock-user','POST','/api/v1/admin/users/4/unlock',template='/api/v1/admin/users/{user_id}/unlock')
    compare('disable-user','DELETE','/api/v1/admin/users/4',template='/api/v1/admin/users/{user_id}')
    compare('create-line','POST','/api/v1/admin/lines',{'name':'Mock parity','provider':'mock'})
    compare('update-line','PUT','/api/v1/admin/lines/1',{'max_concurrency':2},template='/api/v1/admin/lines/{line_id}')
    compare('delete-line','DELETE','/api/v1/admin/lines/1',template='/api/v1/admin/lines/{line_id}')
    compare('update-knowledge','PUT','/api/v1/knowledge/1',{'content':'Revised known content'},template='/api/v1/knowledge/{item_id}')
    compare('deactivate-knowledge','DELETE','/api/v1/knowledge/1',template='/api/v1/knowledge/{item_id}')
    compare('save-policy','POST','/api/v1/product/policy',{'policy':{'faqs':{'who':'Support'}},'expected_version_id':None})
    compare('stale-policy','POST','/api/v1/product/policy',{'policy':{},'expected_version_id':None})
    compare('policy-history','GET','/api/v1/product/policy')
    compare('invalid-calendar','POST','/api/v1/product/policy',{'policy':{'start_hour':20,'end_hour':9},'expected_version_id':1})
    incoming={'event_id':'parity-incoming','phone':'13900000099','kind':'inbound_call','text':'Call back'}
    pending=compare('incoming','POST','/api/v1/product/incoming',incoming)
    compare('incoming-replay','POST','/api/v1/product/incoming',incoming)
    if all(r[0]==200 for r in pending):
        compare('work-complete','PATCH',['/api/v1/product/work-items/'+r[1]['item_id'] for r in pending],{'state':'completed'},template='/api/v1/product/work-items/{item_id}')
    compare('flow-create','POST','/api/v1/script-templates/1/flows',{'name':'Parity flow'},template='/api/v1/script-templates/{template_id}/flows')
    graph={'nodes':[{'id':'start','type':'start','label':'Start','position':{'x':10,'y':10}},{'id':'end','type':'hangup','label':'End','position':{'x':50,'y':10}}], 'edges':[{'id':'edge','source':'start','target':'end','condition':'always'}]}
    compare('flow-edit','PUT','/api/v1/script-templates/1/flows/1',{'graph':graph},template='/api/v1/script-templates/{template_id}/flows/{version_id}')
    compare('flow-publish','POST','/api/v1/script-templates/1/flows/1/publish',template='/api/v1/script-templates/{template_id}/flows/{version_id}/publish')
    compare('flow-simulate','POST','/api/v1/script-templates/1/flows/1/simulate',{'transcript':'Hello'},template='/api/v1/script-templates/{template_id}/flows/{version_id}/simulate')
    compare('published-immutable','PUT','/api/v1/script-templates/1/flows/1',{'graph':graph},template='/api/v1/script-templates/{template_id}/flows/{version_id}')
    for key in ['ai','compliance','sms','capacity','integration']:
        settings=compare('setting-read:'+key,'GET','/api/v1/admin/settings/'+key,template='/api/v1/admin/settings/{section}')
        if all(r[0]==200 for r in settings): compare('setting-save:'+key,'PUT','/api/v1/admin/settings/'+key,{'data':settings[0][1]['data']},template='/api/v1/admin/settings/{section}')
    compare('delete-unreferenced-contact','DELETE','/api/v1/contacts/3',template='/api/v1/contacts/{contact_id}')
    compare('delete-campaign','DELETE','/api/v1/campaigns/1',template='/api/v1/campaigns/{campaign_id}')
    compare('deactivate-template','DELETE','/api/v1/script-templates/1',template='/api/v1/script-templates/{template_id}')
    for transcripts in [['你好','不清楚','不清楚','不清楚'],['请在滴声后留言','请在滴声后留言']]:
        compare('multi-turn-policy','POST','/api/v1/product/simulate',{'policy':{},'utterances':transcripts})
    compare('logout','POST','/api/v1/auth/logout')
    compare('revoked-token','GET','/api/v1/auth/me')
finally:
    process.terminate()
    try: process.wait(timeout=10)
    except subprocess.TimeoutExpired: process.kill();process.wait()
    log.close()
    if 'clients' in globals():
        for client in clients: client.close()
    engine.dispose();scratch.cleanup()

report = dict(jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(), authenticated_cases=sum(r['case']!='unauthenticated' for r in records), successful_body_cases=sum(r['matched'] and r['comparison']=='body' for r in records), baseline='96d9bef44173612d85399792bf54c6399059b1e7', cases=len(records),
    matched=sum(r['matched'] for r in records), differences=sum(not r['matched'] for r in records),
    route_boundaries=len(coverage), normalization='Generated timestamps/tokens and UUID identities normalized; error details are framework-specific, status boundaries compared. Selected fields explicitly recorded.',
    exclusions=['readiness stays blocked until release acceptance','system-overview live dependency/latency probes','audit event text implementation details','successful long-lived agent stream tested separately'], records=records)
(OUT / 'behavior-comparison.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='records'},ensure_ascii=False))
sys.exit(1 if report['differences'] else 0)
