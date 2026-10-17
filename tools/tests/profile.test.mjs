import { test } from "node:test";
import { path, ROOT, readJSON, assert } from "../lib/common.mjs";
import { assessProfile } from "../lib/profile.mjs";
const fixture = () =>
  readJSON(path.join(ROOT, "tools/tests/fixtures/single500.json"));
test("Java static budget uses 11 real lanes and never certifies 500 calls", () => {
  const r = assessProfile(fixture());
  assert(r.static_config_passed, r.blockers.join("; "));
  assert.equal(r.application_db_connections, 64);
  assert.equal(r.ai_slots, 11);
  assert(!r.real_500_call_capacity_verified);
});
for (const [name, mutate] of Object.entries({
  "pool multiplication": (s) => {
    s["ai-worker-1"].environment.DATABASE_POOL_SIZE = "100";
  },
  "media roster mismatch": (s) => {
    s["media-12"].environment.MEDIA_WORKER_CAPACITY = "200";
  },
  "split account quotas": (s) => {
    s["ai-agent-2"].volumes.find(
      (v) => v.target === "/var/lib/model-quota",
    ).source = "other_model_quota";
  },
  "callback admission overload": (s) => {
    s["voice-gateway"].environment.VOICE_CALLBACK_CONCURRENCY = "32";
  },
  "duplicate Java ports": (s) => {
    s["callback-worker-2"].environment.PORT =
      s["callback-worker-1"].environment.PORT;
  },
  "partial durable worker coverage": (s) => {
    s["control-api"].environment.CALLBACK_INBOX_MIN_WORKERS = "1";
  },
  "unbounded batches": (s) => {
    s["voice-gateway"].environment.VOICE_CALLBACK_BATCH_SIZE = "100";
  },
  "reused cleanup credential": (s) => {
    s["voice-gateway"].environment.SERVICE_TOKEN =
      s["voice-gateway"].environment.VOICE_RECORDING_CLEANUP_TOKEN;
  },
  "missing Redis TLS": (s) => {
    s.redis.healthcheck.test = ["CMD", "redis-cli", "ping"];
  },
  "API owns queue": (s) => {
    s["control-api"].environment.JAVA_WORKERS_ENABLED = "true";
  },
  "host resource overflow": (s) => {
    s["voice-gateway"].cpus = "100";
  },
}))
  test("profile rejects " + name, () => {
    const config = fixture();
    mutate(config.services);
    assert(!assessProfile(config).static_config_passed);
  });
