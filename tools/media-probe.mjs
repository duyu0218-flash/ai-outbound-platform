import { createServer } from "node:http";
import {
  fs,
  path,
  crypto,
  args,
  number,
  http,
  assert,
  wait,
  sleep,
  writeJSON,
} from "./lib/common.mjs";
import { eslCommand } from "./lib/esl.mjs";
import { upgrade } from "./lib/websocket.mjs";
export function tone(seconds = 1.2) {
  assert(Number.isFinite(seconds) && seconds > 0 && seconds <= 6);
  const pcm = Buffer.alloc(Math.round(seconds * 8000) * 2);
  for (let i = 0; i < pcm.length / 2; i++)
    pcm.writeInt16LE(
      Math.round(10000 * Math.sin((2 * Math.PI * 1000 * i) / 8000)),
      i * 2,
    );
  return pcm;
}
export function wav(bytes) {
  assert.equal(bytes.subarray(0, 4).toString(), "RIFF");
  assert.equal(bytes.subarray(8, 12).toString(), "WAVE");
  let rate = 0,
    channels = 0,
    bits = 0,
    pcm;
  for (let p = 12; p + 8 <= bytes.length; ) {
    const tag = bytes.subarray(p, p + 4).toString(),
      n = bytes.readUInt32LE(p + 4);
    assert(p + 8 + n <= bytes.length);
    if (tag === "fmt ") {
      assert(n >= 16 && bytes.readUInt16LE(p + 8) === 1, "PCM required");
      channels = bytes.readUInt16LE(p + 10);
      rate = bytes.readUInt32LE(p + 12);
      bits = bytes.readUInt16LE(p + 22);
    }
    if (tag === "data") pcm = bytes.subarray(p + 8, p + 8 + n);
    p += 8 + n + (n % 2);
  }
  assert(rate > 0 && channels > 0 && bits === 16 && pcm);
  return {
    rate,
    channels,
    samples: Array.from(
      { length: Math.floor(pcm.length / (2 * channels)) },
      (_, i) => pcm.readInt16LE(i * channels * 2),
    ),
  };
}
export function tonePower(samples, rate, frequency) {
  const coefficient = 2 * Math.cos((2 * Math.PI * frequency) / rate);
  let previous = 0,
    older = 0;
  for (const x of samples) {
    const current = x + coefficient * previous - older;
    older = previous;
    previous = current;
  }
  return previous * previous + older * older - coefficient * previous * older;
}
const a = args();
if (a.serve) {
  assert(process.env.ENV === "test", "Synthetic probe requires ENV=test");
  const secret = fs
      .readFileSync("/run/secrets/voismart_esl_password", "utf8")
      .trim(),
    token = crypto
      .createHash("sha256")
      .update(secret + ":probe-service")
      .digest("hex"),
    host = process.env.FREESWITCH_ESL_HOST ?? "freeswitch-media",
    owned = new Map(),
    states = new Map(),
    esl = (c) => eslCommand(host, 8021, secret, c);
  const server = createServer(async (req, res) => {
    const send = (status, value) => {
      res.writeHead(status, {
        "Content-Type": "application/json",
        "X-Content-Type-Options": "nosniff",
      });
      res.end(JSON.stringify(value));
    };
    try {
      const u = new URL(req.url, "http://fixture");
      if (u.pathname === "/health") return send(200, { synthetic_only: true });
      if (req.headers.authorization !== "Bearer " + token)
        return send(401, { error: "unauthorized" });
      if (u.pathname === "/probe/status" && req.method === "GET")
        return send(200, {
          synthetic_only: true,
          active_sessions: owned.size,
          inputs: Object.fromEntries(states),
        });
      if (u.pathname === "/probe/call" && req.method === "POST") {
        assert(owned.size < 5, "Probe capacity");
        const id = crypto.randomUUID();
        owned.set(id, null);
        states.set(id, { bytes: 0, closed: false });
        try {
          const result = await esl(
            `originate {origination_uuid=${id},execute_on_answer='sched_hangup +30 ALLOTTED_TIMEOUT'}null/probe &park()`,
          );
          assert(result.startsWith("+OK"));
          for (const variable of [
            "STREAM_DISABLE_AUDIOFILES",
            "STREAM_NO_RECONNECT",
            "STREAM_SUPPRESS_LOG",
          ])
            await esl(`uuid_setvar ${id} ${variable} true`);
          await esl(
            `uuid_raw_audio_stream ${id} start ws://media-probe:8002/stream/${id} mono 8000 8000`,
          );
          await esl(`uuid_broadcast ${id} silence_stream://-1 aleg`);
          send(200, { call_id: id, fs_uuid: id, synthetic_only: true });
        } catch (e) {
          owned.delete(id);
          throw e;
        }
        return;
      }
      const match = u.pathname.match(
        /^\/probe\/(tone|interrupt|close|hangup)\/([0-9a-f-]{36})$/,
      );
      if (match && req.method === "POST") {
        const [, action, id] = match;
        assert(owned.has(id), "Unknown owned channel");
        const socket = owned.get(id);
        if (action === "tone") {
          assert(socket, "Media not connected");
          socket.send(tone(u.searchParams.get("long") === "true" ? 6 : 1.2));
          socket.text({ type: "response.output_audio.done" });
        }
        if (action === "interrupt") {
          assert(socket);
          socket.text({ type: "input_audio_buffer.speech_started" });
        }
        if (action === "close") socket?.close();
        if (action === "hangup") {
          await esl("uuid_kill " + id + " NORMAL_CLEARING");
          socket?.close();
          owned.delete(id);
        }
        return send(200, { synthetic_only: true });
      }
      send(404, { error: "no real dial or arbitrary ESL route" });
    } catch {
      if (!res.headersSent)
        send(503, { error: "synthetic probe operation failed" });
      else res.destroy();
    }
  });
  server.on("upgrade", (req, socket, head) => {
    const id = req.url.match(/^\/stream\/([0-9a-f-]{36})$/)?.[1];
    if (!id || !owned.has(id) || owned.get(id)) {
      socket.destroy();
      return;
    }
    try {
      const ws = upgrade(req, socket, head, {
        onMessage: (b, opcode) => {
          if (opcode === 2) states.get(id).bytes += b.length;
        },
        onClose: () => {
          states.get(id).closed = true;
        },
      });
      owned.set(id, ws);
    } catch {
      socket.destroy();
    }
  });
  const stop = async () => {
    for (const [id, s] of owned) {
      try {
        await esl("uuid_kill " + id + " NORMAL_CLEARING");
      } catch {}
      s?.close();
    }
    server.close(() => process.exit(0));
  };
  process.once("SIGTERM", stop);
  process.once("SIGINT", stop);
  server.listen(8002, "0.0.0.0");
} else if (a.verify) {
  const base = a.url ?? "http://127.0.0.1:8002",
    secret = fs
      .readFileSync(
        a["secret-file"] ?? "/run/secrets/voismart_esl_password",
        "utf8",
      )
      .trim(),
    headers = {
      Authorization:
        "Bearer " +
        crypto
          .createHash("sha256")
          .update(secret + ":probe-service")
          .digest("hex"),
    },
    host = a["esl-host"] ?? "freeswitch-media",
    port = number(a["esl-port"] ?? 8021, "ESL port", {
      min: 1,
      max: 65535,
      integer: true,
    }),
    esl = (c) => eslCommand(host, port, secret, c),
    checks = [];
  await http(base, "/probe/call", {
    method: "POST",
    headers: { Authorization: "Bearer invalid" },
    status: 401,
  });
  await http(base, "/v1/call/dial", { method: "POST", headers, status: 404 });
  checks.push("authenticated probe and no dial route");
  const timerId = crypto.randomUUID(),
    timerStarted = performance.now();
  try {
    assert(
      (
        await esl(
          `originate {origination_uuid=${timerId},execute_on_answer='sched_hangup +2 ALLOTTED_TIMEOUT'}null/security-qa &park()`,
        )
      ).startsWith("+OK"),
    );
    await wait(
      async () => (await esl("uuid_exists " + timerId)).trim() === "true",
      3,
    );
    await wait(
      async () => (await esl("uuid_exists " + timerId)).trim() === "false",
      6,
    );
    assert(performance.now() - timerStarted >= 1500);
    checks.push({
      mode: "native_timer_without_controller",
      elapsed_ms: performance.now() - timerStarted,
    });
  } finally {
    await esl("uuid_kill " + timerId + " NORMAL_CLEARING");
  }
  for (const mode of [
    "drain",
    "interrupt",
    "module_backlog_interrupt",
    "socket_disconnect",
  ]) {
    const call = (
        await http(base, "/probe/call", {
          method: "POST",
          headers,
          status: 200,
        })
      ).json,
      id = call.fs_uuid,
      file = (a["recording-dir"] ?? "/recordings") + "/" + id + ".wav";
    const post = (p) =>
      http(base, p + "/" + id, { method: "POST", headers, status: 200 });
    try {
      await wait(
        async () =>
          (await http(base, "/probe/status", { headers, status: 200 })).json
            .inputs[id]?.bytes >= 3200,
        15,
      );
      if (mode === "socket_disconnect") {
        await post("/probe/close");
        await wait(
          async () =>
            (await http(base, "/probe/status", { headers, status: 200 })).json
              .inputs[id].closed,
        );
        checks.push({ mode, closed: true });
        continue;
      }
      await esl(`uuid_setvar ${id} RECORD_READ_ONLY false`);
      await esl(`uuid_record ${id} start ${file}`);
      await http(base, `/probe/tone/${id}?long=${mode !== "drain"}`, {
        method: "POST",
        headers,
        status: 200,
      });
      if (mode === "drain") await sleep(1800);
      else {
        await sleep(300);
        await post("/probe/interrupt");
        await sleep(500);
      }
      await esl(`uuid_record ${id} stop ${file}`);
      const { rate, samples } = wav(fs.readFileSync(file)),
        window = Math.floor(rate / 50);
      let audible = 0;
      for (let i = 0; i < samples.length; i += window) {
        const w = samples.slice(i, i + window);
        if (w.reduce((s, x) => s + x * x, 0) / w.length > 500 ** 2)
          audible += 0.02;
      }
      assert(
        mode === "drain"
          ? audible >= 1.05 && audible <= 1.4
          : audible > 0.1 && audible < 1.5,
        `${mode}: audible=${audible} rate=${rate} samples=${samples.length}`,
      );
      const peak = Array.from({ length: 21 }, (_, i) => 500 + i * 50).sort(
        (x, y) => tonePower(samples, rate, y) - tonePower(samples, rate, x),
      )[0];
      assert.equal(peak, 1000);
      if (mode !== "drain")
        assert(
          samples.slice(-rate / 5).every((x) => Math.abs(x) < 100),
          "Stale interrupt tail",
        );
      checks.push({ mode, audible_seconds: audible, recorded_tone_hz: peak });
    } finally {
      await post("/probe/hangup");
      await wait(
        async () => (await esl("uuid_exists " + id)).trim() === "false",
      );
    }
  }
  const report = {
    passed: true,
    checks,
    synthetic_only: true,
    real_sip_rtp_cloud_speech_verified: false,
    boundary:
      "Native FreeSWITCH PCM/drain/interruption fixture. Java gateway/media ownership and provider contracts have separate JUnit/process/container tests; not a full real-line gateway acceptance.",
  };
  if (a.report) writeJSON(a.report, report);
  console.log(JSON.stringify(report, null, 2));
}
