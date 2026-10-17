import { fs, path, ROOT, walk, sha, writeJSON } from "./lib/common.mjs";
export function inventory() {
  const folders = [
    "tools",
    "backend-java/src",
    "backend-java/scripts",
    "services-java/src",
    "services-java/scripts",
    "scripts",
    "deploy/freeswitch-voismart",
    "frontend/src",
    "frontend/e2e",
    ".github/workflows",
  ];
  const files = folders
    .flatMap((d) => walk(path.join(ROOT, d)))
    .filter((p) => !p.endsWith(".class"))
    .map((p) => path.relative(ROOT, p));
  for (const p of [
    "frontend/package.json",
    "frontend/pnpm-lock.yaml",
    "frontend/pnpm-workspace.yaml",
    "frontend/vite.config.ts",
    "frontend/playwright.config.ts",
    "compatibility-matrix.json",
    "backend-java/Dockerfile",
    "services-java/Dockerfile",
    "backend-java/pom.xml",
    "services-java/pom.xml",
    ".env.example",
    ".dockerignore",
    ".github/dependabot.yml",
    "deploy/compatibility/production-versions.env.example",
    ...fs.readdirSync(ROOT).filter((p) => /^docker-compose.*\.yml$/.test(p)),
  ])
    files.push(p);
  return Object.fromEntries(
    [...new Set(files)].sort().map((p) => [p, sha(path.join(ROOT, p))]),
  );
}
if (process.argv[1]?.endsWith("source-inventory.mjs"))
  writeJSON(path.join(ROOT, "artifacts/no-python/source-inventory.json"), {
    schema_version: 1,
    source_sha256: inventory(),
  });
