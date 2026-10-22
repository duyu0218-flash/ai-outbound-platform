package com.duozhi.outbound.voice.service;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.dao.LocalLedger;
import com.duozhi.outbound.integration.service.LedgerTestDatabase;
import com.duozhi.outbound.integration.support.Support;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceMockExecutionTest {
    @TempDir Path directory;

    Settings settings() {
        return settings(Map.of());
    }

    Settings settings(Map<String, String> overrides) {
        return new Settings(settingsValues(overrides));
    }

    Map<String, String> settingsValues(Map<String, String> overrides) {
        var values =
                new HashMap<>(
                        Map.of(
                                "VOICE_GATEWAY_DRIVER",
                                "mock",
                                "VOICE_CALLBACK_BASE_URL",
                                "http://127.0.0.1:18000",
                                "FREESWITCH_RECORDING_DIR",
                                directory.toString(),
                                "VOICE_CPS",
                                "100",
                                "VOICE_MOCK_OCCUPANCY_SEC",
                                "20"));
        values.putAll(overrides);
        return LedgerTestDatabase.settings(values);
    }

    @Test
    void unansweredOutcomeAndFullLifetimeSurviveRestartWithDifferentConfiguration()
            throws Exception {
        var values = settingsValues(Map.of("VOICE_MOCK_ANSWER_PERCENT", "0"));
        var cfg = new Settings(values);
        var command = payload();
        Map<String, Object> accepted;
        try (var gateway = new VoiceGateway(cfg)) {
            accepted = gateway.post("dial", command);
            assertThat(accepted).containsEntry("mock_answered", false);
            var schedule =
                    gateway.ledger.read(
                            db ->
                                    LocalLedger.query(
                                                    db,
                                                    "SELECT s.*,a.created FROM voice_mock_schedule"
                                                            + " s JOIN attempts a ON a.uuid=s.uuid")
                                            .get(0));
            assertThat(schedule).containsEntry("phase", "NO_ANSWER");
            assertThat(((Number) schedule.get("due")).doubleValue())
                    .isEqualTo(((Number) schedule.get("end_at")).doubleValue());
            assertThat(
                            ((Number) schedule.get("end_at")).doubleValue()
                                    - ((Number) schedule.get("created")).doubleValue())
                    .isBetween(20.0, 22.0);
        }
        var changed = new HashMap<>(values);
        changed.put("VOICE_MOCK_ANSWER_PERCENT", "100");
        changed.put("VOICE_MOCK_DISTRIBUTION_SEED", "changed-after-acceptance");
        try (var recovered = new VoiceGateway(new Settings(changed))) {
            assertThat(recovered.post("dial", command)).isEqualTo(accepted);
            recovered.ledger.transaction(
                    db -> {
                        LocalLedger.execute(
                                db, "UPDATE voice_mock_schedule SET due=?", LocalLedger.now() - 1);
                        return null;
                    });
            recovered.mockTick();
            recovered.mockTick();
            assertThat(recovered.security.lookup("synthetic-v5", 1))
                    .containsEntry("send_state", "ENDED");
            var statuses =
                    recovered
                            .ledger
                            .read(
                                    db ->
                                            LocalLedger.query(
                                                    db,
                                                    "SELECT body FROM outbox ORDER BY"
                                                            + " enqueue_order"))
                            .stream()
                            .map(
                                    row ->
                                            Support.map(
                                                            Support.object((byte[]) row.get("body"))
                                                                    .get("payload"))
                                                    .get("status"))
                            .toList();
            assertThat(statuses).containsExactly("dialing", "no_answer");
            assertThat(
                            recovered.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM voice_mock_schedule")))
                    .isZero();
        }
    }

    @Test
    void twentyPercentDistributionIsStableForIdentityAndIncludesUnansweredCalls() {
        var answered = new HashSet<String>();
        var other = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            var command = new HashMap<>(payload());
            String id = "distribution-" + i;
            command.put("call_id", id);
            assertThat(VoiceGateway.simulatedAnswer(command, 0, "seed-a")).isFalse();
            assertThat(VoiceGateway.simulatedAnswer(command, 100, "seed-a")).isTrue();
            boolean decision = VoiceGateway.simulatedAnswer(command, 20, "seed-a");
            assertThat(VoiceGateway.simulatedAnswer(new LinkedHashMap<>(command), 20, "seed-a"))
                    .isEqualTo(decision);
            if (decision) answered.add(id);
            if (VoiceGateway.simulatedAnswer(command, 20, "seed-b")) other.add(id);
        }
        assertThat(answered.size()).isBetween(160, 240);
        assertThat(answered).isNotEqualTo(other);
    }

    @Test
    void terminalCohortRollsBackCallbacksAndCleanupTogetherAndKeepsOtherAttempts()
            throws Exception {
        var cfg = settings();
        try (var gateway = new VoiceGateway(cfg)) {
            var uuids = new ArrayList<String>();
            for (String call : List.of("bulk-terminal-a", "bulk-terminal-b")) {
                var command = new HashMap<>(payload());
                command.put("call_id", call);
                uuids.add(gateway.post("dial", command).get("provider_call_id").toString());
            }
            gateway.ledger.transaction(
                    db -> {
                        for (String call : List.of("bulk-terminal-a", "bulk-terminal-b")) {
                            for (int attempt : List.of(1, 2)) {
                                LocalLedger.execute(
                                        db,
                                        "INSERT INTO"
                                                + " voice_action_commands(id,call_id,attempt,digest,result)"
                                                + " VALUES(?,?,?,?,?)",
                                        call + ":" + attempt,
                                        call,
                                        attempt,
                                        "fixture",
                                        "{}");
                                LocalLedger.execute(
                                        db,
                                        "INSERT INTO"
                                                + " voice_permits(id,scope,kind,call_id,attempt,owner,state,created)"
                                                + " VALUES(?,?,?,?,?,?,?,?)",
                                        call + ":reserve:" + attempt,
                                        "fixture",
                                        "reserve",
                                        call,
                                        attempt,
                                        "fixture",
                                        "open",
                                        LocalLedger.now());
                            }
                            LocalLedger.execute(
                                    db,
                                    "INSERT INTO"
                                            + " voice_permits(id,scope,kind,call_id,attempt,owner,state,created)"
                                            + " VALUES(?,?,?,?,?,?,?,?)",
                                    call + ":asr",
                                    "fixture",
                                    "asr",
                                    call,
                                    1,
                                    "fixture",
                                    "unknown",
                                    LocalLedger.now());
                        }
                        LocalLedger.execute(
                                db,
                                "UPDATE voice_mock_schedule SET phase='COMPLETED',due=?",
                                LocalLedger.now() - 1);
                        return null;
                    });
            try (var admin =
                            java.sql.DriverManager.getConnection(
                                    cfg.get("MYSQL_JDBC_URL", ""),
                                    "root",
                                    cfg.secret("MYSQL_PASSWORD"));
                    var sql = admin.createStatement()) {
                sql.execute(
                        "CREATE TRIGGER fixture_completion_failure BEFORE UPDATE ON voice_attempts"
                                + " FOR EACH ROW BEGIN IF NEW.state='ended' AND NEW.uuid='"
                                + uuids.get(1)
                                + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture"
                                + " completion failure';END IF;END");
                try {
                    gateway.mockTick();
                    assertThat(
                                    gateway.ledger.<Long>read(
                                            db ->
                                                    LocalLedger.scalar(
                                                            db,
                                                            "SELECT COUNT(*) FROM attempts WHERE"
                                                                    + " state!='ended'")))
                            .isEqualTo(2);
                    assertThat(
                                    gateway.ledger.<Long>read(
                                            db ->
                                                    LocalLedger.scalar(
                                                            db,
                                                            "SELECT COUNT(*) FROM"
                                                                    + " voice_mock_schedule")))
                            .isEqualTo(2);
                    assertThat(
                                    gateway.ledger.<Long>read(
                                            db ->
                                                    LocalLedger.scalar(
                                                            db, "SELECT COUNT(*) FROM outbox")))
                            .isEqualTo(2);
                    assertThat(
                                    gateway.ledger.<Long>read(
                                            db ->
                                                    LocalLedger.scalar(
                                                            db,
                                                            "SELECT COUNT(*) FROM"
                                                                    + " voice_action_commands")))
                            .isEqualTo(4);
                } finally {
                    sql.execute("DROP TRIGGER fixture_completion_failure");
                }
            }
            gateway.mockTick();
            gateway.mockTick();
            assertThat(
                            gateway.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM attempts WHERE"
                                                            + " state='ended'")))
                    .isEqualTo(2);
            assertThat(
                            gateway.ledger.<Long>read(
                                    db -> LocalLedger.scalar(db, "SELECT COUNT(*) FROM outbox")))
                    .isEqualTo(4);
            assertThat(
                            gateway.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM voice_mock_schedule")))
                    .isZero();
            assertThat(
                            gateway.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM voice_action_commands"
                                                            + " WHERE attempt=2")))
                    .isEqualTo(2);
            assertThat(
                            gateway.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM voice_action_commands"
                                                            + " WHERE attempt=1")))
                    .isZero();
            assertThat(
                            gateway.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM voice_permits WHERE"
                                                            + " kind='reserve' AND attempt=1 AND"
                                                            + " state='closed'")))
                    .isEqualTo(2);
            assertThat(
                            gateway.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM voice_permits WHERE"
                                                            + " kind='reserve' AND attempt=2 AND"
                                                            + " state='open'")))
                    .isEqualTo(2);
            assertThat(
                            gateway.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM voice_permits WHERE"
                                                            + " kind='asr' AND state='unknown'")))
                    .isEqualTo(2);
            assertThat(
                            gateway.ledger.<Long>read(
                                    db -> LocalLedger.scalar(db, "SELECT SUM(cost) FROM attempts")))
                    .isEqualTo(4);
        }
    }

    Map<String, Object> payload() {
        return Map.of(
                "call_id",
                "synthetic-v5",
                "tenant_id",
                1,
                "phone",
                "+8613900000999",
                "caller_id",
                "",
                "webhook_url",
                "http://127.0.0.1:18000/api/v1/webhooks/telephony/status",
                "metadata",
                Map.of(
                        "tenant_id",
                        1,
                        "attempt",
                        1,
                        "mode",
                        "ai_only",
                        "recording_enabled",
                        false));
    }

    @Test
    void acceptedSyntheticExecutionSurvivesRestartAndCompletesOnce() throws Exception {
        var cfg = settings();
        var command = payload();
        String uuid;
        try (var gateway = new VoiceGateway(cfg)) {
            var result = gateway.post("dial", command);
            uuid = result.get("provider_call_id").toString();
            assertThat(result)
                    .containsEntry("result", "pbx_accepted")
                    .containsEntry("evidence_source", "synthetic_executor");
            assertThat(gateway.post("dial", command)).isEqualTo(result);
            assertThat(gateway.post("dial-confirmation", command)).isEqualTo(result);
            assertThat(
                            gateway.ledger.<Long>read(
                                    db -> LocalLedger.scalar(db, "SELECT COUNT(*) FROM attempts")))
                    .isEqualTo(1);
            gateway.ledger.transaction(
                    db -> {
                        LocalLedger.execute(
                                db, "UPDATE voice_mock_schedule SET due=?", LocalLedger.now() - 1);
                        return null;
                    });
            gateway.mockTick();
            assertThat(
                            gateway.ledger.<Long>read(
                                    db -> LocalLedger.scalar(db, "SELECT COUNT(*) FROM outbox")))
                    .isEqualTo(2);
        }
        try (var recovered = new VoiceGateway(cfg)) {
            recovered.ledger.transaction(
                    db -> {
                        LocalLedger.execute(
                                db, "UPDATE voice_mock_schedule SET due=?", LocalLedger.now() - 1);
                        return null;
                    });
            recovered.mockTick();
            recovered.mockTick();
            assertThat(recovered.security.lookup("synthetic-v5", 1))
                    .containsEntry("send_state", "ENDED")
                    .containsEntry("state", "ended");
            assertThat(recovered.post("dial", command)).containsEntry("provider_call_id", uuid);
            assertThat(
                            recovered.ledger.<Long>read(
                                    db -> LocalLedger.scalar(db, "SELECT COUNT(*) FROM outbox")))
                    .isEqualTo(3);
            assertThat(
                            recovered.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM voice_mock_schedule")))
                    .isZero();
        }
    }

    @Test
    void readOnlyConfirmationNeverStartsARegisteredOrUnknownCommand() throws Exception {
        var cfg = settings();
        var command = payload();
        try (var gateway = new VoiceGateway(cfg)) {
            assertThat(gateway.post("dial-confirmation", command))
                    .containsEntry("result", "pending_reconciliation");
            var route =
                    Map.<String, Object>of(
                            "max_duration_sec",
                            20,
                            "billing_multiplier",
                            2,
                            "rate_minor_per_minute",
                            1,
                            "max_concurrent",
                            20,
                            "cps",
                            100,
                            "calls_per_day",
                            1000,
                            "hour_budget_minor",
                            50000,
                            "day_budget_minor",
                            50000);
            var row = gateway.security.admit(command, route, true, 100).row();
            String uuid = row.get("uuid").toString();
            assertThat(gateway.post("dial-confirmation", command))
                    .containsEntry("result", "pending_reconciliation");
            assertThat(gateway.security.lookup("synthetic-v5", 1))
                    .containsEntry("send_state", "REGISTERED");
            assertThat(new VoiceSendJournal(gateway.ledger).claim(uuid)).isTrue();
            new VoiceSendJournal(gateway.ledger).uncertain(uuid);
            assertThat(gateway.post("dial", command))
                    .containsEntry("result", "pending_reconciliation");
            assertThat(
                            gateway.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM voice_mock_schedule")))
                    .isZero();
        }
    }

    @Test
    void staleControlCannotSpeakOrHangupAnotherAttempt() throws Exception {
        try (var gateway = new VoiceGateway(settings())) {
            String uuid = gateway.post("dial", payload()).get("provider_call_id").toString();
            for (String action :
                    List.of("speak", "hangup", "status", "stop-speaking", "transfer")) {
                var stale =
                        Map.<String, Object>of(
                                "call_id",
                                "synthetic-v5",
                                "tenant_id",
                                1,
                                "expected_attempt",
                                2,
                                "provider_call_id",
                                uuid,
                                "text",
                                "测试");
                assertThatThrownBy(() -> gateway.post(action, stale))
                        .isInstanceOf(Support.Failure.class);
            }
            assertThatThrownBy(
                            () ->
                                    gateway.post(
                                            "hangup",
                                            Map.of(
                                                    "call_id",
                                                    "synthetic-v5",
                                                    "tenant_id",
                                                    1,
                                                    "expected_attempt",
                                                    1,
                                                    "provider_call_id",
                                                    "wrong-uuid")))
                    .isInstanceOf(Support.Failure.class);
            assertThat(gateway.security.lookup("synthetic-v5", 1)).containsEntry("state", "active");
        }
    }
}
