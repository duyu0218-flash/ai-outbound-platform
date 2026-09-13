"""Local model/provider simulation; no ASR/TTS, PBX, carrier or external network.

The load uses the production Agent service, HTTP model client and shared quota.
Only the far-side cloud completion and playback acknowledgement are simulated.
"""
import asyncio
import os
import time
from uuid import uuid4

from fastapi import FastAPI, HTTPException

assert os.environ.get('SINGLE500_ISOLATED_MOCK') == 'true'
app = FastAPI()
active = peak = total = cancelled = 0
playbacks = {}
heads = {}
model_delay = float(os.environ.get('SINGLE500_MODEL_DELAY_SEC', '3'))
playback_delay = .2
REPLY = '这项服务支持按需求设置，下面为您介绍具体安排。'


@app.get('/readyz')
async def ready():
    return {'status': 'ready', 'synthetic_provider': True, 'real_audio': False}


@app.get('/stats')
async def stats():
    return dict(active=active, peak=peak, total=total, cancelled=cancelled,
        playback_count=sum(v['text']==REPLY for v in playbacks.values()),
        playback_calls=len({k[0] for k,v in playbacks.items() if v['text']==REPLY}),
        notice_count=sum(v['text']!=REPLY for v in playbacks.values()),
        model_delay_sec=model_delay, playback_delay_sec=playback_delay,
        real_audio=False)


@app.post('/fixture/head')
async def head(body: dict):
    heads[body['call_id']] = body['event_id']
    return {'ok': True}


@app.post('/v1/chat/completions')
async def completion(body: dict):
    global active, peak, total, cancelled
    active += 1; total += 1; peak = max(peak, active)
    try:
        assert body['messages'] and body['model'] == 'synthetic-model'
        await asyncio.sleep(model_delay)
        return {'choices': [{'message': {'content': REPLY}}],
                'usage': {'prompt_tokens': 100, 'completion_tokens': 30}}
    except asyncio.CancelledError:
        cancelled += 1
        raise
    finally:
        active -= 1


@app.post('/v1/call/speak')
async def speak(body: dict):
    key = (body['call_id'], body.get('expected_speech_event_id'), body.get('text'))
    if heads.get(key[0]) != key[1] or not body.get('text', '').strip():
        raise HTTPException(409, 'stale or empty simulated playback')
    if key in playbacks:
        if playbacks[key]['text'] != body['text']:
            raise HTTPException(409, 'changed reply for same turn')
        return playbacks[key]['response']
    await asyncio.sleep(playback_delay)
    if heads.get(key[0]) != key[1]:
        raise HTTPException(409, 'turn changed during simulated playback')
    response = {'playback_id': 'synthetic-' + uuid4().hex, 'playback_complete': True}
    playbacks[key] = {'text': body['text'], 'response': response, 'at': time.monotonic()}
    return response


@app.post('/v1/call/hangup')
async def unexpected_hangup(body: dict):
    raise HTTPException(503, 'unexpected hangup in normal conversation fixture')
