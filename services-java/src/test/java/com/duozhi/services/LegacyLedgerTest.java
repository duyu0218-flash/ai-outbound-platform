package com.duozhi.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyLedgerTest {
    @TempDir Path directory;
    private String path() { return directory.resolve("old-python.sqlite").toString(); }
    private void seed(boolean valid) throws Exception {
        // Historical Python schemas before stream_key and command call/attempt were introduced.
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path())) {
            LocalLedger.plain(db, "CREATE TABLE outbox(id TEXT PRIMARY KEY,url TEXT NOT NULL,body BLOB NOT NULL,created REAL NOT NULL,due REAL NOT NULL,failures INTEGER NOT NULL DEFAULT 0)");
            LocalLedger.plain(db, "CREATE TABLE voice_action_commands(id TEXT PRIMARY KEY,digest TEXT NOT NULL,result TEXT)");
            LocalLedger.execute(db, "INSERT INTO voice_action_commands VALUES (?,?,NULL)", "unknown-operation", "persisted-digest");
            LocalLedger.execute(db, "INSERT INTO outbox VALUES (?,?,?,?,?,?)", "event-1", "http://retained/status", (valid ? "{\"call_id\":\"中文-call\",\"sequence\":1}" : "malformed").getBytes(StandardCharsets.UTF_8), 10, 20, 4);
            LocalLedger.execute(db, "INSERT INTO outbox VALUES (?,?,?,?,?,?)", "event-2", "http://retained/status", "{\"call_id\":\"中文-call\",\"sequence\":2}".getBytes(StandardCharsets.UTF_8), 11, 20, 0);
        }
    }
    @Test void upgradesHistoricalPythonQueueAndCommandsWithoutDroppingUnknownOutcomes() throws Exception {
        seed(true);
        for (int restart = 0; restart < 2; restart++) try (var ledger = new LocalLedger(path())) {
            var rows = ledger.read(db -> LocalLedger.query(db, "SELECT * FROM outbox ORDER BY created"));
            assertEquals(List.of("中文-call", "中文-call"), rows.stream().map(r -> r.get("stream_key")).toList());
            assertEquals(4, ((Number)rows.get(0).get("failures")).intValue());
            assertEquals(1, Support.integer(Support.object((byte[])rows.get(0).get("body")), "sequence", 0));
            var command = ledger.read(db -> LocalLedger.query(db, "SELECT * FROM voice_action_commands")).get(0);
            assertNull(command.get("result")); assertEquals("persisted-digest", command.get("digest"));
            assertEquals("", command.get("call_id")); assertEquals(0, ((Number)command.get("attempt")).intValue());
        }
    }
    @Test void malformedLegacyQueueAbortsUpgradeAtomicallyAndRetainsOriginalBytes() throws Exception {
        seed(false); assertThrows(Exception.class, () -> new LocalLedger(path()));
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path())) {
            assertTrue(LocalLedger.query(db, "PRAGMA table_info(outbox)").stream().noneMatch(c -> "stream_key".equals(c.get("name"))));
            assertEquals("malformed", new String((byte[])LocalLedger.query(db, "SELECT body FROM outbox WHERE id='event-1'").get(0).get("body"), StandardCharsets.UTF_8));
            assertEquals(2L, LocalLedger.scalar(db, "SELECT COUNT(*) FROM outbox"));
        }
    }
    @Test void actualPythonCreatedLedgerKeepsChineseDigestsUnknownPermitsAndQueuedBytes() throws Exception {
        var expected = Support.object(getClass().getResourceAsStream("/legacy-python-ledger.json").readAllBytes());
        String fixture = new String(getClass().getResourceAsStream("/legacy-python-ledger.sql").readAllBytes(), StandardCharsets.UTF_8);
        // This checked-in synthetic sqlite iterdump contains no semicolons inside string values.
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path())) {
            for (String statement : fixture.split(";")) if (!statement.isBlank()) LocalLedger.plain(db, statement);
        }
        var config = new Settings(Map.of("VOICE_GATEWAY_DRIVER", "mock", "VOICE_CALLBACK_BASE_URL", "http://127.0.0.1:18000",
            "VOICE_SECURITY_ROUTES_JSON", new String(Support.json(Map.of("1:0", expected.get("route"))), StandardCharsets.UTF_8),
            "VOICE_QUOTA_ENABLED", "true", "VOICE_QUOTA_BUDGETS_JSON", new String(Support.json(expected.get("budgets")), StandardCharsets.UTF_8)));
        try (var ledger = new LocalLedger(path())) {
            var security = new VoiceSecurity(config, ledger); var payload = Support.map(expected.get("payload"));
            var retry = security.admit(payload, security.policy(payload), true, 10);
            assertFalse(retry.fresh()); assertEquals(expected.get("provider_call_id"), retry.row().get("uuid"));
            assertEquals(expected.get("digest"), retry.row().get("digest")); assertEquals(Support.integer(expected, "reserved_cost", 0), Support.integer(retry.row(), "cost", 0));
            assertEquals(1, Support.integer(Support.map(new VoiceQuota(config, ledger).snapshot().get("asr")), "unknown", 0));
            var command = ledger.read(db -> LocalLedger.query(db, "SELECT * FROM voice_action_commands WHERE id='legacy-unknown-command'")).get(0);
            assertNull(command.get("result"));
            var outgoing = Map.of("call_id", payload.get("call_id"), "expected_attempt", 1, "text", "中文播报");
            assertEquals(Support.hex(Support.sha(Support.canonicalAscii(List.of("speak", outgoing)))), command.get("digest"));
            var queued = ledger.read(db -> LocalLedger.query(db, "SELECT * FROM outbox")).get(0);
            assertEquals(payload.get("call_id"), queued.get("stream_key"));
            assertEquals(payload.get("call_id"), Support.object((byte[])queued.get("body")).get("call_id"));
        }
    }
}
