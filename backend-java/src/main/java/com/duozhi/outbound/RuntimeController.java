package com.duozhi.outbound;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestController
public class RuntimeController {
    private final Config config; private final Store store; private final ExpiringValues redis; private final DependencyHealth dependencies; private final CallbackInbox inbox;
    private volatile boolean started;
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void started(){started=true;}
    public RuntimeController(Config config,Store store,ExpiringValues redis,DependencyHealth dependencies,CallbackInbox inbox) { this.config=config;this.store=store;this.redis=redis;this.dependencies=dependencies;this.inbox=inbox; }
    @GetMapping("/healthz") public Map<String,String> alive() { return Values.map("status","ok"); }
    @GetMapping("/health") public ResponseEntity<Map<String,Object>> health() {
        String db="ok",cache="ok";
        try{store.jdbc().queryForObject("SELECT 1",Integer.class);}catch(RuntimeException e){db="unavailable";}
        if(!config.get("REDIS_URL","").isEmpty())try{if(!"PONG".equals(redis.shared("PING")))cache="unavailable";}catch(RuntimeException e){cache="unavailable";}
        return ResponseEntity.status("ok".equals(db)&&"ok".equals(cache)?200:503).body(Values.map("status","ok","service",config.get("APP_NAME","AI-Outbound-Platform"),"env",config.get("ENV","dev"),"version","1.0.0","checks",Values.map("db",db,"redis",cache)));
    }
    @GetMapping("/readyz") public ResponseEntity<Map<String,Object>> ready() {
        if(!started)return ResponseEntity.status(503).body(Values.map("status","ready","checks",Values.map("startup","initializing")));
        Map<String,Object> services=dependencies.services(config.number("DEFAULT_TENANT_ID",1));
        Map<String,Object> checks=JsonSupport.map("db",services.get("database"),"redis",services.get("redis"),"ai_agent",services.get("ai_agent"),"telephony","mock".equals(services.get("telephony"))?"ok":services.get("telephony"));
        if(config.flag("CALLBACK_INBOX_ENABLED",false))try{checks.put("callback_inbox",Boolean.TRUE.equals(inbox.snapshot().get("ready"))?"ok":"consumer unavailable or backlog exceeds SLO");}catch(RuntimeException e){checks.put("callback_inbox","unavailable");}
        // Release acceptance remains an explicit deployment gate, separate from liveness.
        checks.put("java_release",config.flag("JAVA_RELEASE_ACCEPTED",false)?"ok":"acceptance_required");
        boolean ready=checks.values().stream().allMatch("ok"::equals);
        return ResponseEntity.status(ready?200:503).body(Values.map("status","ready","checks",checks));
    }
    @GetMapping("/api/v1/runtime") public Map<String,Object> runtime() {
        return Values.map("app_name",config.get("APP_NAME","AI-Outbound-Platform"),"demo_users_enabled",config.flag("DEMO_USERS_ENABLED",false)&&!config.production());
    }
}
