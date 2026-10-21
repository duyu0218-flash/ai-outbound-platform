package com.duozhi.outbound.embedded;
import com.alibaba.druid.pool.DruidDataSource;
import javax.sql.DataSource;
import java.sql.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;
/** Every ledger commit uses its own connection, never the business transaction manager. */
@Component
@DependsOnDatabaseInitialization
public final class LedgerDatabase implements AutoCloseable {
    private final DataSource source; private final boolean owned;
    private record Active(String key,Connection db,java.util.concurrent.atomic.AtomicBoolean rollbackOnly){}
    private final ThreadLocal<Active> active=new ThreadLocal<>();
    @org.springframework.beans.factory.annotation.Autowired
    public LedgerDatabase(@Qualifier("voiceLedgerDataSource") DataSource source)throws SQLException{this(source,false);}
    private LedgerDatabase(DataSource source,boolean owned)throws SQLException{this.source=source;this.owned=owned;validate();}
    static LedgerDatabase from(Settings settings)throws SQLException{
        var pool=new DruidDataSource();pool.setName("independentLedger");pool.setUrl(settings.get("MYSQL_JDBC_URL",""));pool.setUsername(settings.get("MYSQL_USER",""));pool.setPassword(settings.secret("MYSQL_PASSWORD"));pool.setInitialSize(0);pool.setMinIdle(0);pool.setMaxActive(4);pool.setMaxWait(5000);pool.setDefaultTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        if(pool.getUrl().isBlank())throw new IllegalArgumentException("MYSQL_JDBC_URL is required for the independent ledger");
        try{return new LedgerDatabase(pool,true);}catch(SQLException|RuntimeException error){pool.close();throw error;}
    }
    private void validate()throws SQLException{
        try(var db=source.getConnection();var q=db.createStatement();var r=q.executeQuery("SELECT @@GLOBAL.innodb_flush_log_at_trx_commit,@@GLOBAL.sync_binlog,@@SESSION.transaction_isolation")){
            r.next();if(r.getInt(1)!=1||r.getInt(2)!=1||!r.getString(3).equalsIgnoreCase("REPEATABLE-READ"))throw new IllegalStateException("Durable ledger requires innodb_flush_log_at_trx_commit=1, sync_binlog=1 and REPEATABLE READ");
        }
    }
    <T>T transaction(String key,LocalLedger.Work<T> work){
        return transaction(key,work,false);
    }
    /** A nonce has its own database UNIQUE key and allocates no resources.
     * Join an enclosing command batch so rollback still covers its nonce;
     * otherwise its INSERT commits without waiting for the resource sentinel. */
    void consumeNonce(String nonce,double expires){
        transaction(null,db->{if(LocalLedger.update(db,"INSERT IGNORE INTO nonces(nonce,expires) VALUES(?,?)",nonce,expires)!=1)throw new Support.Failure(409,"voice command permit was already used");return null;},true);
    }
    private <T>T transaction(String key,LocalLedger.Work<T> work,boolean joinExisting){
        Active previous=active.get();if(previous!=null&&(joinExisting||java.util.Objects.equals(previous.key,key))){try{return work.run(previous.db);}catch(Exception error){previous.rollbackOnly.set(true);throw failure(error);}}
        try(var db=source.getConnection()){
            db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);db.setAutoCommit(false);var state=new Active(key,db,new java.util.concurrent.atomic.AtomicBoolean());active.set(state);
            try{
                if(key!=null)try(var lock=db.prepareStatement("SELECT value FROM voice_flags WHERE `key`=? FOR UPDATE")){lock.setString(1,key);try(var rows=lock.executeQuery()){if(!rows.next())throw new SQLException("ledger writer sentinel missing; apply Flyway V2");}}
                T result=work.run(db);if(state.rollbackOnly.get())throw new Support.Failure(503,"ledger transaction marked for rollback");db.commit();return result;
            }catch(Exception error){try{db.rollback();}catch(SQLException ignored){}throw failure(error);}finally{if(previous==null)active.remove();else active.set(previous);}
        }catch(SQLException error){throw new Support.Failure(503,"independent ledger unavailable");}
    }
    <T>T read(LocalLedger.Work<T> work){Active state=active.get();if(state!=null)try{return work.run(state.db);}catch(Exception error){throw failure(error);}try(var db=source.getConnection()){return work.run(db);}catch(Exception error){throw failure(error);}}
    private static RuntimeException failure(Exception error){return error instanceof RuntimeException runtime?runtime:new Support.Failure(503,"ledger operation failed");}
    public DataSource dataSource(){return source;}
    @Override public void close()throws SQLException{if(owned&&source instanceof DruidDataSource pool)pool.close();}
}
