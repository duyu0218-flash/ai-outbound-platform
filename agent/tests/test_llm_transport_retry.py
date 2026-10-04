import asyncio
from contextlib import asynccontextmanager

from fastapi import HTTPException
import httpx
import pytest

from app import llm


def install(monkeypatch, transport, *, reject_after=None):
    class Budget:
        def __init__(self):
            self.reservations=[];self.inflight=0;self.releases=0
        async def acquire(self,tokens):
            if reject_after is not None and len(self.reservations)>=reject_after:
                raise HTTPException(429,'account budget exhausted')
            self.reservations.append(tokens);self.inflight+=1
        def release(self):
            self.inflight-=1;self.releases+=1
    budget=Budget()
    @asynccontextmanager
    async def client():
        async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as result:
            yield result
    monkeypatch.setattr(llm,'get_llm_client',client)
    monkeypatch.setattr(llm,'quota',budget)
    monkeypatch.setattr(llm.settings,'openai_base_url','http://127.0.0.1/v1')
    monkeypatch.setattr(llm.settings,'openai_api_key','synthetic-local-only')
    monkeypatch.setattr(llm.settings,'llm_allowed_hosts','127.0.0.1')
    monkeypatch.setattr(llm.settings,'llm_quota_db_path','')
    monkeypatch.setattr(llm.settings,'openai_timeout_sec',1)
    return budget


async def reply():
    return await llm.generate_reply(script='approved',transcript='hello',language='zh-CN')


@pytest.mark.parametrize('error',[httpx.ConnectError,httpx.ReadError,httpx.RemoteProtocolError])
def test_transient_proposal_retries_once_and_reserves_each_wire_attempt(monkeypatch,error):
    requests=[]
    async def transport(request):
        requests.append(request.content)
        if len(requests)==1:raise error('synthetic connection failure',request=request)
        return httpx.Response(200,json={'choices':[{'message':{'content':'正常回复'}}]})
    budget=install(monkeypatch,transport)
    assert asyncio.run(reply())=='正常回复'
    assert len(requests)==2 and requests[0]==requests[1]
    assert len(budget.reservations)==2 and budget.reservations[0]==budget.reservations[1]
    assert budget.releases==2 and budget.inflight==0


@pytest.mark.parametrize('kind',['broken','timeout','http','invalid'])
def test_retries_are_bounded_and_exclude_timeout_http_and_invalid_reply(monkeypatch,kind):
    requests=[]
    async def transport(request):
        requests.append(True)
        if kind=='broken':raise httpx.ReadError('broken',request=request)
        if kind=='timeout':raise httpx.ReadTimeout('timeout',request=request)
        if kind=='http':return httpx.Response(503,json={'error':'unavailable'})
        return httpx.Response(200,json={})
    budget=install(monkeypatch,transport)
    error={'broken':httpx.ReadError,'timeout':httpx.ReadTimeout,'http':httpx.HTTPStatusError,'invalid':RuntimeError}[kind]
    with pytest.raises(error):asyncio.run(reply())
    expected=2 if kind=='broken' else 1
    assert len(requests)==len(budget.reservations)==budget.releases==expected
    assert budget.inflight==0


def test_retry_stops_when_second_account_reservation_is_rejected(monkeypatch):
    requests=[]
    async def transport(request):
        requests.append(True);raise httpx.ReadError('broken',request=request)
    budget=install(monkeypatch,transport,reject_after=1)
    with pytest.raises(HTTPException) as error:asyncio.run(reply())
    assert error.value.status_code==429
    assert requests==[True] and budget.releases==1 and budget.inflight==0


def test_retry_backoff_shares_original_deadline(monkeypatch):
    requests=[]
    async def transport(request):
        requests.append(True);raise httpx.ReadError('broken',request=request)
    budget=install(monkeypatch,transport)
    monkeypatch.setattr(llm.settings,'openai_timeout_sec',.02)
    with pytest.raises(TimeoutError):asyncio.run(reply())
    assert requests==[True] and budget.releases==1 and budget.inflight==0


def test_cancellation_during_backoff_never_sends_second_proposal(monkeypatch):
    async def run():
        failed=asyncio.Event();requests=[]
        async def transport(request):
            requests.append(True);failed.set();raise httpx.ReadError('broken',request=request)
        budget=install(monkeypatch,transport)
        task=asyncio.create_task(reply())
        await failed.wait();task.cancel()
        with pytest.raises(asyncio.CancelledError):await task
        assert requests==[True] and budget.releases==1 and budget.inflight==0
    asyncio.run(run())
