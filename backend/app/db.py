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

def _build_engine_options(database_url: str) -> dict:
    opts = {"echo": False, "poolclass": ObservedQueuePool}
    if database_url.startswith("sqlite"):
        opts["connect_args"] = {"check_same_thread": False}
    else:
        opts.update(
            {
                "pool_pre_ping": True,
                "pool_size": max(1, settings.database_pool_size),
                "max_overflow": max(0, settings.database_max_overflow),
                "pool_timeout": max(1, settings.database_pool_timeout_sec),
                "pool_recycle": max(60, settings.database_pool_recycle_sec),
            }
        )
    return opts


def create_engine_for_url(database_url: str):
    return create_engine(database_url, **_build_engine_options(database_url))


def get_database_url_for_api() -> str:
    return settings.database_url_for_api()


def get_database_url_for_bootstrap() -> str:
    return settings.database_url_for_bootstrap()


def _build_lock_key(lock_name: str | None, fallback: str) -> str:
    return (lock_name or fallback).strip() or fallback


def _is_postgresql_url(database_url: str) -> bool:
    return database_url.startswith(("postgresql://", "postgresql+psycopg://"))


def _acquire_advisory_lock(connection, lock_name: str, *, enabled: bool) -> None:
    if enabled:
        connection.execute(text("SELECT pg_advisory_lock(hashtext(:lock_name))"), {"lock_name": lock_name})


def _release_advisory_lock(connection, lock_name: str, *, enabled: bool) -> None:
    if enabled:
        connection.execute(text("SELECT pg_advisory_unlock(hashtext(:lock_name))"), {"lock_name": lock_name})


engine = create_engine_for_url(get_database_url_for_api())

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
    "callusage",
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
    call_columns = {c["name"] for c in inspector.get_columns("callsession")}
    if not {"gateway_node_id", "gateway_endpoint"} <= call_columns:
        raise RuntimeError("database migration 20260907_compact_cluster is required")
    if settings.voice_gateway_nodes_file or settings.voice_gateway_nodes_json.strip() != "[]":
        if "gatewaynode" not in tables or not {"gateway_node_id", "gateway_endpoint"} <= {c["name"] for c in inspector.get_columns("callsession")}:
            raise RuntimeError("compact cluster schema migration is required before enabling the gateway roster")
    for table, required in {"taskoutbox": {"lease_token"}, "speechturn": {"attempt"}, "recordingasset": {"attempt"}, "callanalysis": {"automatic_result_json", "needs_review"}, "realtimesession": {"last_event_sequence"}}.items():
        columns = {c["name"] for c in inspector.get_columns(table)} if table in tables else set()
        if required - columns:
            raise RuntimeError("database migration 20260907_review_fixes is required: "
                               + table + "." + ",".join(sorted(required - columns)))


def create_db_and_tables(*, force: bool = False) -> None:
    from . import models  # noqa: F401
    from .schema_migrations import apply_runtime_migrations

    is_prod = settings.env.lower() in {"prod", "production"}
    if is_prod and not settings.auto_migrate and not force:
        verify_database_schema()
        return

    bootstrap_url = get_database_url_for_bootstrap()
    bootstrap_engine = _bootstrap_engine()

    def _run_initialization(connection):
        SQLModel.metadata.create_all(connection)
        apply_runtime_migrations(connection)

    try:
        with bootstrap_engine.connect() as connection:
            locked = _is_postgresql_url(bootstrap_url) and settings.database_bootstrap_advisory_lock
            lock_name = _build_lock_key(settings.database_bootstrap_lock_name, "ai-outbound-bootstrap-ddl")
            _acquire_advisory_lock(connection, lock_name, enabled=locked)
            # Lock acquisition autobegins a transaction; session locks survive commit.
            connection.commit()
            try:
                with connection.begin():
                    _run_initialization(connection)
            finally:
                connection.rollback()
                _release_advisory_lock(connection, lock_name, enabled=locked)
                connection.commit()
    finally:
        bootstrap_engine.dispose()


def get_engine_url() -> str:
    return get_database_url_for_api()


def _acquire_connection():
    return engine.connect()


def _bootstrap_engine():
    bootstrap_url = get_database_url_for_bootstrap()
    return create_engine_for_url(bootstrap_url)


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
                if self.in_transaction():
                    self.commit()
                if self.outer_transaction is not None and self.outer_transaction.is_active:
                    self.outer_transaction.commit()
            else:
                if self.outer_transaction is not None and self.outer_transaction.is_active:
                    self.outer_transaction.rollback()
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
            except BaseException:
                session.finish(success=False)
                raise
            else:
                session.finish(success=True)
                return result
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
