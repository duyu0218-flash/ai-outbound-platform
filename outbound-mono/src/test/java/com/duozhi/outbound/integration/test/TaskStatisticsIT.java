package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.operations.controller.CompetitiveWorkflows;
import com.duozhi.outbound.operations.service.CompetitiveMetrics;
import com.duozhi.outbound.operations.service.TaskStrategies;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskStatisticsIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    static final AtomicInteger SEQUENCE = new AtomicInteger(8300);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("JWT_SECRET", () -> "task-statistics-isolated-test-secret-more-than-32-characters");
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.flyway.url", MYSQL::getJdbcUrl);
        p.add("spring.flyway.user", () -> "root");
        p.add("spring.flyway.password", MYSQL::getPassword);
        p.add("spring.data.redis.host", () -> "127.0.0.1");
        p.add("spring.data.redis.port", () -> 9);
        p.add("REDIS_URL", () -> "");
        p.add("spring.ai.model.chat", () -> "none");
        p.add("JAVA_WORKERS_ENABLED", () -> "false");
    }

    @Autowired
    Store store;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Transactions tx;
    @Autowired
    TaskStrategies strategies;
    @Autowired
    CompetitiveWorkflows workflows;
    @Autowired
    CompetitiveMetrics metrics;
    long tenant;
    Object campaign, contact;
    Identity identity;
    final String phone = "13900009991";

    @BeforeEach
    void seed() {
        tenant = SEQUENCE.incrementAndGet();
        store.insert(
                "tenant",
                Map.of("id", tenant, "code", "stats-" + tenant, "name", "Statistics fixture"));
        campaign =
                store.insert(
                                "campaign",
                                Map.of(
                                        "tenant_id",
                                        tenant,
                                        "name",
                                        "Statistics fixture",
                                        "mode",
                                        "ai_only",
                                        "status",
                                        "running"))
                        .get("id");
        contact =
                store.insert(
                                "contact",
                                Map.of("tenant_id", tenant, "phone", phone, "name", "Synthetic"))
                        .get("id");
        identity = new Identity(tenant, null, () -> Set.of("operations:write"));
    }

    Map<String, Object> call(int attempts) {
        return store.insert(
                "callsession",
                Map.of(
                        "tenant_id",
                        tenant,
                        "campaign_id",
                        campaign,
                        "contact_id",
                        contact,
                        "phone",
                        phone,
                        "mode",
                        "ai_only",
                        "status",
                        "completed",
                        "attempts",
                        attempts));
    }

    Map<String, Object> usage(
            Map<String, Object> call, int attempt, boolean answered, double seconds) {
        return store.insert(
                "callusage",
                JsonSupport.map(
                        "tenant_id",
                        tenant,
                        "call_session_id",
                        call.get("id"),
                        "attempt",
                        attempt,
                        "answered_at",
                        answered ? Store.now() : null,
                        "telephony_seconds",
                        seconds));
    }

    Map<String, Object> analysis(Map<String, Object> call, String result) {
        return store.insert(
                "callanalysis",
                Map.of(
                        "tenant_id",
                        tenant,
                        "call_session_id",
                        call.get("id"),
                        "result_code",
                        result));
    }

    Map<String, Object> event(Map<String, Object> call, int attempt, LocalDateTime at) {
        return store.insert(
                "callevent",
                Map.of(
                        "call_session_id",
                        call.get("id"),
                        "event_type",
                        "dial_attempt_claimed",
                        "source",
                        "fixture",
                        "payload",
                        "{\"attempt\":" + attempt + "}",
                        "created_at",
                        at));
    }

    Map<String, Object> summary() {
        return jdbc.queryForMap(
                "SELECT * FROM ops_campaign_statistics WHERE tenant_id=? AND campaign_id=?",
                tenant,
                campaign);
    }

    long count(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    @Test
    void answeredRetriesUseAttemptDenominatorWhileOutcomeRatesUseSessions() {
        var call = call(2);
        usage(call, 1, true, 12);
        usage(call, 2, true, 18);
        analysis(call, "qualified_lead");
        strategies.save(
                identity,
                "evaluation",
                "answer",
                Map.of(
                        "revision",
                        0,
                        "config",
                        Map.of(
                                "name",
                                "接通率",
                                "enabled",
                                true,
                                "metric",
                                "connection",
                                "minimum_connected",
                                2,
                                "recommend",
                                80,
                                "reject",
                                60)));
        var assessment =
                strategies.assessContacts(
                        tenant, "answer", List.of(((Number) contact).longValue()));
        assertThat(assessment)
                .containsEntry("rate", 100.0)
                .containsEntry("advice", "recommend")
                .containsEntry("metric_basis", "dial_attempt_v2");
        var row = summary();
        assertThat(count(row, "answered")).isEqualTo(2);
        assertThat(count(row, "connected_sessions")).isEqualTo(1);
        var page = (Map<?, ?>) workflows.tasksFor(Map.of(), identity);
        var task = (Map<String, Object>) ((List<?>) page.get("rows")).getFirst();
        assertThat(task).containsEntry("answer_rate", 100.0).containsEntry("success_rate", 100.0);
        assertThat(count(strategies.stats(tenant, campaign), "connected")).isEqualTo(1);
        assertThat(count(strategies.stats(tenant, campaign), "answered_attempts")).isEqualTo(2);
    }

    @Test
    void lateCorrectionsAndRollbackKeepGuardAndMonitoringInSync() {
        var call = call(2);
        var first = usage(call, 1, true, 12);
        var second = usage(call, 2, false, 0);
        var outcome = analysis(call, "not_interested");
        assertThat(count(summary(), "answered")).isEqualTo(1);
        store.update(
                "callusage",
                tenant,
                second.get("id"),
                Map.of("answered_at", Store.now(), "telephony_seconds", 15));
        store.update(
                "callanalysis", tenant, outcome.get("id"), Map.of("result_code", "qualified_lead"));
        assertThat(count(summary(), "answered")).isEqualTo(2);
        assertThat(count(summary(), "analyzed_interested")).isEqualTo(1);
        for (int n = 0; n < 3; n++)
            store.update("callusage", tenant, first.get("id"), Map.of("telephony_seconds", 12));
        assertThat(((Number) summary().get("seconds")).doubleValue()).isEqualTo(27);
        assertThatThrownBy(
                        () ->
                                tx.run(
                                        () -> {
                                            store.update(
                                                    "callusage",
                                                    tenant,
                                                    first.get("id"),
                                                    JsonSupport.map(
                                                            "answered_at",
                                                            null,
                                                            "telephony_seconds",
                                                            0));
                                            assertThat(count(summary(), "answered")).isEqualTo(1);
                                            throw new IllegalStateException("rollback fixture");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count(summary(), "answered")).isEqualTo(2);
        store.update("callsession", tenant, call.get("id"), Map.of("status", "queued"));
        assertThat(count(strategies.stats(tenant, campaign), "connected")).isZero();
        store.update("callsession", tenant, call.get("id"), Map.of("status", "completed"));
        jdbc.update("DELETE FROM callusage WHERE id=?", second.get("id"));
        jdbc.update("DELETE FROM callanalysis WHERE id=?", outcome.get("id"));
        assertThat(count(summary(), "answered")).isEqualTo(1);
        assertThat(count(summary(), "analyzed_total")).isZero();
    }

    @Test
    void distinctContactsAndTransferredCampaignsRemainExact() {
        var one = call(1);
        var two = call(1);
        usage(one, 1, true, 7);
        assertThat(count(summary(), "dialed_contacts")).isEqualTo(1);
        store.update("callsession", tenant, one.get("id"), Map.of("attempts", 0));
        assertThat(count(summary(), "dialed_contacts")).isEqualTo(1);
        store.update("callsession", tenant, two.get("id"), Map.of("attempts", 0));
        assertThat(count(summary(), "dialed_contacts")).isZero();
        Object other =
                store.insert(
                                "campaign",
                                Map.of(
                                        "tenant_id",
                                        tenant,
                                        "name",
                                        "Moved",
                                        "mode",
                                        "ai_only",
                                        "status",
                                        "running"))
                        .get("id");
        store.update(
                "callsession", tenant, one.get("id"), Map.of("campaign_id", other, "attempts", 1));
        assertThat(count(summary(), "sessions")).isEqualTo(1);
        var moved =
                jdbc.queryForMap(
                        "SELECT * FROM ops_campaign_statistics WHERE tenant_id=? AND campaign_id=?",
                        tenant,
                        other);
        assertThat(count(moved, "answered")).isEqualTo(1);
        assertThat(count(moved, "dialed_contacts")).isEqualTo(1);
        jdbc.update("DELETE FROM callusage WHERE call_session_id=?", one.get("id"));
        jdbc.update("DELETE FROM callsession WHERE id=?", one.get("id"));
        moved =
                jdbc.queryForMap(
                        "SELECT * FROM ops_campaign_statistics WHERE tenant_id=? AND campaign_id=?",
                        tenant,
                        other);
        assertThat(count(moved, "sessions")).isZero();
        assertThat(count(moved, "dialed_contacts")).isZero();
    }

    @Test
    void attemptFactsTrackLateAnswerRevisionsPhoneChangesAndTenantIsolation() {
        var call = call(2);
        var start = Store.now().minusMinutes(1);
        var first = event(call, 1, start);
        var second = event(call, 2, start.plusSeconds(1));
        var answer = usage(call, 1, true, 10);
        assertThat(strategies.attemptsSince(tenant, phone, start, true, "all", 10)).isEqualTo(2);
        assertThat(strategies.attemptsSince(tenant, phone, start, false, "all", 10)).isEqualTo(1);
        assertThat(strategies.attemptsSince(tenant, phone, start, true, "answered", 10))
                .isEqualTo(1);
        assertThat(strategies.attemptsSince(tenant, phone, start, true, "unanswered", 10))
                .isEqualTo(1);
        assertThat(strategies.attemptsSince(tenant + 1, phone, start, true, "all", 10)).isZero();
        store.update("callusage", tenant, answer.get("id"), JsonSupport.map("answered_at", null));
        assertThat(strategies.attemptsSince(tenant, phone, start, true, "answered", 10)).isZero();
        String changed = phone + "123456789012345678901234567890";
        store.update("callsession", tenant, call.get("id"), Map.of("phone", changed));
        assertThat(strategies.attemptsSince(tenant, phone, start, true, "all", 10)).isZero();
        assertThat(strategies.attemptsSince(tenant, changed, start, true, "all", 1)).isEqualTo(1);
        jdbc.update("UPDATE callevent SET payload='not-json' WHERE id=?", second.get("id"));
        assertThat(strategies.attemptsSince(tenant, changed, start, true, "all", 10)).isEqualTo(2);
        jdbc.update("DELETE FROM callevent WHERE id=?", first.get("id"));
        assertThat(strategies.attemptsSince(tenant, changed, start, true, "all", 10)).isEqualTo(1);
    }

    @Test
    void parallelOutcomesDoNotLoseOrDuplicateCampaignTotals() throws Exception {
        var calls = new ArrayList<Map<String, Object>>();
        for (int n = 0; n < 24; n++) calls.add(call(1));
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<?>>();
            for (var call : calls)
                futures.add(
                        executor.submit(
                                () ->
                                        tx.run(
                                                () -> {
                                                    usage(call, 1, true, 3);
                                                    analysis(call, "interested");
                                                    return null;
                                                })));
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        }
        var row = summary();
        assertThat(count(row, "sessions")).isEqualTo(24);
        assertThat(count(row, "answered")).isEqualTo(24);
        assertThat(count(row, "analyzed_interested")).isEqualTo(24);
        assertThat(count(row, "dialed_contacts")).isEqualTo(1);
        assertThat(((Number) row.get("seconds")).doubleValue()).isEqualTo(72);
    }

    @Test
    void seatBucketsShareQueriesWithoutLeakingUsersOrCrossingBucketBoundaries() {
        var start = LocalDateTime.of(2026, 11, 5, 0, 0);
        jdbc.update(
                "INSERT INTO ops_seat_state_sample(tenant_id,user_id,state,event_at) VALUES(?,?,?,?),(?,?,?,?)",
                tenant,
                123,
                "ready",
                start,
                tenant,
                123,
                "offline",
                start.plusSeconds(75));
        var rows = new ArrayList<Map<String, Object>>();
        rows.add(new LinkedHashMap<>(Map.of("dimension_key", 123, "bucket", "2026-11-05 08:00")));
        rows.add(new LinkedHashMap<>(Map.of("dimension_key", 123, "bucket", "2026-11-05 08:01")));
        for (int n = 0; n < 201; n++)
            rows.add(
                    new LinkedHashMap<>(
                            Map.of("dimension_key", 124 + n, "bucket", "2026-11-05 08:00")));
        metrics.enrich(
                tenant,
                rows,
                Map.of(
                        "dimension",
                        "agent",
                        "granularity",
                        "minute",
                        "from",
                        start,
                        "until",
                        start.plusMinutes(2)));
        assertThat(((Number) rows.get(0).get("online_seconds")).doubleValue()).isEqualTo(60);
        assertThat(((Number) rows.get(1).get("online_seconds")).doubleValue()).isEqualTo(15);
        assertThat(rows.get(2).get("online_seconds")).isNull();
        assertThat(rows.getLast().get("online_seconds")).isNull();
    }

    @Test
    void upgradeBackfillsExistingLedgersThenAppliesTheSameDeltas() throws Exception {
        var call = call(2);
        usage(call, 1, true, 12);
        usage(call, 2, true, 18);
        analysis(call, "qualified_lead");
        event(call, 1, Store.now().minusMinutes(2));
        event(call, 2, Store.now().minusMinutes(1));
        String database = "statistics_upgrade_" + tenant;
        String base = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/";
        try (var connection =
                        java.sql.DriverManager.getConnection(base, "root", MYSQL.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
            try {
                org.flywaydb.core.Flyway.configure()
                        .dataSource(base + database, "root", MYSQL.getPassword())
                        .locations("classpath:db/migration")
                        .target("46")
                        .load()
                        .migrate();
                for (String table :
                        List.of(
                                "tenant",
                                "contact",
                                "campaign",
                                "callsession",
                                "callusage",
                                "callanalysis",
                                "callevent")) {
                    String where =
                            table.equals("tenant")
                                    ? "id=" + tenant
                                    : table.equals("callevent")
                                            ? "call_session_id IN (SELECT id FROM "
                                                    + MYSQL.getDatabaseName()
                                                    + ".callsession WHERE tenant_id="
                                                    + tenant
                                                    + ")"
                                            : "tenant_id=" + tenant;
                    statement.execute(
                            "INSERT INTO "
                                    + database
                                    + "."
                                    + table
                                    + " SELECT * FROM "
                                    + MYSQL.getDatabaseName()
                                    + "."
                                    + table
                                    + " WHERE "
                                    + where);
                }
                org.flywaydb.core.Flyway.configure()
                        .dataSource(base + database, "root", MYSQL.getPassword())
                        .locations("classpath:db/migration")
                        .target("47")
                        .load()
                        .migrate();
                try (var row =
                        statement.executeQuery(
                                "SELECT answered,connected_sessions,seconds,dialed_contacts,analyzed_interested FROM "
                                        + database
                                        + ".ops_campaign_statistics")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getLong(1)).isEqualTo(2);
                    assertThat(row.getLong(2)).isEqualTo(1);
                    assertThat(row.getDouble(3)).isEqualTo(30);
                    assertThat(row.getLong(4)).isEqualTo(1);
                    assertThat(row.getLong(5)).isEqualTo(1);
                }
                statement.execute(
                        "UPDATE " + database + ".callusage SET answered_at=NULL WHERE attempt=2");
                try (var row =
                        statement.executeQuery(
                                "SELECT (SELECT answered FROM "
                                        + database
                                        + ".ops_campaign_statistics) AS calls,(SELECT COUNT(*) FROM "
                                        + database
                                        + ".ops_dial_attempt_fact WHERE answered=TRUE) AS events")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getLong(1)).isEqualTo(1);
                    assertThat(row.getLong(2)).isEqualTo(1);
                }
            } finally {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }
}
