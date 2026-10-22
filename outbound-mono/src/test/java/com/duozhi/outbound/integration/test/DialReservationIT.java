package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.dial.service.DialDailyCounters;
import com.duozhi.outbound.dial.service.DialDrainControl;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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
class DialReservationIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "reservation-isolated-fixture-secret-more-than-32-characters");
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
    @Autowired DialDailyCounters daily;
    @Autowired Transactions tx;

    @BeforeEach
    void seed() {
        drain.cancelDrain();
        jdbc.update(
                "UPDATE dial_admission_control SET"
                        + " enforce_quotas=FALSE,daily_read='legacy',admission_mode='lock_compat' WHERE"
                        + " id=1");
        jdbc.update(
                "UPDATE callsession SET status='COMPLETED' WHERE status"
                        + " IN('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN')");
        for (int id : List.of(1, 2))
            if (store.one("tenant", "findById", Map.of("id", id)) == null)
                store.insert(
                        "tenant",
                        Map.of("id", id, "code", "reservation-" + id, "name", "Reservation " + id));
        drain.begin();
        drain.closeSending();
        drain.freezeMockFacts();
        long generation = ((Number) daily.build(Instant.now()).get("generation_id")).longValue();
        daily.publish(generation, false);
        drain.cancelDrain();
        // Isolated structural tests deliberately enable the DB constraints directly.
        // Product activation remains unavailable until the authorization/fence gate.
        jdbc.update(
                "UPDATE dial_admission_control SET"
                        + " enforce_quotas=TRUE,platform_limit=2,tenant_default_limit=2 WHERE id=1");
    }

    @AfterEach
    void cleanup() {
        drain.cancelDrain();
        jdbc.update(
                "UPDATE dial_admission_control SET"
                        + " enforce_quotas=FALSE,admission_mode='lock_compat' WHERE id=1");
        jdbc.update(
                "UPDATE callsession SET status='COMPLETED' WHERE status"
                        + " IN('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN')");
    }

    Map<String, Object> queued(int tenant, String phone) {
        return store.insert(
                "callsession",
                Map.of(
                        "tenant_id",
                        tenant,
                        "phone",
                        phone,
                        "mode",
                        "ai_only",
                        "status",
                        "queued",
                        "max_attempts",
                        3));
    }

    void activate(Object id) {
        jdbc.update(
                "UPDATE callsession SET status='DIALING',attempts=1,started_at=UTC_TIMESTAMP(3)"
                        + " WHERE id=?",
                id);
    }

    long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    int race(List<Map<String, Object>> calls) throws Exception {
        var ready = new CountDownLatch(calls.size());
        var go = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(calls.size());
        var futures = new ArrayList<Future<Boolean>>();
        try {
            for (var call : calls)
                futures.add(
                        workers.submit(
                                () -> {
                                    ready.countDown();
                                    go.await();
                                    try (var db =
                                            DriverManager.getConnection(
                                                    MYSQL.getJdbcUrl(),
                                                    MYSQL.getUsername(),
                                                    MYSQL.getPassword())) {
                                        db.setAutoCommit(false);
                                        try (var ps =
                                                db.prepareStatement(
                                                        "UPDATE callsession SET"
                                                                + " status='DIALING',attempts=1,started_at=UTC_TIMESTAMP(3)"
                                                                + " WHERE id=?")) {
                                            ps.setObject(1, call.get("id"));
                                            ps.executeUpdate();
                                            db.commit();
                                            return true;
                                        } catch (SQLException rejected) {
                                            db.rollback();
                                            return false;
                                        }
                                    }
                                }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            int accepted = 0;
            for (var f : futures) if (f.get(20, TimeUnit.SECONDS)) accepted++;
            return accepted;
        } finally {
            go.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void normalizedPhoneGuardWorksAcrossIndependentConnections() throws Exception {
        var a = queued(1, "+8613900000001");
        var b = queued(1, "8613900000001");
        assertThat(race(List.of(a, b))).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM dial_reservation WHERE state='RESERVED'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM dial_phone_guard")).isEqualTo(1);
    }

    @Test
    void sharedPlatformAndTenantCountersCannotBeOversubscribed() throws Exception {
        assertThat(
                        race(
                                List.of(
                                        queued(1, "8613900000011"),
                                        queued(1, "8613900000012"),
                                        queued(2, "8613900000013"))))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM dial_reservation WHERE state='RESERVED'"))
                .isEqualTo(2);
        assertThat(count("SELECT MAX(active) FROM dial_quota_counter WHERE scope_type='platform'"))
                .isEqualTo(2);
        assertThat(count("SELECT MAX(active) FROM dial_quota_counter WHERE scope_type='tenant'"))
                .isLessThanOrEqualTo(2);
    }

    @Test
    void reservationGuardCountersAndBusinessFactRollbackTogether() {
        var call = queued(1, "8613900000021");
        assertThatThrownBy(
                        () ->
                                tx.run(
                                        () -> {
                                            activate(call.get("id"));
                                            throw new IllegalStateException("rollback");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.require("callsession", 1, call.get("id")))
                .containsEntry("status", "queued")
                .containsEntry("attempts", 0);
        assertThat(count("SELECT COUNT(*) FROM dial_reservation WHERE state='RESERVED'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM dial_phone_guard")).isZero();
        activate(call.get("id"));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM callsession WHERE id=?", call.get("id")))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT COUNT(*) FROM dial_reservation WHERE state='RESERVED'"))
                .isEqualTo(1);
    }

    @Test
    void terminalAndDuplicateTerminalReleaseExactlyOnce() {
        var call = queued(1, "8613900000031");
        activate(call.get("id"));
        store.insert(
                "callevent",
                Map.of(
                        "call_session_id",
                        call.get("id"),
                        "event_type",
                        "dial_attempt_claimed",
                        "source",
                        "test"));
        jdbc.update("UPDATE callsession SET status='COMPLETED' WHERE id=?", call.get("id"));
        jdbc.update("UPDATE callsession SET status='COMPLETED' WHERE id=?", call.get("id"));
        assertThat(count("SELECT COUNT(*) FROM dial_reservation WHERE state='RESERVED'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM dial_phone_guard")).isZero();
        assertThat(count("SELECT COALESCE(SUM(active),0) FROM dial_quota_counter")).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM callevent WHERE call_session_id=? AND"
                                        + " event_type='dial_attempt_claimed'",
                                Long.class,
                                call.get("id")))
                .isEqualTo(1);
    }
}
