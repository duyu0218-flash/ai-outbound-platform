from __future__ import annotations

import hashlib
import hmac
import time
from datetime import timedelta

from sqlalchemy import or_, func, cast, String
from sqlmodel import select

from ..clock import utc_now
from ..config import get_settings
from ..db import session_scope
from ..models import CallAnalysis, CallEvent, CallMetric, CallSession, RecordingAsset, SpeechTurn, TaskState, TaskOutbox, SmsLog
from .admin_settings import get_admin_int_setting
from .task_queue import enqueue_task

settings = get_settings()


def purge_expired_voice_data(*, batch_size: int = 500) -> dict[str, int]:
    """Purge transient/final text, redact expired call PII and queue media deletion."""
    now = utc_now()
    deleted_partials = 0
    deleted_finals = 0
    redacted_calls = 0
    queued_recordings = 0
    with session_scope() as session:
        tenant_ids = set(
            session.exec(
                select(SpeechTurn.tenant_id).where(SpeechTurn.is_final.is_(False)).distinct()
            ).all()
        )
        remaining = max(1, batch_size)
        for tenant_id in tenant_ids:
            if remaining <= 0:
                break
            retention_hours = get_admin_int_setting(
                session,
                tenant_id,
                "compliance",
                "partial_transcript_retention_hours",
                settings.partial_transcript_retention_hours,
                minimum=1,
                maximum=720,
            )
            partial_cutoff = now - timedelta(hours=max(1, retention_hours))
            partials = session.exec(
                select(SpeechTurn)
                .where(
                    SpeechTurn.tenant_id == tenant_id,
                    SpeechTurn.is_final.is_(False),
                    SpeechTurn.created_at <= partial_cutoff,
                )
                .order_by(SpeechTurn.created_at.asc())
                .limit(remaining)
            ).all()
            for turn in partials:
                session.delete(turn)
                deleted_partials += 1
                remaining -= 1

        final_tenant_ids = set(
            session.exec(select(SpeechTurn.tenant_id).where(SpeechTurn.is_final.is_(True)).distinct()).all()
        )
        remaining_finals = max(1, batch_size)
        for tenant_id in final_tenant_ids:
            if remaining_finals <= 0:
                break
            retention_days = get_admin_int_setting(
                session,
                tenant_id,
                "compliance",
                "final_transcript_retention_days",
                settings.final_transcript_retention_days,
                minimum=1,
                maximum=3_650,
            )
            cutoff = now - timedelta(days=retention_days)
            turns = session.exec(
                select(SpeechTurn)
                .where(
                    SpeechTurn.tenant_id == tenant_id,
                    SpeechTurn.is_final.is_(True),
                    SpeechTurn.created_at <= cutoff,
                )
                .order_by(SpeechTurn.created_at.asc())
                .limit(remaining_finals)
            ).all()
            for turn in turns:
                session.delete(turn)
                deleted_finals += 1
                remaining_finals -= 1

        call_tenant_ids = set(session.exec(select(CallSession.tenant_id).distinct()).all())
        remaining_calls = max(1, batch_size)
        for tenant_id in call_tenant_ids:
            if remaining_calls <= 0:
                break
            retention_days = get_admin_int_setting(
                session,
                tenant_id,
                "compliance",
                "call_sensitive_data_retention_days",
                settings.call_sensitive_data_retention_days,
                minimum=1,
                maximum=3_650,
            )
            cutoff = now - timedelta(days=retention_days)
            calls = session.exec(
                select(CallSession)
                .where(
                    CallSession.tenant_id == tenant_id,
                    CallSession.finished_at.is_not(None),
                    CallSession.finished_at <= cutoff,
                    or_(
                        CallSession.last_transcript.is_not(None),
                        CallSession.summary.is_not(None),
                        CallSession.phone.not_like("redacted:%"),
                        CallSession.recording_url.is_not(None),
                    ),
                )
                .order_by(CallSession.finished_at.asc())
                .limit(remaining_calls)
            ).all()
            for call in calls:
                phone_digest = hmac.new(
                    settings.secret_key.encode("utf-8"),
                    f"{tenant_id}:{call.id}:{call.phone}".encode("utf-8"),
                    hashlib.sha256,
                ).hexdigest()[:24]
                if not call.phone.startswith("redacted:"):
                    call.phone = f"redacted:{phone_digest}"
                call.last_transcript = None
                call.summary = None
                call.recording_url = None
                call.last_error = None
                call.updated_at = now
                session.add(call)
                for event in session.exec(select(CallEvent).where(CallEvent.call_session_id == call.id)).all():
                    event.payload = "{}"
                    session.add(event)
                analysis = session.exec(
                    select(CallAnalysis).where(CallAnalysis.call_session_id == call.id)
                ).first()
                if analysis is not None:
                    analysis.summary = ""
                    analysis.qa_flags_json = "[]"
                    analysis.structured_json = "{}"
                    analysis.automatic_result_json = "{}"
                    analysis.needs_review = False
                    analysis.updated_at = now
                    session.add(analysis)
                for metric in session.exec(select(CallMetric).where(CallMetric.call_session_id == call.id)).all():
                    metric.detail = ""
                    session.add(metric)
                for asset in session.exec(
                    select(RecordingAsset).where(
                        RecordingAsset.call_session_id == call.id,
                        RecordingAsset.deleted_at.is_not(None),
                    )
                ).all():
                    asset.provider_url = ""
                    asset.provider_recording_id = None
                    asset.updated_at = now
                    session.add(asset)
                redacted_calls += 1
                remaining_calls -= 1
        scrubbed_tasks = 0
        scrubbed_sms = 0
        for tenant_id in call_tenant_ids:
            days = get_admin_int_setting(session, tenant_id, "compliance", "call_sensitive_data_retention_days",
                settings.call_sensitive_data_retention_days, minimum=1, maximum=3650)
            text_days = get_admin_int_setting(session, tenant_id, "compliance", "final_transcript_retention_days",
                settings.final_transcript_retention_days, minimum=1, maximum=3650)
            text_cutoff = now - timedelta(days=min(days,text_days))
            tasks = session.exec(select(TaskOutbox).join(CallSession,
                func.replace(TaskOutbox.aggregate_id,"-","") == func.replace(cast(CallSession.id,String),"-","")).where(
                TaskOutbox.tenant_id == tenant_id,CallSession.tenant_id == tenant_id,
                CallSession.finished_at.is_not(None),CallSession.finished_at <= text_cutoff,
                TaskOutbox.task_type.in_(["ai_turn","business_callback"]),TaskOutbox.payload_json != "{}",
                TaskOutbox.state != TaskState.PROCESSING).order_by(TaskOutbox.created_at).limit(max(1,batch_size))).all()
            for task in tasks:
                task.payload_json = "{}"
                task.last_error = ""
                if task.state in {TaskState.PENDING,TaskState.FAILED}:
                    task.state = TaskState.DEAD
                task.updated_at = now
                session.add(task)
                scrubbed_tasks += 1
            messages = session.exec(select(SmsLog).join(CallSession,CallSession.id == SmsLog.call_session_id).where(
                CallSession.tenant_id == tenant_id,CallSession.finished_at.is_not(None),
                CallSession.finished_at <= now-timedelta(days=days),
                or_(SmsLog.content != "",SmsLog.to_phone != "redacted")).order_by(SmsLog.created_at).limit(max(1,batch_size))).all()
            for sms in messages:
                sms.to_phone = "redacted"
                sms.content = ""
                sms.provider_error = None
                session.add(sms)
                scrubbed_sms += 1
        recordings = session.exec(
            select(RecordingAsset)
            .where(
                RecordingAsset.retention_until.is_not(None),
                RecordingAsset.retention_until <= now,
                RecordingAsset.deleted_at.is_(None),
                RecordingAsset.state.not_in(["deletion_pending", "deletion_failed"]),
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
                revive_dead=True,
            )
            if task.state == TaskState.DEAD:
                asset.state = "deletion_failed"
                session.add(asset)
            else:
                queued_recordings += 1
        session.commit()
    return {
        "task_payloads": scrubbed_tasks,
        "sms_payloads": scrubbed_sms,
        "partial_transcripts": deleted_partials,
        "final_transcripts": deleted_finals,
        "redacted_calls": redacted_calls,
        "recordings": queued_recordings,
        "recording_deletion_tasks": queued_recordings,
    }


def run_retention_cycle() -> dict[str, int]:
    """Drain full batches within a bounded time slice, independent of dialing."""
    deadline = time.monotonic() + max(1, settings.retention_run_budget_sec)
    total: dict[str,int] = {}
    while time.monotonic() < deadline:
        from .leases import assert_execution_permitted
        assert_execution_permitted()
        counts = purge_expired_voice_data(batch_size=max(1,settings.retention_batch_size))
        for key, value in counts.items():
            total[key] = total.get(key,0) + value
        if not any(value >= settings.retention_batch_size for value in counts.values()):
            break
    return total
