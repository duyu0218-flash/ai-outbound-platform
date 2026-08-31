from __future__ import annotations

import asyncio
import logging
import signal

from .config import get_settings, setup_logging
from .db import create_db_and_tables
from .services.call_service import run_retry_scheduler


async def serve() -> None:
    settings = get_settings()
    setup_logging(settings.log_level)
    logger = logging.getLogger(__name__)
    if not settings.scheduler_enabled:
        raise RuntimeError("the dedicated worker requires SCHEDULER_ENABLED=true")
    create_db_and_tables()
    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop_event.set)
        except NotImplementedError:
            pass
    logger.info("durable task worker started")
    await run_retry_scheduler(stop_event)
    logger.info("durable task worker stopped")


if __name__ == "__main__":
    asyncio.run(serve())
