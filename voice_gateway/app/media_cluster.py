"""One PBX owner, multiple independently addressed media processes.

Only the controller writes this ownership journal. Audio goes directly from
FreeSWITCH to the selected worker; no media bytes traverse this control API.
"""
import asyncio
import json
import hashlib
import time
import secrets
import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from urllib.parse import urlsplit
from uuid import uuid4

import httpx
from fastapi import HTTPException
from .pipecat_pipeline import PipecatCallSession


def worker_specs(settings):
    rows = json.loads(settings.media_workers_json)
    if not isinstance(rows, list) or len(rows) > 16:
        raise ValueError('media workers must be a list of at most 16 processes')
    ids, endpoints = set(), set()
    for row in rows:
        endpoint = urlsplit(row['endpoint'])
        ws = urlsplit(row['ws_base'])
        if (endpoint.scheme != 'http' or endpoint.hostname != '127.0.0.1' or
                endpoint.username or endpoint.password or endpoint.query or endpoint.fragment or endpoint.path not in ('', '/') or
                ws.scheme != 'ws' or ws.hostname != '127.0.0.1' or ws.port != endpoint.port or
                not endpoint.port or ws.password or ws.path.rstrip('/') != '/v1/pipecat/media' or ws.query or ws.fragment or ws.username or
                not 1 <= int(row['capacity']) <= 200 or not row['id'] or
                row['id'] in ids or row['endpoint'] in endpoints):
            raise ValueError('media workers require unique IDs and loopback HTTP/WS endpoints')
        ids.add(row['id']); endpoints.add(row['endpoint'])
    if rows and (len(settings.media_rpc_token) < 32 or not settings.voice_security_db_path):
        raise ValueError('media cluster requires a private RPC token and controller journal')
    if rows and sum(int(row['capacity']) for row in rows) < settings.pipecat_max_active_sessions:
        raise ValueError('media roster capacity is below host session limit')
    if not .5 <= settings.media_health_ttl_sec <= 30:
        raise ValueError('media health TTL must be between .5 and 30 seconds')
    return rows


