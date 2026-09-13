"""Signed commands reuse a pool without leaking request identity across tenants."""
import asyncio
import hashlib
import hmac
import json
from collections import OrderedDict

import httpx

from app.services import telephony, worker_runtime


def test_signed_commands_reuse_pool_but_keep_fresh_tenant_headers(monkeypatch):
    secret = 'synthetic-command-secret-' + 's'*32
    monkeypatch.setattr(telephony.settings, 'voice_command_secret', secret)
    original_client = httpx.AsyncClient
    clients, requests = [], []

    async def respond(request):
        requests.append(request)
        wire = json.loads(request.content)
        assert request.headers['Authorization'] == f"Bearer tenant-{wire['tenant_id']}"
        signed = (request.headers['x-voice-timestamp'] + '.' + request.headers['x-voice-nonce']
                  + '.' + request.url.path + '.').encode() + request.content
        assert hmac.compare_digest(request.headers['x-voice-signature'],
                                   hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest())
        assert wire['expected_attempt'] == wire['tenant_id']
        return httpx.Response(200, json={'playback_id':'test', 'playback_complete':True})

    def make_client(**kwargs):
        assert 'headers' not in kwargs
        client = original_client(**kwargs, transport=httpx.MockTransport(respond))
        clients.append(client)
        return client

    monkeypatch.setattr(worker_runtime.httpx, 'AsyncClient', make_client)

    async def run():
        resources = OrderedDict()
        token = worker_runtime._resources.set(resources)
        try:
            adapters = [telephony.HttpAdapter('https://gateway.invalid', bearer_token=f'tenant-{i}',
                         tenant_id=i, expected_attempt=i) for i in (1,2)]
            await asyncio.gather(*(adapters[i%2].speak(call_id=str(i), text='reply',
                expected_speech_event_id=f'event-{i}') for i in range(20)))
            assert len(clients) == 1
            assert 'authorization' not in clients[0].headers
            assert len({r.headers['x-voice-nonce'] for r in requests}) == 20
        finally:
            for client in resources.values(): await client.aclose()
            worker_runtime._resources.reset(token)
    asyncio.run(run())
