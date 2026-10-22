package com.duozhi.outbound.recording.controller;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.config.UnifiedServiceSettings;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.recording.service.RecordingStorage;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

@RestController
@Lazy
public class RecordingController {
    private final Settings settings;
    private final RecordingStorage storage;
    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();

    @Autowired
    public RecordingController(Config config) throws Exception {
        this(new UnifiedServiceSettings(config, "recording"));
    }

    RecordingController(Settings settings) throws Exception {
        this.settings = settings;
        storage = new RecordingStorage(settings);
        storage.initialize();
    }

    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    public Map<String, Object> ready() {
        if (!storage.ready()) throw new Support.Failure(503, "recording bucket is unavailable");
        return Map.of("status", "ready", "bucket", storage.bucket());
    }

    @PostMapping("/v1/recordings/ingest")
    public Map<String, Object> ingest(
            @RequestBody
            Map<String, Object> payload,
            @RequestHeader(value = "Authorization", required = false)
            String auth)
            throws Exception {
        Support.auth(settings, "SERVICE_TOKEN", auth, false);
        try {
            var result = storage.ingest(payload);
            count("ingest", "success");
            return result;
        } catch (Support.Failure error) {
            count(
                    "ingest",
                    error.status == 400
                            ? "rejected"
                            : error.status == 502 ? "download_failed" : "storage_failed");
            throw error;
        }
    }

    @PostMapping("/v1/recordings/delete")
    public Map<String, Object> delete(
            @RequestBody
            Map<String, Object> payload,
            @RequestHeader(value = "Authorization", required = false)
            String auth)
            throws Exception {
        Support.auth(settings, "SERVICE_TOKEN", auth, false);
        try {
            boolean result = storage.delete(payload);
            count("delete", "success");
            return Map.of("deleted", result);
        } catch (Exception error) {
            count("delete", "failed");
            throw error;
        }
    }

    private void count(String operation, String result) {
        counts.computeIfAbsent(operation + ":" + result, key -> new LongAdder()).increment();
    }

    public Map<String, Long> metricValues() {
        Map<String, Long> result = new LinkedHashMap<>();
        counts.forEach((key, value) -> result.put(key, value.sum()));
        return result;
    }

    @PreDestroy
    void close() {
        storage.close();
    }
}
