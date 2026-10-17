import { test } from "node:test";
import {
  fs,
  path,
  os,
  assert,
  sha,
  writeJSON,
  run,
  java,
  ROOT,
} from "../lib/common.mjs";
import { Samples, RotatingLog } from "../lib/statistics.mjs";
import { assess } from "../capacity-preflight.mjs";
import { normalize, distance, evaluate } from "../evaluate-asr.mjs";
import { validateScenario } from "../real-voice-acceptance.mjs";
import { validate, supervise } from "../soak.mjs";
import { evaluate as stability, CASES } from "../stability-evaluate.mjs";
import { validateLoad } from "../load.mjs";
function temp(fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "java-ops-tests-"));
  return Promise.resolve()
    .then(() => fn(dir))
    .finally(() => fs.rmSync(dir, { recursive: true, force: true }));
}
const manifest = (duration = 1) => ({
  argv: [
    process.execPath,
    "-e",
    'setInterval(()=>console.log("heartbeat"),100)',
  ],
  duration_seconds: duration,
  target_host: "isolated-target",
  images: Object.fromEntries(
    [
      "backend",
      "agent",
      "voice_gateway",
      "recording_adapter",
      "freeswitch",
      "postgres",
      "redis",
    ].map((k) => [k, "example/" + k + "@sha256:" + "a".repeat(64)]),
  ),
});
test("bounded samples retain lifetime max/count and conservative quantiles", () => {
  const s = new Samples(2);
  for (const n of [100, 1, 2, 3]) s.append(n);
  assert.equal(s.count, 4);
  assert.equal(s.values.length, 2);
  assert.equal(s.maximum, 100);
  assert(s.quantile(0.99) >= 100);
  assert(s.quantile(0.5) >= 3 && s.quantile(0.5) < 3.04);
  for (const n of [-1, NaN, Infinity, true]) assert.throws(() => s.append(n));
});
test("rotation preserves every byte", () =>
  temp((dir) => {
    const log = new RotatingLog(dir, 7),
      data = Buffer.from("all bytes remain完整日志");
    log.write(data);
    log.close();
    assert.deepEqual(
      Buffer.concat(
        fs
          .readdirSync(dir)
          .sort()
          .map((p) => fs.readFileSync(path.join(dir, p))),
      ),
      data,
    );
  }));
test("ASR casefold/Unicode/codepoint edits preserve prior semantics", () => {
  assert.equal(normalize("ＡＢＣ， Straße！ Σςσ 中文\n"), "abcstrasseσσσ中文");
  assert.equal(distance("你好", "你们好"), 1);
  assert.equal(distance("😀", ""), 1);
  assert.equal(distance("a", "abcdef"), 5);
});
function asrFixture(dir) {
  const audio = Buffer.from("authorized synthetic audio"),
    hashPath = path.join(dir, "audio.wav");
  fs.writeFileSync(hashPath, audio);
  const digest = sha(hashPath),
    segment = (t, role = "customer") => ({
      text: t,
      start_ms: 0,
      end_ms: 100,
      speaker_role: role,
    }),
    samples = [
      {
        id: "a",
        authorized: true,
        human_verified: true,
        audio_sha256: digest,
        audio_path: "audio.wav",
        duration_ms: 1000,
        segments: [segment("你好")],
        keywords: ["你好"],
      },
      {
        id: "b",
        authorized: true,
        human_verified: true,
        audio_sha256: digest,
        audio_path: "audio.wav",
        duration_ms: 1000,
        segments: [segment("世界欢迎")],
        keywords: [],
      },
    ],
    m = path.join(dir, "manifest.json"),
    r = path.join(dir, "results.json");
  writeJSON(m, { schema_version: 1, dataset_kind: "synthetic", samples });
  const result = {
    schema_version: 1,
    dataset_sha256: sha(m),
    provider: "fixture",
    model: "test",
    revision: "1",
    items: samples.map((s) => ({
      id: s.id,
      audio_sha256: digest,
      status: "ok",
      segments: s.segments,
      processing_ms: 100,
      cost_cny: 0,
    })),
  };
  writeJSON(r, result);
  return { m, r, result, samples, segment };
}
test("offline ASR verifies hashes, weighted CER, roles, keyword and costs", () =>
  temp((dir) => {
    const { m, r, result, segment } = asrFixture(dir);
    let p = evaluate(m, [r], { verifyAudio: true }).providers[0];
    assert.equal(p.cer, 0);
    assert.equal(p.keyword_recall, 1);
    assert.equal(p.total_cost_cny, 0);
    result.items[0].segments = [segment("你们", "agent")];
    writeJSON(r, result);
    p = evaluate(m, [r]).providers[0];
    assert.equal(p.cer, 1 / 6);
    assert.equal(p.samples[0].role_text_cer, 2);
    assert.equal(p.keyword_recall, 0);
  }));
