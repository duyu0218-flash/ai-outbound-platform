"""Run the core, Agent and voice gateway as separate actual Java processes.

Requires JAVA_HOME and built core/services jars; Python is only the test runner. Uses only
loopback ports and disposable SQLite; never connects a real PBX or cloud model.
"""
import hashlib
import hmac
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import httpx

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'services-java/validation'
OUT.mkdir(exist_ok=True)
jar = ROOT / 'backend-java/target/outbound-control-0.1.0-SNAPSHOT.jar'
services_jar = ROOT / 'services-java/target/outbound-services-0.1.0-SNAPSHOT.jar'
java = str(Path(os.environ['JAVA_HOME'])/'bin/java')
def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

ports = {name: port() for name in ['core', 'agent', 'voice']}
urls = {name: 'http://127.0.0.1:%s' % value for name, value in ports.items()}
processes, logs = [], []
checks = []
with tempfile.TemporaryDirectory(prefix='java-all-services-') as folder:
    common = dict(os.environ, ENV='test', LLM_PROVIDER='rule', OPENAI_API_KEY='',
                  LANGFUSE_ENABLED='false', REDIS_URL='', VOICE_GATEWAY_DRIVER='mock',
                  SERVER_TOMCAT_ACCESSLOG_BUFFERED='false', SERVER_TOMCAT_ACCESSLOG_PATTERN='%m %U %s')
    def start(name, command, env):
        log = (OUT / ('java-stack-' + name + '.log')).open('w')
        logs.append(log)
        p = subprocess.Popen(command, cwd=folder, env=dict(common, **env), stdout=log, stderr=subprocess.STDOUT)
        processes.append(p)
        for _ in range(200):
            if p.poll() is not None:
                raise RuntimeError(name + ' stopped; see retained log')
            try:
                if httpx.get(urls[name] + '/health', timeout=2).status_code == 200:
                    return
            except httpx.HTTPError:
                pass
            time.sleep(.1)
        raise RuntimeError(name + ' did not become healthy')
    def wait(check, seconds=15):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if check():
                return
            time.sleep(.1)
        raise AssertionError('timed out waiting for retained-service effect')
    try:
        start('agent', [java, '-jar', str(services_jar)],
              dict(JAVA_SERVICE_ROLE='agent', PORT=str(ports['agent']), SERVICE_TOKEN='isolated-agent-service-token',
                   SERVER_TOMCAT_ACCESSLOG_ENABLED='true', SERVER_TOMCAT_ACCESSLOG_DIRECTORY=folder+'/agent-access'))
        start('voice', [java, '-jar', str(services_jar)],
              dict(JAVA_SERVICE_ROLE='voice', PORT=str(ports['voice']), SERVICE_TOKEN='isolated-voice-service-token',
                   VOICE_SECURITY_DB_PATH=folder+'/voice.db', SERVER_TOMCAT_ACCESSLOG_ENABLED='true',
                   SERVER_TOMCAT_ACCESSLOG_DIRECTORY=folder+'/voice-access',
                   WEBHOOK_TOKEN='isolated-webhook-token', WEBHOOK_SECRET='isolated-callback-signing-key',
                   VOICE_CALLBACK_BASE_URL=urls['core'], VOICE_CALLBACK_ALLOW_PRIVATE_HTTP='true'))
        start('core', [str(Path(os.environ['JAVA_HOME'])/'bin/java'), '-jar', str(jar)],
              dict(JAVA_DATABASE_URL='jdbc:sqlite:'+folder+'/core.db', PORT=str(ports['core']),
                   JAVA_INITIALIZE_SCHEMA='true', DEMO_USERS_ENABLED='true', JWT_SECRET='isolated-retained-services-test-secret',
                   JAVA_WORKERS_ENABLED='true', JAVA_DISPATCH_ENABLED='true', JAVA_RELEASE_ACCEPTED='true',
                   TELEPHONY_PROVIDER='http', TELEPHONY_PROVIDER_ENDPOINT=urls['voice'],
                   OUTBOUND_SECURITY_APPROVAL_TOKEN='isolated-retained-security-approval-only',
                   TELEPHONY_SERVICE_TOKEN='isolated-voice-service-token', VOICE_COMMAND_SECRET='isolated-voice-command-key',
                   TELEPHONY_WEBHOOK_BASE=urls['core'], TELEPHONY_WEBHOOK_TOKEN='isolated-webhook-token',
                   TELEPHONY_WEBHOOK_SECRET='isolated-callback-signing-key', AI_AGENT_URL=urls['agent'],
                   AI_AGENT_SERVICE_TOKEN='isolated-agent-service-token', CALLBACK_INBOX_ENABLED='true',
                   STABILITY_ADMISSION_ENABLED='false', VOICE_GATEWAY_NODES_JSON='[]'))
        with httpx.Client(base_url=urls['core'], timeout=20) as client:
            def api(method, path, body=None):
                r = client.request(method, path, json=body)
                assert r.status_code == 200, (path, r.status_code, r.text)
                return r.json()
            wait(lambda: client.get('/readyz').status_code == 200)
            token = api('POST', '/api/v1/auth/login', {'username':'admin','password':'12345678'})['access_token']
            client.headers['Authorization'] = 'Bearer '+token
            client.headers['X-Security-Approval'] = 'isolated-retained-security-approval-only'
            wait(lambda: client.get('/readyz').status_code == 200)
            checks.append('readiness verifies actual Java Agent and gateway')
            data = api('GET','/api/v1/admin/settings/compliance')['data']
            data.update(allowed_start_hour=0, allowed_end_hour=0)
            api('PUT','/api/v1/admin/settings/compliance',{'data':data})
            contact = api('POST','/api/v1/contacts',{'phone':'13900000077','name':'Retained synthetic contact','consent_state':'consented'})
            call = api('POST','/api/v1/calls',{'contact_id':contact['id'],'mode':'ai_only','max_attempts':1})
            call_id = call['id']
            wait(lambda: api('GET','/api/v1/calls/'+call_id)['status']=='in_ai')
            checks.append('Java HTTP dial and signed Java gateway callbacks reach in_ai')
            def callback(path, payload):
                body=json.dumps(payload,separators=(',',':')).encode()
                stamp=str(int(time.time()))
                signature=hmac.new(b'isolated-callback-signing-key',stamp.encode()+b'.'+body,hashlib.sha256).hexdigest()
                r=client.post('/api/v1/webhooks/telephony/'+path,content=body,headers={'Content-Type':'application/json','X-Webhook-Token':'isolated-webhook-token','X-Webhook-Timestamp':stamp,'X-Webhook-Signature':signature})
                assert r.status_code==200,r.text
            callback('speech',{'call_id':call_id,'event_id':'retained-speech','attempt':1,'transcript':'Please explain the service details','is_final':True,'barge_in':True})
            def access(role):
                return ''.join(p.read_text() for p in Path(folder, role+'-access').glob('*'))
            wait(lambda: '/agent/turn' in access('agent') and '/v1/call/speak' in access('voice'))
            checks.append('Java core invokes Java Agent turn and Java gateway speak over HTTP')
            callback('status',{'call_id':call_id,'kind':'status','payload':{'status':'completed','attempt':1,'event_id':'retained-completed','duration_sec':8}})
            wait(lambda: api('GET','/api/v1/calls/'+call_id)['status']=='completed')
            wait(lambda: client.get('/api/v1/calls/'+call_id+'/analysis').status_code==200 and client.get('/api/v1/calls/'+call_id+'/analysis').json() is not None)
            checks.append('signed terminal callback generates durable analysis')
    finally:
        for p in reversed(processes):
            p.terminate()
            try: p.wait(timeout=10)
            except subprocess.TimeoutExpired: p.kill();p.wait()
        for log in logs: log.close()
        for role in ['agent', 'voice']:
            text=''.join(p.read_text() for p in Path(folder, role+'-access').glob('*'))
            (OUT/('java-stack-'+role+'-access.log')).write_text(text)
report={'checks':checks,'passed':len(checks)==4,'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(), 'services_jar_sha256':hashlib.sha256(services_jar.read_bytes()).hexdigest(),
        'boundary':'Actual Java core/Agent/gateway processes, rule model and mock phone driver only; no real SIP/RTP or cloud model. Recording and media have independent local fixtures.'}
(OUT/'java-stack.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
