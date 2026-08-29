#!/usr/bin/env python3
"""Run explicit, real-line acceptance for the four primary call modes.

This script never dials unless --confirm-dial is supplied.  It uses only the
Python standard library so it can run from an operations host without installing
the application package.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


TERMINAL = {"completed", "failed", "no_answer", "busy", "voicemail"}
SCENARIOS = ("human_only", "mixed_human_first", "ai_only", "ai_handoff")


def request_json(
    base_url: str,
    path: str,
    *,
    method: str = "GET",
    body: dict[str, Any] | None = None,
    api_key: str = "",
    tenant_id: int = 1,
    bearer: str = "",
    timeout: int = 20,
) -> Any:
    headers = {"Accept": "application/json"}
    if api_key:
        headers.update({"x-api-key": api_key, "x-tenant-id": str(tenant_id)})
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    payload = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(body, ensure_ascii=False).encode()
    try:
        with urlopen(Request(f"{base_url.rstrip('/')}{path}", data=payload, headers=headers, method=method), timeout=timeout) as response:
            return json.loads(response.read().decode() or "null")
    except HTTPError as exc:
        detail = exc.read().decode(errors="replace")
        raise RuntimeError(f"{method} {path} returned HTTP {exc.code}: {detail}") from exc
    except URLError as exc:
        raise RuntimeError(f"{method} {path} failed: {exc.reason}") from exc


def event_types(events: list[dict[str, Any]]) -> set[str]:
    return {str(item.get("event_type") or "") for item in events}


def event_statuses(events: list[dict[str, Any]]) -> set[str]:
    statuses: set[str] = set()
    for event in events:
        try:
            payload = json.loads(event.get("payload") or "{}")
        except (TypeError, ValueError, json.JSONDecodeError):
            continue
        if payload.get("status"):
            statuses.add(str(payload["status"]))
    return statuses


def validate_scenario(mode: str, call: dict[str, Any], events: list[dict[str, Any]]) -> list[str]:
    failures: list[str] = []
    types = event_types(events)
    statuses = event_statuses(events)
    if call.get("status") != "completed":
        failures.append(f"terminal status is {call.get('status')}, expected completed")
    if not ({"answered", "in_ai", "waiting_human"} & statuses) and call.get("status") == "completed":
        failures.append("no answered-stage provider status was recorded")
    if not call.get("recording_url"):
        failures.append("recording callback did not persist a recording URL")
    if mode == "human_only" and "ai_decision" in types:
        failures.append("human-only call unexpectedly executed an AI decision")
    if mode in {"mixed_human_first", "ai_only", "ai_handoff"} and "ai_decision" not in types:
        failures.append("AI decision event is missing")
    if mode in {"mixed_human_first", "ai_handoff"}:
        handoff_seen = "waiting_human" in statuses or bool(call.get("handoff_reason"))
        if not handoff_seen:
            failures.append("human handoff was not observed")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description="Real carrier/PBX/ASR/TTS acceptance")
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--api-key", required=True)
    parser.add_argument("--tenant-id", type=int, default=1)
    parser.add_argument("--phone", required=True, help="controlled test handset that is safe to dial")
    parser.add_argument("--timeout-sec", type=int, default=180)
    parser.add_argument("--poll-sec", type=float, default=2.0)
    parser.add_argument("--report", default="reports/real-voice-acceptance.json")
    parser.add_argument("--confirm-dial", action="store_true", help="required acknowledgement that real calls will be placed")
    args = parser.parse_args()
    if not args.confirm_dial:
        parser.error("refusing to place real calls without --confirm-dial")

    request_json(args.base_url, "/health")
    request_json(args.base_url, "/readyz")
    results: list[dict[str, Any]] = []
    for mode in SCENARIOS:
        print(f"[START] {mode}: answer {args.phone} and follow the scenario checklist", flush=True)
        call = request_json(
            args.base_url,
            "/api/v1/calls",
            method="POST",
            body={"phone": args.phone, "mode": mode, "max_attempts": 1},
            api_key=args.api_key,
            tenant_id=args.tenant_id,
        )
        call_id = call["id"]
        deadline = time.monotonic() + max(30, args.timeout_sec)
        while time.monotonic() < deadline:
            call = request_json(
                args.base_url,
                f"/api/v1/calls/{call_id}",
                api_key=args.api_key,
                tenant_id=args.tenant_id,
            )
            if call.get("status") in TERMINAL:
                break
            time.sleep(max(0.5, args.poll_sec))
        events = request_json(
            args.base_url,
            f"/api/v1/calls/{call_id}/events?page=1&size=200",
            api_key=args.api_key,
            tenant_id=args.tenant_id,
        )
        failures = validate_scenario(mode, call, events)
        results.append({
            "mode": mode,
            "call_id": call_id,
            "status": call.get("status"),
            "event_types": sorted(event_types(events)),
            "failures": failures,
        })
        print(f"[{'PASS' if not failures else 'FAIL'}] {mode}: {call_id}", flush=True)

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "base_url": args.base_url,
        "tenant_id": args.tenant_id,
        "phone_redacted": f"***{args.phone[-4:]}",
        "passed": all(not result["failures"] for result in results),
        "results": results,
    }
    report_path = Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"report: {report_path.resolve()}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
