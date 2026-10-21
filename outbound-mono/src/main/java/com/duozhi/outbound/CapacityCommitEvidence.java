package com.duozhi.outbound;

import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

/** Brackets the actual JDBC commit. A missing post-commit record invalidates
 * timing coverage; it is never reconstructed from created_at or first polling. */
@Service
public class CapacityCommitEvidence {
 private final Config config;private final JdbcTemplate jdbc;private final ObjectProvider<DataSource> evidencePool;
 private final long anchorNano,anchorUs,errorUs;
 private final String origin=UUID.randomUUID().toString().replace("-","");
 private static final class Pending {final String id=UUID.randomUUID().toString().replace("-","");long lo;boolean valid;}
 public CapacityCommitEvidence(Config config,JdbcTemplate jdbc,@Qualifier("capacityEvidenceDataSource") ObjectProvider<DataSource> evidencePool){
  this.config=config;this.jdbc=jdbc;this.evidencePool=evidencePool;
  long before=System.nanoTime();var wall=Instant.now();long after=System.nanoTime();
  anchorNano=before+(after-before)/2;anchorUs=wall.getEpochSecond()*1_000_000L+wall.getNano()/1000;
  errorUs=5_000L+(after-before+1999)/2000;
  if(enabled()&&!config.get("JAVA_CAPACITY_RUN_ID","").matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("capacity evidence requires an explicit run identity");
 }
 private boolean enabled(){return config.flag("JAVA_CAPACITY_EVIDENCE_ENABLED",false);}
 long nowUs(){return anchorUs+(System.nanoTime()-anchorNano)/1000;}
 private boolean validClock(){var wall=Instant.now();long actual=wall.getEpochSecond()*1_000_000L+wall.getNano()/1000;return Math.abs(actual-nowUs())<=errorUs;}
 void record(String kind,long tenant,Object call,int attempt){recordBatch(kind,Collections.singletonList(new Object[]{tenant,call.toString(),attempt}));}
 void recordBatch(String kind,List<Object[]> identities){
  if(!enabled()||identities.isEmpty())return;
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("commit evidence requires the fact transaction");
  if(!Set.of("queue","ack").contains(kind))throw new IllegalArgumentException("unknown commit evidence kind");
  Pending pending=(Pending)TransactionSynchronizationManager.getResource(this);
  if(pending==null){
   pending=new Pending();TransactionSynchronizationManager.bindResource(this,pending);final Pending saved=pending;
   TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
    public void beforeCommit(boolean readOnly){saved.valid=validClock();saved.lo=nowUs()-errorUs;}
    public void afterCommit(){
     long hi=nowUs()+errorUs;boolean valid=saved.valid&&validClock();
     // This pool is separate: all original transactions may still hold their
     // business connections while post-commit callbacks execute.
     try(var db=evidencePool.getObject().getConnection();var ps=db.prepareStatement("INSERT INTO capacity_commit_evidence(transaction_id,run_id,clock_origin,lo_us,hi_us,clock_error_us,clock_valid) VALUES(?,?,?,?,?,?,?)")){
      db.setAutoCommit(true);ps.setString(1,saved.id);ps.setString(2,config.get("JAVA_CAPACITY_RUN_ID",""));ps.setString(3,origin);ps.setLong(4,saved.lo);ps.setLong(5,hi);ps.setLong(6,errorUs);ps.setBoolean(7,valid);ps.executeUpdate();
     }catch(java.sql.SQLException failure){throw new IllegalStateException("post-commit timing evidence unavailable",failure);}
    }
    public void afterCompletion(int status){TransactionSynchronizationManager.unbindResourceIfPossible(CapacityCommitEvidence.this);}
   });
  }
  var rows=new ArrayList<Object[]>();for(var id:identities)rows.add(new Object[]{kind,id[0],id[1],id[2],pending.id});
  jdbc.batchUpdate("INSERT IGNORE INTO capacity_identity_evidence(evidence_kind,tenant_id,call_id,attempt,transaction_id) VALUES(?,?,?,?,?)",rows);
 }
}
