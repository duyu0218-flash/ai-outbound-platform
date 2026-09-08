"""Isolated test instrumentation; never shipped as application code."""
import asyncio, json, os, resource, time
from collections import Counter
from pathlib import Path
from app.main import app as inner
from app.services.runtime_metrics import snapshot
assert os.environ['ENV'] == 'test'
assert '/node200single500' in os.environ['DATABASE_URL']
counts = Counter()
async def monitor():
    target = Path(os.environ['LOAD_ARTIFACT_DIR']) / f"runtime-{os.getpid()}.jsonl"
    with target.open('a') as output:
        while True:
            data = snapshot()
            # Keep bounded raw sample windows for quantiles, not huge per-sample copies.
            for key in ('db_wait_seconds',):
                values = sorted(data.pop(key))
                data[key + '_p99'] = values[max(0, int(len(values)*.99)-1)] if values else 0
            data['admission_wait_p99'] = {}
            for bucket, values in data.pop('admission_wait_seconds').items():
                values = sorted(values)
                data['admission_wait_p99'][bucket] = values[max(0, int(len(values)*.99)-1)] if values else 0
            usage = resource.getrusage(resource.RUSAGE_SELF)
            data.update(pid=os.getpid(), time=time.time(), cpu_seconds=usage.ru_utime+usage.ru_stime,
                        max_rss_bytes=usage.ru_maxrss, responses=dict(counts))
            output.write(json.dumps(data)+'\n'); output.flush()
            await asyncio.sleep(.2)

async def app(scope, receive, send):
    if scope['type'] == 'lifespan':
        task = None
        async def lifecycle(message):
            nonlocal task
            if message['type'] == 'lifespan.startup.complete':
                task = asyncio.create_task(monitor())
            if message['type'] == 'lifespan.shutdown.complete' and task:
                task.cancel()
                try: await task
                except asyncio.CancelledError: pass
            await send(message)
        return await inner(scope, receive, lifecycle)
    async def response(message):
        if message['type'] == 'http.response.start':
            message = dict(message)
            message['headers'] = list(message['headers']) + [(b'x-load-pid', str(os.getpid()).encode())]
            if scope['path'].startswith('/api/v1/webhooks/'):
                counts[str(message['status'])] += 1
        await send(message)
    await inner(scope, receive, response)
