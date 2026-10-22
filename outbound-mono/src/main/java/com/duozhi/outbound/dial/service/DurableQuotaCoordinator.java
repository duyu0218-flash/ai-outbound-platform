package com.duozhi.outbound.dial.service;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V3 S1 durable concurrent-resource protocol. Not yet an admission mode. All authority is in MySQL,
 * not reports or a JVM lock. Expiry stops consumption, never releases a call. consume/release
 * callbacks must only persist facts using this transaction's datasource: they must not perform
 * network I/O. Lock order: coordinator, grant, allocation, sorted resource scopes. Existing
 * admission remains on lock_compat until the whole writer/fence gate.
 */
@Service
public class DurableQuotaCoordinator {
    public record Lease(String owner, long epoch) {
        public Lease {
            identifier(owner, 160);
            if (epoch < 1) throw new IllegalArgumentException("invalid coordinator epoch");
        }
    }

    public record GrantRequest(
            String id,
            String grantee,
            long granteeEpoch,
            List<String> scopes,
            long units,
            Instant notBefore,
            Instant validUntil) {
        public GrantRequest {
            identifier(id, 128);
            identifier(grantee, 160);
            if (granteeEpoch < 1 || units < 1 || units > 1_000_000)
                throw new IllegalArgumentException("invalid grant size or owner epoch");
            Objects.requireNonNull(scopes);
            Objects.requireNonNull(notBefore);
            Objects.requireNonNull(validUntil);
            if (scopes.isEmpty() || scopes.size() > 32)
                throw new IllegalArgumentException("bounded nonempty scope tuple required");
            var sorted = new TreeSet<String>();
            for (String scope : scopes) {
                scopeKey(scope);
                if (!sorted.add(scope))
                    throw new IllegalArgumentException("duplicate resource scope");
            }
            scopes = List.copyOf(sorted);
            if (!notBefore.isBefore(validUntil)
                    || Duration.between(notBefore, validUntil).compareTo(Duration.ofMinutes(1)) > 0
                    || notBefore.getNano() % 1000 != 0
                    || validUntil.getNano() % 1000 != 0)
                throw new IllegalArgumentException("bounded microsecond grant window required");
        }
    }

    public record Grant(String id, long issued, long consumed, String state, Instant validUntil) {}

    public record Allocation(String id, String dialKey, String grantId, String state) {}

    public record ScopeBalance(String scope, long hardLimit, long held, long unused, long active) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public DurableQuotaCoordinator(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        // Return only after our commit, including when called by another service.
        // Callbacks join this transaction via the same JdbcTemplate/datasource.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
    }

    private <T> T run(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    public Lease acquire(String owner, long expectedEpoch, Duration duration) {
        identifier(owner, 160);
        leaseDuration(duration);
        if (expectedEpoch < 0)
            throw new IllegalArgumentException("invalid expected coordinator epoch");
        return run(
                () -> {
                    var row = coordinator(true);
                    var now = now();
                    if (number(row, "epoch") != expectedEpoch
                            || row.get("lease_until") != null
                                    && instant(row.get("lease_until")).isAfter(now))
                        throw conflict("coordinator lease is held or epoch changed");
                    long epoch = Math.addExact(expectedEpoch, 1);
                    jdbc.update(
                            "UPDATE dial_v3_coordinator SET"
                                    + " owner=?,epoch=?,lease_until=?,state='RECOVERING' WHERE id=1",
                            owner,
                            epoch,
                            stamp(now.plus(duration)));
                    return new Lease(owner, epoch);
                });
    }

    public void renew(Lease lease, Duration duration) {
        leaseDuration(duration);
        run(
                () -> {
                    authority(lease, false, true);
                    jdbc.update(
                            "UPDATE dial_v3_coordinator SET lease_until=? WHERE id=1",
                            stamp(now().plus(duration)));
                    return null;
                });
    }

    /**
     * Recovery cannot reclaim a missing report. Every open grant and active K must balance before
     * READY; a mismatch leaves the leader RECOVERING.
     */
    public List<ScopeBalance> recover(Lease lease) {
        return run(
                () -> {
                    authority(lease, false, true);
                    var balances = balances();
                    if (jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM dial_v3_quota_grant g WHERE"
                                            + " g.state='ALLOCATING' OR g.consumed<>(SELECT COUNT(*)"
                                            + " FROM dial_v3_quota_allocation a WHERE"
                                            + " a.grant_id=g.grant_id) OR NOT EXISTS(SELECT 1 FROM"
                                            + " dial_v3_quota_grant_scope s WHERE"
                                            + " s.grant_id=g.grant_id)",
                                    Long.class)
                            != 0) throw unavailable("grant identity conservation failed");
                    for (var b : balances)
                        if (b.held() != Math.addExact(b.unused(), b.active())
                                || b.held() > b.hardLimit())
                            throw unavailable("quota conservation failed for " + b.scope());
                    authority(lease, false, true);
                    jdbc.update("UPDATE dial_v3_coordinator SET state='READY' WHERE id=1");
                    return balances;
                });
    }

