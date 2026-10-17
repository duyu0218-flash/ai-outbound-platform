import { spawn } from "node:child_process";
import {
  fs,
  path,
  os,
  crypto,
  args,
  readJSON,
  assert,
  sleep,
} from "./lib/common.mjs";
import { RotatingLog } from "./lib/statistics.mjs";
export function validate(m, smoke = false) {
  assert(
    Array.isArray(m.argv) &&
      m.argv.length &&
      m.argv.every((v) => typeof v === "string" && v),
    "argv must be a nonempty array; shell strings not accepted",
  );
  const n = m.duration_seconds;
  assert(
    Number.isInteger(n) &&
      (smoke ? n >= 1 && n <= 60 : [28800, 86400].includes(n)),
    "Use 8/24h or --smoke 1..60s",
  );
  assert(
    typeof m.target_host === "string" && m.target_host,
    "Explicit target required",
  );
  if (!smoke)
    assert(
      ![os.hostname(), "localhost", "127.0.0.1", "::1"].includes(m.target_host),
      "Independent load host required",
    );
  const required = [
    "backend",
    "agent",
    "voice_gateway",
    "recording_adapter",
    "freeswitch",
    "postgres",
    "redis",
  ];
  assert(
    m.images &&
      required.every((k) => k in m.images) &&
      Object.values(m.images).every(
        (v) => typeof v === "string" && /^.+@sha256:[a-f0-9]{64}$/.test(v),
      ),
    "All service images require immutable digests",
  );
  return n;
}
export async function supervise(m, output, { smoke = false } = {}) {
  const duration = validate(m, smoke);
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.mkdirSync(output, { recursive: false, mode: 0o700 });
  const bytes = Buffer.from(JSON.stringify(m)),
    manifest = path.join(output, "manifest.json");
  fs.writeFileSync(manifest, bytes, { flag: "wx", mode: 0o600 });
  const fd = fs.openSync(path.join(output, "journal.jsonl"), "wx", 0o600),
    log = new RotatingLog(output);
  const record = (v) => {
    fs.writeSync(fd, JSON.stringify({ at: Date.now() / 1000, ...v }) + "\n");
    fs.fsyncSync(fd);
  };
  let p,
    interrupted = false,
    reached = false,
    exit = null,
    spawnError = null,
    captureError = null;
  const signal = () => {
    interrupted = true;
  };
  process.on("SIGINT", signal);
  process.on("SIGTERM", signal);
  const start = performance.now();
  try {
    record({
      event: "started",
      load_host: os.hostname(),
      target_host: m.target_host,
      manifest_sha256: crypto.createHash("sha256").update(bytes).digest("hex"),
      duration_seconds: duration,
      smoke,
    });
    p = spawn(m.argv[0], m.argv.slice(1), {
      detached: true,
      stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env, ACCEPTANCE_OUTPUT_DIR: path.resolve(output) },
    });
    p.once("error", (e) => {
      spawnError = e.name;
    });
    p.once("exit", (code, sig) => {
      exit = code ?? sig;
    });
    for (const stream of [p.stdout, p.stderr])
      stream.on("data", (data) => {
        try {
          log.write(data);
        } catch (e) {
          captureError = e.name;
        }
      });
    while (!interrupted) {
      const elapsed = (performance.now() - start) / 1000;
      record({
        event: "checkpoint",
        elapsed_seconds: elapsed,
        process_exit: exit,
        log_parts: log.part,
        capture_errors: [spawnError, captureError].filter(Boolean),
      });
      if (exit !== null || spawnError || captureError) break;
      if (elapsed >= duration) {
        reached = true;
        break;
      }
      await sleep(Math.min(1000, (duration - elapsed) * 1000));
    }
  } finally {
    if (p?.pid) {
      const kill = (s) => {
        try {
          process.kill(-p.pid, s);
        } catch (e) {
          if (e.code !== "ESRCH") captureError = e.name;
        }
      };
      const wasRunning = p.exitCode === null && !p.signalCode;
      kill("SIGTERM");
      if (wasRunning)
        await Promise.race([
          new Promise((r) => p.once("close", r)),
          sleep(15000),
        ]);
      kill("SIGKILL");
      if (p.exitCode === null && !p.signalCode)
        await Promise.race([
          new Promise((r) => p.once("close", r)),
          sleep(500),
        ]);
    }
    try {
      log.close();
    } catch (e) {
      captureError = e.name;
    }
    process.off("SIGINT", signal);
    process.off("SIGTERM", signal);
  }
  const errors = [spawnError, captureError].filter(Boolean),
    result = {
      supervision_completed: reached && !interrupted && !errors.length,
      interrupted,
      elapsed_seconds: (performance.now() - start) / 1000,
      process_exit_before_cleanup: exit,
      capture_errors: errors,
      real_500_capacity_verified: false,
      smoke,
      qualification_status:
        "unverified: review exported SIP/RTP/cloud/recording traces",
      cleanup_scope: "only this run process group; no shared resource deletion",
    };
  record({ event: "finished", ...result });
  fs.closeSync(fd);
  fs.writeFileSync(
    path.join(output, "result.json"),
    JSON.stringify(result, null, 2) + "\n",
    { flag: "wx", mode: 0o600 },
  );
  return result;
}
if (process.argv[1]?.endsWith("soak.mjs")) {
  const a = args(),
    r = await supervise(readJSON(a.manifest), a.output, {
      smoke: a.smoke === true,
    });
  console.log(JSON.stringify(r));
  if (!r.supervision_completed) process.exitCode = 1;
}
