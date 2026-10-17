import net from "node:net";
import { assert } from "./common.mjs";
export async function eslCommand(host, port, secret, command) {
  assert(/^[A-Za-z0-9]{24,256}$/.test(secret), "Invalid ESL secret");
  assert(
    typeof command === "string" && !/[\r\n]/.test(command),
    "Single ESL command required",
  );
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, host);
    let data = Buffer.alloc(0),
      step = 0,
      headers = null,
      length = 0,
      done = false;
    const fail = (e) => {
      if (!done) {
        done = true;
        socket.destroy();
        reject(e);
      }
    };
    socket.setTimeout(5000, () => fail(Error("ESL deadline")));
    socket.on("error", fail);
    socket.on("end", () => {
      if (!done) fail(Error("ESL EOF"));
    });
    socket.on("data", (chunk) => {
      data = Buffer.concat([data, chunk]);
      if (data.length > 2 * 1024 * 1024)
        return fail(Error("ESL frame too large"));
      try {
        for (;;) {
          if (!headers) {
            const idx = data.indexOf("\n\n");
            if (idx < 0) return;
            assert(idx <= 65536);
            headers = {};
            for (const line of data.subarray(0, idx).toString().split("\n")) {
              const colon = line.indexOf(":");
              assert(colon > 0);
              const key = line.slice(0, colon);
              assert(!(key in headers), "Duplicate ESL header");
              headers[key] = line.slice(colon + 1).trim();
            }
            data = data.subarray(idx + 2);
            length = Number(headers["Content-Length"] ?? 0);
            assert(
              Number.isInteger(length) && length >= 0 && length <= 1024 * 1024,
            );
          }
          if (data.length < length) return;
          const body = data.subarray(0, length).toString();
          data = data.subarray(length);
          const h = headers;
          headers = null;
          if (step === 0) {
            assert.equal(h["Content-Type"], "auth/request");
            socket.write("auth " + secret + "\n\n");
            step++;
          } else if (step === 1) {
            assert(
              h["Reply-Text"]?.startsWith("+OK"),
              "ESL authentication rejected",
            );
            socket.write("api " + command + "\n\n");
            step++;
          } else {
            done = true;
            socket.end();
            return resolve(body);
          }
        }
      } catch (e) {
        fail(e);
      }
    });
  });
}
