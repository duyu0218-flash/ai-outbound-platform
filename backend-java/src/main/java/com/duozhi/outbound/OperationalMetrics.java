package com.duozhi.outbound;

import java.util.*;
import java.time.*;
import java.lang.management.ManagementFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

/** Database metrics are separate from DB-independent process diagnostics. */
@RestController
public class OperationalMetrics {
    private final Store store;private final Config config;private final CallbackInbox inbox;private final StabilityAdmission stability;
    public OperationalMetrics(Store store,Config config,CallbackInbox inbox,StabilityAdmission stability){this.store=store;this.config=config;this.inbox=inbox;this.stability=stability;}
    private void authorize(String header){String token=config.get("METRICS_TOKEN","");String file=config.get("METRICS_TOKEN_FILE","");if(!file.isEmpty())try{token=ConfigFiles.readUtf8(file,4096).trim();}catch(java.io.IOException e){throw new ApiError(401,"invalid metrics token");}if(token.isEmpty()||!Crypto.equal("Bearer "+token,header))throw new ApiError(401,"invalid metrics token");}
    private String label(Object v){return Objects.toString(v,"").replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");}
    private void metric(StringBuilder out,String name,Object value){out.append(name).append(' ').append(value).append('\n');}
    private ResponseEntity<String> response(StringBuilder body){return ResponseEntity.ok().header("Content-Type","text/plain; version=0.0.4; charset=utf-8").body(body.toString());}
    @GetMapping("/metrics/runtime") public ResponseEntity<String> runtime(@RequestHeader(value="Authorization",required=false) String token){authorize(token);StringBuilder out=new StringBuilder();
        String process=ManagementFactory.getRuntimeMXBean().getName().split("@")[0];metric(out,"ai_outbound_process_id",process);metric(out,"ai_outbound_jvm_threads",ManagementFactory.getThreadMXBean().getThreadCount());metric(out,"ai_outbound_jvm_heap_used_bytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());metric(out,"ai_outbound_jvm_uptime_seconds",ManagementFactory.getRuntimeMXBean().getUptime()/1000.0);return response(out);
    }
    @GetMapping("/metrics") public ResponseEntity<String> metrics(@RequestHeader(value="Authorization",required=false) String token){authorize(token);StringBuilder out=new StringBuilder();metric(out,"ai_outbound_up",1);
        for(String[] group:new String[][]{{"callsession","status","ai_outbound_calls","status"},{"callsession","voice_ai_pipeline","ai_outbound_calls_by_pipeline","pipeline"},{"taskoutbox","state","ai_outbound_tasks","state"}})
            for(Map<String,Object> row:store.jdbc().queryForList("SELECT "+group[1]+" AS value,COUNT(*) AS total FROM "+group[0]+" GROUP BY "+group[1]))metric(out,group[2]+"{"+group[3]+"=\""+label(Objects.toString(row.get("value"),"").transform(Ascii::lower))+"\"}",row.get("total"));
        for(String kind:Arrays.asList("ai_turn","business_callback","recording_ingest","recording_delete","dial_call","after_playback","send_sms","interrupt_playback")){
            Map<String,Object> oldest=store.one("taskoutbox","WHERE task_type=? AND state IN ('PENDING','FAILED') AND available_at<=? ORDER BY available_at LIMIT 1",kind,store.encode("taskoutbox","available_at",Store.now()));
            metric(out,"ai_outbound_task_oldest_ready_seconds{type=\""+kind+"\"}",oldest==null?0:Math.max(0,Duration.between(LocalDateTime.parse(oldest.get("available_at").toString()),Store.now()).toMillis()/1000.0));
        }
        for(String stage:Arrays.asList("ai.turn","tts.dispatch","tts.playback","asr.final")){
            List<Map<String,Object>> rows=store.list("callmetric","WHERE stage=? AND duration_ms IS NOT NULL AND created_at>=? ORDER BY created_at DESC LIMIT 10000",stage,store.encode("callmetric","created_at",Store.now().minusSeconds(300)));
            List<Integer> values=new ArrayList<>();for(Map<String,Object> row:rows)values.add(CallLifecycle.number(row,"duration_ms"));Collections.sort(values);metric(out,"ai_outbound_stage_recent_samples{stage=\""+stage+"\"}",values.size());
            for(double quantile:new double[]{.5,.95,.99})metric(out,"ai_outbound_stage_recent_seconds{stage=\""+stage+"\",quantile=\""+quantile+"\"}",values.isEmpty()?0:values.get(Math.max(0,(int)Math.ceil(quantile*values.size())-1))/1000.0);
        }
        metric(out,"ai_outbound_locked_users",store.count("user","WHERE locked_until>?",store.encode("user","locked_until",Store.now())));
        metric(out,"ai_outbound_recording_deletion_failures",store.count("recordingasset","WHERE state='deletion_failed'"));metric(out,"ai_outbound_recording_ingestion_failures",store.count("recordingasset","WHERE state='ingest_failed'"));
        if(config.flag("CALLBACK_INBOX_ENABLED",false))inbox.snapshot().forEach((k,v)->{if(v instanceof Number)metric(out,"ai_outbound_callback_inbox_"+k,v);else if(v instanceof Boolean)metric(out,"ai_outbound_callback_inbox_"+k,Boolean.TRUE.equals(v)?1:0);});
        Map<String,Object> control=stability.status();metric(out,"ai_outbound_stability_dial_fraction",control.get("fraction"));metric(out,"ai_outbound_stability_state{state=\""+label(control.get("mode"))+"\"}",1);return response(out);
    }
}
