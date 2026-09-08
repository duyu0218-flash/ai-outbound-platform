#!/usr/bin/env python3
"""Configurable real Pipecat worker processes, synthetic control sessions, NO SIP/audio.
Run with the gateway Python environment. All credentials are public test values.
"""
import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'voice_gateway'))
from app.config import Settings
from app.media_cluster import RemoteMediaManager


async def run(output, *, workers=4, calls=200, worker_capacity=50):
    if not 1 <= workers <= 16 or not 1 <= worker_capacity <= 200 or not 1 <= calls <= workers*worker_capacity:
        raise ValueError("invalid media workload")
    resource_limit=workers*worker_capacity
    with tempfile.TemporaryDirectory(prefix='node200-media-') as directory:
        work=Path(directory)
        specs=[{'id':f'media-{i}','endpoint':f'http://127.0.0.1:{18660+i}',
                'ws_base':f'ws://127.0.0.1:{18660+i}/v1/pipecat/media','capacity':worker_capacity} for i in range(1,workers+1)]
        token='synthetic-media-process-test-'+'m'*32
        env=dict(os.environ,ENV='test',VOICE_GATEWAY_DRIVER='freeswitch_esl',VOICE_AI_PIPELINE='pipecat',
            SERVICE_TOKEN='s'*40,VOICE_COMMAND_SECRET='c'*40,VOICE_SECURITY_ADMIN_TOKEN='a'*40,
            WEBHOOK_TOKEN='t'*40,WEBHOOK_SECRET='h'*40,FREESWITCH_ESL_PASSWORD='e'*40,
            VOICE_SECURITY_DB_PATH=str(work/'unused-worker-ledger.db'),VOICE_SECURITY_ROUTES_JSON='{}',
            VOICE_SECURITY_ROUTES_FILE='',VOICE_CALLBACK_BASE_URL='http://127.0.0.1:18660',
            VOICE_CALLBACK_ALLOW_PRIVATE_HTTP='true',FREESWITCH_GATEWAY='synthetic-carrier',
            FREESWITCH_TTS_ENGINE='flite',FREESWITCH_TTS_VOICE='slt',PIPECAT_MEDIA_PROTOCOL='voismart',
            PIPECAT_MEDIA_WS_BASE='ws://127.0.0.1:18661/v1/pipecat/media',
            PIPECAT_VERSION='1.8.1+outbound.1',PIPECAT_OPENAI_API_KEY='synthetic-no-network-key',
            MEDIA_RPC_TOKEN=token,MEDIA_WORKERS_JSON='[]',MEDIA_WORKER_CAPACITY=str(worker_capacity),
            PYTHONPATH=str(ROOT/'voice_gateway'))
        processes=[];logs=[]
        def launch(i):
            log=(work/f'worker-{i}.log').open('a');logs.append(log)
            process=subprocess.Popen([sys.executable,'-m','uvicorn','app.media_worker:app',
                '--host','127.0.0.1','--port',str(18660+i),'--no-access-log'],cwd=ROOT/'voice_gateway',
                env=dict(env,MEDIA_WORKER_ID=f'media-{i}'),stdout=log,stderr=subprocess.STDOUT)
            return process
        cfg=Settings(_env_file=None,media_workers_json=json.dumps(specs),media_rpc_token=token,
            voice_security_db_path=str(work/'controller.db'),pipecat_max_active_sessions=resource_limit,media_allow_degraded_admission=True)
        manager=RemoteMediaManager(cfg)
        try:
            processes=[launch(i) for i in range(1,workers+1)]
            await manager.start()
            for _ in range(300):
                await manager.refresh()
                if manager.ready():break
                if any(p.poll() is not None for p in processes):
                    raise RuntimeError('\n'.join(p.read_text()[-4000:] for p in work.glob('worker-*.log')))
                await asyncio.sleep(.1)
            assert manager.ready(),'media processes did not become ready'
            started=time.monotonic()
            async def create(i):return await manager.create_session(call_id=str(i),speech_webhook_url=env['VOICE_CALLBACK_BASE_URL']+'/api/v1/webhooks/telephony/speech',
                media_webhook_url=env['VOICE_CALLBACK_BASE_URL']+'/api/v1/webhooks/telephony/media',metadata={'attempt':1})
            await asyncio.gather(*(create(i) for i in range(calls)))
            elapsed=time.monotonic()-started
            await manager.refresh()
            distribution={key:len(value['sessions']) for key,value in manager.health.items()}
            assert sum(distribution.values())==calls and max(distribution.values())-min(distribution.values())<=1
            await asyncio.gather(*(create(i) for i in range(calls,resource_limit)))
            rejected=False
            try:await create(resource_limit)
            except RuntimeError:rejected=True
            assert rejected
            await asyncio.gather(*(manager.close(str(i),notify=False) for i in range(calls,resource_limit)))
            original={cid:(o.spec['id'],o.epoch,o.session.session_id) for cid,o in manager.owners.items()}
            await manager.stop()
            manager=RemoteMediaManager(cfg);await manager.start()
            assert {cid:(o.spec['id'],o.epoch,o.session.session_id) for cid,o in manager.owners.items()}==original
            processes[0].kill();processes[0].wait(timeout=10)
            processes[0]=launch(1)
            old_epoch=original['0'][1]
            for _ in range(300):
                await manager.refresh()
                state=manager.health.get('media-1',{})
                if state.get('ready') and state.get('epoch')!=old_epoch:break
                await asyncio.sleep(.1)
            affected=[cid for cid,o in manager.owners.items() if o.session.terminated.is_set()]
            assert len(affected)==distribution['media-1']
            await asyncio.gather(*(manager.close(cid,notify=False) for cid in affected))
            await asyncio.gather(*(create(resource_limit+i) for i in range(len(affected))))
            await manager.refresh()
            assert sum(len(v['sessions']) for v in manager.health.values())==calls
            result={'media_processes':workers,'synthetic_control_sessions':calls,'distribution':distribution,
                'resource_session_limit':resource_limit,'create_seconds':elapsed,'over_resource_limit_rejected':rejected,'journal_recovery_preserved':True,
                'worker_restart_affected_sessions':len(affected),'replacement_sessions':len(affected),
                'real_sip_audio_asr_tts':False,'passed':True}
            output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
        finally:
            if manager.client:
                await asyncio.gather(*(manager.close(cid,notify=False) for cid in list(manager.owners)),return_exceptions=True)
                await manager.stop()
            for process in processes:
                if process.poll() is None:process.terminate()
            for process in processes:
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait()
            for log in logs:log.close()


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--workers',type=int,default=4)
    parser.add_argument('--calls',type=int,default=200)
    parser.add_argument('--worker-capacity',type=int,default=50)
    args=parser.parse_args()
    asyncio.run(run(args.output,workers=args.workers,calls=args.calls,worker_capacity=args.worker_capacity))
