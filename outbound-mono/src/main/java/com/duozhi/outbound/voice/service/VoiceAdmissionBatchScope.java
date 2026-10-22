package com.duozhi.outbound.voice.service;

import com.duozhi.outbound.integration.dao.LocalLedger;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.voice.controller.VoiceController;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Read-your-writes projections inside one serialized ledger transaction only. Recent one-second
 * counts may overcount expired rows within this <=100 cohort; they never undercount. Scope/day/hour
 * keys prevent boundary reuse.
 */
public final class VoiceAdmissionBatchScope implements AutoCloseable {
    private static final ThreadLocal<VoiceAdmissionBatchScope> CURRENT = new ThreadLocal<>();
    private final VoiceAdmissionBatchScope previous = CURRENT.get();
    private final Map<String, Long> values = new HashMap<>();
    private final Map<String, Map<String, Object>> compact = new HashMap<>();
    private final List<Map.Entry<String, Long>> pending = new ArrayList<>();
    private Connection connection;
    private boolean preloaded;
    private final Map<String, Map<String, Object>> attempts = new HashMap<>();
    private final Map<String, Map<String, Object>> authorizations = new HashMap<>();
    private final Set<String> nonces = new HashSet<>();
    private final List<Object[]> pendingNonces = new ArrayList<>();
    private final List<Object[]> newAuthorizations = new ArrayList<>();
    private final List<String> unansweredSchedules = new ArrayList<>();
    private Map<String, Object> control;
    private Double oldestCallback;

    public VoiceAdmissionBatchScope() {
        CURRENT.set(this);
    }

    public static VoiceAdmissionBatchScope current() {
        return CURRENT.get();
    }

    public void preload(LocalLedger ledger, List<VoiceController.Command> commands) {
        var ids = new LinkedHashSet<String>();
        for (var command : commands) {
            var payload = Support.object(command.body());
            ids.add(Support.required(payload, "call_id", 128));
        }
        ledger.read(
                db -> {
                    bind(db);
                    control(db);
                    String slots = String.join(",", Collections.nCopies(ids.size(), "?"));
                    var rows =
                            LocalLedger.query(
                                    db,
                                    "SELECT * FROM attempts WHERE call_id IN (" + slots + ")",
                                    ids.toArray());
                    for (var row : rows) remember(row);
                    for (var row :
                            LocalLedger.query(
                                    db,
                                    "SELECT * FROM voice_dial_authorization WHERE call_id IN ("
                                            + slots
                                            + ") ORDER BY tenant_id,call_id,attempt FOR UPDATE",
                                    ids.toArray())) authorized(row);
                    var permits =
                            commands.stream()
                                    .map(
                                            command ->
                                                    command.headers()
                                                            .getOrDefault("x-voice-nonce", ""))
                                    .filter(nonce -> nonce.matches("[a-zA-Z0-9_-]{20,100}"))
                                    .distinct()
                                    .toList();
                    if (!permits.isEmpty())
                        for (var row :
                                LocalLedger.query(
                                        db,
                                        "SELECT nonce FROM nonces WHERE nonce IN ("
                                                + String.join(
                                                        ",",
                                                        Collections.nCopies(permits.size(), "?"))
                                                + ")",
                                        permits.toArray())) nonces.add(row.get("nonce").toString());
                    return null;
                });
        preloaded = true;
    }

    public boolean claimNonce(Connection db, String nonce, double expires) {
        bind(db);
        if (!preloaded)
            throw new IllegalStateException("nonce projection requires serialized preloading");
        if (!nonces.add(nonce)) return false;
        pendingNonces.add(new Object[] {nonce, expires});
        return true;
    }

    void newAuthorization(Connection db, Object[] values) {
        bind(db);
        newAuthorizations.add(values);
    }

    void unanswered(String uuid) {
        unansweredSchedules.add(uuid);
    }

    void flushAuthorizations(Connection db) throws SQLException {
        bind(db);
        LocalLedger.executeBatch(
                db,
                "INSERT INTO"
                        + " voice_dial_authorization(tenant_id,call_id,attempt,registration_id,epoch,owner,fence,expires_at,semantic_digest,state)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,'INSTALLED')",
                newAuthorizations);
        newAuthorizations.clear();
    }

