from contextlib import contextmanager
from typing import Generator

from sqlmodel import Session, SQLModel, create_engine

from .config import get_settings

settings = get_settings()

engine_options = {"echo": False}
if settings.database_url.startswith("sqlite"):
    engine_options["connect_args"] = {"check_same_thread": False}
else:
    engine_options.update(
        {
            "pool_pre_ping": True,
            "pool_size": max(1, settings.database_pool_size),
            "max_overflow": max(0, settings.database_max_overflow),
            "pool_timeout": max(1, settings.database_pool_timeout_sec),
            "pool_recycle": max(60, settings.database_pool_recycle_sec),
        }
    )

engine = create_engine(settings.database_url, **engine_options)


def create_db_and_tables() -> None:
    from . import models  # noqa: F401
    from .schema_migrations import apply_runtime_migrations

    SQLModel.metadata.create_all(engine)
    apply_runtime_migrations(engine)


def get_engine_url() -> str:
    return settings.database_url


@contextmanager
def session_scope() -> Generator[Session, None, None]:
    with Session(engine) as session:
        yield session


def get_session() -> Generator[Session, None, None]:
    """FastAPI dependency that owns one SQLModel session per request."""
    with Session(engine) as session:
        yield session
