import asyncio
import json
from types import SimpleNamespace

import httpx
import pytest
from fastapi import HTTPException
from app.config import Settings
from app.media_cluster import RemoteMediaManager, worker_specs
from app import media_worker as worker


def settings_for(tmp_path, capacity=200):
    return Settings(_env_file=None, media_workers_json=json.dumps([
        {'id':f'media-{i}', 'endpoint':f'http://127.0.0.1:{8100+i}',
         'ws_base':f'ws://127.0.0.1:{8100+i}/v1/pipecat/media', 'capacity':50} for i in range(1,5)]),
        media_rpc_token='synthetic-media-'+'m'*32,voice_security_db_path=str(tmp_path/'ledger.db'),
        pipecat_max_active_sessions=capacity)


def test_roster_rejects_remote_duplicate_and_underprovisioned(tmp_path):
    cfg=settings_for(tmp_path)
    assert len(worker_specs(cfg))==4
    for rows in ([{'id':'x','endpoint':'http://remote:8101','ws_base':'ws://remote:8101/v1/pipecat/media','capacity':200}],
                 json.loads(cfg.media_workers_json)[:3],
                 [json.loads(cfg.media_workers_json)[0]]*4):
        with pytest.raises(ValueError):worker_specs(cfg.model_copy(update={'media_workers_json':json.dumps(rows)}))


def test_200_owners_recovery_and_stale_epoch(tmp_path):
    async def run():
        cfg=settings_for(tmp_path)
        states={f'media-{i}':{'worker_id':f'media-{i}','epoch':f'epoch-{i}', 'ready':True,'capacity':50,'sessions':{}} for i in range(1,5)}
        async def transport(request):
            row=states[f'media-{request.url.port-8100}']
            if request.method=='GET':return httpx.Response(200,json=row)
            data=json.loads(request.content)
            if data['epoch']!=row['epoch']:return httpx.Response(409)
            if data['action']=='create':row['sessions'][data['call_id']]={'session_id':data['session_id'],'started':True}
            if data['action']=='close':row['sessions'].pop(data['call_id'],None)
            return httpx.Response(200,json={'ok':True})
        # Actual ownership journal, synthetic worker HTTP responses.
        manager=RemoteMediaManager(cfg)
        manager.client=httpx.AsyncClient(transport=httpx.MockTransport(transport))
        manager.store.initialize();await manager.refresh()
        try:
            await asyncio.gather(*(manager.create_session(call_id=str(i),speech_webhook_url='http://control/speech',
                media_webhook_url='http://control/media',metadata={'attempt':1}) for i in range(200)))
            assert [len(s['sessions']) for s in states.values()]==[50]*4
            with pytest.raises(RuntimeError,match='host media capacity'):
                await manager.create_session(call_id='201',speech_webhook_url='',media_webhook_url='',metadata={})
            owner=manager.owners['0']
            manager.validate_event(SimpleNamespace(call_id='0',worker_id=owner.spec['id'],epoch=owner.epoch,
                session_id=owner.session.session_id,url='http://control/speech',payload={'call_id':'0','attempt':1,'provider_session_id':owner.session.session_id}))
            with pytest.raises(HTTPException):
                manager.validate_event(SimpleNamespace(call_id='0',worker_id=owner.spec['id'],epoch='stale',
                    session_id=owner.session.session_id,url='http://control/speech',payload={'call_id':'0','attempt':1,'provider_session_id':owner.session.session_id}))
            assert len(manager.store.initialize())==200
            states[owner.spec['id']]['epoch']='restarted'
            await manager.refresh()
            assert owner.session.terminated.is_set()
            assert not manager.owners['1'].session.terminated.is_set()
            await manager.close('0',notify=False)
            assert len(manager.store.initialize())==199
        finally:await manager.client.aclose()
    asyncio.run(run())


def test_worker_fences_identity_attempt_and_speech(monkeypatch):
    async def run():
        monkeypatch.setattr(worker,'registered',{})
        monkeypatch.setattr(worker,'closed',__import__('collections').OrderedDict())
        worker.manager.sessions_by_call.clear();worker.manager.sessions_by_token.clear()
        data={'call_id':'call','session_id':'session','token':'t'*40,'speech_webhook_url':'http://control/speech',
              'media_webhook_url':'http://control/media','metadata':{'attempt':2}}
        base=dict(epoch=worker.epoch,call_id='call',session_id='session',attempt=2)
        await worker.command(worker.Command(action='create',session=data,**base))
        worker.registered['call'].latest_final_event_id='new'
        for changed in ({'epoch':'stale'}, {'attempt':1},{'session_id':'another'},{'expected_speech_event_id':'old'}):
            with pytest.raises(HTTPException) as caught:
                await worker.command(worker.Command(action='fence',**{**base,**changed}))
            assert caught.value.status_code==409
        await worker.command(worker.Command(action='fence',closing=True,expected_speech_event_id='new',**base))
        assert worker.registered['call'].closing
        await worker.command(worker.Command(action='close',notify=False,**base))
        await worker.command(worker.Command(action='close',notify=False,**base))
        assert not worker.registered
        with pytest.raises(HTTPException):await worker.command(worker.Command(action='create',session=data,**base))
    asyncio.run(run())


