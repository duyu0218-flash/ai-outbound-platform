package com.duozhi.outbound.operations.service;

import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.call.service.CallLifecycle;
import com.duozhi.outbound.callback.service.CallbackInbox;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.Ascii;
import com.duozhi.outbound.common.support.ConfigFiles;
import com.duozhi.outbound.dial.service.StabilityAdmission;
import com.duozhi.outbound.monitoring.service.MetricsRegistry;
import com.duozhi.outbound.recording.controller.RecordingController;
import com.duozhi.outbound.voice.controller.VoiceController;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Source business metric snapshots, now rendered by Micrometer and shared with Actuator. */
@RestController
public class OperationalMetrics {
    private final Store store;
    private final Config config;
    private final CallbackInbox inbox;
    private final StabilityAdmission stability;
    private final MetricsRegistry metrics;
    private final VoiceController voice;
    private final ConfigurableListableBeanFactory beans;

    public OperationalMetrics(
            Store store,
            Config config,
            CallbackInbox inbox,
            StabilityAdmission stability,
            MetricsRegistry metrics,
            VoiceController voice,
            ConfigurableListableBeanFactory beans) {
        this.store = store;
        this.config = config;
        this.inbox = inbox;
        this.stability = stability;
        this.metrics = metrics;
        this.voice = voice;
        this.beans = beans;
    }

    public void authorize(String header) {
        String token = config.get("METRICS_TOKEN", "");
        String file = config.get("METRICS_TOKEN_FILE", "");
        if (!file.isEmpty())
            try {
                token = ConfigFiles.readUtf8(file, 4096).trim();
            } catch (IOException e) {
                throw new ApiError(401, "invalid metrics token");
            }
        if (token.isEmpty() || !Crypto.equal("Bearer " + token, header))
            throw new ApiError(401, "invalid metrics token");
    }

    @GetMapping("/metrics/runtime")
    public ResponseEntity<String> runtime(
            @RequestHeader(value = "Authorization", required = false)
            String token) {
        authorize(token);
        return response(metrics.scrape(true));
    }

    private ResponseEntity<String> response(String body) {
        return ResponseEntity.ok()
                .header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                .body(body);
    }

    @GetMapping("/metrics")
    public ResponseEntity<String> metrics(
            @RequestHeader(value = "Authorization", required = false)
            String token) {
        authorize(token);
        refresh();
        return response(metrics.scrape(false));
    }

    public void refresh() {
        metrics.snapshot(
                () -> {
                    metrics.put("ai_outbound_up", 1);
                    for (String[] group :
                            new String[][] {
                                {"callsession", "status", "ai_outbound_calls", "status"},
                                {
                                    "callsession",
                                    "voice_ai_pipeline",
                                    "ai_outbound_calls_by_pipeline",
                                    "pipeline"
                                },
                                {"taskoutbox", "state", "ai_outbound_tasks", "state"}
                            })
                        for (var row :
                                store.groupedAcross(group[0], "metricsGroup" + group[1], Map.of()))
                            metrics.put(
                                    group[2],
                                    row.get("total"),
                                    group[3],
                                    Objects.toString(row.get("value"), "").transform(Ascii::lower));
                    for (String kind :
                            List.of(
                                    "ai_turn",
                                    "business_callback",
                                    "recording_ingest",
                                    "recording_delete",
                                    "dial_call",
                                    "after_playback",
                                    "send_sms",
                                    "interrupt_playback")) {
                        var oldest =
                                store.firstAcrossOrdered(
                                        "taskoutbox",
                                        "scopedOperationalMetrics25",
                                        Store.queryParameters(
                                                -1,
                                                kind,
                                                store.encode(
                                                        "taskoutbox", "available_at", Store.now())),
                                        List.of("available_at:ASC"));
                        metrics.put(
                                "ai_outbound_task_oldest_ready_seconds",
                                oldest == null
                                        ? 0
                                        : Math.max(
                                                0,
                                                Duration.between(
                                                                        LocalDateTime.parse(
                                                                                oldest.get(
                                                                                                "available_at")
                                                                                        .toString()),
                                                                        Store.now())
                                                                .toMillis()
                                                        / 1000.0),
                                "type",
                                kind);
                    }
                    for (String stage :
                            List.of("ai.turn", "tts.dispatch", "tts.playback", "asr.final")) {
                        var rows =
                                store.orderedAcross(
                                        "callmetric",
                                        "scopedOperationalMetrics29",
                                        Store.queryParameters(
                                                -1,
                                                stage,
                                                store.encode(
                                                        "callmetric",
                                                        "created_at",
                                                        Store.now().minusSeconds(300))),
                                        List.of("created_at:DESC"),
                                        10000);
                        var durations = new ArrayList<Integer>();
                        for (var row : rows)
                            durations.add(CallLifecycle.number(row, "duration_ms"));
                        Collections.sort(durations);
                        metrics.put(
                                "ai_outbound_stage_recent_samples",
                                durations.size(),
                                "stage",
                                stage);
                        for (double quantile : new double[] {.5, .95, .99})
                            metrics.put(
                                    "ai_outbound_stage_recent_seconds",
                                    durations.isEmpty()
                                            ? 0
                                            : durations.get(
                                                            Math.max(
                                                                    0,
                                                                    (int)
                                                                                    Math.ceil(
                                                                                            quantile
                                                                                                    * durations
                                                                                                            .size())
                                                                            - 1))
                                                    / 1000.0,
                                    "stage",
                                    stage,
                                    "quantile",
                                    Double.toString(quantile));
                    }
                    metrics.put(
                            "ai_outbound_locked_users",
                            store.sumAcross(
                                    "user",
                                    "scopedOperationalMetrics33",
                                    Store.queryParameters(
                                            -1,
                                            store.encode("user", "locked_until", Store.now()))));
                    metrics.put(
                            "ai_outbound_recording_deletion_failures",
                            store.sumAcross(
                                    "recordingasset",
                                    "countDeletionFailures",
                                    Store.queryParameters(-1)));
                    metrics.put(
                            "ai_outbound_recording_ingestion_failures",
                            store.sumAcross(
                                    "recordingasset",
                                    "countIngestFailures",
                                    Store.queryParameters(-1)));
                    if (config.flag("CALLBACK_INBOX_ENABLED", false))
                        inbox.snapshot()
                                .forEach(
                                        (key, value) ->
                                                metrics.put(
                                                        "ai_outbound_callback_inbox_" + key,
                                                        value));
                    var control = stability.status();
                    metrics.put("ai_outbound_stability_dial_fraction", control.get("fraction"));
                    metrics.put(
                            "ai_outbound_stability_state",
                            1,
                            "state",
                            Objects.toString(control.get("mode"), ""));
                    voice.metricValues()
                            .forEach(
                                    (prefix, values) ->
                                            values.forEach(
                                                    (key, value) ->
                                                            metrics.put(prefix + key, value)));
                    if (beans.containsSingleton("recordingController"))
                        beans.getBean(RecordingController.class)
                                .metricValues()
                                .forEach(
                                        (key, value) -> {
                                            var parts = key.split(":");
                                            metrics.put(
                                                    "ai_outbound_recording_adapter_"
                                                            + parts[0]
                                                            + "_total",
                                                    value,
                                                    "result",
                                                    parts[1]);
                                        });
                });
    }
}
