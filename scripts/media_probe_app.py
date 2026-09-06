"""ISOLATED LOCAL TEST APP. No cloud speech, real numbers, or business DB.

Uses the actual project routes, ESL driver, Pipecat transport and serializer.
Only the speech providers are synthetic. Never launch this as the business API.
"""
import math
import hashlib
import os
import struct
import time
import httpx
from pathlib import Path
from uuid import uuid4

from fastapi import Depends, Header, HTTPException, Query, WebSocket, WebSocketDisconnect
from pipecat.frames.frames import InputAudioRawFrame, TTSSpeakFrame, TTSStartedFrame, TTSStoppedFrame, TTSAudioRawFrame
from pipecat.processors.frame_processor import FrameProcessor, FrameDirection

from app import main
from app.config import Settings
from app.esl import EslError
from app.freeswitch import CallBinding, FreeswitchEslDriver
from app.pipecat_pipeline import PipecatPipelineManager

if os.environ.get('ENV') != 'dev':
    raise RuntimeError('synthetic media probe is permitted only with explicit ENV=dev')
secret = Path('/run/secrets/voismart_esl_password').read_text().strip()
events = []
inputs = {}
sockets = {}
settings = Settings(
    _env_file=None, env='dev', service_token=hashlib.sha256((secret + ':probe-service').encode()).hexdigest(),
    voice_gateway_driver='freeswitch_esl', voice_ai_pipeline='pipecat',
    freeswitch_esl_host=os.environ.get('FREESWITCH_ESL_HOST', 'freeswitch-media'),
    freeswitch_esl_password=secret, freeswitch_gateway='unconfigured-test-only',
    freeswitch_tts_engine='unconfigured-test-only', freeswitch_tts_voice='test-only',
    freeswitch_dialplan_context='media-test',
    freeswitch_agent_extension_template='handoff-probe',
    pipecat_version='1.8.1+outbound.1', pipecat_media_protocol='voismart',
    pipecat_media_ws_base='ws://media-probe:8002/v1/pipecat/media',
    pipecat_openai_api_key='synthetic-test-not-a-cloud-key', pipecat_max_active_sessions=5,
    voice_command_secret=hashlib.sha256((secret + ':probe-command').encode()).hexdigest(),
    voice_security_admin_token=hashlib.sha256((secret + ':probe-admin').encode()).hexdigest(),
    webhook_secret=hashlib.sha256((secret + ':probe-signing').encode()).hexdigest(),
    voice_security_db_path='/tmp/non-billable-probe.sqlite3',
    voice_callback_base_url='http://media-probe:8002', voice_callback_allow_private_http=True,
)
settings.webhook_token = hashlib.sha256((secret + ':probe-callback').encode()).hexdigest()


class CaptureInput(FrameProcessor):
    def __init__(self, session):
        super().__init__()
        self.session = session
        self._register_event_handler('on_connected')
        self._register_event_handler('on_connection_error')
        inputs[session.call_id] = {'bytes': 0, 'nonzero_samples': 0, 'tone_600_energy': 0, 'tone_1000_energy': 0}

    async def process_frame(self, frame, direction):
        await super().process_frame(frame, direction)
        if isinstance(frame, InputAudioRawFrame):
            stats = inputs[self.session.call_id]
            stats['bytes'] += len(frame.audio)
            stats['nonzero_samples'] += sum(abs(x[0]) > 100 for x in struct.iter_unpack('<h', frame.audio))
            samples = [x[0] for x in struct.iter_unpack('<h', frame.audio)]
            for frequency in (600, 1000):
                coefficient = 2 * math.cos(2 * math.pi * frequency / 8000)
                previous = older = 0.0
                for sample in samples:
                    current = sample + coefficient * previous - older
                    older, previous = previous, current
                stats[f'tone_{frequency}_energy'] += previous**2 + older**2 - coefficient*previous*older
            return  # This is an input probe, NOT an ASR result.
        await self.push_frame(frame, direction)


