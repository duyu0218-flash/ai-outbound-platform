package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.callback.service.CallbackInbox;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.dial.service.DialDailyCounters;
import com.duozhi.outbound.dial.service.DialDrainControl;
import com.duozhi.outbound.job.worker.TaskWorker;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DialDrainHistoryIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "drain-history-isolated-secret-more-than-thirty-two-characters");
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.flyway.url", MYSQL::getJdbcUrl);
        p.add("spring.flyway.user", () -> "root");
        p.add("spring.flyway.password", MYSQL::getPassword);
        p.add("spring.data.redis.host", REDIS::getHost);
        p.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        p.add("REDIS_URL", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        p.add("JAVA_WORKERS_ENABLED", () -> "false");
        p.add("JAVA_CALLBACK_TERMINAL_BATCH_ENABLED", () -> "true");
        p.add("spring.ai.model.chat", () -> "none");
    }

    @Autowired Store store;
    @Autowired DialDrainControl drain;
    @Autowired CallbackInbox inbox;
    @Autowired TaskWorker worker;
    @Autowired DialDailyCounters counters;

    @Test
    void processedCallbackHistoryAllowsFreezeAndBackfillWhilePendingCallbacksBlockIt() {
        store.insert(
                "tenant",
                JsonSupport.map(
                        "id",
                        1,
                        "code",
                        "drain-history",
                        "name",
                        "Isolated drain history fixture"));
        var call =
                store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                1,
                                "phone",
                                "15500009991",
                                "mode",
                                "ai_only",
                                "status",
                                "completed",
                                "attempts",
                                1,
                                "max_attempts",
                                1));
        inbox.initialize();
        inbox.maintenance();
        inbox.receive(
                "telephony_status",
                JsonSupport.map(
                        "call_id",
                        call.get("id"),
                        "payload",
                        JsonSupport.map(
                                "status",
                                "completed",
                                "attempt",
                                1,
                                "event_id",
                                "drain-history-" + call.get("id"))));
        drain.begin();
        drain.closeSending();
        assertThat(drain.snapshot()).containsEntry("pending_inbox", 1L);
        assertThatThrownBy(drain::freezeMockFacts)
                .isInstanceOf(ApiError.class)
                .hasMessageContaining("pending_inbox");
        assertThat(inbox.consumeBatch(100)).isEqualTo(1);
        assertThat(drain.snapshot()).containsEntry("pending_inbox", 0L);
        for (int i = 0; i < 4 && worker.processLane(1L, worker.analysisLane()); i++) {}
        assertThat(drain.freezeMockFacts())
                .containsEntry("phase", "FROZEN")
                .containsEntry("pending_inbox", 0L)
                .containsEntry("external_pbx_verified", false);
        assertThat(counters.build(java.time.Instant.now())).containsEntry("state", "VERIFIED");
    }
}
