package com.duozhi.outbound.voice.service;

import com.duozhi.outbound.integration.dao.LocalLedger;
import com.duozhi.outbound.integration.support.Support;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Automatic ESL protection is independent of the durable manual/compliance stop. */
public final class EslEventProtection {
    public enum State {
        NORMAL,
        DEGRADED,
        PAUSED,
        RECOVERING
    }

    private final LocalLedger ledger;
    private final Set<String> quarantined = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<long[]> outcomes = new ArrayDeque<>();
    private volatile State state;
    private long lastFailure, nextAdmission;
    private int healthyWindows;
    private final java.util.function.LongSupplier clock;

    public EslEventProtection(LocalLedger ledger) {
        this(ledger, System::nanoTime);
    }

    EslEventProtection(LocalLedger ledger, java.util.function.LongSupplier clock) {
        this.ledger = ledger;
        this.clock = clock;
        state =
                ledger.read(
                        db -> {
                            for (var row :
                                    LocalLedger.query(
                                            db,
                                            "SELECT DISTINCT uuid FROM voice_event_quarantine WHERE state='RECONCILE_REQUIRED'"))
                                quarantined.add(row.get("uuid").toString());
                            var saved =
                                    LocalLedger.query(
                                            db,
                                            "SELECT value FROM flags WHERE `key`='esl_protection'");
                            return saved.isEmpty()
                                    ? State.NORMAL
                                    : State.valueOf(saved.get(0).get("value").toString());
                        });
        if (state != State.NORMAL) lastFailure = clock.getAsLong();
    }

    public State state() {
        return state;
    }

    public synchronized boolean allowAdmission() {
        if (state == State.PAUSED) return false;
        if (state != State.RECOVERING) return true;
        long now = clock.getAsLong();
        if (now < nextAdmission) return false;
        nextAdmission = now + 1_000_000_000L;
        return true;
    }

    private void transition(State next) {
        if (state == next) return;
        // Freeze first; persistence failures must not leave admission open.
        boolean opening = next == State.NORMAL || next == State.RECOVERING;
        if (!opening) state = next;
        ledger.transaction(
                db -> {
                    LocalLedger.execute(
                            db,
                            "INSERT INTO flags (`key`,value) VALUES ('esl_protection',?)"
                                    + " ON DUPLICATE KEY UPDATE value=VALUES(value)",
                            next.name());
                    LocalLedger.execute(
                            db,
                            "INSERT INTO audit(at,action,detail) VALUES (?,'esl_protection',?)",
                            LocalLedger.now(),
                            next.name());
                    return null;
                });
        if (opening) state = next;
    }

    public synchronized void pause() {
        lastFailure = clock.getAsLong();
        healthyWindows = 0;
        transition(State.PAUSED);
    }

    static String uuid(Map<String, Object> event) {
        return Support.str(
                event,
                "Unique-ID",
                Support.str(
                        event,
                        "Channel-Call-UUID",
                        Support.str(event, "variable_origination_uuid", "")));
    }

    private static Map<String, Object> identity(Map<String, Object> event) {
        var safe = new LinkedHashMap<String, Object>();
        for (String name :
                List.of(
                        "Event-Name",
                        "Event-UUID",
                        "Core-UUID",
                        "Event-Sequence",
                        "Event-Date-Timestamp",
                        "Unique-ID",
                        "Channel-Call-UUID",
                        "variable_origination_uuid",
                        "Job-UUID"))
            if (event.get(name) instanceof String text && text.length() <= 128)
                safe.put(name, text);
        return safe;
    }

    public boolean held(Map<String, Object> event) {
        if (!quarantined.contains(uuid(event))) return false;
        quarantine(event, "FOLLOWING_QUARANTINED_EVENT");
        return true;
    }

    /**
     * Stores identity only, never a body, auth header, number or transcript. No permits are freed.
     */
    public void quarantine(Map<String, Object> event, String errorType) {
        String uuid = uuid(event);
        if (uuid.length() > 128) {
            pause();
            return;
        }
        var identity = identity(event);
        boolean stable =
                identity.containsKey("Event-UUID")
                        || identity.containsKey("Core-UUID")
                                && identity.containsKey("Event-Sequence")
                                && identity.containsKey("Event-Date-Timestamp");
        String key =
                stable
                        ? Support.hex(Support.sha(Support.canonical(identity)))
                        : Support.hex(
                                Support.sha(
                                        UUID.randomUUID()
                                                .toString()
                                                .getBytes(StandardCharsets.UTF_8)));
        try {
            boolean located =
                    ledger.transaction(
                            db -> {
                                var rows =
                                        LocalLedger.query(
                                                db,
                                                "SELECT tenant,call_id,attempt FROM attempts WHERE uuid=?",
                                                uuid);
                                var call = rows.isEmpty() ? Map.<String, Object>of() : rows.get(0);
                                String name = Support.str(event, "Event-Name", "");
                                LocalLedger.execute(
                                        db,
                                        "INSERT IGNORE INTO voice_event_quarantine"
                                                + "(event_identity,uuid,event_name,tenant_id,call_id,attempt,error_type,identity_json)"
                                                + " VALUES(?,?,?,?,?,?,?,?)",
                                        key,
                                        uuid,
                                        name.substring(0, Math.min(64, name.length())),
                                        call.get("tenant"),
                                        call.get("call_id"),
                                        call.get("attempt"),
                                        errorType,
                                        new String(Support.json(identity), StandardCharsets.UTF_8));
                                return !rows.isEmpty();
                            });
            quarantined.add(uuid);
            if (!located || !stable) pause();
        } catch (RuntimeException failed) {
            quarantined.add(uuid);
            pause();
            throw failed;
        }
    }

    public synchronized void outcome(boolean success) {
        long now = clock.getAsLong();
        outcomes.addLast(new long[] {now, success ? 0 : 1});
        while (!outcomes.isEmpty()
                && (outcomes.size() > 1000 || now - outcomes.peekFirst()[0] > 30_000_000_000L))
            outcomes.removeFirst();
        if (!success) {
            lastFailure = now;
            healthyWindows = 0;
            if (state == State.NORMAL) transition(State.DEGRADED);
        }
        if (outcomes.size() >= 20
                && outcomes.stream().mapToLong(v -> v[1]).sum() * 5 >= outcomes.size()) pause();
    }

    /** Healthy windows cannot clear manual stop or mark quarantined events processed. */
    public synchronized void healthy(boolean healthy, int backlog) {
        if (state == State.NORMAL) return;
        if (!healthy || backlog >= 128 || clock.getAsLong() - lastFailure < 10_000_000_000L) {
            healthyWindows = 0;
            return;
        }
        if (++healthyWindows < 5) return;
        healthyWindows = 0;
        transition(state == State.PAUSED ? State.RECOVERING : State.NORMAL);
    }
}
