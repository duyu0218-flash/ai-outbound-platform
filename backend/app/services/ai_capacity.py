"""Dial-only AI execution capacity guard; health never releases call ownership."""
import json
import logging
import math
import re
import time
from datetime import timedelta
from uuid import uuid4

import redis
from redis import asyncio as redis_async
from sqlmodel import select

from ..clock import utc_now
from ..models import TaskOutbox, TaskState

logger = logging.getLogger(__name__)


def required_workers(settings):
    def unique(pairs):
        result={}
        for key,value in pairs:
            if key in result:raise ValueError('duplicate AI worker identity')
            result[key]=value
        return result
    requirements = json.loads(settings.ai_worker_requirements_json, object_pairs_hook=unique)
    if (not isinstance(requirements, dict) or len(requirements) > 64
            or any(not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', worker)
                   or type(slots) is not int or not 1 <= slots <= 256
                   for worker, slots in requirements.items())):
        raise ValueError('AI worker requirements must map unique worker IDs to 1..256 slots')
    return requirements


def worker_keys(settings, worker):
    base = f'{settings.ai_worker_health_prefix}:{worker}'
    return base + ':owner', base + ':state'


class WorkerHeartbeat:
    """Only the current generation may publish/withdraw this worker's health."""
    PULSE = """
    if redis.call('get',KEYS[1]) ~= ARGV[1] then return 0 end
    redis.call('set',KEYS[2],ARGV[2],'EX',ARGV[3])
    redis.call('expire',KEYS[1],ARGV[3])
    return 1
    """
    WITHDRAW = """
    if redis.call('get',KEYS[1]) ~= ARGV[1] then return 0 end
    redis.call('del',KEYS[2])
    if ARGV[2] == 'close' then redis.call('del',KEYS[1]) end
    return 1
    """

    def __init__(self, settings, concurrency):
        self.settings, self.concurrency = settings, concurrency
        self.requirements = required_workers(settings)
        self.epoch, self.client, self.acquired = uuid4().hex, None, False

    async def start(self):
        if not self.requirements:
            return
        if (not self.settings.redis_url
                or self.requirements.get(self.settings.ai_worker_id) != self.concurrency):
            raise RuntimeError('AI worker requires Redis, an approved identity and matching slot budget')
        self.client = redis_async.from_url(self.settings.redis_url, decode_responses=True,
            socket_connect_timeout=.25, socket_timeout=.25, max_connections=1)
        owner, _ = worker_keys(self.settings, self.settings.ai_worker_id)
        self.acquired = bool(await self.client.set(owner, self.epoch, nx=True,
            ex=self.settings.ai_worker_health_ttl_sec))
        if not self.acquired:
            raise RuntimeError('AI worker identity is already owned by another generation')

    async def publish(self, *, inflight, ready):
        if not self.requirements:
            return
        value = json.dumps(dict(worker_id=self.settings.ai_worker_id, epoch=self.epoch,
            limit=self.concurrency, inflight=inflight, ready=ready, updated_at=time.time()))
        if not await self.client.eval(self.PULSE, 2,
                *worker_keys(self.settings, self.settings.ai_worker_id), self.epoch, value,
                self.settings.ai_worker_health_ttl_sec):
            raise RuntimeError('AI worker health ownership was lost; stop claiming work')

    async def withdraw(self, *, close=False):
        if self.client is None:
            return
        try:
            if self.acquired:
                await self.client.eval(self.WITHDRAW, 2,
                    *worker_keys(self.settings, self.settings.ai_worker_id), self.epoch,
                    'close' if close else 'drain')
        except redis.RedisError:
            # TTL and the dial-side fail-closed read remain authoritative.
            logger.warning('AI worker health withdrawal failed')
        finally:
            if close:
                await self.client.aclose()


def workers_valid_until(settings):
    """Read only the configured identities, before taking the DB admission lock."""
    try:
        requirements = required_workers(settings)
        if not requirements:
            return float('inf')
        if not settings.redis_url:
            return 0.0
        keys = [key for worker in requirements for key in worker_keys(settings, worker)]
        with redis.Redis.from_url(settings.redis_url, decode_responses=True,
                socket_connect_timeout=.25, socket_timeout=.25, max_connections=1) as client:
            values = client.mget(keys)
        now = time.time()
        valid_until = time.monotonic() + settings.ai_worker_health_ttl_sec
        for (worker, slots), owner, raw in zip(requirements.items(), values[::2], values[1::2], strict=True):
            state = json.loads(raw)
            stamp = state.get('updated_at')
            if (not owner or state.get('epoch') != owner or state.get('worker_id') != worker
                    or state.get('ready') is not True
                    or type(state.get('limit')) is not int or state['limit'] != slots
                    or type(state.get('inflight')) is not int or not 0 <= state['inflight'] <= slots
                    or type(stamp) not in (int, float) or not math.isfinite(stamp)
                    or not 0 <= now - stamp < settings.ai_worker_health_ttl_sec):
                return 0.0
            valid_until = min(valid_until, time.monotonic() + settings.ai_worker_health_ttl_sec - (time.time()-stamp))
        return valid_until
    except (redis.RedisError, ValueError, TypeError, AttributeError):
        return 0.0


def workers_ready(settings):
    return time.monotonic() < workers_valid_until(settings)


def queue_ready(session, settings):
    if not required_workers(settings):
        return True
    now = utc_now()
    overdue = now - timedelta(seconds=settings.ai_task_max_ready_age_sec)
    # Future retry backoffs are deliberate, not time spent waiting for a slot.
    waiting = session.exec(select(TaskOutbox.id).where(TaskOutbox.task_type == 'ai_turn',
        TaskOutbox.state.in_([TaskState.PENDING, TaskState.FAILED]),
        TaskOutbox.available_at < overdue, TaskOutbox.attempts < TaskOutbox.max_attempts).limit(1)).first()
    if waiting is not None:
        return False
    # Count expired owners from the moment their lease became recoverable.
    recoverable = overdue - timedelta(seconds=max(2, settings.task_lease_sec))
    return session.exec(select(TaskOutbox.id).where(TaskOutbox.task_type == 'ai_turn',
        TaskOutbox.state == TaskState.PROCESSING, TaskOutbox.locked_at < recoverable,
        TaskOutbox.attempts < TaskOutbox.max_attempts).limit(1)).first() is None
