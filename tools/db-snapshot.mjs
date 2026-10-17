import { connectionEnv } from "./lib/pg-options.mjs";
import { spawnSync } from "node:child_process";
import { fs, path, args, number, sleep, assert } from "./lib/common.mjs";
const QUERIES = {
  waits:
    "SELECT state,wait_event_type,wait_event,count(*) AS sessions FROM pg_stat_activity WHERE pid<>pg_backend_pid() GROUP BY 1,2,3",
  wal: "SELECT * FROM pg_stat_wal",
  checkpoints: "SELECT * FROM pg_stat_bgwriter",
  io: "SELECT * FROM pg_stat_io",
  blocking:
    "SELECT count(*) AS blocked_sessions FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid))>0",
  database:
    "SELECT numbackends,xact_commit,xact_rollback,deadlocks,temp_bytes,blk_read_time,blk_write_time FROM pg_stat_database WHERE datname=current_database()",
};
const a = args(),
  seconds = number(a.seconds ?? 60, "seconds", { min: 1, max: 86400 }),
  interval = number(a.interval ?? 5, "interval", { min: 1, max: 60 });
assert(
  process.env.STABILITY_PG_DSN,
  "Set STABILITY_PG_DSN; credentials never enter process arguments",
);
const connection = connectionEnv(process.env.STABILITY_PG_DSN);
const output = fs.openSync(a.output, "wx", 0o600),
  start = performance.now();
let failed = false;
try {
  for (;;) {
    const sample = { at: Date.now() / 1000 };
    for (const [name, sql] of Object.entries(QUERIES)) {
      const command = a.container ? "docker" : "psql",
        prefix = a.container
          ? [
              "exec",
              ...Object.keys(connection).flatMap((k) => ["-e", k]),
              a.container,
              "psql",
            ]
          : [];
      const result = spawnSync(
        command,
        [
          ...prefix,
          "-X",
          "-q",
          "-t",
          "-A",
          "--no-password",
          "-v",
          "ON_ERROR_STOP=1",
          "-c",
          `SELECT coalesce(json_agg(row_to_json(snapshot)), '[]'::json) FROM (${sql}) snapshot`,
        ],
        {
          encoding: "utf8",
          timeout: 5000,
          env: {
            ...Object.fromEntries(
              Object.entries(process.env).filter(
                ([key]) => !key.startsWith("PG"),
              ),
            ),
            ...connection,
            PGCONNECT_TIMEOUT: "3",
            PGOPTIONS:
              "-c default_transaction_read_only=on -c statement_timeout=2000",
          },
        },
      );
      if (result.error || result.status !== 0) {
        sample.error_type = "ReadOnlyDatabaseProbeFailed";
        failed = true;
        break;
      }
      try {
        sample[name] = JSON.parse(result.stdout);
      } catch {
        sample.error_type = "InvalidSnapshot";
        failed = true;
        break;
      }
    }
    for (const name of [
      "cpu.stat",
      "cpu.max",
      "cpuset.cpus.effective",
      "memory.current",
      "memory.max",
      "memory.events",
      "io.stat",
    ]) {
      const p = "/sys/fs/cgroup/" + name;
      if (fs.existsSync(p))
        sample[name] = fs.readFileSync(p, "utf8").slice(0, 16384);
    }
    fs.writeSync(output, JSON.stringify(sample) + "\n");
    fs.fsyncSync(output);
    const remaining = seconds - (performance.now() - start) / 1000;
    if (remaining <= 0) break;
    await sleep(Math.min(interval, remaining) * 1000);
  }
} finally {
  fs.closeSync(output);
}
if (failed) process.exitCode = 1;
