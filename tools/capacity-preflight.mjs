import { fs, args, number, readJSON, assert } from "./lib/common.mjs";
import { request } from "./fleet.mjs";
export function assess(
  nodes,
  policies,
  {
    scope,
    target,
    mean_duration,
    answer_rate,
    hours,
    turn_interval,
    mean_ai_task,
    ai_slots_per_host,
    topology = "cluster",
    call_semantics = "connected",
    mean_setup = 20,
  },
) {
  assert(
    ["cluster", "single-host"].includes(topology) &&
      ["connected", "inflight"].includes(call_semantics),
  );
  for (const [k, v] of Object.entries({
    target,
    mean_duration,
    hours,
    turn_interval,
    mean_ai_task,
    ai_slots_per_host,
  }))
    assert(Number.isFinite(v) && v > 0, k);
  assert(
    target >= 1 &&
      Number.isInteger(ai_slots_per_host) &&
      hours <= 24 &&
      answer_rate > 0 &&
      answer_rate <= 1 &&
      mean_setup >= 0 &&
      Number.isFinite(mean_setup),
  );
  const errors = [],
    effective = [];
  for (const n of nodes) {
    if (n.enabled === false || !n.routes.includes(scope)) continue;
    const p = policies[n.id] ?? {},
      r = p.routes?.[scope];
    if (p.node_id !== n.id || !r) {
      errors.push(n.id + ": identity/approved route missing");
      continue;
    }
    const keys = [
      "cps",
      "call_capacity",
      "daily_calls",
      "hour_budget_minor",
      "day_budget_minor",
    ];
    if (
      keys.some((k) => !Number.isFinite(p[k]) || p[k] < 0) ||
      [
        "cps",
        "max_duration_sec",
        "rate_minor_per_minute",
        "billing_multiplier",
        "max_concurrent",
        "calls_per_day",
        "hour_budget_minor",
        "day_budget_minor",
      ].some((k) => !Number.isFinite(r[k]) || r[k] < 0)
    ) {
      errors.push(n.id + ": invalid budget");
      continue;
    }
    const allowed = Math.min(p.cps, r.cps),
      reserve =
        Math.ceil(r.max_duration_sec / 60) *
        r.rate_minor_per_minute *
        r.billing_multiplier;
    if (!(n.cps > 0 && n.cps <= allowed))
      errors.push(n.id + ": roster CPS exceeds approval");
    if (reserve <= 0) {
      errors.push(n.id + ": invalid reservation price");
      continue;
    }
    effective.push({
      node_id: n.id,
      ai_slots: ai_slots_per_host,
      calls: Math.min(n.capacity, p.call_capacity, r.max_concurrent),
      cps: Math.min(n.cps, allowed),
      daily: Math.min(p.daily_calls, r.calls_per_day),
      hour_budget_calls: Math.floor(
        Math.min(p.hour_budget_minor, r.hour_budget_minor) / reserve,
      ),
      day_budget_calls: Math.floor(
        Math.min(p.day_budget_minor, r.day_budget_minor) / reserve,
      ),
    });
  }
  if (topology === "single-host" && effective.length !== 1)
    errors.push("single-host requires exactly one authorized PBX identity");
  const cps =
      call_semantics === "inflight"
        ? target / (mean_setup + answer_rate * mean_duration)
        : target / mean_duration / answer_rate,
    daily = Math.ceil(cps * hours * 3600),
    required = {
      calls: target,
      cps,
      daily,
      ai_slots: Math.ceil(((target / turn_interval) * mean_ai_task) / 0.7),
      hour_budget_calls: Math.ceil(cps * 3600),
      day_budget_calls: daily,
    },
    scenarios = {};
  for (const omitted of [
    null,
    ...(topology === "cluster" ? effective.map((n) => n.node_id) : []),
  ]) {
    const totals = Object.fromEntries(
        Object.keys(required).map((k) => [
          k,
          effective
            .filter((n) => n.node_id !== omitted)
            .reduce((a, n) => a + n[k], 0),
        ]),
      ),
      name = omitted === null ? "all_nodes" : "without_" + omitted;
    scenarios[name] = totals;
    for (const [k, need] of Object.entries(required))
      if (!Number.isFinite(totals[k]) || totals[k] < need)
        errors.push(`${name}: ${k}=${totals[k]} < required ${need}`);
  }
  return {
    policy_check_passed: !errors.length,
    topology,
    call_semantics,
    n_minus_one_budget_checked: topology === "cluster",
    whole_host_failover_verified: false,
    required,
    effective_nodes: effective,
    scenarios,
    blockers: errors,
    unverified: [
      "tenant/campaign/line limits",
      "PBX sessions and human legs",
      "ASR/TTS streams and LLM limits",
      "actual Java lane occupancy and tail latency",
      "real media/500-call/failover qualification",
    ],
  };
}
if (process.argv[1]?.endsWith("capacity-preflight.mjs")) {
  const a = args(),
    nodes = readJSON(a.roster),
    token = fs.readFileSync(a["admin-token-file"], "utf8").trim(),
    policies = {};
  for (const n of nodes)
    if (n.enabled !== false && n.routes.includes(a.scope)) {
      try {
        policies[n.id] = await request(n.endpoint, "/v1/admin/capacity", token);
      } catch {
        policies[n.id] = {};
      }
    }
  const r = assess(nodes, policies, {
    scope: a.scope,
    target: number(a.target ?? 500, "target", { min: 1 }),
    mean_duration: number(a["mean-duration-sec"], "duration", { min: 0.001 }),
    answer_rate: number(a["answer-rate"], "answer rate", {
      min: 0.0001,
      max: 1,
    }),
    hours: number(a.hours ?? 8, "hours", { min: 0.001, max: 24 }),
    turn_interval: number(a["turn-interval-sec"], "turn interval", {
      min: 0.001,
    }),
    mean_ai_task: number(a["mean-ai-task-sec"], "AI task duration", {
      min: 0.001,
    }),
    ai_slots_per_host: number(a["ai-slots-per-host"], "actual Java AI lanes", {
      min: 1,
      integer: true,
    }),
    topology: a.topology ?? "cluster",
    call_semantics: a["call-semantics"] ?? "connected",
    mean_setup: number(a["mean-setup-sec"] ?? 20, "setup"),
  });
  console.log(JSON.stringify(r, null, 2));
  if (!r.policy_check_passed) process.exitCode = 1;
}
