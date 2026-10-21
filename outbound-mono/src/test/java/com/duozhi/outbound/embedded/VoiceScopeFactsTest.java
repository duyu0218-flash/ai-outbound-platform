package com.duozhi.outbound.embedded;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VoiceScopeFactsTest {
 final double now=86400d*20000+2*3600+30;
 void insert(LocalLedger ledger,String id,long tenant,String line,double created,Double ended,String state,String sending,long cost){ledger.transaction(db->{LocalLedger.execute(db,"INSERT INTO attempts(call_id,attempt,tenant,line,uuid,digest,payload,created,deadline,cost,rate,multiplier,state,send_state,ended) VALUES(?,1,?,?,?,'digest','{}',?,?,?,1,2,?,?,?)",id,tenant,line,id,created,created+3600,cost,state,sending,ended);return null;});}
 void matchesOracle(LocalLedger ledger,double clock){
  for(var scope:List.of(Map.entry("1=1",List.<Object>of()),Map.entry("tenant=?",List.<Object>of(1L)),Map.entry("tenant=? AND line=?",List.<Object>of(1L,"a")),Map.entry("tenant=? AND line=?",List.<Object>of(2L,"b")))){
   ledger.read(db->{
    var projected=VoiceScopeFacts.read(db,scope.getKey(),scope.getValue(),clock);var args=scope.getValue();
    assertThat(((Number)projected.get("active")).longValue()).isEqualTo(LocalLedger.scalar(db,"SELECT COUNT(*) FROM attempts WHERE "+scope.getKey()+" AND state!='ended'",args.toArray()));
    var dayArgs=new ArrayList<>(args);dayArgs.add(Math.floor(clock/86400)*86400);
    assertThat(((Number)projected.get("daily")).longValue()).isEqualTo(LocalLedger.scalar(db,"SELECT COUNT(*) FROM attempts WHERE "+scope.getKey()+" AND send_state!='REJECTED' AND created>=?",dayArgs.toArray()));
    for(long window:new long[]{3600,86400}){
     var budgetArgs=new ArrayList<>(args);double start=Math.floor(clock/window)*window;budgetArgs.add(start);budgetArgs.add(start);
     assertThat(((Number)projected.get(window==3600?"hour_budget":"day_budget")).longValue()).isEqualTo(LocalLedger.scalar(db,"SELECT COALESCE(SUM(cost),0) FROM attempts WHERE "+scope.getKey()+" AND (created>=? OR ended>=? OR state!='ended')",budgetArgs.toArray()));
    }return null;
   });
  }
 }
 @Test void compactFactsMatchEveryLegacyPredicateAcrossUpdatesAndTimeBoundaries()throws Exception{
  try(var ledger=new LocalLedger(new Settings(LedgerTestDatabase.settings(Map.of())))){
   insert(ledger,"old-active",1,"a",now-3*86400,null,"active","PBX_STARTED",12);
   insert(ledger,"old-ended",1,"a",now-2*86400,now-3600,"ended","ENDED",5);
   insert(ledger,"carried-ended",1,"a",now-2*86400,now+10,"ended","ENDED",7);
   insert(ledger,"future-ended",2,"b",now+2*86400,now+2*86400,"ended","ENDED",9);
   insert(ledger,"rejected",1,"a",now,null,"ended","REJECTED",0);
   insert(ledger,"null-ended",1,"a",now,null,"ended","ENDED",4);
   for(double clock:new double[]{now,now+3600,now+86400,now+3*86400})matchesOracle(ledger,clock);
   ledger.transaction(db->{LocalLedger.execute(db,"UPDATE attempts SET state='ended',send_state='ENDED',ended=?,cost=3 WHERE uuid='old-active'",now+3700);return null;});
   ledger.transaction(db->{LocalLedger.execute(db,"UPDATE attempts SET tenant=2,line='b',created=? WHERE uuid='old-ended'",now+86400);return null;});
   ledger.transaction(db->{LocalLedger.execute(db,"UPDATE attempts SET state='pending',send_state='REGISTERED',created=?,ended=NULL,cost=6 WHERE uuid='rejected'",now);return null;});
   for(double clock:new double[]{now,now+3600,now+86400})matchesOracle(ledger,clock);
   assertThatThrownBy(()->ledger.transaction(db->{LocalLedger.execute(db,"UPDATE attempts SET cost=99 WHERE uuid='rejected'");throw new IllegalStateException("injected rollback");})).isInstanceOf(IllegalStateException.class);
   matchesOracle(ledger,now);
   assertThatThrownBy(()->ledger.transaction(db->{LocalLedger.execute(db,"DELETE FROM attempts WHERE uuid='rejected'");return null;})).isInstanceOf(RuntimeException.class);
   ledger.transaction(db->{LocalLedger.execute(db,"DELETE FROM attempts WHERE uuid='future-ended'");return null;});matchesOracle(ledger,now);
   ledger.transaction(db->{LocalLedger.execute(db,"UPDATE dial_admission_control SET phase='FROZEN' WHERE id=1");return null;});
   assertThatThrownBy(()->ledger.transaction(db->{LocalLedger.execute(db,"UPDATE attempts SET result='{}' WHERE uuid='rejected'");return null;})).isInstanceOf(RuntimeException.class);
  }
 }
 @Test void optInReadsStillEnforceEveryHardLimit()throws Exception{
  var settings=new Settings(LedgerTestDatabase.settings(Map.of("VOICE_MATERIALIZED_LIMITS_ENABLED","true","VOICE_MAX_CONCURRENT","1","VOICE_CALLBACK_BASE_URL","http://127.0.0.1:1")));
  try(var ledger=new LocalLedger(settings)){
   var security=new VoiceSecurity(settings,ledger);var payload=Map.<String,Object>of("call_id","first","tenant_id",1,"phone","+8613900000001","webhook_url","http://127.0.0.1:1/api/v1/webhooks/telephony/status","metadata",Map.of("tenant_id",1,"attempt",1));
   var route=Map.<String,Object>of("max_concurrent",10,"cps",10,"calls_per_day",100,"hour_budget_minor",50000,"day_budget_minor",50000);
   security.admit(payload,route,true,10);
   var second=new LinkedHashMap<>(payload);second.put("call_id","second");
   assertThatThrownBy(()->security.admit(second,route,true,10)).isInstanceOf(Support.Failure.class);
   assertThat(ledger.<Long>read(db->LocalLedger.scalar(db,"SELECT COUNT(*) FROM attempts"))).isEqualTo(1);
  }
 }
}
