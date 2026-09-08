"""Isolated test instrumentation; never shipped as application code."""
import asyncio, json, os, resource, time, sys
from collections import Counter, defaultdict, deque
from sqlalchemy import event
from app.db import engine
from pathlib import Path
from app.main import app as inner
from app.services.runtime_metrics import snapshot
assert os.environ['ENV'] == 'test'
assert '/node200single500' in os.environ['DATABASE_URL']
counts = Counter()
sql_counts = Counter()
sql_seconds = Counter()
http_seconds = defaultdict(lambda: deque(maxlen=8192))
@event.listens_for(engine, 'before_cursor_execute')
def sql_start(conn, cursor, statement, parameters, context, executemany):
    context.load_started = time.perf_counter()

@event.listens_for(engine, 'after_cursor_execute')
def sql_end(conn, cursor, statement, parameters, context, executemany):
    kind = statement.split(None, 1)[0].upper()
    sql_counts[kind] += 1
    sql_seconds[kind] += time.perf_counter() - context.load_started

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
                        max_rss_bytes=usage.ru_maxrss * (1 if sys.platform == 'darwin' else 1024), responses=dict(counts),
                        sql_counts=dict(sql_counts), sql_seconds=dict(sql_seconds),
                        http_ms={k:{'count':len(v),'mean':sum(v)/len(v)*1000,
                                    'p99':sorted(v)[min(len(v)-1,int(len(v)*.99))]*1000}
                                 for k,v in http_seconds.items() if v})
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
    started = time.perf_counter()
    async def response(message):
        if message['type'] == 'http.response.start':
            message = dict(message)
            message['headers'] = list(message['headers']) + [(b'x-load-pid', str(os.getpid()).encode())]
            if scope['path'].startswith('/api/v1/webhooks/'):
                counts[str(message['status'])] += 1
                http_seconds[scope['path'].rsplit('/',1)[-1]].append(time.perf_counter()-started)
        await send(message)
    await inner(scope, receive, response)