    public void configure(Lease lease, String scope, long limit) {
        scopeKey(scope);
        if (limit < 0 || limit > 1_000_000_000)
            throw new IllegalArgumentException("invalid concurrent hard limit");
        run(
                () -> {
                    authority(lease, false, false);
                    jdbc.update(
                            "INSERT INTO dial_v3_quota_scope(scope_key,hard_limit) VALUES(?,?) ON"
                                    + " DUPLICATE KEY UPDATE scope_key=scope_key",
                            scope,
                            limit);
                    var row =
                            jdbc.queryForMap(
                                    "SELECT held FROM dial_v3_quota_scope WHERE scope_key=? FOR"
                                            + " UPDATE",
                                    scope);
                    if (number(row, "held") > limit)
                        throw conflict("limit is below durable occupancy");
                    jdbc.update(
                            "UPDATE dial_v3_quota_scope SET hard_limit=?,revision=revision+1 WHERE"
                                    + " scope_key=?",
                            limit,
                            scope);
                    authority(lease, false, false);
                    return null;
                });
    }

    public Grant issue(Lease lease, GrantRequest request) {
        Objects.requireNonNull(request);
        return run(
                () -> {
                    authority(lease, true, false);
                    String digest = digest(request);
                    jdbc.update(
                            "INSERT INTO"
                                    + " dial_v3_quota_grant(grant_id,request_digest,coordinator_epoch,grantee,grantee_epoch,issued,not_before,valid_until,state)"
                                    + " VALUES(?,?,?,?,?,?,?,?,'ALLOCATING') ON DUPLICATE KEY UPDATE"
                                    + " grant_id=grant_id",
                            request.id(),
                            digest,
                            lease.epoch(),
                            request.grantee(),
                            request.granteeEpoch(),
                            request.units(),
                            stamp(request.notBefore()),
                            stamp(request.validUntil()));
                    var row = grant(request.id());
                    if (!digest.equals(row.get("request_digest")))
                        throw conflict("grant identity has different immutable semantics");
                    if (!"ALLOCATING".equals(row.get("state"))) return view(row);
                    if (!now().isBefore(request.validUntil()))
                        throw conflict("grant window has expired");
                    for (String scope : request.scopes()) {
                        var rows =
                                jdbc.queryForList(
                                        "SELECT held,hard_limit FROM dial_v3_quota_scope WHERE"
                                                + " scope_key=? FOR UPDATE",
                                        scope);
                        if (rows.size() != 1) throw conflict("resource scope is not configured");
                        var balance = rows.get(0);
                        if (request.units()
                                > number(balance, "hard_limit") - number(balance, "held"))
                            throw conflict("concurrent quota exhausted");
                        jdbc.update(
                                "UPDATE dial_v3_quota_scope SET held=held+?,revision=revision+1"
                                        + " WHERE scope_key=?",
                                request.units(),
                                scope);
                        jdbc.update(
                                "INSERT INTO dial_v3_quota_grant_scope(grant_id,scope_key)"
                                        + " VALUES(?,?)",
                                request.id(),
                                scope);
                    }
                    jdbc.update(
                            "UPDATE dial_v3_quota_grant SET state='OPEN' WHERE grant_id=?",
                            request.id());
                    authority(lease, true, false);
                    return view(grant(request.id()));
                });
    }

