import asyncio, os
from fastapi import FastAPI
app=FastAPI()
active=0;peak=0;seen=set();gate=asyncio.Event()
@app.get('/stats')
async def stats():return {'active':active,'peak':peak,'unique_calls':len(seen)}
@app.post('/agent/turn')
async def turn(body:dict):
    global active,peak
    active+=1;peak=max(peak,active);seen.add(body['call_id'])
    if active>=256:gate.set()
    try:
        await asyncio.wait_for(gate.wait(),30)
        await asyncio.sleep(3)
        return {'action':'continue','tts_text':None}
    finally:active-=1