class SyntheticTone(FrameProcessor):
    async def process_frame(self, frame, direction):
        await super().process_frame(frame, direction)
        if isinstance(frame, TTSSpeakFrame):
            # Fixed allowlist only: no arbitrary speech or network providers.
            duration = 6.0 if frame.text == 'long-tone' else 1.2
            pcm = b''.join(struct.pack('<h', int(10000 * math.sin(2 * math.pi * 1000 * i / 8000)))
                           for i in range(int(duration * 8000)))
            await self.push_frame(TTSStartedFrame())
            await self.push_frame(TTSAudioRawFrame(audio=pcm, sample_rate=8000, num_channels=1))
            await self.push_frame(TTSStoppedFrame())
            events.append({'kind': 'generation_done', 'at': time.time(), 'duration': duration})
            return
        await self.push_frame(frame, direction)


class ProbeManager(PipecatPipelineManager):
    def media_ws_url(self, session):
        if session.metadata.get('probe_fault') == 'no_connect':
            return f'ws://media-probe:8002/probe/stall/{session.token}'
        return super().media_ws_url(session)

    def make_services(self, session):
        return CaptureInput(session), SyntheticTone()

    async def _run_session(self, websocket, session):
        sockets[session.call_id] = websocket
        try:
            await super()._run_session(websocket, session)
        finally:
            sockets.pop(session.call_id, None)


class ProbeDriver(FreeswitchEslDriver):
    def _binding_from_event(self, event):
        # Never adopt calls created by a different isolated test/controller.
        if event.get('Unique-ID') not in self.calls_by_uuid:
            return None
        return super()._binding_from_event(event)

    async def _tts_media_uri(self, request):
        # Five seconds of silence stand in for the compliance announcement;
        # no cloud request or customer speech is involved in cancellation tests.
        if self._binding(request.call_id).metadata.get('probe_fault') == 'notice':
            return 'silence_stream://5000'
        return await super()._tts_media_uri(request)


manager = ProbeManager(settings)
driver = ProbeDriver(settings, pipecat_manager=manager)


async def probe_callback(url, payload):
    if url != 'http://media-probe:8002/probe/webhook':
        raise RuntimeError('probe callback destination is fixed')
    async with httpx.AsyncClient(timeout=5, trust_env=False, follow_redirects=False) as client:
        response = await client.post(url, json=payload, headers={'x-webhook-token': settings.webhook_token})
        response.raise_for_status()


driver._post_json = probe_callback
manager._post_json = probe_callback
main.settings = settings
main.driver = driver
app = main.app
app.title = 'SYNTHETIC MEDIA TEST ONLY - NOT REAL OUTBOUND'


async def require_probe_token(authorization: str | None = Header(default=None)):
    import secrets
    if not secrets.compare_digest(authorization or '', f'Bearer {settings.service_token}'):
        raise HTTPException(401)


# This separate harness exposes only null/tone channels; it has no carrier
# dial route. Production command permits are tested through the real app.
app.dependency_overrides[main.require_service_token] = require_probe_token
app.dependency_overrides[main.require_security_admin] = require_probe_token

# Real-number dialing is deliberately unavailable even with the probe secret.
app.router.routes[:] = [route for route in app.router.routes if getattr(route, 'path', '') != '/v1/call/dial']


@app.post('/probe/webhook')
async def webhook(payload: dict, x_webhook_token: str = Header(default='')):
    if x_webhook_token != settings.webhook_token:
        raise HTTPException(401)
    events.append({'kind': 'webhook', 'at': time.time(), **payload})
    del events[:-1000]
    return {'ok': True}


