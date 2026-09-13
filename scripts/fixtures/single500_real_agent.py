"""Production Agent app with a test-only request counter, local provider required."""
import os
from app.main import app
from app.llm import settings

assert os.environ.get('SINGLE500_ISOLATED_MOCK') == 'true'
assert settings.env == 'test' and settings.openai_base_url == 'http://127.0.0.1:18942/v1'
requests = 0


@app.middleware('http')
async def count_requests(request, call_next):
    global requests
    if request.url.path == '/agent/turn':
        requests += 1
    return await call_next(request)


@app.get('/fixture/stats')
async def stats():
    return {'requests': requests, 'production_agent_and_quota': True}
