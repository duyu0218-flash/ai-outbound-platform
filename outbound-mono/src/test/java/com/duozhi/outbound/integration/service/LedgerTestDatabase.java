package com.duozhi.outbound.integration.service;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.testcontainers.mysql.MySQLContainer;

/** Every test gets a fresh real server schema; no in-memory database fallback. */
public final class LedgerTestDatabase {
    private static final class Server {
        static final MySQLContainer MYSQL = start();

        static MySQLContainer start() {
            var c = new MySQLContainer("mysql:8.4");
            c.start();
            return c;
        }
    }

    public static Map<String, String> settings(Map<String, String> input) {
        try {
            var mysql = Server.MYSQL;
            String name = "ledger_" + UUID.randomUUID().toString().replace("-", "");
            String root = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/";
            try (var c =
                            DriverManager.getConnection(
                                    root,
                                    mysql.getUsername().equals("root")
                                            ? mysql.getUsername()
                                            : "root",
                                    mysql.getPassword());
                    var q = c.createStatement()) {
                q.execute(
                        "CREATE DATABASE `"
                                + name
                                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
                q.execute("GRANT ALL ON `" + name + "`.* TO '" + mysql.getUsername() + "'@'%'");
            }
            String url = root + name;
            Flyway.configure()
                    .dataSource(url, "root", mysql.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            var result = new HashMap<>(input);
            result.put("MYSQL_JDBC_URL", url);
            result.put("MYSQL_USER", mysql.getUsername());
            result.put("MYSQL_PASSWORD", mysql.getPassword());
            return result;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Real ledger test database initialization failed", error);
        }
    }
}
