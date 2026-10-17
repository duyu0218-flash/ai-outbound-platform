import { fs, path, os, ROOT, sha, assert } from "./lib/common.mjs";
const assets = {
  "linux/arm64": [
    "linux_arm64",
    "4061ef4c021ed3c47f203b818f337e351336cc5cd67c0274fc36bd1dfc728dd7",
  ],
  "linux/x64": [
    "linux_amd64",
    "160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b",
  ],
  "darwin/arm64": [
    "darwin_arm64",
    "d4926ef8da134332543be276dff157e4c170f99ad91fc48e89448a8816bdf70b",
  ],
};
const selected = assets[os.platform() + "/" + os.arch()];
assert(selected, "Unsupported scanner platform");
const [asset, digest] = selected,
  binary = path.join(ROOT, ".tools/osv-scanner");
fs.mkdirSync(path.dirname(binary), { recursive: true });
if (!fs.existsSync(binary) || sha(binary) !== digest) {
  const response = await fetch(
    "https://github.com/google/osv-scanner/releases/download/v2.7.0/osv-scanner_" +
      asset,
    { signal: AbortSignal.timeout(120000) },
  );
  assert(response.ok, "OSV download failed");
  const temporary = binary + "." + process.pid + ".download";
  try {
    fs.writeFileSync(temporary, Buffer.from(await response.arrayBuffer()), {
      mode: 0o700,
    });
    assert.equal(sha(temporary), digest, "OSV Scanner checksum mismatch");
    fs.renameSync(temporary, binary);
  } finally {
    fs.rmSync(temporary, { force: true });
  }
}
fs.chmodSync(binary, 0o755);
