from __future__ import annotations

import asyncio
import logging
import signal

from .config import get_settings, setup_logging
from .db import create_db_and_tables
from .services.async_ai import run_async_ai_lane


async def serve() -> None:
    settings = get_settings()
    setup_logging(settings.log_level)
    logger = logging.getLogger(__name__)
    if settings.task_worker_role != "ai":
        raise RuntimeError("async AI worker requires TASK_WORKER_ROLE=ai")
    if not 1 <= settings.task_ai_concurrency <= 256 or not 1 <= settings.ai_db_threads <= 8 or not 1 <= settings.ai_action_threads <= 8:
        raise RuntimeError("invalid async AI concurrency or work pool limits")
    settings.resolved_task_queue_aliases()
    create_db_and_tables()
    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop_event.set)
        except NotImplementedError:
            pass
    logger.info("async AI worker started")
    await run_async_ai_lane(stop_event, concurrency=settings.task_ai_concurrency)
    logger.info("async AI worker stopped")


if __name__ == "__main__":
    asyncio.run(serve())
