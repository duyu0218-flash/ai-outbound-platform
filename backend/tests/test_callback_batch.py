"""Atomic batch receipts and wire compatibility with the durable sender."""
import hashlib
import hmac
import json
import time
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest
from sqlalchemy import event

from test_callback_inbox import clean_inbox, client, rows, receive, speech
from test_production_hardening import reset_runtime_settings_after_test, _voice_security_contract_module
from app import db
from app.config import get_settings
from app.schemas import TelephonyBatch
from app.services import callback_inbox as inbox

URL = '/api/v1/webhooks/telephony/batch'


def item(payload, kind='speech'):
    body = payload.model_dump(mode='json')
    return dict(id=hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest(), kind=kind, payload=body)


def batch(*payloads):
    return dict(version=1, events=[item(p) for p in payloads])


def test_receipt_matches_single_event_identity_and_dedup(client):
    a, b = speech(uuid4()), speech(uuid4())
    receive(a)
    response = client.post(URL, json=batch(a, b))
    assert response.status_code == 200, response.text
    assert response.json()['accepted'] == [item(p)['id'] for p in (a, b)]
    assert len(rows()) == 2
    assert receive(b)['duplicate']
    assert client.post(URL, json=batch(b, a)).status_code == 200
    with db.session_scope() as session:
        assert inbox.snapshot(session)['pending'] == 2


def test_identity_conflict_rolls_back_other_heads(client):
    original = speech(uuid4()); receive(original)
    changed = original.model_copy(update={'transcript': 'different'})
    response = client.post(URL, json=batch(speech(uuid4()), changed))
    assert response.status_code == 409
    assert len(rows()) == 1


def test_full_partition_does_not_partially_accept_other_calls(client, monkeypatch):
    a, b = speech(uuid4()), speech(uuid4())
    receive(a)
    monkeypatch.setattr(get_settings(), 'callback_inbox_partition_limit', 1)
    later = a.model_copy(update={'event_id': 'second'})
    response = client.post(URL, json=batch(b, later))
    assert response.status_code == 503
    assert response.headers['x-callback-batch-split'] == 'true'
    assert len(rows()) == 1
    # Duplicate heads still acknowledge while the queue is full.
    assert client.post(URL, json=batch(a)).status_code == 200


def test_batch_commit_failure_never_acknowledges_or_charges(client, monkeypatch):
    monkeypatch.setattr(client._transport, 'raise_server_exceptions', False)
    def fail(_): raise RuntimeError('synthetic fsync failure')
    event.listen(db.engine, 'commit', fail)
    try:
        response = client.post(URL, json=batch(speech(uuid4()), speech(uuid4())))
        assert response.status_code >= 500
    finally:
        event.remove(db.engine, 'commit', fail)
    assert not rows()
    with db.session_scope() as session:
        assert inbox.snapshot(session)['pending'] == 0


@pytest.mark.parametrize('mutation', ['same-call', 'same-id', 'invalid-kind', 'invalid-payload', 'too-many', 'version'])
def test_invalid_batch_has_no_receipts(client, mutation):
    a, b = speech(uuid4()), speech(uuid4())
    body = batch(a, b)
    if mutation == 'same-call': body = batch(a, a.model_copy(update={'event_id':'next'}))
    elif mutation == 'same-id': body['events'][1]['id'] = body['events'][0]['id']
    elif mutation == 'invalid-kind': body['events'][1]['kind'] = 'sms'
    elif mutation == 'invalid-payload': body['events'][1]['payload']['call_id'] = 'bad'
    elif mutation == 'too-many': body = batch(*(speech(uuid4()) for _ in range(17)))
    else: body['version'] = 2
    assert client.post(URL, json=body).status_code == 400
    assert not rows()


def test_batch_bytes_are_bounded(client):
    response = client.post(URL, json=batch(speech(uuid4()).model_copy(update={'transcript':'a'*66000})))
    assert response.status_code == 413
    assert not rows()


def test_batch_signature_covers_entire_envelope(client, monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, 'telephony_webhook_secret', 'batch-test-secret')
    body = json.dumps(batch(speech(uuid4()), speech(uuid4()))).encode()
    stamp = str(int(time.time()))
    headers = {'Content-Type':'application/json', 'x-webhook-timestamp':stamp,
        'x-webhook-signature': hmac.new(b'batch-test-secret', stamp.encode()+b'.'+body, hashlib.sha256).hexdigest()}
    assert client.post(URL, content=body+b' ', headers=headers).status_code == 401
    assert not rows()
    assert client.post(URL, content=body, headers=headers).status_code == 200


def test_concurrent_reversed_batches_do_not_double_charge():
    a, b = speech(uuid4()), speech(uuid4())
    def write(index):
        body = TelephonyBatch.model_validate(batch(a, b) if index % 2 else batch(b, a))
        session = db.WebhookSession()
        try:
            inbox.receive_batch(session, body)
            session.finish(success=True)
        finally:
            session.finish(success=False)
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(write, range(12)))
    assert len(rows()) == 2
    with db.session_scope() as session:
        assert inbox.snapshot(session)['pending'] == 2


def test_durable_sender_wire_contract(client, tmp_path):
    import asyncio
    import httpx
    from types import SimpleNamespace
    security = _voice_security_contract_module()
    async def run():
        settings = SimpleNamespace(voice_security_db_path=str(tmp_path/'wire.db'),
            voice_callback_batch_enabled=True, voice_callback_batch_size=16, voice_callback_batch_delay_ms=5,
            voice_callback_concurrency=24, voice_callback_base_url='http://testserver',
            voice_callback_allow_private_http=True, webhook_token='', webhook_secret='')
        sender = security.CallbackSender(settings)
        async def transport(request):
            result = await asyncio.to_thread(client.post, request.url.path, content=request.content,
                                            headers={'content-type':'application/json'})
            return httpx.Response(result.status_code, content=result.content)
        async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
            sender.client = http
            payloads = [speech(uuid4()) for _ in range(8)]
            for payload in payloads:
                await sender.post('http://testserver/api/v1/webhooks/telephony/speech',payload.model_dump(mode='json'))
            assert await sender.flush() == 8
            assert sender.ledger.summary()['pending_callbacks'] == 0
            await sender.stop()
        assert len(rows()) == 8
    asyncio.run(run())
