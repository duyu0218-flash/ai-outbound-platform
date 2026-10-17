import { assert } from "./common.mjs";
/** Static resource/ownership policy, deliberately not a capacity certification. */
export function assessProfile(config) {
  try {
    const s = config.services,
      errors = [],
      group = (p) =>
        Object.entries(s)
          .filter(([k]) => k === p || k.startsWith(p + "-"))
          .map(([, v]) => v),
      api = group("control-api"),
      ai = group("ai-worker"),
      callbacks = group("callback-worker"),
      agents = group("ai-agent"),
      media = group("media"),
      workers = [...ai, ...callbacks, s["task-worker"]];
    const need = (ok, msg) => {
        if (!ok) errors.push(msg);
      },
      truth = (v) => String(v).toLowerCase() === "true",
      env = (v) => v.environment;
    for (const [label, rows, n] of [
      ["callback", callbacks, 6],
      ["API", api, 6],
      ["AI", ai, 4],
      ["Agent", agents, 2],
      ["media", media, 12],
    ])
      need(rows.length === n, `${label}: expected ${n} processes`);
    const g = env(s["voice-gateway"]),
      r = env(s["recording-adapter"]);
    need(
      truth(g.VOICE_QUOTA_ENABLED) &&
        media.every((v) => truth(env(v).VOICE_QUOTA_ENABLED)),
      "Controller and media require shared permits",
    );
    try {
      const budgets = JSON.parse(g.VOICE_QUOTA_BUDGETS_JSON);
      for (const k of ["asr", "tts"]) {
        const b = budgets[k];
        assert(
          ["provider", "account", "region", "product"].every(
            (p) => typeof b[p] === "string" && b[p].trim(),
          ),
        );
        assert(
          Number.isInteger(b.concurrency) &&
            b.concurrency >= (k === "asr" ? 500 : 1),
        );
        if (k === "tts")
          assert(
            Number.isInteger(b.requests_per_minute) &&
              b.requests_per_minute > 0,
          );
      }
    } catch {
      errors.push("Approved ASR/TTS budgets missing or invalid");
    }
    const cleanup = g.VOICE_RECORDING_CLEANUP_TOKEN;
    need(
      typeof cleanup === "string" &&
        cleanup.length >= 32 &&
        cleanup === r.RECORDING_SOURCE_CLEANUP_TOKEN,
      "Independent matching 32+ cleanup credential required",
    );
    need(
      ![
        "MEDIA_RPC_TOKEN",
        "SERVICE_TOKEN",
        "VOICE_COMMAND_SECRET",
        "VOICE_SECURITY_ADMIN_TOKEN",
      ].some((k) => g[k] === cleanup),
      "Cleanup credential reused",
    );
    need(
      Number(g.VOICE_RECORDING_RESERVE_BYTES) > 0,
      "Positive recording reserve required",
    );
    need(
      s["voice-gateway"].volumes.filter(
        (v) => v.target === "/var/lib/freeswitch/recordings" && !v.read_only,
      ).length === 1,
      "Writable owner recording mount required",
    );
    const specs = JSON.parse(g.MEDIA_WORKERS_JSON);
    need(
      Number(g.VOICE_MAX_CONCURRENT) === 500 &&
        Number(g.PIPECAT_MAX_ACTIVE_SESSIONS) === 600,
      "500 calls and 600 media slots required",
    );
    need(
      specs.length === 12 && specs.reduce((a, b) => a + b.capacity, 0) === 600,
      "12 media workers and 600 slots required",
    );
    need(
      JSON.stringify(specs.map((v) => v.id).sort()) ===
        JSON.stringify(media.map((v) => env(v).MEDIA_WORKER_ID).sort()),
      "Media identity mismatch",
    );
    for (const spec of specs) {
      const e = env(s[spec.id]);
      need(
        Number(e.MEDIA_WORKER_CAPACITY) === spec.capacity &&
          e.PIPECAT_MEDIA_WS_BASE === spec.ws_base,
        "Media capacity/address mismatch " + spec.id,
      );
    }
    const backend = [...api, ...workers],
      connections = backend.reduce(
        (a, v) =>
          a +
          Number(env(v).DATABASE_POOL_SIZE) +
          Number(env(v).DATABASE_MAX_OVERFLOW ?? 0),
        0,
      );
    need(
      Number.isFinite(connections) && connections <= 64,
      "Application DB pool budget exceeds 64",
    );
    for (const row of backend) {
      const e = env(row);
      need(
        truth(e.CALLBACK_INBOX_ENABLED) &&
          Number(e.CALLBACK_INBOX_MIN_WORKERS) === workers.length,
        "Complete durable callback coverage required",
      );
      need(
        truth(e.STABILITY_ADMISSION_ENABLED) &&
          Number(e.STABILITY_MIN_AI_WORKERS) === workers.length,
        "Complete AI lane stability coverage required",
      );
    }
    need(
      truth(g.VOICE_CALLBACK_BATCH_ENABLED) &&
        Number(g.VOICE_CALLBACK_BATCH_SIZE) >= 1 &&
        Number(g.VOICE_CALLBACK_BATCH_SIZE) <= 16 &&
        Number(g.VOICE_CALLBACK_BATCH_DELAY_MS) >= 0 &&
        Number(g.VOICE_CALLBACK_BATCH_DELAY_MS) <= 5,
      "Bounded callback batches required",
    );
    for (const row of api) {
      const e = env(row);
      need(
        Number(e.REQUEST_ADMISSION_TOTAL_INFLIGHT) <=
          Number(e.DATABASE_POOL_SIZE) &&
          Number(e.REQUEST_ADMISSION_WEBHOOK_INFLIGHT) <
            Number(e.REQUEST_ADMISSION_TOTAL_INFLIGHT),
        "API admission must fit pool and reserve management capacity",
      );
    }
    const callbackSlots = Number(g.VOICE_CALLBACK_CONCURRENCY),
      apiSlots = api.reduce(
        (a, v) => a + Number(env(v).REQUEST_ADMISSION_WEBHOOK_INFLIGHT),
        0,
      );
    need(
      callbackSlots <= apiSlots,
      "Callback delivery exceeds webhook admission",
    );
    const ports = new Set();
    for (const row of backend) {
      const e = env(row),
        p = Number(e.PORT);
      need(
        Number.isInteger(p) && !ports.has(p),
        "Unique valid Java port required",
      );
      ports.add(p);
      need(
        JSON.stringify(row.command) ===
          JSON.stringify(["java", "-jar", "/app/control.jar"]) &&
          JSON.stringify(row.healthcheck.test) ===
            JSON.stringify([
              "CMD",
              "java",
              "-jar",
              "/app/control.jar",
              "--healthcheck",
            ]),
        "Core jar/healthcheck required",
      );
    }
    need(
      workers.every((v) => env(v).JAVA_WORKERS_ENABLED === "true") &&
        api.every((v) => env(v).JAVA_WORKERS_ENABLED === "false"),
      "Explicit worker ownership required",
    );
    need(
      s.redis.healthcheck.test.includes("--tls"),
      "Redis TLS probe required",
    );
    const quotas = new Set(
      agents.map((v) =>
        JSON.stringify(
          [
            "LLM_QUOTA_DB_PATH",
            "LLM_QUOTA_SCOPE",
            "LLM_QUOTA_RPM",
            "LLM_QUOTA_TPM",
            "LLM_QUOTA_RPS",
          ].map((k) => env(v)[k] ?? ""),
        ),
      ),
    );
    need(
      quotas.size === 1 &&
        agents.every((v) =>
          [
            "LLM_QUOTA_DB_PATH",
            "LLM_QUOTA_SCOPE",
            "LLM_QUOTA_RPM",
            "LLM_QUOTA_TPM",
            "LLM_QUOTA_RPS",
          ].every((k) => env(v)[k]),
        ),
      "Shared account quota settings required",
    );
    const sources = new Set();
    for (const row of agents) {
      const m = row.volumes.filter((v) => v.target === "/var/lib/model-quota");
      need(
        m.length === 1 &&
          !m[0].read_only &&
          m[0].source.endsWith("model_quota"),
        "Writable quota volume required",
      );
      if (m.length) sources.add(m[0].type + ":" + m[0].source);
    }
    need(sources.size === 1, "Agents must share quota volume");
    need(
      Object.values(s).filter((v) => env(v)?.JAVA_DISPATCH_ENABLED === "true")
        .length === 1,
      "Exactly one dispatcher required",
    );
    const cpu = Object.values(s).reduce((a, v) => a + Number(v.cpus ?? 0), 0),
      memory =
        Object.values(s).reduce((a, v) => a + Number(v.mem_limit ?? 0), 0) /
        1024 ** 3;
    need(
      Number.isFinite(cpu) &&
        Number.isFinite(memory) &&
        cpu <= 32 &&
        memory <= 56,
      "Host resource budget exceeded",
    );
    for (const [name, row] of Object.entries(s)) {
      need(row.network_mode === "host", name + ": Linux host network required");
      if (
        /^(control-api|ai-agent|media-)/.test(name) ||
        name === "voice-gateway"
      )
        need(
          env(row).JAVA_BIND_ADDRESS === "127.0.0.1",
          name + ": loopback binding required",
        );
    }
    return {
      static_config_passed: !errors.length,
      runtime: "java",
      customer_inflight_limit: 500,
      media_slots: specs.reduce((a, v) => a + v.capacity, 0),
      ai_slots: workers.length,
      application_db_connections: connections,
      cpu_limits_total: cpu,
      callback_delivery_slots: callbackSlots,
      api_callback_slots: apiSlots,
      memory_limits_gib: memory,
      blockers: errors,
      real_500_call_capacity_verified: false,
      whole_host_high_availability: false,
    };
  } catch {
    return {
      static_config_passed: false,
      blockers: ["Invalid/incomplete rendered Compose config"],
      real_500_call_capacity_verified: false,
    };
  }
}
