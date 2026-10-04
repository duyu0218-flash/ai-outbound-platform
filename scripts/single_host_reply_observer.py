"""Fail visibly when a synthetic reply observer stops; match registered turns only."""
import asyncio
import time


class ReplyObservationError(RuntimeError):
    pass


class ReplyObserver:
    def __init__(self, interval=.1):
        self.interval = interval
        self.pending = {}
        self.failure = None
        self.scans = 0
        self.last_success = None
        self.state = 'created'

    def register(self, call_id, attempt, sequence, since):
        if self.failure is not None:
            raise ReplyObservationError(self.failure['error_type'])
        key = (str(call_id), attempt, sequence)
        if key in self.pending:
            raise ValueError('reply already registered')
        future = asyncio.get_running_loop().create_future()
        self.pending[key] = (since, future)
        return future

    def unregister(self, call_id, attempt, sequence):
        self.pending.pop((str(call_id), attempt, sequence), None)

    def snapshot(self):
        return dict(state=self.state, scans=self.scans, last_success_monotonic=self.last_success,
                    failure=self.failure, pending=len(self.pending))

    async def run(self, reader):
        self.state = 'running'
        try:
            while True:
                since = min((value[0] for value in self.pending.values()), default=None)
                replies = await asyncio.to_thread(reader, since) if since is not None else []
                for call_id, attempt, sequence in replies:
                    registered = self.pending.get((str(call_id), attempt, sequence))
                    if registered is not None and not registered[1].done():
                        registered[1].set_result(None)
                self.scans += 1
                self.last_success = time.monotonic()
                await asyncio.sleep(self.interval)
        except asyncio.CancelledError:
            self.state = 'stopped'
            raise
        except Exception as exc:
            self.state = 'failed'
            self.failure = dict(error_type=type(exc).__name__, at_monotonic=time.monotonic())
            for _, future in self.pending.values():
                if not future.done():
                    future.set_exception(ReplyObservationError(type(exc).__name__))

