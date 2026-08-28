from .calls import router as calls_router
from .campaigns import router as campaigns_router
from .contacts import router as contacts_router
from .webhooks import router as webhooks_router
from .auth import router as auth_router
from .script_templates import router as script_templates_router
from .pages import router as pages_router
from .admin_management import router as admin_management_router

__all__ = [
    "calls_router",
    "campaigns_router",
    "contacts_router",
    "webhooks_router",
    "auth_router",
    "script_templates_router",
    "pages_router",
    "admin_management_router",
]
