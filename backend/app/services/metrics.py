from __future__ import annotations

from datetime import datetime, timedelta
import math

from sqlalchemy import func
from sqlmodel import Session, select

from ..clock import utc_now
from ..models import CallSession, RecordingAsset, TaskOutbox, TaskState, CallMetric, User
from .runtime_metrics import snapshot_for_metrics


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
    lines.extend([
        "# HELP ai_outbound_task_oldest_ready_seconds Age of the oldest ready task by bounded task type.",
        "# TYPE ai_outbound_task_oldest_ready_seconds gauge",
    ])
    for kind in ("ai_turn", "business_callback", "recording_ingest", "recording_delete"):
        oldest = session.exec(select(func.min(TaskOutbox.available_at)).where(
            TaskOutbox.task_type == kind, TaskOutbox.state.in_([TaskState.PENDING, TaskState.FAILED]),
            TaskOutbox.available_at <= current)).one()
        age = max(0, (current - oldest).total_seconds()) if oldest else 0
        lines.append(f'ai_outbound_task_oldest_ready_seconds{{type="{kind}"}} {age:.3f}')
    # Bounded recent sample, explicitly exposed as a gauge, never a lifetime histogram.
    lines.extend([
        "# HELP ai_outbound_stage_recent_seconds Quantiles of at most 10000 newest samples per stage in the last 300 seconds.",
        "# TYPE ai_outbound_stage_recent_seconds gauge",
        "# TYPE ai_outbound_stage_recent_samples gauge",
    ])
    for stage in ("ai.turn", "tts.dispatch", "tts.playback", "asr.final"):
        samples = sorted(session.exec(select(CallMetric.duration_ms).where(
            CallMetric.stage == stage, CallMetric.duration_ms.is_not(None),
            CallMetric.created_at >= current - timedelta(seconds=300))
            .order_by(CallMetric.created_at.desc()).limit(10000)).all())
        lines.append(f'ai_outbound_stage_recent_samples{{stage="{stage}"}} {len(samples)}')
        for q in (.5, .95, .99):
            if samples:
                value = samples[max(0, math.ceil(len(samples) * q)-1)] / 1000
                lines.append(f'ai_outbound_stage_recent_seconds{{stage="{stage}",quantile="{q}"}} {value:.3f}')
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
    exported_types: set[tuple[str, str]] = set()
    for metric_type, metric_name_with_labels, value in snapshot_for_metrics():
        metric_name = metric_name_with_labels.split("{", 1)[0]
        key = (metric_name, metric_type)
        if key not in exported_types:
            lines.append(f"# TYPE {metric_name} {metric_type}")
            exported_types.add(key)
        lines.append(f"{metric_name_with_labels} {value}")
    return "\n".join(lines) + "\n"
