package com.duozhi.outbound;

import javax.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class SystemOverview {
    private final Store store;
    private final Settings settings;
    private final Config config;
    public SystemOverview(Store store, Settings settings, Config config) { this.store = store; this.settings = settings; this.config = config; }

    private Map<String, Object> counts(String table, String column, long tenant) {
        Map<String, Object> result = new LinkedHashMap<>();
        // Only constants from this controller may select SQL identifiers.
        for (Map<String, Object> row : store.jdbc().queryForList("SELECT " + column + ",COUNT(*) AS n FROM " + table + " WHERE tenant_id=? GROUP BY " + column, tenant))
            result.put(row.get(column).toString().toLowerCase(Locale.ROOT), row.get("n"));
        return result;
    }

    @GetMapping("/api/v1/admin/system-overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        long tenant = Identity.from(request).tenant();
        int configured = config.number("MAX_CONCURRENT_CALLS", 20);
        try { configured = Integer.parseInt(settings.get(tenant, "capacity").get("max_concurrent_calls").toString()); }
        catch (NumberFormatException ignored) { }
        configured = Math.min(Math.max(1, config.number("OUTBOUND_PLATFORM_MAX_CONCURRENT", 20)), Math.max(1, configured));
        List<Map<String, Object>> lines = store.list("telephonyline", "WHERE tenant_id=? AND enabled=?", tenant, true);
        Long lineCapacity = lines.isEmpty() ? null : lines.stream().mapToLong(line -> Math.max(1L, ((Number)line.get("max_concurrency")).longValue())).sum();
        long effective = lineCapacity == null ? configured : Math.min(configured, lineCapacity);
        long active = store.count("callsession", "WHERE tenant_id=? AND status IN ('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN')", tenant);
        Map<String, Object> capacity = new LinkedHashMap<>();
        capacity.put("configured_max_concurrent_calls", configured); capacity.put("line_max_concurrency", lineCapacity);
        capacity.put("effective_max_concurrent_calls", effective); capacity.put("active_calls", active); capacity.put("available_slots", Math.max(0, effective - active));
        capacity.put("limiting_source", lineCapacity == null || lineCapacity > configured ? "tenant_capacity" : lineCapacity == configured ? "tenant_and_line" : "telephony_line");
        capacity.put("telephony_provider", config.get("TELEPHONY_PROVIDER", "mock").trim().toLowerCase(Locale.ROOT));
        capacity.put("environment_default", Math.max(1, config.number("MAX_CONCURRENT_CALLS", 20)));
        Map<String, Object> operations = new LinkedHashMap<>();
        operations.put("durable_tasks", counts("taskoutbox", "state", tenant));
        Double latency = store.jdbc().queryForObject("SELECT AVG(duration_ms) FROM callmetric WHERE tenant_id=? AND stage='ai.turn' AND success=?", Double.class, tenant, true);
        operations.put("average_ai_turn_ms", latency == null ? null : Math.round(latency * 10) / 10.0);
        operations.put("stale_processing_tasks", store.count("taskoutbox", "WHERE tenant_id=? AND state='PROCESSING' AND locked_at<=?", tenant, store.encode("taskoutbox", "locked_at", Store.now().minusMinutes(5))));
        Map<String, Object> oldest = store.one("taskoutbox", "WHERE tenant_id=? AND state IN ('PENDING','FAILED','PROCESSING') ORDER BY created_at ASC LIMIT 1", tenant);
        operations.put("oldest_open_task_age_sec", oldest == null ? 0 : Math.max(0, Duration.between(LocalDateTime.parse(oldest.get("created_at").toString()), Store.now()).getSeconds()));
        operations.put("recording_deletion_failures", store.count("recordingasset", "WHERE tenant_id=? AND state='deletion_failed'", tenant));
        // Database queries above are live. External service probes are not yet ported.
        return Values.map("services", Values.map("database", "ok", "redis", "unverified", "ai_agent", "unverified", "telephony", "unverified"),
                "resources", Values.map("users", store.count("user", "WHERE tenant_id=?", tenant), "enabled_users", store.count("user", "WHERE tenant_id=? AND enabled=?", tenant, true),
                        "lines", store.count("telephonyline", "WHERE tenant_id=?", tenant), "enabled_lines", lines.size()),
                "call_statuses", counts("callsession", "status", tenant), "capacity", capacity, "operations", operations, "generated_at", Store.now());
    }
}
