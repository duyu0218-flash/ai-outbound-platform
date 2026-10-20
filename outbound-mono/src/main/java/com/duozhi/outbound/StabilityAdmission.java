package com.duozhi.outbound;

import java.util.*;
import org.springframework.stereotype.Service;

/** Shared controller for Java worker progress and retained baseline signal contracts. */
@Service
public class StabilityAdmission {
    private final Config config;private final ExpiringValues redis;private final JsonSupport json;private final CallbackInbox inbox;
    public StabilityAdmission(Config config,ExpiringValues redis,JsonSupport json,CallbackInbox inbox){this.config=config;this.redis=redis;this.json=json;this.inbox=inbox;}
    private static double number(Object value){if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException("invalid observation");return ((Number)value).doubleValue();}
    public static Map<String,Object> advance(Map<String,Object> previous,double now,double queue,double age,boolean ready){
        Map<String,Object> state=previous==null?new LinkedHashMap<>():new LinkedHashMap<>(previous);
        try{if(!Values.set("NORMAL","THROTTLED","PAUSED","RECOVERING").contains(state.get("mode"))||number(state.get("fraction"))<0||number(state.get("fraction"))>1||number(state.get("changed_at"))>now)throw new IllegalArgumentException();}
        catch(RuntimeException e){state=JsonSupport.map("mode","PAUSED","fraction",0.0,"changed_at",now,"version",0);}
        state.put("updated_at",now);double fraction=number(state.get("fraction"));
        if(!ready||age>=.6)state.putAll(JsonSupport.map("mode","PAUSED","fraction",0.0,"healthy_since",null,"soft_since",null,"reason","dependency_or_callback_pressure","changed_at",now));
        else if(age>=.3||queue>=100){state.put("healthy_since",null);state.putIfAbsent("soft_since",now);double hold=age>=.3?2:10;if(now-number(state.get("soft_since"))>=hold&&now-number(state.get("changed_at"))>=10&&fraction>0){fraction*=.8;state.putAll(JsonSupport.map("mode",fraction>=.1?"THROTTLED":"PAUSED","fraction",fraction>=.1?fraction:0.0,"changed_at",now,"reason","queue_pressure"));}}
        else if(age<.15&&queue<50){state.put("soft_since",null);state.putIfAbsent("healthy_since",now);if(!"NORMAL".equals(state.get("mode"))&&now-number(state.get("healthy_since"))>=60&&now-number(state.get("changed_at"))>=60){fraction=fraction==0?.5:Math.min(1,fraction+.1);state.putAll(JsonSupport.map("mode",fraction>=1?"NORMAL":"RECOVERING","fraction",fraction,"reason","healthy","changed_at",now));}}
        else state.putAll(JsonSupport.map("healthy_since",null,"soft_since",null));
        state.put("version",JsonSupport.integer(state.get("version"),0)+1);return state;
    }
    private String prefix(){return config.get("STABILITY_REDIS_KEY","ai-outbound:stability");}
    public void publishGateway(String id,boolean ready,Object age){
        if(!config.flag("STABILITY_ADMISSION_ENABLED",false))return;
        try{redis.shared("HSET",prefix()+":signals","gateway:"+id,json.json(JsonSupport.map("at",System.currentTimeMillis()/1000.0,"ready",ready&&age!=null,"age_sec",age==null?1:age)));redis.shared("EXPIRE",prefix()+":signals","60");}
        catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(StabilityAdmission.class).warn("stability telemetry unavailable");}
    }
    @SuppressWarnings("unchecked") public double fraction(List<Map<String,Object>> nodes){
        if(!config.flag("STABILITY_ADMISSION_ENABLED",false))return 1;
        try{
            double now=System.currentTimeMillis()/1000.0;int ttl=config.number("STABILITY_SIGNAL_TTL_SEC",15);Object raw=redis.shared("HGETALL",prefix()+":signals");if(!(raw instanceof List)||((List<?>)raw).size()%2!=0)return 0;
            List<?> entries=(List<?>)raw;Map<String,Map<String,Object>> signals=new HashMap<>();List<String> stale=new ArrayList<>();
            for(int i=0;i<entries.size();i+=2){String name=entries.get(i).toString();Map<String,Object> value=json.object(entries.get(i+1).toString());double age=now-number(value.get("at"));if(age<0||age>ttl)stale.add(name);else signals.put(name,value);}
            if(!stale.isEmpty()){List<String> command=new ArrayList<>(Arrays.asList("HDEL",prefix()+":signals"));command.addAll(stale);redis.shared(command.toArray(new String[0]));}
            Map<String,Object> receipt=inbox.snapshot();boolean ready=Boolean.TRUE.equals(receipt.get("ready"));double age=number(receipt.get("oldest_age_sec")),queue=0;int ai=0,enabled=0;
            for(Map.Entry<String,Map<String,Object>> entry:signals.entrySet())if(entry.getKey().startsWith("ai:")){ai++;queue=Math.max(queue,Math.max(number(entry.getValue().get("queue_p95_ms")),number(entry.getValue().get("oldest_wait_ms"))));}
            for(Map<String,Object> node:nodes)if(Boolean.TRUE.equals(node.get("enabled"))){enabled++;Map<String,Object> signal=signals.getOrDefault("gateway:"+node.get("id"),Collections.emptyMap());ready&=JsonSupport.integer(node.get("cps"),0)>0&&Boolean.TRUE.equals(signal.get("ready"));age=Math.max(age,number(signal.getOrDefault("age_sec",1)));}
            ready&=enabled>0&&ai>=config.number("STABILITY_MIN_AI_WORKERS",4);Object old=redis.shared("GET",prefix()+":control");Map<String,Object> state=advance(old==null?null:json.object(old.toString()),now,queue,age,ready);
            redis.shared("SET",prefix()+":control",json.json(state),"EX","120");return number(state.get("fraction"));
        }catch(RuntimeException invalid){return 0;}
    }
    public Map<String,Object> status(){
        if(!config.flag("STABILITY_ADMISSION_ENABLED",false))return JsonSupport.map("mode","DISABLED","fraction",1.0,"updated_at",0);
        try{Object raw=redis.shared("GET",prefix()+":control");if(raw!=null){Map<String,Object> state=json.object(raw.toString());double age=System.currentTimeMillis()/1000.0-number(state.get("updated_at"));if(age>=0&&age<=config.number("STABILITY_SIGNAL_TTL_SEC",15))return state;}}catch(RuntimeException ignored){}
        return JsonSupport.map("mode","PAUSED","fraction",0.0,"updated_at",0);
    }
}
