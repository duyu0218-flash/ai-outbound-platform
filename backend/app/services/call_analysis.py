from __future__ import annotations

import json
from sqlmodel import Session, select

from ..clock import utc_now
from ..models import CallAnalysis, CallSession, CallStatus, SpeechTurn, ConversationState
from .dialogue_rules import classify


POSITIVE_WORDS = ("愿意", "可以", "需要", "有兴趣", "同意", "好的")
NEGATIVE_WORDS = ("不要", "拒绝", "没兴趣", "投诉", "别打", "不需要")
RISK_WORDS = ("投诉", "骚扰", "报警", "删除号码", "别再打")


def analyze_call(session: Session, call: CallSession) -> CallAnalysis:
    if session.get_bind().dialect.name == "sqlite":
        from sqlalchemy import update
        session.exec(update(CallSession).where(CallSession.id == call.id).values(updated_at=CallSession.updated_at))
    session.refresh(call, with_for_update=True)
    turns = session.exec(
        select(SpeechTurn)
        .where(SpeechTurn.call_session_id == call.id, SpeechTurn.is_final.is_(True), SpeechTurn.attempt == call.attempts)
        .order_by(SpeechTurn.turn_index.asc(), SpeechTurn.created_at.asc())
    ).all()
    customer_text = " ".join(t.transcript for t in turns if t.speaker_role == "customer").strip()
    full_text = customer_text
    state = session.exec(select(ConversationState).where(ConversationState.call_id == call.id,
        ConversationState.attempt == call.attempts)).first()
    from ..product_schemas import ScenarioPolicy
    threshold=ScenarioPolicy.model_validate_json(state.policy_json).confidence_threshold if state else 0.55
    decisions = [classify(t.transcript) for t in turns if t.speaker_role == 'customer' and (t.confidence is None or t.confidence>=threshold)]
    meaningful = [d for d in decisions if d in {'interested','rejected','stop_contact','wrong_person','permission_to_listen','callback'}]
    final_intent = 'stop_contact' if 'stop_contact' in meaningful else meaningful[-1] if meaningful else 'unclear'
    has_positive = final_intent == 'interested'
    has_negative = final_intent in {'rejected','stop_contact','wrong_person'}
    product = json.loads(state.data_json) if state else {}
    risks = [word for word in RISK_WORDS if word in full_text]

    if call.status == CallStatus.NO_ANSWER:
        result_code, intent = "no_answer", "unreached"
    elif call.status == CallStatus.BUSY:
        result_code, intent = "busy", "retry_later"
    elif final_intent in {'stop_contact','wrong_person'}:
        result_code, intent = final_intent, final_intent
    elif product.get('outcome') in {'qualified_lead','callback_confirmed','non_human','no_response','service_failure','handoff_timeout'}:
        result_code = intent = product['outcome']
    elif has_negative:
        result_code, intent = "rejected", "not_interested"
    elif has_positive:
        result_code, intent = "interested", "needs_qualification"
    elif call.status == CallStatus.COMPLETED:
        result_code, intent = "completed", "unclear"
    else:
        result_code, intent = "failed", "unknown"

    sentiment = "negative" if has_negative else "positive" if has_positive else "neutral"
    qa_flags: list[str] = []
    if not turns:
        qa_flags.append("missing_structured_transcript")
    if any(t.confidence is not None and t.confidence<threshold for t in turns if t.speaker_role=='customer'):
        qa_flags.append('low_confidence_transcript')
    if risks:
        qa_flags.append("customer_compliance_risk")
    if call.recording_url is None:
        qa_flags.append("missing_recording")
    if result_code == 'interested' or intent == 'unclear':
        qa_flags.append('qualification_unconfirmed')
    qa_score = max(0, 100 - len(qa_flags) * 20)
    structured = {
        "turn_count": len(turns),
        "customer_turn_count": sum(1 for t in turns if t.speaker_role == "customer"),
        "risk_keywords": risks,
        "status": call.status.value,
        "attempt": call.attempts,
        "unstructured_evidence": call.summary if not turns else None,
        "policy_version_id": state.policy_version_id if state else None,
        "answer_kind": product.get('answer_kind','unknown'),
        "slots": product.get('slots',{}),
        "evidence": [{'turn_id':t.id,'text':t.transcript,'start_ms':t.start_ms,'end_ms':t.end_ms}
                     for t in turns if t.speaker_role == 'customer'],
        "delivery_state": 'recording_pending' if call.recording_url is None else 'review_pending' if qa_flags else 'ready',
    }
    summary = full_text[:1000] if full_text else f"通话状态：{call.status.value}，暂无有效转写。"

    analysis = session.exec(
        select(CallAnalysis).where(CallAnalysis.call_session_id == call.id)
    ).first()
    if analysis is None:
        analysis = CallAnalysis(tenant_id=call.tenant_id, call_session_id=call.id)
    automatic = json.dumps(dict(result_code=result_code, sentiment=sentiment, intent=intent,
                                summary=summary, qa_score=qa_score, qa_flags=qa_flags,
                                structured=structured), ensure_ascii=False, sort_keys=True)
    if analysis.review_state == "reviewed":
        if analysis.automatic_result_json != automatic:
            analysis.needs_review = True
        analysis.automatic_result_json = automatic
        analysis.updated_at = utc_now()
        session.add(analysis)
        publish_analysis(session,call,analysis)
        session.refresh(analysis)
        return analysis
    analysis.automatic_result_json = automatic
    analysis.result_code = result_code
    analysis.sentiment = sentiment
    analysis.intent = intent
    analysis.summary = summary
    analysis.qa_score = qa_score
    analysis.qa_flags_json = json.dumps(qa_flags, ensure_ascii=False)
    analysis.structured_json = json.dumps(structured, ensure_ascii=False)
    analysis.updated_at = utc_now()
    session.add(analysis)
    publish_analysis(session,call,analysis)
    session.refresh(analysis)
    return analysis


def publish_analysis(session, call, analysis):
    """Analysis and its versioned delivery outbox commit together."""
    from .task_queue import enqueue_business_callback
    import hashlib
    structured=json.loads(analysis.structured_json or '{}')
    if analysis.needs_review:
        structured['delivery_state']='review_pending'
    elif analysis.review_state=='reviewed':
        structured['delivery_state']='ready' if call.recording_url else 'recording_pending'
    data={'result_code':analysis.result_code,'intent':analysis.intent,'attempt':call.attempts,
          'structured':structured,'review_state':analysis.review_state,'needs_review':analysis.needs_review,
          'automatic_result':json.loads(analysis.automatic_result_json or '{}'),
          'reviewed_by':analysis.reviewed_by,'reviewed_at':str(analysis.reviewed_at or '')}
    version=hashlib.sha256(json.dumps(data,ensure_ascii=False,sort_keys=True,default=str).encode()).hexdigest()
    session.add(analysis)
    session.flush()
    enqueue_business_callback(session,tenant_id=call.tenant_id,call_id=call.id,event_type='call.result',
        data={**data,'version':version},idempotency_key=f'result:{call.id}:{version}')
    session.commit()