    /**
     * allocationId is a reservation identity, dialKey is the immutable K. A proved rejection may
     * create a new allocation for the same K after release. The database still permits only one
     * ACTIVE allocation for that K.
     */
    public Allocation consume(
            Lease lease,
            String grantId,
            String grantee,
            long granteeEpoch,
            String allocationId,
            String dialKey,
            Runnable persistAdmission) {
        identifier(grantId, 128);
        identifier(grantee, 160);
        identifier(allocationId, 128);
        identifier(dialKey, 256);
        if (granteeEpoch < 1) throw new IllegalArgumentException("invalid grantee epoch");
        Objects.requireNonNull(persistAdmission);
        return run(
                () -> {
                    authority(lease, true, false);
                    var g = grant(grantId);
                    if (!grantee.equals(g.get("grantee"))
                            || granteeEpoch != number(g, "grantee_epoch"))
                        throw conflict("grant owner is stale");
                    // The grant row already serializes replays for this grant. Avoid
                    // locking a missing allocation's index gap across independent grants.
                    var prior =
                            jdbc.queryForList(
                                    "SELECT * FROM dial_v3_quota_allocation WHERE allocation_id=?",
                                    allocationId);
                    if (!prior.isEmpty()) {
                        var a =
                                jdbc.queryForMap(
                                        "SELECT * FROM dial_v3_quota_allocation WHERE"
                                                + " allocation_id=? FOR UPDATE",
                                        allocationId);
                        if (!grantId.equals(a.get("grant_id"))
                                || !dialKey.equals(a.get("dial_key")))
                            throw conflict("allocation identity changed");
                        return allocation(a);
                    }
                    var current = now();
                    if (!"OPEN".equals(g.get("state"))
                            || current.isBefore(instant(g.get("not_before")))
                            || !current.isBefore(instant(g.get("valid_until"))))
                        throw conflict("grant is closed or outside its consumption window");
                    if (number(g, "consumed") >= number(g, "issued"))
                        throw conflict("grant has no remaining units");
                    jdbc.update(
                            "INSERT INTO"
                                    + " dial_v3_quota_allocation(allocation_id,dial_key,grant_id,state)"
                                    + " VALUES(?,?,?,'ACTIVE')",
                            allocationId,
                            dialKey,
                            grantId);
                    jdbc.update(
                            "UPDATE dial_v3_quota_grant SET consumed=consumed+1 WHERE grant_id=?",
                            grantId);
                    persistAdmission.run();
                    authority(lease, true, false);
                    // Time may expire while persisting admission; do not commit a late consumption.
                    if (!now().isBefore(instant(g.get("valid_until"))))
                        throw conflict("grant expired during admission");
                    return new Allocation(allocationId, dialKey, grantId, "ACTIVE");
                });
    }

    /**
     * Closes the common durable consumption authority before returning ONLY unused units.
     * Live/unknown allocations are retained regardless of TTL.
     */
    public long close(Lease lease, String grantId) {
        identifier(grantId, 128);
        return run(
                () -> {
                    authority(lease, true, false);
                    var g = grant(grantId);
                    if ("CLOSED".equals(g.get("state"))) return 0L;
                    if (!"OPEN".equals(g.get("state"))) throw unavailable("grant is not committed");
                    long unused = number(g, "issued") - number(g, "consumed");
                    changeHeld(grantId, -unused);
                    jdbc.update(
                            "UPDATE dial_v3_quota_grant SET"
                                    + " state='CLOSED',closed_at=UTC_TIMESTAMP(6) WHERE grant_id=?",
                            grantId);
                    authority(lease, true, false);
                    return unused;
                });
    }

    /**
     * Called only after the caller verifies a terminal/no-send proof. Persist that proof and
     * terminal state in persistTerminal, atomically with release. This method is never exposed as
     * an unauthenticated release endpoint.
     */
    public boolean release(
            Lease lease, String allocationId, String proofId, Runnable persistTerminal) {
        identifier(allocationId, 128);
        identifier(proofId, 192);
        Objects.requireNonNull(persistTerminal);
        return run(
                () -> {
                    authority(lease, true, false);
                    var found =
                            jdbc.queryForList(
                                    "SELECT grant_id FROM dial_v3_quota_allocation WHERE"
                                            + " allocation_id=?",
                                    allocationId);
                    if (found.size() != 1) throw conflict("allocation does not exist");
                    String grantId = found.get(0).get("grant_id").toString();
                    grant(grantId);
                    var a =
                            jdbc.queryForMap(
                                    "SELECT * FROM dial_v3_quota_allocation WHERE allocation_id=?"
                                            + " FOR UPDATE",
                                    allocationId);
                    if ("RELEASED".equals(a.get("state"))) {
                        if (!proofId.equals(a.get("release_proof")))
                            throw conflict("release proof identity changed");
                        return false;
                    }
                    persistTerminal.run();
                    changeHeld(grantId, -1);
                    jdbc.update(
                            "UPDATE dial_v3_quota_allocation SET"
                                    + " state='RELEASED',release_proof=?,released_at=UTC_TIMESTAMP(6)"
                                    + " WHERE allocation_id=?",
                            proofId,
                            allocationId);
                    authority(lease, true, false);
                    return true;
                });
    }

    public List<ScopeBalance> inspect(Lease lease) {
        return run(
                () -> {
                    authority(lease, false, true);
                    return balances();
                });
    }

