package com.duozhi.outbound.embedded;

import java.sql.Connection;

/** Shares the barrier row with the business control, within the gateway's own transaction. */
final class DialSendGate {
    private DialSendGate() { }
    static boolean permitted(Connection db,String call,long tenant,long attempt)throws java.sql.SQLException {
        var projection=VoiceAdmissionBatchScope.current();
        var rows=projection==null?LocalLedger.query(db,"SELECT phase,epoch FROM dial_admission_control WHERE id=1 FOR SHARE"):java.util.List.of(projection.control(db));
        if(rows.size()!=1)return false;
        var row=rows.get(0);String phase=row.get("phase").toString();
        if(phase.equals("RUNNING"))return true;
        return phase.equals("ADMISSION_CLOSED") && LocalLedger.scalar(db,"SELECT COUNT(*) FROM dial_drain_member WHERE epoch=? AND tenant_id=? AND call_id=? AND attempt=?",row.get("epoch"),tenant,call.replace("-",""),attempt)==1;
    }
}
