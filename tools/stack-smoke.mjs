import {
  fs,
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
  run,
} from "./lib/common.mjs";
import { spawnSync } from "node:child_process";
const coreJar = path.join(
    ROOT,
    "backend-java/target/outbound-control-0.1.0-SNAPSHOT.jar",
  ),
  servicesJar = path.join(
    ROOT,
    "services-java/target/outbound-services-0.1.0-SNAPSHOT.jar",
  );
const processes = new Processes(),
  checks = [],
  ports = {},
  urls = {};
for (const n of ["core", "agent", "voice"]) {
  ports[n] = await port();
  urls[n] = "http://127.0.0.1:" + ports[n];
}
await temporary("java-only-stack-", async (folder) => {
  const common = {
    ENV: "test",
    LLM_PROVIDER: "rule",
    OPENAI_API_KEY: "",
    LANGFUSE_ENABLED: "false",
    REDIS_URL: "",
    VOICE_GATEWAY_DRIVER: "mock",
    SERVER_TOMCAT_ACCESSLOG_BUFFERED: "false",
    SERVER_TOMCAT_ACCESSLOG_PATTERN: "%m %U %s",
  };
  async function start(name, jar, env) {
    const p = processes.start(
      "stack-" + name,
      java(),
      ["-jar", jar],
      { ...common, ...env, PORT: String(ports[name]) },
      folder,
    );
    await wait(async () => {
      if (p.startError || p.exitCode !== null || p.signalCode)
        throw Error(name + " exited; inspect artifacts/no-python log");
      return (
        (await http(urls[name], "/health", { timeout: 2000 })).status === 200
      );
    }, 60);
  }
  try {
    await start("agent", servicesJar, {
      JAVA_SERVICE_ROLE: "agent",
      SERVICE_TOKEN: "isolated-agent-service-token",
      SERVER_TOMCAT_ACCESSLOG_ENABLED: "true",
      SERVER_TOMCAT_ACCESSLOG_DIRECTORY: folder + "/agent-access",
    });
    await start("voice", servicesJar, {
      JAVA_SERVICE_ROLE: "voice",
      SERVICE_TOKEN: "isolated-voice-service-token",
      VOICE_SECURITY_DB_PATH: folder + "/voice.db",
      SERVER_TOMCAT_ACCESSLOG_ENABLED: "true",
      SERVER_TOMCAT_ACCESSLOG_DIRECTORY: folder + "/voice-access",
      WEBHOOK_TOKEN: "isolated-webhook-token",
      WEBHOOK_SECRET: "isolated-callback-signing-key",
      VOICE_CALLBACK_BASE_URL: urls.core,
      VOICE_CALLBACK_ALLOW_PRIVATE_HTTP: "true",
    });
    await start("core", coreJar, {
      JAVA_DATABASE_URL: "jdbc:sqlite:" + folder + "/core.db",
      JAVA_INITIALIZE_SCHEMA: "true",
      DEMO_USERS_ENABLED: "true",
      JWT_SECRET: "isolated-java-only-services-test-secret",
      JAVA_WORKERS_ENABLED: "true",
      JAVA_DISPATCH_ENABLED: "true",
      JAVA_RELEASE_ACCEPTED: "true",
      TELEPHONY_PROVIDER: "http",
      TELEPHONY_PROVIDER_ENDPOINT: urls.voice,
      OUTBOUND_SECURITY_APPROVAL_TOKEN:
        "isolated-retained-security-approval-only",
      TELEPHONY_SERVICE_TOKEN: "isolated-voice-service-token",
      VOICE_COMMAND_SECRET: "isolated-voice-command-key",
      TELEPHONY_WEBHOOK_BASE: urls.core,
      TELEPHONY_WEBHOOK_TOKEN: "isolated-webhook-token",
      TELEPHONY_WEBHOOK_SECRET: "isolated-callback-signing-key",
      AI_AGENT_URL: urls.agent,
      AI_AGENT_SERVICE_TOKEN: "isolated-agent-service-token",
      CALLBACK_INBOX_ENABLED: "true",
      FRONTEND_STATIC_DIR: path.join(ROOT, "frontend/dist"),
      STABILITY_ADMISSION_ENABLED: "false",
      VOICE_GATEWAY_NODES_JSON: "[]",
    });
    const headers = {};
    const api = async (method, p, body) =>
      (
        await http(urls.core, p, {
          method,
          body,
          headers,
          status: 200,
          timeout: 20000,
        })
      ).json;
    await wait(async () => (await http(urls.core, "/readyz")).status === 200);
    const login = await api("POST", "/api/v1/auth/login", {
      username: "admin",
      password: "12345678",
    });
    headers.Authorization = "Bearer " + login.access_token;
    headers["X-Security-Approval"] = "isolated-retained-security-approval-only";
    checks.push("readiness verifies actual Java Agent and gateway");
    const data = (await api("GET", "/api/v1/admin/settings/compliance")).data;
    Object.assign(data, { allowed_start_hour: 0, allowed_end_hour: 0 });
    await api("PUT", "/api/v1/admin/settings/compliance", { data });
    const contact = await api("POST", "/api/v1/contacts", {
        phone: "13900000077",
        name: "Java-only synthetic contact",
        consent_state: "consented",
      }),
      call = await api("POST", "/api/v1/calls", {
        contact_id: contact.id,
        mode: "ai_only",
        max_attempts: 1,
      });
    await wait(
      async () =>
        (await api("GET", "/api/v1/calls/" + call.id)).status === "in_ai",
    );
    checks.push("Java HTTP dial and signed Java gateway callbacks reach in_ai");
    const callback = async (p, payload) => {
      const body = JSON.stringify(payload),
        stamp = String(Math.floor(Date.now() / 1000)),
        sig = crypto
          .createHmac("sha256", "isolated-callback-signing-key")
          .update(stamp + "." + body)
          .digest("hex");
      await http(urls.core, "/api/v1/webhooks/telephony/" + p, {
        method: "POST",
        body,
        status: 200,
        headers: {
          "Content-Type": "application/json",
          "X-Webhook-Token": "isolated-webhook-token",
          "X-Webhook-Timestamp": stamp,
          "X-Webhook-Signature": sig,
        },
      });
    };
    await callback("speech", {
      call_id: call.id,
      event_id: "java-only-speech",
      attempt: 1,
      transcript: "Please explain the service details",
      is_final: true,
      barge_in: true,
    });
    const access = (role) =>
      fs
        .readdirSync(folder + "/" + role + "-access")
        .map((p) =>
          fs.readFileSync(folder + "/" + role + "-access/" + p, "utf8"),
        )
        .join("");
    await wait(
      () =>
        access("agent").includes("/agent/turn") &&
        access("voice").includes("/v1/call/speak"),
    );
    checks.push(
      "Java core invokes Java Agent turn and Java gateway speak over HTTP",
    );
    await callback("status", {
      call_id: call.id,
      kind: "status",
      payload: {
        status: "completed",
        attempt: 1,
        event_id: "java-only-completed",
        duration_sec: 8,
      },
    });
    await wait(
      async () =>
        (await api("GET", "/api/v1/calls/" + call.id)).status === "completed",
    );
    await wait(async () => {
      const r = await http(
        urls.core,
        "/api/v1/calls/" + call.id + "/analysis",
        { headers },
      );
      return r.status === 200 && r.json !== null;
    });
    checks.push("signed terminal callback generates durable analysis");
    if (process.env.JAVA_SERVICES_LOAD === "1") {
      const { runLoad } = await import("./load.mjs");
      const before = {
        token: process.env.LOAD_WEBHOOK_TOKEN,
        secret: process.env.LOAD_WEBHOOK_SECRET,
      };
      try {
        process.env.LOAD_WEBHOOK_TOKEN = "isolated-webhook-token";
        process.env.LOAD_WEBHOOK_SECRET = "isolated-callback-signing-key";
        for (const scenario of ["read", "webhook"]) {
          const r = await runLoad({
            url: urls.core,
            "isolated-mock": true,
            scenario,
            rate: 10,
            seconds: 2,
            concurrency: 10,
            output: path.join(
              ROOT,
              "artifacts/no-python/" +
                path.basename(folder) +
                "-load-" +
                scenario +
                ".json",
            ),
          });
          assert(r.passed);
        }
        const conversationCalls = [];
        for (let i = 0; i < 2; i++) {
          const c = await api("POST", "/api/v1/contacts", {
            phone: "1390000901" + i,
            name: "Synthetic load " + i,
            consent_state: "consented",
          });
          const q = await api("POST", "/api/v1/calls", {
            contact_id: c.id,
            mode: "ai_only",
            max_attempts: 1,
          });
          conversationCalls.push(q.id);
          await wait(
            async () =>
              (await api("GET", "/api/v1/calls/" + q.id)).status === "in_ai",
          );
        }
        const file = path.join(folder, "load-calls.json");
        writeJSON(file, conversationCalls);
        const conversation = await runLoad({
          url: urls.core,
          "isolated-mock": true,
          scenario: "conversation",
          rate: 1,
          seconds: 2,
          concurrency: 2,
          "calls-file": file,
          output: path.join(
            ROOT,
            "artifacts/no-python",
            path.basename(folder) + "-load-conversation.json",
          ),
        });
        assert(conversation.passed);
        for (const id of conversationCalls)
          await callback("status", {
            call_id: id,
            kind: "status",
            payload: {
              status: "completed",
              attempt: 1,
              event_id: "load-completed-" + id,
              duration_sec: 4,
            },
          });
      } finally {
        for (const [k, v] of [
          ["LOAD_WEBHOOK_TOKEN", before.token],
          ["LOAD_WEBHOOK_SECRET", before.secret],
        ])
          if (v === undefined) delete process.env[k];
          else process.env[k] = v;
      }
      checks.push(
        "bounded Node arrival-rate HTTP read and signed webhook smoke",
      );
    }
    if (process.env.JAVA_SERVICES_BROWSER === "1") {
      if (process.env.JAVA_ALL_BROWSER === "1") {
        const { seedBrowser } = await import("./lib/browser-seed.mjs");
        await seedBrowser(folder);
      }
      const tests =
        process.env.JAVA_ALL_BROWSER === "1"
          ? [
              "--grep-invert",
              "Java core campaign form|Java mock dispatch|campaign contact search and paging",
            ]
          : [
              "e2e/portal.spec.ts",
              "e2e/java-services.spec.ts",
              "--grep-invert",
              "SPA account switch",
            ];
      const r = spawnSync(
        process.execPath,
        [
          path.join(ROOT, "frontend/node_modules/@playwright/test/cli.js"),
          "test",
          ...tests,
          "--reporter=json",
        ],
        {
          cwd: path.join(ROOT, "frontend"),
          encoding: "utf8",
          timeout: 900000,
          maxBuffer: 20 * 1024 * 1024,
          env: {
            ...process.env,
            E2E_BASE_URL: urls.core,
            E2E_JAVA_SERVICES: "1",
            E2E_REVIEW_TENANTS: process.env.JAVA_ALL_BROWSER === "1" ? "1" : "",
            E2E_FIX7_ISOLATED: process.env.JAVA_ALL_BROWSER === "1" ? "1" : "",
            E2E_JAVA_AGENT_URL: urls.agent,
            E2E_JAVA_VOICE_URL: urls.voice,
            E2E_SECURITY_APPROVAL: headers["X-Security-Approval"],
            E2E_WEBHOOK_TOKEN: "isolated-webhook-token",
            E2E_WEBHOOK_SECRET: "isolated-callback-signing-key",
          },
        },
      );
      fs.writeFileSync(
        path.join(ROOT, "artifacts/no-python/browser.json"),
        r.stdout ?? "",
      );
      fs.writeFileSync(
        path.join(ROOT, "artifacts/no-python/browser.log"),
        r.stderr ?? "",
      );
      if (r.status !== 0) {
        try {
          const auth = await http(urls.core, "/api/v1/auth/login", {
            method: "POST",
            body: { username: "admin", password: "12345678" },
            status: 200,
          });
          const h = { Authorization: "Bearer " + auth.json.access_token };
          const calls = await http(urls.core, "/api/v1/calls?page=1&size=200", {
            headers: h,
            status: 200,
          });
          writeJSON(
            path.join(
              ROOT,
              "artifacts/no-python/browser-call-diagnostics.json",
            ),
            calls.json,
          );
        } catch {}
      }
      assert.equal(
        r.status,
        0,
        "Java browser regression failed; see artifacts/no-python/browser.json",
      );
      checks.push(
        "browser navigation, save/readback, authorization, logout and Java calls/reports",
      );
    }
  } finally {
    await processes.close();
  }
});
writeJSON(path.join(ROOT, "artifacts/no-python/stack.json"), {
  passed: true,
  checks,
  jar_sha256: sha(coreJar),
  services_jar_sha256: sha(servicesJar),
  boundary:
    "Actual Java processes, rule model and mock phone; no real PBX/cloud/capacity qualification.",
});
console.log("Java-only process/browser checks passed:", checks.length);
