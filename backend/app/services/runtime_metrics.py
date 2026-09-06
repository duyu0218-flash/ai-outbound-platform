from __future__ import annotations

import math
import threading
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from collections import defaultdict, deque
from typing import Any, Deque, Dict


_lock = threading.Lock()

_db_wait_seconds: Deque[float] = deque(maxlen=5000)
_db_checked_out = 0
_db_checkout_wait_records = 0
_db_checkout_wait_sum = 0.0
_db_checkout_failures = 0
_execution_threads = 0
_late_commit_attempts = 0


@dataclass
class RequestExecution:
    timed_out: bool = False


request_execution: ContextVar[RequestExecution | None] = ContextVar("request_execution", default=None)


@contextmanager
def execution_threads():
    global _execution_threads
    with _lock:
        _execution_threads += 1
    try:
        yield
    finally:
        with _lock:
            _execution_threads -= 1


def record_late_commit():
    global _late_commit_attempts
    state = request_execution.get()
    if state and state.timed_out:
        with _lock:
            _late_commit_attempts += 1

_request_inflight: Dict[str, int] = defaultdict(int)
_request_timeouts: Dict[str, int] = defaultdict(int)
_request_timeout_total = 0
_admission_wait_seconds: Dict[str, Deque[float]] = defaultdict(lambda: deque(maxlen=2000))
_admission_rejections: Dict[str, int] = defaultdict(int)
_webhook_duplicate_events: Dict[str, int] = defaultdict(int)
_outbox_duplicate_tasks: Dict[str, int] = defaultdict(int)


def _quantile(samples: Deque[float], q: float) -> float | None:
    if not samples:
        return None
    sorted_samples = sorted(samples)
    if not sorted_samples:
        return None
    index = max(0, min(len(sorted_samples) - 1, math.ceil(len(sorted_samples) * q) - 1))
    return sorted_samples[index]


def record_db_wait(wait_seconds: float, *, failed: bool = False) -> None:
    global _db_checkout_wait_records, _db_checkout_wait_sum, _db_checkout_failures
    with _lock:
        _db_wait_seconds.append(max(0.0, float(wait_seconds)))
        _db_checkout_wait_records += 1
        _db_checkout_wait_sum += float(wait_seconds)
        _db_checkout_failures += int(failed)


def record_db_checkout() -> None:
    global _db_checked_out
    with _lock:
        _db_checked_out += 1


def record_db_checkin() -> None:
    global _db_checked_out
    with _lock:
        if _db_checked_out > 0:
            _db_checked_out -= 1


def record_request_inflight(bucket: str, delta: int) -> None:
    with _lock:
        _request_inflight[bucket] = max(0, _request_inflight[bucket] + delta)


def record_request_timeout(bucket: str) -> None:
    global _request_timeout_total
    with _lock:
        _request_timeouts[bucket] += 1
        _request_timeout_total += 1


def record_admission_wait(bucket: str, seconds: float, accepted: bool) -> None:
    with _lock:
        _admission_wait_seconds[bucket].append(max(0.0, float(seconds)))


def record_admission_reject(bucket: str, reason: str) -> None:
    with _lock:
        _admission_rejections[f"{bucket}:{reason}"] += 1


def record_webhook_duplicate(event_type: str) -> None:
    with _lock:
        _webhook_duplicate_events[event_type if event_type in {"status", "transcript", "speech", "media", "recording", "sms_status"} else "other"] += 1


def record_outbox_duplicate(task_type: str) -> None:
    with _lock:
        _outbox_duplicate_tasks[task_type if task_type in {"ai_turn", "business_callback", "recording_ingest", "recording_delete"} else "other"] += 1


def snapshot() -> dict[str, Any]:
    with _lock:
        return {
            "db_wait_seconds": list(_db_wait_seconds),
            "db_checkout_wait_records": _db_checkout_wait_records,
            "db_checkout_wait_sum": _db_checkout_wait_sum,
            "db_checked_out": _db_checked_out,
            "db_checkout_failures": _db_checkout_failures,
            "execution_threads": _execution_threads,
            "late_commit_attempts": _late_commit_attempts,
            "request_inflight": dict(_request_inflight),
            "request_timeouts": dict(_request_timeouts),
            "request_timeout_total": _request_timeout_total,
            "admission_wait_seconds": {bucket: list(samples) for bucket, samples in _admission_wait_seconds.items()},
            "admission_rejections": dict(_admission_rejections),
            "webhook_duplicates": dict(_webhook_duplicate_events),
            "outbox_duplicates": dict(_outbox_duplicate_tasks),
        }


def snapshot_for_metrics() -> list[tuple[str, str, float | int]]:
    now = snapshot()
    lines: list[tuple[str, str, float | int]] = []
    lines.append(("gauge", "ai_outbound_db_pool_checked_out", now["db_checked_out"]))
    lines.append(("counter", "ai_outbound_db_pool_checkout_failures_total", now["db_checkout_failures"]))
    lines.append(("gauge", "ai_outbound_webhook_execution_threads", now["execution_threads"]))
    lines.append(("counter", "ai_outbound_commit_after_timeout_attempts_total", now["late_commit_attempts"]))

    db_wait = now["db_wait_seconds"]
    for q in (0.5, 0.95, 0.99):
        value = _quantile(deque(db_wait), q)
        if value is not None:
            lines.append(("gauge", f'ai_outbound_db_pool_wait_seconds{{quantile="{q}"}}', round(value, 6)))
    lines.append(("counter", "ai_outbound_db_pool_wait_count", now["db_checkout_wait_records"]))
    lines.append(("counter", "ai_outbound_db_pool_wait_seconds_sum", round(now["db_checkout_wait_sum"], 6)))

    for bucket in sorted(now["request_inflight"]):
        lines.append(("gauge", f'ai_outbound_http_inflight{{bucket="{bucket}"}}', float(now["request_inflight"][bucket])))

    for bucket in sorted(now["request_timeouts"]):
        lines.append(("counter", f'ai_outbound_request_timeouts_total{{bucket="{bucket}"}}', now["request_timeouts"][bucket]))
    lines.append(("counter", "ai_outbound_request_timeout_all_total", now["request_timeout_total"]))

    for bucket in sorted(now["admission_wait_seconds"]):
        samples = deque(now["admission_wait_seconds"][bucket])
        for q in (0.5, 0.95, 0.99):
            value = _quantile(samples, q)
            if value is not None:
                lines.append(("gauge", f'ai_outbound_admission_wait_seconds{{bucket="{bucket}",quantile="{q}"}}', round(value, 6)))

    for key, value in sorted(now["admission_rejections"].items()):
        bucket, reason = key.split(":", 1)
        lines.append(("counter", f'ai_outbound_request_admission_rejected_total{{bucket="{bucket}",reason="{reason}"}}', value))

    for event_type, count in sorted(now["webhook_duplicates"].items()):
        lines.append(("counter", f'ai_outbound_webhook_duplicate_events_total{{event_type="{event_type}"}}', count))

    for task_type, count in sorted(now["outbox_duplicates"].items()):
        lines.append(("counter", f'ai_outbound_outbox_duplicate_tasks_total{{task_type="{task_type}"}}', count))

    return lines
