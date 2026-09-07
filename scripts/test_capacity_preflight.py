import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("capacity_preflight", Path(__file__).with_name("capacity-preflight.py"))
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


def fixture():
    nodes = [{"id": str(i), "capacity": 200, "cps": 10, "routes": ["1:0"]} for i in range(4)]
    route = dict(max_concurrent=200, cps=10, calls_per_day=100000, hour_budget_minor=100000000,
                 day_budget_minor=100000000, max_duration_sec=120, rate_minor_per_minute=1, billing_multiplier=2)
    policies = {str(i): dict(node_id=str(i), call_capacity=200, cps=10, daily_calls=100000,
                            hour_budget_minor=100000000, day_budget_minor=100000000,
                            routes={"1:0": dict(route)}) for i in range(4)}
    return nodes, policies


def assess(nodes, policies, mean_ai_task=.5):
    return preflight.assess(nodes, policies, scope="1:0", target=500, mean_duration=120,
                           answer_rate=.5, hours=8, turn_interval=4, mean_ai_task=mean_ai_task)


def test_capacity_is_minimum_of_approved_layers():
    nodes, policies = fixture()
    policies["0"]["routes"]["1:0"]["max_concurrent"] = 1
    result = assess(nodes, policies)
    assert not result["policy_check_passed"]
    assert result["effective_nodes"][0]["calls"] == 1
    assert result["unverified"]


def test_ai_budget_uses_measured_occupancy_and_n_minus_one():
    nodes, policies = fixture()
    assert assess(nodes, policies)["policy_check_passed"]
    slow = assess(nodes, policies, mean_ai_task=3)
    assert not slow["policy_check_passed"]
    assert slow["required"]["ai_slots"] == 536
    assert slow["scenarios"]["without_0"]["ai_slots"] == 192


def test_missing_node_identity_fails_closed():
    nodes, policies = fixture()
    policies["0"] = {}
    result = assess(nodes, policies)
    assert not result["policy_check_passed"]
    assert any("identity" in error for error in result["blockers"])
