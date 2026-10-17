"""Check actual Java media-worker RPC ownership and crash/restart epochs.

Python is only an isolated test driver. No ASR/TTS request, PBX or production state.
"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import uuid
import httpx

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'services-java/validation'
JAR = ROOT / 'services-java/target/outbound-services-0.1.0-SNAPSHOT.jar'
JAVA = str(Path(os.environ['JAVA_HOME']) / 'bin/java')


def main():
    OUT.mkdir(exist_ok=True)
    with socket.socket() as handle:
        handle.bind(('127.0.0.1', 0))
        port = handle.getsockname()[1]
    origin = 'http://127.0.0.1:%s' % port
    token = secrets.token_urlsafe(32)
    checks, processes, logs = [], [], []
    with tempfile.TemporaryDirectory(prefix='java-media-process-') as folder:
        client = httpx.Client(base_url=origin, headers={'Authorization': 'Bearer ' + token}, timeout=3, trust_env=False)

        def start():
            log = (OUT / ('java-media-process-%s.log' % len(logs))).open('w')
            logs.append(log)
            env = dict(os.environ, ENV='test', JAVA_SERVICE_ROLE='media', JAVA_BIND_ADDRESS='127.0.0.1', PORT=str(port),
                       MEDIA_WORKER_ID='isolated-worker', MEDIA_WORKER_CAPACITY='2', MEDIA_RPC_TOKEN=token,
                       MEDIA_CONTROL_URL='http://127.0.0.1:1', PIPECAT_OPENAI_API_KEY='synthetic-unused-key',
                       PIPECAT_STT_PROVIDER='openai-realtime', VOICE_QUOTA_ENABLED='false')
            process = subprocess.Popen([JAVA, '-jar', str(JAR)], cwd=folder, env=env, stdout=log, stderr=subprocess.STDOUT)
            processes.append(process)
            for _ in range(200):
                if process.poll() is not None:
                    raise RuntimeError('Java media worker exited; inspect retained local log')
                try:
                    response = client.get('/internal/state')
                    if response.status_code == 200:
                        return process, response.json()
                except httpx.HTTPError:
                    pass
                time.sleep(.1)
            raise RuntimeError('Java media worker readiness timeout')

        def command(epoch, action, owned, **extra):
            payload = dict(epoch=epoch, action=action, call_id=owned['call_id'], session_id=owned['session_id'],
                           attempt=owned['metadata']['attempt'], operation_id=str(uuid.uuid4()), expires_at=time.time()+5)
            payload.update(extra)
            return client.post('/internal/command', json=payload)

        try:
            process, state = start()
            epoch = state['epoch']
            assert state['worker_id'] == 'isolated-worker' and state['capacity'] == 2
            assert state['metrics']['pcm_input_frames_total'] == 0 and 'tts_first_audio_seconds_sum' in state['metrics']
            assert client.get('/internal/state', headers={'Authorization': 'Bearer wrong'}).status_code == 401
            checks.append('actual Java worker readiness and RPC authentication')
            session = dict(call_id='isolated-call', session_id=str(uuid.uuid4()), token=secrets.token_urlsafe(32),
                           speech_webhook_url='', media_webhook_url='', metadata={'tenant_id': 1, 'attempt': 1})
            assert command(epoch, 'create', session, session=session).status_code == 200
            assert command(epoch, 'create', session, session=session).status_code == 200
            assert session['call_id'] in client.get('/internal/state').json()['sessions']
            checks.append('create/retry preserves one media generation')
            assert command('stale-epoch', 'close', session).status_code == 409
            assert command(epoch, 'close', session, session_id='stale-session').status_code == 409
            assert command(epoch, 'close', session, attempt=2).status_code == 409
            assert command(epoch, 'fence', session, expected_speech_event_id='stale-speech').status_code == 409
            assert command(epoch, 'close', session, expires_at=time.time()-1).status_code == 409
            checks.append('epoch/session/attempt/speech/expiry reject stale control')
            assert command(epoch, 'close', session, notify=False).status_code == 200
            assert command(epoch, 'close', session, notify=False).status_code == 200
            assert command(epoch, 'create', session, session=session).status_code == 409
            checks.append('closed generation retry is idempotent and cannot resurrect')
            session['session_id'] = str(uuid.uuid4())
            assert command(epoch, 'create', session, session=session).status_code == 200
            process.kill()
            process.wait(timeout=10)
            _, restarted = start()
            assert restarted['epoch'] != epoch and restarted['sessions'] == {}
            assert command(epoch, 'close', session).status_code == 409
            checks.append('actual process kill/restart changes epoch and fences old commands')
            report = dict(passed=True, checks=checks, services_jar_sha256=hashlib.sha256(JAR.read_bytes()).hexdigest(),
                          boundary='Actual Java media process and HTTP RPC; synthetic session metadata only. No audio, cloud, physical permit release or controller-journal recovery acceptance.')
            (OUT / 'java-media-process.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            print(json.dumps(report, ensure_ascii=False))
        finally:
            client.close()
            for process in processes:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
            for log in logs:
                log.close()


if __name__ == '__main__':
    main()
