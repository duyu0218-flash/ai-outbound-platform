"""Isolated AI work-pool timing; executes the production worker unchanged."""
import asyncio
import inspect
import json
import os
import time
import threading
from contextlib import asynccontextmanager
from collections import defaultdict, deque
from pathlib import Path
from app.services.async_ai import WorkPool
from app.ai_worker import serve
from app.services import async_ai, dispatcher
from app.services.ai_claim_state import current_claim

assert os.environ.get('SINGLE500_ISOLATED_MOCK') == 'true'
measurements = defaultdict(lambda: deque(maxlen=10000))
original = WorkPool.run
event_file=(Path(os.environ['LOAD_ARTIFACT_DIR'])/f'ai-events-{os.getpid()}.jsonl').open('a',buffering=65536)
event_lock=threading.Lock()


def event(kind, **fields):
    claim=current_claim.get()
    row=dict(event=kind,at=time.monotonic(),claim=str(claim[0]) if claim else None,**fields)
    with event_lock:event_file.write(json.dumps(row,separators=(',',':'))+'\n')


original_claim=async_ai.process_ai_claim
async def process_claim(task_id, claim, pool, action_pool):
    payload=json.loads(claim[2])
    event('claim_started',task_id=str(task_id),claimed_at=claim[3],
        call_id=payload.get('call_id'),attempt=payload.get('attempt'),sequence=payload.get('turn_sequence'),
        speech_event_id=payload.get('speech_event_id'))
    try:return await original_claim(task_id,claim,pool,action_pool)
    finally:event('claim_finished',task_id=str(task_id),call_id=payload.get('call_id'))


original_lock=dispatcher._ai_turn_lock
@asynccontextmanager
async def turn_lock(call_id):
    event('call_lock_wait',call_id=call_id)
    async with original_lock(call_id):
        event('call_lock_acquired',call_id=call_id)
        try:yield
        finally:event('call_lock_releasing',call_id=call_id)
    event('call_lock_released',call_id=call_id)


async_ai.process_ai_claim=process_claim
dispatcher._ai_turn_lock=turn_lock


async def measured(self, function, *args):
    begin = time.monotonic()
    async def invoke(*values):
        executing = time.monotonic()
        measurements[function.__name__ + '.queue_ms'].append((executing-begin)*1000)
        try:
            value = function(*values)
            return await value if inspect.isawaitable(value) else value
        finally:
            ended=time.monotonic()
            measurements[function.__name__ + '.execute_ms'].append((ended-executing)*1000)
            event('pool',unit=function.__name__,queued=begin,executing=executing,ended=ended)
    return await original(self, invoke, *args)


WorkPool.run = measured


async def monitor():
    target = Path(os.environ['LOAD_ARTIFACT_DIR']) / f'ai-stages-{os.getpid()}.json'
    while True:
        data = {}
        for key, samples in list(measurements.items()):
            values = sorted(samples)
            if values:
                data[key] = dict(count=len(values), p99=values[min(len(values)-1,int(len(values)*.99))], max=max(values))
        temporary = target.with_suffix('.tmp')
        temporary.write_text(json.dumps(data)); temporary.replace(target)
        with event_lock:event_file.flush()
        await asyncio.sleep(1)


async def main():
    task = asyncio.create_task(monitor())
    try: await serve()
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        with event_lock:event_file.flush()


asyncio.run(main())
