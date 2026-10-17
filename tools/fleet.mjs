import {
  fs,
  args,
  number,
  readJSON,
  http,
  assert,
  sleep,
} from "./lib/common.mjs";
export async function request(endpoint, p, token = "", method = "GET") {
  const r = await http(endpoint.replace(/\/$/, ""), p, {
    headers: token ? { Authorization: "Bearer " + token } : {},
    method,
    status: 200,
    timeout: 5000,
  });
  return r.json;
}
if (process.argv[1]?.endsWith("fleet.mjs")) {
  const action = process.argv[2];
  assert(
    ["status", "drain", "resume"].includes(action),
    "Use status|drain|resume",
  );
  const a = args(process.argv.slice(3)),
    nodes = readJSON(a.roster).filter((n) => !a.node || n.id === a.node);
  assert(nodes.length, "Unknown node");
  if (action !== "status")
    assert(
      nodes.length === 1 && a.node && a["admin-token-file"],
      "Drain/resume requires exactly one node and credential file",
    );
  const token = a["admin-token-file"]
      ? fs.readFileSync(a["admin-token-file"], "utf8").trim()
      : "",
    seconds = number(a["wait-seconds"] ?? 0, "wait seconds", { max: 86400 });
  for (const n of nodes) {
    try {
      if (action === "status") {
        const d = await request(n.endpoint, "/readyz");
        assert.equal(d.node_id, n.id, "Identity mismatch");
        console.log(
          JSON.stringify({
            node: n.id,
            ready: d.status,
            identity_matches: true,
            capacity: d.call_capacity,
          }),
        );
      } else {
        const identity = await request(n.endpoint, "/v1/admin/capacity", token);
        assert.equal(identity.node_id, n.id, "Identity mismatch");
        await request(
          n.endpoint,
          "/v1/admin/drain?enabled=" + (action === "drain"),
          token,
          "POST",
        );
        if (action === "drain" && seconds) {
          const deadline = Date.now() + seconds * 1000;
          for (;;) {
            const d = await request(n.endpoint, "/v1/admin/security", token);
            if (d.active_attempts === 0 && d.pending_callbacks === 0) {
              console.log(JSON.stringify({ node: n.id, safe_to_stop: true }));
              break;
            }
            assert(
              Date.now() < deadline,
              "Drain still has durable calls/callbacks",
            );
            await sleep(1000);
          }
        } else
          console.log(JSON.stringify({ node: n.id, action, accepted: true }));
      }
    } catch {
      console.log(
        JSON.stringify({ node: n.id, error_type: "FleetOperationFailed" }),
      );
      process.exitCode = 1;
    }
  }
}
