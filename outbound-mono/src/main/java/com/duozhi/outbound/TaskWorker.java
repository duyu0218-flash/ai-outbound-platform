package com.duozhi.outbound;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

@Service
public class TaskWorker {
    @org.springframework.beans.factory.annotation.Autowired private DialDrainControl dialControl;
    private static final ThreadLocal<Runnable> GUARD = new ThreadLocal<>();
    private static final ThreadLocal<Long> TENANT_HINT = new ThreadLocal<>();
    private static final ThreadLocal<String> OWNER_TOKEN=new ThreadLocal<>();
    static String ownerToken(){return OWNER_TOKEN.get();}
    static Long tenantHint(){return TENANT_HINT.get();}
    private static final String TYPES = "('dial_call','mock_status','call_analysis','ai_turn','after_playback','interrupt_playback','business_callback','recording_ingest','recording_delete','send_sms')";
    private final Store store;
    private final Transactions tx;
    private final Config config;
    private final JsonSupport json;
    private final Outbox outbox;
    private final DialService dial;
    private final CallLifecycle lifecycle;
    private final CallAnalysis analysis;
    private final CallbackInbox inbox;
    private final AiRuntime ai;
    private final IntegrationTasks integrations;
    private final DialBatchWorker dialBatch;
    private final ExecutorService dialExecutor;
    private final AtomicBoolean[] dialLanes;
    private final ExecutorService executor;
    private final ExecutorService aiExecutor, controlExecutor, analysisExecutor;
    private final int controlOwners;
    private final ScheduledExecutorService heartbeat = Executors.newScheduledThreadPool(1, r -> { Thread t = new Thread(r, "java-outbox-lease"); t.setDaemon(true); return t; });
    // Each workload has a separate bounded executor. One outstanding slice per
    // logical owner bounds queued submissions; playback owners partition by call.
    // AI stream predecessors and task leases retain their existing fences.
    // Control partitions serialize each call within this single-JVM compatibility path.
    private final AtomicBoolean[] lanes;
    private final AtomicBoolean[] receiving;
    private final ExecutorService receipts;
    private final String[] laneTypes;
    private final int analysisBatchSize;
    private volatile boolean ready;
    private volatile long aiProgress;
    private final ArrayDeque<long[]> aiDelays = new ArrayDeque<>();
    private final java.util.concurrent.atomic.AtomicLong cursor=new java.util.concurrent.atomic.AtomicLong();
    private final int[] callbackCursors;
    public TaskWorker(Store store, Transactions tx, Config config, JsonSupport json, Outbox outbox, DialService dial, CallLifecycle lifecycle, CallAnalysis analysis, CallbackInbox inbox, AiRuntime ai, IntegrationTasks integrations,DialBatchWorker dialBatch) {
        this.store = store; this.tx = tx; this.config = config; this.json = json; this.outbox = outbox; this.dial = dial; this.lifecycle = lifecycle; this.analysis = analysis; this.inbox = inbox; this.ai = ai; this.integrations = integrations;this.dialBatch=dialBatch;
        int taskWorkers=Math.max(1,Math.min(16,config.number("JAVA_TASK_WORKERS",4))),aiOwners=Math.max(1,Math.min(16,config.number("JAVA_AI_TASK_OWNERS",4)));
        int analysisOwners=Math.max(1,Math.min(16,config.number("JAVA_ANALYSIS_TASK_OWNERS",1)));
        controlOwners=Math.max(1,Math.min(16,config.number("JAVA_CONTROL_TASK_OWNERS",4)));
        analysisBatchSize=config.number("JAVA_ANALYSIS_BATCH_SIZE",1);
        if(analysisBatchSize<1||analysisBatchSize>16)throw new IllegalArgumentException("analysis batches require 1..16 rows");
        executor=pool(taskWorkers,"java-outbox");
        aiExecutor=pool(Math.max(1,Math.min(aiOwners,config.number("JAVA_AI_TASK_WORKERS",4))),"java-ai-turn");
        controlExecutor=pool(Math.max(1,Math.min(controlOwners,config.number("JAVA_CONTROL_TASK_WORKERS",4))),"java-call-control");
        analysisExecutor=pool(Math.max(1,Math.min(analysisOwners,config.number("JAVA_ANALYSIS_TASK_WORKERS",2))),"java-call-analysis");
        int callbackWorkers=Math.max(1,Math.min(4,config.number("JAVA_CALLBACK_WORKERS",1)));
        receiving=java.util.stream.IntStream.range(0,callbackWorkers).mapToObj(i->new AtomicBoolean()).toArray(AtomicBoolean[]::new);callbackCursors=new int[callbackWorkers];Arrays.fill(callbackCursors,-1);
        receipts=Executors.newFixedThreadPool(callbackWorkers,r->{Thread thread=new Thread(r,"java-callback-inbox");thread.setDaemon(true);return thread;});
        var types=new ArrayList<>(List.of("('dial_call','mock_status')","('ai_turn')","('after_playback','interrupt_playback')","('business_callback','recording_ingest','recording_delete','send_sms')"));
        for(int i=1;i<aiOwners;i++)types.add("('ai_turn')");
        for(int i=0;i<analysisOwners;i++)types.add("('call_analysis')");
        for(int i=1;i<controlOwners;i++)types.add("('after_playback','interrupt_playback')");
        laneTypes=types.toArray(String[]::new);lanes=java.util.stream.IntStream.range(0,laneTypes.length).mapToObj(i->new AtomicBoolean()).toArray(AtomicBoolean[]::new);
        int dialWorkers=Math.max(1,Math.min(4,config.number("JAVA_DIAL_WORKERS",1)));dialLanes=java.util.stream.IntStream.range(0,dialWorkers).mapToObj(i->new AtomicBoolean()).toArray(AtomicBoolean[]::new);
        dialExecutor=Executors.newFixedThreadPool(dialWorkers,r->{var thread=new Thread(r,"java-dial-batch");thread.setDaemon(true);return thread;});
    }
    private static ExecutorService pool(int threads,String name) {
        // At most 50 logical owners exist, each with one outstanding slice.
        return new ThreadPoolExecutor(threads,threads,0L,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64),r->{var thread=new Thread(r,name);thread.setDaemon(true);return thread;});
    }
    int analysisLane(){return Arrays.asList(laneTypes).indexOf("('call_analysis')");}
    private ExecutorService executorFor(int lane) {
        String type=laneTypes[lane];
        if(type.equals("('ai_turn')"))return aiExecutor;
        if(type.equals("('after_playback','interrupt_playback')"))return controlExecutor;
        if(type.equals("('call_analysis')"))return analysisExecutor;
        return executor;
    }
    public static void permitted() { Runnable guard = GUARD.get(); if (guard != null) guard.run(); }
    static <T>T guarded(long tenant,Runnable guard,java.util.function.Supplier<T> work){return guarded(tenant,OWNER_TOKEN.get(),guard,work);}
    static <T>T guarded(long tenant,String owner,Runnable guard,java.util.function.Supplier<T> work){var previous=GUARD.get();var oldTenant=TENANT_HINT.get();var oldOwner=OWNER_TOKEN.get();GUARD.set(guard);TENANT_HINT.set(tenant);if(owner==null)OWNER_TOKEN.remove();else OWNER_TOKEN.set(owner);try{permitted();return work.get();}finally{if(previous==null)GUARD.remove();else GUARD.set(previous);if(oldTenant==null)TENANT_HINT.remove();else TENANT_HINT.set(oldTenant);if(oldOwner==null)OWNER_TOKEN.remove();else OWNER_TOKEN.set(oldOwner);}}
    @EventListener(ApplicationReadyEvent.class) public void ready() { ready = true; }
    @PreDestroy public void close() { ready = false; executor.shutdownNow();aiExecutor.shutdownNow();controlExecutor.shutdownNow();analysisExecutor.shutdownNow();dialExecutor.shutdownNow(); receipts.shutdownNow(); heartbeat.shutdownNow(); }
    private void aiProgress(long queued) {
        long now=System.nanoTime();
        synchronized(aiDelays){
            aiProgress=now;
            if(queued!=0){aiDelays.addLast(new long[]{now,Math.max(0,now-queued)});if(aiDelays.size()>256)aiDelays.removeFirst();}
        }
    }
    Map<String,Object> aiSignal() {
        long now=System.nanoTime(),progress=aiProgress;
        if(!ready||!config.flag("JAVA_WORKERS_ENABLED",false)||progress==0||now-progress>config.number("STABILITY_SIGNAL_TTL_SEC",15)*1_000_000_000L)return null;
        double p95;
        synchronized(aiDelays){
            while(!aiDelays.isEmpty()&&now-aiDelays.peekFirst()[0]>30_000_000_000L)aiDelays.removeFirst();
            long[] delays=aiDelays.stream().mapToLong(sample->sample[1]).sorted().toArray();
            p95=delays.length==0?0:delays[(int)Math.ceil(delays.length*.95)-1]/1_000_000.0;
        }
        Map<String,Object> oldest=store.firstAcrossOrdered("taskoutbox","scopedTaskWorker60",Store.queryParameters(-1,store.encode("taskoutbox","available_at",Store.now())),List.of("available_at:ASC"));
        double age=oldest==null?0:Math.max(0,java.time.Duration.between(LocalDateTime.parse(oldest.get("available_at").toString()),Store.now()).toMillis());
        // Include time spent reading the queue; a slow database must not freshen old progress.
        return JsonSupport.map("at",System.currentTimeMillis()/1000.0-(System.nanoTime()-progress)/1_000_000_000.0,"queue_p95_ms",p95,"oldest_wait_ms",age);
    }

    public int enqueueDue() {
        if (!config.flag("JAVA_DISPATCH_ENABLED", false)) return 0;
        return tx.serialized(() -> {
            tx.platform(); if(!dialControl.acceptingInTransaction())return 0;
            int count = dialBatch.size()>1?dialBatch.enqueue():0;
            List<Map<String, Object>> candidates = store.orderedAcross("callsession",dialBatch.size()>1?"retryDueForBatch":"scopedTaskWorker70",Store.queryParameters(-1,store.encode("callsession", "next_attempt_at", Store.now())),List.of("updated_at:ASC"),100);
            for (Map<String, Object> call : candidates) {
                if (call.get("campaign_id") != null) {
                    Map<String, Object> campaign = store.require("campaign", CallLifecycle.tenant(call), call.get("campaign_id"));
                    if (!"running".equals(campaign.get("status")) || !Boolean.TRUE.equals(campaign.get("dispatch_enabled"))) continue;
                }
                if (!"queued".equals(call.get("status"))) {
                    if (!CallLifecycle.TERMINAL.contains(call.get("status"))) continue;
                    call=store.update("callsession",CallLifecycle.tenant(call),call.get("id"),JsonSupport.map("status","queued","next_attempt_at",null,"last_error",null));
                }
                outbox.enqueue(CallLifecycle.tenant(call), "dial_call", call.get("id"), "dial:" + call.get("id") + ":" + (CallLifecycle.number(call, "attempts") + 1), JsonSupport.map("call_id", call.get("id"), "attempt", call.get("attempts")));
                count++;
            }
            return count;
        });
    }
    private Map<String, Object> claim(Long tenant, String types) { return claim(tenant,types,0,1); }
    private Map<String, Object> claim(Long tenant, String types,int partition,int partitions) {
        boolean parallel=config.flag("JAVA_TASK_CLAIM_PARALLEL_ENABLED",false);
        java.util.function.Supplier<Map<String,Object>> work=() -> {
            Object now = store.encode("taskoutbox", "available_at", Store.now());
            Object cutoff = store.encode("taskoutbox", "locked_at", Store.now().minusSeconds(Math.max(2, config.number("TASK_LEASE_SEC", 30))));
            List<String> allowed=Arrays.asList(types.replace("(","").replace(")","").replace("'","").split(","));
            Map<String,Object> filters=JsonSupport.map("types",allowed,"now",now,"cutoff",cutoff,"parallelClaim",parallel,"controlPartition",partition,"controlPartitions",partitions,"recoverOnly",types.contains("'dial_call'")&&dialControl.sendingClosed());
            for(var candidate:store.orderedAcross("taskoutbox","abandonedLeases",filters,List.of(),10)) {
                var abandoned=store.one("taskoutbox",parallel?"lockByIdSkipLocked":"lockById",Values.map("tenantId",CallLifecycle.tenant(candidate),"id",store.encode("taskoutbox","id",candidate.get("id"))));
                if(abandoned!=null && "processing".equals(abandoned.get("state")) && expired(abandoned,cutoff)
                        && CallLifecycle.number(abandoned,"attempts")>=CallLifecycle.number(abandoned,"max_attempts"))
                    patchTask(abandoned,JsonSupport.map("state","dead","lease_token",null,"locked_at",null,"last_error","worker stopped during final task attempt"));
            }
            List<Map<String,Object>> candidates=new ArrayList<>();
            if(tenant!=null){var scoped=new LinkedHashMap<>(filters);scoped.put("tenantId",tenant);candidates.addAll(store.query("taskoutbox","claimCandidate",scoped));}
            else candidates.addAll(store.across("taskoutbox","claimCandidate",filters));
            long previousCursor=cursor.get();
            candidates.sort(Comparator.<Map<String,Object>>comparingInt(item->CallLifecycle.tenant(item)>previousCursor?0:1).thenComparingLong(CallLifecycle::tenant).thenComparing(item->item.get("available_at").toString()).thenComparing(item->item.get("id").toString()));
            for(var candidate:candidates){
            var row=store.one("taskoutbox",parallel?"lockByIdSkipLocked":"lockById",Values.map("tenantId",CallLifecycle.tenant(candidate),"id",store.encode("taskoutbox","id",candidate.get("id"))));
            if(row==null || CallLifecycle.number(row,"attempts")>=CallLifecycle.number(row,"max_attempts")
                    || !(Values.set("pending","failed").contains(row.get("state")) || "processing".equals(row.get("state"))&&expired(row,cutoff))
                    || LocalDateTime.parse(row.get("available_at").toString()).isAfter((LocalDateTime)now)){if(parallel)continue;return null;}
            cursor.set(CallLifecycle.tenant(row));
            return patchTask(row, JsonSupport.map("state", "processing", "attempts", CallLifecycle.number(row, "attempts") + 1,
                    "locked_at", Store.now(), "lease_token", UUID.randomUUID().toString().replace("-", "")));
            }
            return null;
        };
        return parallel?tx.run(work):tx.serializedIdentity("java-outbox-claim",work);
    }
    private boolean expired(Map<String,Object> row,Object cutoff) {
        return row.get("locked_at")!=null && !LocalDateTime.parse(row.get("locked_at").toString()).isAfter((LocalDateTime)cutoff);
    }
    private Map<String,Object> patchTask(Map<String,Object> row,Map<String,Object> changes) {
        var encoded=new LinkedHashMap<String,Object>();changes.forEach((key,value)->encoded.put(key,store.encode("taskoutbox",key,value)));
        encoded.put("updated_at",Store.now());
        store.execute("taskoutbox","patchFields",Values.map("tenantId",CallLifecycle.tenant(row),"id",store.encode("taskoutbox","id",row.get("id")),"changes",encoded));
        var result=new LinkedHashMap<>(row);result.putAll(changes);result.put("updated_at",encoded.get("updated_at").toString());return result;
    }
    private Object leaseCutoff() { return store.encode("taskoutbox", "locked_at", Store.now().minusSeconds(Math.max(2, config.number("TASK_LEASE_SEC", 30)))); }
    boolean owns(Map<String, Object> task) {
        return store.scalar("taskoutbox","scopedTaskWorker107",Store.scopedParameters(CallLifecycle.tenant(task),store.encode("taskoutbox", "id", task.get("id")),task.get("lease_token"),leaseCutoff())) == 1;
    }
    public static class Deferred extends RuntimeException { }
    public boolean processOne() { return processOne(null); }
    boolean processOne(Long tenant) { return processOne(tenant, TYPES); }
    boolean processLane(Long tenant,int lane) {
        String type=laneTypes[lane];
        var route=type.equals("('call_analysis')")?WorkloadDataSource.Lane.ANALYSIS:
                type.equals("('ai_turn')")||type.equals("('after_playback','interrupt_playback')")?WorkloadDataSource.Lane.REALTIME:WorkloadDataSource.Lane.DEFAULT;
        try(var routing=WorkloadDataSource.use(route)){return processRoutedLane(tenant,lane);}
    }
    private boolean processRoutedLane(Long tenant,int lane) {
        if(lane==0&&dialBatch.size()>1)return dialBatch.process(tenant,heartbeat)||processOne(tenant,"('mock_status')");
        if(analysisBatchSize>1&&laneTypes[lane].equals("('call_analysis')"))return processAnalysisBatch(tenant);
        if(laneTypes[lane].equals("('after_playback','interrupt_playback')")) {
            int partition=0;for(int i=0;i<lane;i++)if(laneTypes[i].equals(laneTypes[lane]))partition++;
            return processOne(tenant,laneTypes[lane],partition,controlOwners);
        }
        return processOne(tenant,laneTypes[lane]);
    }
    /** Analysis has no external I/O. Its task ownership, call results and task
     * completion can therefore share one bounded commit. Row locks arbitrate
     * ownership; a failed cohort rolls back before ordinary per-task retry. */
    private boolean processAnalysisBatch(Long only){
        if(!config.flag("JAVA_TASK_CLAIM_PARALLEL_ENABLED",false))return processOne(only,"('call_analysis')");
        var tenants=new ArrayList<>(store.query("tenant","allForMaintenance",Map.of()));long previous=cursor.get();
        tenants.sort(Comparator.<Map<String,Object>>comparingInt(t->CallLifecycle.number(t,"id")>previous?0:1).thenComparingLong(t->CallLifecycle.number(t,"id")));
        for(var tenant:tenants){
            long tid=((Number)tenant.get("id")).longValue();if(only!=null&&tid!=only)continue;
            var filters=JsonSupport.map("tenantId",tid,"types",List.of("call_analysis"),"now",Store.now(),"cutoff",leaseCutoff(),"parallelClaim",true);
            // Candidate reads happen before the transaction. Within it all
            // task/predecessor/call locks precede the first RR snapshot.
            var expired=store.query("taskoutbox","abandonedLeases",filters);
            if(!expired.isEmpty())tx.run(()->{for(var item:expired){var row=store.one("taskoutbox","lockByIdSkipLocked",JsonSupport.map("tenantId",tid,"id",store.encode("taskoutbox","id",item.get("id"))));if(row!=null&&"processing".equals(row.get("state"))&&expired(row,filters.get("cutoff"))&&CallLifecycle.number(row,"attempts")>=CallLifecycle.number(row,"max_attempts"))patchTask(row,JsonSupport.map("state","dead","lease_token",null,"locked_at",null,"last_error","worker stopped during final task attempt"));}return null;});
            var candidates=store.query("taskoutbox","claimCandidate",filters);if(candidates.isEmpty())continue;
            var predecessors=new HashMap<Object,List<Map<String,Object>>>();
            for(var task:candidates)predecessors.put(task.get("id"),store.query("taskoutbox","analysisPredecessors",JsonSupport.map("tenantId",tid,"aggregate",task.get("aggregate_id"),"created",store.encode("taskoutbox","created_at",task.get("created_at")),"id",store.encode("taskoutbox","id",task.get("id")))));
            try{
                boolean worked=tx.run(()->{
                    var tasks=new ArrayList<Map<String,Object>>();
                    for(var candidate:candidates.stream().sorted(Comparator.comparing(r->r.get("id").toString())).toList()){
                        if(tasks.size()>=analysisBatchSize)break;
                        var task=store.one("taskoutbox","lockByIdSkipLocked",JsonSupport.map("tenantId",tid,"id",store.encode("taskoutbox","id",candidate.get("id"))));
                        if(task==null||!"call_analysis".equals(task.get("task_type"))||CallLifecycle.number(task,"attempts")>=CallLifecycle.number(task,"max_attempts")||!(Values.set("pending","failed").contains(task.get("state"))||"processing".equals(task.get("state"))&&expired(task,filters.get("cutoff")))||LocalDateTime.parse(task.get("available_at").toString()).isAfter((LocalDateTime)filters.get("now")))continue;
                        boolean blocked=false;
                        for(var old:predecessors.get(task.get("id"))){var current=store.one("taskoutbox","lockByIdSkipLocked",JsonSupport.map("tenantId",tid,"id",store.encode("taskoutbox","id",old.get("id"))));if(current==null||Values.set("pending","failed","processing").contains(current.get("state"))){blocked=true;break;}}
                        if(blocked)continue;
                        tasks.add(task);
                    }
                    if(tasks.isEmpty())return false;
                    var payloads=new LinkedHashMap<Object,Map<String,Object>>();var ids=new TreeSet<String>();
                    for(var task:tasks){var payload=json.object(task.get("payload_json").toString());payloads.put(task.get("id"),payload);ids.add(Objects.toString(payload.get("call_id"),""));}
                    var calls=new HashMap<String,Map<String,Object>>();
                    for(String id:ids)calls.put(id,store.one("callsession","lockById",JsonSupport.map("tenantId",tid,"id",store.encode("callsession","id",id))));
                    Runnable owned=()->{if(Thread.currentThread().isInterrupted()||!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("atomic analysis owner lost");};
                    String owner="analysis-atomic:"+UUID.randomUUID();
                    for(var task:tasks){var payload=payloads.get(task.get("id"));guarded(tid,owner,owned,()->analysis.analyzeLocked(calls.get(payload.get("call_id").toString()),JsonSupport.integer(payload.get("attempt"),-1)));}
                    owned.run();store.bufferWrites(()->{for(var task:tasks)store.writeLocked("taskoutbox",tid,task.get("id"),JsonSupport.map("state","completed","attempts",CallLifecycle.number(task,"attempts")+1,"lease_token",null,"locked_at",null,"last_error",""));return null;});
                    return true;
                });
                if(worked){cursor.set(tid);return true;}
            }catch(RuntimeException failed){
                org.slf4j.LoggerFactory.getLogger(TaskWorker.class).warn("analysis cohort rolled back; retrying one task error_type={}",failed.getClass().getSimpleName());
                return processOne(tid,"('call_analysis')");
            }
        }
        return false;
    }
    private boolean processOne(Long tenant, String types) { return processOne(tenant,types,0,1); }
    private boolean processOne(Long tenant,String types,int partition,int partitions) {
        Map<String, Object> task = claim(tenant, types,partition,partitions); if (task == null) return false;
        AtomicBoolean lost = new AtomicBoolean();
        int period = Math.max(1, config.number("TASK_LEASE_SEC", 30) / 3);
        ScheduledFuture<?> renewal = heartbeat.scheduleAtFixedRate(() -> {
            try {
                int changed = store.execute("taskoutbox","renewLease",Values.map("tenantId",CallLifecycle.tenant(task),"now",Store.now(),"id",store.encode("taskoutbox","id",task.get("id")),"lease",task.get("lease_token"),"cutoff",leaseCutoff()));
                if (changed != 1) lost.set(true);
            } catch (RuntimeException e) { lost.set(true); }
        }, period, period, TimeUnit.SECONDS);
        TENANT_HINT.set(CallLifecycle.tenant(task));
        OWNER_TOKEN.set("task:"+task.get("id")+":"+task.get("lease_token"));
        Object proofKey=new Object();
        GUARD.set(() -> {
            if(lost.get()||Thread.currentThread().isInterrupted())throw new IllegalStateException("task lease lost");
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()){
                if(org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(proofKey))return;
                // Hold the lease row until commit. A concurrent reclaim cannot
                // invalidate this proof within the transaction. Never cache it
                // across transactions or external commands.
                if(store.one("taskoutbox","scopedTaskWorker151",Store.scopedParameters(CallLifecycle.tenant(task),store.encode("taskoutbox","id",task.get("id")),task.get("lease_token"),leaseCutoff()))==null)throw new IllegalStateException("task lease lost");
                org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(proofKey,Boolean.TRUE);
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void afterCompletion(int status){org.springframework.transaction.support.TransactionSynchronizationManager.unbindResourceIfPossible(proofKey);}});
            }else if(!owns(task))throw new IllegalStateException("task lease lost");
        });
        String state = "completed", error = ""; boolean deferred = false;
        try {
            permitted();
            Map<String, Object> payload = json.object(task.get("payload_json").toString());
            String type = task.get("task_type").toString();
            if (Values.set("business_callback", "recording_ingest", "recording_delete", "send_sms").contains(type)) integrations.execute(type, task, payload);
            else if ("ai_turn".equals(type)) ai.turn(payload);
            else if ("after_playback".equals(type)) ai.afterPlayback(payload);
            else if ("interrupt_playback".equals(type)) ai.interrupt(payload);
            else if ("mock_status".equals(type)) lifecycle.status(payload);
            else if ("call_analysis".equals(type)) analysis.analyze(payload.get("call_id"), JsonSupport.integer(payload.get("attempt"), -1));
            else if ("dial_call".equals(type)) {
                Map<String, Object> call = store.locate("callsession",store.encode("callsession", "id", payload.get("call_id")),false);
                if(!payload.containsKey("_dial_claimed")&&call!=null){var recovered=dial.frozenTask(call,payload);if(recovered!=null)payload.put("_dial_claimed",recovered);}
                if(payload.get("_dial_claimed") instanceof Map<?,?> frozen&&call!=null&&frozen.get("call") instanceof Map<?,?> old){
                    boolean queryOnly=dialControl.sendingClosed()||CallLifecycle.TERMINAL.contains(call.get("status"))||CallLifecycle.number(call,"attempts")!=CallLifecycle.number((Map<String,Object>)old,"attempts");
                    boolean complete=queryOnly?dial.confirmClaimed((Map<String,Object>)old,(Map<String,Object>)frozen.get("metadata")):dial.sendClaimed((Map<String,Object>)old,(Map<String,Object>)frozen.get("metadata"));
                    if(!complete){payload.remove("_dial_claimed");store.update("taskoutbox",CallLifecycle.tenant(task),task.get("id"),JsonSupport.map("payload_json",json.json(payload)));throw new Deferred();}
                }else if (call != null && "queued".equals(call.get("status")) && CallLifecycle.number(call, "attempts") == JsonSupport.integer(payload.get("attempt"), -1)) {
                    if (!dial.dispatch(call.get("id"))) {
                        Map<String, Object> latest = store.require("callsession", CallLifecycle.tenant(call), call.get("id"));
                        if ("queued".equals(latest.get("status"))) throw new Deferred();
                    }
                }
            } else throw new IllegalStateException("unsupported durable task type");
            permitted();
        } catch (Deferred e) { state = "pending"; error = "waiting for dial admission"; deferred = true; }
        catch (RuntimeException e) { state = CallLifecycle.number(task, "attempts") >= CallLifecycle.number(task, "max_attempts") ? "dead" : "failed"; error = e.getClass().getSimpleName(); }
        finally { renewal.cancel(false); GUARD.remove(); TENANT_HINT.remove();OWNER_TOKEN.remove(); }
        final String finalState = state, finalError = error; final boolean isDeferred = deferred;
        if (!lost.get()) tx.run(() -> {
            Map<String, Object> owned = store.one("taskoutbox","scopedTaskWorker151",Store.scopedParameters(CallLifecycle.tenant(task),store.encode("taskoutbox", "id", task.get("id")),task.get("lease_token"),leaseCutoff()));
            if (owned != null) {
                Map<String, Object> change = JsonSupport.map("state", finalState, "last_error", finalError, "lease_token", null, "locked_at", null);
                if (!"completed".equals(finalState)) change.put("available_at", Store.now().plusSeconds(isDeferred ? 1 : Math.min(300, 1L << Math.min(8, CallLifecycle.number(task, "attempts")))));
                if (isDeferred) change.put("attempts", Math.max(0, CallLifecycle.number(task, "attempts") - 1));
                patchTask(owned,change);
                if ("dead".equals(finalState) && Values.set("recording_ingest", "recording_delete").contains(task.get("task_type"))) {
                    Object assetId=json.object(task.get("payload_json").toString()).get("recording_asset_id");
                    Map<String,Object> asset=store.one("recordingasset","sourceTaskWorker159_7",Store.queryParameters(0,CallLifecycle.tenant(task),assetId));
                    if(asset!=null&&asset.get("deleted_at")==null)store.update("recordingasset",CallLifecycle.tenant(task),assetId,JsonSupport.map("state","recording_delete".equals(task.get("task_type"))?"deletion_failed":"ingest_failed"));
                }
            }
            return null;
        });
        return true;
    }
    @Scheduled(fixedDelayString="${JAVA_WORKER_POLL_MS:250}")
    public void tick() {
        if (!ready || !config.flag("JAVA_WORKERS_ENABLED", false) || dialControl.frozen()) return;
        for (int lane=0;lane<laneTypes.length;lane++) {
            final int index=lane;
            if(index==0&&dialBatch.size()>1){for(int i=0;i<dialLanes.length;i++)if(dialLanes[i].compareAndSet(false,true))submitDialLane(i,true);continue;}
            if (!lanes[index].compareAndSet(false,true)) continue;
            submitLane(index,System.nanoTime(),true);
        }
    }
    private void submitDialLane(int lane,boolean dispatch){
        dialExecutor.submit(()->{boolean full=false;try{
            // Refill before this owner consumes its previous 1,000-row refill.
            // An indefinitely self-resubmitting lane must not drain the outbox
            // and wait for the scheduler tick while queued numbers still exist.
            if(dispatch)enqueueDue();int processed=0,refillEvery=Math.max(1,800/dialBatch.size());while(processed<8&&!Thread.currentThread().isInterrupted()){if(processed>0&&processed%refillEvery==0)enqueueDue();if(!processLane(null,0))break;processed++;}full=processed==8;
        }catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(TaskWorker.class).warn("batch dial lane={} error_type={}",lane,error.getClass().getSimpleName());}
        finally{if(full&&ready&&!dialExecutor.isShutdown())submitDialLane(lane,true);else dialLanes[lane].set(false);}});
    }
    private void submitLane(int index,long queued,boolean dispatch) {
        executorFor(index).submit(() -> {
            boolean full=false;
            try {
                if(laneTypes[index].equals("('ai_turn')"))aiProgress(queued);
                if(index==0&&dispatch)enqueueDue();
                int processed=0;
                while(processed<16&&!Thread.currentThread().isInterrupted()) {
                    boolean next=processLane(null,index);if(laneTypes[index].equals("('ai_turn')"))aiProgress(0);
                    if(!next)break;processed++;
                }
                full=processed==16;
            } catch(RuntimeException e) { org.slf4j.LoggerFactory.getLogger(TaskWorker.class).warn("worker lane={} error_type={}",index,e.getClass().getSimpleName()); }
            finally {
                if(full&&ready&&!executorFor(index).isShutdown())submitLane(index,System.nanoTime(),false);
                else lanes[index].set(false);
            }
        });
    }

    @Scheduled(fixedDelayString="${JAVA_CALLBACK_POLL_MS:100}")
    public void receiveTick() {
        if(!ready||!config.flag("JAVA_WORKERS_ENABLED",false)||!config.flag("CALLBACK_INBOX_ENABLED",false)||dialControl.frozen())return;
        for(int owner=0;owner<receiving.length;owner++)if(receiving[owner].compareAndSet(false,true))receiveOwned(owner);
    }
    private void receiveOwned(int owner){
        receipts.submit(()->{
            try {
                inbox.maintenance();long deadline=System.nanoTime()+250000000L;
                do {
                    boolean progress=false;
                    int batchSize=config.number("JAVA_CALLBACK_BATCH_SIZE",1);
                    if(batchSize>1&&inbox.consumeBatch(batchSize,owner,receiving.length)>0)continue;
                    List<Map<String,Object>> partitions=new ArrayList<>(store.projection("callbackinbox","rawTaskWorker192_1",Store.queryParameters(-1)));
                    partitions.removeIf(p->((Number)p.get("partition_id")).intValue()%receiving.length!=owner);
                    int previous=callbackCursors[owner];
                    partitions.sort(Comparator.comparingInt(p->Math.floorMod(((Number)p.get("partition_id")).intValue()-previous-1,64)));
                    for(Map<String,Object> partition:partitions) {
                        callbackCursors[owner]=((Number)partition.get("partition_id")).intValue();
                        progress|=inbox.consume(callbackCursors[owner]);
                        if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted())break;
                    }
                    if(!progress)break;
                }while(System.nanoTime()<deadline&&!Thread.currentThread().isInterrupted());
            }catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(TaskWorker.class).warn("callback consumer error_type={}",e.getClass().getSimpleName());}
            finally{receiving[owner].set(false);}
        });
    }
}