test("missing exports stay in denominator, costs remain unknown", () =>
  temp((dir) => {
    const { m, r, result } = asrFixture(dir);
    result.items.pop();
    delete result.items[0].cost_cny;
    writeJSON(r, result);
    const p = evaluate(m, [r]).providers[0];
    assert.equal(p.cer, 4 / 6);
    assert.equal(p.failed_or_missing, 1);
    assert.equal(p.total_cost_cny, null);
  }));
for (const field of [
  "duration",
  "interval",
  "hash",
  "authorization",
  "unknown",
  "duplicate",
  "status",
  "cost",
])
  test("ASR rejects invalid " + field, () =>
    temp((dir) => {
      const { m, r, result } = asrFixture(dir);
      let data = JSON.parse(fs.readFileSync(m));
      if (field === "duration") data.samples[0].duration_ms = true;
      if (field === "authorization") data.samples[0].human_verified = false;
      if (["duration", "authorization"].includes(field)) {
        writeJSON(m, data);
        result.dataset_sha256 = sha(m);
      }
      if (field === "interval")
        result.items[0].segments = [
          { text: "bad", start_ms: 300, end_ms: 100 },
        ];
      if (field === "hash") result.dataset_sha256 = "wrong";
      if (field === "unknown") result.items[0].id = "unknown";
      if (field === "duplicate") result.items[1].id = "a";
      if (field === "status") result.items[0].status = "missing";
      if (field === "cost") result.items[0].cost_cny = -1;
      writeJSON(r, result);
      assert.throws(() => evaluate(m, [r]));
    }),
  );
test("changed audio fails verification and report duplication fails", () =>
  temp((dir) => {
    const { m, r } = asrFixture(dir);
    fs.writeFileSync(path.join(dir, "audio.wav"), "changed");
    assert.throws(() => evaluate(m, [r], { verifyAudio: true }));
    assert.throws(() => evaluate(m, [r, r]));
  }));
test("silence insertion CER has no divide by zero", () =>
  temp((dir) => {
    const { m, r, result } = asrFixture(dir),
      data = JSON.parse(fs.readFileSync(m));
    data.samples[0].segments = [];
    data.samples[0].keywords = [];
    writeJSON(m, data);
    result.dataset_sha256 = sha(m);
    writeJSON(r, result);
    const p = evaluate(m, [r]).providers[0];
    assert.equal(p.samples[0].cer, null);
    assert.equal(p.edits, 2);
  }));
const node = { id: "a", capacity: 600, cps: 10, routes: ["1:0"] },
  route = {
    cps: 10,
    max_duration_sec: 60,
    rate_minor_per_minute: 1,
    billing_multiplier: 1,
    max_concurrent: 500,
    calls_per_day: 1000000,
    hour_budget_minor: 1000000,
    day_budget_minor: 1000000,
  },
  policy = {
    node_id: "a",
    cps: 10,
    call_capacity: 550,
    daily_calls: 1000000,
    hour_budget_minor: 1000000,
    day_budget_minor: 1000000,
    routes: { "1:0": route },
  },
  work = {
    scope: "1:0",
    target: 500,
    mean_duration: 120,
    answer_rate: 0.5,
    hours: 8,
    turn_interval: 10,
    mean_ai_task: 0.1,
    ai_slots_per_host: 11,
    topology: "single-host",
    call_semantics: "inflight",
    mean_setup: 20,
  };
test("capacity is min of approved layers and inflight includes ringing", () => {
  const r = assess([node], { a: policy }, work);
  assert(r.policy_check_passed);
  assert.equal(r.effective_nodes[0].calls, 500);
  assert.equal(r.required.cps, 500 / 80);
  assert(!r.n_minus_one_budget_checked);
  assert(!r.whole_host_failover_verified);
});
test("capacity rejects missing identity, zero pricing, NaN and excess CPS", () => {
  assert(!assess([node], {}, work).policy_check_passed);
  assert(
    !assess([{ ...node, cps: 100 }], { a: policy }, work).policy_check_passed,
  );
  assert(
    !assess(
      [node],
      {
        a: {
          ...policy,
          routes: { "1:0": { ...route, rate_minor_per_minute: 0 } },
        },
      },
      work,
    ).policy_check_passed,
  );
  assert.throws(() =>
    assess([node], { a: policy }, { ...work, mean_ai_task: NaN }),
  );
});
test("cluster N minus one and measured Java AI occupancy are enforced", () => {
  assert(
    !assess([node], { a: policy }, { ...work, topology: "cluster" })
      .policy_check_passed,
  );
  assert(
    !assess([node], { a: policy }, { ...work, mean_ai_task: 10 })
      .policy_check_passed,
  );
});
const event = (status) => ({
    event_type: "status",
    payload: JSON.stringify({ status }),
  }),
  turn = {
    is_final: true,
    normalized_transcript: "valid",
    asr_provider: "fixture",
    confidence: 0.9,
    start_ms: 0,
    end_ms: 100,
  };
