#!/usr/bin/env python3
"""Bounded HTTP arrival-rate smoke load against an explicitly isolated mock API.

Requires E2E/load fixture exposing /api/v1/runtime with demo_users_enabled=true,
plus an explicit --isolated-mock confirmation. No dial/campaign/SMS mutation.
Outputs scheduled-to-completion p95/p99 including admission wait (no coordinated
omission from a closed-loop generator), client saturation and HTTP status counts.
"""
import argparse
import asyncio
import hashlib
import hmac
import ipaddress
import json
import math
import os
import time
from collections import Counter, deque
from contextlib import AsyncExitStack
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

import httpx


def percentile(values, q):
    return round(sorted(values)[max(0, math.ceil(len(values)*q)-1)]*1000, 3) if values else None


async def run(args):
    host=urlsplit(args.url).hostname
    if not args.isolated_mock or not host or not ipaddress.ip_address(host).is_loopback:
        raise SystemExit('Only an explicit isolated mock fixture on a loopback IP is accepted')
    secret=os.environ.get('LOAD_WEBHOOK_SECRET','')
    token=os.environ.get('LOAD_WEBHOOK_TOKEN','')
    if not secret or not token: raise SystemExit('Set synthetic LOAD_WEBHOOK_SECRET and LOAD_WEBHOOK_TOKEN')
    # Small independent pools avoid quadratic connection-pool scheduling at
    # hundreds of in-flight requests; the aggregate semaphore remains bounded.
    async with AsyncExitStack() as stack:
        clients=[await stack.enter_async_context(httpx.AsyncClient(base_url=args.url, timeout=10,
            trust_env=False, limits=httpx.Limits(max_connections=25,max_keepalive_connections=25)))
            for _ in range(max(1,math.ceil(args.concurrency/25)))]
        client=clients[0]
        runtime=await client.get('/api/v1/runtime')
        if not runtime.is_success or not runtime.json().get('demo_users_enabled'):
            raise SystemExit('Refusing a non-demo fixture')
        auth=await client.post('/api/v1/auth/login',json={'username':'admin','password':'12345678'})
        auth.raise_for_status(); headers={'Authorization':'Bearer '+auth.json()['access_token']}
        call_ids=json.loads(Path(args.calls_file).read_text()) if args.calls_file else []
        if call_ids and len(call_ids)<args.rate*args.seconds: raise SystemExit('Not enough distinct fixture calls')
        semaphore=asyncio.Semaphore(args.concurrency)
        counters=Counter();latency=[];waits=[];service_latency=[];active=0;peak=0;sent=0
        recent_latency=deque(maxlen=10000);recent_service=deque(maxlen=10000);recent_waits=deque(maxlen=10000)
        run_id=uuid4().hex
        started=time.perf_counter()
        def snapshot(final=False):
            elapsed=time.perf_counter()-started
            successes=sum(v for k,v in counters.items() if k.startswith('2'))
            return {'scope':'isolated mock HTTP; no media or provider calls','known_call_fixtures':bool(call_ids),
                'scenario':args.scenario,'target_rps':args.rate,'duration_sec':args.seconds,
                'planned_requests':args.rate*args.seconds,'finished_requests':len(latency),'sent_requests':sent,
                'elapsed_sec':round(elapsed,3),'successful_rps':round(successes/max(.001,elapsed),2),
                'actual_sent_rps':round(sent/max(.001,elapsed),2),'max_in_flight':peak,'status_counts':dict(counters),
                'scheduled_to_finish_p95_ms':percentile(latency if final else recent_latency,.95),'scheduled_to_finish_p99_ms':percentile(latency if final else recent_latency,.99),
                'successful_http_p95_ms':percentile(service_latency if final else recent_service,.95),'successful_http_p99_ms':percentile(service_latency if final else recent_service,.99),
                'generator_wait_p95_ms':percentile(waits if final else recent_waits,.95),'generator_queue_timeout_sec':args.max_queue_wait,
                'progress_quantiles':'latest 10000 samples; final uses all samples',
                'final':final,'passed':final and successes==args.rate*args.seconds}
        async def progress():
            while True:
                await asyncio.sleep(1)
                Path(args.output).write_text(json.dumps(snapshot(),indent=2)+'\n')
        async def request(index):
            nonlocal active,peak,sent
            scheduled=started+index/args.rate
            await asyncio.sleep(max(0,scheduled-time.perf_counter()))
            try:
                await asyncio.wait_for(semaphore.acquire(),timeout=args.max_queue_wait)
            except asyncio.TimeoutError:
                counters['generator_queue_timeout']+=1
                waits.append(time.perf_counter()-scheduled)
                latency.append(time.perf_counter()-scheduled)
                return
            waits.append(time.perf_counter()-scheduled)
            active+=1;peak=max(peak,active);sent+=1
            http_started=time.perf_counter()
            client=clients[index % len(clients)]
            try:
                if args.scenario=='webhook':
                    body=json.dumps({'call_id':call_ids[index] if call_ids else str(uuid4()),'kind':'status','payload':{'status':'completed','provider_call_id':'synthetic-unknown','attempt':1,'event_id':f'{run_id}-{index}'}}).encode()
                    stamp=str(int(time.time()))
                    signed={'x-webhook-token':token,'x-webhook-timestamp':stamp,
                        'x-webhook-signature':hmac.new(secret.encode(),stamp.encode()+b'.'+body,hashlib.sha256).hexdigest(),
                        'Content-Type':'application/json'}
                    response=await client.post('/api/v1/webhooks/telephony/status',content=body,headers=signed)
                else:
                    response=await client.get('/api/v1/calls?page=1&size=20',headers=headers)
                counters[str(response.status_code)]+=1
                if response.is_success:
                    duration=time.perf_counter()-http_started
                    service_latency.append(duration);recent_service.append(duration)
            except httpx.HTTPError as exc:
                counters[type(exc).__name__]+=1
            finally:
                active-=1;semaphore.release();latency.append(time.perf_counter()-scheduled)
                recent_latency.append(latency[-1]);recent_waits.append(waits[-1])
        monitor=asyncio.create_task(progress())
        try:
            # Only create work as arrivals occur. Never preallocate millions of
            # sleeping tasks for a 30-minute run. A bounded backlog records
            # generator drops instead of silently shifting the arrival schedule.
            pending=set()
            pending_limit=args.concurrency+max(1,math.ceil(args.rate*args.max_queue_wait))
            for i in range(args.rate*args.seconds):
                scheduled=started+i/args.rate
                await asyncio.sleep(max(0,scheduled-time.perf_counter()))
                if time.perf_counter()-scheduled > args.max_queue_wait:
                    counters['generator_schedule_missed']+=1
                    latency.append(time.perf_counter()-scheduled)
                    continue
                if len(pending)>=pending_limit:
                    counters['generator_backlog_full']+=1
                    latency.append(time.perf_counter()-scheduled)
                    continue
                task=asyncio.create_task(request(i));pending.add(task)
                task.add_done_callback(pending.discard)
            if pending: await asyncio.gather(*pending)
        finally:
            monitor.cancel()
            try: await monitor
            except asyncio.CancelledError: pass
        result=snapshot(final=True)
        Path(args.output).write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps(result,indent=2))
        if not result['passed']: raise SystemExit(1)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--url',required=True);p.add_argument('--isolated-mock',action='store_true')
    p.add_argument('--scenario',choices=['read','webhook'],default='webhook')
    p.add_argument('--rate',type=int,default=50);p.add_argument('--seconds',type=int,default=20)
    p.add_argument('--calls-file',help='JSON array of distinct isolated synthetic call IDs')
    p.add_argument('--max-queue-wait',type=float,default=5)
    p.add_argument('--concurrency',type=int,default=100);p.add_argument('--output',required=True)
    args=p.parse_args()
    if not 1<=args.rate<=1000 or not 1<=args.seconds<=3600 or not 1<=args.concurrency<=500 or not .1<=args.max_queue_wait<=30:
        p.error('bounds: rate 1..1000, seconds 1..3600, concurrency 1..500')
    asyncio.run(run(args))
