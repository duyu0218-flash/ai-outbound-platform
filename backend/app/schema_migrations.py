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
        if "script_flow_version_id" not in call_columns:
            statements.append("ALTER TABLE callsession ADD COLUMN script_flow_version_id INTEGER")
        if "flow_node_key" not in call_columns:
            statements.append("ALTER TABLE callsession ADD COLUMN flow_node_key VARCHAR(128)")

    if "campaign" in tables:
        campaign_columns = _columns(engine, "campaign")
        if "script_flow_version_id" not in campaign_columns:
            statements.append("ALTER TABLE campaign ADD COLUMN script_flow_version_id INTEGER")

    if "telephonyline" in tables:
        line_columns = _columns(engine, "telephonyline")
        if "priority" not in line_columns:
            statements.append("ALTER TABLE telephonyline ADD COLUMN priority INTEGER NOT NULL DEFAULT 100")
        if "weight" not in line_columns:
            statements.append("ALTER TABLE telephonyline ADD COLUMN weight INTEGER NOT NULL DEFAULT 1")
        if "credential_ref" not in line_columns:
            statements.append("ALTER TABLE telephonyline ADD COLUMN credential_ref VARCHAR(128) NOT NULL DEFAULT ''")

    if "user" in tables:
        user_columns = _columns(engine, "user")
        if "agent_status" not in user_columns:
            statements.append("ALTER TABLE \"user\" ADD COLUMN agent_status VARCHAR(32) NOT NULL DEFAULT 'offline'")
        if "last_seen_at" not in user_columns:
            statements.append("ALTER TABLE \"user\" ADD COLUMN last_seen_at TIMESTAMP")

    if "smslog" in tables:
        sms_columns = _columns(engine, "smslog")
        if "provider_message_id" not in sms_columns:
            statements.append("ALTER TABLE smslog ADD COLUMN provider_message_id VARCHAR(255)")
        if "provider_error" not in sms_columns:
            statements.append("ALTER TABLE smslog ADD COLUMN provider_error VARCHAR(2000)")
        if "updated_at" not in sms_columns:
            statements.append("ALTER TABLE smslog ADD COLUMN updated_at TIMESTAMP")

    with engine.begin() as connection:
        if engine.dialect.name == "postgresql" and "handoffrequest" in tables:
            # SQLModel creates Python enums as native PostgreSQL enums. Older
            # installations therefore need the transient claim state added
            # before the handoff acceptance endpoint can use it.
            connection.execute(
                text("ALTER TYPE handoffstate ADD VALUE IF NOT EXISTS 'ACCEPTING'")
            )
        for statement in statements:
            logger.info("applying database migration: %s", statement)
            connection.execute(text(statement))
        if "callsession" in tables:
            connection.execute(
                text("CREATE INDEX IF NOT EXISTS ix_callsession_telephony_line_id ON callsession (telephony_line_id)")
            )
        if "smslog" in tables:
            connection.execute(
                text("CREATE INDEX IF NOT EXISTS ix_smslog_provider_message_id ON smslog (provider_message_id)")
            )
