import net from "node:net";
import {
  fs,
  path,
  ROOT,
  args,
  run,
  http,
  wait,
  writeJSON,
  sha,
  crypto,
  assert,
  sleep,
  temporary,
} from "./lib/common.mjs";
const a = args(),
  mode = a.role ?? "services",
  prefix = "java-only-check-" + crypto.randomBytes(5).toString("hex"),
  names = [],
  volumes = [],
  networks = [],
  checks = [];
const docker = (...argv) => run("docker", argv, { timeout: 180000 });
const auth = crypto.randomBytes(32).toString("base64url"),
  cleanup = crypto.randomBytes(32).toString("base64url"),
  key = crypto.randomBytes(32).toString("base64url");
const headers = { Authorization: "Bearer " + auth };
function start(role, env, image, command = [], extra = []) {
  const name = prefix + "-" + role;
  const params = [
    "run",
    "-d",
    "--name",
    name,
    "--label",
    "codex.task=no-python-check",
  ];
  if (role === "s3")
    params.push("--network", prefix, "--tmpfs", "/data:rw,size=256m");
  else
    params.push(
      "--network",
      prefix,
      "--read-only",
      "--cap-drop=ALL",
      "--security-opt=no-new-privileges:true",
      "--tmpfs",
      "/tmp:rw,noexec,nosuid,size=128m,uid=10001,gid=10001",
      "--tmpfs",
      "/recordings:rw,noexec,nosuid,size=64m,uid=10001,gid=10001",
      "--tmpfs",
      "/var/spool/recordings:rw,noexec,nosuid,size=64m,uid=10001,gid=10001",
      "-p",
      "127.0.0.1::" + (role === "core" ? "8000" : "8003"),
    );
  for (const [k, v] of Object.entries(env)) params.push("-e", k + "=" + v);
  docker(...params, ...extra, image, ...command);
  names.push(name);
  return name;
}
function endpoint(name, port = 8003) {
  const mapping = JSON.parse(
    docker("inspect", "--format", "{{json .NetworkSettings.Ports}}", name),
  );
  return "http://127.0.0.1:" + mapping[port + "/tcp"][0].HostPort;
}
async function ready(name, p = 8003) {
  const url = endpoint(name, p);
  await wait(
    async () =>
      (await http(url, "/readyz", { headers, timeout: 2000 })).status === 200,
    90,
  );
  docker(
    "exec",
    name,
    "java",
    "-jar",
    p === 8000 ? "/app/control.jar" : "/app/services.jar",
    "--healthcheck",
  );
  return url;
}
async function duplicateHeaders(base, token) {
  const u = new URL(base);
  for (const name of [
    "Authorization",
    "X-API-Key",
    "X-Tenant-ID",
    "X-Security-Approval",
    "X-Webhook-Token",
    "X-Webhook-Timestamp",
    "X-Webhook-Signature",
    "Origin",
  ]) {
    const raw = await new Promise((resolve, reject) => {
      const s = net.connect(Number(u.port), u.hostname),
        chunks = [];
      s.setTimeout(5000, () => s.destroy(Error("Socket timeout")));
      s.on("error", reject);
      s.on("data", (b) => chunks.push(b));
      s.on("end", () => resolve(Buffer.concat(chunks).toString()));
      s.on("connect", () =>
        s.end(
          `GET /api/v1/auth/me HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n${name === "Authorization" ? "" : "Authorization: Bearer " + token + "\r\n"}${name}: ${name === "Authorization" ? "Bearer " + token : "first"}\r\n${name}: second\r\n\r\n`,
        ),
      );
    });
    assert(
      /^HTTP\/1\.1 400\b/.test(raw),
      name + ": duplicate must be rejected",
    );
    assert(/x-content-type-options: nosniff/i.test(raw));
  }
}
try {
  docker("network", "create", "--label", "codex.task=no-python-check", prefix);
  networks.push(prefix);
  if (mode === "core") {
    const image = a.image ?? "java-core-ci";
    assert.equal(
      docker("image", "inspect", "--format", "{{.Config.User}}", image),
      "10001:10001",
    );
    const name = start(
        "core",
        {
          ENV: "test",
          JAVA_DATABASE_URL: "jdbc:sqlite:/tmp/core.db",
          JAVA_INITIALIZE_SCHEMA: "true",
          DEMO_USERS_ENABLED: "true",
          JWT_SECRET: "isolated-container-ci-secret-never-production",
          CORS_ALLOW_ORIGINS: "https://trusted.example",
          TELEPHONY_PROVIDER: "mock",
          JAVA_DISPATCH_ENABLED: "true",
          JAVA_WORKERS_ENABLED: "true",
        },
        image,
      ),
      base = endpoint(name, 8000);
    let token;
    await wait(async () => {
      const r = await http(base, "/api/v1/auth/login", {
        method: "POST",
        body: { username: "admin", password: "12345678" },
        timeout: 2000,
      });
      token = r.json?.access_token;
      return r.status === 200 && token;
    }, 90);
    assert(
      (await http(base, "/admin/login", { status: 200 })).text
        .toLowerCase()
        .includes("<html"),
    );
    assert.equal(
      (await http(base, "/healthz", { status: 200 })).json.status,
      "ok",
    );
    await duplicateHeaders(base, token);
    checks.push("actual Tomcat duplicate credentials, frontend and login");
    const h = { Authorization: "Bearer " + token };
    for (const origin of [
      "https://trusted.example",
      "https://trusted.example.evil",
    ]) {
      const r = await http(base, "/api/v1/auth/me", {
        headers: { ...h, Origin: origin },
        status: 200,
      });
      assert.equal(
        r.headers.get("access-control-allow-origin"),
        origin === "https://trusted.example" ? origin : null,
      );
      assert.equal(r.headers.get("access-control-allow-credentials"), null);
    }
    assert.equal((await http(base, "/readyz")).status, 503);
    checks.push("strict CORS and default release gate");
    const api = async (method, p, body) =>
        (await http(base, p, { method, body, headers: h, status: 200 })).json,
      data = (await api("GET", "/api/v1/admin/settings/compliance")).data;
    Object.assign(data, { allowed_start_hour: 0, allowed_end_hour: 0 });
    await api("PUT", "/api/v1/admin/settings/compliance", { data });
    const contact = await api("POST", "/api/v1/contacts", {
        phone: "13900000088",
        name: "Container synthetic contact",
        consent_state: "consented",
      }),
      call = await api("POST", "/api/v1/calls", {
        contact_id: contact.id,
        mode: "human_only",
        max_attempts: 1,
      });
    await wait(
      async () =>
        (await api("GET", "/api/v1/calls/" + call.id)).status === "completed",
      40,
    );
    await wait(
      async () =>
        (await api("GET", "/api/v1/calls/" + call.id + "/analysis")) !== null,
      30,
    );
    checks.push(
      "contact, actual mock dispatch/callback and persisted analysis",
    );
  } else {
    assert.equal(mode, "services");
    const image =
        a.image ?? process.env.JAVA_SERVICES_TEST_IMAGE ?? "java-services-ci",
      imageId = docker("image", "inspect", "--format", "{{.Id}}", image);
    assert.equal(
      docker("image", "inspect", "--format", "{{.Config.User}}", image),
      "10001:10001",
    );
    const ledger = prefix + "-ledger";
    docker("volume", "create", "--label", "codex.task=no-python-check", ledger);
    volumes.push(ledger);
    const s3 = start(
      "s3",
      {
        AWS_ACCESS_KEY_ID: "java-isolated-access",
        AWS_SECRET_ACCESS_KEY: "java-isolated-secret",
        S3_BUCKET: "java-isolated-recordings",
      },
      "chrislusf/seaweedfs:4.44",
      [
        "mini",
        "-dir=/data",
        "-ip=" + prefix + "-s3",
        "-ip.bind=0.0.0.0",
        "-master.volumeSizeLimitMB=8",
      ],
    );
    await wait(() => {
      try {
        docker(
          "exec",
          s3,
          "wget",
          "-q",
          "--spider",
          "http://127.0.0.1:9333/cluster/status",
        );
        return true;
      } catch {
        return false;
      }
    }, 60);
    const common = {
        ENV: "test",
        PORT: "8003",
        SERVICE_TOKEN: auth,
        METRICS_TOKEN: "isolated-container-metrics",
      },
      voiceEnv = {
        ...common,
        JAVA_SERVICE_ROLE: "voice",
        VOICE_GATEWAY_DRIVER: "mock",
        VOICE_SECURITY_DB_PATH: "/var/lib/voice-security/ledger.sqlite3",
        FREESWITCH_RECORDING_DIR: "/recordings",
        VOICE_NODE_ID: "container-node",
        VOICE_COMMAND_SECRET: key,
        VOICE_RECORDING_CLEANUP_TOKEN: cleanup,
      },
      voice = start(
        "voice",
        voiceEnv,
        image,
        [],
        ["-v", ledger + ":/var/lib/voice-security"],
      ),
      voiceUrl = await ready(voice);
    checks.push("nonroot/read-only/noexec voice with persistent SQLite");
    await temporary("java-fleet-cli-", async (folder) => {
      const roster = path.join(folder, "nodes.json"),
        credential = path.join(folder, "token");
      writeJSON(roster, [{ id: "container-node", endpoint: voiceUrl }]);
      fs.writeFileSync(credential, auth, { mode: 0o600 });
      const command = (action) =>
        run(process.execPath, [
          path.join(ROOT, "tools/fleet.mjs"),
          action,
          "--roster",
          roster,
          ...(action === "status"
            ? []
            : [
                "--node",
                "container-node",
                "--admin-token-file",
                credential,
                "--wait-seconds",
                "2",
              ]),
        ]);
      assert(JSON.parse(command("status")).identity_matches);
      assert(command("drain").includes('"safe_to_stop":true'));
      await http(voiceUrl, "/readyz", { status: 503 });
      command("resume");
      await ready(voice);
    });
    checks.push(
      "actual fleet identity/status, durable drain and explicit resume CLI",
    );
    const agent = start(
        "agent",
        {
          ...common,
          JAVA_SERVICE_ROLE: "agent",
          LLM_PROVIDER: "rule",
          LLM_QUOTA_DB_PATH: "/tmp/quota.sqlite",
        },
        image,
      ),
      agentUrl = await ready(agent);
    await http(agentUrl, "/agent/start", {
      method: "POST",
      body: {},
      status: 401,
    });
    assert(
      (
        await http(agentUrl, "/agent/start", {
          method: "POST",
          headers,
          body: {
            call_id: "synthetic-container",
            phone: "+8613900139000",
            mode: "ai_only",
          },
          status: 200,
        })
      ).json.tts_text,
    );
    checks.push("Agent HTTP authentication and greeting");
    const recording = start(
        "recording",
        {
          ...common,
          JAVA_SERVICE_ROLE: "recording",
          S3_ENDPOINT_URL: "http://" + s3 + ":8333",
          S3_BUCKET: "java-isolated-recordings",
          S3_ACCESS_KEY_ID: "java-isolated-access",
          S3_SECRET_ACCESS_KEY: "java-isolated-secret",
          RECORDING_SPOOL_DIR: "/var/spool/recordings",
          RECORDING_SOURCE_ALLOWED_HOSTS: voice,
          RECORDING_MAX_BYTES: "16384",
          RECORDING_SPOOL_RESERVE_BYTES: "0",
          RECORDING_SOURCE_CLEANUP_ORIGIN: "http://" + voice + ":8003",
          RECORDING_SOURCE_CLEANUP_TOKEN: cleanup,
        },
        image,
      ),
      recordingUrl = await ready(recording),
      audio = Buffer.from("RIFF-isolated-container-wave-data"),
      filename = "synthetic-fixture.wav";
    for (const [suffix, data] of [
      ["", audio],
      [
        ".complete",
        Buffer.from(
          JSON.stringify({ completed_at: 0, size_bytes: audio.length }),
        ),
      ],
    ])
      run(
        "docker",
        ["exec", "-i", voice, "tee", "/recordings/" + filename + suffix],
        { input: data },
      );
    const expires = Math.floor(Date.now() / 1000) + 90,
      signature = crypto
        .createHmac("sha256", key)
        .update(`recording-source:container-node:${filename}:${expires}`)
        .digest("hex"),
      source =
        "http://" +
        voice +
        `:8003/v1/recordings/${filename}?expires=${expires}&signature=${signature}`,
      payload = {
        tenant_id: 1,
        recording_asset_id: 1,
        call_id: "container-recording",
        provider_url: source,
      };
    const ingest = async () =>
        (
          await http(recordingUrl, "/v1/recordings/ingest", {
            method: "POST",
            body: payload,
            headers,
            status: 200,
            timeout: 30000,
          })
        ).json,
      saved = await ingest();
    assert.equal(
      saved.checksum_sha256,
      crypto.createHash("sha256").update(audio).digest("hex"),
    );
    await http(
      voiceUrl,
      `/v1/recordings/${filename}?expires=${expires}&signature=${signature}`,
      { status: 404 },
    );
    assert.deepEqual(await ingest(), saved);
    await http(recordingUrl, "/v1/recordings/delete", {
      method: "POST",
      body: { ...payload, storage_uri: saved.storage_uri },
      headers,
      status: 200,
    });
    await http(recordingUrl, "/v1/recordings/ingest", {
      method: "POST",
      body: payload,
      headers,
      status: 503,
    });
    checks.push(
      "signed source -> recording -> actual S3 readback -> cleanup/retry/tombstone",
    );
    const media = start(
        "media",
        {
          ...common,
          JAVA_SERVICE_ROLE: "media",
          MEDIA_WORKER_ID: "container-worker",
          MEDIA_RPC_TOKEN: auth,
          PIPECAT_OPENAI_API_KEY: "synthetic-unused-key",
          MEDIA_CONTROL_URL: "http://127.0.0.1:1",
        },
        image,
      ),
      mediaUrl = await ready(media);
    assert.equal(
      (await http(mediaUrl, "/internal/state", { headers, status: 200 })).json
        .worker_id,
      "container-worker",
    );
    checks.push("authenticated Java media role");
    for (const base of [voiceUrl, agentUrl, recordingUrl, mediaUrl]) {
      assert(
        Object.keys(
          (await http(base, "/openapi.json", { status: 200 })).json.paths,
        ).length,
      );
      assert(
        (await http(base, "/docs", { status: 200 })).headers
          .get("content-security-policy")
          .includes("connect-src 'self'"),
      );
    }
    for (const base of [voiceUrl, recordingUrl]) {
      await http(base, "/metrics", { status: 401 });
      const r = await http(base, "/metrics", {
        headers: { Authorization: "Bearer isolated-container-metrics" },
        status: 200,
      });
      assert(r.text.includes("ai_outbound_java_uptime_seconds "));
      assert(!r.text.includes(auth) && !r.text.includes(cleanup));
    }
    checks.push("four role docs and authenticated JVM metrics");
    await http(voiceUrl, "/v1/admin/security/stop", {
      method: "POST",
      headers,
      body: { stopped: true, reason: "isolated persistence regression" },
      status: 200,
    });
    docker("rm", "-f", voice);
    names.splice(names.indexOf(voice), 1);
    const restarted = start(
        "voice-restarted",
        {
          ...common,
          JAVA_SERVICE_ROLE: "voice",
          VOICE_GATEWAY_DRIVER: "mock",
          VOICE_SECURITY_DB_PATH: "/var/lib/voice-security/ledger.sqlite3",
          FREESWITCH_RECORDING_DIR: "/recordings",
        },
        image,
        [],
        ["-v", ledger + ":/var/lib/voice-security"],
      ),
      restartedUrl = endpoint(restarted);
    let state;
    await wait(async () => {
      state = await http(restartedUrl, "/v1/admin/security", { headers });
      return state.status === 200;
    }, 90);
    assert.equal(state.json.stopped, true);
    await http(restartedUrl, "/readyz", { status: 503 });
    await http(restartedUrl, "/v1/admin/security/stop", {
      method: "POST",
      headers,
      body: { stopped: false, reason: "isolated resume" },
      status: 200,
    });
    await ready(restarted);
    checks.push("remove/recreate retains stop until explicit resume");
  }
  for (const name of names.filter((n) => !n.endsWith("-s3"))) {
    const r = docker(
      "exec",
      name,
      "sh",
      "-c",
      'for p in python python3 python2 pip pip3; do if command -v "$p" >/dev/null 2>&1; then exit 1; fi; done',
    );
    checks.push("no interpreter/pip in " + name.slice(prefix.length + 1));
  }
  writeJSON(
    path.join(ROOT, "artifacts/no-python/container-" + mode + ".json"),
    {
      passed: true,
      checks,
      boundary:
        "Disposable Java Linux images and synthetic state. No production/real PBX/cloud/capacity acceptance.",
    },
  );
  console.log("Java-only container checks passed:", checks.length);
} finally {
  fs.mkdirSync(path.join(ROOT, "artifacts/no-python"), { recursive: true });
  for (const name of names) {
    try {
      fs.writeFileSync(
        path.join(ROOT, "artifacts/no-python", name + ".log"),
        docker("logs", name),
      );
    } catch {}
  }
  for (const name of names.reverse())
    try {
      docker("rm", "-f", name);
    } catch {}
  for (const n of networks)
    try {
      docker("network", "rm", n);
    } catch {}
  for (const v of volumes)
    try {
      docker("volume", "rm", v);
    } catch {}
}
