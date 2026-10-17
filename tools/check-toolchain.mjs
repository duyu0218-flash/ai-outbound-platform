import {
  parseEnv,
  validatePolicy,
  validateFrontend,
  validatePins,
  validateProduction,
} from "./lib/release-policy.mjs";
import {
  fs,
  path,
  ROOT,
  walk,
  readJSON,
  run,
  xml,
  child,
  assert,
  args,
} from "./lib/common.mjs";
const [major, minor] = process.versions.node.split(".").map(Number);
assert(
  major > 22 || (major === 22 && minor >= 13),
  "Use Node 22.13+ for built-in fixture SQLite support",
);
const a = args(),
  exclusions = [
    ".git/",
    ".tools/",
    "artifacts/",
    "frontend/node_modules/",
    "frontend/playwright-report/",
    "frontend/test-results/",
    "backend-java/target/",
    "services-java/target/",
    "backend/app/static/",
  ];
const files = walk(ROOT)
  .map((p) => path.relative(ROOT, p))
  .filter(
    (p) =>
      !exclusions.some((e) => p.startsWith(e)) &&
      !p.split("/").some((v) => v.startsWith(".venv")),
  );
const errors = [];
for (const p of files) {
  if (
    /\.(py|pyc|pyo)$/.test(p) ||
    /(^|\/)(requirements[^/]*\.txt|pyproject\.toml|Pipfile(\.lock)?)$/.test(p)
  )
    errors.push("Retired runtime artifact remains: " + p);
  if (/\.(sh|mjs|ya?ml)$/.test(p) && !p.startsWith("docs/")) {
    const s = fs.readFileSync(path.join(ROOT, p), "utf8");
    if (
      /(?:^|\n)\s*(?:run:\s*)?(?:python[23]?|pip[23]?|pytest|uvicorn)\s|actions\/setup-python|(?:entrypoint|command):\s*\[\s*["']?(?:python|uvicorn)|FROM\s+python:/im.test(
        s,
      )
    )
      errors.push("Executable interpreter dependency: " + p);
  }
}
for (const p of ["backend-java", "services-java"]) {
  const pom = xml(path.join(ROOT, p, "pom.xml"));
  assert.equal(child(child(pom, "parent"), "version").text, "4.1.1");
  assert.equal(child(child(pom, "properties"), "java.version").text, "17");
}
const matrix = readJSON(path.join(ROOT, "compatibility-matrix.json"));
validatePolicy(matrix);
assert.equal(matrix.components.java.constraint, "==17.*");
assert(
  !matrix.components.python &&
    !matrix.components.pipecat &&
    !matrix.components.setuptools,
);
const pkg = readJSON(path.join(ROOT, "frontend/package.json"));
assert.equal(
  pkg.packageManager,
  "pnpm@" + matrix.components.pnpm.constraint.slice(2),
);
validateFrontend(pkg, matrix);
if (a["require-clean-env"]) {
  for (const exe of ["python", "python2", "python3", "pip", "pip3"]) {
    try {
      run("sh", ["-c", 'command -v "$1"', "probe", exe]);
      errors.push("Interpreter exists in clean acceptance environment: " + exe);
    } catch {}
  }
}
if (a["production-pins"]) validatePins(matrix, readJSON(a["production-pins"]));
if (a["production-env"])
  validateProduction(
    matrix,
    parseEnv(fs.readFileSync(a["production-env"], "utf8")),
  );
const workflows = ["ci", "java-core", "java-services"]
  .map((n) =>
    fs.readFileSync(path.join(ROOT, ".github/workflows", n + ".yml"), "utf8"),
  )
  .join("\n");
assert(
  workflows.includes("pull_request:") &&
    workflows.includes("node tools/check-toolchain.mjs"),
  "CI must validate candidate toolchains",
);
for (const [, version] of workflows.matchAll(
  /uses:\s+actions\/[^@\s]+@([^\s]+)/g,
))
  assert(
    /^[0-9a-f]{40}$/.test(version),
    "Action references must be SHA pinned",
  );
const bots = fs.readFileSync(path.join(ROOT, ".github/dependabot.yml"), "utf8");
for (const e of ["npm", "maven", "docker", "github-actions"])
  assert(
    bots.includes('package-ecosystem: "' + e + '"'),
    "Dependency update ecosystem missing: " + e,
  );

assert(!errors.length, errors.join("\n"));
console.log(
  "No-Python source/deployment/toolchain checks passed; production acceptance remains separate.",
);
