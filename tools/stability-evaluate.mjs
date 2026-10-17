import { fs, path, args, readJSON, sha, assert } from "./lib/common.mjs";
export const CASES = {
  dialogue50: ["conversation", 50, 10],
  dialogue80: ["conversation", 80, 5],
  dialogue100: ["conversation", 100, 5],
  dialogue125: ["conversation", 125, 5],
  callback600: ["mixed", 200, 120],
  callback1200: ["mixed", 400, 30],
};
export function evaluate(manifest, base) {
  const results = [],
    seen = new Set();
  let hashes;
  for (const [name, [scenario, rate, length]] of Object.entries(CASES)) {
    const files = manifest.cases?.[name] ?? [],
      errors = [];
    if (!Array.isArray(files) || files.length < 5)
      errors.push("Requires five independent reports");
    for (const relative of Array.isArray(files) ? files : []) {
      try {
        const p = fs.realpathSync(path.resolve(base, relative)),
          digest = sha(p);
        if (seen.has(p) || seen.has(digest)) errors.push("Duplicate report");
        seen.add(p);
        seen.add(digest);
        const d = readJSON(p),
          source = d.source_sha256;
        assert(
          source && typeof source === "object" && Object.keys(source).length,
        );
        const sorted = JSON.stringify(Object.entries(source).sort());
        if (hashes === undefined) hashes = sorted;
        else if (hashes !== sorted) errors.push("Source revisions differ");
        if (
          d.scenario !== scenario ||
          d.initial_speech_start_rate !== rate ||
          d.final_transcripts_per_second !== rate ||
          (scenario === "conversation"
            ? d.conversation_rounds
            : d.duration_seconds) !== length
        )
          errors.push("Wrong scenario/rate/duration");
        const flags = [
          "correctness_passed",
          "capacity_slo_passed",
          "runtime_source_unchanged_during_test",
          ...(scenario === "conversation"
            ? [
                "conversation_correctness_passed",
                "conversation_control_slo_passed",
              ]
            : []),
        ];
        if (flags.some((k) => d[k] !== true))
          errors.push("Missing/failed correctness/SLO flag");
        if (
          scenario === "conversation" &&
          (d.synthetic_reply_p99_ms > 4200 ||
            (d.deadline_miss_count ?? d.deadline_misses?.length) !== 0)
        )
          errors.push("Reply latency or schedule missed");
        const latencies = [
          d.gateway_delivery_max_ms,
          d.inbox_final?.max_completion_latency_ms,
          ...(scenario === "conversation" ? [d.synthetic_reply_p99_ms] : []),
        ];
        if (
          latencies.some(
            (v) => typeof v !== "number" || !Number.isFinite(v) || v < 0,
          )
        )
          errors.push("Invalid latency");
        const expected =
          scenario === "conversation" ? 500 * length : rate * length;
        if (
          d.final_transcripts !== expected ||
          d.inbox_final?.processed !== expected * 3
        )
          errors.push("Offered load incomplete");
        if (
          d.gateway_delivery_max_ms > 1000 ||
          d.inbox_final?.max_completion_latency_ms > 1000
        )
          errors.push("Delivery/inbox latency exceeded");
        if (
          d.inbox_final?.pending !== 0 ||
          d.inbox_final?.dead !== 0 ||
          d.pending_callbacks !== 0
        )
          errors.push("Unfinished events/dead letters");
      } catch {
        errors.push("Unreadable/invalid report");
      }
    }
    results.push({
      case: name,
      runs: files.length,
      passed: !errors.length,
      errors: [...new Set(errors)].sort(),
    });
  }
  return {
    cases: results,
    repeated_synthetic_passed: results.every((r) => r.passed),
    production_capacity_verified: false,
    real_media_verified: false,
    long_soak_verified: false,
    fault_recovery_verified: false,
  };
}
if (process.argv[1]?.endsWith("stability-evaluate.mjs")) {
  const a = args(),
    r = evaluate(readJSON(a.manifest), path.dirname(a.manifest));
  console.log(JSON.stringify(r, null, 2));
  if (!r.repeated_synthetic_passed) process.exitCode = 1;
}
