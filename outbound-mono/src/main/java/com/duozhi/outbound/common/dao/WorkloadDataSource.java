package com.duozhi.outbound.common.dao;

import com.alibaba.druid.pool.*;
import java.sql.SQLException;

/**
 * Druid pools share one database and one Spring transaction resource key. The executing lane
 * selects a pool before a transaction borrows its connection; nested work keeps that already-bound
 * connection and cannot split a commit.
 */
public final class WorkloadDataSource extends DruidDataSource {
    public enum Lane {
        DEFAULT,
        REALTIME,
        ANALYSIS
    }

    private static final ThreadLocal<Lane> CURRENT = new ThreadLocal<>();
    private final DruidDataSource realtime, analysis;

    public WorkloadDataSource(DruidDataSource realtime, DruidDataSource analysis) {
        this.realtime = realtime;
        this.analysis = analysis;
    }

    public static Scope use(Lane lane) {
        return new Scope(lane);
    }

    public static final class Scope implements AutoCloseable {
        private final Lane previous = CURRENT.get();

        Scope(Lane lane) {
            CURRENT.set(lane);
        }

        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    @Override
    public DruidPooledConnection getConnection() throws SQLException {
        Lane lane = CURRENT.get();
        if (lane == Lane.REALTIME) return realtime.getConnection();
        if (lane == Lane.ANALYSIS) return analysis.getConnection();
        return super.getConnection();
    }
}
