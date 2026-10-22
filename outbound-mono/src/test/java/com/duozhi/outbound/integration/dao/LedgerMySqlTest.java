package com.duozhi.outbound.integration.dao;

import static org.junit.jupiter.api.Assertions.*;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.service.LedgerTestDatabase;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class LedgerMySqlTest {
    @Test
    void allVoiceTablesAndFifoTieOrdinalPersistAcrossRestart() throws Exception {
        var settings = new Settings(LedgerTestDatabase.settings(Map.of()));
        try (var ledger = new LocalLedger(settings)) {
            ledger.transaction(
                    db -> {
                        LocalLedger.execute(
                                db,
                                "INSERT INTO voice_action_commands VALUES"
                                        + " ('command','digest',null,'call',1)");
                        LocalLedger.execute(
                                db,
                                "INSERT INTO media_owners VALUES"
                                        + " ('call','worker','epoch','{}','pending') ON DUPLICATE KEY"
                                        + " UPDATE epoch=VALUES(epoch)");
                        LocalLedger.execute(
                                db,
                                "INSERT INTO media_owners VALUES"
                                        + " ('call','worker','next','{}','pending') ON DUPLICATE KEY"
                                        + " UPDATE epoch=VALUES(epoch)");
                        for (String id : List.of("z-first", "a-second"))
                            LocalLedger.execute(
                                    db,
                                    "INSERT INTO outbox(id,url,body,created,due,stream_key) VALUES"
                                            + " (?,?,?,1,1,'same-call')",
                                    id,
                                    "http://test",
                                    new byte[] {1, 2});
                        var tables =
                                LocalLedger.query(
                                                db,
                                                "SELECT table_name AS fact_table FROM"
                                                        + " information_schema.tables WHERE"
                                                        + " table_schema=DATABASE() AND table_name LIKE"
                                                        + " 'voice_%'")
                                        .stream()
                                        .map(r -> r.get("fact_table").toString())
                                        .collect(Collectors.toSet());
                        assertEquals(
                                Set.of(
                                        "voice_attempts",
                                        "voice_flags",
                                        "voice_nonces",
                                        "voice_outbox",
                                        "voice_audit",
                                        "voice_action_commands",
                                        "voice_permits",
                                        "voice_media_owners",
                                        "voice_model_budgets",
                                        "voice_model_usage",
                                        "voice_mock_schedule",
                                        "voice_dial_authorization",
                                        "voice_dial_authorization_history",
                                        "voice_scope_counter"),
                                tables);
                        return null;
                    });
        }
        try (var ledger = new LocalLedger(settings)) {
            var rows =
                    ledger.read(
                            db ->
                                    LocalLedger.query(
                                            db,
                                            "SELECT id FROM outbox ORDER BY"
                                                    + " created,enqueue_order"));
            assertEquals(
                    List.of("z-first", "a-second"), rows.stream().map(r -> r.get("id")).toList());
            assertEquals(
                    "next",
                    ledger.read(
                                    db ->
                                            LocalLedger.query(
                                                    db,
                                                    "SELECT epoch FROM media_owners WHERE"
                                                            + " call_id='call'"))
                            .get(0)
                            .get("epoch"));
            assertEquals(
                    1L,
                    ledger.<Long>read(
                            db ->
                                    LocalLedger.scalar(
                                            db,
                                            "SELECT COUNT(*) FROM voice_action_commands WHERE"
                                                    + " id='command'")));
        }
    }

    @Test
    void independentLedgerCommitSurvivesBusinessRollbackAndWriterRowSerializesPools()
            throws Exception {
        var values = LedgerTestDatabase.settings(Map.of());
        var settings = new Settings(values);
        var pool = Executors.newSingleThreadExecutor();
        try (var first = new LocalLedger(settings);
                var second = new LocalLedger(settings);
                var business =
                        DriverManager.getConnection(
                                values.get("MYSQL_JDBC_URL"),
                                values.get("MYSQL_USER"),
                                values.get("MYSQL_PASSWORD"))) {
            business.setAutoCommit(false);
            try (var q = business.createStatement()) {
                q.executeUpdate(
                        "INSERT INTO tenant(id,name,code,enabled,created_at,updated_at) VALUES"
                                + " (99,'rollback','rollback',true,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            }
            first.stopped(true, "independent commit");
            business.rollback();
            assertTrue((Boolean) second.summary().get("stopped"));
            try (var q = business.createStatement();
                    var r = q.executeQuery("SELECT COUNT(*) FROM tenant WHERE id=99")) {
                r.next();
                assertEquals(0, r.getInt(1));
            }
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var held =
                    pool.submit(
                            () ->
                                    first.transaction(
                                            db -> {
                                                entered.countDown();
                                                assertTrue(release.await(3, TimeUnit.SECONDS));
                                                return null;
                                            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var other = Executors.newSingleThreadExecutor();
            try {
                var waiting =
                        other.submit(
                                () -> {
                                    second.stopped(false, "serialized");
                                    return true;
                                });
                Thread.sleep(100);
                assertFalse(waiting.isDone());
                release.countDown();
                held.get(2, TimeUnit.SECONDS);
                assertTrue(waiting.get(2, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                other.shutdownNow();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void startupRejectsDurabilityParameterMismatch() throws Exception {
        var values = LedgerTestDatabase.settings(Map.of());
        var settings = new Settings(values);
        try (var root =
                        DriverManager.getConnection(
                                values.get("MYSQL_JDBC_URL"),
                                "root",
                                values.get("MYSQL_PASSWORD"));
                var q = root.createStatement()) {
            q.execute("SET GLOBAL sync_binlog=0");
            try {
                assertThrows(IllegalStateException.class, () -> LedgerDatabase.from(settings));
            } finally {
                q.execute("SET GLOBAL sync_binlog=1");
            }
            try (var good = LedgerDatabase.from(settings)) {
                assertNotNull(good.dataSource());
            }
        }
    }
}
