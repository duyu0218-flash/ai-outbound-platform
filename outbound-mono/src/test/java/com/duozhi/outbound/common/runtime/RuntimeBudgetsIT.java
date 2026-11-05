package com.duozhi.outbound.common.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.duozhi.outbound.common.config.JdbcBudgets;
import com.duozhi.outbound.common.dao.WorkloadDataSource;
import com.duozhi.outbound.common.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.*;
import java.sql.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class RuntimeBudgetsIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    DruidDataSource pool() {
        var pool = new DruidDataSource();
        pool.setUrl(JdbcBudgets.boundedUrl(MYSQL.getJdbcUrl()));
        pool.setUsername(MYSQL.getUsername());
        pool.setPassword(MYSQL.getPassword());
        pool.setMaxActive(2);
        pool.setMaxWait(150);
        pool.setConnectionInitSqls(java.util.List.of("SET SESSION innodb_lock_wait_timeout=1"));
        return pool;
    }

    @Test
    void rowLockTimeoutRollsBackPriorWritesAndPoolRecoversAfterExhaustion() throws Exception {
        try (var pool = pool()) {
            var jdbc = new JdbcTemplate(pool);
            jdbc.execute(
                    "CREATE TABLE runtime_budget_fixture(id INT PRIMARY KEY, value INT NOT NULL) ENGINE=InnoDB");
            jdbc.update("INSERT INTO runtime_budget_fixture VALUES(1,0),(2,0)");
            var tx =
                    new Transactions(
                            null,
                            new DataSourceTransactionManager(pool),
                            new JvmPlatformLock(new SimpleMeterRegistry()));
            try (var blocker = pool.getConnection()) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.createStatement()) {
                    statement.executeUpdate("UPDATE runtime_budget_fixture SET value=1 WHERE id=2");
                }
                assertThrows(
                        RuntimeException.class,
                        () ->
                                tx.run(
                                        () -> {
                                            jdbc.update(
                                                    "UPDATE runtime_budget_fixture SET value=99 WHERE id=1");
                                            jdbc.update(
                                                    "UPDATE runtime_budget_fixture SET value=99 WHERE id=2");
                                            return null;
                                        }));
                blocker.rollback();
            }
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT SUM(value) FROM runtime_budget_fixture", Integer.class));
            try (var first = pool.getConnection();
                    var second = pool.getConnection()) {
                long started = System.nanoTime();
                assertThrows(SQLException.class, pool::getConnection);
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
            }
            assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
        }
    }

    @Test
    void nestedRealtimeBudgetShortensOuterDeadlineAndCannotBeResetByReentry() {
        try (var pool = pool()) {
            var manager = new DataSourceTransactionManager(pool);
            var tx =
                    new Transactions(null, manager, new JvmPlatformLock(new SimpleMeterRegistry()));
            tx.run(
                    () -> {
                        var holder =
                                (ConnectionHolder)
                                        TransactionSynchronizationManager.getResource(pool);
                        assertTrue(holder.getTimeToLiveInMillis() > 5000);
                        try (var lane = WorkloadDataSource.use(WorkloadDataSource.Lane.REALTIME)) {
                            tx.run(
                                    () -> {
                                        assertTrue(holder.getTimeToLiveInMillis() <= 5000);
                                        holder.setTimeoutInMillis(100);
                                        return null;
                                    });
                            tx.run(
                                    () -> {
                                        assertTrue(holder.getTimeToLiveInMillis() <= 100);
                                        return null;
                                    });
                        }
                        return null;
                    });
        }
    }

    @Test
    void silentMysqlPeerCannotHoldAConnectionAttemptWithoutABound() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var callers = Executors.newFixedThreadPool(2)) {
            var accepted = callers.submit(() -> listener.accept());
            var connecting =
                    callers.submit(
                            () ->
                                    assertThrows(
                                            SQLException.class,
                                            () ->
                                                    DriverManager.getConnection(
                                                            JdbcBudgets.boundedUrl(
                                                                    "jdbc:mysql://127.0.0.1:"
                                                                            + listener
                                                                                    .getLocalPort()
                                                                            + "/fixture?connectTimeout=100&socketTimeout=150"),
                                                            "fixture",
                                                            "fixture")));
            try (var peer = accepted.get(2, TimeUnit.SECONDS)) {
                assertNotNull(connecting.get(3, TimeUnit.SECONDS));
            }
        }
    }
}
