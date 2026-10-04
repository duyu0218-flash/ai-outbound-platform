import asyncio
from datetime import datetime, timezone

import pytest

from scripts.single_host_reply_observer import ReplyObserver, ReplyObservationError


def test_reader_failure_reaches_customer_and_future_registrations():
    async def run():
        observer = ReplyObserver(.001)
        futures = [observer.register(str(i), 1, 2, datetime.now(timezone.utc)) for i in range(2)]
        def read(since):
            raise RuntimeError('synthetic database read failed')
        await observer.run(read)
        for future in futures:
            with pytest.raises(ReplyObservationError, match='RuntimeError'):
                await future
        with pytest.raises(ReplyObservationError):
            observer.register('next', 1, 1, datetime.now(timezone.utc))
        assert observer.snapshot()['state'] == 'failed'
        assert observer.snapshot()['failure']['error_type'] == 'RuntimeError'
    asyncio.run(run())


def test_old_attempt_and_unregistered_round_cannot_complete_current_customer():
    async def run():
        observer = ReplyObserver(.001)
        future = observer.register('call', 2, 1, datetime.now(timezone.utc))
        scans = 0
        def read(since):
            nonlocal scans
            scans += 1
            return [('call', 1, 1), ('call', 2, 2)] if scans == 1 else [('call', 2, 1)]
        observer.interval = .02
        task = asyncio.create_task(observer.run(read))
        try:
            while observer.scans < 1:
                await asyncio.sleep(.001)
            assert not future.done()
            await asyncio.wait_for(future, 1)
            observer.unregister('call', 2, 1)
            assert not observer.pending
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        assert observer.failure is None
    asyncio.run(run())


def test_timed_out_customer_does_not_crash_late_observation():
    async def run():
        observer = ReplyObserver(.001)
        future = observer.register('call', 1, 1, datetime.now(timezone.utc))
        future.cancel()
        task = asyncio.create_task(observer.run(lambda since: [('call', 1, 1)]))
        try:
            while observer.scans < 2:
                await asyncio.sleep(.001)
            assert observer.failure is None
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
    asyncio.run(run())
