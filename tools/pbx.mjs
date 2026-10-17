import { args, number, xml, descendants, assert } from "./lib/common.mjs";
export function assessCore(
  file,
  { calls = 200, legsPerCall = 2, spareSessions = 100, cps = 10 } = {},
) {
  for (const [k, v] of Object.entries({ calls, legsPerCall, cps }))
    assert(Number.isInteger(v) && v > 0, k);
  assert(Number.isInteger(spareSessions) && spareSessions >= 0);
  const root = xml(file),
    configs = descendants(root, "configuration").filter(
      (n) => n.attrs.name === "switch.conf",
    ),
    values = {};
  assert(configs.length === 1, "Exactly one expanded switch.conf required");
  for (const p of descendants(configs[0], "param"))
    if (
      [
        "max-sessions",
        "sessions-per-second",
        "rtp-start-port",
        "rtp-end-port",
      ].includes(p.attrs.name)
    ) {
      assert(!(p.attrs.name in values), "Duplicate core parameter");
      assert(/^\d+$/.test(p.attrs.value), "Resolved numeric value required");
      values[p.attrs.name] = Number(p.attrs.value);
    }
  const needed = calls * legsPerCall + spareSessions,
    errors = [];
  if ((values["max-sessions"] ?? 0) < needed)
    errors.push("Insufficient PBX sessions");
  if ((values["sessions-per-second"] ?? 0) < cps * legsPerCall)
    errors.push("Insufficient PBX CPS");
  const start = values["rtp-start-port"] ?? 0,
    end = values["rtp-end-port"] ?? 0;
  if (
    !(
      start >= 1024 &&
      start < end &&
      end <= 65535 &&
      end - start + 1 >= needed * 2
    )
  )
    errors.push("Insufficient RTP port budget");
  return {
    static_config_passed: !errors.length,
    target_customer_calls: calls,
    required_pbx_sessions: needed,
    configured: values,
    blockers: errors,
    runtime_and_audio_verified: false,
  };
}
if (process.argv[1]?.endsWith("pbx.mjs")) {
  const a = args(),
    r = assessCore(a["expanded-core-xml"], {
      calls: number(a.calls ?? 200, "calls", { min: 1, integer: true }),
      legsPerCall: number(a["legs-per-call"] ?? 2, "legs", {
        min: 1,
        integer: true,
      }),
      spareSessions: number(a["spare-sessions"] ?? 100, "spare", {
        integer: true,
      }),
      cps: number(a.cps ?? 10, "cps", { min: 1, integer: true }),
    });
  console.log(JSON.stringify(r, null, 2));
  if (!r.static_config_passed) process.exitCode = 1;
}
