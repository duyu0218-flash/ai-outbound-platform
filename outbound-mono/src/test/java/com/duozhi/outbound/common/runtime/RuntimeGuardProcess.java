package com.duozhi.outbound.common.runtime;

import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;

/**
 * Isolated child JVM fixture. Its database has no phone numbers, provider or production secrets.
 */
public final class RuntimeGuardProcess {
    public static void main(String[] args) throws Exception {
        var connection =
                DriverManager.getConnection(
                        System.getenv("GUARD_TEST_JDBC"),
                        System.getenv("GUARD_TEST_USER"),
                        System.getenv("GUARD_TEST_PASSWORD"));
        try (var ownership =
                new RuntimeOwnership(
                        connection, "process-it", () -> Runtime.getRuntime().halt(78), true)) {
            long id;
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                rows.next();
                id = rows.getLong(1);
            }
            System.out.println("HELD " + id);
            System.out.flush();
            new CountDownLatch(1).await();
        }
    }
}
