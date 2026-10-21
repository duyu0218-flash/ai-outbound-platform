package com.duozhi.outbound.embedded;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Durable intent precedes external I/O. An unknown intent is never replayed. */
final class VoiceSendJournal {
    private final LocalLedger ledger;
    VoiceSendJournal(LocalLedger ledger) { this.ledger=ledger; }

    boolean claim(String uuid) {
        return ledger.transaction(db -> {
            var rows=LocalLedger.query(db,"SELECT uuid,send_state,call_id,tenant,attempt,protocol_version FROM attempts WHERE uuid=?",uuid);
            if(rows.isEmpty()) return false;
            return claim(rows.get(0));
        });
    }
    boolean claim(Map<String,Object> row){
        return claim(row,null);
    }
    boolean claim(Map<String,Object> row,Map<String,Object> payload){
        return ledger.transaction(db->{
            if(!DialSendGate.permitted(db,row.get("call_id").toString(),((Number)row.get("tenant")).longValue(),((Number)row.get("attempt")).longValue()))return false;
            var scope=VoiceAdmissionBatchScope.current();String stopped="SELECT COUNT(*) FROM flags WHERE key='stopped' AND value='1'";
            if((scope==null?LocalLedger.scalar(db,stopped):scope.count(db,"__stopped",stopped))!=0)return false;
            long fence=0;
            if(Support.integer(row,"protocol_version",1)==2){if(payload==null)return false;var auth=VoiceAuthorizations.requireInstalled(db,payload);if(!auth.get("reservation_id").equals(row.get("registration_id")))return false;fence=Support.integer(auth,"fence",0);}
            else {var projection=VoiceAdmissionBatchScope.current();if("db".equals((projection==null?LocalLedger.query(db,"SELECT admission_mode FROM dial_admission_control WHERE id=1 FOR SHARE").get(0):projection.control(db)).get("admission_mode")))return false;}
            int changed=LocalLedger.update(db,"UPDATE attempts SET send_state='DISPATCH_INTENT',send_intent_at=?,send_fence=? WHERE uuid=? AND tenant=? AND call_id=? AND attempt=? AND send_state='REGISTERED' AND state!='ended'",LocalLedger.now(),fence,row.get("uuid"),row.get("tenant"),row.get("call_id"),row.get("attempt"));
            if(changed==1)row.put("send_state","DISPATCH_INTENT");return changed==1;
        });
    }

    void accepted(String uuid, Map<String,Object> evidence) {
        String encoded=new String(Support.canonical(evidence),StandardCharsets.UTF_8);
        ledger.transaction(db -> {
            LocalLedger.execute(db,"UPDATE attempts SET result=?,send_evidence=?,send_state=CASE WHEN state='ended' THEN 'ENDED' WHEN observed<>0 THEN 'PBX_STARTED' ELSE 'PBX_ACCEPTED' END,state=CASE WHEN state='ended' THEN state ELSE 'active' END WHERE uuid=?",encoded,encoded,uuid);
            return null;
        });
    }

    void uncertain(String uuid) {
        ledger.transaction(db -> {
            LocalLedger.execute(db,"UPDATE attempts SET send_state='RECONCILING' WHERE uuid=? AND state!='ended' AND send_state='DISPATCH_INTENT'",uuid);
            return null;
        });
    }

    void escalate() {
        ledger.transaction(db -> {
            var rows=LocalLedger.query(db,"SELECT uuid FROM attempts WHERE state!='ended' AND send_state IN ('DISPATCH_INTENT','RECONCILING') AND send_intent_at<=?",LocalLedger.now()-60);
            for(var row:rows) {
                LocalLedger.execute(db,"UPDATE attempts SET send_state='MANUAL_HOLD',manual_hold_at=? WHERE uuid=? AND state!='ended'",LocalLedger.now(),row.get("uuid"));
                LocalLedger.execute(db,"INSERT INTO audit(at,action,detail) VALUES (?,'send-manual-hold',?)",LocalLedger.now(),row.get("uuid"));
            }
            return null;
        });
    }
}
