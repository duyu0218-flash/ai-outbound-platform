"""Durable prepared actions belong to the already leased AI Outbox task."""
import json
from contextvars import ContextVar
from ..schemas import AiTurnResult
from .task_queue import _owned_task
from .leases import LeaseLost

current_claim = ContextVar('current_ai_claim', default=None)


def owned(session):
    claim = current_claim.get()
    if claim is None:
        return None
    task = _owned_task(session, *claim)
    if task is None:
        raise LeaseLost('AI action claim is no longer owned')
    return task


def save_action(session, result):
    task = owned(session)
    if task is not None:
        payload = json.loads(task.payload_json)
        payload['prepared_action'] = result.model_dump(mode='json')
        payload['action_committed'] = False
        task.payload_json = json.dumps(payload, ensure_ascii=False)
        session.add(task)


def load_action():
    from ..db import session_scope
    with session_scope() as session:
        task = owned(session)
        if task is None:
            return None
        payload = json.loads(task.payload_json)
        action = payload.get('prepared_action')
        return (AiTurnResult.model_validate(action), bool(payload.get('action_committed'))) if action is not None else None


def mark_committed(session):
    task = owned(session)
    if task is not None:
        payload = json.loads(task.payload_json)
        payload['action_committed'] = True
        task.payload_json = json.dumps(payload, ensure_ascii=False)
        session.add(task)
