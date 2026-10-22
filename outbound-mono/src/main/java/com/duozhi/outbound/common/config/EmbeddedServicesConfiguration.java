package com.duozhi.outbound.common.config;

import com.duozhi.outbound.ai.service.EmbeddedAgent;
import com.duozhi.outbound.integration.config.UnifiedServiceSettings;

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class EmbeddedServicesConfiguration {
    @org.springframework.context.annotation.Bean
    EmbeddedAgent embeddedAgent(
            Config config, com.duozhi.outbound.integration.dao.LedgerDatabase database)
            throws Exception {
        var settings = new UnifiedServiceSettings(config, "agent");
        long connections = settings.number("LLM_MAX_CONNECTIONS", 256, 1, 1024);
        System.setProperty(
                "jdk.httpclient.connectionPoolSize",
                Long.toString(
                        settings.number(
                                "LLM_MAX_KEEPALIVE_CONNECTIONS",
                                Math.min(128, connections),
                                1,
                                connections)));
        return new EmbeddedAgent(settings, database);
    }
}
