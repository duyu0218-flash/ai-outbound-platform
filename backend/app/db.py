from contextlib import contextmanager
from functools import wraps
import time
from typing import Generator

from sqlalchemy import event, inspect, text
from sqlalchemy.pool import QueuePool
from sqlmodel import Session, SQLModel, create_engine

from .config import get_settings
from .services.runtime_metrics import record_db_checkin, record_db_checkout, record_db_wait, record_late_commit, execution_threads

settings = get_settings()

class ObservedQueuePool(QueuePool):
    def _do_get(self):
        started = time.perf_counter()
        try:
            result = super()._do_get()
        except BaseException:
            record_db_wait(time.perf_counter() - started, failed=True)
            raise
        record_db_wait(time.perf_counter() - started)
        return result


engine_options = {"echo": False, "poolclass": ObservedQueuePool}
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

event.listen(engine, "checkout", lambda *args: record_db_checkout())
event.listen(engine, "checkin", lambda *args: record_db_checkin())
event.listen(engine, "commit", lambda *args: record_late_commit())


REQUIRED_PRODUCTION_TABLES = {
    "tenant",
    "user",
    "contact",
    "contactimportjob",
    "campaign",
    "callsession",
    "taskoutbox",
    "recordingasset",
}


def verify_database_schema() -> None:
    tables = set(inspect(engine).get_table_names())
    missing = sorted(REQUIRED_PRODUCTION_TABLES - tables)
    if missing:
        raise RuntimeError(
            "database schema is not initialized; run the approved schema bootstrap/migrations first: "
            + ", ".join(missing)
        )
    inspector = inspect(engine)
    for table, required in {"taskoutbox": {"lease_token"}, "speechturn": {"attempt"}}.items():
        columns = {c["name"] for c in inspector.get_columns(table)} if table in tables else set()
        if required - columns:
            raise RuntimeError("database migration 20260906_execution_leases is required: "
                               + table + "." + ",".join(sorted(required - columns)))


def create_db_and_tables(*, force: bool = False) -> None:
    from . import models  # noqa: F401
    from .schema_migrations import apply_runtime_migrations

    is_prod = settings.env.lower() in {"prod", "production"}
    if is_prod and not settings.auto_migrate and not force:
        verify_database_schema()
        return

    if settings.database_url.startswith(("postgresql://", "postgresql+psycopg://")):
        # Every uvicorn worker runs lifespan. Serialize initial DDL so two fresh
        # workers cannot race while creating PostgreSQL enum types or tables.
        with engine.connect() as lock_connection:
            lock_connection.execute(text("SELECT pg_advisory_lock(hashtext('ai-outbound-bootstrap-ddl'))"))
            try:
                SQLModel.metadata.create_all(engine)
                apply_runtime_migrations(engine)
            finally:
                lock_connection.execute(text("SELECT pg_advisory_unlock(hashtext('ai-outbound-bootstrap-ddl'))"))
                lock_connection.commit()
        return
    SQLModel.metadata.create_all(engine)
    apply_runtime_migrations(engine)


def get_engine_url() -> str:
    return settings.database_url


def _acquire_connection():
    return engine.connect()


@contextmanager
def session_scope() -> Generator[Session, None, None]:
    # Lazy acquisition; commit/rollback returns the connection to the pool.
    with Session(engine) as session:
        yield session


def get_session() -> Generator[Session, None, None]:
    """FastAPI dependency that owns one SQLModel session per request."""
    with session_scope() as session:
        yield session


class WebhookSession(Session):
    """Acquire the outer transaction lazily, while the route owns a thread.

    Holding a connection in the dependency before the synchronous route can
    run creates a connection/thread starvation cycle under high concurrency.
    Service commits still release savepoints, preserving whole-event atomicity.
    """

    def __init__(self):
        self.outer_connection = None
        self.outer_transaction = None
        self.finished = False
        super().__init__(bind=engine, join_transaction_mode="create_savepoint")

    def get_bind(self, mapper=None, *, clause=None, bind=None, **kwargs):
        if self.outer_connection is None:
            connection = _acquire_connection()
            try:
                transaction = connection.begin()
                if connection.dialect.name == "sqlite":
                    connection.exec_driver_sql("BEGIN IMMEDIATE")
            except BaseException:
                connection.close()
                raise
            self.outer_connection = connection
            self.outer_transaction = transaction
            self.bind = connection
        return super().get_bind(mapper=mapper, clause=clause, bind=bind, **kwargs)

    def finish(self, *, success: bool):
        if self.finished:
            return
        try:
            if success:
                self.commit()
                if self.outer_transaction is not None:
                    self.outer_transaction.commit()
            else:
                self.rollback()
        finally:
            self.close()
            if self.outer_connection is not None:
                self.outer_connection.close()  # rolls back on any failure
            self.finished = True


def webhook_transaction(func):
    """Keep checkout, route, outer commit and checkin in one worker invocation."""
    @wraps(func)
    def wrapped(*args, **kwargs):
        session = kwargs.get("session")
        if not isinstance(session, WebhookSession):
            return func(*args, **kwargs)  # explicitly supplied service-test session
        with execution_threads():
            try:
                result = func(*args, **kwargs)
                session.finish(success=True)
                return result
            finally:
                session.finish(success=False)
    return wrapped


def get_webhook_session() -> Generator[Session, None, None]:
    """Commit an entire webhook, including its dedup marker and outbox, atomically.

    Existing service commits release savepoints, never the outer transaction.
    Use a function-scoped dependency so the outer commit precedes background work.
    """
    session = WebhookSession()
    try:
        yield session
    finally:
        # Validation/auth failures never enter the route and own no connection.
        session.finish(success=False)
