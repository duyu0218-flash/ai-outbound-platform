package com.duozhi.services;

import java.sql.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.*;

/** Single gateway writer with persistent SQLite WAL and an exclusive JVM/process lock. */
final class LocalLedger implements AutoCloseable {
    interface Work<T> { T run(Connection connection) throws Exception; }
    private final String path;
    private final FileChannel owner;
    private final FileLock lock;
    private final Connection anchor;
    private static final class Transaction { final Connection db; boolean rollbackOnly; Transaction(Connection db) { this.db = db; } }
    private final ThreadLocal<Transaction> active = new ThreadLocal<>();
    LocalLedger(String path) throws Exception {
        if (path.isBlank() || path.equals(":memory:")) throw new IllegalArgumentException("persistent gateway ledger required");
        this.path = path; Path parent = Path.of(path).toAbsolutePath().getParent(); Files.createDirectories(parent);
        owner = FileChannel.open(Path.of(path + ".java.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired; try { acquired = owner.tryLock(); } catch (OverlappingFileLockException busy) { acquired = null; }
        if (acquired == null) { owner.close(); throw new IllegalArgumentException("gateway ledger already has a Java owner; stop old controllers before cutover"); } lock = acquired;
        Connection opened = null;
        try {
            opened = connection(); plain(opened, "PRAGMA journal_mode=WAL");
            String[] schema = {
                "CREATE TABLE IF NOT EXISTS attempts(call_id TEXT NOT NULL,attempt INTEGER NOT NULL,tenant INTEGER NOT NULL,line TEXT NOT NULL,uuid TEXT NOT NULL UNIQUE,digest TEXT NOT NULL,payload TEXT NOT NULL,created REAL NOT NULL,deadline REAL NOT NULL,cost INTEGER NOT NULL,rate INTEGER NOT NULL,multiplier INTEGER NOT NULL,state TEXT NOT NULL DEFAULT 'pending',result TEXT,ended REAL,observed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(call_id,attempt))",
                "CREATE INDEX IF NOT EXISTS attempts_scope ON attempts(tenant,line,created)",
                "CREATE TABLE IF NOT EXISTS flags(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS nonces(nonce TEXT PRIMARY KEY,expires REAL NOT NULL)",
                "CREATE TABLE IF NOT EXISTS outbox(id TEXT PRIMARY KEY,url TEXT NOT NULL,body BLOB NOT NULL,created REAL NOT NULL,due REAL NOT NULL,failures INTEGER NOT NULL DEFAULT 0,stream_key TEXT NOT NULL DEFAULT '')",
                "CREATE INDEX IF NOT EXISTS outbox_stream_order ON outbox(stream_key,created)",
                "CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY,at REAL NOT NULL,action TEXT NOT NULL,detail TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS voice_action_commands(id TEXT PRIMARY KEY,digest TEXT NOT NULL,result TEXT,call_id TEXT NOT NULL DEFAULT '',attempt INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS voice_permits(id TEXT PRIMARY KEY,scope TEXT NOT NULL,kind TEXT NOT NULL,call_id TEXT NOT NULL,attempt INTEGER NOT NULL,owner TEXT NOT NULL,state TEXT NOT NULL,created REAL NOT NULL)",
                "CREATE INDEX IF NOT EXISTS voice_permits_scope ON voice_permits(scope,state)"
            };
            for (String sql : schema) try (var statement = opened.createStatement()) { statement.execute(sql); }
            anchor = opened;
        } catch (Exception error) { if (opened != null) opened.close(); lock.release(); owner.close(); throw error; }
    }
    private Connection connection() throws SQLException {
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + path);
        try { plain(db, "PRAGMA busy_timeout=5000"); plain(db, "PRAGMA synchronous=FULL"); return db; }
        catch (SQLException error) { db.close(); throw error; }
    }
    synchronized <T> T transaction(Work<T> work) {
        Transaction existing = active.get();
        if (existing != null) {
            try { return work.run(existing.db); }
            catch (Exception error) { existing.rollbackOnly = true; if (error instanceof Support.Failure failure) throw failure; throw new Support.Failure(503, "gateway transaction failed"); }
        }
        try (var db = connection()) {
            plain(db, "BEGIN IMMEDIATE");
            Transaction transaction = new Transaction(db); active.set(transaction);
            try { T result = work.run(db); if (transaction.rollbackOnly) throw new Support.Failure(503, "gateway transaction marked for rollback"); plain(db, "COMMIT"); return result; }
            catch (Exception error) { try { plain(db, "ROLLBACK"); } catch (SQLException ignored) { } throw error; }
            finally { active.remove(); }
        } catch (Support.Failure error) { throw error; } catch (Exception error) { throw new Support.Failure(503, "gateway ledger unavailable"); }
    }
    <T> T read(Work<T> work) {
        Transaction existing = active.get();
        if (existing != null) try { return work.run(existing.db); } catch (Exception error) { if (error instanceof Support.Failure failure) throw failure; throw new Support.Failure(503, "gateway transaction read failed"); }
        try (var db = connection()) { return work.run(db); }
        catch (Support.Failure error) { throw error; } catch (Exception error) { throw new Support.Failure(503, "gateway ledger unavailable"); }
    }
    static List<Map<String,Object>> query(Connection db, String sql, Object... args) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            try (var rows = statement.executeQuery()) {
                List<Map<String,Object>> result = new ArrayList<>(); int columns = rows.getMetaData().getColumnCount();
                while (rows.next()) { Map<String,Object> row = new LinkedHashMap<>(); for (int i = 1; i <= columns; i++) row.put(rows.getMetaData().getColumnLabel(i), rows.getObject(i)); result.add(row); } return result;
            }
        }
    }
    static long scalar(Connection db, String sql, Object... args) throws SQLException { return ((Number)query(db, sql, args).get(0).values().iterator().next()).longValue(); }
    static void execute(Connection db, String sql, Object... args) throws SQLException {
        try (var statement = db.prepareStatement(sql)) { for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]); statement.executeUpdate(); }
    }
    static void plain(Connection db, String sql) throws SQLException { try (var statement = db.createStatement()) { statement.execute(sql); } }
    static double now() { return System.currentTimeMillis() / 1000.0; }
    void stopped(boolean stop, String reason) { transaction(db -> { execute(db, "INSERT OR REPLACE INTO flags VALUES ('stopped',?)", stop ? "1" : "0"); execute(db, "INSERT INTO audit(at,action,detail) VALUES (?,?,?)", now(), stop ? "stop" : "resume", reason.substring(0, Math.min(300, reason.length()))); return null; }); }
    Map<String,Object> summary() { return read(db -> {
        var flag = query(db, "SELECT value FROM flags WHERE key='stopped'"); var pending = query(db, "SELECT COUNT(*) AS count,MIN(created) AS oldest FROM outbox").get(0);
        Object oldest = pending.get("oldest");
        return Map.of("stopped", !flag.isEmpty() && flag.get(0).get("value").equals("1"), "pending_callbacks", pending.get("count"), "oldest_callback_age_sec", oldest instanceof Number n ? Math.max(0, now() - n.doubleValue()) : 0, "active_attempts", scalar(db, "SELECT COUNT(*) FROM attempts WHERE state!='ended'"), "rejected_commands", scalar(db, "SELECT COUNT(*) FROM audit WHERE action='reject'"));
    }); }
    @Override public synchronized void close() throws Exception { try { anchor.close(); } finally { try { lock.release(); } finally { owner.close(); } } }
}
