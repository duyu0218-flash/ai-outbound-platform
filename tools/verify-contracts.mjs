import { fs, path, ROOT, readJSON, writeJSON, assert } from "./lib/common.mjs";
const norm = (p) => p.replace(/\{[^}]+\}/g, "{}"),
  rows = [];
for (const [role, controller] of Object.entries({
  agent: "AgentController",
  recording: "RecordingController",
  voice: "VoiceController",
  media: "MediaWorkerController",
})) {
  const source = fs.readFileSync(
      path.join(
        ROOT,
        "services-java/src/main/java/com/duozhi/services",
        controller + ".java",
      ),
      "utf8",
    ),
    actual = new Set(["GET /docs", "GET /openapi.json"]);
  for (const [, method, p] of source.matchAll(
    /@(Get|Post|Put|Delete|Patch)Mapping\((?:value=)?"([^"]+)"/g,
  ))
    actual.add(method.toUpperCase() + " " + norm(p));
  if (role === "voice") {
    actual.delete("POST /v1/call/{}");
    for (const a of [
      "dial",
      "speak",
      "stop-speaking",
      "transfer",
      "hangup",
      "status",
    ])
      actual.add("POST /v1/call/" + a);
  }
  if (["voice", "media"].includes(role)) actual.add("WS /v1/pipecat/media/{}");
  for (const r of readJSON(
    path.join(ROOT, "tools/legacy-routes.json"),
  ).routes.filter((r) => r.role === role))
    assert(
      actual.has(r.method + " " + norm(r.path)),
      `${role}: missing legacy route ${r.method} ${r.path}`,
    );
  for (const key of actual)
    rows.push({ role, method: key.split(" ")[0], path: key.split(" ")[1] });
}
const core = new Map();
for (const p of fs
  .readdirSync(
    path.join(ROOT, "backend-java/src/main/java/com/duozhi/outbound"),
  )
  .filter((p) => p.endsWith(".java"))) {
  const s = fs.readFileSync(
      path.join(ROOT, "backend-java/src/main/java/com/duozhi/outbound", p),
      "utf8",
    ),
    prefix = s.match(/@RequestMapping\("([^"]+)"\)/)?.[1] ?? "";
  for (const [, m, a] of s.matchAll(
    /@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?/g,
  )) {
    const suffixes = [...(a ?? "").matchAll(/"([^"]*)"/g)].map((x) => x[1]);
    for (const suffix of suffixes.length ? suffixes : [""])
      core.set(m.toUpperCase() + " " + norm(prefix + suffix), p);
  }
}
const inventory = readJSON(
  path.join(
    ROOT,
    "backend-java/src/main/resources/contracts/migration-inventory.json",
  ),
);
for (const r of inventory.routes)
  assert(
    core.has(r.method + " " + norm(r.path)),
    `Core missing ${r.method} ${r.path}`,
  );
writeJSON(path.join(ROOT, "artifacts/no-python/routes.json"), {
  passed: true,
  core_routes: inventory.routes.length,
  retained_auxiliary_routes: readJSON(
    path.join(ROOT, "tools/legacy-routes.json"),
  ).routes.length,
  routes: rows,
  boundary:
    "Static route accounting only; business equivalence requires runtime tests.",
});
console.log("Frozen legacy routes accounted for without executing old source");
