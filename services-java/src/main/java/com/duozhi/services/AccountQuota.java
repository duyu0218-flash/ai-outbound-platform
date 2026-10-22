package com.duozhi.services;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** FULL/WAL account reservations commit before upstream I/O and survive ambiguous failures. */
final class AccountQuota implements AutoCloseable {
    private final Settings settings;
    private final String path, scope;
    private final int rpm, tpm, rps;
    private final Semaphore connections;
    private final AtomicInteger inflight = new AtomicInteger();
    private Connection anchor;
    AccountQuota(Settings settings) throws SQLException {
        this.settings = settings; path = settings.get("LLM_QUOTA_DB_PATH", ""); scope = settings.get("LLM_QUOTA_SCOPE", "primary-account");
        rpm = (int) settings.number("LLM_QUOTA_RPM", 10000, 1, Integer.MAX_VALUE);
        tpm = (int) settings.number("LLM_QUOTA_TPM", 13000000, 1, Integer.MAX_VALUE);
        rps = (int) settings.number("LLM_QUOTA_RPS", 500, 1, Integer.MAX_VALUE);
        connections = new Semaphore((int) settings.number("LLM_MAX_CONNECTIONS", 256, 1, 1024));
        if (path.equals(":memory:") || scope.isBlank()) throw new IllegalArgumentException("quota requires a persistent shared file and scope");
        if (!path.isEmpty()) {
            anchor = begin();
            try {
                anchor.createStatement().execute("CREATE TABLE IF NOT EXISTS budgets(scope TEXT PRIMARY KEY,config TEXT NOT NULL,blocked_until REAL NOT NULL DEFAULT 0)");
                anchor.createStatement().execute("CREATE TABLE IF NOT EXISTS usage(scope TEXT NOT NULL,second INTEGER NOT NULL,requests INTEGER NOT NULL,tokens INTEGER NOT NULL,PRIMARY KEY(scope,second))");
                // Preserve Python's historical json.dumps representation and schema for an offline cutover.
                String config = Support.hex(Support.sha(("[" + rpm + ", " + tpm + ", " + rps + "]").getBytes(StandardCharsets.UTF_8)));
                try (var insert = anchor.prepareStatement("INSERT OR IGNORE INTO budgets(scope,config) VALUES (?,?)")) { insert.setString(1, scope); insert.setString(2, config); insert.executeUpdate(); }
                try (var query = anchor.prepareStatement("SELECT config FROM budgets WHERE scope=?")) {
                    query.setString(1, scope); try (var row = query.executeQuery()) { if (!row.next() || !config.equals(row.getString(1))) throw new IllegalArgumentException("Agent processes must share identical account quotas; drain before changing configuration"); }
                }
                anchor.createStatement().execute("COMMIT");
            } catch (Exception error) { anchor.close(); anchor = null; throw error; }
        }
    }
    private Connection begin() throws SQLException {
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + path);
        boolean opened = false;
        try {
            db.createStatement().execute("PRAGMA busy_timeout=500"); long deadline = System.nanoTime() + 500_000_000L;
            while (true) {
                try {
                    boolean wal;
                    try (var statement = db.createStatement(); var row = statement.executeQuery("PRAGMA journal_mode")) { wal = row.next() && "wal".equals(row.getString(1)); }
                    if (!wal) try (var statement = db.createStatement()) { statement.execute("PRAGMA journal_mode=WAL"); }
                    db.createStatement().execute("PRAGMA synchronous=FULL"); db.createStatement().execute("BEGIN IMMEDIATE"); opened = true; return db;
                } catch (SQLException error) {
                    if ((error.getErrorCode() != 5 && error.getErrorCode() != 6) || System.nanoTime() >= deadline) throw error;
                    try { Thread.sleep(5); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new SQLException("quota startup interrupted", interrupted); }
                }
            }
        } finally { if (!opened) db.close(); }
    }
    synchronized int reserve(long tokens, double now, boolean write) throws SQLException {
        if (path.isEmpty()) return 0;
        try (Connection db = begin()) {
            try {
                try (var query = db.prepareStatement("SELECT blocked_until FROM budgets WHERE scope=?")) {
                    query.setString(1, scope); try (var row = query.executeQuery()) {
                        if (!row.next()) throw new SQLException("account quota not initialized");
                        if (row.getDouble(1) > now) { int delay = Math.max(1, (int)(row.getDouble(1) - now) + 1); db.createStatement().execute("COMMIT"); return delay; }
                    }
                }
                try (var delete = db.prepareStatement("DELETE FROM usage WHERE scope=? AND second<?")) { delete.setString(1, scope); delete.setLong(2, (long)now - 60); delete.executeUpdate(); }
                long requests, used, recent;
                try (var query = db.prepareStatement("SELECT COALESCE(SUM(requests),0),COALESCE(SUM(tokens),0),COALESCE(SUM(CASE WHEN second>=? THEN requests ELSE 0 END),0) FROM usage WHERE scope=?")) {
                    query.setLong(1, (long)now - 1); query.setString(2, scope);
                    try (var row = query.executeQuery()) { row.next(); requests = row.getLong(1); used = row.getLong(2); recent = row.getLong(3); }
                }
                int delay = requests + 1 > rpm || used + tokens > tpm || recent + 1 > rps ? 1 : 0;
                if (delay == 0 && write) try (var insert = db.prepareStatement("INSERT INTO usage VALUES (?,?,1,?) ON CONFLICT(scope,second) DO UPDATE SET requests=requests+1,tokens=tokens+excluded.tokens")) {
                    insert.setString(1, scope); insert.setLong(2, (long)now); insert.setLong(3, tokens); insert.executeUpdate();
                }
                db.createStatement().execute("COMMIT"); return delay;
            } catch (Exception error) {
                // Never replay a reservation whose commit result is unknown.
                try { db.createStatement().execute("ROLLBACK"); } catch (SQLException ignored) { }
                throw error;
            }
        }
    }
    synchronized void block(double seconds) throws SQLException {
        if (path.isEmpty()) return;
        try (Connection db = begin(); var update = db.prepareStatement("UPDATE budgets SET blocked_until=MAX(blocked_until,?) WHERE scope=?")) {
            update.setDouble(1, System.currentTimeMillis() / 1000.0 + seconds); update.setString(2, scope); update.executeUpdate(); db.createStatement().execute("COMMIT");
        }
    }
    void acquire(long tokens) {
        if (!connections.tryAcquire()) throw new Support.Failure(429, "model concurrency budget exhausted");
        inflight.incrementAndGet();
        try {
            if (reserve(tokens, System.currentTimeMillis() / 1000.0, true) != 0) throw new Support.Failure(429, "account model budget exhausted");
        } catch (SQLException error) { release(); throw new Support.Failure(503, "account quota unavailable"); }
        catch (RuntimeException error) { release(); throw error; }
    }
    void release() { inflight.decrementAndGet(); connections.release(); }
    int inflight() { return inflight.get(); }
    boolean ready() { try { return reserve(1, System.currentTimeMillis() / 1000.0, false) == 0; } catch (Exception error) { return false; } }
    @Override
    public synchronized void close() throws SQLException { if (anchor != null) { anchor.close(); anchor = null; } }
}
