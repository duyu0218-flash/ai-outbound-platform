package com.duozhi.outbound.monitoring.config;

import io.micrometer.core.instrument.Clock;
import io.micrometer.prometheusmetrics.*;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

/**
 * The original /metrics endpoints remain available even when optional export auto-configuration is
 * disabled.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {
    @Bean
    @ConditionalOnMissingBean(PrometheusRegistry.class)
    PrometheusRegistry prometheusRegistry() {
        // Boot 4's scrape endpoint consumes this raw registry, not the meter registry.
        return new PrometheusRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(PrometheusMeterRegistry.class)
    PrometheusMeterRegistry prometheusMeterRegistry(PrometheusRegistry prometheusRegistry) {
        return new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT, prometheusRegistry, Clock.SYSTEM);
    }
}