    public void flush(LocalLedger ledger) {
        ledger.transaction(
                db -> {
                    bind(db);
                    LocalLedger.executeBatch(
                            db, "INSERT INTO nonces(nonce,expires) VALUES(?,?)", pendingNonces);
                    flushAuthorizations(db);
                    if (!unansweredSchedules.isEmpty()
                            && LocalLedger.update(
                                            db,
                                            "UPDATE voice_mock_schedule SET"
                                                    + " phase='NO_ANSWER',due=end_at WHERE uuid IN ("
                                                    + String.join(
                                                            ",",
                                                            Collections.nCopies(
                                                                    unansweredSchedules.size(),
                                                                    "?"))
                                                    + ") AND phase='ANSWERED'",
                                            unansweredSchedules.toArray())
                                    != unansweredSchedules.size())
                        throw new Support.Failure(503, "synthetic schedule lost original outcome");
                    pendingNonces.clear();
                    unansweredSchedules.clear();
                    return null;
                });
    }

    private void bind(Connection db) {
        if (connection == null) connection = db;
        if (connection != db)
            throw new IllegalStateException(
                    "voice admission facts crossed transaction connections");
    }

    public boolean preloaded() {
        return preloaded;
    }

    public Map<String, Object> prior(Connection db, String call, long attempt) {
        bind(db);
        return attempts.get(call + ":" + attempt);
    }

    public void remember(Map<String, Object> row) {
        attempts.put(row.get("call_id") + ":" + row.get("attempt"), row);
    }

    Map<String, Object> authorization(Connection db, long tenant, String call, long attempt) {
        bind(db);
        return authorizations.get(tenant + ":" + call + ":" + attempt);
    }

    void authorized(Map<String, Object> row) {
        authorizations.put(
                row.get("tenant_id") + ":" + row.get("call_id") + ":" + row.get("attempt"), row);
    }

    public long activeFor(Connection db, String call, long tenant) {
        bind(db);
        return attempts.values().stream()
                .filter(
                        row ->
                                call.equals(row.get("call_id"))
                                        && (!"ended".equals(row.get("state"))
                                                || ((Number) row.get("tenant")).longValue()
                                                        != tenant))
                .count();
    }

    Map<String, Object> control(Connection db) throws SQLException {
        bind(db);
        if (control == null)
            control =
                    LocalLedger.query(
                                    db,
                                    "SELECT phase,epoch,admission_mode FROM dial_admission_control"
                                            + " WHERE id=1 FOR SHARE")
                            .get(0);
        return control;
    }

    public double oldestCallback(Connection db) throws SQLException {
        bind(db);
        if (oldestCallback == null) {
            Object oldest =
                    LocalLedger.query(db, "SELECT MIN(created) AS oldest FROM outbox")
                            .get(0)
                            .get("oldest");
            oldestCallback = oldest instanceof Number n ? n.doubleValue() : 0d;
        }
        return oldestCallback;
    }

    void noteCallback(double created) {
        if (oldestCallback != null && (oldestCallback == 0 || created < oldestCallback))
            oldestCallback = created;
    }

    public void begin() {
        pending.clear();
    }

    public long count(Connection db, String key, String sql, Object... args) throws Exception {
        bind(db);
        Long value = values.get(key);
        if (value == null) {
            value = LocalLedger.scalar(db, sql, args);
            values.put(key, value);
        }
        return value;
    }

    public Map<String, Object> compact(Connection db, String where, List<Object> args, double now)
            throws Exception {
        bind(db);
        String key = where + args + ":" + (long) Math.floor(now / 3600);
        var result = compact.get(key);
        if (result == null) {
            result = VoiceScopeFacts.read(db, where, args, now);
            compact.put(key, result);
        }
        return result;
    }

    public long projected(String key, long initial) {
        return values.computeIfAbsent(key, k -> initial);
    }

    public void add(String key, long amount) {
        pending.add(Map.entry(key, amount));
    }

    public void accepted() {
        for (var delta : pending)
            values.compute(
                    delta.getKey(),
                    (key, value) -> Math.addExact(Objects.requireNonNull(value), delta.getValue()));
        pending.clear();
    }

    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
