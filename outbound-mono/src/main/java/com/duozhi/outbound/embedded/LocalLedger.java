package com.duozhi.outbound.embedded;
import java.sql.*;
import java.util.*;
/** Independent durable voice ledger; writer serialization is a locked sentinel row. */
final class LocalLedger implements AutoCloseable {
    interface Work<T> { T run(Connection connection) throws Exception; }
    private final LedgerDatabase database; private final boolean owned;
    private final java.util.concurrent.atomic.AtomicLong commits=new java.util.concurrent.atomic.AtomicLong();
    private final Object callbackReceipts=new Object();
    LocalLedger(Settings settings)throws Exception{this(LedgerDatabase.from(settings),true);}
    LocalLedger(LedgerDatabase database){this(database,false);}
    private LocalLedger(LedgerDatabase database,boolean owned){this.database=database;this.owned=owned;}
    synchronized <T>T transaction(Work<T> work){T result=database.transaction("__voice_writer__",work);commits.incrementAndGet();return result;}
    void consumeNonce(String nonce,double expires){database.consumeNonce(nonce,expires);commits.incrementAndGet();}
    /** Only acknowledge/reschedule existing callback rows. Call reservations,
     * authorization and originating remain in the original writer domain. */
    <T>T callbackReceipt(Work<T> work){synchronized(callbackReceipts){T result=database.transaction("__voice_callback_receipts__",work);commits.incrementAndGet();return result;}}
    <T>T read(Work<T> work){return database.read(work);}
    private static String dialect(String sql){
        String result=sql.replace("INSERT OR IGNORE","INSERT IGNORE");
        var names=Map.of("attempts","voice_attempts","flags","voice_flags","nonces","voice_nonces","outbox","voice_outbox","audit","voice_audit","media_owners","voice_media_owners");
        var match=java.util.regex.Pattern.compile("(?i)\\b(FROM|JOIN|UPDATE|INTO)\\s+(attempts|flags|nonces|outbox|audit|media_owners)\\b").matcher(result);var out=new StringBuffer();
        while(match.find())match.appendReplacement(out,match.group(1)+" "+names.get(match.group(2).toLowerCase(java.util.Locale.ROOT)));match.appendTail(out);
        return out.toString().replaceAll("(?<!`)\\bkey\\b(?!`)","`key`");
    }
    static List<Map<String,Object>> query(Connection db, String sql, Object... args) throws SQLException {
        try (var statement = db.prepareStatement(dialect(sql))) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            try (var rows = statement.executeQuery()) {
                List<Map<String,Object>> result = new ArrayList<>(); var metadata=rows.getMetaData();int columns=metadata.getColumnCount();String[] labels=new String[columns];for(int i=0;i<columns;i++)labels[i]=metadata.getColumnLabel(i+1);
                while (rows.next()) { Map<String,Object> row = new LinkedHashMap<>(); for (int i = 1; i <= columns; i++) row.put(labels[i-1], rows.getObject(i)); result.add(row); } return result;
            }
        }
    }
    static long scalar(Connection db, String sql, Object... args) throws SQLException { return ((Number)query(db, sql, args).get(0).values().iterator().next()).longValue(); }
    static void execute(Connection db, String sql, Object... args) throws SQLException {
        try (var statement = db.prepareStatement(dialect(sql))) { for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]); statement.executeUpdate(); }
    }
    static int update(Connection db,String sql,Object...args)throws SQLException{
        try(var statement=db.prepareStatement(dialect(sql))){for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement.executeUpdate();}
    }
    static void executeBatch(Connection db,String sql,List<Object[]> rows)throws SQLException{
        if(rows.isEmpty())return;try(var statement=db.prepareStatement(dialect(sql))){for(var args:rows){for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);statement.addBatch();}for(int changed:statement.executeBatch())if(changed==java.sql.Statement.EXECUTE_FAILED)throw new SQLException("ledger batch write failed");}
    }
    static void plain(Connection db, String sql) throws SQLException { try (var statement = db.createStatement()) { statement.execute(dialect(sql)); } }
    static double now() { return System.currentTimeMillis() / 1000.0; }
    long commits() { return commits.get(); }
    void stopped(boolean stop, String reason) { transaction(db -> { execute(db, "INSERT INTO flags (`key`,value) VALUES ('stopped',?) ON DUPLICATE KEY UPDATE value=VALUES(value)", stop ? "1" : "0"); execute(db, "INSERT INTO audit(at,action,detail) VALUES (?,?,?)", now(), stop ? "stop" : "resume", reason.substring(0, Math.min(300, reason.length()))); return null; }); }
    Map<String,Object> summary() { return read(db -> {
        var flag = query(db, "SELECT value FROM flags WHERE key='stopped'"); var pending = query(db, "SELECT COUNT(*) AS count,MIN(created) AS oldest FROM outbox").get(0);
        Object oldest = pending.get("oldest");
        return Map.of("stopped", !flag.isEmpty() && flag.get(0).get("value").equals("1"), "pending_callbacks", pending.get("count"), "oldest_callback_age_sec", oldest instanceof Number n ? Math.max(0, now() - n.doubleValue()) : 0, "active_attempts", scalar(db, "SELECT COUNT(*) FROM attempts WHERE state!='ended'"), "rejected_commands", scalar(db, "SELECT COUNT(*) FROM audit WHERE action='reject'"));
    }); }
    @Override public void close()throws Exception{if(owned)database.close();}
}
