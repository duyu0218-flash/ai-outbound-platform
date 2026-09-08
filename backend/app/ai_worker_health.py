"""Read this container's own async AI worker heartbeat; no shared host signal."""
import json
from pathlib import Path
import time
from .config import get_settings


def main():
    try:
        state=json.loads(Path(get_settings().ai_worker_health_path).read_text())
        return 0 if 0<=time.time()-state['updated_at']<20 else 1
    except (OSError,ValueError,KeyError):return 1


if __name__=='__main__':raise SystemExit(main())
