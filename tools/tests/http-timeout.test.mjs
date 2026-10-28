import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";

const common = new URL("../lib/common.mjs", import.meta.url).href;
function child(source) {
  const result = spawnSync(process.execPath, ["--input-type=module", "-e", `
    import assert from "node:assert/strict";
    import { http } from ${JSON.stringify(common)};
    ${source}
  `], { encoding: "utf8", timeout: 5000 });
  assert.equal(result.error, undefined);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /settled/);
}
for (const phase of ["request", "body"]) {
  test(`HTTP ${phase} deadline settles even without active sockets`, () => {
    child(`
      globalThis.fetch = async (_, options) => {
        const stalled = () => new Promise((resolve, reject) =>
          options.signal.addEventListener("abort", () => reject(options.signal.reason)));
        return ${phase === "request" ? "stalled()" : "{ text: stalled }"};
      };
      await assert.rejects(http("http://fixture.invalid", "/", { timeout: 40 }), { name: "TimeoutError" });
      console.log("settled");
    `);
  });
}
test("successful HTTP request clears its long deadline", () => {
  child(`
    globalThis.fetch = async () => ({ status: 200, headers: {}, text: async () => "{}" });
    await http("http://fixture.invalid", "/", { timeout: 60000 });
    console.log("settled");
  `);
});
