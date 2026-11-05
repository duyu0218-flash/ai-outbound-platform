package com.duozhi.outbound.common.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.duozhi.outbound.common.exception.ApiError;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class RuntimeOwnershipIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    Connection connect() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }

    @Test
    void independentSessionsContendAndKilledOwnerFreezesWithoutAutomaticResume() throws Exception {
        var terminated = new AtomicInteger();
        var firstConnection = connect();
        long id;
        try (var query = firstConnection.createStatement();
                var rows = query.executeQuery("SELECT CONNECTION_ID()")) {
            rows.next();
            id = rows.getLong(1);
        }
        try (var first =
                new RuntimeOwnership(firstConnection, "it", terminated::incrementAndGet, false)) {
            var competing = connect();
            assertThrows(
                    IllegalStateException.class,
                    () -> new RuntimeOwnership(competing, "it", () -> {}, false));
            assertTrue(competing.isClosed());
            first.requireRunning();
            try (var independentDeployment =
                    new RuntimeOwnership(connect(), "other", () -> {}, false)) {
                independentDeployment.requireRunning();
            }
            try (var admin = connect();
                    var kill = admin.createStatement()) {
                kill.execute("KILL " + id);
            }
            first.check();
            assertEquals(RuntimeOwnership.State.FROZEN, first.state());
            assertEquals(1, terminated.get());
            assertThrows(ApiError.class, first::requireRunning);
            first.check();
            assertEquals(1, terminated.get());
            try (var replacement = new RuntimeOwnership(connect(), "it", () -> {}, false)) {
                replacement.requireRunning();
                assertThrows(ApiError.class, first::requireRunning);
            }
        }
        try (var restarted = new RuntimeOwnership(connect(), "it", () -> {}, false)) {
            restarted.requireRunning();
        }
    }

    Process child() throws Exception {
        var process =
                new ProcessBuilder(
                        System.getProperty("java.home") + "/bin/java",
                        "-cp",
                        System.getProperty(
                                "surefire.test.class.path", System.getProperty("java.class.path")),
                        RuntimeGuardProcess.class.getName());
        process.environment().put("GUARD_TEST_JDBC", MYSQL.getJdbcUrl());
        process.environment().put("GUARD_TEST_USER", "root");
        process.environment().put("GUARD_TEST_PASSWORD", MYSQL.getPassword());
        return process.redirectErrorStream(true).start();
    }

    @Test
    void secondJvmCannotStartAndLostSessionTerminatesOriginalJvm() throws Exception {
        var first = child();
        try (var reader = first.inputReader();
                var waits = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            String held =
                    waits.submit(() -> reader.readLine())
                            .get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(held.startsWith("HELD "), held);
            var second = child();
            try {
                assertTrue(second.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                assertNotEquals(0, second.exitValue());
                assertTrue(
                        new String(
                                        second.getInputStream().readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8)
                                .contains("another writer owns this deployment"));
            } finally {
                second.destroyForcibly();
            }
            long id = Long.parseLong(held.substring(5));
            try (var admin = connect();
                    var kill = admin.createStatement()) {
                kill.execute("KILL " + id);
            }
            assertTrue(first.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(78, first.exitValue());
        } finally {
            first.destroyForcibly();
        }
    }
}
