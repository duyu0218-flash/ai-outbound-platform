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
    private static final ThreadLocal<Runnable> GUARD = new ThreadLocal<>();
    private static final ThreadLocal<Long> TENANT_HINT = new ThreadLocal<>();
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
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "java-outbox"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService heartbeat = Executors.newScheduledThreadPool(1, r -> { Thread t = new Thread(r, "java-outbox-lease"); t.setDaemon(true); return t; });
    // Logical AI owners share the existing four threads with the other task lanes.
    // Claim's same-stream predecessor predicate and lease fencing still serialize each call.
    private final AtomicBoolean[] lanes = java.util.stream.IntStream.range(0,7).mapToObj(i->new AtomicBoolean()).toArray(AtomicBoolean[]::new);
    private final AtomicBoolean receiving = new AtomicBoolean();
    private final ExecutorService receipts = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "java-callback-inbox"); t.setDaemon(true); return t; });
    private static final String[] LANE_TYPES = {"('dial_call','mock_status')", "('ai_turn')", "('after_playback','interrupt_playback','call_analysis')", "('business_callback','recording_ingest','recording_delete','send_sms')", "('ai_turn')", "('ai_turn')", "('ai_turn')"};
    private volatile boolean ready;
    private volatile long aiProgress;
    private final ArrayDeque<long[]> aiDelays = new ArrayDeque<>();
    private long cursor;
    private int callbackCursor=-1;
    public TaskWorker(Store store, Transactions tx, Config config, JsonSupport json, Outbox outbox, DialService dial, CallLifecycle lifecycle, CallAnalysis analysis, CallbackInbox inbox, AiRuntime ai, IntegrationTasks integrations) {
        this.store = store; this.tx = tx; this.config = config; this.json = json; this.outbox = outbox; this.dial = dial; this.lifecycle = lifecycle; this.analysis = analysis; this.inbox = inbox; this.ai = ai; this.integrations = integrations;
    }
    public static void permitted() { Runnable guard = GUARD.get(); if (guard != null) guard.run(); }
    @EventListener(ApplicationReadyEvent.class) public void ready() { ready = true; }
    @PreDestroy public void close() { ready = false; executor.shutdownNow(); receipts.shutdownNow(); heartbeat.shutdownNow(); }
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
        return tx.run(() -> {
            tx.platform(); int count = 0;
            List<Map<String, Object>> candidates = store.orderedAcross("callsession","scopedTaskWorker70",Store.queryParameters(-1,store.encode("callsession", "next_attempt_at", Store.now())),List.of("updated_at:ASC"),100);
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
    private Map<String, Object> claim(Long tenant, String types) {
        return tx.run(() -> {
            tx.identity("java-outbox-claim");
            Object now = store.encode("taskoutbox", "available_at", Store.now());
            Object cutoff = store.encode("taskoutbox", "locked_at", Store.now().minusSeconds(Math.max(2, config.number("TASK_LEASE_SEC", 30))));
            List<String> allowed=Arrays.asList(types.replace("(","").replace(")","").replace("'","").split(","));
            Map<String,Object> filters=JsonSupport.map("types",allowed,"now",now,"cutoff",cutoff);
            for(var candidate:store.orderedAcross("taskoutbox","abandonedLeases",filters,List.of(),10)) {
                var abandoned=store.one("taskoutbox","lockById",Values.map("tenantId",CallLifecycle.tenant(candidate),"id",store.encode("taskoutbox","id",candidate.get("id"))));
                if(abandoned!=null && "processing".equals(abandoned.get("state")) && expired(abandoned,cutoff)
                        && CallLifecycle.number(abandoned,"attempts")>=CallLifecycle.number(abandoned,"max_attempts"))
                    patchTask(abandoned,JsonSupport.map("state","dead","lease_token",null,"locked_at",null,"last_error","worker stopped during final task attempt"));
            }
            List<Map<String,Object>> candidates=new ArrayList<>();
            if(tenant!=null){var scoped=new LinkedHashMap<>(filters);scoped.put("tenantId",tenant);candidates.addAll(store.query("taskoutbox","claimCandidate",scoped));}
            else candidates.addAll(store.across("taskoutbox","claimCandidate",filters));
            long previousCursor=cursor;
            candidates.sort(Comparator.<Map<String,Object>>comparingInt(item->CallLifecycle.tenant(item)>previousCursor?0:1).thenComparingLong(CallLifecycle::tenant).thenComparing(item->item.get("available_at").toString()).thenComparing(item->item.get("id").toString()));
            Map<String,Object> row=candidates.isEmpty()?null:candidates.get(0);
            if (row == null) return null;
            row=store.one("taskoutbox","lockById",Values.map("tenantId",CallLifecycle.tenant(row),"id",store.encode("taskoutbox","id",row.get("id"))));
            if(row==null || CallLifecycle.number(row,"attempts")>=CallLifecycle.number(row,"max_attempts")
                    || !(Values.set("pending","failed").contains(row.get("state")) || "processing".equals(row.get("state"))&&expired(row,cutoff))
                    || LocalDateTime.parse(row.get("available_at").toString()).isAfter((LocalDateTime)now)) return null;
            cursor = CallLifecycle.tenant(row);
            return patchTask(row, JsonSupport.map("state", "processing", "attempts", CallLifecycle.number(row, "attempts") + 1,
                    "locked_at", Store.now(), "lease_token", UUID.randomUUID().toString().replace("-", "")));
        });
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
    boolean processLane(Long tenant,int lane) { return processOne(tenant,LANE_TYPES[lane]); }
    private boolean processOne(Long tenant, String types) {
        Map<String, Object> task = claim(tenant, types); if (task == null) return false;
        AtomicBoolean lost = new AtomicBoolean();
        int period = Math.max(1, config.number("TASK_LEASE_SEC", 30) / 3);
        ScheduledFuture<?> renewal = heartbeat.scheduleAtFixedRate(() -> {
            try {
                int changed = store.execute("taskoutbox","renewLease",Values.map("tenantId",CallLifecycle.tenant(task),"now",Store.now(),"id",store.encode("taskoutbox","id",task.get("id")),"lease",task.get("lease_token"),"cutoff",leaseCutoff()));
                if (changed != 1) lost.set(true);
            } catch (RuntimeException e) { lost.set(true); }
        }, period, period, TimeUnit.SECONDS);
        TENANT_HINT.set(CallLifecycle.tenant(task));
        GUARD.set(() -> { if (lost.get() || Thread.currentThread().isInterrupted() || !owns(task)) throw new IllegalStateException("task lease lost"); });
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
                if (call != null && "queued".equals(call.get("status")) && CallLifecycle.number(call, "attempts") == JsonSupport.integer(payload.get("attempt"), -1)) {
                    if (!dial.dispatch(call.get("id"))) {
                        Map<String, Object> latest = store.require("callsession", CallLifecycle.tenant(call), call.get("id"));
                        if ("queued".equals(latest.get("status"))) throw new Deferred();
                    }
                }
            } else throw new IllegalStateException("unsupported durable task type");
            permitted();
        } catch (Deferred e) { state = "pending"; error = "waiting for dial admission"; deferred = true; }
        catch (RuntimeException e) { state = CallLifecycle.number(task, "attempts") >= CallLifecycle.number(task, "max_attempts") ? "dead" : "failed"; error = e.getClass().getSimpleName(); }
        finally { renewal.cancel(false); GUARD.remove(); TENANT_HINT.remove(); }
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
        if (!ready || !config.flag("JAVA_WORKERS_ENABLED", false)) return;
        for (int lane=0;lane<LANE_TYPES.length;lane++) {
            final int index=lane;
            if (!lanes[index].compareAndSet(false,true)) continue;
            submitLane(index,System.nanoTime(),true);
        }
    }
    private void submitLane(int index,long queued,boolean dispatch) {
        executor.submit(() -> {
            boolean full=false;
            try {
                if(index==1||index>=4)aiProgress(queued);
                if(index==0&&dispatch)enqueueDue();
                int processed=0;
                while(processed<16&&!Thread.currentThread().isInterrupted()) {
                    boolean next=processOne(null,LANE_TYPES[index]);if(index==1||index>=4)aiProgress(0);
                    if(!next)break;processed++;
                }
                full=processed==16;
            } catch(RuntimeException e) { org.slf4j.LoggerFactory.getLogger(TaskWorker.class).warn("worker lane={} error_type={}",index,e.getClass().getSimpleName()); }
            finally {
                if(full&&ready&&!executor.isShutdown())submitLane(index,System.nanoTime(),false);
                else lanes[index].set(false);
            }
        });
    }

    @Scheduled(fixedDelayString="${JAVA_CALLBACK_POLL_MS:100}")
    public void receiveTick() {
        if(!ready||!config.flag("JAVA_WORKERS_ENABLED",false)||!config.flag("CALLBACK_INBOX_ENABLED",false)||!receiving.compareAndSet(false,true))return;
        receipts.submit(()->{
            try {
                inbox.maintenance();long deadline=System.nanoTime()+250000000L;
                do {
                    boolean progress=false;
                    List<Map<String,Object>> partitions=new ArrayList<>(store.projection("callbackinbox","rawTaskWorker192_1",Store.queryParameters(-1)));
                    int previous=callbackCursor;
                    partitions.sort(Comparator.comparingInt(p->Math.floorMod(((Number)p.get("partition_id")).intValue()-previous-1,64)));
                    for(Map<String,Object> partition:partitions) {
                        callbackCursor=((Number)partition.get("partition_id")).intValue();
                        progress|=inbox.consume(callbackCursor);
                        if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted())break;
                    }
                    if(!progress)break;
                }while(System.nanoTime()<deadline&&!Thread.currentThread().isInterrupted());
            }catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(TaskWorker.class).warn("callback consumer error_type={}",e.getClass().getSimpleName());}
            finally{receiving.set(false);}
        });
    }
}
