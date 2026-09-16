"""One batched read for overlapping model waits; final writes still take locks."""
import asyncio
from contextvars import copy_context
from sqlmodel import select
from ..db import session_scope
from ..models import CallSession, RealtimeSession


def read_current(checks):
    from .dispatcher import AI_ACTIVE_STATUSES
    with session_scope() as session:
        rows = session.exec(select(CallSession.id, CallSession.attempts, CallSession.status,
            RealtimeSession.turn_sequence).outerjoin(RealtimeSession,
                RealtimeSession.call_session_id == CallSession.id).where(
                CallSession.id.in_({item[0] for item in checks}))).all()
        states = {cid:(attempt,status,sequence) for cid,attempt,status,sequence in rows}
        return [bool(cid in states and states[cid][0] == attempt
            and states[cid][1] in AI_ACTIVE_STATUSES
            and (sequence is None or states[cid][2] is None or states[cid][2] == sequence))
            for cid,attempt,sequence in checks]


class LivenessBatcher:
    def __init__(self, pool):
        self.pool, self.pending, self.task = pool, [], None

    async def current(self, snapshot, sequence):
        future = asyncio.get_running_loop().create_future()
        self.pending.append(((snapshot['call_id'], snapshot['attempt'], sequence), future))
        if self.task is None:
            from .leases import _leases
            context = copy_context()
            context.run(_leases.set, ())
            self.task = asyncio.create_task(self.flush(), context=context)
        return await future

    async def flush(self):
        # Only the periodic cancellation hint is coalesced. Outbound commands
        # and their conditional commits always perform authoritative checks.
        try:
            while self.pending:
                await asyncio.sleep(.005)
                batch = [(key, future) for key, future in self.pending if not future.done()]
                self.pending = []
                if not batch:
                    continue
                try:
                    values = await self.pool.run(read_current, [key for key,_ in batch])
                    for (_,future), value in zip(batch, values, strict=True):
                        if not future.done():
                            future.set_result(value)
                except Exception as exc:
                    for _,future in batch:
                        if not future.done():
                            future.set_exception(exc)
                except asyncio.CancelledError:
                    for _,future in batch:
                        future.cancel()
                    raise
        finally:
            # Keep one flush alive through queueing AND execution. New callers
            # collect for its next read rather than enqueueing competing reads.
            for _,future in self.pending:
                future.cancel()
            self.pending = []
            self.task = None
