from .calls import router as calls_router
from .campaigns import router as campaigns_router
from .contacts import router as contacts_router
from .webhooks import router as webhooks_router

__all__ = [
    "calls_router",
    "campaigns_router",
    "contacts_router",
    "webhooks_router",
]
