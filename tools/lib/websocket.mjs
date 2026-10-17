import { crypto, assert } from "./common.mjs";
/** Minimal bounded RFC6455 server for isolated binary PCM fixtures, no extensions. */
export function upgrade(
  req,
  socket,
  head,
  { onMessage = () => {}, onClose = () => {} } = {},
) {
  assert.equal(req.headers.upgrade?.toLowerCase(), "websocket");
  assert.equal(req.headers["sec-websocket-version"], "13");
  const key = req.headers["sec-websocket-key"];
  assert(typeof key === "string" && Buffer.from(key, "base64").length === 16);
  socket.write(
    "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " +
      crypto
        .createHash("sha1")
        .update(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
        .digest("base64") +
      "\r\n\r\n",
  );
  let data = Buffer.from(head),
    fragments = [],
    fragmentOpcode = 0,
    fragmentBytes = 0;
  function send(payload, opcode = 2) {
    if (socket.destroyed) throw Error("Closed websocket");
    const b = Buffer.isBuffer(payload) ? payload : Buffer.from(payload),
      n = b.length;
    assert(n <= 1024 * 1024);
    const header = Buffer.alloc(n < 126 ? 2 : n <= 65535 ? 4 : 10);
    header[0] = 0x80 | opcode;
    if (n < 126) header[1] = n;
    else if (n <= 65535) {
      header[1] = 126;
      header.writeUInt16BE(n, 2);
    } else {
      header[1] = 127;
      header.writeBigUInt64BE(BigInt(n), 2);
    }
    socket.write(Buffer.concat([header, b]));
  }
  const api = {
    send,
    text: (value) => send(JSON.stringify(value), 1),
    close: () => {
      if (!socket.destroyed) {
        send(Buffer.alloc(0), 8);
        socket.end();
      }
    },
    socket,
  };
  function parse() {
    try {
      while (data.length >= 2) {
        const fin = !!(data[0] & 128),
          opcode = data[0] & 15,
          masked = !!(data[1] & 128);
        assert(
          !(data[0] & 0x70) && masked,
          "No extensions; client masking required",
        );
        let n = data[1] & 127,
          offset = 2;
        if (n === 126) {
          if (data.length < 4) return;
          n = data.readUInt16BE(2);
          offset = 4;
        } else if (n === 127) {
          if (data.length < 10) return;
          const wide = data.readBigUInt64BE(2);
          assert(wide <= 1048576n);
          n = Number(wide);
          offset = 10;
        }
        assert(n <= 1024 * 1024);
        if (data.length < offset + 4 + n) return;
        const mask = data.subarray(offset, offset + 4),
          payload = Buffer.from(data.subarray(offset + 4, offset + 4 + n));
        for (let i = 0; i < n; i++) payload[i] ^= mask[i % 4];
        data = data.subarray(offset + 4 + n);
        if (opcode >= 8) {
          assert(fin && n <= 125);
          if (opcode === 8) {
            api.close();
            return;
          }
          if (opcode === 9) send(payload, 10);
          else assert(opcode === 10);
          continue;
        }
        assert([0, 1, 2].includes(opcode));
        if (opcode === 0) assert(fragmentOpcode);
        else {
          assert(!fragmentOpcode);
          fragmentOpcode = opcode;
        }
        fragments.push(payload);
        fragmentBytes += n;
        assert(fragmentBytes <= 1024 * 1024);
        if (fin) {
          const b = Buffer.concat(fragments);
          onMessage(b, fragmentOpcode, api);
          fragments = [];
          fragmentBytes = 0;
          fragmentOpcode = 0;
        }
      }
    } catch {
      socket.destroy();
    }
  }
  socket.on("data", (chunk) => {
    data = Buffer.concat([data, chunk]);
    if (data.length > 2 * 1024 * 1024) socket.destroy();
    else parse();
  });
  socket.on("error", () => {});
  socket.once("close", () => onClose());
  parse();
  return api;
}
