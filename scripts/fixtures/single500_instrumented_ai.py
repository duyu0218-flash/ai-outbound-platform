"""Isolated AI work-pool timing; executes the production worker unchanged."""
import asyncio
import inspect
import json
import os
import time
from collections import defaultdict, deque
from pathlib import Path
from app.services.async_ai import WorkPool
from app.ai_worker import serve

assert os.environ.get('SINGLE500_ISOLATED_MOCK') == 'true'
measurements = defaultdict(lambda: deque(maxlen=10000))
original = WorkPool.run


async def measured(self, function, *args):
    begin = time.monotonic()
    async def invoke(*values):
        executing = time.monotonic()
        measurements[function.__name__ + '.queue_ms'].append((executing-begin)*1000)
        try:
            value = function(*values)
            return await value if inspect.isawaitable(value) else value
        finally:
            measurements[function.__name__ + '.execute_ms'].append((time.monotonic()-executing)*1000)
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
        await asyncio.sleep(1)


async def main():
    task = asyncio.create_task(monitor())
    try: await serve()
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


asyncio.run(main())
