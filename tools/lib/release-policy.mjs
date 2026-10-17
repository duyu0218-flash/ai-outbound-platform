import { assert } from "./common.mjs";
const exact = /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/;
export function parseEnv(text) {
  const result = {};
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const m = line.match(/^(?:export\s+)?([A-Z][A-Z0-9_]*)\s*=\s*(.*)$/);
    assert(m, "Invalid environment assignment");
    assert(!Object.hasOwn(result, m[1]), "Duplicate environment field");
    let v = m[2].trim();
    if (
      (v.startsWith('"') && v.endsWith('"')) ||
      (v.startsWith("'") && v.endsWith("'"))
    )
      v = v.slice(1, -1);
    result[m[1]] = v;
  }
  return result;
}
export function validatePolicy(matrix) {
  assert.equal(matrix.schema_version, 1);
  for (const k of [
    "application",
    "java",
    "spring_boot",
    "maven",
    "node",
    "pnpm",
    "postgresql",
    "redis",
    "freeswitch",
    "freeswitch_media_module",
    "coturn",
    "nginx",
    "seaweedfs",
    "prometheus",
    "alertmanager",
    "grafana",
    "sipp",
    "github_actions",
  ])
    assert(matrix.components[k], "Missing compatibility component " + k);
  for (const k of [
    "production_requires_immutable_images",
    "production_requires_exact_runtime_versions",
    "candidate_versions_must_pass_ci",
    "candidate_versions_must_pass_controlled_real_call",
    "candidate_tracking_is_automated",
    "candidate_versions_enter_ci_automatically",
    "production_requires_regression_approval",
    "production_requires_controlled_canary",
    "security_updates_use_expedited_path",
  ])
    assert.equal(matrix.policy[k], true, k + " must remain true");
  for (const k of [
    "automatic_production_upgrade",
    "security_updates_may_bypass_release_gates",
  ])
    assert.equal(matrix.policy[k], false, k + " must remain false");
}
export function validateFrontend(pkg, matrix) {
  assert.equal(pkg.version, matrix.components.application.constraint.slice(2));
  assert.equal(
    pkg.packageManager,
    "pnpm@" + matrix.components.pnpm.constraint.slice(2),
  );
  for (const entries of [pkg.dependencies, pkg.devDependencies])
    for (const [name, v] of Object.entries(entries))
      assert(
        exact.test(v),
        "Frontend dependency must be exactly pinned: " + name,
      );
}
export function validatePins(matrix, pins) {
  for (const [name, c] of Object.entries(matrix.components))
    if (c.production_image_env)
      assert(
        /^[^\s@]+@sha256:[a-f0-9]{64}$/.test(
          pins[c.production_image_env] ?? "",
        ),
        "Immutable image required: " + name,
      );
  assert(
    /^17\.\d+\.\d+(?:\.\d+)?(?:\+\d+)?$/.test(pins.JAVA_VERSION ?? ""),
    "Exact JDK 17 runtime version required",
  );
  assert(
    /^22\.\d+\.\d+$/.test(pins.NODE_VERSION ?? ""),
    "Exact Node 22 runtime version required",
  );
  assert.equal(pins.SPRING_BOOT_VERSION, "4.1.1");
  assert.equal(pins.PNPM_VERSION, "11.19.0");
  assert(
    exact.test(pins.FREESWITCH_VERSION ?? ""),
    "Exact FreeSWITCH runtime version required",
  );
  if (
    pins.FREESWITCH_MEDIA_START_COMMAND_TEMPLATE ||
    ["pipecat", "hybrid"].includes(pins.VOICE_AI_PIPELINE)
  )
    assert(
      exact.test(pins.FREESWITCH_MEDIA_MODULE_VERSION ?? ""),
      "Exact native media module version required",
    );
}
export function validateProduction(matrix, env) {
  validatePins(matrix, env);
  const low = (k) =>
    String(env[k] ?? "")
      .trim()
      .toLowerCase();
  assert(
    ["prod", "production"].includes(low("ENV")),
    "Production ENV required",
  );
  assert(
    low("AUTO_MIGRATE") === "false" &&
      low("JAVA_INITIALIZE_SCHEMA") === "false",
    "Use explicit schema maintenance; automatic schema writes prohibited",
  );
  assert(
    ["http", "tenant"].includes(low("TELEPHONY_PROVIDER")),
    "Real approved telephony provider required",
  );
  assert.equal(low("VOICE_GATEWAY_DRIVER"), "freeswitch_esl");
  const prefixes = String(env.OUTBOUND_ALLOWED_PHONE_PREFIXES ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  assert(
    prefixes.length && prefixes.every((p) => /^\d{1,15}$/.test(p)),
    "Approved numeric prefixes required",
  );
  const daily = Number(env.OUTBOUND_DAILY_CALL_LIMIT);
  assert(
    Number.isInteger(daily) && daily >= 1 && daily <= 10000000,
    "Bounded daily outbound limit required",
  );
  for (const k of ["TELEPHONY_WEBHOOK_TOKEN", "TELEPHONY_WEBHOOK_SECRET"])
    assert(
      String(env[k] ?? "").trim().length >= 32,
      "32+ character credential required: " + k,
    );
  assert.equal(low("RECORDING_SOURCE_REQUIRE_HTTPS"), "true");
  assert.equal(low("LLM_SEND_PII") || "false", "false");
  if (low("LLM_PROVIDER") === "openai-compatible")
    assert(
      String(env.LLM_ALLOWED_HOSTS ?? "").trim(),
      "External LLM host allowlist required",
    );
  const pipeline = low("VOICE_AI_PIPELINE") || "legacy";
  assert(
    ["legacy", "pipecat", "hybrid"].includes(pipeline),
    "Invalid Java media pipeline",
  );
  if (["pipecat", "hybrid"].includes(pipeline))
    assert(
      env.PIPECAT_MEDIA_PROTOCOL === "voismart" ||
        String(env.FREESWITCH_PIPECAT_START_COMMAND_TEMPLATE ?? "").trim(),
      "Native media start command required",
    );
  const alert = String(env.ALERTMANAGER_CONFIG_FILE ?? "").trim();
  assert(
    alert.startsWith("/") &&
      !alert.endsWith("deploy/alertmanager/alertmanager.yml"),
    "Approved external alert notification configuration required",
  );
}
