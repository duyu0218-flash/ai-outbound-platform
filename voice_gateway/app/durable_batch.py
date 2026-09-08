"""Bounded group commit. A caller is acknowledged only AFTER FULL commit.

One coroutine owns the queue; one disk work unit commits each batch. Cancelled
callers do not cancel already accepted writes. Shutdown drains accepted writes.
"""
import asyncio


class DurableBatch:
    def __init__(self, commit, *, size=64, delay=.002, capacity=2048):
        self.commit = commit
        self.size = size
        self.delay = delay
        self.queue = asyncio.Queue(maxsize=capacity)
        self.task = None
        self.closed = False
        self.batches = 0
        self.operations = 0

    async def submit(self, operation):
        if self.closed:
            raise RuntimeError('durable writer is closing')
        future = asyncio.get_running_loop().create_future()
        # Consume errors even if the submitting HTTP request was cancelled.
        future.add_done_callback(lambda f: f.exception() if not f.cancelled() else None)
        try:
            self.queue.put_nowait((operation, future))
        except asyncio.QueueFull:
            raise RuntimeError('durable writer queue is full; retry original event') from None
        if self.task is None:
            self.task = asyncio.create_task(self._run(), name='durable-group-commit')
        await asyncio.shield(future)

    async def _run(self):
        while not self.queue.empty():
            await asyncio.sleep(self.delay)
            batch = []
            while len(batch) < self.size and not self.queue.empty():
                batch.append(self.queue.get_nowait())
            try:
                await asyncio.to_thread(self.commit, [item[0] for item in batch])
            except Exception as exc:
                for _, future in batch:
                    future.set_exception(exc)
            else:
                self.batches += 1
                self.operations += len(batch)
                for _, future in batch:
                    future.set_result(None)
            finally:
                for _ in batch:
                    self.queue.task_done()
        # No await between empty observation and reset: submit cannot strand work.
        self.task = None

    async def close(self):
        self.closed = True
        if self.task is not None:
            await asyncio.shield(self.task)
