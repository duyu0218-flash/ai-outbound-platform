import asyncio,time
from fastapi import FastAPI
app=FastAPI();active=0;peak=0;total=0;cancelled=0
@app.get('/stats')
async def stats():return dict(active=active,peak=peak,total=total,cancelled=cancelled)
@app.post('/agent/turn')
async def turn(body:dict):
    global active,peak,total,cancelled
    active+=1;peak=max(peak,active);total+=1
    try:
        await asyncio.sleep(3)
        return {'action':'continue','tts_text':None}
    except asyncio.CancelledError:
        cancelled+=1;raise
    finally:active-=1

@app.get("/health")
async def health():return {"status":"ok"}
