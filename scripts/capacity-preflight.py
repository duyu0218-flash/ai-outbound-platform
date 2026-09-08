#!/usr/bin/env python3
"""Read effective gateway policies and compare them to an explicit workload.

No dialing, configuration mutation, or claim of measured media capacity.
Run from the fleet's private network; token contents never appear in output.
"""
import argparse
import importlib.util
import json
import math
from pathlib import Path


def assess(nodes, policies, *, scope, target, mean_duration, answer_rate, hours,
           turn_interval, mean_ai_task, ai_slots_per_host=64, topology='cluster',
           call_semantics='connected', mean_setup=20):
    if topology not in {'cluster', 'single-host'} or call_semantics not in {'connected', 'inflight'}:
        raise ValueError('invalid topology or call semantics')
    if (target < 1 or min(mean_duration, turn_interval, mean_ai_task, ai_slots_per_host) <= 0
            or not 0 < answer_rate <= 1 or not 0 < hours <= 24 or mean_setup < 0):
        raise ValueError('invalid workload')
    errors, effective = [], []
    for node in nodes:
        if not node.get("enabled", True) or scope not in node["routes"]:
            continue
        policy = policies.get(node["id"], {})
        route = policy.get("routes", {}).get(scope)
        if policy.get("node_id") != node["id"] or not route:
            errors.append(f"{node['id']}: identity or approved route missing")
            continue
        paced = node.get("cps", 0)
        allowed_cps = min(policy["cps"], route["cps"])
        if not 0 < paced <= allowed_cps:
            errors.append(f"{node['id']}: roster CPS must be 1..{allowed_cps}")
        reserve = math.ceil(route["max_duration_sec"] / 60) * route["rate_minor_per_minute"] * route["billing_multiplier"]
        effective.append({"node_id": node["id"],
            "ai_slots": ai_slots_per_host,
            "calls": min(node["capacity"], policy["call_capacity"], route["max_concurrent"]),
            "cps": min(paced, allowed_cps),
            "daily": min(policy["daily_calls"], route["calls_per_day"]),
            "hour_budget_calls": min(policy["hour_budget_minor"], route["hour_budget_minor"]) // reserve,
            "day_budget_calls": min(policy["day_budget_minor"], route["day_budget_minor"]) // reserve})
    if topology == 'single-host' and len(effective) != 1:
        errors.append('single-host requires exactly one enabled authorized PBX/controller identity')
    required_cps = (target / (mean_setup + answer_rate * mean_duration)
                    if call_semantics == 'inflight' else target / mean_duration / answer_rate)
    required_daily = math.ceil(required_cps * hours * 3600)
    required = {"calls": target, "cps": required_cps, "daily": required_daily,
                "ai_slots": math.ceil(target / turn_interval * mean_ai_task / .7),
                "hour_budget_calls": math.ceil(required_cps * 3600), "day_budget_calls": required_daily}
    scenarios = {}
    for omitted in [None] + ([item["node_id"] for item in effective] if topology == 'cluster' else []):
        totals = {key: sum(item[key] for item in effective if item["node_id"] != omitted) for key in required}
        name = "all_nodes" if omitted is None else "without_" + omitted
        scenarios[name] = totals
        for key, need in required.items():
            if totals[key] < need:
                errors.append(f"{name}: {key}={totals[key]} < required {need:.2f}")
    return {"policy_check_passed": not errors, "topology": topology, "call_semantics": call_semantics,
            "n_minus_one_budget_checked": topology == 'cluster',
            "whole_host_failover_verified": False,
            "required": required, "effective_nodes": effective,
            "scenarios": scenarios, "blockers": errors,
            "unverified": ["tenant/campaign/backend line limits", "PBX sessions including human legs",
                "ASR/TTS concurrent streams and LLM RPM/TPM", "AI task latency and first audio",
                "real 200/500-call audio and failover acceptance", "cross-PBX agent registration",
                "per-node recording source reachability"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--roster", required=True)
    parser.add_argument("--admin-token-file", required=True)
    parser.add_argument("--scope", required=True, help="tenant_id:line_id")
    parser.add_argument("--target", type=int, default=500)
    parser.add_argument("--mean-duration-sec", type=float, required=True)
    parser.add_argument("--answer-rate", type=float, required=True)
    parser.add_argument("--hours", type=float, default=8)
    parser.add_argument("--turn-interval-sec", type=float, required=True, help="measured seconds between final transcripts per call")
    parser.add_argument("--mean-ai-task-sec", type=float, required=True, help="measured task occupancy, excluding playback continuation")
    parser.add_argument("--ai-slots-per-host", type=int, default=64)
    parser.add_argument('--topology', choices=['cluster', 'single-host'], default='cluster')
    parser.add_argument('--call-semantics', choices=['connected', 'inflight'], default='connected')
    parser.add_argument('--mean-setup-sec', type=float, default=20,
                        help='mean dialing/ringing duration per attempt, for inflight semantics')
    args = parser.parse_args()
    if (args.target < 1 or args.mean_duration_sec <= 0 or not 0 < args.answer_rate <= 1 or not 0 < args.hours <= 24
            or args.turn_interval_sec <= 0 or args.mean_ai_task_sec <= 0 or args.ai_slots_per_host < 1
            or args.mean_setup_sec < 0):
        parser.error("positive target/duration, answer rate in (0,1], hours in (0,24] required")
    spec = importlib.util.spec_from_file_location("fleet", Path(__file__).with_name("compact-fleet.py"))
    fleet = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fleet)
    nodes = json.loads(Path(args.roster).read_text())
    token = Path(args.admin_token_file).read_text().strip()
    policies = {}
    for node in nodes:
        if node.get("enabled", True) and args.scope in node["routes"]:
            try:
                policies[node["id"]] = fleet.request(node["endpoint"], "/v1/admin/capacity", token)
            except Exception:
                policies[node["id"]] = {}
    result = assess(nodes, policies, scope=args.scope, target=args.target,
                    mean_duration=args.mean_duration_sec, answer_rate=args.answer_rate, hours=args.hours,
                    turn_interval=args.turn_interval_sec, mean_ai_task=args.mean_ai_task_sec,
                    ai_slots_per_host=args.ai_slots_per_host, topology=args.topology,
                    call_semantics=args.call_semantics, mean_setup=args.mean_setup_sec)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["policy_check_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