@app.get('/probe/status', dependencies=[Depends(main.require_service_token)])
async def status():
    return {'synthetic_only': True, 'events': events, 'inputs': inputs,
            'active_sessions': len(manager.sessions_by_call), 'active_bindings': len(driver.calls_by_id),
            'active_tokens': len(manager.sessions_by_token), 'active_lifecycles': len(driver.media_tasks),
            'active_sockets': len(sockets), 'notice_waiters': len(driver.playback_waiters)}


@app.post('/probe/call', dependencies=[Depends(main.require_service_token)])
async def test_call(source: str = 'null', fault: str = '', max_duration_sec: int = Query(default=300, ge=1, le=300)):
    if source not in {'null', 'tone'}:
        raise HTTPException(422, 'only null or internal tone is permitted')
    if fault not in {'', 'no_connect', 'notice'}:
        raise HTTPException(422, 'unknown synthetic fault')
    if len(driver.calls_by_id) >= 5:
        raise HTTPException(429, 'probe limit reached')
    call_id = f'media-probe-{uuid4()}'
    fs_uuid = str(uuid4())
    binding = CallBinding(call_id=call_id, fs_uuid=fs_uuid,
                          status_webhook_url='http://media-probe:8002/probe/webhook',
                          media_webhook_url='http://media-probe:8002/probe/webhook',
                          metadata={'recording_enabled': fault == 'notice', 'recording_notice': fault == 'notice',
                                    'probe_fault': fault}, voice_ai_pipeline='pipecat')
    driver.calls_by_id[call_id] = binding
    driver.calls_by_uuid[fs_uuid] = binding
    # Neither endpoint can reach a carrier or real telephone number.
    destination = 'null/probe' if source == 'null' else 'loopback/tone/media-test'
    try:
        result = await driver.client.api(
            f"originate {{origination_uuid={fs_uuid},loopback_bowout=false,execute_on_answer='sched_hangup +{max_duration_sec} ALLOTTED_TIMEOUT',hangup_after_bridge=true}}{destination} &park()")
        if not result.startswith('+OK'):
            raise EslError('synthetic channel rejected')
    except (EslError, OSError, TimeoutError):
        driver.calls_by_id.pop(call_id, None)
        driver.calls_by_uuid.pop(fs_uuid, None)
        raise HTTPException(503, 'local test channel could not be created') from None
    # CHANNEL_ANSWER starts media through the project's real event listener.
    return {'call_id': call_id, 'fs_uuid': fs_uuid, 'synthetic_only': True}


@app.websocket('/probe/stall/{token}')
async def stall(socket: WebSocket, token: str):
    current = manager.sessions_by_token.get(token)
    if current is None or current.metadata.get('probe_fault') != 'no_connect':
        await socket.close(code=4404)
        return
    await socket.accept()
    try:
        while True:
            await socket.receive_bytes()  # Deliberately never construct Pipecat.
    except WebSocketDisconnect:
        pass


@app.post('/probe/close-socket/{call_id}', dependencies=[Depends(main.require_service_token)])
async def close_socket(call_id: str):
    if call_id not in sockets:
        raise HTTPException(404)
    await sockets[call_id].close(code=1011, reason='synthetic disconnect test')
    return {'synthetic_only': True}


@app.post('/probe/backlog/{call_id}', dependencies=[Depends(main.require_service_token)])
async def backlog(call_id: str):
    current = manager.sessions_by_call.get(call_id)
    if current is None or call_id not in sockets:
        raise HTTPException(404)
    if current.playback_id:
        raise HTTPException(409)
    current.playback_id = str(uuid4())
    # Intentionally bypass transport pacing to create SIX SECONDS of actual
    # FreeSWITCH backlog. This endpoint exists only in the isolated test app.
    pcm = b''.join(struct.pack('<h', int(10000 * math.sin(2*math.pi*1000*i/8000))) for i in range(48000))
    await sockets[call_id].send_bytes(pcm)
    await sockets[call_id].send_json({'type': 'response.output_audio.done'})
    return {'playback_id': current.playback_id, 'queued_audio_seconds': 6}
