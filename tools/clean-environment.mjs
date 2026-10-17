import {
  fs,
  path,
  ROOT,
  run,
  wait,
  crypto,
  writeJSON,
  assert,
  args,
} from "./lib/common.mjs";
import { spawn } from "node:child_process";
import { inventory } from "./source-inventory.mjs";
const a = args(),
  prefix = "java-clean-" + crypto.randomBytes(5).toString("hex"),
  network = prefix + "-net",
  image = a.image ?? "outbound-no-python:validation",
  owned = [];
let created = false;
const docker = (...v) => run("docker", v, { timeout: 180000 });
function start(role, image, extra) {
  const name = prefix + "-" + role;
  docker(
    "run",
    "-d",
    "--name",
    name,
    "--label",
    "codex.task=no-python-clean",
    "--network",
    network,
    "--network-alias",
    role,
    ...extra,
    image,
    ...(role === "seaweedfs"
      ? [
          "mini",
          "-dir=/data",
          "-ip=seaweedfs",
          "-ip.bind=0.0.0.0",
          "-master.volumeSizeLimitMB=8",
        ]
      : []),
  );
  owned.push(name);
  return name;
}
writeJSON(path.join(ROOT, "artifacts/no-python/clean-environment.json"), {
  passed: false,
  status: "running",
  run_id: prefix,
});
try {
  docker("network", "create", "--label", "codex.task=no-python-clean", network);
  created = true;
  const pg = start("postgres", "postgres:16-alpine", [
      "--tmpfs",
      "/var/lib/postgresql/data:rw,size=256m",
      "-e",
      "POSTGRES_DB=java_core_test",
      "-e",
      "POSTGRES_USER=java_test",
      "-e",
      "POSTGRES_PASSWORD=isolated-ci-test-password",
    ]),
    redis = start("redis", "redis:7-alpine", []),
    s3 = start("seaweedfs", "chrislusf/seaweedfs:4.44", [
      "--tmpfs",
      "/data:rw,size=256m",
      "-e",
      "AWS_ACCESS_KEY_ID=java-isolated-access",
      "-e",
      "AWS_SECRET_ACCESS_KEY=java-isolated-secret",
      "-e",
      "S3_BUCKET=java-isolated-recordings",
    ]);
  await wait(() => {
    try {
      docker(
        "exec",
        pg,
        "pg_isready",
        "-U",
        "java_test",
        "-d",
        "java_core_test",
      );
      docker("exec", redis, "redis-cli", "ping");
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
  const name = prefix + "-tools";
  owned.push(name);
  const p = spawn(
    "docker",
    [
      "run",
      "--name",
      name,
      "--label",
      "codex.task=no-python-clean",
      "--network",
      network,
      "-e",
      "JAVA_TEST_DATABASE_URL=jdbc:postgresql://postgres:5432/java_core_test",
      "-e",
      "DATABASE_USER=java_test",
      "-e",
      "DATABASE_PASSWORD=isolated-ci-test-password",
      "-e",
      "JAVA_TEST_REDIS_URL=redis://redis:6379/0",
      "-e",
      "JAVA_TEST_S3_ENDPOINT=http://seaweedfs:8333",
      "-e",
      "JAVA_SERVICES_LOAD=1",
      "-e",
      "CLEAN_SECURITY=1",
      image,
      "bash",
      "tools/clean-verify.sh",
      ...(a.browser ? ["--browser"] : []),
    ],
    { cwd: ROOT, stdio: "inherit" },
  );
  const code = await new Promise((resolve, reject) => {
    p.once("error", reject);
    p.once("exit", resolve);
  });
  const evidence = path.join(ROOT, "artifacts/no-python", prefix);
  fs.mkdirSync(evidence, { recursive: true });
  const copyErrors = [];
  for (const folder of [
    "artifacts/no-python",
    "backend-java/target/surefire-reports",
    "services-java/target/surefire-reports",
    ...(a.browser ? ["frontend/test-results"] : []),
    ...["backend-java", "services-java"].flatMap((m) =>
      [
        "java-dependencies.cdx.json",
        "osv.json",
        "spotbugsXml.xml",
        "security-summary.json",
      ].map((f) => m + "/target/" + f),
    ),
  ]) {
    try {
      docker(
        "cp",
        name + ":/workspace/" + folder,
        path.join(evidence, folder.replaceAll("/", "-")),
      );
    } catch {
      copyErrors.push(folder);
    }
  }
  for (const fixture of owned.filter((n) => n !== name))
    docker(
      "exec",
      fixture,
      "sh",
      "-c",
      'for p in python python3 pip pip3; do if command -v "$p" >/dev/null 2>&1; then exit 1; fi; done',
    );
  const snapshotPath = path.join(
    evidence,
    "artifacts-no-python/source-inventory.json",
  );
  let sourceMatches = false;
  try {
    sourceMatches =
      JSON.stringify(
        JSON.parse(fs.readFileSync(snapshotPath)).source_sha256,
      ) === JSON.stringify(inventory());
  } catch {}
  const passed = code === 0 && !copyErrors.length && sourceMatches;
  writeJSON(path.join(ROOT, "artifacts/no-python/clean-environment.json"), {
    passed,
    image_id: docker("image", "inspect", "--format", "{{.Id}}", image),
    browser: a.browser === true,
    python_installed: false,
    source_matches_current: sourceMatches,
    evidence_dir: path.relative(ROOT, evidence),
    copy_errors: copyErrors,
    postgresql_redis_and_s3_fixtures: true,
    exit_code: code,
    boundary:
      "Clean development/test container and isolated services only. No production/real line/capacity claim.",
  });
  assert(
    passed,
    "Clean environment verification/source/evidence failed; inspect retained reports",
  );
} finally {
  for (const name of owned.reverse())
    try {
      docker("rm", "-f", name);
    } catch {}
  if (created)
    try {
      docker("network", "rm", network);
    } catch {}
}
