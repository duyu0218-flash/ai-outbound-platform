from __future__ import annotations

from datetime import timedelta

from sqlmodel import select

from ..clock import utc_now
from ..config import get_settings
from ..db import session_scope
from ..models import RecordingAsset, SpeechTurn, TaskState
from .task_queue import enqueue_task

settings = get_settings()


def purge_expired_voice_data(*, batch_size: int = 500) -> dict[str, int]:
    """Purge transient ASR text and queue durable recording deletion."""
    now = utc_now()
    partial_cutoff = now - timedelta(hours=max(1, settings.partial_transcript_retention_hours))
    deleted_partials = 0
    queued_recordings = 0
    with session_scope() as session:
        partials = session.exec(
            select(SpeechTurn)
            .where(SpeechTurn.is_final.is_(False), SpeechTurn.created_at <= partial_cutoff)
            .order_by(SpeechTurn.created_at.asc())
            .limit(max(1, batch_size))
        ).all()
        for turn in partials:
            session.delete(turn)
            deleted_partials += 1
        recordings = session.exec(
            select(RecordingAsset)
            .where(
                RecordingAsset.retention_until.is_not(None),
                RecordingAsset.retention_until <= now,
                RecordingAsset.deleted_at.is_(None),
            )
            .order_by(RecordingAsset.retention_until.asc())
            .limit(max(1, batch_size))
        ).all()
        for asset in recordings:
            asset.state = "deletion_pending"
            asset.updated_at = now
            session.add(asset)
            task = enqueue_task(
                session,
                tenant_id=asset.tenant_id,
                task_type="recording_delete",
                aggregate_id=str(asset.id),
                idempotency_key=f"recording-delete:{asset.id}",
                payload={"recording_asset_id": asset.id},
            )
            if task.state == TaskState.DEAD:
                asset.state = "deletion_failed"
                session.add(asset)
            else:
                queued_recordings += 1
        session.commit()
    return {
        "partial_transcripts": deleted_partials,
        "recordings": queued_recordings,
        "recording_deletion_tasks": queued_recordings,
    }
