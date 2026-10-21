package com.duozhi.outbound;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

/** Frozen backfill, independently checked L/E facts, and explicit read publication. */
@Service
public class DialDailyCounters {
    private final JdbcTemplate jdbc;
    private final Transactions tx;
    private final Settings settings;
    public DialDailyCounters(JdbcTemplate jdbc,Transactions tx,Settings settings){this.jdbc=jdbc;this.tx=tx;this.settings=settings;}
    public Long readGeneration(){
        var control=jdbc.queryForMap("SELECT generation_id,daily_read FROM dial_admission_control WHERE id=1");
        if(!"counter".equals(control.get("daily_read")))return null;
        if(!(control.get("generation_id") instanceof Number id))throw new TaskWorker.Deferred();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM dial_migration_generation WHERE id=? AND state='READY'",Long.class,id.longValue())!=1)throw new TaskWorker.Deferred();
        return id.longValue();
    }
    DailyAttemptCount read(long generation,long tenant,String phone,LocalDateTime day,String zone){
        String scope=phone==null?"tenant":"phone";
        var rows=jdbc.queryForList("SELECT legacy_component,event_component,timezone_revision FROM dial_daily_counter WHERE generation_id=? AND scope_type=? AND tenant_id=? AND phone_digest=UNHEX(SHA2(?,256)) AND phone=? AND window_start=? AND ready=TRUE",
            generation,scope,tenant,phone==null?"":phone,phone==null?"":phone,day);
        if(rows.size()!=1||!zone.equals(rows.get(0).get("timezone_revision")))throw new TaskWorker.Deferred();
        var row=rows.get(0);
        return new DailyAttemptCount(((Number)row.get("legacy_component")).longValue(),((Number)row.get("event_component")).longValue());
    }
    public boolean frozen(){return "FROZEN".equals(jdbc.queryForObject("SELECT phase FROM dial_admission_control WHERE id=1",String.class));}
    private void requireFrozen(){
        if(!"FROZEN".equals(jdbc.queryForObject("SELECT phase FROM dial_admission_control WHERE id=1 FOR UPDATE",String.class)))
            throw new ApiError(409,"daily backfill requires a frozen and drained control domain");
    }
    /** Caller must first prove all external resources drained; this method cannot issue that proof. */
    public Map<String,Object> build(Instant at){
        return tx.serialized(()->{
            requireFrozen();
            var key=new GeneratedKeyHolder();
            jdbc.update(connection->{var ps=connection.prepareStatement("INSERT INTO dial_migration_generation(state) VALUES('BUILDING')",Statement.RETURN_GENERATED_KEYS);return ps;},key);
            long generation=Objects.requireNonNull(key.getKey()).longValue();
            var inventory=sha256();long expectedRows=0;
            for(var tenant:jdbc.queryForList("SELECT id FROM tenant ORDER BY id")){
                long tid=((Number)tenant.get("id")).longValue();
                String zone=Objects.toString(settings.get(tid,"compliance").get("timezone"),"Asia/Shanghai");
                var tenantWindows=windows(generation,tid,null,zone,at);jdbc.batchUpdate(INSERT,tenantWindows);
                for(var window:tenantWindows){inventory(inventory,window);expectedRows++;}
                var phones=jdbc.queryForList("SELECT p.phone,COALESCE(NULLIF(c.timezone,''),?) AS zone FROM (SELECT phone FROM callsession WHERE tenant_id=? UNION SELECT phone FROM contact WHERE tenant_id=?) p LEFT JOIN contact c ON c.tenant_id=? AND c.phone=p.phone ORDER BY UNHEX(SHA2(p.phone,256)),p.phone",zone,tid,tid,tid);
                var windows=new ArrayList<Object[]>();
                for(var phone:phones)windows.addAll(windows(generation,tid,phone.get("phone").toString(),phone.get("zone").toString(),at));
                for(var window:windows){inventory(inventory,window);expectedRows++;}
                for(int start=0;start<windows.size();start+=500)jdbc.batchUpdate(INSERT,windows.subList(start,Math.min(windows.size(),start+500)));
            }
            // The lower-bound predicates are the frozen legacy oracle. No upper bound is added.
            for(String scope:List.of("tenant","phone"))jdbc.update("UPDATE dial_daily_counter d SET legacy_component="+legacySql(scope)+",event_component="+eventsSql(scope)+",ready=TRUE WHERE generation_id=? AND scope_type=?",generation,scope);
            jdbc.update("UPDATE dial_migration_generation SET evidence=JSON_OBJECT('expected_rows',?,'inventory_digest',?,'reference_at',?) WHERE id=?",expectedRows,HexFormat.of().formatHex(inventory.digest()),at.toString(),generation);
            var audit=verify(generation);
            if(((Number)audit.get("mismatches")).longValue()!=0)throw new ApiError(409,"daily generation failed its legacy oracle");
            jdbc.update("UPDATE dial_migration_generation SET state='VERIFIED',verified_at=UTC_TIMESTAMP(3) WHERE id=?",generation);
            return Map.of("generation_id",generation,"state","VERIFIED","oracle",audit);
        });
    }
    private static final String INSERT="INSERT INTO dial_daily_counter(generation_id,scope_type,tenant_id,phone,window_start,timezone_revision,legacy_component,event_component) VALUES(?,?,?,?,?,?,0,0)";
    private static List<Object[]> windows(long generation,long tenant,String phone,String zoneName,Instant at){
        var zone=ZoneId.of(zoneName);var date=at.atZone(zone).toLocalDate();var result=new ArrayList<Object[]>();
        for(int next=0;next<2;next++)result.add(new Object[]{generation,phone==null?"tenant":"phone",tenant,phone==null?"":phone,LocalDateTime.ofInstant(date.plusDays(next).atStartOfDay(zone).toInstant(),ZoneOffset.UTC),zoneName});
        return result;
    }
    private static java.security.MessageDigest sha256(){try{return java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void inventory(java.security.MessageDigest digest,Object[] row){
        for(int index=1;index<row.length;index++){byte[] value=Objects.toString(row[index],"").getBytes(java.nio.charset.StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());digest.update(value);}
    }
    private static String legacySql(String scope){
        return "(SELECT COALESCE(SUM(c.attempts),0) FROM callsession c WHERE c.tenant_id=d.tenant_id AND "+(scope.equals("tenant")?"c.started_at>=d.window_start":"c.phone=d.phone AND c.created_at>=d.window_start")+")";
    }
    private static String eventsSql(String scope){
        return "(SELECT COUNT(*) FROM callsession c STRAIGHT_JOIN callevent e ON c.id=e.call_session_id WHERE c.tenant_id=d.tenant_id "+(scope.equals("phone")?"AND c.phone=d.phone ":"")+"AND e.event_type='dial_attempt_claimed' AND e.created_at>=d.window_start)";
    }
    public Map<String,Object> verify(long generation){
        return tx.run(()->verifySnapshot(generation));
    }
    private Map<String,Object> verifySnapshot(long generation){
        var saved=jdbc.queryForList("SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(evidence,'$.expected_rows')) AS UNSIGNED) AS expected_rows,JSON_UNQUOTE(JSON_EXTRACT(evidence,'$.inventory_digest')) AS inventory_digest FROM dial_migration_generation WHERE id=?",generation);
        if(saved.isEmpty())throw new ApiError(404,"daily generation not found");
        var manifest=saved.get(0);if(!(manifest.get("expected_rows") instanceof Number)||manifest.get("inventory_digest")==null)throw new ApiError(409,"daily generation has no independently frozen inventory");
        var actual=sha256();long[] rows={0};
        jdbc.query("SELECT scope_type,tenant_id,phone,window_start,timezone_revision FROM dial_daily_counter WHERE generation_id=? ORDER BY tenant_id,CASE WHEN scope_type='tenant' THEN 0 ELSE 1 END,phone_digest,window_start",(org.springframework.jdbc.core.RowCallbackHandler)result->{inventory(actual,new Object[]{generation,result.getString(1),result.getLong(2),result.getString(3),result.getTimestamp(4).toLocalDateTime(),result.getString(5)});rows[0]++;},generation);
        boolean complete=rows[0]==((Number)manifest.get("expected_rows")).longValue()&&HexFormat.of().formatHex(actual.digest()).equals(manifest.get("inventory_digest"));
        long missing=jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT tenant_id,phone FROM callsession UNION SELECT tenant_id,phone FROM contact) p WHERE (SELECT COUNT(*) FROM dial_daily_counter d WHERE d.generation_id=? AND d.scope_type='phone' AND d.tenant_id=p.tenant_id AND d.phone_digest=UNHEX(SHA2(p.phone,256)) AND d.phone=p.phone AND d.ready=TRUE)<>2",Long.class,generation);
        missing+=jdbc.queryForObject("SELECT COUNT(*) FROM tenant t WHERE (SELECT COUNT(*) FROM dial_daily_counter d WHERE d.generation_id=? AND d.scope_type='tenant' AND d.tenant_id=t.id AND d.ready=TRUE)<>2",Long.class,generation);
        complete&=missing==0;
        // Compare both components, not only max(L,E). This is intentionally independent of trigger deltas.
        long mismatches=complete?0:1;
        for(String scope:List.of("tenant","phone"))mismatches+=jdbc.queryForObject("SELECT COUNT(*) FROM dial_daily_counter d WHERE generation_id=? AND scope_type=? AND (ready=FALSE OR legacy_component<>"+legacySql(scope)+" OR event_component<>"+eventsSql(scope)+")",Long.class,generation,scope);
        return Map.of("generation_id",generation,"mismatches",mismatches,"rows",rows[0],"inventory_complete",complete,"missing_scope_keys",missing);
    }
    public Map<String,Object> publish(long generation,boolean readCounters){
        return tx.serialized(()->{
            requireFrozen();
            var state=jdbc.queryForObject("SELECT state FROM dial_migration_generation WHERE id=? FOR UPDATE",String.class,generation);
            if(!"VERIFIED".equals(state))throw new ApiError(409,"generation is not verified");
            if(((Number)verify(generation).get("mismatches")).longValue()!=0)throw new ApiError(409,"generation changed before publication");
            jdbc.update("UPDATE dial_migration_generation SET state='RETIRED' WHERE state='READY'");
            jdbc.update("UPDATE dial_migration_generation SET state='READY' WHERE id=?",generation);
            jdbc.update("UPDATE dial_admission_control SET generation_id=?,daily_read=?,revision=revision+1 WHERE id=1",generation,readCounters?"counter":"legacy");
            return Map.of("generation_id",generation,"state","READY","daily_read",readCounters?"counter":"legacy");
        });
    }
}
