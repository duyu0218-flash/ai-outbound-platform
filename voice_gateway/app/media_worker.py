"""Pipecat-only process: no ESL connection, dialing or security-ledger writer."""
import asyncio
import secrets
import time
import hashlib
from collections import OrderedDict
from contextlib import asynccontextmanager
from uuid import uuid4

import httpx
from fastapi import FastAPI, Depends, Header, HTTPException, WebSocket
from pydantic import BaseModel, Field
from .config import get_settings
from .pipecat_pipeline import PipecatPipelineManager, PipecatCallSession, MediaPlaybackBusyError

settings=get_settings()
epoch=uuid4().hex
manager=PipecatPipelineManager(settings.model_copy(update={'pipecat_max_active_sessions':settings.media_worker_capacity}))
registered={}
closed=OrderedDict()
create_lock=asyncio.Lock()
http=None

async def authorized(authorization: str | None=Header(default=None)):
    if len(settings.media_rpc_token)<32 or not secrets.compare_digest(authorization or '', 'Bearer '+settings.media_rpc_token):
        raise HTTPException(401,'media RPC credential required')

async def post_event(url,payload):
    call_id=str(payload.get('call_id') or '')
    session=registered.get(call_id)
    if session is None or payload.get('provider_session_id') != session.session_id:
        raise RuntimeError('media event has no matching registered generation')
    body={'worker_id':settings.media_worker_id,'epoch':epoch,'call_id':call_id,
        'session_id':session.session_id,'url':url,'payload':payload}
    for attempt in range(3):
        try:
            response=await http.post(settings.media_control_url.rstrip('/')+'/v1/internal/media-events',json=body)
            response.raise_for_status()
            if response.status_code!=200:raise RuntimeError('media event not durably acknowledged')
            return
        except httpx.HTTPError:
            if attempt == 2:
                session.media_error_code='MEDIA_CALLBACK_UNAVAILABLE'
                session.terminated.set()
                raise
            await asyncio.sleep(.1 * (attempt + 1))

@asynccontextmanager
async def lifespan(_):
    global http
    settings.validate_media_runtime()
    if not settings.media_worker_id or len(settings.media_rpc_token)<32:
        raise RuntimeError('media worker identity and RPC token required')
    from urllib.parse import urlsplit
    target=urlsplit(settings.media_control_url)
    if target.scheme!='http' or target.hostname!='127.0.0.1' or target.username or target.password or target.query or target.fragment or target.path not in ('','/'):
        raise RuntimeError('media control must be loopback HTTP')
    if not 1<=settings.media_worker_capacity<=200:raise RuntimeError('invalid media capacity')
    if not manager.ready():raise RuntimeError('Pipecat distribution differs from configured version')
    http=httpx.AsyncClient(timeout=settings.media_rpc_timeout_sec,trust_env=False,
        headers={'Authorization':'Bearer '+settings.media_rpc_token},limits=httpx.Limits(max_connections=32,max_keepalive_connections=16))
    manager._post_json=post_event
    try:yield
    finally:
        await asyncio.gather(*(manager.close(cid,notify=False) for cid in list(registered)),return_exceptions=True)
        await http.aclose()

app=FastAPI(title='Internal media process',lifespan=lifespan)

@app.get('/internal/state',dependencies=[Depends(authorized)])
async def state():
    return {'worker_id':settings.media_worker_id,'epoch':epoch,'ready':manager.ready(),
        'capacity':settings.media_worker_capacity,'metrics':manager.metrics,
        'sessions':{cid:{'session_id':s.session_id,'started':s.startup_complete.is_set(),
            'terminated':s.terminated.is_set(),'latest_final_event_id':s.latest_final_event_id,
            'media_error_code':s.media_error_code} for cid,s in registered.items()}}

class Command(BaseModel):
    action: str
    epoch: str
    call_id: str=Field(max_length=128)
    session_id: str
    attempt: int | None=None
    session: dict | None=None
    text: str=Field(default='',max_length=50000)
    operation_id: str=Field(default='',max_length=128)
    expires_at: float | None=None
    expected_speech_event_id: str | None=None
    closing: bool=False
    kind: str=''
    timestamp_us: int=0
    notify: bool=True

