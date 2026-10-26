package com.duozhi.outbound.common.config;

import com.duozhi.outbound.ai.service.EmbeddedAgent;
import com.duozhi.outbound.integration.config.UnifiedServiceSettings;
import com.duozhi.outbound.integration.dao.LedgerDatabase;
import com.duozhi.outbound.voiceai.service.VoiceAiCredentials;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class EmbeddedServicesConfiguration {
    @Bean
    EmbeddedAgent embeddedAgent(
            Config config, LedgerDatabase database, VoiceAiCredentials credentials)
            throws Exception {
        var settings = credentials.root(new UnifiedServiceSettings(config, "agent"));
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
