"""Disposable Linux Java role containers; real S3 and synthetic WAV only.

Own container/network names carry a random task prefix. No existing deployment is used.
"""
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import httpx

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT/'services-java/validation'
IMAGE = os.environ.get('JAVA_SERVICES_TEST_IMAGE', 'ai-outbound-services-java:validation-20261017')


def main():
    OUT.mkdir(exist_ok=True)
    prefix = 'java-services-check-' + secrets.token_hex(4)
    names, checks = [], []
    network_created = False
    token, cleanup, command_key = (secrets.token_urlsafe(32) for _ in range(3))

    def docker(*args, check=True):
        result = subprocess.run(['docker', *args], capture_output=True, text=True, timeout=90)
        if check and result.returncode:
            raise RuntimeError('Docker test operation failed: ' + result.stderr[-2000:])
        return result.stdout.strip()

    def run(role, env, image=IMAGE, command=None):
        name = prefix+'-'+role
        names.append(name)
        args = ['run', '-d', '--name', name, '--label', 'codex.task=java-services-container-check', '--network', prefix]
        if role == 's3':
            args += ['--tmpfs', '/data:rw,size=256m']
        else:
            args += ['--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges:true',
                     '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m,uid=10001,gid=10001',
                     '--tmpfs', '/recordings:rw,noexec,nosuid,size=64m,uid=10001,gid=10001',
                     '--tmpfs', '/var/spool/recordings:rw,noexec,nosuid,size=64m,uid=10001,gid=10001',
                     '-p', '127.0.0.1::8003']
        for key, value in env.items():
            args += ['-e', key+'='+value]
        args += [image]+(command or [])
        docker(*args)
        return name

    def endpoint(name):
        mapping = json.loads(docker('inspect', '--format', '{{json .NetworkSettings.Ports}}', name))
        return 'http://127.0.0.1:'+mapping['8003/tcp'][0]['HostPort']

    def ready(name, auth=True):
        url = endpoint(name)
        for _ in range(150):
            try:
                if httpx.get(url+'/readyz', headers={'Authorization': 'Bearer '+token} if auth else {}, timeout=2, trust_env=False).status_code == 200:
                    docker('exec', name, 'java', '-jar', '/app/services.jar', '--healthcheck')
                    return url
            except httpx.HTTPError:
                pass
            if docker('inspect', '--format', '{{.State.Running}}', name) != 'true':
                raise RuntimeError('Test Java container stopped; retained diagnostic log available')
            time.sleep(.1)
        raise RuntimeError('Container readiness timeout')

    try:
        image_id = docker('image', 'inspect', '--format', '{{.Id}}', IMAGE)
        assert docker('image', 'inspect', '--format', '{{.Config.User}}', IMAGE) == '10001:10001'
        docker('network', 'create', '--label', 'codex.task=java-services-container-check', prefix)
        network_created = True
        s3 = run('s3', dict(AWS_ACCESS_KEY_ID='java-isolated-access', AWS_SECRET_ACCESS_KEY='java-isolated-secret', S3_BUCKET='java-isolated-recordings'),
                 image='chrislusf/seaweedfs:4.44', command=['mini', '-dir=/data', '-ip='+prefix+'-s3', '-ip.bind=0.0.0.0', '-master.volumeSizeLimitMB=8'])
        for _ in range(60):
            result = subprocess.run(['docker', 'exec', s3, 'wget', '-q', '--spider', 'http://127.0.0.1:9333/cluster/status'], capture_output=True)
            if result.returncode == 0:
                break
            time.sleep(.2)
        else:
            raise RuntimeError('S3 fixture readiness timeout')
        common = dict(ENV='test', PORT='8003', SERVICE_TOKEN=token, METRICS_TOKEN='isolated-container-metrics')
        voice = run('voice', dict(common, JAVA_SERVICE_ROLE='voice', VOICE_GATEWAY_DRIVER='mock',
                                  VOICE_SECURITY_DB_PATH='/tmp/voice.sqlite', FREESWITCH_RECORDING_DIR='/recordings',
                                  VOICE_NODE_ID='container-node', VOICE_COMMAND_SECRET=command_key, VOICE_RECORDING_CLEANUP_TOKEN=cleanup))
        voice_url = ready(voice)
        checks.append('nonroot read-only Java voice container and SQLite with noexec temporary directory')
        agent = run('agent', dict(common, JAVA_SERVICE_ROLE='agent', LLM_PROVIDER='rule', LLM_QUOTA_DB_PATH='/tmp/quota.sqlite'))
        agent_url = ready(agent)
        with httpx.Client(base_url=agent_url, trust_env=False) as client:
            assert client.post('/agent/start', json={}).status_code == 401
            response = client.post('/agent/start', headers={'Authorization': 'Bearer '+token}, json={'call_id':'synthetic-container', 'phone':'+8613900139000', 'mode':'ai_only'})
            assert response.status_code == 200 and response.json()['tts_text']
        checks.append('nonroot Java Agent HTTP credentials and greeting')
        recording = run('recording', dict(common, JAVA_SERVICE_ROLE='recording', S3_ENDPOINT_URL='http://'+s3+':8333',
                                         S3_BUCKET='java-isolated-recordings', S3_ACCESS_KEY_ID='java-isolated-access', S3_SECRET_ACCESS_KEY='java-isolated-secret',
                                         RECORDING_SPOOL_DIR='/var/spool/recordings', RECORDING_SOURCE_ALLOWED_HOSTS=voice,
                                         RECORDING_MAX_BYTES='16384', RECORDING_SPOOL_RESERVE_BYTES='0',
                                         RECORDING_SOURCE_CLEANUP_ORIGIN='http://'+voice+':8003', RECORDING_SOURCE_CLEANUP_TOKEN=cleanup))
        recording_url = ready(recording)
        audio = b'RIFF-isolated-container-wave-data'
        filename = 'synthetic-fixture.wav'
        for suffix, data in [('', audio), ('.complete', json.dumps(dict(completed_at=0, size_bytes=len(audio))).encode())]:
            subprocess.run(['docker', 'exec', '-i', voice, 'tee', '/recordings/'+filename+suffix],
                           input=data, capture_output=True, check=True, timeout=10)
        expires = int(time.time())+60
        signature = hmac.new(command_key.encode(), ('recording-source:container-node:%s:%s' % (filename, expires)).encode(), hashlib.sha256).hexdigest()
        source = 'http://'+voice+':8003/v1/recordings/'+filename+'?expires=%s&signature=%s' % (expires, signature)
        payload = dict(tenant_id=1, recording_asset_id=1, call_id='container-recording', provider_url=source)
        with httpx.Client(base_url=recording_url, headers={'Authorization':'Bearer '+token}, timeout=30, trust_env=False) as client:
            response = client.post('/v1/recordings/ingest', json=payload)
            assert response.status_code == 200, response.text
            saved = response.json()
            assert saved['checksum_sha256'] == hashlib.sha256(audio).hexdigest()
            assert httpx.get(voice_url+'/v1/recordings/'+filename, params={'expires':expires,'signature':signature}, trust_env=False).status_code == 404
            assert client.post('/v1/recordings/ingest', json=payload).json() == saved
            assert client.post('/v1/recordings/delete', json=dict(payload, storage_uri=saved['storage_uri'])).status_code == 200
            assert client.post('/v1/recordings/ingest', json=payload).status_code == 503
        checks.append('Java signed source -> Java recording -> actual S3 readback -> owner cleanup, retry and delete tombstone')
        media = run('media', dict(common, JAVA_SERVICE_ROLE='media', MEDIA_WORKER_ID='container-worker', MEDIA_RPC_TOKEN=token,
                                  PIPECAT_OPENAI_API_KEY='synthetic-unused-key', MEDIA_CONTROL_URL='http://127.0.0.1:1'))
        media_url = ready(media)
        assert httpx.get(media_url+'/internal/state', headers={'Authorization':'Bearer '+token}, trust_env=False).json()['worker_id'] == 'container-worker'
        checks.append('nonroot Java media role and authenticated worker state')
        for url in [voice_url, agent_url, recording_url, media_url]:
            document = httpx.get(url+'/openapi.json', trust_env=False)
            assert document.status_code == 200 and document.json()['paths']
            docs = httpx.get(url+'/docs', trust_env=False)
            assert docs.status_code == 200 and "connect-src 'self'" in docs.headers['content-security-policy']
        for url in [voice_url, recording_url]:
            assert httpx.get(url+'/metrics', trust_env=False).status_code == 401
            metrics = httpx.get(url+'/metrics', headers={'Authorization':'Bearer isolated-container-metrics'}, trust_env=False)
            assert metrics.status_code == 200 and 'ai_outbound_java_uptime_seconds ' in metrics.text
            assert token not in metrics.text and cleanup not in metrics.text
        checks.append('all four role API references and authenticated nonempty JVM metrics in actual containers')
        report = dict(passed=True, image_id=image_id, checks=checks,
                      boundary='Disposable Linux Java role containers, noexec/read-only filesystem, synthetic WAV and real SeaweedFS; no production data, PBX, cloud model, call capacity or deployment cutover.')
        (OUT/'java-containers.json').write_text(json.dumps(report, indent=2)+'\n')
        print(json.dumps(report))
    finally:
        for name in names:
            log = subprocess.run(['docker','logs',name],capture_output=True,text=True)
            (OUT/(name+'.log')).write_text(log.stdout+log.stderr)
        if names:
            docker('rm','-f',*names,check=False)
        if network_created:
            docker('network','rm',prefix,check=False)


if __name__ == '__main__':
    main()
