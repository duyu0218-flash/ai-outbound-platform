package com.duozhi.outbound.monitoring.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.duozhi.outbound.monitoring.service.MetricsRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MetricsConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    MetricsAutoConfiguration.class,
                                    PrometheusMetricsExportAutoConfiguration.class))
                    .withUserConfiguration(MetricsConfiguration.class);

    @Test
    void legacyMetricsRemainAvailableWhenOptionalExportIsDisabled() {
        context.withPropertyValues("management.prometheus.metrics.export.enabled=false")
                .run(
                        ctx -> {
                            var meters = ctx.getBean(PrometheusMeterRegistry.class);
                            assertThat(meters.getPrometheusRegistry())
                                    .isSameAs(ctx.getBean(PrometheusRegistry.class));
                            var legacy = new MetricsRegistry(meters);
                            legacy.put("ai_outbound_calls", 2, "status", "queued");
                            assertThat(legacy.scrape(false))
                                    .contains("ai_outbound_calls{status=\"queued\"} 2.0");
                        });
    }

    @Test
    void explicitlyProvidedRawRegistryIsUsedForApplicationMeters() {
        var raw = new PrometheusRegistry();
        context.withBean(PrometheusRegistry.class, () -> raw)
                .run(
                        ctx -> {
                            assertThat(ctx).hasSingleBean(PrometheusRegistry.class);
                            assertThat(
                                            ctx.getBean(PrometheusMeterRegistry.class)
                                                    .getPrometheusRegistry())
                                    .isSameAs(raw);
                        });
    }
}
