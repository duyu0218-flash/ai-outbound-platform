import { assert, fs } from "./common.mjs";
/** Bounded sample retention; lifetime count, sum, maximum and 1% upper bins. */
export class Samples {
  constructor(limit = 10000) {
    assert(Number.isInteger(limit) && limit > 0);
    this.limit = limit;
    this.values = [];
    this.count = 0;
    this.total = 0;
    this.maximum = 0;
    this.bins = new Map();
    this.cursor = 0;
  }
  append(v) {
    assert(
      typeof v === "number" && Number.isFinite(v) && v >= 0,
      "Finite nonnegative sample required",
    );
    if (this.values.length < this.limit) this.values.push(v);
    else {
      this.values[this.cursor] = v;
      this.cursor = (this.cursor + 1) % this.limit;
    }
    this.count++;
    this.total += v;
    this.maximum = Math.max(this.maximum, v);
    const b = Math.ceil(Math.log1p(v) / Math.log(1.01));
    this.bins.set(b, (this.bins.get(b) ?? 0) + 1);
  }
  quantile(q) {
    assert(Number.isFinite(q) && q >= 0 && q <= 1);
    if (!this.count) return 0;
    const rank = Math.min(this.count - 1, Math.floor(this.count * q));
    if (this.count <= this.limit)
      return [...this.values].sort((a, b) => a - b)[rank];
    let n = 0;
    for (const [i, c] of [...this.bins].sort((a, b) => a[0] - b[0])) {
      n += c;
      if (n > rank)
        return Math.min(this.maximum, Math.expm1(i * Math.log(1.01)));
    }
  }
}
export class RotatingLog {
  constructor(folder, maxBytes = 64 * 1024 * 1024) {
    this.folder = folder;
    this.maxBytes = maxBytes;
    this.part = 0;
    this.size = 0;
    this.fd = null;
    assert(Number.isInteger(maxBytes) && maxBytes > 0);
  }
  write(buffer) {
    for (let offset = 0; offset < buffer.length; ) {
      if (this.fd === null || this.size === this.maxBytes) {
        this.close();
        this.part++;
        this.fd = fs.openSync(
          this.folder + `/workload-${String(this.part).padStart(5, "0")}.log`,
          "wx",
          0o600,
        );
        this.size = 0;
      }
      const n = Math.min(buffer.length - offset, this.maxBytes - this.size);
      let written = 0;
      while (written < n)
        written += fs.writeSync(this.fd, buffer, offset + written, n - written);
      offset += n;
      this.size += n;
    }
  }
  close() {
    if (this.fd !== null) {
      fs.fsyncSync(this.fd);
      fs.closeSync(this.fd);
      this.fd = null;
    }
  }
}
