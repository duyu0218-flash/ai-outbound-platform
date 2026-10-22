package com.duozhi.outbound;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

/** A bounded cohort retains one durable task and idempotent command per call.
 * No JDBC transaction is held across the gateway command. */
@Service
public class DialBatchWorker {
    @org.springframework.beans.factory.annotation.Autowired private CapacityCommitEvidence timing;
    @org.springframework.beans.factory.annotation.Autowired private DialRateControl rate;
    @org.springframework.beans.factory.annotation.Autowired private DialDrainControl drain;
    private final Store store;private final DialBatchStore db;private final Transactions tx;private final DialService dial;private final Config config;private final JsonSupport json;private final CallbackInbox inbox;
    private long cursor;
    public DialBatchWorker(Store store,DialBatchStore db,Transactions tx,DialService dial,Config config,JsonSupport json,CallbackInbox inbox){this.store=store;this.db=db;this.tx=tx;this.dial=dial;this.config=config;this.json=json;this.inbox=inbox;}
    int size(){return Math.max(1,Math.min(500,config.number("JAVA_DIAL_BATCH_SIZE",1)));}
    int enqueue(){
        return tx.serialized(()->{int total=0;for(var tenant:store.query("tenant","allForMaintenance",Map.of())){
            if(db.count("readyTaskCount",JsonSupport.map("tenantId",tenant.get("id"),"now",Store.now()))>=1000)continue;
            var pending=db.rows("queuedCandidates",JsonSupport.map("tenantId",tenant.get("id"),"now",Store.now(),"limit",1000));if(pending.isEmpty())continue;
            var rows=pending.stream().map(c->JsonSupport.map("id",UUID.randomUUID().toString(),"tenant_id",tenant.get("id"),"call_id",c.get("id"),"key","dial:"+c.get("id")+":"+(((Number)c.get("attempts")).intValue()+1),"payload",json.json(JsonSupport.map("call_id",c.get("id"),"attempt",c.get("attempts"))))).toList();total+=db.write("enqueueRows",JsonSupport.map("rows",rows,"now",Store.now()));
            timing.recordBatch("queue",pending.stream().map(c->new Object[]{tenant.get("id"),c.get("id"),((Number)c.get("attempts")).intValue()+1}).toList());
        }return total;});
    }
    private Object cutoff(){return Store.now().minusSeconds(Math.max(2,config.number("TASK_LEASE_SEC",30)));}
    private List<Map<String,Object>> claim(Long only){
        // Dial cohorts have their own row-lock/revalidation path. Do not hold
        // the AI/general claim mutex while reading hundreds of dial tasks.
        long begin=System.nanoTime();long[] stages=new long[6];int[] count={0};
        try{return tx.serializedIdentity("java-dial-batch-claim",()->{stages[0]=System.nanoTime();var tenants=new ArrayList<>(store.query("tenant","allForMaintenance",Map.of()));stages[1]=System.nanoTime();long previous=cursor;
            tenants.sort(Comparator.<Map<String,Object>>comparingInt(t->((Number)t.get("id")).longValue()>previous?0:1).thenComparingLong(t->((Number)t.get("id")).longValue()));
            for(var tenant:tenants){long id=((Number)tenant.get("id")).longValue();if(only!=null&&id!=only)continue;
                var params=JsonSupport.map("tenantId",id,"now",Store.now(),"cutoff",cutoff(),"limit",size(),"recoverOnly",drain.sendingClosed());var expired=db.rows("expiredFinalTasks",params);if(!expired.isEmpty()){var expiration=new LinkedHashMap<>(params);expiration.put("ids",expired.stream().map(r->r.get("id").toString()).toList());db.write("expireFinalTasks",expiration);}
                stages[2]=System.nanoTime();var candidates=db.tasks(params);stages[3]=System.nanoTime();if(candidates.isEmpty())continue;
                var locked=new LinkedHashMap<>(params);locked.put("ids",candidates.stream().map(t->t.get("id").toString()).toList());
                var tasks=db.lockedTasks(locked).stream().filter(t->CallLifecycle.number(t,"attempts")<CallLifecycle.number(t,"max_attempts")&&!java.time.LocalDateTime.parse(t.get("available_at").toString()).isAfter((java.time.LocalDateTime)params.get("now"))&&(Values.set("pending","failed").contains(t.get("state"))||"processing".equals(t.get("state"))&&t.get("locked_at")!=null&&!java.time.LocalDateTime.parse(t.get("locked_at").toString()).isAfter((java.time.LocalDateTime)params.get("cutoff")))).toList();if(tasks.isEmpty())continue;
                stages[4]=System.nanoTime();var ids=tasks.stream().map(t->t.get("id").toString()).toList();String lease=UUID.randomUUID().toString().replace("-","");params.put("ids",ids);params.put("lease",lease);
                if(db.write("claimTasks",params)!=tasks.size())throw new IllegalStateException("dial cohort claim changed");stages[5]=System.nanoTime();count[0]=tasks.size();cursor=id;
                for(var task:tasks){task.put("state","processing");task.put("attempts",CallLifecycle.number(task,"attempts")+1);task.put("lease_token",lease);}return tasks;
            }return List.of();
        });}finally{long end=System.nanoTime();if(config.flag("JAVA_DIAL_PROFILE",false)&&(count[0]>0||end-begin>100_000_000L))
            org.slf4j.LoggerFactory.getLogger(DialBatchWorker.class).info("dial_claim size={} enter_ms={} tenants_ms={} expire_ms={} candidate_ms={} lock_rows_ms={} update_ms={} commit_ms={}",count[0],ms(stages[0],begin),ms(stages[1],stages[0]),ms(stages[2],stages[1]),ms(stages[3],stages[2]),ms(stages[4],stages[3]),ms(stages[5],stages[4]),ms(end,stages[5]));}
    }
    private static double ms(long end,long begin){return end==0||begin==0?0:(end-begin)/1_000_000.0;}
    boolean process(Long tenant,ScheduledExecutorService heartbeat){
        if(!config.flag("JAVA_DISPATCH_ENABLED",false))return false;
        if(config.flag("CALLBACK_INBOX_ENABLED",false)&&!rate.healthyForAdmission()&&!drain.sendingClosed())return false;
        long started=System.nanoTime();var tasks=claim(tenant);if(tasks.isEmpty())return false;long claimed=System.nanoTime();long owner=CallLifecycle.tenant(tasks.get(0));
        var ids=tasks.stream().map(t->t.get("id").toString()).toList();String lease=tasks.get(0).get("lease_token").toString();var lost=new AtomicBoolean();Object proof=new Object();
        java.util.function.Supplier<Map<String,Object>> params=()->JsonSupport.map("tenantId",owner,"ids",ids,"lease",lease,"cutoff",cutoff(),"now",Store.now());
        var renewal=heartbeat.scheduleAtFixedRate(()->{try{if(db.write("renewTasks",params.get())!=tasks.size())lost.set(true);}catch(RuntimeException error){lost.set(true);}},Math.max(1,config.number("TASK_LEASE_SEC",30)/3),Math.max(1,config.number("TASK_LEASE_SEC",30)/3),TimeUnit.SECONDS);
        Runnable guard=()->{
            if(lost.get()||Thread.currentThread().isInterrupted())throw new IllegalStateException("dial cohort lease lost");
            if(TransactionSynchronizationManager.isActualTransactionActive()){
                if(TransactionSynchronizationManager.hasResource(proof))return;
                if(db.rows("ownedTasks",params.get()).size()!=tasks.size())throw new IllegalStateException("dial cohort lease lost");
                TransactionSynchronizationManager.bindResource(proof,Boolean.TRUE);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCompletion(int status){TransactionSynchronizationManager.unbindResourceIfPossible(proof);}});
            }else if(db.count("ownedCount",params.get())!=tasks.size())throw new IllegalStateException("dial cohort lease lost");
        };
        List<Map<String,Object>> entries=new ArrayList<>();RuntimeException failure=null;
        long[] stages=new long[2];try{
            entries=TaskWorker.guarded(owner,"batch:"+lease,guard,()->{var prepared=dial.prepareTasks(tasks,db);stages[0]=System.nanoTime();dial.sendTasks(prepared,db);stages[1]=System.nanoTime();return prepared;});
        }catch(RuntimeException error){failure=error;}
        finally{renewal.cancel(false);}
        if(lost.get())return true;
        final var completed=entries;final var thrown=failure;
        tx.run(()->{
            var owned=new HashSet<String>();for(var row:db.rows("ownedTasks",params.get()))owned.add(row.get("id").toString());
            return store.bufferWrites(()->{
                for(int i=0;i<tasks.size();i++){var task=tasks.get(i);if(!owned.contains(task.get("id").toString()))continue;
                    var entry=completed.size()==tasks.size()?completed.get(i):null;boolean deferred=entry!=null&&(Boolean.TRUE.equals(entry.get("deferred"))||Boolean.TRUE.equals(entry.get("pending_confirmation")));Object error=thrown!=null?thrown:entry==null?new IllegalStateException("incomplete cohort"):entry.get("error");
                    String state=deferred?"pending":error==null?"completed":CallLifecycle.number(task,"attempts")>=CallLifecycle.number(task,"max_attempts")?"dead":"failed";
                    var changes=JsonSupport.map("state",state,"locked_at",null,"lease_token",null,"last_error",deferred?"waiting for dial admission":error==null?"":error.getClass().getSimpleName());
                    if(!"completed".equals(state))changes.put("available_at",deferred?Store.now().plusNanos(ThreadLocalRandom.current().nextLong(50,201)*1_000_000L):Store.now().plusSeconds(Math.min(300,1L<<Math.min(8,CallLifecycle.number(task,"attempts")))));
                    if(deferred)changes.put("attempts",Math.max(0,CallLifecycle.number(task,"attempts")-1));
                    if(entry!=null){var payload=new LinkedHashMap<>((Map<String,Object>)entry.get("payload"));if("completed".equals(state))payload.remove("_dial_claimed");changes.put("payload_json",json.json(payload));}
                    store.writeLocked("taskoutbox",owner,task.get("id"),changes);
                }return null;
            });
        });if(config.flag("JAVA_DIAL_PROFILE",false))org.slf4j.LoggerFactory.getLogger(DialBatchWorker.class).info("dial_batch size={} admitted={} claim_ms={} prepare_ms={} send_ack_ms={} finish_ms={} error={}",tasks.size(),entries.stream().filter(e->e.get("call")!=null).count(),ms(claimed,started),ms(stages[0],claimed),ms(stages[1],stages[0]),ms(stages[1]==0?0:System.nanoTime(),stages[1]),thrown==null?"none":thrown.getClass().getSimpleName());
        // Yield on admission pressure so due mock statuses/other lanes can free
        // capacity. Cycling through deferred cohorts must not starve completion.
        return thrown!=null||entries.stream().anyMatch(e->!Boolean.TRUE.equals(e.get("deferred")));
    }
}
