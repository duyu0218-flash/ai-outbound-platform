package com.duozhi.outbound;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Only due realtime work exerts this pressure. Future timers and cold analysis
 * do not consume the realtime deadline budget. Missing observations fail closed. */
@Service
final class RealtimeTaskPressure {
    private final JdbcTemplate jdbc;
    RealtimeTaskPressure(JdbcTemplate jdbc){this.jdbc=jdbc;}
    Map<String,Double> snapshot(){return snapshot(-1);}
    Map<String,Double> snapshot(long tenant){
        var result=new HashMap<String,Double>();result.put("control_age_sec",0d);result.put("ai_age_sec",0d);result.put("dead",0d);
        var rows=jdbc.queryForList("SELECT CASE WHEN t.task_type='ai_turn' THEN 'ai_age_sec' ELSE 'control_age_sec' END AS kind,GREATEST(0,COALESCE(TIMESTAMPDIFF(MICROSECOND,MIN(CASE WHEN t.state!='DEAD' AND t.available_at<=UTC_TIMESTAMP(3) THEN t.available_at END),UTC_TIMESTAMP(3)),0))/1000000.0 AS age,COALESCE(SUM(t.state='DEAD'),0) AS dead FROM tenant n STRAIGHT_JOIN taskoutbox t FORCE INDEX(ix_taskoutbox_ready_scope) ON t.tenant_id=n.id WHERE (?<0 OR n.id=?) AND t.task_type IN ('ai_turn','after_playback','interrupt_playback') AND t.state IN ('PENDING','FAILED','PROCESSING','DEAD') GROUP BY kind",tenant,tenant);
        for(var row:rows){result.put(row.get("kind").toString(),((Number)row.get("age")).doubleValue());result.merge("dead",((Number)row.get("dead")).doubleValue(),Double::sum);}return result;
    }
    static double fraction(Map<String,Double> sample){
        if(sample==null||sample.getOrDefault("dead",1d)!=0)return 0;
        return Math.min(budget(sample.getOrDefault("control_age_sec",Double.NaN),.1,.5),budget(sample.getOrDefault("ai_age_sec",Double.NaN),.5,1.5));
    }
    private static double budget(double age,double soft,double stop){
        if(!Double.isFinite(age)||age<0||age>=stop)return 0;
        return age<=soft?1:(stop-age)/(stop-soft);
    }
}
