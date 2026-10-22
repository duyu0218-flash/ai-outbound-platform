package com.duozhi.outbound.dial.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.job.worker.TaskWorker;
import java.util.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * All call writers project resource changes in the same MySQL transaction. lock_compat keeps the
 * existing platform mutex until the full writer/fence gate.
 */
@Service
@DependsOnDatabaseInitialization
public class DialQuotaCounters implements InitializingBean {
    private final JdbcTemplate jdbc;
    private final Transactions tx;
    private final Config config;
    private final Store store;

    public DialQuotaCounters(JdbcTemplate jdbc, Transactions tx, Config config, Store store) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.config = config;
        this.store = store;
    }

    public void afterPropertiesSet() {
        tx.serialized(
                () -> {
                    var control =
                            jdbc.queryForMap(
                                    "SELECT"
                                            + " platform_limit,tenant_default_limit,enforce_quotas,admission_mode"
                                            + " FROM dial_admission_control WHERE id=1 FOR UPDATE");
                    long platform = config.number("OUTBOUND_PLATFORM_MAX_CONCURRENT", 20),
                            tenant = config.number("MAX_CONCURRENT_CALLS", 20);
                    if (platform < 1 || platform > 100000 || tenant < 1 || tenant > 100000)
                        throw new IllegalArgumentException("dial quota configuration out of range");
                    String requested = config.get("JAVA_DIAL_ADMISSION_MODE", "lock_compat");
                    if (!Set.of("lock_compat", "db").contains(requested)
                            || !requested.equals(control.get("admission_mode")))
                        throw new IllegalStateException(
                                "admission mode must be installed through the persistent"
                                        + " maintenance barrier");
                    boolean changed =
                            ((Number) control.get("platform_limit")).longValue() != platform
                                    || ((Number) control.get("tenant_default_limit")).longValue()
                                            != tenant;
                    if (changed
                            && jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM dial_reservation WHERE"
                                                    + " state='RESERVED'",
                                            Long.class)
                                    != 0)
                        throw new IllegalStateException(
                                "quota defaults cannot change while durable reservations are"
                                        + " active");
                    jdbc.update(
                            "UPDATE dial_admission_control SET"
                                    + " platform_limit=?,tenant_default_limit=? WHERE id=1",
                            platform,
                            tenant);
                    return null;
                });
    }

    Long active(Long tenant, Object line, Object campaign) {
        var control =
                jdbc.queryForMap(
                        "SELECT enforce_quotas,generation_id FROM dial_admission_control WHERE"
                                + " id=1");
        if (!truth(control.get("enforce_quotas"))) return null;
        if (!(control.get("generation_id") instanceof Number gen)
                || jdbc.queryForObject(
                                "SELECT COUNT(*) FROM dial_migration_generation WHERE id=? AND"
                                        + " state='READY'",
                                Long.class,
                                gen.longValue())
                        != 1) throw new TaskWorker.Deferred();
        String type =
                tenant == null
                        ? "platform"
                        : line != null ? "line" : campaign != null ? "campaign" : "tenant";
        String id =
                tenant == null
                        ? "0"
                        : line != null
                                ? tenant + ":" + line
                                : campaign != null ? tenant + ":" + campaign : tenant.toString();
        var rows =
                jdbc.queryForList(
                        "SELECT active FROM dial_quota_counter WHERE generation_id=? AND"
                                + " scope_type=? AND scope_id=?",
                        gen.longValue(),
                        type,
                        id);
        // Missing concurrency keys fall back to the exact locked legacy query. A
        // daily key never uses this rule: its real historical L/E must be built.
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("active")).longValue();
    }

    private static boolean truth(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0;
    }

    /**
     * PolicyWrite holds admission's compatibility lock before its transaction. Existing resources
     * must fit a proposed lower limit; tenant FOR SHARE alone is not an exclusion protocol for
     * policy changes.
     */
    public void assertLimit(long tenant, Object line, Object campaign, long limit) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("policy limit change requires a transaction");
        tx.tenant(tenant);
        String type = line != null ? "line" : campaign != null ? "campaign" : "tenant";
        String id =
                line != null
                        ? tenant + ":" + line
                        : campaign != null ? tenant + ":" + campaign : Long.toString(tenant);
        long calls =
                store.scalar(
                        "callsession",
                        "activeDialCount",
                        JsonSupport.map("tenantId", tenant, "line", line, "campaign", campaign));
        long reserved =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(active),0) FROM dial_quota_counter WHERE scope_type=?"
                                + " AND scope_id=?",
                        Long.class,
                        type,
                        id);
        if (limit < Math.max(calls, reserved))
            throw new ApiError(409, "concurrency limit is below existing resource occupancy");
    }

    public Map<String, Object> snapshot() {
        return Map.of(
                "reserved",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM dial_reservation WHERE state='RESERVED'", Long.class),
                "guards",
                jdbc.queryForObject("SELECT COUNT(*) FROM dial_phone_guard", Long.class),
                "counters",
                jdbc.queryForList(
                        "SELECT generation_id,scope_type,scope_id,active,hard_limit,revision FROM"
                                + " dial_quota_counter ORDER BY generation_id,scope_type,scope_id"));
    }
}
