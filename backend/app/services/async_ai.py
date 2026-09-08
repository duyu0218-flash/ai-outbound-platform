"""Bounded async AI lane with short, thread-owned database work units."""
import asyncio
import inspect
import json
import logging
import threading
import time
from pathlib import Path
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from contextvars import copy_context
from datetime import timedelta
from uuid import UUID

from ..clock import utc_now
from ..config import get_settings
from ..db import session_scope
from ..models import TaskState
from .leases import monitored_lease, assert_execution_permitted, ExecutionLease, _leases
from .worker_runtime import WorkerRuntime, _resources
from .task_queue import claim_ready_tasks, _renew_task, _owned_task, _record_dead_task

logger = logging.getLogger(__name__)
settings = get_settings()


class WorkPool:
    """Cancellation drains an accepted work unit before releasing its lease.

    Threads own their ORM session and event loop. Shared execution lease objects
    let loss/cancellation prohibit subsequent external actions in that thread.
    """
    def __init__(self, size, name='ai-db'):
        self.executor=ThreadPoolExecutor(max_workers=size, thread_name_prefix=name)
        self.slots=asyncio.Semaphore(size)
        self.local=threading.local()
        self.runtimes=[]

    async def run(self, function, *args):
        async with self.slots:
            context=copy_context()
            cancelled=ExecutionLease(float('inf'))
            context.run(_leases.set, (*context.get(_leases, ()), cancelled))
            def execute():
                if not hasattr(self.local,'runtime'):
                    self.local.runtime=WorkerRuntime()
                    self.runtimes.append(self.local.runtime)
                runtime=self.local.runtime
                context.run(_resources.set, runtime.resources)
                async def invoke():
                    assert_execution_permitted()
                    value=function(*args)
                    return await value if inspect.isawaitable(value) else value
                return runtime.runner.run(invoke(), context=context)
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

    async def close(self):
        self.executor.shutdown(wait=True)
        for runtime in self.runtimes:
            await asyncio.to_thread(runtime.close)


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


async def run_async_ai_lane(stop_event, *, concurrency):
    pool=WorkPool(settings.ai_db_threads)
    actions=WorkPool(settings.ai_action_threads,'ai-action')
    pending=set()
    last_health=0.0
    last_claim=0.0
    health_path=Path(settings.ai_worker_health_path)
    resources=OrderedDict()
    resource_token=_resources.set(resources)
    try:
        while not stop_event.is_set():
            done={job for job in pending if job.done()}
            pending.difference_update(done)
            for job in done:
                try:job.result()
                except Exception:logger.exception('async AI claim failed')
            available=concurrency-len(pending)
            if available:
                try:
                    claims=await pool.run(claim_ready_tasks,('ai_turn',),available)
                    last_claim=time.monotonic()
                    for task_id,claim in claims:
                        pending.add(asyncio.create_task(process_ai_claim(task_id,claim,pool,actions)))
                except Exception:logger.exception('async AI claim poll failed')
            now=time.monotonic()
            if now-last_health>=5 and (not available or now-last_claim<15):
                data={'updated_at':time.time(),'inflight':len(pending),'limit':concurrency,
                      'db_threads':len(pool.runtimes),'action_threads':len(actions.runtimes)}
                temporary=health_path.with_suffix('.tmp')
                temporary.write_text(json.dumps(data));temporary.replace(health_path)
                last_health=now
            if pending:
                await asyncio.wait(pending,timeout=max(.01,settings.task_poll_interval_sec),
                                   return_when=asyncio.FIRST_COMPLETED)
            else:
                try:await asyncio.wait_for(stop_event.wait(),max(.01,settings.task_poll_interval_sec))
                except asyncio.TimeoutError:pass
    finally:
        await asyncio.gather(*pending,return_exceptions=True)
        for resource in resources.values():await resource.aclose()
        _resources.reset(resource_token)
        await actions.close();await pool.close()
        health_path.unlink(missing_ok=True)
