"""Bounded, aging DB admission; running threads are never preempted."""
import asyncio
from dataclasses import dataclass
import time


class WorkQueueFull(RuntimeError):
    pass


@dataclass
class Waiter:
    future: asyncio.Future
    priority: int
    started: float
    granted: bool = False


class WorkGate:
    def __init__(self, size, limit=512, aging_sec=.1):
        if size < 1 or limit < 1 or aging_sec <= 0:
            raise ValueError('positive work gate limits required')
        self.size, self.limit, self.aging_sec = size, limit, aging_sec
        self.active = 0
        self.waiters = []

    async def acquire(self, priority):
        if self.active < self.size and not self.waiters:
            self.active += 1
            return
        if len(self.waiters) >= self.limit:
            raise WorkQueueFull('bounded DB work queue is full')
        waiter = Waiter(asyncio.get_running_loop().create_future(), priority, time.monotonic())
        self.waiters.append(waiter)
        try:
            await waiter.future
        except BaseException:
            if waiter.granted:
                self.release()
            elif waiter in self.waiters:
                self.waiters.remove(waiter)
            raise

    def release(self):
        self.active -= 1
        now = time.monotonic()
        while self.waiters and self.active < self.size:
            # Aging eventually makes every queued class eligible; FIFO breaks ties.
            waiter = min(self.waiters, key=lambda w: (
                max(0, w.priority - int((now-w.started)/self.aging_sec)), w.started))
            self.waiters.remove(waiter)
            if waiter.future.done():
                continue
            self.active += 1
            waiter.granted = True
            waiter.future.set_result(None)

    def snapshot(self):
        return dict(active=self.active, queued=len(self.waiters), limit=self.limit,
                    oldest_wait_ms=max((time.monotonic()-w.started for w in self.waiters), default=0)*1000)


def work_priority(name):
    if name == '_renew_task':
        return 0
    if name in {'complete', '_finish_ai_turn', '_finish_and_prepare_ai_action', '_fail_ai_turn', 'finish', 'record_speech', 'record_sms'}:
        return 1
    if name in {'claim_ready_tasks', 'read_current'}:
        return 3
    return 2


class Histogram:
    """Cumulative milliseconds histogram, mergeable across workers/restarts by delta."""
    bounds = (1, 5, 10, 25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)

    def __init__(self):
        self.count = 0
        self.total = 0.0
        self.maximum = 0.0
        self.buckets = [0] * len(self.bounds)

    def observe(self, value):
        self.count += 1
        self.total += value
        self.maximum = max(self.maximum, value)
        for i, bound in enumerate(self.bounds):
            if value <= bound:
                self.buckets[i] += 1

    def snapshot(self):
        return dict(count=self.count, sum_ms=self.total, max_ms=self.maximum,
                    buckets={**{str(b): n for b, n in zip(self.bounds, self.buckets)}, '+Inf': self.count})
