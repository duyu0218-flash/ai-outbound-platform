"""Run inside media-probe; force the production dial command to null/ only.

No SIP gateway, cloud speech or real telephone destination can be reached.
Tests the actual FreeSWITCH timer with the controlling gateway stopped.
"""
import asyncio
import hashlib
import json
import tempfile
import time
from pathlib import Path

from app.config import Settings
from app.esl import EslClient, EslError
from app.freeswitch import FreeswitchEslDriver
from app.security import SecureDriver


async def main():
    esl_secret = Path('/run/secrets/voismart_esl_password').read_text().strip()
    client = EslClient('freeswitch-media', 8021, esl_secret)
    if (await client.api('show channels count')).strip() != '0 total.':
        raise RuntimeError('other synthetic channels are active; do not interrupt them')

    class NullOnlyClient:
        count = 0
        async def api(self, command):
            return await client.api(command)
        def events(self, names):
            return client.events(names)
        async def bgapi(self, command):
            target = 'sofia/gateway/synthetic-never-configured/12025550123'
            if command.count(target) != 1 or not command.endswith(' &park()'):
                raise RuntimeError('refusing non-synthetic dial command')
            rewritten = command.replace(target, 'null/security-qa')
            if 'sofia/' in rewritten or 'bridge(' in rewritten:
                raise RuntimeError('no SIP or bridge destinations permitted in this test')
            self.count += 1
            return await client.bgapi(rewritten)

    with tempfile.TemporaryDirectory(prefix='voice-security-synthetic-') as directory:
        key = lambda label: hashlib.sha256((esl_secret + ':synthetic:' + label).encode()).hexdigest()
        route = dict(gateway='synthetic-never-configured', caller_id='12025550100', allowed_prefixes=['12025550123'],
                     max_concurrent=1, cps=1, calls_per_day=2, hour_budget_minor=100, day_budget_minor=100,
                     rate_minor_per_minute=10, max_duration_sec=2)
        settings = Settings(_env_file=None, env='dev', voice_gateway_driver='freeswitch_esl',
                            service_token=key('service'), voice_command_secret=key('command'),
                            voice_security_admin_token=key('admin'), webhook_token=key('callback'), webhook_secret=key('signing'),
                            voice_security_db_path=str(Path(directory) / 'ledger.sqlite3'),
                            voice_security_routes_json=json.dumps({'1:0': route}), voice_callback_base_url='http://127.0.0.1:9',
                            voice_callback_allow_private_http=True, voice_max_duration_sec=2,
                            freeswitch_esl_password=esl_secret, freeswitch_gateway='synthetic-never-configured',
                            freeswitch_tts_engine='unused', freeswitch_tts_voice='unused', freeswitch_originate_timeout_sec=5)
        guarded_client = NullOnlyClient()
        driver = SecureDriver(settings, FreeswitchEslDriver(settings, client=guarded_client))
        payload = dict(call_id='synthetic-security-call', phone='12025550123',
                       webhook_url='http://127.0.0.1:9/api/v1/webhooks/telephony/status',
                       metadata={'tenant_id': 1, 'attempt': 1, 'recording_enabled': False})
        provider_id = None
        observed_cause = asyncio.Future()
        async def observe_termination():
            async for event in client.events(('CHANNEL_HANGUP_COMPLETE',)):
                if event.get('Unique-ID') == provider_id:
                    observed_cause.set_result(event.get('Hangup-Cause'))
                    return
        observer = asyncio.create_task(observe_termination())
        await driver.start()
        try:
            await asyncio.sleep(0.2)  # allow the ESL event subscription to attach
            results = await asyncio.gather(*(driver.post('dial', payload) for _ in range(100)))
            provider_id = results[0]['provider_call_id']
            assert guarded_client.count == 1
            deadline = time.monotonic() + 5
            while (await client.api(f'uuid_exists {provider_id}')).strip() != 'true':
                assert time.monotonic() < deadline
                await asyncio.sleep(0.05)
            stopped_at = time.monotonic()
            assert not (await driver.post('status', {'call_id': payload['call_id'], 'tenant_id': 1}))['ended']
            await driver.stop()  # no application/controller timer may save us
            while (await client.api(f'uuid_exists {provider_id}')).strip() != 'false':
                assert time.monotonic() - stopped_at < 6, 'PBX hard hangup timer did not execute'
                await asyncio.sleep(0.05)
            elapsed = round(time.monotonic() - stopped_at, 3)
            cause = await asyncio.wait_for(observed_cause, 3)
            assert cause == 'ALLOTTED_TIMEOUT', cause
            restarted = SecureDriver(settings, FreeswitchEslDriver(settings, client=guarded_client))
            assert (await restarted.post('dial', payload))['provider_call_id'] == provider_id
            assert guarded_client.count == 1
            # A missing uuid during originate remains ambiguous until the
            # configured safety deadline. Wait it out; never edit the ledger.
            row = restarted.ledger.lookup(payload['call_id'], 1)
            await asyncio.sleep(max(0, row['deadline'] - time.time() + 0.1))
            assert (await restarted.post('hangup', {'call_id': payload['call_id'], 'tenant_id': 1}))['ended']
            assert restarted.ledger.summary()['active_attempts'] == 0
            return {'synthetic_only': True, 'real_sip_rtp': 'unverified', 'requests': 100,
                    'actual_null_originates': guarded_client.count, 'pbx_hangup_after_controller_stop_sec': elapsed,
                    'pbx_hangup_cause': cause,
                    'restart_redial_prevented': True, 'confirmed_termination_releases_capacity': True}
        finally:
            observer.cancel()
            try:
                await observer
            except asyncio.CancelledError:
                pass
            await driver.stop()
            if provider_id:
                try:
                    await client.api(f'uuid_kill {provider_id} NORMAL_CLEARING')
                except EslError:
                    pass  # already ended


if __name__ == '__main__':
    print(json.dumps(asyncio.run(main()), ensure_ascii=False, indent=2))
