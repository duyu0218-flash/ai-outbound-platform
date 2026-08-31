from __future__ import annotations

import sys

import redis

from .config import get_settings
from .services.health import db_health_check, redis_health_check


def main() -> int:
    settings = get_settings()
    if db_health_check() != "ok" or redis_health_check() != "ok":
        return 1
    if settings.redis_url:
        client = redis.from_url(settings.redis_url, socket_connect_timeout=2, socket_timeout=2)
        try:
            if not client.get("ai-outbound:scheduler:heartbeat"):
                return 1
        finally:
            client.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
