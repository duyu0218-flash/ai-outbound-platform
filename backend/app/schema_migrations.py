"""Small, idempotent runtime migrations for installations created before v1.1.

SQLModel's ``create_all`` creates new tables but never adds columns to existing
tables. Keep these additive migrations explicit so an upgraded container does
not start with a model/database mismatch.
"""

from __future__ import annotations

import logging

from sqlalchemy import inspect, text
from sqlalchemy.engine import Engine

logger = logging.getLogger(__name__)


def _columns(engine: Engine, table: str) -> set[str]:
    return {str(item["name"]) for item in inspect(engine).get_columns(table)}


def apply_runtime_migrations(engine: Engine) -> None:
    inspector = inspect(engine)
    tables = set(inspector.get_table_names())
    statements: list[str] = []

    if "callsession" in tables:
        call_columns = _columns(engine, "callsession")
        if "human_agent_id" not in call_columns:
            statements.append("ALTER TABLE callsession ADD COLUMN human_agent_id INTEGER")
        if "telephony_line_id" not in call_columns:
            statements.append("ALTER TABLE callsession ADD COLUMN telephony_line_id INTEGER")

    if "telephonyline" in tables:
        line_columns = _columns(engine, "telephonyline")
        if "priority" not in line_columns:
            statements.append("ALTER TABLE telephonyline ADD COLUMN priority INTEGER NOT NULL DEFAULT 100")
        if "weight" not in line_columns:
            statements.append("ALTER TABLE telephonyline ADD COLUMN weight INTEGER NOT NULL DEFAULT 1")
        if "credential_ref" not in line_columns:
            statements.append("ALTER TABLE telephonyline ADD COLUMN credential_ref VARCHAR(128) NOT NULL DEFAULT ''")

    with engine.begin() as connection:
        for statement in statements:
            logger.info("applying database migration: %s", statement)
            connection.execute(text(statement))
        if "callsession" in tables:
            connection.execute(
                text("CREATE INDEX IF NOT EXISTS ix_callsession_telephony_line_id ON callsession (telephony_line_id)")
            )
