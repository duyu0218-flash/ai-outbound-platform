"""Per-work-unit DB timing, without SQL text or customer data."""
from contextvars import ContextVar
import time

current = ContextVar('db_work_observation', default=None)


def before_cursor(conn, cursor, statement, parameters, context, many):
    if current.get() is not None:
        context._work_sql_started = time.perf_counter()


def after_cursor(conn, cursor, statement, parameters, context, many):
    observation = current.get()
    started = getattr(context, '_work_sql_started', None)
    if observation is not None and started is not None:
        observation['sql_ms'] += (time.perf_counter()-started)*1000
        observation['sql_count'] += 1
        context._work_sql_started = None


def before_commit(session):
    if current.get() is not None:
        session.info.setdefault('_work_commit_started', []).append(time.perf_counter())


def after_commit(session):
    starts = session.info.get('_work_commit_started', [])
    observation = current.get()
    if starts:
        started = starts.pop()
        if observation is not None:
            # Includes ORM flush and commit; not pure fsync time.
            observation['flush_commit_ms'] += (time.perf_counter()-started)*1000
            observation['commit_count'] += 1


def after_rollback(session):
    session.info.pop('_work_commit_started', None)
    observation = current.get()
    if observation is not None:
        observation['rollback_count'] += 1


def sql_error(error_context):
    observation = current.get()
    context = error_context.execution_context
    started = getattr(context, '_work_sql_started', None)
    if observation is not None and started is not None:
        observation['sql_ms'] += (time.perf_counter()-started)*1000
        observation['sql_count'] += 1
        observation['sql_error_count'] = observation.get('sql_error_count', 0)+1
        context._work_sql_started = None