class OwnershipStore:
    def __init__(self, path): self.path = path

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA synchronous=FULL')
        try:
            yield db
            db.commit()
        finally: db.close()

    def initialize(self):
        with self.db() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS media_owners (
                call_id TEXT PRIMARY KEY, worker_id TEXT NOT NULL, epoch TEXT NOT NULL,
                session_json TEXT NOT NULL, state TEXT NOT NULL)''')
            return [dict(row) for row in db.execute("SELECT * FROM media_owners WHERE state != 'closed'")]

    def save(self, session, worker, epoch, state='pending'):
        data = {k: getattr(session, k) for k in ('call_id','session_id','token','speech_webhook_url','media_webhook_url','metadata')}
        with self.db() as db:
            db.execute('INSERT INTO media_owners VALUES (?,?,?,?,?) ON CONFLICT(call_id) DO UPDATE SET worker_id=excluded.worker_id,epoch=excluded.epoch,session_json=excluded.session_json,state=excluded.state',
                       (session.call_id,worker,epoch,json.dumps(data),state))

    def close(self, call_id):
        with self.db() as db: db.execute("DELETE FROM media_owners WHERE call_id=?",(call_id,))


@dataclass
class Owner:
    spec: dict
    epoch: str
    session: PipecatCallSession


class RemoteMediaManager:
    def __init__(self, settings):
        self.settings = settings
        self.specs = worker_specs(settings)
        self.store = OwnershipStore(settings.voice_security_db_path)
        self.sessions_by_call = {}
        self.owners = {}
        self.health = {}
        self.health_checked_at = {}
        self.metrics = {}
        self.lock = asyncio.Lock()
        self.client = None
        self.monitor = None

    async def start(self):
        self.client = httpx.AsyncClient(timeout=self.settings.media_rpc_timeout_sec,trust_env=False,
            headers={'Authorization': 'Bearer '+self.settings.media_rpc_token},
            limits=httpx.Limits(max_connections=64,max_keepalive_connections=16))
        saved = await asyncio.to_thread(self.store.initialize)
        specs = {s['id']:s for s in self.specs}
        for row in saved:
            if row['worker_id'] not in specs:
                raise RuntimeError('active media owner removed from roster; drain before changing workers')
            session = PipecatCallSession(**json.loads(row['session_json']))
            self.sessions_by_call[session.call_id] = session
            self.owners[session.call_id] = Owner(specs[row['worker_id']],row['epoch'],session)
        await self.refresh()
        self.monitor = asyncio.create_task(self._monitor(),name='media-process-health')

    async def stop(self):
        if self.monitor:
            self.monitor.cancel(); await asyncio.gather(self.monitor,return_exceptions=True)
        if self.client: await self.client.aclose()

    def ready(self):
        if self.settings.media_allow_degraded_admission:
            return self.admission_capacity() > 0
        return bool(self.specs) and all(self._healthy(s) for s in self.specs)

    def _healthy(self, spec):
        return (self.health.get(spec['id'], {}).get('ready') is True and
                time.monotonic() - self.health_checked_at.get(spec['id'], float('-inf'))
                <= self.settings.media_health_ttl_sec)

    def admission_capacity(self):
        # Failed owners remain in the durable journal and backend occupancy.
        # Healthy capacity is only a conservative ceiling for NEW admission.
        return min(self.settings.pipecat_max_active_sessions,
                   sum(s['capacity'] for s in self.specs if self._healthy(s)))

    def _used(self, spec):
        known = {cid for cid, owner in self.owners.items() if owner.spec['id'] == spec['id']}
        return len(known | set(self.health.get(spec['id'], {}).get('sessions', {})))

    async def _monitor(self):
        while True:
            await asyncio.sleep(.25)
            await self.refresh()

    async def refresh(self):
        async def poll(spec):
            started = time.monotonic()
            try:
                response = await self.client.get(spec['endpoint']+'/internal/state',
                                                 timeout=min(self.settings.media_rpc_timeout_sec, self.settings.media_health_ttl_sec / 2))
                response.raise_for_status(); data=response.json()
                if (data['worker_id'] != spec['id'] or data['capacity'] != spec['capacity']
                        or not isinstance(data.get('sessions'), dict) or not data.get('epoch')):
                    raise ValueError('worker identity or capacity differs from roster')
                return spec,data,started
            except (httpx.HTTPError, ValueError, KeyError, TypeError): return spec,{'ready':False},started
        for spec,data,started in await asyncio.gather(*(poll(s) for s in self.specs)):
            if started < self.health_checked_at.get(spec['id'], float('-inf')):
                continue
            self.health[spec['id']] = data
            self.health_checked_at[spec['id']] = started
            for owner in list(self.owners.values()):
                if owner.spec['id'] != spec['id']: continue
                session = owner.session
                state = data.get('sessions',{}).get(session.call_id)
                if not data.get('ready') or data.get('epoch') != owner.epoch:
                    session.media_error_code='MEDIA_WORKER_UNAVAILABLE'
                    session.terminated.set(); session.startup_complete.set()
                elif state is not None:
                    if state['session_id'] != session.session_id:
                        session.media_error_code='MEDIA_WORKER_IDENTITY_CHANGED'; session.terminated.set()
                    if state.get('started'): session.startup_complete.set()
                    if state.get('terminated'): session.terminated.set()
                    session.latest_final_event_id=state.get('latest_final_event_id','')
                    session.media_error_code=state.get('media_error_code') or session.media_error_code
                # A pending create may not have reached the worker yet. Its own
                # startup deadline handles absence; never erase its reservation.
        self.metrics = {'media_workers_ready':sum(bool(self._healthy(s)) for s in self.specs),
                        'media_admission_capacity': self.admission_capacity(),
                        'media_failed_owners': sum(o.session.terminated.is_set() for o in self.owners.values())}
        for data in self.health.values():
            for name, value in data.get('metrics', {}).items():
                if isinstance(value, (float, int)):
                    self.metrics[name] = self.metrics.get(name, 0) + value

    async def rpc(self, owner, action, **payload):
        operation = uuid4().hex
        if action == 'speak' and payload.get('expected_speech_event_id') is not None:
            operation = hashlib.sha256((owner.session.session_id + ':speak:' + payload['expected_speech_event_id']).encode()).hexdigest()
        response = await self.client.post(owner.spec['endpoint']+'/internal/command',json={
            'action':action,'epoch':owner.epoch,'call_id':owner.session.call_id,
            'session_id':owner.session.session_id,'attempt':owner.session.metadata.get('attempt'),
            'operation_id':operation,'expires_at':time.time()+self.settings.media_rpc_timeout_sec,**payload})
        if response.status_code==409: raise HTTPException(409,'stale media owner or speech generation')
        if response.status_code==404: raise KeyError('media session ended')
        response.raise_for_status()
        return response.json()

    async def create_session(self, *, call_id, speech_webhook_url, media_webhook_url, metadata):
        async with self.lock:
            existing = self.sessions_by_call.get(call_id)
            if existing is not None:
                if (existing.metadata.get('attempt') != metadata.get('attempt')
                        or existing.speech_webhook_url != speech_webhook_url
                        or existing.media_webhook_url != media_webhook_url
                        or existing.closing or existing.terminated.is_set()):
                    raise RuntimeError('previous media generation must be closed before reuse')
                return existing
            if len(self.sessions_by_call)>=self.settings.pipecat_max_active_sessions:
                raise RuntimeError('host media capacity reached')
            choices=[]
            for spec in self.specs:
                health=self.health.get(spec['id'],{})
                used=self._used(spec)
                if self._healthy(spec) and used<spec['capacity']: choices.append((used/spec['capacity'],spec['id'],spec))
            if not choices: raise RuntimeError('no media process has capacity')
            spec=min(choices)[2]
            session=PipecatCallSession(call_id=call_id,session_id=str(uuid4()),token=secrets.token_urlsafe(32),
                speech_webhook_url=speech_webhook_url,media_webhook_url=media_webhook_url,metadata=dict(metadata))
            owner=Owner(spec,self.health[spec['id']]['epoch'],session)
            await asyncio.to_thread(self.store.save,session,spec['id'],owner.epoch)
            self.sessions_by_call[call_id]=session; self.owners[call_id]=owner
        await self.rpc(owner,'create',session={k:getattr(session,k) for k in (
            'call_id','session_id','token','speech_webhook_url','media_webhook_url','metadata')})
        return session

    def media_ws_url(self, session):
        return self.owners[session.call_id].spec['ws_base'].rstrip('/')+'/'+session.token

    async def speak(self, call_id, text, expected_speech_event_id=None):
        return (await self.rpc(self.owners[call_id],'speak',text=text,expected_speech_event_id=expected_speech_event_id))['playback_id']

    async def validate_generation(self, call_id, expected, action):
        await self.rpc(self.owners[call_id],'fence',expected_speech_event_id=expected,closing=action=='hangup')

    async def interrupt(self, call_id, expected_speech_event_id=None):
        await self.rpc(self.owners[call_id], 'interrupt', expected_speech_event_id=expected_speech_event_id)

    async def handle_module_event(self, call_id, kind, timestamp_us=0):
        owner=self.owners.get(call_id)
        if owner: await self.rpc(owner,'module',kind=kind,timestamp_us=timestamp_us)

    async def close(self, call_id, *, notify=True, expected_session_id=None):
        owner=self.owners.get(call_id)
        if owner is None or (expected_session_id is not None and owner.session.session_id != expected_session_id):return
        # Serialize closes of this generation, not unrelated calls. A second
        # delayed close must never delete a replacement owner's journal row.
        async with owner.session.rpc_lock:
            if self.owners.get(call_id) is not owner:return
            owner.session.closing=True
            try:await self.rpc(owner,'close',notify=notify)
            except (httpx.HTTPError, HTTPException, KeyError):
                if self.health.get(owner.spec['id'],{}).get('epoch') in (None, owner.epoch):
                    raise
            async with self.lock:
                if self.owners.get(call_id) is not owner:return
                await asyncio.to_thread(self.store.close,call_id)
                owner.session.terminated.set();owner.session.startup_complete.set()
                self.sessions_by_call.pop(call_id,None);self.owners.pop(call_id,None)

    def validate_event(self, event):
        owner = self.owners.get(event.call_id)
        if (owner is None or owner.spec['id'] != event.worker_id or owner.epoch != event.epoch
                or owner.session.session_id != event.session_id
                or event.payload.get('call_id') != event.call_id
                or event.payload.get('provider_session_id') != owner.session.session_id
                or event.payload.get('attempt') != owner.session.metadata.get('attempt')
                or event.url not in (owner.session.speech_webhook_url, owner.session.media_webhook_url)):
            raise HTTPException(409, 'stale or unregistered media callback')

    async def run_websocket(self, websocket, token):
        await websocket.close(code=4404,reason='connect to the assigned media process')
