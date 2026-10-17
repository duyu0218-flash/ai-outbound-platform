import {
  args,
  number,
  readJSON,
  writeJSON,
  fs,
  path,
  ROOT,
  sha,
  http,
  crypto,
  assert,
  sleep,
} from "./lib/common.mjs";
import { Samples } from "./lib/statistics.mjs";
export function validateLoad(a) {
  const u = new URL(a.url);
  assert(
    a["isolated-mock"] === true &&
      ["127.0.0.1", "[::1]", "::1"].includes(u.hostname) &&
      u.protocol === "http:" &&
      !u.username &&
      !u.password,
    "Only explicitly acknowledged loopback synthetic fixture permitted",
  );
  const rate = number(a.rate ?? 50, "rate", {
      min: 1,
      max: 1000,
      integer: true,
    }),
    seconds = number(a.seconds ?? 20, "seconds", {
      min: 1,
      max: 86400,
      integer: true,
    }),
    concurrency = number(a.concurrency ?? 100, "concurrency", {
      min: 1,
      max: 500,
      integer: true,
    }),
    maxWait = number(a["max-queue-wait"] ?? 5, "queue wait", {
      min: 0.1,
      max: 30,
    }),
    scenario = a.scenario ?? "read";
  assert(["read", "webhook", "conversation"].includes(scenario));
  return { url: a.url, rate, seconds, concurrency, maxWait, scenario };
}
export async function runLoad(a) {
  assert(
    typeof a.output === "string" && a.output.trim(),
    "Fresh output path required",
  );
  assert(
    !fs.existsSync(a.output),
    "Preserve existing load evidence; choose a fresh output",
  );
  const { url, rate, seconds, concurrency, maxWait, scenario } =
      validateLoad(a),
    runtime = await http(url, "/api/v1/runtime", { status: 200 });
  assert(runtime.json.demo_users_enabled === true, "Refusing non-demo fixture");
  const login = await http(url, "/api/v1/auth/login", {
      method: "POST",
      body: { username: "admin", password: "12345678" },
      status: 200,
    }),
    headers = { Authorization: "Bearer " + login.json.access_token },
    calls = a["calls-file"] ? readJSON(a["calls-file"]) : [];
  assert(
    Array.isArray(calls) &&
      calls.every((v) => typeof v === "string") &&
      new Set(calls).size === calls.length,
    "Distinct fixture call IDs required",
  );
  if (scenario === "conversation")
    assert(calls.length, "Conversation requires known synthetic calls");
  if (scenario === "webhook" && calls.length)
    assert(
      calls.length >= rate * seconds,
      "Distinct call per terminal callback required",
    );
  const secret = process.env.LOAD_WEBHOOK_SECRET,
    token = process.env.LOAD_WEBHOOK_TOKEN;
  if (scenario !== "read")
    assert(secret && token, "Synthetic callback credentials required");
  const sourceFiles = [
      "tools/load.mjs",
      ...fs
        .readdirSync(
          path.join(ROOT, "backend-java/src/main/java/com/duozhi/outbound"),
        )
        .filter((p) => p.endsWith(".java"))
        .map((p) => "backend-java/src/main/java/com/duozhi/outbound/" + p),
      ...fs
        .readdirSync(
          path.join(ROOT, "services-java/src/main/java/com/duozhi/services"),
        )
        .filter((p) => p.endsWith(".java"))
        .map((p) => "services-java/src/main/java/com/duozhi/services/" + p),
    ],
    hashes = Object.fromEntries(
      sourceFiles.map((p) => [p, sha(path.join(ROOT, p))]),
    );
  const count = rate * seconds,
    latency = new Samples(),
    waits = new Samples(),
    service = new Samples(),
    counters = {},
    pending = new Set(),
    id = crypto.randomUUID(),
    start = performance.now();
  let peak = 0,
    sent = 0;
  const increment = (k) => {
    counters[k] = (counters[k] ?? 0) + 1;
  };
  async function perform(i, scheduled) {
    const begun = performance.now(),
      waitMs = begun - scheduled;
    waits.append(Math.max(0, waitMs));
    sent++;
    peak = Math.max(peak, pending.size + 1);
    try {
      let r;
      if (scenario === "read")
        r = await http(url, "/api/v1/calls?page=1&size=20", {
          headers,
          timeout: 10000,
        });
      else {
        const call = calls.length
            ? calls[i % calls.length]
            : crypto.randomUUID(),
          payload =
            scenario === "conversation"
              ? {
                  call_id: call,
                  event_id: `${id}-${i}`,
                  attempt: 1,
                  transcript: `Synthetic run ${id}: Please explain the service details`,
                  is_final: true,
                  barge_in: true,
                }
              : {
                  call_id: call,
                  kind: "status",
                  payload: {
                    status: "completed",
                    provider_call_id: "synthetic-unknown",
                    attempt: 1,
                    event_id: `${id}-${i}`,
                  },
                },
          body = JSON.stringify(payload),
          stamp = String(Math.floor(Date.now() / 1000));
        r = await http(
          url,
          "/api/v1/webhooks/telephony/" +
            (scenario === "conversation" ? "speech" : "status"),
          {
            method: "POST",
            body,
            timeout: 10000,
            headers: {
              "Content-Type": "application/json",
              "X-Webhook-Token": token,
              "X-Webhook-Timestamp": stamp,
              "X-Webhook-Signature": crypto
                .createHmac("sha256", secret)
                .update(stamp + "." + body)
                .digest("hex"),
            },
          },
        );
      }
      increment(String(r.status));
      if (r.status >= 200 && r.status < 300)
        service.append(performance.now() - begun);
    } catch {
      increment("transport_error");
    } finally {
      latency.append(performance.now() - scheduled);
    }
  }
  // Create arrivals at their planned times; bound retained work and count all misses.
  for (let i = 0; i < count; i++) {
    const scheduled = start + (i / rate) * 1000;
    await sleep(Math.max(0, scheduled - performance.now()));
    if (performance.now() - scheduled > maxWait * 1000) {
      increment("generator_schedule_missed");
      latency.append(performance.now() - scheduled);
      continue;
    }
    if (pending.size >= concurrency) {
      increment("generator_backlog_full");
      latency.append(performance.now() - scheduled);
      continue;
    }
    const p = perform(i, scheduled);
    pending.add(p);
    p.finally(() => pending.delete(p));
  }
  await Promise.all(pending);
  const successes = Object.entries(counters)
      .filter(([k]) => /^2\d\d$/.test(k))
      .reduce((n, [, v]) => n + v, 0),
    unchanged = Object.entries(hashes).every(
      ([p, d]) => sha(path.join(ROOT, p)) === d,
    ),
    elapsed = (performance.now() - start) / 1000;
  // Durable AI results are checked separately from successful callback receipt.
  let durable = null;
  if (scenario === "conversation") {
    const targetCounts = Object.fromEntries(
        calls.map((c, index) => [
          c,
          Math.floor(count / calls.length) +
            (index < count % calls.length ? 1 : 0),
        ]),
      ),
      deadline =
        Date.now() +
        number(a["catchup-sec"] ?? 30, "catchup", { max: 600, min: 1 }) * 1000;
    const observed = {};
    while (Date.now() < deadline) {
      for (const c of calls) {
        const r = await http(
          url,
          `/api/v1/calls/${encodeURIComponent(c)}/speech-turns?final_only=true`,
          { headers, status: 200 },
        );
        const rows = r.json.items ?? r.json;
        observed[c] = Array.isArray(rows)
          ? rows.filter(
              (t) =>
                t.speaker_role === "customer" &&
                String(t.transcript ?? "").includes(id),
            ).length
          : 0;
      }
      if (calls.every((c) => observed[c] >= targetCounts[c])) break;
      await sleep(500);
    }
    durable = {
      expected: count,
      observed: Object.values(observed).reduce((x, y) => x + y, 0),
      all_final_transcripts_persisted: calls.every(
        (c) => observed[c] >= targetCounts[c],
      ),
      ai_reply_delivery_verified: false,
    };
  }
  const result = {
    schema_version: 2,
    runtime: "java",
    scope: "isolated synthetic HTTP/Java pipeline only",
    scenario,
    target_rps: rate,
    duration_sec: seconds,
    planned_requests: count,
    finished_requests: latency.count,
    sent_requests: sent,
    elapsed_sec: elapsed,
    successful_rps: successes / elapsed,
    max_in_flight: peak,
    status_counts: counters,
    scheduled_to_finish_p95_ms: latency.quantile(0.95),
    scheduled_to_finish_p99_ms: latency.quantile(0.99),
    successful_http_p95_ms: service.quantile(0.95),
    successful_http_p99_ms: service.quantile(0.99),
    generator_wait_p95_ms: waits.quantile(0.95),
    latency_lifetime_max_ms: latency.maximum,
    source_sha256: hashes,
    runtime_source_unchanged_during_test: unchanged,
    durable,
    passed:
      successes === count &&
      unchanged &&
      (durable === null || durable.all_final_transcripts_persisted),
    real_media_verified: false,
    production_capacity_verified: false,
    capacity_slo_passed: false,
    qualification_status:
      "HTTP acceptance is not media/capacity qualification; Java schema v2 is deliberately incompatible with historical capacity-pass reports",
  };
  assert(a.output, "Output required");
  fs.mkdirSync(path.dirname(a.output), { recursive: true });
  fs.writeFileSync(a.output, JSON.stringify(result, null, 2) + "\n", {
    flag: "wx",
    mode: 0o600,
  });
  return result;
}
if (process.argv[1]?.endsWith("load.mjs")) {
  const result = await runLoad(args());
  console.log(JSON.stringify(result));
  if (!result.passed) process.exitCode = 1;
}
