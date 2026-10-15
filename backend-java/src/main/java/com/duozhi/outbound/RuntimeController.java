package com.duozhi.outbound;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestController
public class RuntimeController {
    private final Config config; private final Store store;
    public RuntimeController(Config config,Store store) { this.config=config;this.store=store; }
    @GetMapping({"/health","/healthz"}) public Map<String,String> health() { return Values.map("status","ok"); }
    @GetMapping("/readyz") public ResponseEntity<Map<String,Object>> ready() {
        store.jdbc().queryForObject("SELECT 1",Integer.class);
        return ResponseEntity.status(503).body(Values.map("status","partial","database","ok","migration_complete",false));
    }
    @GetMapping("/api/v1/runtime") public Map<String,Object> runtime() {
        return Values.map("app_name",config.get("APP_NAME","AI 外呼平台"),"demo_users_enabled",config.flag("DEMO_USERS_ENABLED",false)&&!config.production());
    }
}
