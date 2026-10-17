import {
  path,
  ROOT,
  java,
  port,
  http,
  wait,
  Processes,
  temporary,
  writeJSON,
  sha,
  crypto,
  assert,
} from "./lib/common.mjs";
const jar = path.join(
    ROOT,
    "services-java/target/outbound-services-0.1.0-SNAPSHOT.jar",
  ),
  p = await port(),
  origin = "http://127.0.0.1:" + p,
  token = crypto.randomBytes(32).toString("base64url"),
  headers = { Authorization: "Bearer " + token },
  processes = new Processes(),
  checks = [];
await temporary("java-only-media-", async (folder) => {
  let generation = 0;
  async function start() {
    const process = processes.start(
      "media-" + generation++,
      java(),
      ["-jar", jar],
      {
        ENV: "test",
        JAVA_SERVICE_ROLE: "media",
        JAVA_BIND_ADDRESS: "127.0.0.1",
        PORT: String(p),
        MEDIA_WORKER_ID: "isolated-worker",
        MEDIA_WORKER_CAPACITY: "2",
        MEDIA_RPC_TOKEN: token,
        MEDIA_CONTROL_URL: "http://127.0.0.1:1",
        PIPECAT_OPENAI_API_KEY: "synthetic-unused-key",
        PIPECAT_STT_PROVIDER: "openai-realtime",
        VOICE_QUOTA_ENABLED: "false",
      },
      folder,
    );
    let state;
    await wait(async () => {
      const r = await http(origin, "/internal/state", {
        headers,
        timeout: 2000,
      });
      if (r.status === 200) {
        state = r.json;
        return true;
      }
    }, 60);
    return { process, state };
  }
  const command = (epoch, action, s, extra = {}) =>
    http(origin, "/internal/command", {
      method: "POST",
      headers,
      body: {
        epoch,
        action,
        call_id: s.call_id,
        session_id: s.session_id,
        attempt: s.metadata.attempt,
        operation_id: crypto.randomUUID(),
        expires_at: Date.now() / 1000 + 5,
        ...extra,
      },
    });
  try {
    const { process, state } = await start(),
      epoch = state.epoch;
    assert.equal(state.worker_id, "isolated-worker");
    assert.equal(state.capacity, 2);
    assert.equal(state.metrics.pcm_input_frames_total, 0);
    assert("tts_first_audio_seconds_sum" in state.metrics);
    assert.equal(
      (
        await http(origin, "/internal/state", {
          headers: { Authorization: "Bearer wrong" },
        })
      ).status,
      401,
    );
    checks.push("worker state, metrics and RPC authentication");
    const s = {
      call_id: "isolated-call",
      session_id: crypto.randomUUID(),
      token: crypto.randomBytes(32).toString("base64url"),
      speech_webhook_url: "",
      media_webhook_url: "",
      metadata: { tenant_id: 1, attempt: 1 },
    };
    for (let i = 0; i < 2; i++)
      assert.equal(
        (await command(epoch, "create", s, { session: s })).status,
        200,
      );
    assert(
      s.call_id in
        (await http(origin, "/internal/state", { headers })).json.sessions,
    );
    checks.push("create/retry retains one generation");
    for (const [e, action, extra] of [
      ["stale-epoch", "close", {}],
      [epoch, "close", { session_id: "stale-session" }],
      [epoch, "close", { attempt: 2 }],
      [epoch, "fence", { expected_speech_event_id: "stale-speech" }],
      [epoch, "close", { expires_at: Date.now() / 1000 - 1 }],
    ])
      assert.equal((await command(e, action, s, extra)).status, 409);
    checks.push("epoch/session/attempt/speech/expiry fences");
    for (let i = 0; i < 2; i++)
      assert.equal(
        (await command(epoch, "close", s, { notify: false })).status,
        200,
      );
    assert.equal(
      (await command(epoch, "create", s, { session: s })).status,
      409,
    );
    checks.push("close retries cannot resurrect generation");
    s.session_id = crypto.randomUUID();
    assert.equal(
      (await command(epoch, "create", s, { session: s })).status,
      200,
    );
    await processes.stop(process, "SIGKILL");
    const restarted = (await start()).state;
    assert.notEqual(restarted.epoch, epoch);
    assert.deepEqual(restarted.sessions, {});
    assert.equal((await command(epoch, "close", s)).status, 409);
    checks.push("actual kill/restart changes epoch");
  } finally {
    await processes.close();
  }
});
writeJSON(path.join(ROOT, "artifacts/no-python/media-process.json"), {
  passed: true,
  checks,
  services_jar_sha256: sha(jar),
  boundary:
    "Actual Java process/RPC; no real audio/cloud or physical permit release qualification.",
});
console.log("Java media process checks passed:", checks.length);
