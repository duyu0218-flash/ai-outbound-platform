"""Production Agent app with a test-only request counter, local provider required."""
import os
import httpx
from contextlib import asynccontextmanager
from app.main import app
from app import llm
from app.llm import settings

assert os.environ.get('SINGLE500_ISOLATED_MOCK') == 'true'
assert settings.env == 'test' and settings.openai_base_url == 'http://127.0.0.1:18942/v1'
requests = 0
injected_errors = 0
original_client = llm.get_llm_client


if os.environ.get('SINGLE500_AGENT_INJECT_READ_ERROR') == 'true':
    class FaultOnce:
        def __init__(self,client):self.client=client
        async def post(self,*args,**kwargs):
            global injected_errors
            if not injected_errors:
                injected_errors+=1
                raise httpx.ReadError('synthetic pre-send connection reset')
            return await self.client.post(*args,**kwargs)
    @asynccontextmanager
    async def fault_client():
        async with original_client() as client:
            yield FaultOnce(client)
    llm.get_llm_client=fault_client


@app.middleware('http')
async def count_requests(request, call_next):
    global requests
    if request.url.path == '/agent/turn':
        requests += 1
    return await call_next(request)


@app.get('/fixture/stats')
async def stats():
    return {'requests': requests, 'production_agent_and_quota': True,
            'model_transport_errors_injected': injected_errors}
