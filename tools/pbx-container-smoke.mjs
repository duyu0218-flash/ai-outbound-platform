import {
  fs,
  path,
  ROOT,
  run,
  wait,
  crypto,
  writeJSON,
  assert,
} from "./lib/common.mjs";
const prefix = "java-pbx-check-" + crypto.randomBytes(5).toString("hex"),
  network = prefix + "-net",
  secrets = prefix + "-secrets",
  recordings = prefix + "-audio",
  names = [],
  volumes = [],
  checks = [];
let netCreated = false;
const docker = (...v) => run("docker", v, { timeout: 180000 });
try {
  docker("network", "create", "--label", "codex.task=no-python-pbx", network);
  netCreated = true;
  const subnet = JSON.parse(
    docker("network", "inspect", network),
  )[0].IPAM.Config.find((v) => v.Subnet.includes(".")).Subnet;
  for (const volume of [secrets, recordings]) {
    docker("volume", "create", "--label", "codex.task=no-python-pbx", volume);
    volumes.push(volume);
  }
  run(
    "docker",
    [
      "run",
      "--rm",
      "-i",
      "--user",
      "0:0",
      "-v",
      secrets + ":/secret",
      "node:22-bookworm-slim",
      "node",
      "-e",
      "const f=require('node:fs');f.writeFileSync('/secret/voismart_esl_password',f.readFileSync(0),{mode:0o600});f.chownSync('/secret/voismart_esl_password',10001,10001)",
    ],
    { input: crypto.randomBytes(24).toString("hex") },
  );
  const pbx = prefix + "-pbx";
  docker(
    "run",
    "-d",
    "--name",
    pbx,
    "--label",
    "codex.task=no-python-pbx",
    "--network",
    network,
    "--network-alias",
    "freeswitch-media",
    "--read-only",
    "--cap-drop=ALL",
    "--security-opt=no-new-privileges:true",
    "--tmpfs",
    "/tmp:rw,nosuid,size=256m,uid=10001,gid=10001",
    "-e",
    "MEDIA_ESL_ALLOWED_CIDR=" + subnet,
    "-v",
    path.join(ROOT, "deploy/freeswitch-voismart/freeswitch.xml") +
      ":/opt/media-config/freeswitch.xml:ro",
    "-v",
    secrets + ":/run/secrets:ro",
    "-v",
    recordings + ":/recordings",
    "ai-freeswitch-voismart-java:2.5.1",
  );
  names.push(pbx);
  await wait(() => {
    try {
      docker("exec", pbx, "java", "-cp", "/opt/media-tools", "Pbx", "health");
      return true;
    } catch {
      return false;
    }
  }, 60);
  checks.push("nonroot read-only native PBX and Java authenticated ESL health");
  const probe = prefix + "-probe";
  docker(
    "run",
    "-d",
    "--name",
    probe,
    "--label",
    "codex.task=no-python-pbx",
    "--network",
    network,
    "--network-alias",
    "media-probe",
    "--read-only",
    "--user",
    "10001:10001",
    "--cap-drop=ALL",
    "--security-opt=no-new-privileges:true",
    "--tmpfs",
    "/tmp:rw,nosuid,size=64m,uid=10001,gid=10001",
    "-e",
    "ENV=test",
    "-e",
    "FREESWITCH_ESL_HOST=freeswitch-media",
    "-v",
    path.join(ROOT, "tools") + ":/workspace/tools:ro",
    "-v",
    secrets + ":/run/secrets:ro",
    "-v",
    recordings + ":/recordings:ro",
    "-w",
    "/workspace",
    "node:22-bookworm-slim",
    "node",
    "tools/media-probe.mjs",
    "--serve",
  );
  names.push(probe);
  await wait(() => {
    try {
      docker(
        "exec",
        probe,
        "node",
        "-e",
        "fetch('http://127.0.0.1:8002/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))",
      );
      return true;
    } catch {
      return false;
    }
  }, 30);
  docker(
    "exec",
    probe,
    "node",
    "tools/media-probe.mjs",
    "--verify",
    "--report",
    "/tmp/pcm-results.json",
  );
  const report = JSON.parse(
    docker("exec", probe, "cat", "/tmp/pcm-results.json"),
  );
  assert(report.passed);
  checks.push(...report.checks);
  for (const name of names)
    docker(
      "exec",
      name,
      "sh",
      "-c",
      'for p in python python3 pip pip3; do if command -v "$p" >/dev/null 2>&1; then exit 1; fi; done',
    );
  checks.push("PBX and Node PCM fixture contain no interpreter or pip");
  writeJSON(path.join(ROOT, "artifacts/no-python/pbx-container.json"), {
    passed: true,
    image_id: docker(
      "image",
      "inspect",
      "--format",
      "{{.Id}}",
      "ai-freeswitch-voismart-java:2.5.1",
    ),
    checks,
    synthetic_only: true,
    real_line_verified: false,
    boundary: report.boundary,
  });
  console.log("Native PBX/Java/Node PCM checks passed");
} finally {
  fs.mkdirSync(path.join(ROOT, "artifacts/no-python"), { recursive: true });
  for (const name of names) {
    try {
      if (name.endsWith("-pbx"))
        docker(
          "cp",
          name + ":/recordings",
          path.join(ROOT, "artifacts/no-python", name + "-recordings"),
        );
    } catch {}
    try {
      fs.writeFileSync(
        path.join(ROOT, "artifacts/no-python", name + ".log"),
        docker("logs", name),
      );
    } catch {}
  }
  for (const name of names.reverse())
    try {
      docker("rm", "-f", name);
    } catch {}
  for (const v of volumes)
    try {
      docker("volume", "rm", v);
    } catch {}
  if (netCreated)
    try {
      docker("network", "rm", network);
    } catch {}
}
