package com.duozhi.services;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="service.role", havingValue="recording")
class RecordingController {
    private final Settings settings;
    private final RecordingStorage storage;
    private final Map<String,LongAdder> counts = new ConcurrentHashMap<>();
    RecordingController(Settings settings) throws Exception { this.settings = settings; storage = new RecordingStorage(settings); storage.initialize(); }
    @GetMapping("/health") Map<String,Object> health() { return Map.of("status", "ok"); }
    @GetMapping("/readyz") Map<String,Object> ready() {
        if (!storage.ready()) throw new Support.Failure(503, "recording bucket is unavailable");
        return Map.of("status", "ready", "bucket", storage.bucket());
    }
    @PostMapping("/v1/recordings/ingest") Map<String,Object> ingest(@RequestBody Map<String,Object> payload, @RequestHeader(value="Authorization", required=false) String auth) throws Exception {
        Support.auth(settings, "SERVICE_TOKEN", auth, false);
        try { var result = storage.ingest(payload); count("ingest", "success"); return result; }
        catch (Support.Failure error) { count("ingest", error.status == 400 ? "rejected" : error.status == 502 ? "download_failed" : "storage_failed"); throw error; }
    }
    @PostMapping("/v1/recordings/delete") Map<String,Object> delete(@RequestBody Map<String,Object> payload, @RequestHeader(value="Authorization", required=false) String auth) throws Exception {
        Support.auth(settings, "SERVICE_TOKEN", auth, false);
        try { boolean result = storage.delete(payload); count("delete", "success"); return Map.of("deleted", result); }
        catch (Exception error) { count("delete", "failed"); throw error; }
    }
    private void count(String operation, String result) { counts.computeIfAbsent(operation + ":" + result, key -> new LongAdder()).increment(); }
    @GetMapping(value="/metrics", produces="text/plain;version=0.0.4;charset=utf-8") String metrics(@RequestHeader(value="Authorization", required=false) String auth) {
        Support.auth(settings, "METRICS_TOKEN", auth, false);
        StringBuilder output = new StringBuilder();
        counts.forEach((key, value) -> { var parts = key.split(":"); output.append("ai_outbound_recording_adapter_").append(parts[0]).append("_total{result=\"").append(parts[1]).append("\"} ").append(value.sum()).append('\n'); });
        return output.append(Metrics.jvm()).toString();
    }
}
