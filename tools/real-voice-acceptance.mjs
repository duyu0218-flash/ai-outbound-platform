import { fs, path, args, number, http, sleep, assert } from "./lib/common.mjs";
export const SCENARIOS = [
  "human_only",
  "mixed_human_first",
  "ai_only",
  "ai_handoff",
];
export function validateScenario(
  mode,
  call,
  events,
  speechTurns,
  metrics,
  { expectedAsrProvider = "" } = {},
) {
  assert(SCENARIOS.includes(mode), "Unknown mode");
  const failures = [],
    types = new Set(events.map((e) => String(e.event_type ?? ""))),
    statuses = new Set();
  for (const event of events) {
    try {
      const p =
        typeof event.payload === "string"
          ? JSON.parse(event.payload || "{}")
          : event.payload;
      if (p?.status) statuses.add(String(p.status));
    } catch {}
  }
  if (call.status !== "completed")
    failures.push("Terminal status must be completed");
  if (
    !["answering", "answered", "in_ai", "human_connected", "in_human"].some(
      (s) => statuses.has(s),
    ) &&
    call.status === "completed"
  )
    failures.push("No answered-stage provider status");
  if (!call.recording_url) failures.push("Recording callback missing");
  if (mode === "human_only" && types.has("ai_decision"))
    failures.push("Human-only executed AI decision");
  if (mode !== "human_only" && !types.has("ai_decision"))
    failures.push("AI decision missing");
  if (["ai_only", "ai_handoff"].includes(mode)) {
    const final = speechTurns.filter(
      (t) => t.is_final && String(t.normalized_transcript ?? "").trim(),
    );
    if (final.length < (mode === "ai_only" ? 3 : 1))
      failures.push("Insufficient nonempty ASR final turns");
    const m = metrics.filter((v) => v.stage === "asr.final");
    if (!m.length || m.some((v) => v.success !== true))
      failures.push("Missing/failed asr.final metric");
    if (expectedAsrProvider) {
      if (
        new Set(final.map((t) => String(t.asr_provider ?? ""))).size !== 1 ||
        final.some((t) => t.asr_provider !== expectedAsrProvider)
      )
        failures.push("ASR provider mismatch");
      if (
        final.length &&
        !final.some((t) => t.confidence !== null && t.confidence !== undefined)
      )
        failures.push("ASR confidence missing");
      if (
        final.length &&
        !final.some(
          (t) =>
            t.start_ms !== null &&
            t.start_ms !== undefined &&
            t.end_ms !== null &&
            t.end_ms !== undefined,
        )
      )
        failures.push("ASR timestamps missing");
    }
  }
  if (
    ["mixed_human_first", "ai_handoff"].includes(mode) &&
    !["human_connected", "in_human"].some((s) => statuses.has(s))
  )
    failures.push("Human connection not confirmed by provider");
  return failures;
}
if (process.argv[1]?.endsWith("real-voice-acceptance.mjs")) {
  const a = args();
  assert(
    a["confirm-dial"] === true,
    "Refusing real calls without --confirm-dial",
  );
  assert(
    typeof a.phone === "string" && /^\+?[0-9]{7,15}$/.test(a.phone),
    "Explicit controlled handset required",
  );
  assert(
    a["api-key-file"],
    "Use --api-key-file; no credentials in process arguments",
  );
  const base = a["base-url"] ?? "http://127.0.0.1:8000",
    headers = {
      "X-API-Key": fs.readFileSync(a["api-key-file"], "utf8").trim(),
      "X-Tenant-ID": String(
        number(a["tenant-id"] ?? 1, "tenant", { min: 1, integer: true }),
      ),
    };
  if (a["security-approval-file"])
    headers["X-Security-Approval"] = fs
      .readFileSync(a["security-approval-file"], "utf8")
      .trim();
  const timeout = number(a["timeout-sec"] ?? 180, "timeout", {
      min: 30,
      max: 3600,
    }),
    poll = number(a["poll-sec"] ?? 2, "poll", { min: 0.5, max: 60 }),
    reportPath = a.report ?? "reports/real-voice-acceptance.json";
  fs.mkdirSync(path.dirname(reportPath), { recursive: true });
  const fd = fs.openSync(reportPath, "wx", 0o600);
  let report = {
    started_at: new Date().toISOString(),
    passed: false,
    results: [],
    boundary:
      "Requires human/trace review in addition to API observations; never proves 500-call capacity.",
  };
  try {
    await http(base, "/health", { status: 200 });
    await http(base, "/readyz", { status: 200 });
    for (const mode of SCENARIOS) {
      console.log("Controlled handset scenario:", mode);
      let call = (
        await http(base, "/api/v1/calls", {
          method: "POST",
          headers,
          status: 200,
          body: { phone: a.phone, mode, max_attempts: 1 },
        })
      ).json;
      const id = call.id,
        deadline = Date.now() + timeout * 1000;
      while (Date.now() < deadline) {
        call = (
          await http(base, "/api/v1/calls/" + id, { headers, status: 200 })
        ).json;
        if (
          ["completed", "failed", "no_answer", "busy", "voicemail"].includes(
            call.status,
          )
        )
          break;
        await sleep(poll * 1000);
      }
      const get = async (suffix) =>
          (
            await http(base, "/api/v1/calls/" + id + suffix, {
              headers,
              status: 200,
            })
          ).json,
        events = await get("/events?page=1&size=200"),
        turns = await get("/speech-turns?final_only=true"),
        metrics = await get("/metrics");
      const failures = validateScenario(
        mode,
        call,
        events.items ?? events,
        turns.items ?? turns,
        metrics.items ?? metrics,
        { expectedAsrProvider: a["expected-asr-provider"] ?? "" },
      );
      report.results.push({
        mode,
        call_id: id,
        passed: !failures.length,
        failures,
      });
    }
    report.passed =
      report.results.length === 4 && report.results.every((r) => r.passed);
  } finally {
    fs.writeFileSync(fd, JSON.stringify(report, null, 2) + "\n");
    fs.fsyncSync(fd);
    fs.closeSync(fd);
  }
  if (!report.passed) process.exitCode = 1;
}
