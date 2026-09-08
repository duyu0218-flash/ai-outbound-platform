"""Run with python -m app.callback_inbox_worker; --retry RECEIPT requeues a dead head."""
import argparse
import logging
import json
from pathlib import Path
import signal
import threading
import time
from uuid import uuid4

from sqlmodel import select
from .config import get_settings, setup_logging
from .db import create_db_and_tables, session_scope
from .models import CallbackInboxPartition
from .services.callback_inbox import PARTITIONS, consume_partition, maintenance, retry_receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--retry')
    args = parser.parse_args()
    settings = get_settings()
    if not settings.callback_inbox_enabled:
        raise RuntimeError('CALLBACK_INBOX_ENABLED=true is required')
    setup_logging(settings.log_level)
    create_db_and_tables()
    if args.retry:
        retry_receipt(args.retry)
        return
    stop = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stop.set())
    worker_id = uuid4().hex
    cursor = int(worker_id[:8], 16) % PARTITIONS
    last_maintenance = 0
    while not stop.is_set():
        try:
            if time.monotonic() - last_maintenance >= 1:
                maintenance(worker_id)
                Path(settings.callback_inbox_health_path).write_text(json.dumps(dict(worker_id=worker_id, at=time.time())))
                last_maintenance = time.monotonic()
            with session_scope() as session:
                partitions = session.exec(select(CallbackInboxPartition.id).where(
                    CallbackInboxPartition.pending_count > 0)).all()
            count = 0
            for partition in sorted(partitions, key=lambda p: (p - cursor) % PARTITIONS):
                if stop.is_set():
                    break
                count = consume_partition(partition, worker_id)
                cursor = (partition + 1) % PARTITIONS
                if count:
                    break
            if not count:
                stop.wait(settings.callback_inbox_poll_sec)
        except Exception:
            logging.exception('callback batch rolled back; durable receipt will be retried')
            stop.wait(settings.callback_inbox_poll_sec)
    # Expire readiness immediately on graceful exit. Crash uses heartbeat TTL.
    from .models import CallbackInboxWorker
    from .clock import utc_now
    from datetime import timedelta
    with session_scope() as session:
        worker = session.get(CallbackInboxWorker, worker_id)
        if worker:
            worker.heartbeat_at = utc_now() - timedelta(seconds=settings.callback_inbox_worker_ttl_sec + 1)
            session.add(worker)
            session.commit()


if __name__ == '__main__':
    main()
