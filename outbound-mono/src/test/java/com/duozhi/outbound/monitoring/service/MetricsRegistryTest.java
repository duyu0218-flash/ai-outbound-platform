package com.duozhi.outbound.monitoring.service;

import static org.assertj.core.api.Assertions.*;

import io.micrometer.prometheusmetrics.*;
import org.junit.jupiter.api.Test;

class MetricsRegistryTest {
    @Test
    void originalNamesLabelsAndValuesAreExportedAndOldDimensionsAreRemoved() {
        var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var metrics = new MetricsRegistry(prometheus);
            metrics.snapshot(
                    () -> {
                        metrics.put("ai_outbound_calls", 3, "status", "queued");
                        metrics.put("ai_outbound_calls", 2, "status", "completed");
                        metrics.put(
                                "ai_outbound_stage_recent_seconds",
                                .125,
                                "stage",
                                "ai.turn",
                                "quantile",
                                "0.95");
                    });
            String first = metrics.scrape(false);
            assertThat(first)
                    .contains(
                            "ai_outbound_calls{status=\"queued\"} 3.0",
                            "ai_outbound_calls{status=\"completed\"} 2.0",
                            "quantile=\"0.95\"",
                            "stage=\"ai.turn\"",
                            "0.125");
            metrics.snapshot(() -> metrics.put("ai_outbound_calls", 5, "status", "completed"));
            assertThat(metrics.scrape(false))
                    .doesNotContain("status=\"queued\"", "ai_outbound_stage_recent_seconds")
                    .contains("status=\"completed\"} 5.0");
            assertThat(metrics.scrape(true))
                    .contains("ai_outbound_jvm_threads", "ai_outbound_process_id")
                    .doesNotContain("ai_outbound_calls{");
        } finally {
            prometheus.close();
        }
    }
}
