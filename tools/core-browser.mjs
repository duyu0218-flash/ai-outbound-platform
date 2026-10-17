import { spawnSync } from "node:child_process";
import {
  fs,
  path,
  ROOT,
  args,
  java,
  port,
  Processes,
  temporary,
  wait,
  http,
  assert,
  writeJSON,
  sha,
} from "./lib/common.mjs";
const a = args(),
  variant = a.variant ?? "prepared";
assert(["prepared", "dispatch", "queued"].includes(variant));
const processes = new Processes(),
  p = await port(),
  base = "http://127.0.0.1:" + p,
  jar = path.join(
    ROOT,
    "backend-java/target/outbound-control-0.1.0-SNAPSHOT.jar",
  );
await temporary("java-core-browser-", async (folder) => {
  try {
    processes.start(
      "core-browser-" + variant,
      java(),
      ["-jar", jar],
      {
        ENV: "test",
        PORT: String(p),
        JAVA_DATABASE_URL: "jdbc:sqlite:" + folder + "/core.db",
        JAVA_INITIALIZE_SCHEMA: "true",
        DEMO_USERS_ENABLED: "true",
        JWT_SECRET: "isolated-core-browser-test-secret-never-production",
        JAVA_WORKERS_ENABLED: variant === "dispatch" ? "true" : "false",
        JAVA_DISPATCH_ENABLED: variant !== "prepared" ? "true" : "false",
        JAVA_RELEASE_ACCEPTED: "true",
        TELEPHONY_PROVIDER: "mock",
        REDIS_URL: "",
        STABILITY_ADMISSION_ENABLED: "false",
        FRONTEND_STATIC_DIR: path.join(ROOT, "frontend/dist"),
      },
      folder,
    );
    await wait(async () => (await http(base, "/health")).status === 200, 60);
    let login;
    await wait(async () => {
      login = await http(base, "/api/v1/auth/login", {
        method: "POST",
        body: { username: "admin", password: "12345678" },
      });
      return login.status === 200 && login.json?.access_token;
    }, 60);
    const headers = { Authorization: "Bearer " + login.json.access_token };
    const compliance = (
      await http(base, "/api/v1/admin/settings/compliance", {
        headers,
        status: 200,
      })
    ).json.data;
    await http(base, "/api/v1/admin/settings/compliance", {
      method: "PUT",
      headers,
      body: {
        data: { ...compliance, allowed_start_hour: 0, allowed_end_hour: 0 },
      },
      status: 200,
    });
    const r = spawnSync(
      process.execPath,
      [
        path.join(ROOT, "frontend/node_modules/@playwright/test/cli.js"),
        "test",
        variant === "prepared"
          ? "e2e/java-core.spec.ts"
          : variant === "queued"
            ? "e2e/functional-fixes.spec.ts"
            : "e2e/java-dispatch.spec.ts",
        ...(variant === "queued"
          ? ["--grep", "campaign contact search and paging"]
          : []),
        "--reporter=json",
      ],
      {
        cwd: path.join(ROOT, "frontend"),
        encoding: "utf8",
        timeout: 180000,
        maxBuffer: 20 * 1024 * 1024,
        env: {
          ...process.env,
          E2E_BASE_URL: base,
          E2E_JAVA_CORE: variant === "prepared" ? "1" : "0",
          E2E_FIX7_ISOLATED: variant === "queued" ? "1" : "",
          E2E_JAVA_DISPATCH: variant === "dispatch" ? "1" : "0",
        },
      },
    );
    fs.mkdirSync(path.join(ROOT, "artifacts/no-python"), { recursive: true });
    fs.writeFileSync(
      path.join(ROOT, "artifacts/no-python/core-browser-" + variant + ".json"),
      r.stdout ?? "",
    );
    fs.writeFileSync(
      path.join(ROOT, "artifacts/no-python/core-browser-" + variant + ".log"),
      r.stderr ?? "",
    );
    assert.equal(r.status, 0, "Dedicated " + variant + " browser case failed");
  } finally {
    await processes.close();
  }
});
console.log("Dedicated Java browser fixture passed:", variant);
