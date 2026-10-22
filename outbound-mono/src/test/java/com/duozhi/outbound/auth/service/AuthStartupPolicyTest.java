package com.duozhi.outbound.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.web.HttpBoundary;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;

class AuthStartupPolicyTest {
    @TestConfiguration(proxyBeanMethods = false)
    @Import({Config.class, AuthStartupPolicy.class, HttpBoundary.class})
    static class BoundaryContext {}

    private ApplicationContextRunner production() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ENV", "production");
        String[] names = {
            "SECRET_KEY",
            "JWT_SECRET",
            "TELEPHONY_WEBHOOK_SECRET",
            "VOICE_COMMAND_SECRET",
            "OUTBOUND_SECURITY_APPROVAL_TOKEN",
            "TELEPHONY_SERVICE_TOKEN",
            "AI_AGENT_SERVICE_TOKEN",
            "API_KEY",
            "METRICS_TOKEN"
        };
        for (int i = 0; i < names.length; i++)
            values.put(names[i], String.valueOf((char) ('A' + i)).repeat(40));
        values.put("TRUSTED_HOSTS", "localhost");
        values.put("CORS_ALLOW_ORIGINS", "");
        values.put("TELEPHONY_WEBHOOK_TOKEN", "webhook-token");
        values.put("spring.datasource.url", "jdbc:mysql://localhost/test");
        values.put("TELEPHONY_PROVIDER", "freeswitch_esl");
        values.put("REDIS_URL", "rediss://:" + "R".repeat(32) + "@redis.invalid:6379");
        values.put("OUTBOUND_ALLOWED_PHONE_PREFIXES", "86");
        values.put("RECORDING_RETENTION_DAYS", "0");
        values.put("JAVA_RELEASE_ACCEPTED", "true");
        values.put("DEMO_USERS_ENABLED", "false");
        values.put("AUTO_MIGRATE", "false");
        values.put("JAVA_INITIALIZE_SCHEMA", "false");
        values.put("TASK_INLINE_EXECUTION_ENABLED", "false");
        values.put("TENANT_API_KEYS_JSON", "");
        values.put("WEBRTC_ENABLED", "false");
        values.put("SMS_PROVIDER", "mock");
        values.put("METRICS_TOKEN_FILE", "");
        return new ApplicationContextRunner()
                .withUserConfiguration(BoundaryContext.class)
                .withPropertyValues(
                        values.entrySet().stream()
                                .map(e -> e.getKey() + "=" + e.getValue())
                                .toArray(String[]::new));
    }

    @Test
    void originalValidProductionConfigurationStarts() {
        production().run(c -> assertThat(c).hasNotFailed());
    }

    @Test
    void weakJwtSecretRejectsStartup() {
        production()
                .withPropertyValues("JWT_SECRET=change-me")
                .run(
                        c -> {
                            assertThat(c).hasFailed();
                            assertThat(c.getStartupFailure()).hasStackTraceContaining("JWT_SECRET");
                        });
    }

    @Test
    void demoAccountsRejectProductionStartup() {
        production()
                .withPropertyValues("DEMO_USERS_ENABLED=true")
                .run(
                        c -> {
                            assertThat(c).hasFailed();
                            assertThat(c.getStartupFailure())
                                    .hasStackTraceContaining("DEMO_USERS_ENABLED must be false");
                        });
    }

    @Test
    void wildcardCorsRejectsStartup() {
        production()
                .withPropertyValues("CORS_ALLOW_ORIGINS=*")
                .run(
                        c -> {
                            assertThat(c).hasFailed();
                            assertThat(c.getStartupFailure())
                                    .hasStackTraceContaining("CORS_ALLOW_ORIGINS wildcard");
                        });
    }

    @Test
    void developmentStillPermitsDemoFlagButRejectsWildcardCors() {
        new ApplicationContextRunner()
                .withUserConfiguration(BoundaryContext.class)
                .withPropertyValues(
                        "ENV=dev", "JWT_SECRET=" + "J".repeat(40), "DEMO_USERS_ENABLED=true")
                .run(c -> assertThat(c).hasNotFailed());
        new ApplicationContextRunner()
                .withUserConfiguration(BoundaryContext.class)
                .withPropertyValues(
                        "ENV=dev", "JWT_SECRET=" + "J".repeat(40), "CORS_ALLOW_ORIGINS=*")
                .run(c -> assertThat(c).hasFailed());
    }
}