@app.post('/internal/command',dependencies=[Depends(authorized)])
async def command(cmd:Command):
    if cmd.expires_at is not None and time.time()>cmd.expires_at:
        raise HTTPException(409,'media command expired')
    if cmd.epoch!=epoch:raise HTTPException(409,'worker epoch changed')
    if cmd.action=='create':
        async with create_lock:
            if cmd.expires_at is not None and time.time()>cmd.expires_at:
                raise HTTPException(409,'media command expired')
            existing=registered.get(cmd.call_id)
            if existing is not None:
                if existing.session_id!=cmd.session_id or existing.metadata.get('attempt')!=cmd.attempt:raise HTTPException(409,'call already has another media generation')
                return {'created':True}
            if cmd.session_id in closed:raise HTTPException(409,'media generation was closed')
            if len(registered)>=settings.media_worker_capacity:raise HTTPException(503,'media process at capacity')
            data=cmd.session or {}
            if data.get('call_id')!=cmd.call_id or data.get('session_id')!=cmd.session_id or data.get('metadata',{}).get('attempt')!=cmd.attempt:
                raise HTTPException(409,'media ownership mismatch')
            session=PipecatCallSession(**data)
            registered[cmd.call_id]=session
            manager.sessions_by_call[cmd.call_id]=session
            manager.sessions_by_token[session.token]=session
            return {'created':True}
    session=registered.get(cmd.call_id)
    if session is None:
        if cmd.action=='close':
            # Fence an ambiguous create before acknowledging absence. A delayed
            # create for this generation must not resurrect the closed owner.
            closed[cmd.session_id]=cmd.call_id
            while len(closed)>1000:closed.popitem(last=False)
            return {'closed':True}
        raise HTTPException(404,'unknown media session')
    if session.session_id!=cmd.session_id or session.metadata.get('attempt')!=cmd.attempt:
        raise HTTPException(409,'stale media session')
    if cmd.expected_speech_event_id is not None and session.latest_final_event_id!=cmd.expected_speech_event_id:
        raise HTTPException(409,'stale speech generation')
    try:
        if cmd.action=='fence':
            if cmd.closing:session.closing=True
        elif cmd.action=='speak':
            # A backend retry after an ambiguous RPC response must not queue
            # the same reply twice. Results live only for this worker epoch.
            async with session.rpc_lock:
                if registered.get(cmd.call_id) is not session or session.closing or session.terminated.is_set():
                    raise HTTPException(409,'media generation is no longer active')
                if cmd.expires_at is not None and time.time()>cmd.expires_at:
                    raise HTTPException(409,'media command expired')
                if cmd.expected_speech_event_id is not None and session.latest_final_event_id!=cmd.expected_speech_event_id:
                    raise HTTPException(409,'stale speech generation')
                digest=hashlib.sha256(cmd.text.encode()).hexdigest()
                cached=session.rpc_results.get(cmd.operation_id) if cmd.operation_id else None
                if cached:
                    if cached[0]!=digest:raise HTTPException(409,'reply operation payload changed')
                    return cached[1]
                result={'playback_id':await manager.speak(cmd.call_id,cmd.text)}
                if cmd.operation_id:
                    session.rpc_results[cmd.operation_id]=(digest,result)
                    while len(session.rpc_results)>64:session.rpc_results.popitem(last=False)
                return result
        elif cmd.action=='interrupt':await manager.interrupt(cmd.call_id)
        elif cmd.action=='module':await manager.handle_module_event(cmd.call_id,cmd.kind,cmd.timestamp_us)
        elif cmd.action=='close':
            async with session.rpc_lock:
                if registered.get(cmd.call_id) is not session:return {'closed':True}
                if not cmd.notify:session.closed_notified=True
                await manager.close(cmd.call_id,notify=cmd.notify)
                registered.pop(cmd.call_id,None)
                closed[cmd.session_id]=cmd.call_id
                while len(closed)>1000:closed.popitem(last=False)
        else:raise HTTPException(400,'unsupported media operation')
    except MediaPlaybackBusyError as exc:raise HTTPException(409,str(exc)) from exc
    except KeyError as exc:raise HTTPException(404,'media session ended') from exc
    return {'ok':True}

@app.websocket('/v1/pipecat/media/{token}')
async def media(websocket:WebSocket,token:str):
    await manager.run_websocket(websocket,token)
