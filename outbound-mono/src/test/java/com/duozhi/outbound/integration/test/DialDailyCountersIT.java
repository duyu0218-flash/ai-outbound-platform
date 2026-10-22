package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.call.domain.DailyAttemptCount;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.dial.service.DialDailyCounters;
import com.duozhi.outbound.dial.service.DialDrainControl;
import com.duozhi.outbound.job.worker.TaskWorker;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DialDailyCountersIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "daily-facts-fixture-only-secret-more-than-32-characters");
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
        p.add("spring.ai.model.chat", () -> "none");
    }

    @Autowired Store store;
    @Autowired JdbcTemplate jdbc;
    @Autowired DialDrainControl drain;
    @Autowired DialDailyCounters counters;
    @Autowired Transactions tx;
    final String phone = "+8613900000999";

    @BeforeEach
    void seed() {
        drain.cancelDrain();
        jdbc.update(
                "UPDATE dial_admission_control SET daily_read='legacy',generation_id=NULL WHERE"
                        + " id=1");
        jdbc.update("UPDATE dial_migration_generation SET state='RETIRED'");
        // Every case owns its facts; today's long-phone fixture must not contribute
        // to the next case's independent yesterday/today oracle.
        jdbc.update("DELETE FROM callevent");
        jdbc.update("DELETE FROM callsession");
        if (store.one("tenant", "findById", Map.of("id", 1)) == null)
            store.insert("tenant", Map.of("id", 1, "code", "daily", "name", "Daily facts"));
        var setting =
                store.one(
                        "adminsetting",
                        "findBySection",
                        JsonSupport.map("tenantId", 1, "section", "compliance"));
        if (setting == null)
            store.insert(
                    "adminsetting",
                    Map.of(
                            "tenant_id",
                            1,
                            "section",
                            "compliance",
                            "data_json",
                            "{\"timezone\":\"UTC\"}"));
    }

    @AfterEach
    void cleanup() {
        drain.cancelDrain();
        jdbc.update(
                "UPDATE dial_admission_control SET daily_read='legacy',generation_id=NULL WHERE"
                        + " id=1");
        jdbc.update("UPDATE dial_migration_generation SET state='RETIRED'");
    }

    long generation() {
        drain.begin();
        drain.closeSending();
        drain.freezeMockFacts();
        long id = ((Number) counters.build(Instant.now()).get("generation_id")).longValue();
        counters.publish(id, true);
        drain.cancelDrain();
        return id;
    }

    LocalDateTime day() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay();
    }

    void expect(long gen, String number, long l, long e) {
        assertThat(counters.read(gen, 1, number, day(), "UTC"))
                .isEqualTo(new DailyAttemptCount(l, e));
    }

    @Test
    void crossDayRejectedAndReadmittedFactsMatchBothLegacyComponents() {
        var old = day().minusDays(1).plusHours(10);
        var call =
                store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                1,
                                "phone",
                                phone,
                                "mode",
                                "ai_only",
                                "status",
                                "completed",
                                "attempts",
                                2,
                                "created_at",
                                old,
                                "started_at",
                                old));
        long gen = generation();
        expect(gen, null, 0, 0);
        expect(gen, phone, 0, 0);
        tx.serialized(
                () -> {
                    store.update(
                            "callsession",
                            1,
                            call.get("id"),
                            JsonSupport.map("attempts", 3, "started_at", Store.now()));
                    store.insert(
                            "callevent",
                            Map.of(
                                    "call_session_id",
                                    call.get("id"),
                                    "event_type",
                                    "dial_attempt_claimed",
                                    "source",
                                    "test",
                                    "payload",
                                    "{\"attempt\":3}"));
                    return null;
                });
        expect(gen, null, 3, 1);
        expect(gen, phone, 0, 1);
        store.update(
                "callsession",
                1,
                call.get("id"),
                JsonSupport.map("attempts", 2, "started_at", null));
        expect(gen, null, 0, 1);
        expect(gen, phone, 0, 1);
        tx.serialized(
                () -> {
                    store.update(
                            "callsession",
                            1,
                            call.get("id"),
                            JsonSupport.map("attempts", 3, "started_at", Store.now()));
                    store.insert(
                            "callevent",
                            Map.of(
                                    "call_session_id",
                                    call.get("id"),
                                    "event_type",
                                    "dial_attempt_claimed",
                                    "source",
                                    "test",
                                    "payload",
                                    "{\"attempt\":3}"));
                    return null;
                });
        expect(gen, null, 3, 2);
        expect(gen, phone, 0, 2);
        assertThat(counters.verify(gen)).containsEntry("mismatches", 0L);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM callsession WHERE id=?", call.get("id")))
                .isInstanceOf(RuntimeException.class);
        expect(gen, null, 3, 2);
        expect(gen, phone, 0, 2);
        jdbc.update("DELETE FROM callevent WHERE call_session_id=?", call.get("id"));
        jdbc.update("DELETE FROM callsession WHERE id=?", call.get("id"));
        expect(gen, null, 0, 0);
        expect(gen, phone, 0, 0);
        assertThat(counters.verify(gen)).containsEntry("mismatches", 0L);
    }

    @Test
    void factAndCounterChangesRollbackTogetherAndMissingWindowsNeverMeanZero() {
        var call =
                store.insert(
                        "callsession",
                        Map.of(
                                "tenant_id",
                                1,
                                "phone",
                                phone,
                                "mode",
                                "ai_only",
                                "status",
                                "completed"));
        long gen = generation();
        var before = counters.read(gen, 1, null, day(), "UTC");
        assertThatThrownBy(
                        () ->
                                tx.serialized(
                                        () -> {
                                            store.update(
                                                    "callsession",
                                                    1,
                                                    call.get("id"),
                                                    JsonSupport.map(
                                                            "attempts",
                                                            4,
                                                            "started_at",
                                                            Store.now()));
                                            store.insert(
                                                    "callevent",
                                                    Map.of(
                                                            "call_session_id",
                                                            call.get("id"),
                                                            "event_type",
                                                            "dial_attempt_claimed",
                                                            "source",
                                                            "test"));
                                            throw new IllegalStateException("rollback");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(counters.read(gen, 1, null, day(), "UTC")).isEqualTo(before);
        assertThat(counters.verify(gen)).containsEntry("mismatches", 0L);
        assertThatThrownBy(() -> counters.read(gen, 1, "+8613800000000", day(), "UTC"))
                .isInstanceOf(TaskWorker.Deferred.class);
        assertThatThrownBy(() -> counters.read(gen, 1, phone, day(), "Asia/Shanghai"))
                .isInstanceOf(TaskWorker.Deferred.class);
    }

    @Test
    void frozenWritersAreRejectedAndUnverifiedGenerationCannotBePublished() {
        drain.begin();
        drain.closeSending();
        drain.freezeMockFacts();
        assertThatThrownBy(
                        () ->
                                store.insert(
                                        "callsession",
                                        Map.of("tenant_id", 1, "phone", phone, "status", "queued")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                store.insert(
                                        "contact",
                                        Map.of(
                                                "tenant_id",
                                                1,
                                                "phone",
                                                "+8613800000123",
                                                "name",
                                                "frozen")))
                .isInstanceOf(RuntimeException.class);
        jdbc.update("INSERT INTO dial_migration_generation(state) VALUES('BUILDING')");
        long id = jdbc.queryForObject("SELECT MAX(id) FROM dial_migration_generation", Long.class);
        assertThatThrownBy(() -> counters.publish(id, true)).isInstanceOf(ApiError.class);
    }

    @Test
    void missingOrSubstitutedCounterKeysCannotPassGenerationVerification() {
        store.insert(
                "contact", Map.of("tenant_id", 1, "phone", "+8613900000789", "name", "inventory"));
        drain.begin();
        drain.closeSending();
        drain.freezeMockFacts();
        long id = ((Number) counters.build(Instant.now()).get("generation_id")).longValue();
        assertThat(counters.verify(id)).containsEntry("inventory_complete", true);
        jdbc.update(
                "UPDATE dial_daily_counter SET phone='+8613900000790' WHERE generation_id=? AND"
                        + " phone='+8613900000789'",
                id);
        assertThat(counters.verify(id)).containsEntry("inventory_complete", false);
        assertThatThrownBy(() -> counters.publish(id, true)).isInstanceOf(ApiError.class);
        jdbc.update(
                "DELETE FROM dial_daily_counter WHERE generation_id=? AND scope_type='tenant'", id);
        assertThat(counters.verify(id)).containsEntry("inventory_complete", false);
        assertThatThrownBy(() -> counters.verify(0)).isInstanceOf(ApiError.class);
    }

    @Test
    void fullLengthPhonesWithIdenticalPrefixesKeepSeparateLegacyFacts() {
        String prefix = "1".repeat(1400), a = prefix + "A", b = prefix + "B";
        var one =
                store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                1,
                                "phone",
                                a,
                                "mode",
                                "ai_only",
                                "status",
                                "completed",
                                "attempts",
                                2,
                                "started_at",
                                Store.now()));
        var two =
                store.insert(
                        "callsession",
                        JsonSupport.map(
                                "tenant_id",
                                1,
                                "phone",
                                b,
                                "mode",
                                "ai_only",
                                "status",
                                "completed",
                                "attempts",
                                3,
                                "started_at",
                                Store.now()));
        long gen = generation();
        expect(gen, a, 2, 0);
        expect(gen, b, 3, 0);
        store.insert(
                "callevent",
                Map.of(
                        "call_session_id",
                        one.get("id"),
                        "event_type",
                        "dial_attempt_claimed",
                        "source",
                        "test"));
        expect(gen, a, 2, 1);
        expect(gen, b, 3, 0);
        assertThat(counters.verify(gen))
                .containsEntry("inventory_complete", true)
                .containsEntry("mismatches", 0L);
        store.update(
                "callsession",
                1,
                two.get("id"),
                JsonSupport.map("phone", "redacted:" + "a".repeat(64)));
        expect(gen, b, 0, 0);
        assertThat(counters.verify(gen)).containsEntry("inventory_complete", false);
    }
}
