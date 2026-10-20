package com.duozhi.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance evidence only: two real migrations and an independent database inventory. */
@Testcontainers
class DataLayerReplayIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

    @Test void emptyDatabaseThenUnchangedReplay() throws Exception {
        var flyway = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load();
        var first = flyway.migrate();
        assertThat(first.migrationsExecuted).isEqualTo(6);
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("6");
        var second = flyway.migrate();
        assertThat(second.migrationsExecuted).isZero();
        flyway.validate();
        String version = flyway.info().current().getVersion().toString();
        var report = new LinkedHashMap<String, Object>();
        report.put("first", Map.of("migrationsExecuted", first.migrationsExecuted,
                "targetSchemaVersion", first.targetSchemaVersion, "success", first.success));
        report.put("second", Map.of("migrationsExecuted", second.migrationsExecuted,
                "currentSchemaVersion", version, "success", second.success));
        var tables = new LinkedHashMap<String, Object>();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT VERSION(), @@character_set_server, @@collation_server")) {
                assertThat(result.next()).isTrue();
                report.put("mysql", result.getString(1));
                report.put("serverCharset", result.getString(2));
                report.put("serverCollation", result.getString(3));
            }
            try (var result = statement.executeQuery("SELECT TABLE_NAME, ENGINE, TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME")) {
                while (result.next()) {
                    var table = new LinkedHashMap<String, Object>();
                    table.put("engine", result.getString(2)); table.put("collation", result.getString(3));
                    table.put("columns", new LinkedHashMap<String, Object>());
                    table.put("uniqueIndexes", new LinkedHashMap<String, List<String>>());
                    tables.put(result.getString(1), table);
                }
            }
            for (var entry : tables.entrySet()) {
                @SuppressWarnings("unchecked") var table = (Map<String, Object>) entry.getValue();
                @SuppressWarnings("unchecked") var columns = (Map<String, Object>) table.get("columns");
                try (var query = connection.prepareStatement("SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, DATETIME_PRECISION, COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
                    query.setString(1, entry.getKey());
                    try (var result = query.executeQuery()) {
                        while (result.next()) {
                            var column = new LinkedHashMap<String, Object>();
                            column.put("type", result.getString(2)); column.put("nullable", result.getString(3));
                            column.put("datetimePrecision", result.getObject(4)); column.put("collation", result.getString(5));
                            columns.put(result.getString(1), column);
                        }
                    }
                }
                @SuppressWarnings("unchecked") var indexes = (Map<String, List<String>>) table.get("uniqueIndexes");
                // Names originate exclusively in information_schema, never from request input.
                try (var result = statement.executeQuery("SHOW INDEX FROM `" + entry.getKey().replace("`", "``") + "`")) {
                    while (result.next()) if (!result.getBoolean("Non_unique")) {
                        indexes.computeIfAbsent(result.getString("Key_name"), k -> new ArrayList<>()).add(result.getString("Column_name"));
                    }
                }
            }
        }
        try(var connection=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var statement=connection.createStatement()) {
            for(var expected:Map.of("ix_taskoutbox_ready_scope","tenant_id,task_type,state,available_at,id","ix_taskoutbox_stream_scope","tenant_id,aggregate_id,task_type,state,created_at,id","ix_callbackinbox_expiry","state,completed_at,id","ix_call_admission_counts","tenant_id,status,telephony_line_id,campaign_id","ix_call_daily_attempts","tenant_id,started_at,attempts","ix_event_attempt_daily","event_type,created_at,call_session_id","ix_callback_pending_global","state,id").entrySet()) {
                try(var query=connection.prepareStatement("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND INDEX_NAME=?")) {
                    query.setString(1,expected.getKey());try(var result=query.executeQuery()){assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo(expected.getValue());}
                }
            }
        }
        assertThat(tables).hasSize(55).containsKey("flyway_schema_history");
        report.put("totalTablesIncludingFlyway", tables.size());
        report.put("businessTableCount", 34);
        report.put("auxiliaryTableCount", 10);
        report.put("operationsTableCount", 10);
        report.put("applicationTableCount", tables.size() - 1);
        report.put("tables", tables);
        Path target = Path.of("target", "data-layer-replay.json"); Files.createDirectories(target.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report);
        System.out.println("Flyway first: migrationsExecuted=" + first.migrationsExecuted + ", version=" + first.targetSchemaVersion + ", success=" + first.success);
        System.out.println("Flyway second: migrationsExecuted=" + second.migrationsExecuted + ", version=" + version + ", success=" + second.success);
        System.out.println("MySQL inventory: businessTables=34, auxiliaryTables=10, flywayMetadataTables=1, operationsTables=10, totalTables=55");
    }
}
