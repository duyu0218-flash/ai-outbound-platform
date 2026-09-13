"""Batch delivery preserves durable acceptance and FIFO under ambiguous results."""
import asyncio
import json

import httpx
import pytest
from app.security import CallbackSender
from test_security import configuration

URL = 'http://control-api:8000/api/v1/webhooks/telephony/speech'


def payload(cid, event='first'):
    return dict(call_id=cid, event_id=event, transcript='hello', is_final=True, attempt=1)


def ack(request):
    body = json.loads(request.content)
    return httpx.Response(200, json={'version':1, 'result':'received',
        'accepted':[e['id'] for e in body['events']]})


def test_batch_only_takes_one_head_per_call_and_releases_after_commit(tmp_path):
    async def run():
        sender = CallbackSender(configuration(tmp_path, voice_callback_batch_enabled=True))
        requests = []
        async def respond(request):
            requests.append(json.loads(request.content))
            return ack(request)
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            sender.client = client
            for cid in ('a','b','c'):
                for event in ('first','second'):
                    await sender.post(URL, payload(cid,event))
            assert await sender.flush() == 3
            assert sender.ledger.summary()['pending_callbacks'] == 3
            assert await sender.flush() == 3
            assert sender.ledger.summary()['pending_callbacks'] == 0
            assert [[e['payload']['event_id'] for e in r['events']] for r in requests] == [['first']*3,['second']*3]
            await sender.stop()
    asyncio.run(run())


@pytest.mark.parametrize('failure', ['response-loss','bad-ack','partial-ack','server-error'])
def test_ambiguous_batch_keeps_all_heads_across_restart(tmp_path, failure):
    async def run():
        cfg = configuration(tmp_path, voice_callback_batch_enabled=True)
        sender = CallbackSender(cfg)
        bodies = []
        async def respond(request):
            bodies.append(request.content)
            if failure == 'response-loss': raise httpx.ReadError('lost ACK', request=request)
            if failure == 'server-error': return httpx.Response(503)
            if failure == 'partial-ack':
                result = ack(request).json();result['accepted'].pop()
                return httpx.Response(200,json=result)
            return httpx.Response(200,json={'accepted':[]})
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            sender.client=client
            for cid in ('a','b'): await sender.post(URL,payload(cid))
            await sender.flush()
            assert sender.ledger.summary()['pending_callbacks'] == 2
            await sender.stop()
        restored = CallbackSender(cfg)
        with restored.ledger.transaction() as db:db.execute('UPDATE outbox SET due=0')
        async def accept(request):
            assert request.content == bodies[0]
            return ack(request)
        async with httpx.AsyncClient(transport=httpx.MockTransport(accept)) as client:
            restored.client=client
            assert await restored.flush()==2
            assert restored.ledger.summary()['pending_callbacks']==0
            await restored.stop()
    asyncio.run(run())


@pytest.mark.parametrize('status', [400,404,409,413,422,503])
def test_rejected_batch_isolates_bad_head_without_overtaking(tmp_path, status):
    async def run():
        sender=CallbackSender(configuration(tmp_path,voice_callback_batch_enabled=True))
        async def respond(request):
            if request.url.path.endswith('/batch'):
                return httpx.Response(status,headers={'X-Callback-Batch-Split':'true'} if status==503 else {})
            body=json.loads(request.content)
            return httpx.Response(409 if body['call_id']=='bad' else 200)
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            sender.client=client
            for cid in ('bad','good'):
                await sender.post(URL,payload(cid))
                await sender.post(URL,payload(cid,'next'))
            assert await sender.flush()==2
            assert sender.ledger.summary()['pending_callbacks']==3
            assert await sender.flush()==1
            assert sender.ledger.summary()['pending_callbacks']==2
            await sender.stop()
    asyncio.run(run())


def test_disabled_batching_remains_single(tmp_path):
    async def run():
        sender=CallbackSender(configuration(tmp_path,voice_callback_batch_enabled=False))
        seen=[]
        async def respond(request):seen.append(request.url.path);return httpx.Response(200)
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            sender.client=client
            for cid in ('a','b'):await sender.post(URL,payload(cid))
            assert await sender.flush()==2
            assert seen==['/api/v1/webhooks/telephony/speech']*2
            await sender.stop()
    asyncio.run(run())
