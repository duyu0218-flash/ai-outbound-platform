package com.duozhi.outbound.marketing.dataset;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.marketing.MarketingTestBase;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MatchFlightMigrationIT extends MarketingTestBase {
    @Test
    void upgradeProtectsAlreadyChargedPendingAndUncertainRequests() throws Exception {
        String schema = "match_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url =
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema;
        try (var admin =
                        DriverManager.getConnection(
                                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                var statement = admin.createStatement()) {
            statement.execute(
                    "CREATE DATABASE "
                            + schema
                            + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
            try {
                Flyway.configure()
                        .dataSource(url, "root", MYSQL.getPassword())
                        .locations("classpath:db/migration")
                        .target("34")
                        .load()
                        .migrate();
                try (var conn = DriverManager.getConnection(url, "root", MYSQL.getPassword());
                        var sql = conn.createStatement()) {
                    sql.executeUpdate(
                            "INSERT INTO mk_partner(id,tenant_id,code,name,adapter,product,config) VALUES('p',1,'legacy','legacy','ant_das','intention','{}')");
                    for (int i = 1; i <= 3; i++) {
                        sql.executeUpdate(
                                "INSERT INTO mk_match_job(id,tenant_id,partner_id,adapter,product,state) VALUES('j"
                                        + i
                                        + "',1,'p','ant_das','intention','running')");
                        String hash = (i == 3 ? "b" : "a").repeat(64),
                                state = i == 1 ? "pending" : i == 2 ? "failed" : "uncertain";
                        sql.executeUpdate(
                                "INSERT INTO mk_match_request(id,tenant_id,job_id,position,phone_hash,phone_cipher,phone_mask,state,charged,sent_at) VALUES('r"
                                        + i
                                        + "',1,'j"
                                        + i
                                        + "',1,'"
                                        + hash
                                        + "','synthetic-cipher','134****0000','"
                                        + state
                                        + "',TRUE,NOW(6))");
                    }
                }
                var flyway =
                        Flyway.configure()
                                .dataSource(url, "root", MYSQL.getPassword())
                                .locations("classpath:db/migration")
                                .load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                try (var conn = DriverManager.getConnection(url, "root", MYSQL.getPassword());
                        var sql = conn.createStatement();
                        var rows =
                                sql.executeQuery(
                                        "SELECT owner_request_id,state FROM mk_match_flight ORDER BY phone_hash")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("r1");
                    assertThat(rows.getString(2)).isEqualTo("pending");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("r3");
                    assertThat(rows.getString(2)).isEqualTo("uncertain");
                    assertThat(rows.next()).isFalse();
                }
            } finally {
                statement.execute("DROP DATABASE " + schema);
            }
        }
    }
}
