"""Run inside media-probe. Generates tones only; never dials a real number."""
import asyncio
import json
import math
import struct
import time
import wave
from pathlib import Path

import httpx
from app.esl import EslClient


async def main():
    secret = Path('/run/secrets/voismart_esl_password').read_text().strip()
    esl = EslClient('freeswitch-media', 8021, secret)
    async with httpx.AsyncClient(base_url='http://127.0.0.1:8002', headers={'Authorization': f'Bearer {secret}'}, timeout=10) as client:
        async def post(path, payload=None):
            response = await client.post(path, json=payload or {})
            response.raise_for_status()
            return response.json()

        async def wait_for(predicate, timeout=8):
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                state = (await client.get('/probe/status')).json()
                if predicate(state):
                    return state
                await asyncio.sleep(0.05)
            raise AssertionError('timed out waiting for media state')

        report = {'synthetic_only': True, 'real_sip_rtp_cloud_speech': 'unverified', 'checks': []}
        denied = await client.post('/probe/call', headers={'Authorization': 'Bearer invalid'})
        assert denied.status_code == 401
        assert (await client.post('/v1/call/dial', json={})).status_code == 404
        report['checks'].append('unauthorized probe rejected; real-number dial route absent')
        for mode in ('drain', 'interrupt', 'module_backlog_interrupt'):
            interrupt = mode != 'drain'
            call = await post('/probe/call')
            call_id, fs_uuid = call['call_id'], call['fs_uuid']
            recording = f'/recordings/{call_id}.wav'
            try:
                await wait_for(lambda s: s['inputs'].get(call_id, {}).get('bytes', 0) >= 3200)
                await esl.api(f'uuid_setvar {fs_uuid} RECORD_READ_ONLY false')
                await esl.api(f'uuid_record {fs_uuid} start {recording}')
                if mode == 'module_backlog_interrupt':
                    speech = await post(f'/probe/backlog/{call_id}')
                else:
                    speech = await post('/v1/call/speak', {'call_id': call_id, 'text': 'long-tone' if interrupt else 'short-tone'})
                await wait_for(lambda s: any(e.get('call_id') == call_id and e.get('state') == 'speaking' for e in s['events']))
                if interrupt:
                    await asyncio.sleep(0.3)
                    stopped_at = time.monotonic()
                    await post('/v1/call/stop-speaking', {'call_id': call_id})
                    report[f'{mode}_ack_ms'] = round((time.monotonic() - stopped_at) * 1000, 1)
                state = await wait_for(lambda s: any(e.get('call_id') == call_id and e.get('state') == 'listening'
                                                   and e.get('at', 0) > speech_started(s, call_id) for e in s['events']))
                await asyncio.sleep(0.4)
                await esl.api(f'uuid_record {fs_uuid} stop {recording}')
                with wave.open(recording, 'rb') as wav:
                    rate, channels = wav.getframerate(), wav.getnchannels()
                    samples = [x[0] for x in struct.iter_unpack('<h', wav.readframes(wav.getnframes()))][::channels]
                windows = [samples[i:i + rate // 50] for i in range(0, len(samples), rate // 50)]
                audible = sum(sum(x*x for x in w) / max(1, len(w)) > 500**2 for w in windows) / 50
                assert (0.1 < audible < 1.5) if interrupt else (1.05 <= audible <= 1.4), audible
                peak = max(range(500, 1501, 50), key=lambda f: tone_power(samples, rate, f))
                assert peak == 1000, peak
                if interrupt:
                    assert all(abs(x) < 100 for x in samples[-rate//5:]), 'stale tail after interruption'
                else:
                    generated = max(e['at'] for e in state['events'] if e.get('kind') == 'generation_done')
                    drained = max(e['at'] for e in state['events'] if e.get('call_id') == call_id and e.get('state') == 'listening')
                    assert drained - generated >= 0.8, 'generation completion was mistaken for playout completion'
                    report['generation_to_drain_ms'] = round((drained-generated)*1000, 1)
                report['checks'].append({'mode': mode, 'pcm_upstream_bytes': state['inputs'][call_id]['bytes'],
                                         'recorded_tone_hz': peak, 'audible_seconds': audible, 'playback_id': speech['playback_id'], 'recording': recording})
            finally:
                await post('/v1/call/hangup', {'call_id': call_id, 'reason': 'synthetic acceptance'})
                await wait_for(lambda s: s['active_sessions'] == 0 and s['active_bindings'] == 0)
                assert (await esl.api(f'uuid_exists {fs_uuid}')).strip() == 'false'
        report['checks'].append('hangup removes channel, session and token bindings')
        call = await post('/probe/call?source=tone')
        call_id = call['call_id']
        try:
            await wait_for(lambda s: s['inputs'].get(call_id, {}).get('nonzero_samples', 0) >= 4000)
            await post('/v1/call/speak', {'call_id': call_id, 'text': 'short-tone'})
            state = await wait_for(lambda s: any(e.get('call_id') == call_id and e.get('state') == 'listening'
                                               and e.get('at', 0) > speech_started(s, call_id) for e in s['events']))
            stats = state['inputs'][call_id]
            assert stats['tone_600_energy'] > 20 * max(1, stats['tone_1000_energy']), stats
            report['checks'].append({'mode': 'simultaneous_600hz_input_1000hz_output', **stats})
        finally:
            await post('/v1/call/hangup', {'call_id': call_id})
            await wait_for(lambda s: s['active_sessions'] == 0 and s['active_bindings'] == 0)
        print(json.dumps(report, ensure_ascii=False, indent=2))


def speech_started(state, call_id):
    return max((e['at'] for e in state['events'] if e.get('call_id') == call_id and e.get('state') == 'speaking'), default=float('inf'))


def tone_power(samples, rate, frequency):
    coefficient = 2 * math.cos(2 * math.pi * frequency / rate)
    previous = older = 0.0
    for sample in samples:
        current = sample + coefficient * previous - older
        older, previous = previous, current
    return previous*previous + older*older - coefficient*previous*older


if __name__ == '__main__':
    asyncio.run(main())
