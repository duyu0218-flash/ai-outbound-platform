"""Destructive-to-synthetic-calls-only fault checks, run inside media-probe."""
import asyncio
import hashlib
import argparse
import json
import time
from pathlib import Path

import httpx
from app.esl import EslClient


async def main(peer_repeats=1):
    secret = Path('/run/secrets/voismart_esl_password').read_text().strip()
    esl = EslClient('freeswitch-media', 8021, secret)
    report = {'synthetic_only': True, 'real_human_transfer': 'unverified',
              'peer_hangup_repetitions': peer_repeats, 'checks': []}
    counters = ('active_sessions', 'active_bindings', 'active_tokens', 'active_lifecycles', 'active_sockets', 'notice_waiters')
    service_token = hashlib.sha256((secret + ':probe-service').encode()).hexdigest()
    async with httpx.AsyncClient(base_url='http://127.0.0.1:8002',
                                headers={'Authorization': f'Bearer {service_token}'}, timeout=10) as client:
        async def post(path, payload=None):
            response = await client.post(path, json=payload or {})
            response.raise_for_status()
            return response.json()

        async def wait(predicate, timeout=8):
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                try:
                    response = await client.get('/probe/status')
                except httpx.ConnectError:
                    await asyncio.sleep(0.1)
                    continue
                response.raise_for_status()
                state = response.json()
                if predicate(state):
                    return state
                await asyncio.sleep(0.05)
            raise AssertionError('media lifecycle condition timed out')

        def failures(state, call_id):
            return [e['payload']['hangup_reason'] for e in state['events']
                    if e.get('call_id') == call_id and e.get('payload', {}).get('status') == 'failed']

        await wait(lambda s: all(s[k] == 0 for k in counters))
        modes = ['socket_disconnect', 'module_stop', 'no_connect', 'transfer', 'notice_transfer', 'notice_hangup']
        for mode in modes + ['remote_hangup'] * peer_repeats + ['recovery']:
            fault = 'no_connect' if mode == 'no_connect' else ('notice' if mode.startswith('notice_') else '')
            call = await post(f'/probe/call?fault={fault}')
            call_id, fs_uuid = call['call_id'], call['fs_uuid']
            started = time.monotonic()
            try:
                if mode.startswith('notice_'):
                    await wait(lambda s: s['notice_waiters'] == 1)
                elif mode != 'no_connect':
                    await wait(lambda s: s['inputs'].get(call_id, {}).get('bytes', 0) >= 1600)
                if mode == 'socket_disconnect':
                    await post(f'/probe/close-socket/{call_id}')
                elif mode == 'module_stop':
                    await esl.api(f'uuid_raw_audio_stream {fs_uuid} stop')
                elif mode == 'remote_hangup':
                    await esl.api(f'uuid_kill {fs_uuid} NORMAL_CLEARING')
                elif mode in {'transfer', 'notice_transfer'}:
                    await post('/v1/call/transfer', {'call_id': call_id, 'target_group': 'agent:1'})
                    state = await wait(lambda s: s['active_sessions'] == 0 and s['active_lifecycles'] == 0 and s['notice_waiters'] == 0)
                    assert (await esl.api(f'uuid_exists {fs_uuid}')).strip() == 'true', 'handoff killed customer leg'
                    bugs = await esl.api(f'uuid_buglist {fs_uuid}')
                    assert 'openai' not in bugs.lower(), 'AI media bug survived transfer'
                    # Let the original five-second announcement deadline pass;
                    # a leaked startup task would start recording/media afterward.
                    await asyncio.sleep(5.2 if mode == 'notice_transfer' else 0.2)
                    await wait(lambda s: s['active_sessions'] == 0 and s['active_lifecycles'] == 0)
                    bugs = await esl.api(f'uuid_buglist {fs_uuid}')
                    assert 'record_session' not in bugs and 'openai' not in bugs.lower()
                    await post('/v1/call/hangup', {'call_id': call_id})
                elif mode in {'notice_hangup', 'recovery'}:
                    await post('/v1/call/hangup', {'call_id': call_id})
                state = await wait(lambda s: all(s[k] == 0 for k in counters), timeout=22)
                assert (await esl.api(f'uuid_exists {fs_uuid}')).strip() == 'false'
                errors = failures(state, call_id)
                if mode in {'socket_disconnect', 'module_stop'}:
                    assert errors == ['MEDIA_DISCONNECTED'], errors
                elif mode == 'no_connect':
                    assert errors == ['MEDIA_CONNECT_TIMEOUT'], errors
                    assert 14 <= time.monotonic() - started < 22
                else:
                    assert not errors, (mode, errors)
                    assert any(e.get('call_id') == call_id and e.get('payload', {}).get('status') == 'ended'
                               for e in state['events']), (mode, 'missing normal terminal status')
                    assert not [e for e in state['events'] if e.get('call_id') == call_id
                                and e.get('state') == 'closed' and e.get('error_code')], 'intentional close reported as failure'
                report['checks'].append({'mode': mode, 'seconds': round(time.monotonic() - started, 2),
                                         'failure_reasons': errors, 'remaining': {k: state[k] for k in counters}})
            finally:
                # Only the synthetic UUID returned by this probe is in scope.
                if (await esl.api(f'uuid_exists {fs_uuid}')).strip() == 'true':
                    await esl.api(f'uuid_kill {fs_uuid} NORMAL_CLEARING')
            # Repeatability, not CPS/load: stay below the test PBX's 5 CPS cap.
            await asyncio.sleep(0.3)
        print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--peer-repeats', type=int, choices=range(1, 21), default=1)
    asyncio.run(main(parser.parse_args().peer_repeats))
