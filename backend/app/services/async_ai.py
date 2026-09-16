"""Bounded async AI lane with short, thread-owned database work units."""
import asyncio
import inspect
import json
import logging
import threading
import time
from pathlib import Path
from collections import OrderedDict, defaultdict, deque, Counter
from concurrent.futures import ThreadPoolExecutor
from contextvars import copy_context
from datetime import timedelta
from uuid import UUID, uuid4

from ..clock import utc_now
from ..config import get_settings
from ..db import session_scope
from ..models import TaskState
from .leases import monitored_lease, assert_execution_permitted, ExecutionLease, _leases
from .worker_runtime import WorkerRuntime, _resources, http_client
from .work_scheduling import WorkGate, work_priority, Histogram
from . import db_work_observation
from .task_queue import claim_ready_tasks, _renew_task, _owned_task, _record_dead_task

logger = logging.getLogger(__name__)
settings = get_settings()


class WorkPool:
    """Cancellation drains an accepted work unit before releasing its lease.

    Threads own their ORM session and event loop. Shared execution lease objects
    let loss/cancellation prohibit subsequent external actions in that thread.
    """
    def __init__(self, size, name='ai-db', queue_limit=512):
        self.size=size
        self.executor=ThreadPoolExecutor(max_workers=size, thread_name_prefix=name)
        self.gate=WorkGate(size, queue_limit)
        self.histograms=defaultdict(Histogram)
        self.db_counts=defaultdict(Counter)
        self.queue_samples=deque(maxlen=2048)
        self.local=threading.local()
        self.runtimes=[]
        self.timings = defaultdict(lambda: deque(maxlen=2048))
        self.timing_lock = threading.Lock()

    def record_timing(self, name, milliseconds):
        with self.timing_lock:
            self.timings[name].append(milliseconds)
            self.histograms[name].observe(milliseconds)
            if name.endswith('.queue'):
                self.queue_samples.append((time.monotonic(), milliseconds))

    def timing_snapshot(self):
        with self.timing_lock:
            copied = {name: sorted(values) for name, values in self.timings.items()}
        return {name: {'sample_count':len(values), 'p99_ms':values[min(len(values)-1,int(len(values)*.99))],
                       'max_ms':values[-1]} for name,values in copied.items() if values}

    def pressure_snapshot(self):
        with self.timing_lock:
            values=sorted(value for at,value in self.queue_samples if time.monotonic()-at <= 10)
            histograms={name:h.snapshot() for name,h in self.histograms.items()}
            db_counts={name:dict(counts) for name,counts in self.db_counts.items()}
        return {**self.gate.snapshot(), 'queue_p95_ms': values[min(len(values)-1,int(len(values)*.95))] if values else 0,
                'histograms':histograms, 'db_counts':db_counts}

    async def run(self, function, *args):
        queued_at = time.monotonic()
        await self.gate.acquire(work_priority(function.__name__))
        try:
            context=copy_context()
            cancelled=ExecutionLease(float('inf'))
            context.run(_leases.set, (*context.get(_leases, ()), cancelled))
            def execute():
                observation=dict(sql_ms=0.,sql_count=0,flush_commit_ms=0.,commit_count=0,rollback_count=0)
                context.run(db_work_observation.current.set, observation)
                executing_at = time.monotonic()
                self.record_timing(function.__name__ + '.queue', (executing_at - queued_at) * 1000)
                if not hasattr(self.local,'runtime'):
                    self.local.runtime=WorkerRuntime()
                    self.runtimes.append(self.local.runtime)
                runtime=self.local.runtime
                context.run(_resources.set, runtime.resources)
                async def invoke():
                    assert_execution_permitted()
                    value=function(*args)
                    return await value if inspect.isawaitable(value) else value
                try:
                    return runtime.runner.run(invoke(), context=context)
                finally:
                    self.record_timing(function.__name__ + '.execute', (time.monotonic() - executing_at) * 1000)
                    for stage in ('sql_ms','flush_commit_ms'):
                        self.record_timing(function.__name__ + '.' + stage, observation[stage])
                    with self.timing_lock:
                        self.db_counts[function.__name__].update({k:v for k,v in observation.items() if k.endswith('_count')})
                    from .ai_claim_state import current_claim
                    claim=context.get(current_claim)
                    logger.debug('db work task=%s operation=%s observations=%s',
                        claim[0] if claim else None, function.__name__, observation)
            future=asyncio.get_running_loop().run_in_executor(self.executor, execute)
            try:
                return await asyncio.shield(future)
            except asyncio.CancelledError:
                cancelled.lost=True
                # A Python thread cannot be cancelled. Never release the lease
                # while an accepted work unit could still perform an action.
                while not future.done():
                    try:await asyncio.shield(future)
                    except asyncio.CancelledError:continue
                    except Exception:break
                if future.done() and not future.cancelled():future.exception()
                raise
        finally:
            self.gate.release()

    async def close(self):
        self.executor.shutdown(wait=True)
        for runtime in self.runtimes:
            await asyncio.to_thread(runtime.close)

    async def prepare_http(self, **options):
        # Create each thread's loop and HTTP/TLS pool before advertising ready.
        # No network request or business action occurs during this preparation.
        barrier=threading.Barrier(self.size)
        async def prepare():
            barrier.wait(timeout=15)
            async with http_client(**options):
                pass
        await asyncio.gather(*(self.run(prepare) for _ in range(self.size)))


