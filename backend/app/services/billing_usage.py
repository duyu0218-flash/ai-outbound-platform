"""Per-attempt duration estimates. Provider CDR seconds override observed timing."""
from math import isfinite
from sqlmodel import Session, select
from ..clock import utc_now
from ..models import CallUsage, CallMode, CallStatus, CallSession
from .call_service import TERMINAL_STATUSES


def record_status_usage(session: Session, call, state: CallStatus, payload: dict) -> None:
    # Caller holds the call row lock in the webhook outer transaction.
    usage = session.exec(select(CallUsage).where(CallUsage.call_session_id == call.id,
                                                 CallUsage.attempt == call.attempts)).first()
    if usage is None:
        usage = CallUsage(tenant_id=call.tenant_id, call_session_id=call.id, attempt=call.attempts)
    now = utc_now()
    if state in {CallStatus.ANSWERED, CallStatus.IN_AI} and usage.answered_at is None and usage.ended_at is None:
        usage.answered_at = now
    if state == CallStatus.IN_HUMAN or state in TERMINAL_STATUSES:
        if usage.ai_ended_at is None:
            usage.ai_ended_at = now
        if call.mode == CallMode.HUMAN_ONLY:
            usage.ai_seconds = 0
        elif usage.answered_at is not None:
            usage.ai_seconds = max(0, (usage.ai_ended_at - usage.answered_at).total_seconds())
    if state in TERMINAL_STATUSES:
        usage.ended_at = usage.ended_at or now
        seconds = payload.get("billable_duration_sec")
        if type(seconds) in {int, float} and isfinite(seconds) and seconds >= 0:
            usage.telephony_seconds = float(seconds)
            usage.duration_source = "provider_cdr"
        elif usage.telephony_seconds is None and usage.answered_at is not None:
            usage.telephony_seconds = max(0, (usage.ended_at - usage.answered_at).total_seconds())
            usage.duration_source = "observed_estimate"
        elif usage.telephony_seconds is None and state in {CallStatus.NO_ANSWER, CallStatus.BUSY}:
            usage.telephony_seconds = 0
            usage.ai_seconds = 0
            usage.duration_source = "unanswered"
    session.add(usage)


def usage_by_call(session: Session, tenant_id: int, call_ids: list) -> dict:
    if not call_ids:
        return {}
    result = {}
    known_attempts = {}
    for usage in session.exec(select(CallUsage).where(CallUsage.tenant_id == tenant_id,
                                                      CallUsage.call_session_id.in_(call_ids))).all():
        known_attempts.setdefault(str(usage.call_session_id), set()).add(usage.attempt)
        row = result.setdefault(str(usage.call_session_id), dict(telephony_minutes=0.0, ai_minutes=0.0,
                         missing_duration_count=0, estimated_duration_count=0, missing_ai_duration_count=0))
        if usage.ended_at is None:
            # Running attempts have no final duration yet.
            row["missing_duration_count"] += 1
            row["missing_ai_duration_count"] += 1
            continue
        if usage.telephony_seconds is None:
            row["missing_duration_count"] += 1
        else:
            row["telephony_minutes"] += usage.telephony_seconds / 60
        if usage.ai_seconds is None:
            row["missing_ai_duration_count"] += 1
        else:
            row["ai_minutes"] += usage.ai_seconds / 60
        if usage.duration_source == "observed_estimate":
            row["estimated_duration_count"] += 1
    for call in session.exec(select(CallSession).where(CallSession.tenant_id == tenant_id, CallSession.id.in_(call_ids))).all():
        key = str(call.id)
        if key not in result:
            continue  # The reporting layer already marks calls without a ledger.
        missing_attempts = max(0, call.attempts - len(known_attempts[key]))
        result[key]["missing_duration_count"] += missing_attempts
        if call.mode != CallMode.HUMAN_ONLY:
            result[key]["missing_ai_duration_count"] += missing_attempts
    return result
