"""Only pure model proposals may retry; deadlines and cancellation still win."""
import asyncio
from contextlib import asynccontextmanager

import httpx
import pytest

import test_production_hardening  # Initialize isolated test settings before app imports.
from app.services import dispatcher


def install(monkeypatch, transport):
    @asynccontextmanager
    async def client(**kwargs):
        kwargs.pop('max_connections', None)
        async with httpx.AsyncClient(transport=httpx.MockTransport(transport), **kwargs) as result:
            yield result
    monkeypatch.setattr(dispatcher, 'http_client', client)
    monkeypatch.setattr(dispatcher.settings, 'ai_agent_url', 'http://127.0.0.1:18941')


async def request():
    return await dispatcher.request_ai_turn(call_id='00000000-0000-4000-8000-000000000001', phone='13800000000', mode='ai_only')


@pytest.mark.parametrize('error', [httpx.ConnectError, httpx.ReadError, httpx.RemoteProtocolError])
def test_transient_model_transport_retries_same_proposal_once(monkeypatch, error):
    requests = []
    async def transport(req):
        requests.append(req.content)
        if len(requests) == 1:
            raise error('synthetic broken connection', request=req)
        return httpx.Response(200, json={'action': 'continue'})
    install(monkeypatch, transport)
    assert asyncio.run(request()).action == 'continue'
    assert len(requests) == 2 and requests[0] == requests[1]


@pytest.mark.parametrize('kind', ['broken', 'timeout', 'http', 'invalid'])
def test_retry_is_bounded_and_not_applied_to_other_failures(monkeypatch, kind):
    calls = []
    async def transport(req):
        calls.append(True)
        if kind == 'broken': raise httpx.ReadError('broken', request=req)
        if kind == 'timeout': raise httpx.ReadTimeout('timeout', request=req)
        if kind == 'http': return httpx.Response(503, json={'error': 'unavailable'})
        return httpx.Response(200, json={})
    install(monkeypatch, transport)
    expected = {'broken': httpx.ReadError, 'timeout': httpx.ReadTimeout, 'http': RuntimeError, 'invalid': ValueError}[kind]
    with pytest.raises(expected): asyncio.run(request())
    assert len(calls) == (2 if kind == 'broken' else 1)


def test_retry_wait_shares_original_deadline(monkeypatch):
    calls = []
    async def transport(req):
        calls.append(True)
        raise httpx.ReadError('broken', request=req)
    install(monkeypatch, transport)
    monkeypatch.setattr(dispatcher.settings, 'ai_callback_timeout_sec', .02)
    with pytest.raises(TimeoutError): asyncio.run(request())
    assert len(calls) == 1


def test_cancellation_during_retry_wait_never_starts_second_request(monkeypatch):
    async def run():
        failed = asyncio.Event()
        calls = []
        async def transport(req):
            calls.append(True)
            failed.set()
            raise httpx.ReadError('broken', request=req)
        install(monkeypatch, transport)
        task = asyncio.create_task(request())
        await asyncio.wait_for(failed.wait(), 1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError): await task
        assert len(calls) == 1
    asyncio.run(run())
