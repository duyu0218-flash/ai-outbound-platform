package com.duozhi.outbound;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@AdminPermission("admin:system")
public class SystemOverview {
    private final Store store;
    private final Settings settings;
    private final Config config;
    private final DependencyHealth health; private final StabilityAdmission stability; private final CallbackInbox inbox;
    public SystemOverview(Store store, Settings settings, Config config, DependencyHealth health, StabilityAdmission stability, CallbackInbox inbox) { this.store = store; this.settings = settings; this.config = config; this.health = health; this.stability = stability; this.inbox = inbox; }

    private Map<String, Object> counts(String table, String column, long tenant) {
        Map<String, Object> result = new LinkedHashMap<>();
        // Only constants from this controller may select SQL identifiers.
        for (Map<String, Object> row : store.projection(table,"systemCounts",Values.map("tenantId",tenant)))
            result.put(row.get(column).toString().transform(Ascii::lower), row.get("n"));
        return result;
    }

    @GetMapping("/api/v1/admin/system-overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        long tenant = Identity.from(request).tenant();
        int configured = config.number("MAX_CONCURRENT_CALLS", 20);
        try { configured = Integer.parseInt(settings.get(tenant, "capacity").get("max_concurrent_calls").toString()); }
        catch (NumberFormatException ignored) { }
        configured = Math.min(Math.max(1, config.number("OUTBOUND_PLATFORM_MAX_CONCURRENT", 20)), Math.max(1, configured));
        List<Map<String, Object>> lines = store.query("telephonyline","sourceSystemOverview32_0",Store.queryParameters(0,tenant,true));
        Long lineCapacity = lines.isEmpty() ? null : lines.stream().mapToLong(line -> Math.max(1L, ((Number)line.get("max_concurrency")).longValue())).sum();
        long effective = lineCapacity == null ? configured : Math.min(configured, lineCapacity);
        long active = store.scalar("callsession","sourceSystemOverview35_1",Store.queryParameters(0,tenant));
        Map<String, Object> capacity = new LinkedHashMap<>();
        capacity.put("configured_max_concurrent_calls", configured); capacity.put("line_max_concurrency", lineCapacity);
        capacity.put("effective_max_concurrent_calls", effective); capacity.put("active_calls", active); capacity.put("available_slots", Math.max(0, effective - active));
        capacity.put("limiting_source", lineCapacity == null || lineCapacity > configured ? "tenant_capacity" : lineCapacity == configured ? "tenant_and_line" : "telephony_line");
        capacity.put("telephony_provider", config.get("TELEPHONY_PROVIDER", "mock").trim().transform(Ascii::lower));
        capacity.put("environment_default", Math.max(1, config.number("MAX_CONCURRENT_CALLS", 20)));
        Map<String, Object> operations = new LinkedHashMap<>();
        operations.put("durable_tasks", counts("taskoutbox", "state", tenant));
        Double latency = store.value("callmetric","rawSystemOverview45_1",Store.queryParameters(0,tenant,true),Double.class);
        operations.put("average_ai_turn_ms", latency == null ? null : Math.round(latency * 10) / 10.0);
        operations.put("stale_processing_tasks", store.scalar("taskoutbox","sourceSystemOverview46_2",Store.queryParameters(0,tenant,store.encode("taskoutbox", "locked_at", Store.now().minusMinutes(5)))));
        Map<String, Object> oldest = store.one("taskoutbox","sourceSystemOverview47_3",Store.queryParameters(0,tenant));
        operations.put("oldest_open_task_age_sec", oldest == null ? 0 : Math.max(0, Duration.between(LocalDateTime.parse(oldest.get("created_at").toString()), Store.now()).getSeconds()));
        operations.put("recording_deletion_failures", store.scalar("recordingasset","sourceSystemOverview49_4",Store.queryParameters(0,tenant)));
        operations.put("callback_inbox", inbox.snapshot()); operations.put("stability", stability.status());
        return Values.map("services", health.services(tenant),
                "resources", Values.map("users", store.scalar("user","sourceSystemOverview52_5",Store.queryParameters(0,tenant)), "enabled_users", store.scalar("user","sourceSystemOverview52_6",Store.queryParameters(0,tenant,true)),
                        "lines", store.scalar("telephonyline","sourceSystemOverview53_7",Store.queryParameters(0,tenant)), "enabled_lines", lines.size()),
                "call_statuses", counts("callsession", "status", tenant), "capacity", capacity, "operations", operations, "generated_at", Store.now());
    }
}
