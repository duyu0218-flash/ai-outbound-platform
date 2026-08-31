from __future__ import annotations

from datetime import datetime

from sqlalchemy import func
from sqlmodel import Session, select

from ..clock import utc_now
from ..models import CallSession, RecordingAsset, TaskOutbox, User


def _label(value: object) -> str:
    return str(getattr(value, "value", value)).replace("\\", "\\\\").replace('"', '\\"')


def render_prometheus_metrics(session: Session, *, now: datetime | None = None) -> str:
    """Render low-cardinality, database-backed operational metrics."""

    current = now or utc_now()
    lines = [
        "# HELP ai_outbound_up Control API metrics query succeeded.",
        "# TYPE ai_outbound_up gauge",
        "ai_outbound_up 1",
        "# HELP ai_outbound_calls Calls by terminal or active status.",
        "# TYPE ai_outbound_calls gauge",
    ]
    for status, count in session.exec(
        select(CallSession.status, func.count(CallSession.id)).group_by(CallSession.status)
    ).all():
        lines.append(f'ai_outbound_calls{{status="{_label(status)}"}} {int(count)}')

    lines.extend([
        "# HELP ai_outbound_calls_by_pipeline Calls assigned to each voice AI pipeline.",
        "# TYPE ai_outbound_calls_by_pipeline gauge",
    ])
    for pipeline, count in session.exec(
        select(CallSession.voice_ai_pipeline, func.count(CallSession.id)).group_by(CallSession.voice_ai_pipeline)
    ).all():
        lines.append(f'ai_outbound_calls_by_pipeline{{pipeline="{_label(pipeline)}"}} {int(count)}')

    lines.extend([
        "# HELP ai_outbound_tasks Durable tasks by state.",
        "# TYPE ai_outbound_tasks gauge",
    ])
    for state, count in session.exec(
        select(TaskOutbox.state, func.count(TaskOutbox.id)).group_by(TaskOutbox.state)
    ).all():
        lines.append(f'ai_outbound_tasks{{state="{_label(state)}"}} {int(count)}')

    locked_users = session.exec(
        select(func.count(User.id)).where(User.locked_until.is_not(None), User.locked_until > current)
    ).one()
    deletion_failures = session.exec(
        select(func.count(RecordingAsset.id)).where(RecordingAsset.state == "deletion_failed")
    ).one()
    ingestion_failures = session.exec(
        select(func.count(RecordingAsset.id)).where(RecordingAsset.state == "ingestion_failed")
    ).one()
    lines.extend([
        "# HELP ai_outbound_locked_users Accounts currently locked after failed logins.",
        "# TYPE ai_outbound_locked_users gauge",
        f"ai_outbound_locked_users {int(locked_users)}",
        "# HELP ai_outbound_recording_deletion_failures Recordings whose external deletion failed.",
        "# TYPE ai_outbound_recording_deletion_failures gauge",
        f"ai_outbound_recording_deletion_failures {int(deletion_failures)}",
        "# HELP ai_outbound_recording_ingestion_failures Recordings that could not be copied to managed storage.",
        "# TYPE ai_outbound_recording_ingestion_failures gauge",
        f"ai_outbound_recording_ingestion_failures {int(ingestion_failures)}",
    ])
    return "\n".join(lines) + "\n"
