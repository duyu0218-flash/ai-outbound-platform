"""Renewable leases with cancellation and a monotonic side-effect deadline."""
from __future__ import annotations

import asyncio
import time
from contextlib import asynccontextmanager, suppress
from contextvars import ContextVar
from dataclasses import dataclass
from uuid import uuid4

from redis import asyncio as redis_async


class LeaseLost(RuntimeError):
    pass


@dataclass
class ExecutionLease:
    valid_until: float
    lost: bool = False

    def check(self):
        if self.lost or time.monotonic() >= self.valid_until:
            raise LeaseLost('execution lease expired; no further side effects are permitted')


_leases: ContextVar[tuple[ExecutionLease, ...]] = ContextVar('execution_leases', default=())


def assert_execution_permitted() -> None:
    for lease in _leases.get():
        lease.check()


@asynccontextmanager
async def monitored_lease(renew, *, ttl: float, initial_until: float):
    lease = ExecutionLease(initial_until)
    context_token = _leases.set((*_leases.get(), lease))
    owner = asyncio.current_task()

    async def heartbeat():
        while True:
            await asyncio.sleep(max(.1, ttl / 3))
            sent_at = time.monotonic()
            try:
                lease.check()
                if not await asyncio.wait_for(renew(), timeout=max(.1, ttl / 3)):
                    raise LeaseLost('lease is owned by another executor')
                # A blocked event loop cannot revive an already expired lease.
                lease.check()
                lease.valid_until = sent_at + ttl
            except Exception:
                lease.lost = True
                owner.cancel()
                return

    task = asyncio.create_task(heartbeat())
    try:
        lease.check()
        yield lease
        lease.check()
    except asyncio.CancelledError:
        if lease.lost:
            raise LeaseLost('execution lease could not be renewed') from None
        raise
    finally:
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task
        _leases.reset(context_token)


@asynccontextmanager
async def redis_lease(*, url: str, key: str, ttl: int, wait_sec: float = 0):
    if not url:
        yield True
        return
    ttl = max(2, ttl)
    client = redis_async.from_url(url, decode_responses=True, socket_connect_timeout=2, socket_timeout=2)
    token = uuid4().hex
    acquired = False
    deadline = time.monotonic() + max(0, wait_sec)
    try:
        while True:
            sent_at = time.monotonic()
            acquired = bool(await client.set(key, token, nx=True, ex=ttl))
            if acquired or time.monotonic() >= deadline:
                break
            await asyncio.sleep(.05)
        if not acquired:
            yield False
            return

        async def renew():
            return await client.eval(
                "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('expire',KEYS[1],ARGV[2]) else return 0 end",
                1, key, token, ttl)

        async with monitored_lease(renew, ttl=ttl, initial_until=sent_at + ttl):
            yield True
    finally:
        if acquired:
            with suppress(Exception):
                await client.eval("if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",1,key,token)
        await client.aclose()
