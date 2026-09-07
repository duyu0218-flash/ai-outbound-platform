#!/usr/bin/env python3
"""Four real Pipecat worker processes, synthetic control sessions, NO SIP/audio.
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


async def run(output):
    with tempfile.TemporaryDirectory(prefix='node200-media-') as directory:
        work=Path(directory)
        specs=[{'id':f'media-{i}','endpoint':f'http://127.0.0.1:{18660+i}',
                'ws_base':f'ws://127.0.0.1:{18660+i}/v1/pipecat/media','capacity':50} for i in range(1,5)]
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
            MEDIA_RPC_TOKEN=token,MEDIA_WORKERS_JSON='[]',MEDIA_WORKER_CAPACITY='50',
            PYTHONPATH=str(ROOT/'voice_gateway'))
        processes=[];logs=[]
        def launch(i):
            log=(work/f'worker-{i}.log').open('a');logs.append(log)
            process=subprocess.Popen([sys.executable,'-m','uvicorn','app.media_worker:app',
                '--host','127.0.0.1','--port',str(18660+i),'--no-access-log'],cwd=ROOT/'voice_gateway',
                env=dict(env,MEDIA_WORKER_ID=f'media-{i}'),stdout=log,stderr=subprocess.STDOUT)
            return process
        cfg=Settings(_env_file=None,media_workers_json=json.dumps(specs),media_rpc_token=token,
            voice_security_db_path=str(work/'controller.db'),pipecat_max_active_sessions=200)
        manager=RemoteMediaManager(cfg)
        try:
            processes=[launch(i) for i in range(1,5)]
            await manager.start()
            for _ in range(100):
                await manager.refresh()
                if manager.ready():break
                if any(p.poll() is not None for p in processes):
                    raise RuntimeError('\n'.join(p.read_text()[-4000:] for p in work.glob('worker-*.log')))
                await asyncio.sleep(.1)
            assert manager.ready(),'media processes did not become ready'
            started=time.monotonic()
            async def create(i):return await manager.create_session(call_id=str(i),speech_webhook_url=env['VOICE_CALLBACK_BASE_URL']+'/api/v1/webhooks/telephony/speech',
                media_webhook_url=env['VOICE_CALLBACK_BASE_URL']+'/api/v1/webhooks/telephony/media',metadata={'attempt':1})
            await asyncio.gather(*(create(i) for i in range(200)))
            elapsed=time.monotonic()-started
            await manager.refresh()
            distribution={key:len(value['sessions']) for key,value in manager.health.items()}
            assert list(distribution.values())==[50]*4
            rejected=False
            try:await create(200)
            except RuntimeError:rejected=True
            assert rejected
            original={cid:(o.spec['id'],o.epoch,o.session.session_id) for cid,o in manager.owners.items()}
            await manager.stop()
            manager=RemoteMediaManager(cfg);await manager.start()
            assert {cid:(o.spec['id'],o.epoch,o.session.session_id) for cid,o in manager.owners.items()}==original
            processes[0].kill();processes[0].wait(timeout=10)
            processes[0]=launch(1)
            old_epoch=original['0'][1]
            for _ in range(100):
                await manager.refresh()
                state=manager.health.get('media-1',{})
                if state.get('ready') and state.get('epoch')!=old_epoch:break
                await asyncio.sleep(.1)
            affected=[cid for cid,o in manager.owners.items() if o.session.terminated.is_set()]
            assert len(affected)==50
            await asyncio.gather(*(manager.close(cid,notify=False) for cid in affected))
            await asyncio.gather(*(create(200+i) for i in range(50)))
            await manager.refresh()
            assert all(len(v['sessions'])==50 for v in manager.health.values())
            result={'media_processes':4,'synthetic_control_sessions':200,'distribution':distribution,
                'create_200_seconds':elapsed,'201st_rejected':rejected,'journal_recovery_preserved':True,
                'worker_restart_affected_sessions':len(affected),'replacement_sessions':50,
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
    args=parser.parse_args()
    asyncio.run(run(args.output))
