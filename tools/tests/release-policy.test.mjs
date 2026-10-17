import { test } from "node:test";
import { path, ROOT, readJSON, assert } from "../lib/common.mjs";
import {
  parseEnv,
  validatePolicy,
  validateFrontend,
  validatePins,
  validateProduction,
} from "../lib/release-policy.mjs";
const matrix = () => readJSON(path.join(ROOT, "compatibility-matrix.json"));
function env() {
  return {
    ...Object.fromEntries(
      Object.values(matrix().components)
        .filter((c) => c.production_image_env)
        .map((c) => [
          c.production_image_env,
          "approved/runtime@sha256:" + "a".repeat(64),
        ]),
    ),
    JAVA_VERSION: "17.0.17",
    NODE_VERSION: "22.23.3",
    SPRING_BOOT_VERSION: "4.1.1",
    PNPM_VERSION: "11.19.0",
    FREESWITCH_VERSION: "1.10.13",
    ENV: "production",
    AUTO_MIGRATE: "false",
    JAVA_INITIALIZE_SCHEMA: "false",
    TELEPHONY_PROVIDER: "http",
    VOICE_GATEWAY_DRIVER: "freeswitch_esl",
    OUTBOUND_ALLOWED_PHONE_PREFIXES: "139,138",
    OUTBOUND_DAILY_CALL_LIMIT: "100",
    TELEPHONY_WEBHOOK_TOKEN: "t".repeat(32),
    TELEPHONY_WEBHOOK_SECRET: "s".repeat(32),
    RECORDING_SOURCE_REQUIRE_HTTPS: "true",
    LLM_SEND_PII: "false",
    VOICE_AI_PIPELINE: "legacy",
    ALERTMANAGER_CONFIG_FILE: "/approved/notification.yml",
  };
}
test("existing release policy cannot silently lose regression/canary/no-bypass gates", () => {
  validatePolicy(matrix());
  for (const k of [
    "production_requires_regression_approval",
    "production_requires_controlled_canary",
    "candidate_versions_must_pass_controlled_real_call",
  ]) {
    const m = matrix();
    m.policy[k] = false;
    assert.throws(() => validatePolicy(m));
  }
  const m = matrix();
  m.policy.security_updates_may_bypass_release_gates = true;
  assert.throws(() => validatePolicy(m));
});
test("frontend must retain exact dependency and package manager pins", () => {
  const p = readJSON(path.join(ROOT, "frontend/package.json"));
  validateFrontend(p, matrix());
  p.dependencies.react = "^19.2.8";
  assert.throws(() => validateFrontend(p, matrix()));
});
test("approved-shaped configuration passes static gates without declaring production acceptance", () => {
  validateProduction(matrix(), env());
});
for (const [name, k, v] of [
  ["mutable image", "JAVA_RUNTIME_IMAGE", "java:latest"],
  ["wrong JDK", "JAVA_VERSION", "8.0.504"],
  ["implicit migration", "AUTO_MIGRATE", "true"],
  ["schema bootstrap", "JAVA_INITIALIZE_SCHEMA", "true"],
  ["mock phone", "TELEPHONY_PROVIDER", "mock"],
  ["mock voice", "VOICE_GATEWAY_DRIVER", "mock"],
  ["invalid prefix", "OUTBOUND_ALLOWED_PHONE_PREFIXES", "+86139"],
  ["missing limit", "OUTBOUND_DAILY_CALL_LIMIT", "0"],
  ["weak callback key", "TELEPHONY_WEBHOOK_SECRET", "short"],
  ["plaintext recording", "RECORDING_SOURCE_REQUIRE_HTTPS", "false"],
  ["PII override", "LLM_SEND_PII", "true"],
  [
    "local alert route",
    "ALERTMANAGER_CONFIG_FILE",
    "deploy/alertmanager/alertmanager.yml",
  ],
])
  test("production gate rejects " + name, () => {
    const e = env();
    e[k] = v;
    assert.throws(() => validateProduction(matrix(), e));
  });
test("native media and external LLM still require module version/start and host allowlist", () => {
  const e = env();
  e.VOICE_AI_PIPELINE = "pipecat";
  assert.throws(() => validateProduction(matrix(), e));
  e.FREESWITCH_MEDIA_MODULE_VERSION = "2.5.1";
  e.PIPECAT_MEDIA_PROTOCOL = "voismart";
  validateProduction(matrix(), e);
  e.LLM_PROVIDER = "openai-compatible";
  assert.throws(() => validateProduction(matrix(), e));
});
test("environment parsing never executes shell syntax and rejects duplicates", () => {
  const e = parseEnv("# fields\nENV=production\nKEY='$(not-executed)'\n");
  assert.equal(e.KEY, "$(not-executed)");
  assert.throws(() => parseEnv("ENV=test\nENV=production"));
});
