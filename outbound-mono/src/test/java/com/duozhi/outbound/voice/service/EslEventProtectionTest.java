package com.duozhi.outbound.voice.service;

import static org.junit.jupiter.api.Assertions.*;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.dao.LocalLedger;
import com.duozhi.outbound.integration.service.LedgerTestDatabase;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class EslEventProtectionTest {
    LocalLedger ledger() throws Exception {
        return new LocalLedger(new Settings(LedgerTestDatabase.settings(Map.of())));
    }

    Map<String, Object> event(String uuid, int sequence) {
        return Map.of(
                "Event-Name",
                "CHANNEL_HANGUP",
                "Unique-ID",
                uuid,
                "Core-UUID",
                "pbx",
                "Event-Sequence",
                "" + sequence,
                "Event-Date-Timestamp",
                "123456",
                "Caller-Caller-ID-Number",
                "must-not-store",
                "Authorization",
                "must-not-store");
    }

    @Test
    void isolatesKnownCallDeduplicatesAndRetainsSubsequentOrderAndResources() throws Exception {
        try (var ledger = ledger()) {
            ledger.transaction(
                    db -> {
                        LocalLedger.execute(
                                db,
                                "INSERT INTO attempts(call_id,attempt,tenant,`line`,uuid,digest,payload,created,deadline,cost,rate,multiplier,state)"
                                        + " VALUES ('call',1,1,'line','uuid','digest','{}',0,9999999999,100,10,1,'pending')");
                        return null;
                    });
            var protection = new EslEventProtection(ledger);
            protection.quarantine(event("uuid", 1), "FixtureFailure");
            protection.outcome(false);
            protection.quarantine(event("uuid", 1), "FixtureFailure");
            assertEquals(EslEventProtection.State.DEGRADED, protection.state());
            assertTrue(protection.allowAdmission());
            assertTrue(protection.held(event("uuid", 2)));
            assertFalse(protection.held(event("other", 3)));
            assertEquals(
                    2L,
                    (long)
                            ledger.read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM voice_event_quarantine")));
            assertEquals(
                    1L,
                    (long)
                            ledger.read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM attempts WHERE state='pending' AND cost=100")));
            var saved =
                    ledger.read(
                            db ->
                                    LocalLedger.query(
                                            db,
                                            "SELECT identity_json FROM voice_event_quarantine ORDER BY id"));
            assertFalse(saved.toString().contains("must-not-store"));
            var restarted = new EslEventProtection(ledger);
            assertTrue(restarted.held(event("uuid", 3)));
            assertEquals(
                    3L,
                    (long)
                            ledger.read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM voice_event_quarantine")));
        }
    }

    @Test
    void rateWindowRequiresSamplesAndRecoveryNeverClearsManualStop() throws Exception {
        try (var ledger = ledger()) {
            var now = new AtomicLong(1);
            var protection = new EslEventProtection(ledger, now::get);
            ledger.stopped(true, "fixture operator stop");
            for (int i = 0; i < 19; i++) protection.outcome(false);
            assertEquals(EslEventProtection.State.DEGRADED, protection.state());
            protection.outcome(false);
            assertEquals(EslEventProtection.State.PAUSED, protection.state());
            assertFalse(protection.allowAdmission());
            now.addAndGet(11_000_000_000L);
            for (int i = 0; i < 5; i++) protection.healthy(true, 128);
            assertEquals(EslEventProtection.State.PAUSED, protection.state());
            for (int i = 0; i < 5; i++) protection.healthy(true, 0);
            assertEquals(EslEventProtection.State.RECOVERING, protection.state());
            assertTrue(protection.allowAdmission());
            assertFalse(protection.allowAdmission());
            for (int i = 0; i < 5; i++) protection.healthy(true, 0);
            assertEquals(EslEventProtection.State.NORMAL, protection.state());
            assertEquals(true, ledger.summary().get("stopped"));
        }
    }

    @Test
    void unknownEventOrFailedPersistenceClosesNewAdmissions() throws Exception {
        try (var ledger = ledger()) {
            var protection = new EslEventProtection(ledger);
            protection.quarantine(event("unknown", 1), "FixtureFailure");
            assertEquals(EslEventProtection.State.PAUSED, protection.state());
            assertFalse(protection.allowAdmission());
            ledger.transaction(
                    db -> {
                        LocalLedger.execute(db, "DROP TABLE voice_event_quarantine");
                        return null;
                    });
            assertThrows(
                    RuntimeException.class,
                    () -> protection.quarantine(event("other", 1), "FixtureFailure"));
            assertFalse(protection.allowAdmission());
        }
    }
}
