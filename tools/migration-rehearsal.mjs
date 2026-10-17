import { spawnSync } from "node:child_process";
import {
  fs,
  path,
  ROOT,
  java,
  run,
  wait,
  crypto,
  assert,
  sha,
  writeJSON,
  temporary,
} from "./lib/common.mjs";
const name = "java-migration-only-" + crypto.randomBytes(5).toString("hex"),
  password = crypto.randomBytes(24).toString("hex"),
  tables = [
    "tenant",
    "user",
    "contact",
    "campaign",
    "callsession",
    "speechturn",
    "callusage",
    "recordingasset",
  ],
  jar = path.join(
    ROOT,
    "backend-java/target/outbound-control-0.1.0-SNAPSHOT.jar",
  );
let created = false;
const docker = (...a) => run("docker", a),
  sql = (db, q) =>
    docker(
      "exec",
      name,
      "psql",
      "-v",
      "ON_ERROR_STOP=1",
      "-U",
      "postgres",
      "-d",
      db,
      "-Atc",
      q,
    ),
  snap = (db) =>
    Object.fromEntries(
      tables.map((t) => [
        t,
        sql(
          db,
          `SELECT count(*),md5(COALESCE(string_agg(doc::text,'' ORDER BY doc::text),'')) FROM (SELECT to_jsonb(t)-'voice_ai_pipeline' AS doc FROM "${t}" t) s`,
        ),
      ]),
    );
try {
  docker(
    "run",
    "-d",
    "--name",
    name,
    "--label",
    "codex.task=no-python-migration",
    "--tmpfs",
    "/var/lib/postgresql/data:rw,size=256m",
    "-p",
    "127.0.0.1::5432",
    "-e",
    "POSTGRES_PASSWORD=" + password,
    "postgres:16-alpine",
  );
  created = true;
  await wait(() => {
    try {
      docker(
        "exec",
        name,
        "sh",
        "-c",
        'test "$(cat /proc/1/comm)" = postgres && pg_isready -U postgres',
      );
      return true;
    } catch {
      return false;
    }
  }, 60);
  docker("exec", name, "createdb", "-U", "postgres", "legacy");
  docker("exec", name, "createdb", "-U", "postgres", "restored");
  const schema = fs.readFileSync(
      path.join(
        ROOT,
        "backend-java/src/main/resources/contracts/schema-postgresql.sql",
      ),
      "utf8",
    ),
    seed =
      "INSERT INTO tenant(id,name,code,enabled,created_at,updated_at) VALUES(900001,'Historical tenant','historical',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP); ALTER TABLE callsession DROP COLUMN voice_ai_pipeline; ALTER TABLE taskoutbox DROP COLUMN lease_token; DROP TABLE callbackinboxworker;";
  run(
    "docker",
    [
      "exec",
      "-i",
      name,
      "psql",
      "-v",
      "ON_ERROR_STOP=1",
      "-U",
      "postgres",
      "-d",
      "legacy",
    ],
    { input: schema + seed },
  );
  const before = snap("legacy");
  const dump = spawnSync(
    "docker",
    ["exec", name, "pg_dump", "-U", "postgres", "-Fc", "legacy"],
    { maxBuffer: 20 * 1024 * 1024, timeout: 30000 },
  );
  assert.equal(dump.status, 0);
  const backup = dump.stdout;
  assert(backup.length > 0);
  const mapping = JSON.parse(
      docker("inspect", "--format", "{{json .NetworkSettings.Ports}}", name),
    ),
    port = mapping["5432/tcp"][0].HostPort,
    env = {
      ...process.env,
      JAVA_DATABASE_URL: `jdbc:postgresql://127.0.0.1:${port}/legacy`,
      DATABASE_USER: "postgres",
      DATABASE_PASSWORD: password,
    };
  const migrate = (mode) =>
    JSON.parse(
      run(java(), ["-jar", jar, "--database-migration=" + mode], {
        env,
        timeout: 120000,
      }),
    );
  const plan = migrate("plan");
  assert.equal(plan.writes, false);
  assert.deepEqual(snap("legacy"), before);
  const applied = migrate("apply");
  assert.equal(applied.applied.length, 22);
  assert.deepEqual(snap("legacy"), before);
  assert(migrate("verify").compatible);
  assert.deepEqual(migrate("apply").applied, []);
  run(
    "docker",
    [
      "exec",
      "-i",
      name,
      "pg_restore",
      "-U",
      "postgres",
      "-d",
      "restored",
      "--exit-on-error",
    ],
    { input: backup },
  );
  assert.deepEqual(snap("restored"), before);
  assert.equal(
    sql(
      "restored",
      "SELECT count(*) FROM information_schema.columns WHERE table_name='callsession' AND column_name='voice_ai_pipeline'",
    ),
    "0",
  );
  await temporary("java-db-snapshot-", async (folder) => {
    const output = path.join(folder, "snapshot.jsonl");
    run(
      process.execPath,
      [
        path.join(ROOT, "tools/db-snapshot.mjs"),
        "--container",
        name,
        "--output",
        output,
        "--seconds",
        "1",
        "--interval",
        "1",
      ],
      {
        env: {
          ...process.env,
          STABILITY_PG_DSN: "postgresql:///legacy?user=postgres",
        },
      },
    );
    const rows = fs
      .readFileSync(output, "utf8")
      .trim()
      .split("\n")
      .map(JSON.parse);
    assert(
      rows.length && rows.every((r) => !r.error_type && r.database && r.io),
    );
  });
  writeJSON(path.join(ROOT, "artifacts/no-python/migration-rehearsal.json"), {
    passed: true,
    environment:
      "Disposable PostgreSQL 16, frozen SQL synthetic historical schema only",
    plan_read_only: true,
    readonly_database_snapshot_cli_verified: true,
    applied_versions: 22,
    repeat_applied: 0,
    backup_sha256: crypto.createHash("sha256").update(backup).digest("hex"),
    backup_bytes: backup.length,
    restore_preserves_legacy_schema: true,
    business_snapshots: before,
    jar_sha256: sha(jar),
    boundary: "Synthetic schema/data, not the production old database.",
  });
  console.log(
    "Java/Node migration plan/apply/verify and native pg_dump/restore passed",
  );
} finally {
  if (created) docker("rm", "-f", name);
}