def test_worker_speak_retry_is_idempotent_and_has_deadline(monkeypatch):
    async def run():
        from app.pipecat_pipeline import PipecatCallSession
        session=PipecatCallSession(call_id='c',session_id='s',token='t',speech_webhook_url='',media_webhook_url='',metadata={'attempt':1})
        session.latest_final_event_id='final'
        monkeypatch.setattr(worker,'registered',{'c':session})
        calls=[]
        async def speak(call_id,text):calls.append(text);return 'playback'
        monkeypatch.setattr(worker.manager,'speak',speak)
        base=dict(action='speak',epoch=worker.epoch,call_id='c',session_id='s',attempt=1,
                  text='hello',operation_id='op',expected_speech_event_id='final')
        first,second=await asyncio.gather(*(worker.command(worker.Command(**base)) for _ in range(2)))
        assert first==second and calls==['hello']
        for changed in ({'text':'changed'},{'expires_at':1}):
            with pytest.raises(HTTPException):await worker.command(worker.Command(**{**base,**changed}))
        session.latest_final_event_id='new'
        with pytest.raises(HTTPException):await worker.command(worker.Command(**base))
    asyncio.run(run())


def test_close_before_create_fences_delayed_generation(monkeypatch):
    async def run():
        monkeypatch.setattr(worker,'registered',{})
        monkeypatch.setattr(worker,'closed',__import__('collections').OrderedDict())
        base=dict(epoch=worker.epoch,call_id='late',session_id='late-session',attempt=1)
        assert (await worker.command(worker.Command(action='close',**base)))['closed']
        with pytest.raises(HTTPException) as caught:
            await worker.command(worker.Command(action='create',session={'call_id':'late',
                'session_id':'late-session','metadata':{'attempt':1}},**base))
        assert caught.value.status_code==409
    asyncio.run(run())


def test_retry_and_concurrent_close_cannot_reuse_or_delete_wrong_owner(tmp_path,monkeypatch):
    async def run():
        manager=RemoteMediaManager(settings_for(tmp_path));manager.store.initialize()
        manager.health={spec['id']:{'ready':True,'epoch':spec['id']} for spec in manager.specs}
        manager.health_checked_at={spec['id']:__import__('time').monotonic() for spec in manager.specs}
        entered=asyncio.Event();release=asyncio.Event();close_count=0
        async def rpc(owner,action,**kwargs):
            nonlocal close_count
            if action=='close':
                close_count+=1;entered.set();await release.wait()
            return {}
        monkeypatch.setattr(manager,'rpc',rpc)
        args=dict(call_id='retry',speech_webhook_url='http://control/speech',media_webhook_url='http://control/media')
        old=await manager.create_session(**args,metadata={'attempt':1})
        with pytest.raises(RuntimeError,match='previous media generation'):
            await manager.create_session(**args,metadata={'attempt':2})
        first=asyncio.create_task(manager.close('retry',notify=False))
        await entered.wait()
        second=asyncio.create_task(manager.close('retry',notify=False))
        with pytest.raises(RuntimeError):await manager.create_session(**args,metadata={'attempt':1})
        release.set();await asyncio.gather(first,second)
        assert close_count==1
        new=await manager.create_session(**args,metadata={'attempt':2})
        assert new.session_id!=old.session_id
        await manager.close('retry',notify=False,expected_session_id=old.session_id)
        saved=manager.store.initialize()
        assert len(saved)==1 and json.loads(saved[0]['session_json'])['session_id']==new.session_id
    asyncio.run(run())


def test_late_media_callback_cannot_borrow_replacement_session(monkeypatch):
    async def run():
        monkeypatch.setattr(worker,'registered',{'call':SimpleNamespace(session_id='new-session')})
        with pytest.raises(RuntimeError,match='matching registered generation'):
            await worker.post_event('http://control/media',{'call_id':'call','provider_session_id':'old-session','attempt':1})
    asyncio.run(run())


def test_reconciliation_uses_tenant_and_attempt_and_guards_hangup(tmp_path,monkeypatch):
    from test_security import gateway
    async def run():
        secured,_=gateway(tmp_path)
        ended=asyncio.Event();ended.set()
        media=SimpleNamespace(call_id='call',metadata={'tenant_id':7,'attempt':2},
            terminated=ended,media_error_code='MEDIA_WORKER_UNAVAILABLE')
        secured.driver.pipecat_manager=SimpleNamespace(owners={'call':SimpleNamespace(session=media)})
        lookups=[];commands=[]
        def lookup(call_id,tenant,attempt):
            lookups.append((call_id,tenant,attempt))
            return {'state':'active','tenant':tenant,'attempt':attempt,'uuid':'exact-pbx-uuid'}
        async def post(action,payload):commands.append((action,payload))
        monkeypatch.setattr(secured.ledger,'lookup',lookup);monkeypatch.setattr(secured,'post',post)
        await secured._reconcile_media_once()
        assert lookups==[('call',7,2)]
        assert commands==[('hangup',{'call_id':'call','tenant_id':7,'expected_attempt':2,'provider_call_id':'exact-pbx-uuid'})]
    asyncio.run(run())


def test_command_binding_cannot_change_after_security_validation(tmp_path):
    from test_security import gateway,request
    async def run():
        secured,_=gateway(tmp_path);payload=request()
        await secured.post('dial',payload)
        binding=secured.driver.calls_by_id[payload['call_id']]
        with pytest.raises(HTTPException) as caught:
            await secured.driver.post('speak',{'call_id':payload['call_id'],'text':'old',
                'expected_attempt':binding.metadata['attempt']+1,'provider_call_id':binding.fs_uuid})
        assert caught.value.status_code==409
    asyncio.run(run())
