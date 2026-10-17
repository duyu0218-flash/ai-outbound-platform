import { test } from "node:test";
import { assert } from "../lib/common.mjs";
import { connectionEnv } from "../lib/pg-options.mjs";
test("connection URI separates credentials into environment only", () => {
  const e = connectionEnv(
    "postgresql://audit:synthetic%40value@localhost:5432/legacy?sslmode=verify-full",
  );
  assert.equal(e.PGUSER, "audit");
  assert.equal(e.PGPASSWORD, "synthetic@value");
  assert.equal(e.PGDATABASE, "legacy");
  assert.equal(e.PGHOST, "localhost");
  assert.equal(e.PGSSLMODE, "verify-full");
  assert(e.PGOPTIONS.includes("default_transaction_read_only=on"));
});
test("Unix socket database URI preserves explicit user", () => {
  const e = connectionEnv("postgresql:///legacy?user=postgres");
  assert(!e.PGHOST);
  assert.equal(e.PGUSER, "postgres");
  assert.equal(e.PGDATABASE, "legacy");
});
test("quoted native conninfo is supported without shell parsing", () => {
  const e = connectionEnv(
    "host=/var/run/postgresql dbname=legacy user=audit password='synthetic value'",
  );
  assert.equal(e.PGPASSWORD, "synthetic value");
  assert.equal(e.PGHOST, "/var/run/postgresql");
});
test("bad settings fail with generic error without credential leak", () => {
  for (const s of [
    "postgresql://audit:private-value@localhost/legacy?options=unsafe",
    "dbname=legacy password='private-value",
    "postgresql://audit:private-value@localhost/",
  ])
    assert.throws(
      () => connectionEnv(s),
      (e) =>
        e.message === "Invalid PostgreSQL connection settings" &&
        !e.message.includes("private-value"),
    );
});
