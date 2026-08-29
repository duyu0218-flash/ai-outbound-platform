from __future__ import annotations

import json
from sqlmodel import Session, select

from ..clock import utc_now
from ..models import CallAnalysis, CallSession, CallStatus, SpeechTurn


POSITIVE_WORDS = ("愿意", "可以", "需要", "有兴趣", "同意", "好的")
NEGATIVE_WORDS = ("不要", "拒绝", "没兴趣", "投诉", "别打", "不需要")
RISK_WORDS = ("投诉", "骚扰", "报警", "删除号码", "别再打")


def analyze_call(session: Session, call: CallSession) -> CallAnalysis:
    turns = session.exec(
        select(SpeechTurn)
        .where(SpeechTurn.call_session_id == call.id, SpeechTurn.is_final.is_(True))
        .order_by(SpeechTurn.turn_index.asc(), SpeechTurn.created_at.asc())
    ).all()
    customer_text = " ".join(t.transcript for t in turns if t.speaker_role == "customer").strip()
    full_text = customer_text or (call.summary or call.last_transcript or "")
    has_positive = any(word in full_text for word in POSITIVE_WORDS)
    has_negative = any(word in full_text for word in NEGATIVE_WORDS)
    risks = [word for word in RISK_WORDS if word in full_text]

    if call.status == CallStatus.NO_ANSWER:
        result_code, intent = "no_answer", "unreached"
    elif call.status == CallStatus.BUSY:
        result_code, intent = "busy", "retry_later"
    elif has_negative:
        result_code, intent = "rejected", "not_interested"
    elif has_positive:
        result_code, intent = "interested", "positive_lead"
    elif call.status == CallStatus.COMPLETED:
        result_code, intent = "completed", "unclear"
    else:
        result_code, intent = "failed", "unknown"

    sentiment = "negative" if has_negative else "positive" if has_positive else "neutral"
    qa_flags: list[str] = []
    if not turns:
        qa_flags.append("missing_structured_transcript")
    if risks:
        qa_flags.append("customer_compliance_risk")
    if call.recording_url is None:
        qa_flags.append("missing_recording")
    qa_score = max(0, 100 - len(qa_flags) * 20)
    structured = {
        "turn_count": len(turns),
        "customer_turn_count": sum(1 for t in turns if t.speaker_role == "customer"),
        "risk_keywords": risks,
        "status": call.status.value,
    }
    summary = full_text[:1000] if full_text else f"通话状态：{call.status.value}，暂无有效转写。"

    analysis = session.exec(
        select(CallAnalysis).where(CallAnalysis.call_session_id == call.id)
    ).first()
    if analysis is None:
        analysis = CallAnalysis(tenant_id=call.tenant_id, call_session_id=call.id)
    analysis.result_code = result_code
    analysis.sentiment = sentiment
    analysis.intent = intent
    analysis.summary = summary
    analysis.qa_score = qa_score
    analysis.qa_flags_json = json.dumps(qa_flags, ensure_ascii=False)
    analysis.structured_json = json.dumps(structured, ensure_ascii=False)
    analysis.updated_at = utc_now()
    session.add(analysis)
    session.commit()
    session.refresh(analysis)
    return analysis
