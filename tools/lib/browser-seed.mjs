import { path, crypto, assert } from "./common.mjs";
/** Fixtures only: caller owns the temporary SQLite file; never accepts a live URL. */
export async function seedBrowser(folder) {
  assert(
    path.basename(folder).startsWith("java-only-stack-"),
    "Only owned temporary fixture accepted",
  );
  const { DatabaseSync } = await import("node:sqlite");
  const db = new DatabaseSync(path.join(folder, "core.db"));
  try {
    db.exec("PRAGMA busy_timeout=5000; BEGIN IMMEDIATE");
    const admin = db
        .prepare('SELECT * FROM "user" WHERE username=?')
        .get("admin"),
      tenant = db
        .prepare("SELECT * FROM tenant WHERE id=?")
        .get(admin.tenant_id),
      contact = db
        .prepare("SELECT * FROM contact WHERE tenant_id=? ORDER BY id LIMIT 1")
        .get(admin.tenant_id),
      call = db
        .prepare(
          "SELECT * FROM callsession WHERE tenant_id=? ORDER BY created_at LIMIT 1",
        )
        .get(admin.tenant_id);
    assert(admin && tenant && contact && call);
    const insert = (table, row) => {
      const keys = Object.keys(row);
      db.prepare(
        `INSERT INTO "${table}" (${keys.map((k) => '"' + k + '"').join(",")}) VALUES (${keys.map(() => "?").join(",")})`,
      ).run(...keys.map((k) => row[k]));
    };
    insert("tenant", {
      ...tenant,
      id: 900007,
      name: "Synthetic review B",
      code: "java-only-review-b",
    });
    insert("user", {
      ...admin,
      id: 900007,
      tenant_id: 900007,
      username: "review-b",
      full_name: "Synthetic review B",
      token_version: 0,
      failed_login_attempts: 0,
      locked_until: null,
    });
    insert("contact", {
      ...contact,
      id: 900007,
      tenant_id: 900007,
      name: "SyntheticTenantB",
      phone: "13900009007",
    });
    insert("callbackappointment", {
      id: crypto.randomUUID().replaceAll("-", ""),
      tenant_id: admin.tenant_id,
      source_call_id: call.id,
      request_key: "browser-appointment",
      scheduled_at: "2026-12-01T06:00:00",
      state: "confirmed",
      revision: 1,
      dial_call_id: null,
      created_at: call.created_at,
      updated_at: call.updated_at,
    });
    db.exec("COMMIT");
  } catch (e) {
    try {
      db.exec("ROLLBACK");
    } catch {}
    throw e;
  } finally {
    db.close();
  }
}
