import { assert } from "./common.mjs";
const fields = {
  dbname: "PGDATABASE",
  host: "PGHOST",
  port: "PGPORT",
  user: "PGUSER",
  password: "PGPASSWORD",
  sslmode: "PGSSLMODE",
  sslrootcert: "PGSSLROOTCERT",
  sslcert: "PGSSLCERT",
  sslkey: "PGSSLKEY",
  application_name: "PGAPPNAME",
};
/** Parse without exposing a credential-bearing URI to process arguments/logs. */
export function connectionEnv(dsn) {
  try {
    assert(typeof dsn === "string" && dsn.trim());
    const values = {};
    const set = (k, v) => {
      assert(
        !Object.hasOwn(values, k) && Object.hasOwn(fields, k),
        "Unsupported or duplicate connection field",
      );
      values[k] = v;
    };
    if (/^postgres(?:ql)?:\/\//.test(dsn)) {
      const u = new URL(dsn);
      assert(["postgresql:", "postgres:"].includes(u.protocol));
      if (u.hostname) set("host", u.hostname.replace(/^\[|\]$/g, ""));
      if (u.port) set("port", u.port);
      if (u.username) set("user", decodeURIComponent(u.username));
      if (u.password) set("password", decodeURIComponent(u.password));
      if (u.pathname.length > 1)
        set("dbname", decodeURIComponent(u.pathname.slice(1)));
      for (const [k, v] of u.searchParams) set(k, v);
    } else {
      let i = 0;
      while (i < dsn.length) {
        while (/\s/.test(dsn[i] ?? "") && i < dsn.length) i++;
        if (i === dsn.length) break;
        const key = dsn.slice(i).match(/^([a-z_]+)\s*=\s*/);
        assert(key, "Malformed conninfo");
        i += key[0].length;
        let value = "",
          quoted = dsn[i] === "'",
          closed = !quoted;
        if (quoted) i++;
        while (i < dsn.length) {
          const c = dsn[i++];
          if (c === "\\") {
            assert(i < dsn.length);
            value += dsn[i++];
          } else if (quoted && c === "'") {
            closed = true;
            break;
          } else if (!quoted && /\s/.test(c)) break;
          else value += c;
        }
        assert(closed, "Unclosed conninfo");
        set(key[1], value);
      }
    }
    assert(values.dbname, "Explicit database required");
    if (values.port)
      assert(
        /^\d+$/.test(values.port) &&
          Number(values.port) >= 1 &&
          Number(values.port) <= 65535,
      );
    if (values.sslmode)
      assert(
        [
          "disable",
          "allow",
          "prefer",
          "require",
          "verify-ca",
          "verify-full",
        ].includes(values.sslmode),
      );
    const env = {};
    for (const [k, v] of Object.entries(values)) env[fields[k]] = v;
    env.PGCONNECT_TIMEOUT = "3";
    env.PGOPTIONS =
      "-c default_transaction_read_only=on -c statement_timeout=2000";
    return env;
  } catch {
    throw Error("Invalid PostgreSQL connection settings");
  }
}
