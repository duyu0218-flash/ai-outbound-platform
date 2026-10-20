package com.duozhi.outbound;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/** A database timeout alone is never evidence that the PBX released a call. */
@Service
public class CallReconciliation {
    private final Store store;
    private final Transactions tx;
    private final Config config;
    private final TelephonyClient client;
    private final CallLifecycle lifecycle;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "call-reconciliation"); t.setDaemon(true); return t; });
    private volatile boolean ready;
    public CallReconciliation(Store store, Transactions tx, Config config, TelephonyClient client, CallLifecycle lifecycle) {
        this.store=store; this.tx=tx; this.config=config; this.client=client; this.lifecycle=lifecycle;
    }
    @EventListener(ApplicationReadyEvent.class) public void ready() { ready=true; }
    @PreDestroy public void close() { ready=false; executor.shutdownNow(); }
    public int reconcile(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("invalid reconciliation batch size");
        List<Map<String,Object>> stale=store.orderedAcross("callsession","scopedCallReconciliation30",Store.queryParameters(-1,store.encode("callsession", "updated_at", Store.now().minusSeconds(Math.max(10, config.number("DEFAULT_CALL_TIMEOUT_SEC", 120)))),limit),List.of("updated_at:ASC","id:ASC"),limit);
        int released=0;
        for (Map<String,Object> call:stale) {
            boolean confirmed;
            try { TaskWorker.permitted(); confirmed=Boolean.TRUE.equals(client.command(call,"hangup",JsonSupport.map("reason","provider_status_timeout")).get("ended")); }
            catch (RuntimeException failure) { TaskWorker.permitted(); confirmed=false; }
            final boolean ended=confirmed;
            boolean changed=tx.serialized(()->{
                tx.platform(); TaskWorker.permitted(); Map<String,Object> latest=tx.call(call.get("id"));
                if(latest==null || CallLifecycle.number(latest,"attempts")!=CallLifecycle.number(call,"attempts") || CallLifecycle.TERMINAL.contains(latest.get("status")))return false;
                if(!ended) {
                    // Do not overwrite newer activity with an older failed probe.
                    if(Objects.equals(latest.get("updated_at"),call.get("updated_at")))store.update("callsession",CallLifecycle.tenant(call),call.get("id"),JsonSupport.map("last_error","provider status unknown; capacity retained"));
                    return false;
                }
                lifecycle.status(JsonSupport.map("call_id",call.get("id"),"payload",JsonSupport.map("status","failed","attempt",call.get("attempts"),"event_id","reconciled-end:"+call.get("id")+":"+call.get("attempts"),"hangup_reason","provider status timeout")));
                return true;
            });
            if(changed)released++;
        }
        return released;
    }
    @Scheduled(fixedDelayString="${JAVA_RECONCILE_POLL_MS:5000}") public void tick() {
        if(!ready || !config.flag("JAVA_WORKERS_ENABLED",false) || !running.compareAndSet(false,true))return;
        executor.submit(()->{ try { reconcile(20); } catch(RuntimeException e) { org.slf4j.LoggerFactory.getLogger(CallReconciliation.class).warn("call reconciliation error_type={}",e.getClass().getSimpleName()); } finally { running.set(false); } });
    }
}
