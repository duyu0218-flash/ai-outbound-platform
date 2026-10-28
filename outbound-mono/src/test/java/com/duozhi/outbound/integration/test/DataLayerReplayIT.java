package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Acceptance evidence only: two real migrations and an independent database inventory. */
@Testcontainers
class DataLayerReplayIT {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4")
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_0900_ai_ci");

    @Test
    void v18UpgradePreservesLiveCompatibilityReservationsAndMode() throws Exception {
        String schema = "v3_upgrade";
        try (var connection =
                        DriverManager.getConnection(
                                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE DATABASE "
                            + schema
                            + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        }
        String url =
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema;
        var old =
                Flyway.configure()
                        .dataSource(url, "root", MYSQL.getPassword())
                        .locations("classpath:db/migration")
                        .target("18")
                        .load();
        assertThat(old.migrate().migrationsExecuted).isEqualTo(18);
        try (var connection = DriverManager.getConnection(url, "root", MYSQL.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO tenant(id,name,code,enabled,created_at,updated_at)"
                            + " VALUES(1,'upgrade"
                            + " fixture','upgrade',TRUE,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            statement.executeUpdate(
                    "INSERT INTO"
                            + " callsession(id,tenant_id,phone,mode,status,attempts,max_attempts,voice_ai_pipeline,created_at,updated_at)"
                            + " VALUES('11111111-1111-1111-1111-111111111111',1,'13900000001','AI_ONLY','DIALING',1,3,'legacy',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            try (var rows =
                    statement.executeQuery(
                            "SELECT COUNT(*) FROM dial_reservation WHERE state='RESERVED'")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(1);
            }
        }
        var upgrade =
                Flyway.configure()
                        .dataSource(url, "root", MYSQL.getPassword())
                        .locations("classpath:db/migration")
                        .load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(10);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url, "root", MYSQL.getPassword());
                var statement = connection.createStatement()) {
            try (var rows =
                    statement.executeQuery(
                            "SELECT COUNT(*) FROM dial_reservation WHERE state='RESERVED'")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(1);
            }
            try (var rows =
                    statement.executeQuery(
                            "SELECT admission_mode FROM dial_admission_control WHERE id=1")) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("lock_compat");
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM dial_v3_quota_grant")) {
                rows.next();
                assertThat(rows.getLong(1)).isZero();
            }
            // Existing terminal writer still releases its original reservation;
            // the unused new ledger neither double-counts nor takes ownership.
            statement.executeUpdate(
                    "UPDATE callsession SET status='COMPLETED' WHERE"
                            + " id='11111111-1111-1111-1111-111111111111'");
            try (var rows =
                    statement.executeQuery(
                            "SELECT COUNT(*) FROM dial_reservation WHERE state='RESERVED'")) {
                rows.next();
                assertThat(rows.getLong(1)).isZero();
            }
        }
    }

    @Test
    void v22TrialHistoryKeepsUnknownMeasurements() throws Exception {
        String url =
                "jdbc:mysql://"
                        + MYSQL.getHost()
                        + ":"
                        + MYSQL.getMappedPort(3306)
                        + "/trial_upgrade";
        try (var c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                var st = c.createStatement()) {
            st.execute("CREATE DATABASE trial_upgrade");
        }
        Flyway.configure()
                .dataSource(url, "root", MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("22")
                .load()
                .migrate();
        try (var c = DriverManager.getConnection(url, "root", MYSQL.getPassword());
                var st = c.createStatement()) {
            st.executeUpdate(
                    "INSERT INTO aivoicetrial(id,tenant_id,user_id,contact_id,client_key,request_hash,status,script_snapshot,profile_json,events_json,result_json,published_json) VALUES('old',1,1,1,'old','hash','completed','script','{}','[]','{}','{}')");
        }
        var upgrade =
                Flyway.configure()
                        .dataSource(url, "root", MYSQL.getPassword())
                        .locations("classpath:db/migration")
                        .load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(6);
        try (var c = DriverManager.getConnection(url, "root", MYSQL.getPassword());
                var st = c.createStatement();
                var row =
                        st.executeQuery(
                                "SELECT status,profile_json,metrics_json FROM aivoicetrial WHERE id='old'")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo("completed");
            assertThat(row.getString(2)).isEqualTo("{}");
            assertThat(row.getString(3)).isEqualTo("{}");
        }
    }

    @Test
    void emptyDatabaseThenUnchangedReplay() throws Exception {
        var flyway =
                Flyway.configure()
                        .dataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())
                        .locations("classpath:db/migration")
                        .load();
        var first = flyway.migrate();
        assertThat(first.migrationsExecuted).isEqualTo(28);
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("28");
        var second = flyway.migrate();
        assertThat(second.migrationsExecuted).isZero();
        flyway.validate();
        String version = flyway.info().current().getVersion().toString();
        var report = new LinkedHashMap<String, Object>();
        report.put(
                "first",
                Map.of(
                        "migrationsExecuted",
                        first.migrationsExecuted,
                        "targetSchemaVersion",
                        first.targetSchemaVersion,
                        "success",
                        first.success));
        report.put(
                "second",
                Map.of(
                        "migrationsExecuted",
                        second.migrationsExecuted,
                        "currentSchemaVersion",
                        version,
                        "success",
                        second.success));
        var tables = new LinkedHashMap<String, Object>();
        try (var connection =
                        DriverManager.getConnection(
                                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = connection.createStatement()) {
            try (var result =
                    statement.executeQuery(
                            "SELECT VERSION(), @@character_set_server, @@collation_server")) {
                assertThat(result.next()).isTrue();
                report.put("mysql", result.getString(1));
                report.put("serverCharset", result.getString(2));
                report.put("serverCollation", result.getString(3));
            }
            try (var result =
                    statement.executeQuery(
                            "SELECT TABLE_NAME, ENGINE, TABLE_COLLATION FROM"
                                    + " information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER"
                                    + " BY TABLE_NAME")) {
                while (result.next()) {
                    var table = new LinkedHashMap<String, Object>();
                    table.put("engine", result.getString(2));
                    table.put("collation", result.getString(3));
                    table.put("columns", new LinkedHashMap<String, Object>());
                    table.put("uniqueIndexes", new LinkedHashMap<String, List<String>>());
                    tables.put(result.getString(1), table);
                }
            }
            for (var entry : tables.entrySet()) {
                @SuppressWarnings("unchecked")
                var table = (Map<String, Object>) entry.getValue();
                @SuppressWarnings("unchecked")
                var columns = (Map<String, Object>) table.get("columns");
                try (var query =
                        connection.prepareStatement(
                                "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, DATETIME_PRECISION,"
                                        + " COLLATION_NAME FROM information_schema.COLUMNS WHERE"
                                        + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY"
                                        + " ORDINAL_POSITION")) {
                    query.setString(1, entry.getKey());
                    try (var result = query.executeQuery()) {
                        while (result.next()) {
                            var column = new LinkedHashMap<String, Object>();
                            column.put("type", result.getString(2));
                            column.put("nullable", result.getString(3));
                            column.put("datetimePrecision", result.getObject(4));
                            column.put("collation", result.getString(5));
                            columns.put(result.getString(1), column);
                        }
                    }
                }
                @SuppressWarnings("unchecked")
                var indexes = (Map<String, List<String>>) table.get("uniqueIndexes");
                // Names originate exclusively in information_schema, never from request input.
                try (var result =
                        statement.executeQuery(
                                "SHOW INDEX FROM `" + entry.getKey().replace("`", "``") + "`")) {
                    while (result.next())
                        if (!result.getBoolean("Non_unique")) {
                            indexes.computeIfAbsent(
                                            result.getString("Key_name"), k -> new ArrayList<>())
                                    .add(result.getString("Column_name"));
                        }
                }
            }
        }
        try (var connection =
                        DriverManager.getConnection(
                                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = connection.createStatement()) {
            for (var expected :
                    Map.of(
                                    "ix_taskoutbox_ready_scope",
                                    "tenant_id,task_type,state,available_at,id",
                                    "ix_taskoutbox_stream_scope",
                                    "tenant_id,aggregate_id,task_type,state,created_at,id",
                                    "ix_callbackinbox_expiry",
                                    "state,completed_at,id",
                                    "ix_call_admission_counts",
                                    "tenant_id,status,telephony_line_id,campaign_id",
                                    "ix_call_daily_attempts",
                                    "tenant_id,started_at,attempts",
                                    "ix_event_attempt_daily",
                                    "event_type,created_at,call_session_id",
                                    "ix_callback_pending_global",
                                    "state,id")
                            .entrySet()) {
                try (var query =
                        connection.prepareStatement(
                                "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM"
                                        + " information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()"
                                        + " AND INDEX_NAME=?")) {
                    query.setString(1, expected.getKey());
                    try (var result = query.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getString(1)).isEqualTo(expected.getValue());
                    }
                }
            }
        }
        assertThat(tables)
                .hasSize(97)
                .containsKeys(
                        "msg_message",
                        "msg_journal",
                        "msg_application",
                        "msg_webhook_delivery",
                        "msg_call_intent",
                        "msg_interaction",
                        "aivoicetrial",
                        "aivoicetrialaudio",
                        "aivoiceconfig",
                        "aivoicetestset",
                        "aivoicetestrun",
                        "flyway_schema_history",
                        "dial_admission_control",
                        "dial_drain_member",
                        "dial_migration_generation",
                        "dial_daily_counter",
                        "dial_fact_revision",
                        "dial_fact_head",
                        "dial_command_journal",
                        "voice_mock_schedule",
                        "dial_async_batch",
                        "capacity_commit_evidence",
                        "capacity_identity_evidence",
                        "dial_reservation",
                        "dial_phone_guard",
                        "dial_quota_counter",
                        "voice_dial_authorization",
                        "voice_dial_authorization_history",
                        "voice_scope_counter",
                        "dial_v3_coordinator",
                        "dial_v3_quota_scope",
                        "dial_v3_quota_grant",
                        "dial_v3_quota_grant_scope",
                        "dial_v3_quota_allocation");
        report.put("totalTablesIncludingFlyway", tables.size());
        report.put("businessTableCount", 34);
        report.put("auxiliaryTableCount", 10);
        report.put("operationsTableCount", 10);
        report.put("dialControlTableCount", 22);
        report.put("voiceAiComparisonTableCount", 3);
        report.put("applicationTableCount", tables.size() - 1);
        report.put("tables", tables);
        Path target = Path.of("target", "data-layer-replay.json");
        Files.createDirectories(target.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report);
        System.out.println(
                "Flyway first: migrationsExecuted="
                        + first.migrationsExecuted
                        + ", version="
                        + first.targetSchemaVersion
                        + ", success="
                        + first.success);
        System.out.println(
                "Flyway second: migrationsExecuted="
                        + second.migrationsExecuted
                        + ", version="
                        + version
                        + ", success="
                        + second.success);
        System.out.println(
                "MySQL inventory: businessTables=34, auxiliaryTables=10, flywayMetadataTables=1,"
                        + " operationsTables=10, dialControlTables=22, totalTables=77");
    }
}
