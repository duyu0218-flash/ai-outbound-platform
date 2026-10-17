import { test, before, after } from "node:test";
import { spawn } from "node:child_process";
import net from "node:net";
import { fs, path, os, ROOT, java, run, assert } from "../lib/common.mjs";
import { assessCore } from "../pbx.mjs";
import { tone, wav, tonePower } from "../media-probe.mjs";
let folder;
before(() => {
  folder = fs.mkdtempSync(path.join(os.tmpdir(), "pbx-java-tests-"));
  run(path.join(path.dirname(java()), "javac"), [
    "--release",
    "17",
    "-d",
    folder,
    path.join(ROOT, "tools/java/Pbx.java"),
  ]);
});
after(() => fs.rmSync(folder, { recursive: true, force: true }));
function render(
  network = "172.20.0.0/16",
  secret = "a".repeat(32),
  input = path.join(ROOT, "deploy/freeswitch-voismart/freeswitch.xml"),
) {
  const key = path.join(folder, "key");
  fs.writeFileSync(key, secret, { mode: 0o600 });
  const out = path.join(folder, "rendered.xml");
  run(java(), ["-cp", folder, "Pbx", "render", input, out, key, network]);
  return fs.readFileSync(out, "utf8");
}
test("PBX renderer preserves XML, private ACL and secret permissions", () => {
  const s = render();
  assert(!s.includes("__MEDIA_CIDR__"));
  assert(s.includes("172.20.0.0/16"));
  assert(s.includes("a".repeat(32)));
  assert.equal(
    fs.statSync(path.join(folder, "rendered.xml")).mode & 0o777,
    0o600,
  );
});
test("PBX rejects public, broad, unaligned and malformed CIDR plus unsafe secret", () => {
  for (const n of [
    "8.8.0.0/16",
    "172.0.0.0/8",
    "172.20.0.1/16",
    "172.999.0.0/16",
    "172.020.0.0/16",
  ])
    assert.throws(() => render(n));
  assert.throws(() => render("172.20.0.0/16", "unsafe\nsecret"));
});
test("PBX denies DTD/external entity input", () => {
  const p = path.join(folder, "evil.xml");
  fs.writeFileSync(
    p,
    '<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><x>&e;</x>',
  );
  assert.throws(() => render("172.20.0.0/16", "a".repeat(32), p));
});
test("expanded PBX budget checks reject unresolved/duplicate core settings", () => {
  const p = path.join(folder, "core.xml");
  fs.writeFileSync(
    p,
    '<configuration name="switch.conf"><settings><param name="max-sessions" value="500"/><param name="sessions-per-second" value="20"/><param name="rtp-start-port" value="20000"/><param name="rtp-end-port" value="22000"/></settings></configuration>',
  );
  assert(assessCore(p).static_config_passed);
  fs.writeFileSync(
    p,
    fs.readFileSync(p, "utf8").replace('value="500"', 'value="$${unknown}"'),
  );
  assert.throws(() => assessCore(p));
});
test("Java ESL health reads fragmented frames and checks module", async () => {
  let accepted = false;
  const server = net.createServer((socket) => {
    socket.write("Content-Type: auth/request\n\n");
    let data = "",
      stage = 0;
    socket.on("error", () => {});
    socket.on("data", (b) => {
      data += b.toString();
      let i;
      while ((i = data.indexOf("\n\n")) >= 0) {
        const cmd = data.slice(0, i);
        data = data.slice(i + 2);
        if (stage++ === 0) {
          accepted = cmd === "auth " + "a".repeat(32);
          socket.write(
            "Content-Type: command/reply\nReply-Text: " +
              (accepted ? "+OK accepted" : "-ERR") +
              "\n\n",
          );
        } else {
          assert.equal(cmd, "api module_exists mod_openai_audio_stream");
          socket.write("Content-Type: api/response\nContent-Length: 4\n\ntr");
          setTimeout(() => socket.end("ue"), 5);
        }
      }
    });
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  try {
    const p = spawn(
      java(),
      [
        "-cp",
        folder,
        "Pbx",
        "health",
        path.join(folder, "health-key"),
        "127.0.0.1",
        String(server.address().port),
      ],
      { stdio: "pipe" },
    );
    fs.writeFileSync(path.join(folder, "health-key"), "a".repeat(32));
    const status = await new Promise((resolve, reject) => {
      p.once("error", reject);
      p.once("exit", resolve);
    });
    assert.equal(status, 0);
    assert(accepted);
  } finally {
    await new Promise((r) => server.close(r));
  }
});
test("PCM helpers measure tone energy and reject invalid RIFF", () => {
  const pcm = tone(1.2),
    samples = Array.from({ length: pcm.length / 2 }, (_, i) =>
      pcm.readInt16LE(i * 2),
    );
  assert(tonePower(samples, 8000, 1000) > 100 * tonePower(samples, 8000, 600));
  assert.throws(() => tone(100));
  assert.throws(() => wav(Buffer.from("not a wav")));
});
