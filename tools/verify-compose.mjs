import {
  fs,
  path,
  ROOT,
  temporary,
  run,
  readJSON,
  writeJSON,
  xml,
  child,
  assert,
} from "./lib/common.mjs";
export async function resolveProfiles() {
  return temporary("java-compose-only-", async (folder) => {
    const fixture = path.join(folder, "synthetic.env");
    fs.writeFileSync(
      fixture,
      "POSTGRES_USER=synthetic\nPOSTGRES_DB=synthetic\nPOSTGRES_PASSWORD=synthetic\nREDISCLI_AUTH=synthetic\n",
    );
    const reports = [];
    for (const files of [
      ["docker-compose.yml"],
      [
        "docker-compose.yml",
        "docker-compose.java.yml",
        "docker-compose.java-services.yml",
      ],
      ["docker-compose.compact.yml"],
      ["docker-compose.single-host-500.yml"],
    ]) {
      const env = { ...process.env, APP_ENV_FILE: ".env.example" };
      for (const name of [
        ...files,
        ...(files.includes("docker-compose.single-host-500.yml")
          ? ["docker-compose.compact.yml"]
          : []),
      ])
        for (const [, k] of fs
          .readFileSync(path.join(ROOT, name), "utf8")
          .matchAll(/\$\{([A-Z0-9_]+):?\?/g))
          env[k] = k.endsWith("_FILE")
            ? fixture
            : k.endsWith("_DIR")
              ? folder
              : k.endsWith("_IMAGE")
                ? "example.invalid/fixture@sha256:" + "a".repeat(64)
                : "synthetic-" + "a".repeat(40);
      Object.assign(env, {
        NODE_PRIVATE_IP: "127.0.0.1",
        NODE_ID: "synthetic-node",
        LLM_APPROVED_RPM: "100",
        LLM_APPROVED_TPM: "10000",
        LLM_APPROVED_RPS: "10",
        VOICE_RECORDING_CLEANUP_TOKEN: "cleanup-" + "c".repeat(40),
        VOICE_RECORDING_RESERVE_BYTES: "10000000000",
        VOICE_QUOTA_BUDGETS_JSON: JSON.stringify({
          asr: {
            provider: "test",
            account: "a",
            region: "r",
            product: "asr",
            concurrency: 600,
          },
          tts: {
            provider: "test",
            account: "a",
            region: "r",
            product: "tts",
            concurrency: 100,
            requests_per_minute: 10000,
          },
        }),
      });
      const config = JSON.parse(
          run(
            "docker",
            [
              "compose",
              ...files.flatMap((f) => ["-f", f]),
              "config",
              "--format",
              "json",
            ],
            { env },
          ),
        ),
        selected = {},
        ports = new Set();
      for (const [name, s] of Object.entries(config.services)) {
        const core =
            /^(control-api|task-worker|ai-worker|callback-worker)/.test(name),
          aux = /^(ai-agent|voice-gateway|media-|recording-adapter)/.test(name);
        if (!core && !aux) continue;
        assert.deepEqual(s.command.slice(0, 2), ["java", "-jar"], name);
        assert.deepEqual(s.healthcheck.test.slice(0, 2), ["CMD", "java"], name);
        const e = s.environment,
          p = Number(e.PORT);
        assert(Number.isInteger(p) && p > 0);
        if (s.network_mode === "host") {
          assert(!ports.has(p), "Colliding host port " + p);
          ports.add(p);
          assert(["127.0.0.1", "0.0.0.0"].includes(e.JAVA_BIND_ADDRESS));
          if (aux)
            assert(
              ["agent", "voice", "media", "recording"].includes(
                e.JAVA_SERVICE_ROLE,
              ),
            );
        }
        if (core)
          assert.equal(
            e.JAVA_WORKERS_ENABLED,
            name.startsWith("control-api") && s.network_mode === "host"
              ? "false"
              : "true",
          );
        if (name === "voice-gateway")
          assert(
            s.volumes.some(
              (v) =>
                ["/recordings", "/var/lib/freeswitch/recordings"].includes(
                  v.target,
                ) && !v.read_only,
            ),
            "Writable recording owner required",
          );
        const state =
          name === "voice-gateway"
            ? e.VOICE_SECURITY_DB_PATH
            : name.startsWith("ai-agent")
              ? e.LLM_QUOTA_DB_PATH
              : name === "recording-adapter"
                ? e.RECORDING_SPOOL_DIR
                : null;
        if (state)
          assert(
            s.volumes.some(
              (v) =>
                !v.read_only &&
                (state === v.target || state.startsWith(v.target + "/")),
            ),
            "Persistent writable state required: " + name,
          );
        selected[name] = { jar: s.command[2], port: p };
      }
      assert(Object.keys(selected).length >= 4);
      if (files.length === 1 && files[0] === "docker-compose.yml")
        assert(!config.services["task-worker"]);
      if (files[0] === "docker-compose.single-host-500.yml") {
        assert(config.services.redis.healthcheck.test.includes("--tls"));
        assert(
          config.services.redis.volumes.some(
            (v) => v.target === "/etc/redis/tls" && v.read_only,
          ),
        );
        const { assessProfile } = await import("./lib/profile.mjs");
        const r = assessProfile(config);
        assert(r.static_config_passed, r.blockers.join("; "));
        writeJSON(path.join(ROOT, "artifacts/no-python/single500.json"), r);
      }
      reports.push({ files, services: selected });
      if (files[0] === "docker-compose.single-host-500.yml")
        writeJSON(
          path.join(
            ROOT,
            "artifacts/no-python/single500-synthetic-config.json",
          ),
          config,
        );
    }
    for (const module of ["backend-java", "services-java"]) {
      const p = xml(path.join(ROOT, module, "pom.xml"));
      assert.equal(child(child(p, "parent"), "version").text, "4.1.1");
      assert.equal(child(child(p, "properties"), "java.version").text, "17");
    }
    return reports;
  });
}
if (process.argv[1]?.endsWith("verify-compose.mjs")) {
  const profiles = await resolveProfiles();
  writeJSON(path.join(ROOT, "artifacts/no-python/compose.json"), {
    passed: true,
    profiles,
    boundary:
      "Static Compose resolution; no target deployment/TLS/capacity acceptance.",
  });
  console.log("Validated Java Compose profiles:", profiles.length);
}