test("real acceptance requires three finals, provider, timing, metrics and confirmed handoff", () => {
  const call = { status: "completed", recording_url: "s3://fixture" },
    events = [event("in_ai"), { event_type: "ai_decision" }],
    turns = [turn, turn, turn],
    metrics = [{ stage: "asr.final", success: true }];
  assert.deepEqual(
    validateScenario("ai_only", call, events, turns, metrics, {
      expectedAsrProvider: "fixture",
    }),
    [],
  );
  assert(validateScenario("ai_only", call, events, [turn], metrics).length);
  assert(
    validateScenario("ai_only", call, events, turns, [
      { stage: "asr.final", success: false },
    ]).length,
  );
  assert(
    validateScenario("ai_only", call, events, turns, metrics, {
      expectedAsrProvider: "wrong",
    }).length,
  );
  assert(
    validateScenario(
      "ai_handoff",
      call,
      [...events, event("waiting_for_agent")],
      [turn],
      metrics,
    ).length,
  );
  assert.deepEqual(
    validateScenario(
      "mixed_human_first",
      call,
      [...events, event("in_human")],
      [],
      [],
    ),
    [],
  );
});
test("real dialing tool refuses missing confirmation before network", () => {
  assert.throws(() =>
    run(process.execPath, [
      path.join(ROOT, "tools/real-voice-acceptance.mjs"),
      "--phone",
      "12025550123",
    ]),
  );
});
test("load refuses external targets and invalid/unbounded scheduling", () => {
  for (const a of [
    { url: "https://example.com", "isolated-mock": true },
    { url: "http://127.0.0.1", rate: 0, "isolated-mock": true },
    {
      url: "http://127.0.0.1",
      rate: 1,
      seconds: 999999,
      "isolated-mock": true,
    },
    { url: "http://127.0.0.1" },
  ])
    assert.throws(() => validateLoad(a));
  assert.equal(
    validateLoad({ url: "http://127.0.0.1", "isolated-mock": true }).rate,
    50,
  );
});
test("soak rejects short qualification, same host and mutable images", () => {
  assert.throws(() => validate(manifest()));
  const m = manifest(28800);
  m.target_host = "localhost";
  assert.throws(() => validate(m));
  m.target_host = "independent";
  m.images.redis = "redis:latest";
  assert.throws(() => validate(m));
  m.argv = "shell command";
  assert.throws(() => validate(m, true));
});
test("soak observes duration, preserves journal and does not certify capacity", () =>
  temp(async (dir) => {
    const r = await supervise(manifest(), path.join(dir, "run"), {
      smoke: true,
    });
    assert(r.supervision_completed);
    assert.equal(r.real_500_capacity_verified, false);
    const events = fs
      .readFileSync(path.join(dir, "run/journal.jsonl"), "utf8")
      .trim()
      .split("\n")
      .map(JSON.parse);
    assert.equal(events[0].event, "started");
    assert.equal(events.at(-1).event, "finished");
    await assert.rejects(
      supervise(manifest(), path.join(dir, "run"), { smoke: true }),
    );
  }));
test("early workload exit never passes", () =>
  temp(async (dir) => {
    const m = manifest();
    m.argv = [process.execPath, "-e", "process.exit(0)"];
    const r = await supervise(m, path.join(dir, "early"), { smoke: true });
    assert(!r.supervision_completed);
  }));
test("stability evaluator rejects missing, duplicate, wrong revisions and invalid latency", () =>
  temp((dir) => {
    assert(!stability({}, dir).repeated_synthetic_passed);
    const m = { cases: {} };
    let sequence = 0;
    for (const [name, [scenario, rate, length]] of Object.entries(CASES)) {
      m.cases[name] = [];
      for (let i = 0; i < 5; i++) {
        const file = `${name}-${i}.json`;
        m.cases[name].push(file);
        writeJSON(path.join(dir, file), {
          sequence: sequence++,
          source_sha256: { core: "same" },
          scenario,
          initial_speech_start_rate: rate,
          final_transcripts_per_second: rate,
          conversation_rounds: length,
          duration_seconds: length,
          correctness_passed: true,
          capacity_slo_passed: true,
          runtime_source_unchanged_during_test: true,
          conversation_correctness_passed: true,
          conversation_control_slo_passed: true,
          synthetic_reply_p99_ms: 100,
          deadline_miss_count: 0,
          gateway_delivery_max_ms: 10,
          final_transcripts:
            scenario === "conversation" ? 500 * length : rate * length,
          inbox_final: {
            processed:
              (scenario === "conversation" ? 500 * length : rate * length) * 3,
            max_completion_latency_ms: 10,
            pending: 0,
            dead: 0,
          },
          pending_callbacks: 0,
        });
      }
    }
    assert(stability(m, dir).repeated_synthetic_passed);
    m.cases.dialogue50[1] = m.cases.dialogue50[0];
    assert(!stability(m, dir).repeated_synthetic_passed);
  }));
