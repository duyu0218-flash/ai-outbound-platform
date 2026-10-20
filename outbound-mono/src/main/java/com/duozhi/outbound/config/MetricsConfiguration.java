package com.duozhi.outbound.config;
import io.micrometer.prometheusmetrics.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
/** The original /metrics endpoints remain available even when optional export auto-configuration is disabled. */
@Configuration(proxyBeanMethods=false)
public class MetricsConfiguration {
    @Bean @ConditionalOnMissingBean(PrometheusMeterRegistry.class)
    PrometheusMeterRegistry prometheusMeterRegistry(){return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);}
}
