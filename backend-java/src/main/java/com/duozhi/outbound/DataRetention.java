package com.duozhi.outbound;

import java.util.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/** Bounded cleanup of expired text and confirmed recording deletion requests. */
@Service
public class DataRetention {
    private final Store store;private final Transactions tx;private final Config config;private final Settings settings;private final Outbox outbox;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"retention");t.setDaemon(true);return t;});
    private final AtomicBoolean running=new AtomicBoolean();private volatile boolean ready;
    public DataRetention(Store store,Transactions tx,Config config,Settings settings,Outbox outbox){this.store=store;this.tx=tx;this.config=config;this.settings=settings;this.outbox=outbox;}
    @EventListener(ApplicationReadyEvent.class) public void ready(){ready=true;}
    @PreDestroy public void close(){ready=false;executor.shutdownNow();}
    private int days(Map<String,Object> policy,String name,int fallback){return Math.max(1,Math.min(3650,JsonSupport.integer(policy.get(name),fallback)));}
    private String redacted(long tenant,Object id,Object phone){String secret=config.get("SECRET_KEY",config.get("JWT_SECRET",""));if(secret.isEmpty())throw new ApiError(503,"retention redaction secret is missing");return "redacted:"+JsonSupport.hmac(secret,(tenant+":"+id+":"+phone).getBytes(StandardCharsets.UTF_8)).substring(0,24);}
    private void increment(Map<String,Integer> counts,String key){counts.put(key,counts.getOrDefault(key,0)+1);}
    public Map<String,Integer> purge(int limit){
        if(limit<1||limit>1000)throw new IllegalArgumentException("invalid retention batch size");
        return tx.run(()->{
            tx.identity("java-retention");Map<String,Integer> counts=new LinkedHashMap<>();LocalDateTime now=Store.now();
            for(Map<String,Object> tenant:store.list("tenant","ORDER BY id")){
                long tid=((Number)tenant.get("id")).longValue();Map<String,Object> policy=settings.get(tid,"compliance");
                LocalDateTime sensitive=now.minusDays(days(policy,"call_sensitive_data_retention_days",180)),text=now.minusDays(days(policy,"final_transcript_retention_days",30));
                for(boolean last:new boolean[]{false,true}){
                    String metric=last?"final_transcripts":"partial_transcripts";int remaining=limit-counts.getOrDefault(metric,0);if(remaining<=0)continue;
                    LocalDateTime cutoff=last?text:now.minusHours(Math.max(1,Math.min(720,JsonSupport.integer(policy.get("partial_transcript_retention_hours"),24))));
                    for(Map<String,Object> row:store.list("speechturn","WHERE tenant_id=? AND is_final=? AND created_at<=? ORDER BY created_at,id LIMIT ?",tid,last,store.encode("speechturn","created_at",cutoff),remaining)){store.delete("speechturn",tid,row.get("id"));increment(counts,metric);}
                }
                int remaining=limit-counts.getOrDefault("redacted_calls",0);
                if(remaining>0)for(Map<String,Object> call:store.list("callsession","WHERE tenant_id=? AND finished_at<=? AND next_attempt_at IS NULL AND status IN ('COMPLETED','FAILED','NO_ANSWER','BUSY','VOICEMAIL') AND (phone NOT LIKE 'redacted:%' OR last_transcript IS NOT NULL OR summary IS NOT NULL OR recording_url IS NOT NULL) ORDER BY finished_at,id LIMIT ?",tid,store.encode("callsession","finished_at",sensitive),remaining)){
                    Object id=store.encode("callsession","id",call.get("id"));String phone=Objects.toString(call.get("phone"),"");if(!phone.startsWith("redacted:"))phone=redacted(tid,call.get("id"),phone);
                    store.update("callsession",tid,call.get("id"),JsonSupport.map("phone",phone,"last_transcript",null,"summary",null,"recording_url",null,"last_error",null));
                    store.jdbc().update("UPDATE conversationstate SET data_json='{}',policy_json='{}',deadline=NULL,generation=generation+1 WHERE tenant_id=? AND call_id=?",tid,id);
                    store.jdbc().update("UPDATE productworkitem SET phone=?,detail_json='{}' WHERE tenant_id=? AND call_id=?",phone,tid,id);
                    store.jdbc().update("UPDATE callevent SET payload='{}' WHERE call_session_id=?",id);
                    store.jdbc().update("UPDATE callanalysis SET summary='',qa_flags_json='[]',structured_json='{}',automatic_result_json='{}',needs_review=? WHERE tenant_id=? AND call_session_id=?",false,tid,id);
                    store.jdbc().update("UPDATE callmetric SET detail='' WHERE tenant_id=? AND call_session_id=?",tid,id);
                    store.jdbc().update("UPDATE recordingasset SET provider_url='',provider_recording_id=NULL WHERE tenant_id=? AND call_session_id=? AND deleted_at IS NOT NULL",tid,id);increment(counts,"redacted_calls");
                }
                remaining=limit-counts.getOrDefault("orphan_work_items",0);
                if(remaining>0)for(Map<String,Object> item:store.list("productworkitem","WHERE tenant_id=? AND call_id IS NULL AND created_at<=? AND phone NOT LIKE 'redacted:%' ORDER BY created_at LIMIT ?",tid,store.encode("productworkitem","created_at",sensitive),remaining)){store.update("productworkitem",tid,item.get("id"),JsonSupport.map("phone",redacted(tid,item.get("id"),item.get("phone")),"detail_json","{}"));increment(counts,"orphan_work_items");}
                LocalDateTime cutoff=text.isBefore(sensitive)?sensitive:text;
                remaining=limit-counts.getOrDefault("task_payloads",0);
                if(remaining>0)for(Map<String,Object> task:store.list("taskoutbox","WHERE tenant_id=? AND task_type IN ('ai_turn','business_callback') AND payload_json<>'{}' AND state<>'PROCESSING' AND replace(aggregate_id,'-','') IN (SELECT replace(CAST(id AS TEXT),'-','') FROM callsession WHERE tenant_id=? AND finished_at<=? AND status IN ('COMPLETED','FAILED','NO_ANSWER','BUSY','VOICEMAIL')) ORDER BY created_at LIMIT ?",tid,tid,store.encode("callsession","finished_at",cutoff),remaining)){
                    Map<String,Object> change=JsonSupport.map("payload_json","{}","last_error","");if(Values.set("pending","failed").contains(task.get("state")))change.put("state","dead");store.update("taskoutbox",tid,task.get("id"),change);increment(counts,"task_payloads");
                }
                remaining=limit-counts.getOrDefault("sms_payloads",0);
                if(remaining>0)for(Map<String,Object> sms:store.list("smslog","WHERE tenant_id=? AND (content<>'' OR to_phone<>'redacted') AND call_session_id IN (SELECT id FROM callsession WHERE tenant_id=? AND finished_at<=?) ORDER BY created_at LIMIT ?",tid,tid,store.encode("callsession","finished_at",sensitive),remaining)){store.update("smslog",tid,sms.get("id"),JsonSupport.map("to_phone","redacted","content","","provider_error",null));increment(counts,"sms_payloads");}
            }
            for(Map<String,Object> asset:store.list("recordingasset","WHERE retention_until<=? AND deleted_at IS NULL AND state NOT IN ('deletion_pending','deletion_failed') ORDER BY retention_until,id LIMIT ?",store.encode("recordingasset","retention_until",now),limit)){
                long tenant=CallLifecycle.tenant(asset);Map<String,Object> task=outbox.enqueue(tenant,"recording_delete",asset.get("id"),"recording-delete:"+asset.get("id"),JsonSupport.map("recording_asset_id",asset.get("id")));
                store.update("recordingasset",tenant,asset.get("id"),JsonSupport.map("state","dead".equals(task.get("state"))?"deletion_failed":"deletion_pending"));increment(counts,"recording_deletion_tasks");
            }
            return counts;
        });
    }
    public int archive(int limit){
        if(limit<1||limit>1000)throw new IllegalArgumentException("invalid archive batch size");Object cutoff=store.encode("callsession","finished_at",Store.now().minusDays(180));
        List<Map<String,Object>> candidates=store.list("taskoutbox","WHERE state='COMPLETED' AND updated_at<=? AND EXISTS (SELECT 1 FROM callsession c WHERE c.tenant_id=taskoutbox.tenant_id AND replace(CAST(c.id AS TEXT),'-','')=replace(taskoutbox.aggregate_id,'-','') AND c.finished_at<=? AND c.phone LIKE 'redacted:%' AND c.status IN ('COMPLETED','FAILED','NO_ANSWER','BUSY','VOICEMAIL')) ORDER BY updated_at LIMIT ?",cutoff,cutoff,limit);int archived=0;
        for(Map<String,Object> task:candidates){Boolean removed=tx.run(()->{
            tx.identity(task.get("idempotency_key").toString());Map<String,Object> latest=store.one("taskoutbox","WHERE id=?"+store.lock(),store.encode("taskoutbox","id",task.get("id")));if(latest==null||!"completed".equals(latest.get("state"))||LocalDateTime.parse(latest.get("updated_at").toString()).isAfter(Store.now().minusDays(180)))return false;
            if(store.one("taskreceipt","WHERE id=?",store.encode("taskreceipt","id",task.get("id")))==null)store.insert("taskreceipt",JsonSupport.map("id",latest.get("id"),"tenant_id",latest.get("tenant_id"),"task_type",latest.get("task_type"),"aggregate_id",latest.get("aggregate_id"),"idempotency_key",latest.get("idempotency_key"),"completed_at",latest.get("updated_at")));
            store.delete("taskoutbox",CallLifecycle.tenant(latest),latest.get("id"));return true;
        });if(removed)archived++;}
        for(String table:Arrays.asList("callevent","callmetric"))store.jdbc().update("DELETE FROM "+table+" WHERE id IN (SELECT e.id FROM "+table+" e JOIN callsession c ON c.id=e.call_session_id WHERE c.finished_at<=? AND e.created_at<=? AND c.phone LIKE 'redacted:%' AND c.status IN ('COMPLETED','FAILED','NO_ANSWER','BUSY','VOICEMAIL') ORDER BY e.created_at LIMIT ?)",cutoff,cutoff,limit);
        return archived;
    }
    @Scheduled(fixedDelayString="${JAVA_RETENTION_POLL_MS:60000}") public void tick(){if(!ready||!config.flag("JAVA_WORKERS_ENABLED",false)||!running.compareAndSet(false,true))return;executor.submit(()->{try{int limit=Math.max(1,Math.min(1000,config.number("RETENTION_BATCH_SIZE",500)));purge(limit);archive(limit);}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(DataRetention.class).warn("retention error_type={}",e.getClass().getSimpleName());}finally{running.set(false);}});}
}
