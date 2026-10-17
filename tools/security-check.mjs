import {
  fs,
  path,
  ROOT,
  readJSON,
  writeJSON,
  sha,
  walk,
  xml,
  child,
  descendants,
} from "./lib/common.mjs";
import { pathToFileURL } from "node:url";
const equal = (a, b) => a.size === b.size && [...a].every((x) => b.has(x));
const inc = (map, key) => map.set(key, (map.get(key) ?? 0) + 1);
export function findingKey(f) {
  const s = child(f, "SourceLine")?.attrs,
    m = child(f, "Method")?.attrs;
  if (!s || !m) throw Error("Malformed SAST finding");
  return [
    f.attrs.type,
    s.sourcepath,
    m.classname,
    m.name,
    m.signature,
    s.start,
    s.end,
    f.attrs.priority,
  ]
    .map((v) => {
      if (v === undefined) throw Error("Missing SAST attribute");
      return v;
    })
    .join(":");
}
export function evaluate(module) {
  const target = path.join(module, "target"),
    bom = readJSON(path.join(target, "java-dependencies.cdx.json")),
    osv = readJSON(path.join(target, "osv.json")),
    reviews = readJSON(path.join(module, "security/reviewed-findings.json")),
    entrypoints = readJSON(path.join(module, "security/entrypoints.json")),
    scope = readJSON(path.join(module, "security/review-scope.json")),
    root = xml(path.join(target, "spotbugsXml.xml")),
    errors = [];
  const sources = new Set(
    ["src/main/java", "src/main/resources"]
      .flatMap((d) => walk(path.join(module, d)))
      .map((p) => path.relative(module, p)),
  );
  if (!equal(sources, new Set(Object.keys(scope))))
    errors.push("Runtime source scope changed; security review required");
  for (const [p, d] of Object.entries(scope))
    if (!fs.existsSync(path.join(module, p)) || sha(path.join(module, p)) !== d)
      errors.push("Runtime source review invalidated: " + p);
  const components = new Set(
      bom.components
        .filter((c) => c.purl?.startsWith("pkg:maven/"))
        .map((c) => c.purl.split("?")[0]),
    ),
    scanned = new Set(),
    advisories = new Set();
  for (const result of osv.results ?? [])
    for (const p of result.packages ?? []) {
      if (p.package.ecosystem === "Maven")
        scanned.add(
          "pkg:maven/" +
            p.package.name.replace(":", "/") +
            "@" +
            p.package.version,
        );
      for (const v of p.vulnerabilities ?? []) advisories.add(v.id);
    }
  if (!components.size || ![...components].every((c) => scanned.has(c)))
    errors.push("OSV package coverage is incomplete");
  if (advisories.size)
    errors.push("Dependency advisories: " + [...advisories].sort().join(", "));
  const compiled = new Set(
      walk(path.join(target, "classes"))
        .filter((p) => p.endsWith(".class"))
        .map((p) =>
          path
            .relative(path.join(target, "classes"), p)
            .slice(0, -6)
            .replaceAll(path.sep, "."),
        ),
    ),
    analyzedClasses = new Set(
      descendants(root, "ClassStats").map((n) => n.attrs.class),
    );
  const analyzed = Number(
      child(root, "FindBugsSummary")?.attrs.total_classes ?? 0,
    ),
    scanErrors = child(root, "Errors");
  if (
    !compiled.size ||
    analyzed !== compiled.size ||
    !equal(compiled, analyzedClasses) ||
    !scanErrors ||
    Number(scanErrors.attrs.errors ?? 0) !== 0 ||
    Number(scanErrors.attrs.missingClasses ?? 0) !== 0 ||
    descendants(scanErrors, "Error").length ||
    descendants(scanErrors, "MissingClass").length
  )
    errors.push("SAST class coverage or analysis errors");
  const findings = root.children.filter((c) => c.name === "BugInstance"),
    counts = new Map(),
    routes = new Map();
  for (const f of findings) {
    const key = findingKey(f),
      priority = Number(f.attrs.priority);
    if (![1, 2, 3].includes(priority)) throw Error("Invalid priority");
    if (f.attrs.type === "SPRING_ENDPOINT") {
      inc(routes, key);
      const r = entrypoints[key];
      if (priority !== 3 || !r?.access || !r?.evidence)
        errors.push("Unreviewed or changed route: " + key);
      continue;
    }
    inc(counts, key);
    const r = reviews[key];
    if (!r?.reason || !r?.evidence) {
      errors.push("Unreviewed finding: " + key);
      continue;
    }
    const source = child(f, "SourceLine").attrs.sourcepath;
    if (source !== r.source) errors.push("Review source mismatch: " + key);
    if (!Object.hasOwn(r.sha256 ?? {}, "src/main/java/" + source))
      errors.push("Review must bind its own source: " + key);
    for (const [p, d] of Object.entries(r.sha256 ?? {}))
      if (sha(path.join(module, p)) !== d)
        errors.push("Review invalidated by source change: " + key + ": " + p);
  }
  for (const [k, n] of counts)
    if (reviews[k] && n !== reviews[k].count)
      errors.push("Review occurrence count changed: " + k);
  for (const k of Object.keys(reviews))
    if (!counts.has(k)) errors.push("Stale review must be removed: " + k);
  for (const k of Object.keys(entrypoints))
    if (!routes.has(k)) errors.push("Stale route review must be removed: " + k);
  for (const [k, n] of routes)
    if (entrypoints[k] && n !== entrypoints[k].count)
      errors.push("Route occurrence count changed: " + k);
  const types = {};
  for (const f of findings)
    types[f.attrs.type] = (types[f.attrs.type] ?? 0) + 1;
  const report = {
    passed: !errors.length,
    dependency_packages: components.size,
    scanned_packages: scanned.size,
    dependency_advisories: [...advisories].sort(),
    sast_classes: analyzed,
    sast_raw_findings: findings.length,
    sast_reviewed_high_medium: findings.filter(
      (f) => Number(f.attrs.priority) <= 2,
    ).length,
    sast_reviewed_low_priority: findings.filter(
      (f) =>
        Number(f.attrs.priority) === 3 && f.attrs.type !== "SPRING_ENDPOINT",
    ).length,
    sast_reviewed_actionable: [...counts.values()].reduce((a, b) => a + b, 0),
    sast_reviewed_entrypoints: [...routes.values()].reduce((a, b) => a + b, 0),
    sast_unreviewed_findings: errors.filter((e) => e.includes("Unreviewed"))
      .length,
    source_scope_files: Object.keys(scope).length,
    sast_low_priority: findings.filter((f) => Number(f.attrs.priority) > 2)
      .length,
    sast_by_type: types,
    errors,
  };
  writeJSON(path.join(target, "security-summary.json"), report);
  return report;
}
if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const module = path.resolve(ROOT, process.argv[2] ?? "backend-java");
  if (!["backend-java", "services-java"].includes(path.basename(module)))
    throw Error("Unknown module");
  const r = evaluate(module);
  console.log(JSON.stringify(r, null, 2));
  if (!r.passed) process.exitCode = 1;
}
