import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { spawn, spawnSync } from "node:child_process";
import net from "node:net";
import assert from "node:assert/strict";
export { fs, path, os, crypto, assert };
export const ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../..",
);
export const java = () =>
  process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin/java") : "java";
export const readJSON = (p) => JSON.parse(fs.readFileSync(p, "utf8"));
export function writeJSON(p, v) {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, JSON.stringify(v, null, 2) + "\n");
}
export const sha = (p) =>
  crypto.createHash("sha256").update(fs.readFileSync(p)).digest("hex");
export function walk(dir) {
  return fs.existsSync(dir)
    ? fs
        .readdirSync(dir, { withFileTypes: true })
        .flatMap((e) =>
          e.isDirectory()
            ? walk(path.join(dir, e.name))
            : [path.join(dir, e.name)],
        )
    : [];
}
export function run(cmd, args = [], opts = {}) {
  const r = spawnSync(cmd, args, {
    cwd: ROOT,
    encoding: "utf8",
    timeout: 120000,
    maxBuffer: 20 * 1024 * 1024,
    ...opts,
  });
  if (r.error || r.status !== 0)
    throw Error(
      `${cmd} failed (${r.status}): ${r.error?.message ?? r.stderr?.slice(-3000)}`,
    );
  return r.stdout.trim();
}
export function xml(p) {
  return JSON.parse(run(java(), [path.join(ROOT, "tools/lib/Xml.java"), p]));
}
export function descendants(node, name) {
  return [
    ...(node.name === name ? [node] : []),
    ...node.children.flatMap((c) => descendants(c, name)),
  ];
}
export const child = (node, name) =>
  node?.children.find((c) => c.name === name);
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export async function wait(check, seconds = 30) {
  const until = Date.now() + seconds * 1000;
  let last;
  while (Date.now() < until) {
    try {
      if (await check()) return;
    } catch (e) {
      last = e;
    }
    await sleep(100);
  }
  throw Error(
    "Timed out waiting for isolated fixture" +
      (last ? ": " + last.message : ""),
  );
}
export async function port() {
  const s = net.createServer();
  await new Promise((r, j) => {
    s.once("error", j);
    s.listen(0, "127.0.0.1", r);
  });
  const p = s.address().port;
  await new Promise((r) => s.close(r));
  return p;
}
export async function http(
  base,
  p,
  { method = "GET", body, headers = {}, status, timeout = 10000 } = {},
) {
  const r = await fetch(base + p, {
    method,
    headers: {
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...headers,
    },
    body:
      body === undefined
        ? undefined
        : typeof body === "string"
          ? body
          : JSON.stringify(body),
    signal: AbortSignal.timeout(timeout),
    redirect: "error",
  });
  const text = await r.text();
  if (status !== undefined)
    assert.equal(r.status, status, `${p}: ${text.slice(0, 1000)}`);
  let json;
  try {
    json = JSON.parse(text);
  } catch {}
  return { status: r.status, headers: r.headers, text, json };
}
export function args(argv = process.argv.slice(2)) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    assert(argv[i].startsWith("--"), "Expected --option");
    const k = argv[i].slice(2);
    assert(!(k in out), "Duplicate option " + k);
    out[k] = argv[i + 1] && !argv[i + 1].startsWith("--") ? argv[++i] : true;
  }
  return out;
}
export function number(
  v,
  name,
  { min = 0, max = Infinity, integer = false } = {},
) {
  const n = Number(v);
  assert(
    v !== undefined &&
      v !== true &&
      Number.isFinite(n) &&
      n >= min &&
      n <= max &&
      (!integer || Number.isInteger(n)),
    `Invalid ${name}`,
  );
  return n;
}
export class Processes {
  items = [];
  start(name, command, argv, env = {}, cwd = ROOT) {
    const output = path.join(ROOT, "artifacts/no-python", name + ".log");
    fs.mkdirSync(path.dirname(output), { recursive: true });
    const fd = fs.openSync(output, "w");
    const p = spawn(command, argv, {
      cwd,
      env: { ...process.env, ...env },
      stdio: ["ignore", fd, fd],
    });
    fs.closeSync(fd);
    p.once("error", (e) => {
      p.startError = e;
    });
    this.items.push(p);
    return p;
  }
  async stop(p, signal = "SIGTERM") {
    if (p.exitCode !== null || p.signalCode) return;
    p.kill(signal);
    await Promise.race([new Promise((r) => p.once("exit", r)), sleep(12000)]);
    if (p.exitCode === null && !p.signalCode) {
      p.kill("SIGKILL");
      await new Promise((r) => p.once("exit", r));
    }
  }
  async close() {
    for (const p of [...this.items].reverse()) await this.stop(p);
  }
}
export async function temporary(prefix, fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  try {
    return await fn(dir);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}
