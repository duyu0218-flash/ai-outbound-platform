package com.duozhi.outbound.common.runtime;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.config.JdbcBudgets;
import com.duozhi.outbound.common.exception.ApiError;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.HexFormat;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

/** A MySQL session lock prevents accidental second writers on this database instance. */
@Component
public final class RuntimeOwnership implements AutoCloseable {
    public enum State {
        DISABLED,
        RUNNING,
        FROZEN,
        CLOSED
    }

    private volatile State state = State.FROZEN;
    private final Connection connection;
    private final String lockName;
    private final long connectionId;
    private final Runnable terminate;
    private final ScheduledExecutorService monitor;

    @Autowired
    public RuntimeOwnership(Config config, DataSourceProperties properties) throws Exception {
        this(
                open(config, properties),
                config.get("JAVA_DEPLOYMENT_ID", "outbound-mono"),
                () -> Runtime.getRuntime().halt(78),
                true);
    }

    private static Connection open(Config config, DataSourceProperties properties)
            throws SQLException {
        boolean enabled = config.flag("JAVA_SINGLE_INSTANCE_GUARD_ENABLED", config.production());
        if (config.production() && !enabled)
            throw new IllegalStateException("production requires the single-instance guard");
        if (!enabled) return null;
        return DriverManager.getConnection(
                JdbcBudgets.boundedUrl(properties.determineUrl()),
                properties.determineUsername(),
                properties.determinePassword());
    }

    // Tests inject termination, and can drive checks without a background timer.
    RuntimeOwnership(
            Connection connection, String deployment, Runnable terminate, boolean automatic)
            throws Exception {
        this.connection = connection;
        this.terminate = terminate;
        monitor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            var thread = new Thread(r, "runtime-ownership");
                            thread.setDaemon(true);
                            return thread;
                        });
        if (connection == null) {
            lockName = "";
            connectionId = 0;
            state = State.DISABLED;
            return;
        }
        try {
            if (!deployment.matches("[A-Za-z0-9_.-]{1,64}"))
                throw new IllegalArgumentException("invalid deployment identity");
            connection.setNetworkTimeout(Runnable::run, 2000);
            String catalog = connection.getCatalog();
            if (catalog == null || catalog.isBlank())
                throw new IllegalStateException("database required");
            lockName =
                    "outbound:"
                            + HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(
                                                            (catalog + ":" + deployment)
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8)))
                                    .substring(0, 48);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(2);
                try (var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    rows.next();
                    connectionId = rows.getLong(1);
                }
            }
            try (var statement = connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
                statement.setQueryTimeout(2);
                statement.setString(1, lockName);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 1 || rows.wasNull())
                        throw new IllegalStateException("another writer owns this deployment");
                }
            }
            state = State.RUNNING;
            if (automatic) monitor.scheduleWithFixedDelay(this::check, 1, 1, TimeUnit.SECONDS);
        } catch (Exception failed) {
            monitor.shutdownNow();
            try {
                connection.close();
            } catch (SQLException closeFailed) {
                failed.addSuppressed(closeFailed);
            }
            throw failed;
        }
    }

    public State state() {
        return state;
    }

    public void requireRunning() {
        if (state != State.RUNNING && state != State.DISABLED)
            throw new ApiError(503, "RUNTIME_OWNERSHIP_FROZEN");
    }

    synchronized void check() {
        if (state != State.RUNNING) return;
        try (var statement = connection.prepareStatement("SELECT IS_USED_LOCK(?)")) {
            statement.setQueryTimeout(2);
            statement.setString(1, lockName);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != connectionId || rows.wasNull())
                    throw new SQLException("runtime ownership lost");
            }
        } catch (SQLException | RuntimeException lost) {
            state = State.FROZEN;
            monitor.shutdownNow();
            LoggerFactory.getLogger(getClass())
                    .error("Runtime ownership lost; frozen, terminating process");
            terminate.run();
        }
    }

    @PreDestroy
    public synchronized void close() {
        state = State.CLOSED;
        monitor.shutdownNow();
        if (connection != null)
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
    }
}