    private List<ScopeBalance> balances() {
        var rows =
                jdbc.queryForList(
                        "SELECT scope_key,hard_limit,held FROM dial_v3_quota_scope ORDER BY"
                                + " scope_key FOR UPDATE");
        var out = new ArrayList<ScopeBalance>();
        for (var row : rows) {
            String scope = row.get("scope_key").toString();
            long unused =
                    jdbc.queryForObject(
                            "SELECT COALESCE(SUM(g.issued-g.consumed),0) FROM"
                                    + " dial_v3_quota_grant_scope s JOIN dial_v3_quota_grant g ON"
                                    + " g.grant_id=s.grant_id WHERE s.scope_key=? AND g.state='OPEN'",
                            Long.class,
                            scope);
            long active =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM dial_v3_quota_grant_scope s JOIN"
                                    + " dial_v3_quota_allocation a ON a.grant_id=s.grant_id WHERE"
                                    + " s.scope_key=? AND a.state='ACTIVE'",
                            Long.class,
                            scope);
            out.add(
                    new ScopeBalance(
                            scope, number(row, "hard_limit"), number(row, "held"), unused, active));
        }
        return List.copyOf(out);
    }

    private void changeHeld(String grantId, long delta) {
        var scopes =
                jdbc.queryForList(
                        "SELECT scope_key FROM dial_v3_quota_grant_scope WHERE grant_id=? ORDER BY"
                                + " scope_key",
                        String.class,
                        grantId);
        if (scopes.isEmpty()) throw unavailable("grant scope manifest is missing");
        for (String scope : scopes) {
            jdbc.queryForMap(
                    "SELECT held FROM dial_v3_quota_scope WHERE scope_key=? FOR UPDATE", scope);
            if (jdbc.update(
                            "UPDATE dial_v3_quota_scope SET held=held+?,revision=revision+1 WHERE"
                                    + " scope_key=? AND held+? BETWEEN 0 AND hard_limit",
                            delta,
                            scope,
                            delta)
                    != 1) throw unavailable("quota release conservation failed");
        }
    }

    private Map<String, Object> coordinator(boolean exclusive) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM dial_v3_coordinator WHERE id=1 "
                                + (exclusive ? "FOR UPDATE" : "FOR SHARE"));
        if (rows.size() != 1) throw unavailable("coordinator authority is missing");
        return rows.get(0);
    }

    private void authority(Lease lease, boolean ready, boolean exclusive) {
        Objects.requireNonNull(lease);
        var row = coordinator(exclusive);
        if (!lease.owner().equals(row.get("owner"))
                || lease.epoch() != number(row, "epoch")
                || row.get("lease_until") == null
                || !now().isBefore(instant(row.get("lease_until"))))
            throw conflict("coordinator authority is stale");
        if (ready && !"READY".equals(row.get("state")))
            throw unavailable("coordinator recovery is incomplete");
    }

    private Map<String, Object> grant(String id) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM dial_v3_quota_grant WHERE grant_id=? FOR UPDATE", id);
        if (rows.size() != 1) throw conflict("grant does not exist");
        return rows.get(0);
    }

    private Grant view(Map<String, Object> row) {
        return new Grant(
                row.get("grant_id").toString(),
                number(row, "issued"),
                number(row, "consumed"),
                row.get("state").toString(),
                instant(row.get("valid_until")));
    }

    private Allocation allocation(Map<String, Object> row) {
        return new Allocation(
                row.get("allocation_id").toString(),
                row.get("dial_key").toString(),
                row.get("grant_id").toString(),
                row.get("state").toString());
    }

    private Instant now() {
        return jdbc.queryForObject(
                "SELECT UTC_TIMESTAMP(6)",
                (rs, n) -> rs.getTimestamp(1).toLocalDateTime().toInstant(ZoneOffset.UTC));
    }

    private static Timestamp stamp(Instant value) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    private static Instant instant(Object value) {
        return value instanceof Timestamp t
                ? t.toLocalDateTime().toInstant(ZoneOffset.UTC)
                : ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }

    private static long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    private static String digest(GrantRequest r) {
        return JsonSupport.sha(
                String.join("\n", r.scopes())
                        + "\n"
                        + r.grantee()
                        + "\n"
                        + r.granteeEpoch()
                        + "\n"
                        + r.units()
                        + "\n"
                        + r.notBefore()
                        + "\n"
                        + r.validUntil());
    }

    private static void identifier(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || !value.matches("[A-Za-z0-9:_./-]+"))
            throw new IllegalArgumentException("invalid protocol identity");
    }

    private static void scopeKey(String value) {
        identifier(value, 192);
        if (!value.matches("(platform|tenant|line|campaign|gateway|ai):[A-Za-z0-9:_./-]+"))
            throw new IllegalArgumentException(
                    "concurrent resource scope required; CPS and daily policies use separate"
                            + " authorities");
    }

    private static void leaseDuration(Duration d) {
        if (d == null
                || d.compareTo(Duration.ofSeconds(1)) < 0
                || d.compareTo(Duration.ofMinutes(1)) > 0
                || d.getNano() % 1000 != 0)
            throw new IllegalArgumentException("bounded microsecond coordinator lease required");
    }

    private static ApiError conflict(String message) {
        return new ApiError(409, message);
    }

    private static ApiError unavailable(String message) {
        return new ApiError(503, message);
    }
}
