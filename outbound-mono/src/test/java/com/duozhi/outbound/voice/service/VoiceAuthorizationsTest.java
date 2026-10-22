package com.duozhi.outbound.voice.service;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.dao.LocalLedger;
import com.duozhi.outbound.integration.service.LedgerTestDatabase;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.voice.controller.VoiceController;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceAuthorizationsTest {
    @TempDir Path directory;

    Settings settings() {
        return new Settings(
                LedgerTestDatabase.settings(
                        Map.of(
                                "VOICE_GATEWAY_DRIVER",
                                "mock",
                                "VOICE_CALLBACK_BASE_URL",
                                "http://127.0.0.1:18000",
                                "FREESWITCH_RECORDING_DIR",
                                directory.toString(),
                                "VOICE_CPS",
                                "100",
                                "VOICE_COMMAND_SECRET",
                                "v5-auth-independent-fixture-signature-secret-32-plus")));
    }

    Map<String, Object> wire(String reservation, String owner, long fence, long epoch) {
        var payload =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "protocol_version",
                                2,
                                "call_id",
                                "授权-语义-v5",
                                "tenant_id",
                                1,
                                "phone",
                                "+8613900000123",
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
                                        "notice",
                                        "您好")));
        var grant =
                Map.of(
                        "reservation_id",
                        reservation,
                        "owner",
                        owner,
                        "fence",
                        fence,
                        "epoch",
                        epoch,
                        "expires_at",
                        System.currentTimeMillis() / 1000 + 60,
                        "semantic_digest",
                        VoiceAuthorizations.digest(payload));
        payload.put("authorization", grant);
        return payload;
    }

    Map<String, Object> route() {
        return Map.of(
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
    }

    Map<String, Object> named(String call) {
        var payload = wire(UUID.randomUUID().toString(), "task:first", 1, 1);
        payload.put("call_id", call);
        var grant = new LinkedHashMap<>(Support.map(payload.get("authorization")));
        grant.put("semantic_digest", VoiceAuthorizations.digest(payload));
        payload.put("authorization", grant);
        return payload;
    }

    VoiceController.Command signed(Settings cfg, String action, Map<String, Object> payload) {
        byte[] body = Support.canonical(payload);
        String stamp = Long.toString(System.currentTimeMillis() / 1000),
                nonce = UUID.randomUUID().toString();
        String prefix = stamp + "." + nonce + "./v1/call/" + action + ".";
        byte[] bytes =
                (prefix + new String(body, java.nio.charset.StandardCharsets.UTF_8))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new VoiceController.Command(
                action,
                body,
                Map.of(
                        "x-voice-timestamp",
                        stamp,
                        "x-voice-nonce",
                        nonce,
                        "x-voice-signature",
                        Support.hmac(cfg.secret("VOICE_COMMAND_SECRET"), bytes)));
    }

    @Test
    void newOwnerMustInstallAHigherFenceBeforeFirstSendAndOldOwnerCannotExecute() throws Exception {
        try (var gateway = new VoiceGateway(settings())) {
            String id = UUID.randomUUID().toString();
            var old = wire(id, "task:first", 1, 1);
            var next = wire(id, "task:second", 2, 1);
            assertThat(VoiceAuthorizations.digest(old)).isEqualTo(VoiceAuthorizations.digest(next));
            var permits = new VoiceAuthorizations(gateway.ledger);
            permits.install(old);
            var row = gateway.security.admit(old, route(), true, 100).row();
            assertThat(row).containsEntry("send_state", "REGISTERED");
            permits.install(next);
            assertThatThrownBy(() -> new VoiceSendJournal(gateway.ledger).claim(row, old))
                    .isInstanceOf(Support.Failure.class);
            assertThat(gateway.security.lookup("授权-语义-v5", 1))
                    .containsEntry("send_state", "REGISTERED");
            var accepted = gateway.post("dial", next);
            assertThat(accepted).containsEntry("result", "pbx_accepted");
            assertThat(gateway.post("dial", old)).isEqualTo(accepted);
            assertThat(
                            ((Number) gateway.security.lookup("授权-语义-v5", 1).get("send_fence"))
                                    .longValue())
                    .isEqualTo(2);
            assertThat(
                            gateway.ledger.<Long>read(
                                    db -> LocalLedger.scalar(db, "SELECT COUNT(*) FROM attempts")))
                    .isEqualTo(1);
            var changed = wire(id, "task:third", 3, 1);
            changed.put("phone", "+8613900000456");
            assertThatThrownBy(() -> permits.install(changed)).isInstanceOf(Support.Failure.class);
            assertThatThrownBy(
                            () ->
                                    permits.install(
                                            wire(UUID.randomUUID().toString(), "task:third", 3, 1)))
                    .isInstanceOf(Support.Failure.class);
        }
    }

    @Test
    void committedUnknownIntentCannotBeReplayedByANewerOwner() throws Exception {
        try (var gateway = new VoiceGateway(settings())) {
            String id = UUID.randomUUID().toString();
            var first = wire(id, "task:first", 1, 1);
            var permits = new VoiceAuthorizations(gateway.ledger);
            permits.install(first);
            var row = gateway.security.admit(first, route(), true, 100).row();
            assertThat(new VoiceSendJournal(gateway.ledger).claim(row, first)).isTrue();
            var second = wire(id, "task:second", 2, 1);
            permits.install(second);
            assertThat(gateway.post("dial", second))
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
    void epochChangeBlocksAnOldRegisteredOwnerUntilTheNewEpochIsInstalled() throws Exception {
        try (var gateway = new VoiceGateway(settings())) {
            String id = UUID.randomUUID().toString();
            var first = wire(id, "task:first", 1, 1);
            var permits = new VoiceAuthorizations(gateway.ledger);
            permits.install(first);
            var row = gateway.security.admit(first, route(), true, 100).row();
            gateway.ledger.transaction(
                    db -> {
                        LocalLedger.execute(
                                db, "UPDATE dial_admission_control SET epoch=2 WHERE id=1");
                        return null;
                    });
            assertThatThrownBy(() -> new VoiceSendJournal(gateway.ledger).claim(row, first))
                    .isInstanceOf(Support.Failure.class);
            var second = wire(id, "task:second", 2, 2);
            permits.install(second);
            assertThat(new VoiceSendJournal(gateway.ledger).claim(row, second)).isTrue();
        }
    }

    @Test
    void protocolTwoRequiresTheFullCommandSignatureEvenInTheMockDriver() throws Exception {
        var cfg = settings();
        var payload = wire(UUID.randomUUID().toString(), "task:first", 1, 1);
        var controller = new VoiceController(cfg);
        try {
            assertThatThrownBy(
                            () ->
                                    controller.commandInternal(
                                            "dial-authorize", Support.canonical(payload), Map.of()))
                    .isInstanceOf(Support.Failure.class);
            assertThat(
                            controller.gateway.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM"
                                                            + " voice_dial_authorization")))
                    .isZero();
        } finally {
            controller.close();
        }
    }

    @Test
    void durableRejectionRevokesOldGrantAndReadmissionKeepsTheOriginalUuid() throws Exception {
        var cfg = settings();
        String id = UUID.randomUUID().toString();
        var first = wire(id, "task:first", 1, 1);
        String uuid;
        Map<String, Object> proof;
        try (var gateway = new VoiceGateway(cfg)) {
            var permits = new VoiceAuthorizations(gateway.ledger);
            permits.install(first);
            var row = gateway.security.admit(first, route(), true, 100).row();
            uuid = row.get("uuid").toString();
            proof = gateway.post("dial-cancel", first);
            assertThat(proof)
                    .containsEntry("result", "rejected_before_dispatch")
                    .containsEntry("provider_call_id", uuid);
            var signed = new LinkedHashMap<>(proof);
            String signature = signed.remove("proof_signature").toString();
            assertThat(signature)
                    .isEqualTo(
                            Support.hmac(
                                    cfg.secret("VOICE_COMMAND_SECRET"), Support.canonical(signed)));
            assertThatThrownBy(() -> permits.install(first)).isInstanceOf(Support.Failure.class);
            assertThatThrownBy(() -> gateway.post("dial", first))
                    .isInstanceOf(Support.Failure.class);
        }
        try (var gateway = new VoiceGateway(cfg)) {
            assertThat(Support.canonical(gateway.post("dial-cancel", first)))
                    .isEqualTo(Support.canonical(proof));
            var next = wire(UUID.randomUUID().toString(), "task:second", 2, 1);
            new VoiceAuthorizations(gateway.ledger).install(next);
            assertThat(gateway.post("dial", next))
                    .containsEntry("provider_call_id", uuid)
                    .containsEntry("result", "pbx_accepted");
            assertThat(
                            gateway.ledger.<Long>read(
                                    db -> LocalLedger.scalar(db, "SELECT COUNT(*) FROM attempts")))
                    .isEqualTo(1);
            assertThat(
                            gateway.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM voice_mock_schedule")))
                    .isEqualTo(1);
        }
    }

    @Test
    void committedDispatchIntentCannotProduceARejectionProof() throws Exception {
        try (var gateway = new VoiceGateway(settings())) {
            var first = wire(UUID.randomUUID().toString(), "task:first", 1, 1);
            new VoiceAuthorizations(gateway.ledger).install(first);
            var row = gateway.security.admit(first, route(), true, 100).row();
            new VoiceSendJournal(gateway.ledger).claim(row, first);
            assertThat(gateway.post("dial-cancel", first))
                    .containsEntry("result", "pending_reconciliation")
                    .doesNotContainKey("proof_signature");
            assertThat(gateway.security.lookup("授权-语义-v5", 1))
                    .containsEntry("send_state", "DISPATCH_INTENT")
                    .containsEntry("state", "pending");
        }
    }

    @Test
    void cachedAuthorizationCohortsCommitBeforeSendingAndRemainIdempotent() throws Exception {
        var base = settings();
        var cfg =
                new Settings(Map.of()) {
                    @Override
                    public String get(String key, String fallback) {
                        return key.equals("VOICE_ADMISSION_BATCH_CACHE_ENABLED")
                                ? "true"
                                : key.equals("VOICE_MOCK_ANSWER_PERCENT")
                                        ? "0"
                                        : base.get(key, fallback);
                    }
                };
        var controller = new VoiceController(cfg);
        try {
            var payloads = List.of(named("cohort-one"), named("cohort-two"));
            var installed =
                    controller.commandsInternalBatch(
                            payloads.stream().map(p -> signed(cfg, "dial-authorize", p)).toList());
            assertThat(installed)
                    .allSatisfy(
                            r ->
                                    assertThat(r.response())
                                            .containsEntry("result", "authorization_installed"));
            var first =
                    controller.commandsInternalBatch(
                            payloads.stream().map(p -> signed(cfg, "dial", p)).toList());
            assertThat(first)
                    .allSatisfy(
                            r -> assertThat(r.response()).containsEntry("result", "pbx_accepted"));
            assertThat(first)
                    .allSatisfy(
                            r -> assertThat(r.response()).containsEntry("mock_answered", false));
            assertThat(
                            controller.gateway.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM voice_mock_schedule WHERE"
                                                            + " phase='NO_ANSWER' AND due=end_at")))
                    .isEqualTo(2);
            var replay =
                    controller.commandsInternalBatch(
                            payloads.stream().map(p -> signed(cfg, "dial", p)).toList());
            for (int i = 0; i < first.size(); i++)
                assertThat(replay.get(i).response()).isEqualTo(first.get(i).response());
            assertThat(
                            controller.gateway.ledger.<Long>read(
                                    db -> LocalLedger.scalar(db, "SELECT COUNT(*) FROM attempts")))
                    .isEqualTo(2);
        } finally {
            controller.close();
        }
    }

    @Test
    void aHigherFenceForANewIdentityInOneCohortPersistsInOrder() throws Exception {
        var base = settings();
        var cfg =
                new Settings(Map.of()) {
                    @Override
                    public String get(String key, String fallback) {
                        return key.equals("VOICE_ADMISSION_BATCH_CACHE_ENABLED")
                                ? "true"
                                : base.get(key, fallback);
                    }
                };
        var controller = new VoiceController(cfg);
        try {
            String reservation = UUID.randomUUID().toString();
            var first = wire(reservation, "task:first", 1, 1);
            var second = wire(reservation, "task:second", 2, 1);
            var installed =
                    controller.commandsInternalBatch(
                            List.of(
                                    signed(cfg, "dial-authorize", first),
                                    signed(cfg, "dial-authorize", second)));
            assertThat(installed).allSatisfy(result -> assertThat(result.status()).isEqualTo(200));
            var row =
                    controller.gateway.ledger.read(
                            db ->
                                    LocalLedger.query(db, "SELECT * FROM voice_dial_authorization")
                                            .get(0));
            assertThat(row).containsEntry("owner", "task:second");
            assertThat(((Number) row.get("fence")).longValue()).isEqualTo(2);
            assertThat(
                            controller.gateway.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM"
                                                            + " voice_dial_authorization_history")))
                    .isEqualTo(1);
            assertThatThrownBy(() -> controller.gateway.post("dial", first))
                    .isInstanceOf(Support.Failure.class);
            assertThat(controller.gateway.post("dial", second))
                    .containsEntry("result", "pbx_accepted");
        } finally {
            controller.close();
        }
    }

    @Test
    void independentOwnersCannotBothCommitDispatchAndNegativeProof() throws Exception {
        var cfg = settings();
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try (var first = new VoiceGateway(cfg);
                var second = new VoiceGateway(cfg)) {
            for (int index = 0; index < 5; index++) {
                var payload = named("race-" + index);
                new VoiceAuthorizations(first.ledger).install(payload);
                var row = first.security.admit(payload, route(), true, 100).row();
                var start = new java.util.concurrent.CountDownLatch(1);
                var dispatch =
                        workers.submit(
                                () -> {
                                    start.await();
                                    try {
                                        return new VoiceSendJournal(first.ledger)
                                                .claim(row, payload);
                                    } catch (Support.Failure revoked) {
                                        if (revoked.status != 409) throw revoked;
                                        return false;
                                    }
                                });
                var cancel =
                        workers.submit(
                                () -> {
                                    start.await();
                                    return new VoiceAuthorizations(second.ledger)
                                            .reject(payload, cfg.secret("VOICE_COMMAND_SECRET"));
                                });
                start.countDown();
                boolean sent = dispatch.get(10, java.util.concurrent.TimeUnit.SECONDS);
                var response = cancel.get(10, java.util.concurrent.TimeUnit.SECONDS);
                boolean proof = "rejected_before_dispatch".equals(response.get("result"));
                assertThat(sent).isNotEqualTo(proof);
                assertThat(first.security.lookup("race-" + index, 1))
                        .containsEntry("send_state", proof ? "REJECTED" : "DISPATCH_INTENT");
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void emergencyStopAlsoFencesAnAlreadyRegisteredUnsentCommand() throws Exception {
        try (var gateway = new VoiceGateway(settings())) {
            var payload = named("stopped-registered");
            new VoiceAuthorizations(gateway.ledger).install(payload);
            var row = gateway.security.admit(payload, route(), true, 100).row();
            gateway.ledger.stopped(true, "isolated stop race");
            assertThat(new VoiceSendJournal(gateway.ledger).claim(row, payload)).isFalse();
            assertThat(gateway.post("dial", payload))
                    .containsEntry("result", "pending_reconciliation");
            assertThat(gateway.security.lookup("stopped-registered", 1))
                    .containsEntry("send_state", "REGISTERED");
            assertThat(
                            gateway.ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM voice_mock_schedule")))
                    .isZero();
        }
    }
}
