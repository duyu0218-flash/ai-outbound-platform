package com.duozhi.outbound;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** The claim transaction already committed its immutable journal. Recovery
 * needs no surviving HTTP request or original task. External execution is still
 * protected by the installed owner fence and the gateway's single send CAS. */
@Service
public class DialCommandRecovery {
 private final JdbcTemplate jdbc;private final Transactions tx;private final Store store;private final JsonSupport json;
 private final DialService dial;private final DialDrainControl drain;private final Config config;
 public DialCommandRecovery(JdbcTemplate jdbc,Transactions tx,Store store,JsonSupport json,DialService dial,DialDrainControl drain,Config config){this.jdbc=jdbc;this.tx=tx;this.store=store;this.json=json;this.dial=dial;this.drain=drain;this.config=config;}
 @Scheduled(fixedDelayString="${JAVA_DIAL_RECOVERY_POLL_MS:1000}")
 public void tick(){if(config.flag("JAVA_WORKERS_ENABLED",false)&&config.flag("JAVA_DIAL_RECOVERY_ENABLED",true)&&!drain.frozen())try{processOne();}catch(RuntimeException failed){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Durable dial recovery deferred: {}",failed.getClass().getSimpleName());}}
 @SuppressWarnings("unchecked")
 boolean processOne(){
  String owner="recovery:"+UUID.randomUUID();
  var selected=tx.run(()->{
   var rows=jdbc.queryForList("SELECT * FROM dial_command_journal WHERE state IN ('PREPARED','MANUAL_HOLD') AND recovery_at<=UTC_TIMESTAMP(3) AND created_at<=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 5 SECOND) AND (recovery_until IS NULL OR recovery_until<UTC_TIMESTAMP(3)) ORDER BY recovery_at,created_at LIMIT 1 FOR UPDATE SKIP LOCKED");
   if(rows.isEmpty())return null;var row=rows.get(0);
   jdbc.update("UPDATE dial_command_journal SET recovery_owner=?,recovery_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 60 SECOND) WHERE tenant_id=? AND call_id=? AND attempt=?",owner,row.get("tenant_id"),row.get("call_id"),row.get("attempt"));return row;
  });
  if(selected==null)return false;
  long tenant=((Number)selected.get("tenant_id")).longValue();Object callId=selected.get("call_id"),attempt=selected.get("attempt");
  Runnable guard=()->{if(jdbc.queryForObject("SELECT COUNT(*) FROM dial_command_journal WHERE tenant_id=? AND call_id=? AND attempt=? AND recovery_owner=? AND recovery_until>=UTC_TIMESTAMP(3)",Long.class,tenant,callId,attempt,owner)!=1)throw new TaskWorker.Deferred();};
  try{
   TaskWorker.guarded(tenant,owner,guard,()->{
    var frozen=json.object(selected.get("frozen_payload").toString());var call=(Map<String,Object>)frozen.get("call");
    var current=store.require("callsession",tenant,callId);
    boolean queryOnly=selected.get("state").equals("MANUAL_HOLD")||CallLifecycle.TERMINAL.contains(current.get("status"))||CallLifecycle.number(current,"attempts")!=CallLifecycle.number(call,"attempts");
    if(queryOnly)dial.confirmClaimed(call,(Map<String,Object>)frozen.get("metadata"));else dial.sendClaimed(call,(Map<String,Object>)frozen.get("metadata"));return null;
   });
  }catch(DialService.PendingConfirmation uncertain){
   tx.serialized(()->{
    var latest=tx.call(callId);
    int changed=jdbc.update("UPDATE dial_command_journal SET state='MANUAL_HOLD',manual_hold_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND call_id=? AND attempt=? AND recovery_owner=? AND state='PREPARED' AND created_at<=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 60 SECOND)",tenant,callId,attempt,owner);
    if(changed==1&&latest!=null)store.insert("callevent",JsonSupport.map("call_session_id",callId,"event_type","dial_manual_hold","source","platform","payload",json.json(JsonSupport.map("attempt",attempt,"reason","no positive or reliably rejected outcome after 60 seconds"))));return null;
   });
  }catch(RuntimeException retry){/* Lease recovery is unbounded; no negative outcome is inferred. */}
  finally{tx.run(()->{jdbc.update("UPDATE dial_command_journal SET recovery_owner=NULL,recovery_until=NULL,recovery_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 5 SECOND) WHERE tenant_id=? AND call_id=? AND attempt=? AND recovery_owner=?",tenant,callId,attempt,owner);return null;});}
  return true;
 }
}
