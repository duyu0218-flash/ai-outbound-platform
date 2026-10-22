package com.duozhi.outbound.monitoring.config;

import io.micrometer.prometheusmetrics.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

/**
 * The original /metrics endpoints remain available even when optional export auto-configuration is
 * disabled.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {
    @Bean
    @ConditionalOnMissingBean(PrometheusMeterRegistry.class)
    PrometheusMeterRegistry prometheusMeterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }
}
