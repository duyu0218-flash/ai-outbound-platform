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
    private final AtomicBoolean[] lanes = {new AtomicBoolean(),new AtomicBoolean(),new AtomicBoolean(),new AtomicBoolean()};
    private final AtomicBoolean receiving = new AtomicBoolean();
    private final ExecutorService receipts = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "java-callback-inbox"); t.setDaemon(true); return t; });
    private static final String[] LANE_TYPES = {"('dial_call','mock_status')", "('ai_turn')", "('after_playback','interrupt_playback','call_analysis')", "('business_callback','recording_ingest','recording_delete','send_sms')"};
    private volatile boolean ready;
    private long cursor;
    public TaskWorker(Store store, Transactions tx, Config config, JsonSupport json, Outbox outbox, DialService dial, CallLifecycle lifecycle, CallAnalysis analysis, CallbackInbox inbox, AiRuntime ai, IntegrationTasks integrations) {
        this.store = store; this.tx = tx; this.config = config; this.json = json; this.outbox = outbox; this.dial = dial; this.lifecycle = lifecycle; this.analysis = analysis; this.inbox = inbox; this.ai = ai; this.integrations = integrations;
    }
    public static void permitted() { Runnable guard = GUARD.get(); if (guard != null) guard.run(); }
    @EventListener(ApplicationReadyEvent.class) public void ready() { ready = true; }
    @PreDestroy public void close() { ready = false; executor.shutdownNow(); receipts.shutdownNow(); heartbeat.shutdownNow(); }

    public int enqueueDue() {
        if (!config.flag("JAVA_DISPATCH_ENABLED", false)) return 0;
        return tx.run(() -> {
            tx.platform(); int count = 0;
            List<Map<String, Object>> candidates = store.list("callsession", "WHERE attempts<max_attempts AND (status='QUEUED' OR (next_attempt_at IS NOT NULL AND next_attempt_at<=?)) ORDER BY updated_at LIMIT 100", store.encode("callsession", "next_attempt_at", Store.now()));
            for (Map<String, Object> call : candidates) {
                if (call.get("campaign_id") != null) {
                    Map<String, Object> campaign = store.require("campaign", CallLifecycle.tenant(call), call.get("campaign_id"));
                    if (!"running".equals(campaign.get("status")) || !Boolean.TRUE.equals(campaign.get("dispatch_enabled"))) continue;
                }
                if (!"queued".equals(call.get("status"))) {
                    if (!CallLifecycle.TERMINAL.contains(call.get("status"))) continue;
                    call = store.update("callsession", CallLifecycle.tenant(call), call.get("id"), JsonSupport.map("status", "queued", "next_attempt_at", null, "last_error", null));
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
            for (Map<String, Object> abandoned : store.list("taskoutbox", "WHERE task_type IN " + types + " AND state='PROCESSING' AND locked_at<=? AND attempts>=max_attempts LIMIT 10" + store.lock(), cutoff))
                store.update("taskoutbox", CallLifecycle.tenant(abandoned), abandoned.get("id"), JsonSupport.map("state", "dead", "lease_token", null, "locked_at", null, "last_error", "worker stopped during final task attempt"));
            String where = "WHERE task_type IN " + types + " AND available_at<=? AND attempts<max_attempts AND (state IN ('PENDING','FAILED') OR (state='PROCESSING' AND locked_at<=?))"
                    + " AND NOT EXISTS (SELECT 1 FROM taskoutbox older WHERE taskoutbox.task_type<>'after_playback' AND older.tenant_id=taskoutbox.tenant_id AND older.aggregate_id=taskoutbox.aggregate_id AND (older.task_type=taskoutbox.task_type OR (older.task_type IN ('recording_ingest','recording_delete') AND taskoutbox.task_type IN ('recording_ingest','recording_delete'))) AND older.state IN ('PENDING','FAILED','PROCESSING') AND (older.created_at<taskoutbox.created_at OR (older.created_at=taskoutbox.created_at AND older.id<taskoutbox.id)))";
            List<Object> parameters = new ArrayList<>(Arrays.asList(now, cutoff));
            if (tenant != null) { where += " AND tenant_id=?"; parameters.add(tenant); }
            parameters.add(cursor);
            Map<String, Object> row = store.one("taskoutbox", where + " ORDER BY CASE WHEN tenant_id>? THEN 0 ELSE 1 END,tenant_id,available_at,id LIMIT 1" + store.lock(), parameters.toArray());
            if (row == null) return null;
            cursor = CallLifecycle.tenant(row);
            return store.update("taskoutbox", cursor, row.get("id"), JsonSupport.map("state", "processing", "attempts", CallLifecycle.number(row, "attempts") + 1,
                    "locked_at", Store.now(), "lease_token", UUID.randomUUID().toString().replace("-", "")));
        });
    }
    private Object leaseCutoff() { return store.encode("taskoutbox", "locked_at", Store.now().minusSeconds(Math.max(2, config.number("TASK_LEASE_SEC", 30)))); }
    boolean owns(Map<String, Object> task) {
        return store.count("taskoutbox", "WHERE id=? AND state='PROCESSING' AND lease_token=? AND locked_at>?", store.encode("taskoutbox", "id", task.get("id")), task.get("lease_token"), leaseCutoff()) == 1;
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
                int changed = store.jdbc().update("UPDATE taskoutbox SET locked_at=?,updated_at=? WHERE id=? AND state='PROCESSING' AND lease_token=? AND locked_at>?",
                        store.encode("taskoutbox", "locked_at", Store.now()), store.encode("taskoutbox", "updated_at", Store.now()), store.encode("taskoutbox", "id", task.get("id")), task.get("lease_token"), leaseCutoff());
                if (changed != 1) lost.set(true);
            } catch (RuntimeException e) { lost.set(true); }
        }, period, period, TimeUnit.SECONDS);
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
                Map<String, Object> call = store.one("callsession", "WHERE id=?", store.encode("callsession", "id", payload.get("call_id")));
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
        finally { renewal.cancel(false); GUARD.remove(); }
        final String finalState = state, finalError = error; final boolean isDeferred = deferred;
        if (!lost.get()) tx.run(() -> {
            Map<String, Object> owned = store.one("taskoutbox", "WHERE id=? AND state='PROCESSING' AND lease_token=? AND locked_at>?" + store.lock(), store.encode("taskoutbox", "id", task.get("id")), task.get("lease_token"), leaseCutoff());
            if (owned != null) {
                Map<String, Object> change = JsonSupport.map("state", finalState, "last_error", finalError, "lease_token", null, "locked_at", null);
                if (!"completed".equals(finalState)) change.put("available_at", Store.now().plusSeconds(isDeferred ? 1 : Math.min(300, 1L << Math.min(8, CallLifecycle.number(task, "attempts")))));
                if (isDeferred) change.put("attempts", Math.max(0, CallLifecycle.number(task, "attempts") - 1));
                store.update("taskoutbox", CallLifecycle.tenant(task), task.get("id"), change);
                if ("dead".equals(finalState) && Values.set("recording_ingest", "recording_delete").contains(task.get("task_type"))) {
                    Object assetId=json.object(task.get("payload_json").toString()).get("recording_asset_id");
                    Map<String,Object> asset=store.one("recordingasset","WHERE tenant_id=? AND id=?",CallLifecycle.tenant(task),assetId);
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
            executor.submit(() -> {
                try {
                    if(index==0)enqueueDue();
                    for(int i=0;i<16&&!Thread.currentThread().isInterrupted();i++)if(!processOne(null,LANE_TYPES[index]))break;
                } catch(RuntimeException e) { org.slf4j.LoggerFactory.getLogger(TaskWorker.class).warn("worker lane={} error_type={}",index,e.getClass().getSimpleName()); }
                finally { lanes[index].set(false); }
            });
        }
    }
    @Scheduled(fixedDelayString="${JAVA_CALLBACK_POLL_MS:100}")
    public void receiveTick() {
        if(!ready||!config.flag("JAVA_WORKERS_ENABLED",false)||!config.flag("CALLBACK_INBOX_ENABLED",false)||!receiving.compareAndSet(false,true))return;
        receipts.submit(()->{
            try {
                inbox.maintenance();long deadline=System.nanoTime()+250000000L;
                do {
                    boolean progress=false;
                    for(Map<String,Object> partition:store.jdbc().queryForList("SELECT DISTINCT partition_id FROM callbackinbox WHERE state='pending' LIMIT 64")) {
                        progress|=inbox.consume(((Number)partition.get("partition_id")).intValue());
                        if(System.nanoTime()>deadline||Thread.currentThread().isInterrupted())break;
                    }
                    if(!progress)break;
                }while(System.nanoTime()<deadline&&!Thread.currentThread().isInterrupted());
            }catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(TaskWorker.class).warn("callback consumer error_type={}",e.getClass().getSimpleName());}
            finally{receiving.set(false);}
        });
    }
}
