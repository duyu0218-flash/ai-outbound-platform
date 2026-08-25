from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api.routers import calls_router, campaigns_router, contacts_router, webhooks_router
from .config import get_settings
from .db import create_db_and_tables

settings = get_settings()


app = FastAPI(title=settings.app_name)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.on_event("startup")
def startup_event():
    create_db_and_tables()


@app.get("/health")
def health():
    return {"status": "ok", "service": settings.app_name}


app.include_router(calls_router)
app.include_router(campaigns_router)
app.include_router(contacts_router)
app.include_router(webhooks_router)
