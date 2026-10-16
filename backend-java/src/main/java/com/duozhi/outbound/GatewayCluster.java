package com.duozhi.outbound;

import java.util.*;
import java.time.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

@Service
public class GatewayCluster {
    private final Store store; private final Transactions tx; private final Config config; private final JsonSupport json; private final TelephonyClient client; private final StabilityAdmission stability;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"gateway-probes");t.setDaemon(true);return t;});
    private final AtomicBoolean probing=new AtomicBoolean(); private volatile boolean ready;
    public GatewayCluster(Store store,Transactions tx,Config config,JsonSupport json,TelephonyClient client,StabilityAdmission stability){this.store=store;this.tx=tx;this.config=config;this.json=json;this.client=client;this.stability=stability;}
    @EventListener(ApplicationReadyEvent.class) public void ready(){ready=true;}
    @PreDestroy public void close(){ready=false;executor.shutdownNow();}
    @SuppressWarnings("unchecked") public List<Map<String,Object>> specs(){
        try {
            String file=config.get("VOICE_GATEWAY_NODES_FILE",""),raw=config.get("VOICE_GATEWAY_NODES_JSON","[]");
            if(!file.isEmpty()){raw=ConfigFiles.readUtf8(file,1048576);}
            Object parsed=json.object("{\"nodes\":"+raw+"}").get("nodes");if(!(parsed instanceof List))throw new IllegalArgumentException();
            List<Map<String,Object>> result=new ArrayList<>();Set<Object> ids=new HashSet<>(),endpoints=new HashSet<>();
            for(Object item:(List<?>)parsed){
                if(!(item instanceof Map))throw new IllegalArgumentException();Map<String,Object> n=new LinkedHashMap<>((Map<String,Object>)item);
                if(!Values.set("id","endpoint","capacity","cps","enabled","routes").containsAll(n.keySet()))throw new IllegalArgumentException();
                if(!(n.get("id") instanceof String)||!n.get("id").toString().matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException();
                String endpoint=Objects.toString(n.get("endpoint"),"").replaceAll("/+$","");URI uri=URI.create(endpoint);Settings.endpoint(endpoint,false);
                if(uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()||uri.getQuery()!=null)throw new IllegalArgumentException();n.put("endpoint",endpoint);
                n.putIfAbsent("capacity",200);n.putIfAbsent("cps",0);n.putIfAbsent("enabled",true);
                if(!(n.get("capacity") instanceof Integer)||!(n.get("cps") instanceof Integer)||!(n.get("enabled") instanceof Boolean))throw new IllegalArgumentException();
                int capacity=(Integer)n.get("capacity"),cps=(Integer)n.get("cps");if(capacity<1||capacity>500||cps<0||cps>1000||config.production()&&Boolean.TRUE.equals(n.get("enabled"))&&cps<1)throw new IllegalArgumentException();
                if(!(n.get("routes") instanceof List)||((List<?>)n.get("routes")).isEmpty())throw new IllegalArgumentException();for(Object route:(List<?>)n.get("routes"))if(!(route instanceof String)||!route.toString().matches("[1-9][0-9]*:[0-9]+"))throw new IllegalArgumentException();
                if(!ids.add(n.get("id"))||!endpoints.add(endpoint))throw new IllegalArgumentException();result.add(n);
            }
            if(!file.isEmpty()&&result.isEmpty())throw new IllegalArgumentException();return result;
        }catch(Exception invalid){throw new ApiError(503,"invalid operator gateway roster");}
    }
    /** Caller holds the shared platform admission transaction lock. */
    public Map<String,Object> choose(long tenant,Object line,double fraction){
        List<Map<String,Object>> specs=specs();if(specs.isEmpty()){if(config.flag("STABILITY_ADMISSION_ENABLED",false))throw new TaskWorker.Deferred();return null;}fraction=Math.min(fraction,stability.fraction(specs));if(fraction<=0||fraction>1||!Double.isFinite(fraction))throw new TaskWorker.Deferred();
        String scope=tenant+":"+Objects.toString(line,"0");Map<String,Object> selected=null,specSelected=null;double load=Double.MAX_VALUE;
        for(Map<String,Object> spec:specs){
            if(!Boolean.TRUE.equals(spec.get("enabled"))||!((List<?>)spec.get("routes")).contains(scope))continue;
            Map<String,Object> node=store.one("gatewaynode","WHERE id=?"+store.lock(),spec.get("id"));
            if(node==null||!Boolean.TRUE.equals(node.get("ready"))||!Objects.equals(node.get("endpoint"),spec.get("endpoint"))||LocalDateTime.parse(node.get("checked_at").toString()).isBefore(Store.now().minusSeconds(Math.max(1,config.number("VOICE_GATEWAY_HEALTH_TTL_SEC",20)))))continue;
            if(JsonSupport.integer(spec.get("cps"),0)>0&&node.get("next_dial_at")!=null&&LocalDateTime.parse(node.get("next_dial_at").toString()).isAfter(Store.now()))continue;
            int capacity=Math.min(JsonSupport.integer(spec.get("capacity"),0),JsonSupport.integer(node.get("capacity"),0));long active=store.count("callsession","WHERE gateway_node_id=? AND status IN "+CallLifecycle.ACTIVE_SQL,spec.get("id"));
            if(capacity<=0||active>=capacity)continue;double ratio=active/(double)capacity;
            if(selected==null||ratio<load||ratio==load&&node.get("id").toString().compareTo(selected.get("id").toString())<0){selected=node;specSelected=spec;load=ratio;}
        }
        if(selected==null)throw new TaskWorker.Deferred();int cps=JsonSupport.integer(specSelected.get("cps"),0);
        if(cps>0)store.updateInternal("gatewaynode",selected.get("id"),JsonSupport.map("next_dial_at",Store.now().plusNanos((long)Math.ceil(1e9/(cps*fraction)))));
        return selected;
    }
    void record(Map<String,Object> spec,LocalDateTime started,boolean healthy,int capacity){
        tx.run(()->{
            tx.platform();Map<String,Object> node=store.one("gatewaynode","WHERE id=?"+store.lock(),spec.get("id"));
            if(node!=null&&LocalDateTime.parse(node.get("checked_at").toString()).isAfter(started))return null;
            if(node!=null&&!Objects.equals(node.get("endpoint"),spec.get("endpoint"))&&store.count("callsession","WHERE gateway_node_id=? AND status IN "+CallLifecycle.ACTIVE_SQL,spec.get("id"))>0){store.updateInternal("gatewaynode",spec.get("id"),JsonSupport.map("ready",false));return null;}
            Map<String,Object> fields=JsonSupport.map("endpoint",spec.get("endpoint"),"ready",healthy&&Boolean.TRUE.equals(spec.get("enabled")),"capacity",Math.max(0,Math.min(capacity,JsonSupport.integer(spec.get("capacity"),0))),"checked_at",started);
            if(node==null){fields.put("id",spec.get("id"));store.insert("gatewaynode",fields);}else store.updateInternal("gatewaynode",node.get("id"),fields);return null;
        });
    }
    public void probe(){
        List<Map<String,Object>> specs=specs();if(specs.isEmpty())return;boolean dependency=true;
        if(config.flag("OUTBOUND_REQUIRE_AGENT_READY",false))try{dependency="ready".equals(client.get(config.get("AI_AGENT_URL","http://localhost:8001").replaceAll("/+$","")+"/readyz",Values.map("Authorization","Bearer "+config.get("AI_AGENT_SERVICE_TOKEN","")),2000).get("status"));}catch(RuntimeException e){dependency=false;}
        for(Map<String,Object> spec:specs){
            LocalDateTime started=Store.now();boolean healthy=false;int capacity=0;Object age=null;
            try{Map<String,Object> data=client.get(spec.get("endpoint")+"/readyz",Collections.emptyMap(),2000);healthy=dependency&&"ready".equals(data.get("status"))&&Objects.equals(spec.get("id"),data.get("node_id"));capacity=JsonSupport.integer(data.get("call_capacity"),0);age=data.get("callback_oldest_age_sec");}catch(RuntimeException ignored){}
            record(spec,started,healthy,capacity);stability.publishGateway(spec.get("id").toString(),healthy,age);
        }
    }
    @Scheduled(fixedDelayString="${JAVA_GATEWAY_POLL_MS:5000}") public void tick(){if(!ready||!config.flag("JAVA_WORKERS_ENABLED",false)||!probing.compareAndSet(false,true))return;executor.submit(()->{try{probe();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(GatewayCluster.class).warn("gateway probe error_type={}",e.getClass().getSimpleName());}finally{probing.set(false);}});}
}