def complete(task_id, token, error=None):
    assert_execution_permitted()
    with session_scope() as session:
        task=_owned_task(session,task_id,token)
        if task is None:return False
        if error is None:
            task.state=TaskState.COMPLETED
            task.last_error=''
        else:
            task.state=TaskState.DEAD if task.attempts>=task.max_attempts else TaskState.FAILED
            task.available_at=utc_now()+timedelta(seconds=min(300,2**task.attempts))
            task.last_error=type(error).__name__
            if task.state==TaskState.DEAD:_record_dead_task(session,task)
        task.locked_at=task.lease_token=None
        task.updated_at=utc_now()
        session.add(task);session.commit()
        return error is None


async def process_ai_claim(task_id, claim, pool, action_pool):
    from .dispatcher import run_ai_turn_async
    token, task_type, raw, started=claim
    ttl=max(2,settings.task_lease_sec)
    from .ai_claim_state import current_claim
    claim_context = current_claim.set((task_id, token))
    try:
        async def renew():return await pool.run(_renew_task,task_id,token)
        async with monitored_lease(renew,ttl=ttl,initial_until=started+ttl):
            payload=json.loads(raw)
            if task_type!='ai_turn' or type(payload.get('attempt')) is not int:
                raise ValueError('AI claim requires an attempt')
            await asyncio.wait_for(run_ai_turn_async(pool=pool, action_pool=action_pool,
                call_id=UUID(payload['call_id']),transcript=str(payload.get('transcript') or ''),
                expected_attempt=payload['attempt'],expected_turn_sequence=payload.get('turn_sequence'),
                expected_speech_event_id=payload.get('speech_event_id')),
                timeout=max(1,settings.task_timeout_sec))
            return await pool.run(complete,task_id,token)
    except asyncio.CancelledError:raise
    except Exception as exc:
        logger.warning('async AI task failed id=%s error_type=%s',task_id,type(exc).__name__)
        # Ownership check prevents a stale executor from overwriting recovery.
        return await pool.run(complete,task_id,token,exc)
    finally:
        current_claim.reset(claim_context)


async def run_async_ai_lane(stop_event, *, concurrency):
    pool=WorkPool(settings.ai_db_threads, queue_limit=concurrency*3+16)
    actions=None  # Network actions run as bounded coroutines in the AI claim lane.
    pending=set()
    worker_identity=uuid4().hex
    last_health=0.0
    last_claim=0.0
    last_poll=0.0
    health_path=Path(settings.ai_worker_health_path)
    resources=OrderedDict()
    resource_token=_resources.set(resources)
    try:
        from .callback_inbox import prepare_handlers
        prepare_handlers()
        async with http_client(timeout=settings.telephony_timeout_sec,
                               follow_redirects=False, trust_env=False):
            pass
        async with http_client(max_connections=max(100,settings.task_ai_concurrency),
                timeout=settings.ai_callback_timeout_sec,follow_redirects=False,trust_env=False):
            pass
        while not stop_event.is_set():
            done={job for job in pending if job.done()}
            pending.difference_update(done)
            for job in done:
                try:job.result()
                except Exception:logger.exception('async AI claim failed')
            available=concurrency-len(pending)
            poll_interval = max(.01, settings.task_poll_interval_sec)
            if available and pool.gate.snapshot()['queued'] < max(2, settings.ai_db_threads*2) and time.monotonic() - last_poll >= poll_interval:
                try:
                    claims=await pool.run(claim_ready_tasks,('ai_turn',),available)
                    last_claim=time.monotonic()
                    for task_id,claim in claims:
                        pending.add(asyncio.create_task(process_ai_claim(task_id,claim,pool,actions)))
                except Exception:logger.exception('async AI claim poll failed')
                finally:
                    # Completions can arrive one at a time under load. Coalesce
                    # free slots rather than issuing a claim transaction for
                    # every completion (including repeatedly empty polls).
                    last_poll=time.monotonic()
            now=time.monotonic()
            if now-last_health>=5 and (not available or now-last_claim<15):
                data={'updated_at':time.time(),'inflight':len(pending),'limit':concurrency,
                      'db_threads':len(pool.runtimes),'action_threads':0,
                      'stage_timings':pool.timing_snapshot(), 'db_work':pool.pressure_snapshot()}
                from .stability import publish
                await asyncio.to_thread(publish, 'ai', worker_identity,
                    {k:data['db_work'][k] for k in ('queue_p95_ms', 'oldest_wait_ms')})
                temporary=health_path.with_suffix('.tmp')
                temporary.write_text(json.dumps(data));temporary.replace(health_path)
                last_health=now
            poll_wait = max(.001, poll_interval - (time.monotonic() - last_poll)) if available else poll_interval
            if pool.gate.snapshot()['queued'] >= max(2, settings.ai_db_threads*2):
                poll_wait = poll_interval
            if pending:
                await asyncio.wait(pending,timeout=poll_wait,
                                   return_when=asyncio.FIRST_COMPLETED)
            else:
                try:await asyncio.wait_for(stop_event.wait(),poll_wait)
                except asyncio.TimeoutError:pass
    finally:
        await asyncio.gather(*pending,return_exceptions=True)
        for resource in resources.values():await resource.aclose()
        _resources.reset(resource_token)
        await pool.close()
        health_path.unlink(missing_ok=True)
