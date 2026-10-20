package com.duozhi.outbound.embedded;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Durable account reservations commit before upstream I/O and survive ambiguous failures. */
final class AccountQuota implements AutoCloseable {
    private final Settings settings;
    private final String scope;
    private final int rpm, tpm, rps;
    private final Semaphore connections;
    private final AtomicInteger inflight = new AtomicInteger();
    private final LedgerDatabase database; private final boolean owned,enabled;
    AccountQuota(Settings settings)throws SQLException{this(settings,null);}
    AccountQuota(Settings settings,LedgerDatabase shared)throws SQLException{
        this.settings=settings;scope=settings.get("LLM_QUOTA_SCOPE","primary-account");if(scope.isBlank())throw new IllegalArgumentException("quota requires a scope");
        rpm=(int)settings.number("LLM_QUOTA_RPM",10000,1,Integer.MAX_VALUE);tpm=(int)settings.number("LLM_QUOTA_TPM",13000000,1,Integer.MAX_VALUE);rps=(int)settings.number("LLM_QUOTA_RPS",500,1,Integer.MAX_VALUE);
        connections=new Semaphore((int)settings.number("LLM_MAX_CONNECTIONS",256,1,1024));
        // The old optional file path also enabled the durable window; retain that switch explicitly.
        enabled=settings.flag("LLM_QUOTA_ENABLED",false);owned=shared==null&&enabled;database=owned?LedgerDatabase.from(settings):shared;
        String config=Support.hex(Support.sha(("["+rpm+", "+tpm+", "+rps+"]").getBytes(StandardCharsets.UTF_8)));
        if(enabled)try{database.transaction("__model_writer__",db->{LocalLedger.execute(db,"INSERT IGNORE INTO voice_model_budgets(scope,config) VALUES (?,?)",scope,config);var row=LocalLedger.query(db,"SELECT config FROM voice_model_budgets WHERE scope=?",scope);if(row.isEmpty()||!config.equals(row.get(0).get("config")))throw new IllegalArgumentException("Agent processes must share identical account quotas; drain before changing configuration");return null;});}catch(RuntimeException error){if(owned)try{database.close();}catch(SQLException close){error.addSuppressed(close);}throw error;}
    }
    synchronized int reserve(long tokens,double now,boolean write)throws SQLException{
        if(!enabled)return 0;
        return database.transaction("__model_writer__",db->{
            var budget=LocalLedger.query(db,"SELECT blocked_until FROM voice_model_budgets WHERE scope=? FOR UPDATE",scope);if(budget.isEmpty())throw new SQLException("account quota not initialized");
            double blocked=((Number)budget.get(0).get("blocked_until")).doubleValue();if(blocked>now)return Math.max(1,(int)(blocked-now)+1);
            LocalLedger.execute(db,"DELETE FROM voice_model_usage WHERE scope=? AND second<?",scope,(long)now-60);
            var row=LocalLedger.query(db,"SELECT COALESCE(SUM(requests),0) AS requests,COALESCE(SUM(tokens),0) AS tokens,COALESCE(SUM(CASE WHEN second>=? THEN requests ELSE 0 END),0) AS recent FROM voice_model_usage WHERE scope=?",(long)now-1,scope).get(0);
            int delay=((Number)row.get("requests")).longValue()+1>rpm||((Number)row.get("tokens")).longValue()+tokens>tpm||((Number)row.get("recent")).longValue()+1>rps?1:0;
            if(delay==0&&write)LocalLedger.execute(db,"INSERT INTO voice_model_usage VALUES (?,?,1,?) ON DUPLICATE KEY UPDATE requests=requests+1,tokens=tokens+VALUES(tokens)",scope,(long)now,tokens);return delay;
        });
    }
    synchronized void block(double seconds)throws SQLException{if(enabled)database.transaction("__model_writer__",db->{LocalLedger.execute(db,"UPDATE voice_model_budgets SET blocked_until=GREATEST(blocked_until,?) WHERE scope=?",System.currentTimeMillis()/1000.0+seconds,scope);return null;});}
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
    @Override public synchronized void close()throws SQLException{if(owned)database.close();}
}
