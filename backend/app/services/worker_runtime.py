"""Thread-owned reusable event loops and bounded network connection pools."""
from collections import OrderedDict
from contextlib import asynccontextmanager
from contextvars import ContextVar
import asyncio
import json

import httpx

_resources = ContextVar("worker_network_resources", default=None)


class WorkerRuntime:
    def __init__(self):
        self.runner = asyncio.Runner()
        self.resources = OrderedDict()
        async def initialize():
            _resources.set(self.resources)
        self.runner.run(initialize())

    def run(self, coroutine):
        return self.runner.run(coroutine)

    def close(self):
        async def cleanup():
            for resource in self.resources.values():
                await resource.aclose()
            self.resources.clear()
        try:
            self.runner.run(cleanup())
        finally:
            self.runner.close()


@asynccontextmanager
async def http_client(**kwargs):
    resources = _resources.get()
    if resources is None:
        async with httpx.AsyncClient(**kwargs) as client:
            yield client
        return
    # Include all constructor settings, especially headers, to prevent credential
    # reuse across tenants. A lane thread runs one job at a time.
    key = "http:" + json.dumps(kwargs, sort_keys=True)
    client = resources.get(key)
    if client is None:
        if len(resources) >= 8:
            _, previous = resources.popitem(last=False)
            await previous.aclose()
        client = httpx.AsyncClient(**kwargs)
        resources[key] = client
    resources.move_to_end(key)
    yield client
