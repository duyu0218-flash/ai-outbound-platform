package com.duozhi.outbound.voice.service;

import static org.junit.jupiter.api.Assertions.*;

import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.dao.LocalLedger;
import com.duozhi.outbound.integration.service.LedgerTestDatabase;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.recording.service.RecordingSource;
import com.duozhi.outbound.voice.controller.VoiceController;
import com.duozhi.outbound.voice.security.VoiceSecurity;
import com.duozhi.outbound.voice.transport.EslClient;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class VoiceTest {
    @TempDir
    Path directory;

    private Settings settings(Map<String, String> overrides) {
        var values =
                new HashMap<>(
                        Map.of(
                                "VOICE_GATEWAY_DRIVER",
                                "mock",
                                "VOICE_CALLBACK_BASE_URL",
                                "http://127.0.0.1:18000",
                                "VOICE_CALLBACK_ALLOW_PRIVATE_HTTP",
                                "true",
                                "FREESWITCH_RECORDING_DIR",
                                directory.toString(),
                                "VOICE_COMMAND_SECRET",
                                "command-secret-independent-00000000000"));
        var route =
                new HashMap<String, Object>(
                        Map.of(
                                "gateway",
                                "approved",
                                "caller_id",
                                "+8613800138000",
                                "allowed_prefixes",
                                List.of("86"),
                                "denied_prefixes",
                                List.of("86900"),
                                "max_concurrent",
                                2,
                                "cps",
                                100,
                                "calls_per_day",
                                1000,
                                "hour_budget_minor",
                                10000,
                                "day_budget_minor",
                                50000,
                                "rate_minor_per_minute",
                                10));
        route.put("max_duration_sec", 60);
        values.put(
                "VOICE_SECURITY_ROUTES_JSON",
                new String(Support.json(Map.of("1:0", route)), StandardCharsets.UTF_8));
        values.put("VOICE_CPS", "100");
        values.putAll(overrides);
        return new Settings(LedgerTestDatabase.settings(values));
    }

    private Map<String, Object> dial(String call, int tenant, int attempt) {
        return Map.of(
                "call_id",
                call,
                "phone",
                "+8613900139000",
                "webhook_url",
                "http://127.0.0.1:18000/api/v1/webhooks/telephony/status",
                "metadata",
                Map.of(
                        "tenant_id",
                        tenant,
                        "attempt",
                        attempt,
                        "mode",
                        "ai_only",
                        "recording_enabled",
                        false));
    }

    @Test
    void transactionLocalAdmissionProjectionPreservesSharedHardLimits() throws Exception {
        var cfg = settings(Map.of("VOICE_MAX_CONCURRENT", "2"));
        try (var ledger = new LocalLedger(cfg)) {
            var security = new VoiceSecurity(cfg, ledger);
            try (var projection = new VoiceAdmissionBatchScope()) {
                ledger.transaction(
                        db -> {
                            for (String id : List.of("batch-limit-a", "batch-limit-b")) {
                                var request = dial(id, 1, 1);
                                assertTrue(
                                        security.admit(request, security.policy(request), true, 10)
                                                .fresh());
                            }
                            return null;
                        });
            }
            var request = dial("batch-limit-c", 1, 1);
            assertEquals(
                    429,
                    assertThrows(
                                    Support.Failure.class,
                                    () ->
                                            security.admit(
                                                    request, security.policy(request), true, 10))
                            .status);
            assertEquals(
                    2L,
                    ledger.<Long>read(
                            db -> LocalLedger.scalar(db, "SELECT COUNT(*) FROM attempts")));
        }
    }

    @Test
    void registeredCommandCanBeClaimedOnlyOnceAcrossLedgerOwners() throws Exception {
        var config = settings(Map.of());
        try (var first = new LocalLedger(config);
                var second = new LocalLedger(config)) {
            var security = new VoiceSecurity(config, first);
            var payload = dial("v5-send-once", 1, 1);
            String uuid =
                    security.admit(payload, security.policy(payload), true, 10)
                            .row()
                            .get("uuid")
                            .toString();
            var workers = Executors.newFixedThreadPool(2);
            try {
                var a = workers.submit(() -> new VoiceSendJournal(first).claim(uuid));
                var b = workers.submit(() -> new VoiceSendJournal(second).claim(uuid));
                assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
                assertFalse(new VoiceSendJournal(second).claim(uuid));
            } finally {
                workers.shutdownNow();
            }
        }
    }

    @Test
    void ambiguousIntentEscalatesDurablyAndLateAcceptanceCannotReviveEndedCall() throws Exception {
        var config = settings(Map.of());
        try (var ledger = new LocalLedger(config)) {
            var security = new VoiceSecurity(config, ledger);
            var payload = dial("v5-hold", 1, 1);
            String uuid =
                    security.admit(payload, security.policy(payload), true, 10)
                            .row()
                            .get("uuid")
                            .toString();
            var journal = new VoiceSendJournal(ledger);
            assertTrue(journal.claim(uuid));
            journal.uncertain(uuid);
            ledger.transaction(
                    db -> {
                        LocalLedger.execute(
                                db,
                                "UPDATE attempts SET send_intent_at=? WHERE uuid=?",
                                LocalLedger.now() - 61,
                                uuid);
                        return null;
                    });
            journal.escalate();
            journal.escalate();
            assertEquals("MANUAL_HOLD", security.lookup("v5-hold", 1).get("send_state"));
            assertFalse(journal.claim(uuid));
            assertEquals(
                    1L,
                    ledger.<Long>read(
                            db ->
                                    LocalLedger.scalar(
                                            db,
                                            "SELECT COUNT(*) FROM audit WHERE"
                                                    + " action='send-manual-hold'")));
            security.finish(uuid, 0L);
            journal.accepted(uuid, Map.of("result", "accepted", "provider_call_id", uuid));
            assertEquals("ENDED", security.lookup("v5-hold", 1).get("send_state"));
            assertEquals("ended", security.lookup("v5-hold", 1).get("state"));
        }
    }

    @Test
    void admissionIdempotencyBudgetAndTenantFences() throws Exception {
        var settings = settings(Map.of());
        try (var ledger = new LocalLedger(settings)) {
            var security = new VoiceSecurity(settings, ledger);
            var request = dial("call-a", 1, 1);
            var route = security.policy(request);
            var first = security.admit(request, route, true, 10);
            assertTrue(first.fresh());
            assertFalse(security.admit(request, route, true, 10).fresh());
            var changed = new HashMap<>(request);
            changed.put("phone", "+8613900139001");
            assertEquals(
                    409,
                    assertThrows(
                                    Support.Failure.class,
                                    () -> security.admit(changed, route, true, 10))
                            .status);
            assertEquals(
                    403,
                    assertThrows(Support.Failure.class, () -> security.policy(dial("b", 2, 1)))
                            .status);
            security.admit(dial("b", 1, 1), route, true, 10);
            assertEquals(
                    429,
                    assertThrows(
                                    Support.Failure.class,
                                    () -> security.admit(dial("c", 1, 1), route, true, 10))
                            .status);
            assertThrows(Support.Failure.class, () -> security.lookup("call-a", 2));
            security.finish(first.row().get("uuid").toString(), null);
            assertEquals(20, ((Number) security.lookup("call-a", 1).get("cost")).intValue());
        }
    }

    @Test
    void batchedCallbackPostsCommitTogetherDeduplicateAndRollbackTogether() throws Exception {
        var settings = settings(Map.of());
        try (var ledger = new LocalLedger(settings);
                var callbacks =
                        new VoiceCallbacks(settings, ledger, new VoiceSecurity(settings, ledger))) {
            String url = "http://127.0.0.1:18000/api/v1/webhooks/telephony/status";
            var a = Map.<String, Object>of("call_id", "a", "sequence", 1);
            var b = Map.<String, Object>of("call_id", "b", "sequence", 1);
            callbacks.atomic(
                    () -> {
                        callbacks.post(url, a);
                        callbacks.post(url, b);
                        callbacks.post(url, a);
                    });
            assertEquals(2, ((Number) ledger.summary().get("pending_callbacks")).intValue());
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            callbacks.atomic(
                                    () -> {
                                        callbacks.post(url, Map.of("call_id", "c", "sequence", 1));
                                        throw new IllegalStateException("synthetic crash");
                                    }));
            assertEquals(2, ((Number) ledger.summary().get("pending_callbacks")).intValue());
        }
    }

    @Test
    void signedCommandReplayIsDurableAcrossRestart() throws Exception {
        var settings = settings(Map.of());
        String stamp = Long.toString(System.currentTimeMillis() / 1000),
                nonce = "nonce-012345678901234567890",
                path = "/v1/call/dial";
        byte[] body = Support.json(dial("a", 1, 1));
        var prefix = (stamp + "." + nonce + "." + path + ".").getBytes(StandardCharsets.UTF_8);
        var signed = Arrays.copyOf(prefix, prefix.length + body.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        String signature = Support.hmac(settings.secret("VOICE_COMMAND_SECRET"), signed);
        try (var ledger = new LocalLedger(settings)) {
            new VoiceSecurity(settings, ledger).command(path, body, stamp, nonce, signature);
        }
        try (var ledger = new LocalLedger(settings)) {
            var security = new VoiceSecurity(settings, ledger);
            assertEquals(
                    409,
                    assertThrows(
                                    Support.Failure.class,
                                    () -> security.command(path, body, stamp, nonce, signature))
                            .status);
            assertEquals(
                    401,
                    assertThrows(
                                    Support.Failure.class,
                                    () ->
                                            security.command(
                                                    path, new byte[0], stamp, nonce, signature))
                            .status);
        }
    }

    private Map<String, String> commandPermit(
            Settings config, String path, byte[] body, String nonce) {
        String stamp = Long.toString(System.currentTimeMillis() / 1000);
        byte[] prefix = (stamp + "." + nonce + "." + path + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = Arrays.copyOf(prefix, prefix.length + body.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        return Map.of(
                "x-voice-timestamp",
                stamp,
                "x-voice-nonce",
                nonce,
                "x-voice-signature",
                Support.hmac(config.secret("VOICE_COMMAND_SECRET"), signed));
    }

    @Test
    void batchedNonceClaimsCommitTogetherAndReplayCannotSurviveRollback() throws Exception {
        var config = settings(Map.of());
        String path = "/v1/call/dial";
        byte[] body = Support.json(dial("nonce-batch", 1, 1));
        var permits =
                List.of(
                        commandPermit(config, path, body, "batch-first-0000000000000000"),
                        commandPermit(config, path, body, "batch-second-000000000000000"));
        var commands =
                permits.stream()
                        .map(headers -> new VoiceController.Command("dial", body, headers))
                        .toList();
        try (var ledger = new LocalLedger(config)) {
            var security = new VoiceSecurity(config, ledger);
            ledger.transaction(
                    db -> {
                        try (var scope = new VoiceAdmissionBatchScope()) {
                            scope.preload(ledger, commands);
                            for (var headers : permits)
                                security.command(
                                        path,
                                        body,
                                        headers.get("x-voice-timestamp"),
                                        headers.get("x-voice-nonce"),
                                        headers.get("x-voice-signature"));
                            assertEquals(0L, LocalLedger.scalar(db, "SELECT COUNT(*) FROM nonces"));
                            scope.flush(ledger);
                            return null;
                        }
                    });
            assertEquals(
                    2L,
                    ledger.<Long>read(db -> LocalLedger.scalar(db, "SELECT COUNT(*) FROM nonces")));
            var repeat = permits.get(0);
            assertEquals(
                    409,
                    assertThrows(
                                    Support.Failure.class,
                                    () ->
                                            security.command(
                                                    path,
                                                    body,
                                                    repeat.get("x-voice-timestamp"),
                                                    repeat.get("x-voice-nonce"),
                                                    repeat.get("x-voice-signature")))
                            .status);
            var fresh = commandPermit(config, path, body, "batch-rollback-0000000000000");
            var duplicated =
                    List.of(
                            new VoiceController.Command("dial", body, fresh),
                            new VoiceController.Command("dial", body, fresh));
            assertThrows(
                    Support.Failure.class,
                    () ->
                            ledger.transaction(
                                    db -> {
                                        try (var scope = new VoiceAdmissionBatchScope()) {
                                            scope.preload(ledger, duplicated);
                                            security.command(
                                                    path,
                                                    body,
                                                    fresh.get("x-voice-timestamp"),
                                                    fresh.get("x-voice-nonce"),
                                                    fresh.get("x-voice-signature"));
                                            assertEquals(
                                                    409,
                                                    assertThrows(
                                                                    Support.Failure.class,
                                                                    () ->
                                                                            security.command(
                                                                                    path,
                                                                                    body,
                                                                                    fresh.get(
                                                                                            "x-voice-timestamp"),
                                                                                    fresh.get(
                                                                                            "x-voice-nonce"),
                                                                                    fresh.get(
                                                                                            "x-voice-signature")))
                                                            .status);
                                            scope.flush(ledger);
                                            return null;
                                        }
                                    }));
            assertEquals(
                    0L,
                    ledger.<Long>read(
                            db ->
                                    LocalLedger.scalar(
                                            db,
                                            "SELECT COUNT(*) FROM nonces WHERE nonce=?",
                                            fresh.get("x-voice-nonce"))));
            security.command(
                    path,
                    body,
                    fresh.get("x-voice-timestamp"),
                    fresh.get("x-voice-nonce"),
                    fresh.get("x-voice-signature"));
        }
    }

    @Test
    void signedNoncesCommitWhileTheResourceWriterIsHeldAndConcurrentReplayHasOneWinner()
            throws Exception {
        var config = settings(Map.of());
        String path = "/v1/call/play";
        byte[] body = Support.json(Map.of("call_id", "nonce-concurrency"));
        var permit = commandPermit(config, path, body, "nonce-concurrent-000000000000000");
        var pool = Executors.newFixedThreadPool(2);
        try (var ledger = new LocalLedger(config)) {
            var security = new VoiceSecurity(config, ledger);
            ledger.transaction(
                    db -> {
                        var start = new CountDownLatch(1);
                        var replies = new ArrayList<Future<Integer>>();
                        for (int i = 0; i < 2; i++)
                            replies.add(
                                    pool.submit(
                                            () -> {
                                                start.await();
                                                try {
                                                    security.command(
                                                            path,
                                                            body,
                                                            permit.get("x-voice-timestamp"),
                                                            permit.get("x-voice-nonce"),
                                                            permit.get("x-voice-signature"));
                                                    return 200;
                                                } catch (Support.Failure failure) {
                                                    return failure.status;
                                                }
                                            }));
                        start.countDown();
                        var codes = new ArrayList<Integer>();
                        for (var reply : replies) codes.add(reply.get(5, TimeUnit.SECONDS));
                        Collections.sort(codes);
                        assertEquals(List.of(200, 409), codes);
                        return null;
                    });
            assertEquals(
                    1L,
                    ledger.<Long>read(
                            db ->
                                    LocalLedger.scalar(
                                            db,
                                            "SELECT COUNT(*) FROM nonces WHERE nonce=?",
                                            permit.get("x-voice-nonce"))));
            var rollback = commandPermit(config, path, body, "nonce-noncached-rollback-00000000");
            assertThrows(
                    Support.Failure.class,
                    () ->
                            ledger.transaction(
                                    db -> {
                                        security.command(
                                                path,
                                                body,
                                                rollback.get("x-voice-timestamp"),
                                                rollback.get("x-voice-nonce"),
                                                rollback.get("x-voice-signature"));
                                        assertEquals(
                                                409,
                                                assertThrows(
                                                                Support.Failure.class,
                                                                () ->
                                                                        security.command(
                                                                                path,
                                                                                body,
                                                                                rollback.get(
                                                                                        "x-voice-timestamp"),
                                                                                rollback.get(
                                                                                        "x-voice-nonce"),
                                                                                rollback.get(
                                                                                        "x-voice-signature")))
                                                        .status);
                                        return null;
                                    }));
            assertEquals(
                    0L,
                    ledger.<Long>read(
                            db ->
                                    LocalLedger.scalar(
                                            db,
                                            "SELECT COUNT(*) FROM nonces WHERE nonce=?",
                                            rollback.get("x-voice-nonce"))));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentNonceConsumptionInvalidatesTheWholePreloadedBatch() throws Exception {
        var config = settings(Map.of());
        String path = "/v1/call/dial";
        byte[] body = Support.json(dial("nonce-race", 1, 1));
        var permit = commandPermit(config, path, body, "nonce-preloaded-race-0000000000");
        var pool = Executors.newSingleThreadExecutor();
        try (var ledger = new LocalLedger(config)) {
            var security = new VoiceSecurity(config, ledger);
            assertThrows(
                    Support.Failure.class,
                    () ->
                            ledger.transaction(
                                    db -> {
                                        try (var scope = new VoiceAdmissionBatchScope()) {
                                            scope.preload(
                                                    ledger,
                                                    List.of(
                                                            new VoiceController.Command(
                                                                    "dial", body, permit)));
                                            security.command(
                                                    path,
                                                    body,
                                                    permit.get("x-voice-timestamp"),
                                                    permit.get("x-voice-nonce"),
                                                    permit.get("x-voice-signature"));
                                            LocalLedger.execute(
                                                    db,
                                                    "INSERT INTO flags (`key`,value) VALUES"
                                                            + " ('nonce_batch_side_effect','pending')");
                                            pool.submit(
                                                            () ->
                                                                    security.command(
                                                                            path,
                                                                            body,
                                                                            permit.get(
                                                                                    "x-voice-timestamp"),
                                                                            permit.get(
                                                                                    "x-voice-nonce"),
                                                                            permit.get(
                                                                                    "x-voice-signature")))
                                                    .get(5, TimeUnit.SECONDS);
                                            scope.flush(ledger);
                                            return null;
                                        }
                                    }));
            assertEquals(
                    0L,
                    ledger.<Long>read(
                            db ->
                                    LocalLedger.scalar(
                                            db,
                                            "SELECT COUNT(*) FROM flags WHERE"
                                                    + " `key`='nonce_batch_side_effect'")));
            assertEquals(
                    1L,
                    ledger.<Long>read(
                            db ->
                                    LocalLedger.scalar(
                                            db,
                                            "SELECT COUNT(*) FROM nonces WHERE nonce=?",
                                            permit.get("x-voice-nonce"))));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unsafeRoutesTargetsAndDuplicateOwnerFailClosed() throws Exception {
        var settings = settings(Map.of());
        try (var ledger = new LocalLedger(settings)) {
            var security = new VoiceSecurity(settings, ledger);
            try (var second = new LocalLedger(settings)) {
                ledger.stopped(true, "shared database writer");
                assertTrue((Boolean) second.summary().get("stopped"));
            }
            assertThrows(
                    Support.Failure.class,
                    () ->
                            security.callback(
                                    "http://127.0.0.1:18000/api/v1/webhooks/telephony/status?x=1"));
            var request = new HashMap<>(dial("a", 1, 1));
            request.put("phone", "+86900123456");
            assertThrows(Support.Failure.class, () -> security.policy(request));
            request.put("phone", "+８６13900139000");
            assertThrows(Support.Failure.class, () -> security.policy(request));
            ledger.stopped(true, "test emergency stop");
            assertThrows(
                    Support.Failure.class,
                    () ->
                            security.admit(
                                    dial("a", 1, 1),
                                    Support.map(security.routes.get("1:0")),
                                    true,
                                    10));
        }
    }

    @Test
    void unconfirmedVoicePermitsNeverExpireOrReleaseCallReservation() throws Exception {
        var budgets =
                Map.of(
                        "asr",
                        Map.of(
                                "provider",
                                "test",
                                "account",
                                "a",
                                "region",
                                "cn",
                                "product",
                                "asr",
                                "concurrency",
                                1),
                        "tts",
                        Map.of(
                                "provider",
                                "test",
                                "account",
                                "a",
                                "region",
                                "cn",
                                "product",
                                "tts",
                                "concurrency",
                                1,
                                "requests_per_minute",
                                2));
        var settings =
                settings(
                        Map.of(
                                "VOICE_QUOTA_ENABLED",
                                "true",
                                "VOICE_QUOTA_BUDGETS_JSON",
                                new String(Support.json(budgets), StandardCharsets.UTF_8)));
        try (var ledger = new LocalLedger(settings)) {
            var quota = new VoiceQuota(settings, ledger);
            quota.validate();
            ledger.transaction(
                    db -> {
                        quota.reserve(db, "a", 1);
                        quota.acquire(db, "physical-a", "asr", "a", 1, "epoch-a");
                        quota.release(db, "physical-a", "epoch-a", false);
                        return null;
                    });
            assertEquals(
                    1,
                    ((Number) Support.map(quota.snapshot().get("asr")).get("unknown")).intValue());
            var security = new VoiceSecurity(settings, ledger);
            assertThrows(
                    Support.Failure.class,
                    () ->
                            security.admit(
                                    dial("b", 1, 1), security.policy(dial("b", 1, 1)), true, 10));
        }
    }

    @Test
    void callbacksKeepPerCallOrderAndRequireExactly200Ack() throws Exception {
        List<Integer> received = new CopyOnWriteArrayList<>();
        AtomicInteger status = new AtomicInteger(202);
        AtomicBoolean validSignature = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/webhooks/telephony/status",
                request -> {
                    byte[] body = request.getRequestBody().readAllBytes();
                    String stamp = request.getRequestHeaders().getFirst("x-webhook-timestamp");
                    byte[] prefix = (stamp + ".").getBytes(StandardCharsets.UTF_8),
                            bytes = Arrays.copyOf(prefix, prefix.length + body.length);
                    System.arraycopy(body, 0, bytes, prefix.length, body.length);
                    validSignature.set(
                            Support.equals(
                                    Support.hmac("callback-signing-test", bytes),
                                    request.getRequestHeaders().getFirst("x-webhook-signature")));
                    received.add(((Number) Support.object(body).get("sequence")).intValue());
                    request.sendResponseHeaders(status.get(), -1);
                    request.close();
                });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var settings =
                settings(
                        Map.of(
                                "VOICE_CALLBACK_BASE_URL",
                                url,
                                "WEBHOOK_SECRET",
                                "callback-signing-test"));
        try (var ledger = new LocalLedger(settings);
                var callbacks =
                        new VoiceCallbacks(settings, ledger, new VoiceSecurity(settings, ledger))) {
            callbacks.post(
                    url + "/api/v1/webhooks/telephony/status",
                    Map.of("call_id", "a", "sequence", 1));
            callbacks.post(
                    url + "/api/v1/webhooks/telephony/status",
                    Map.of("call_id", "a", "sequence", 2));
            callbacks.tickOnce();
            await(() -> !received.isEmpty());
            Thread.sleep(50);
            assertEquals(List.of(1), received);
            assertEquals(2, ((Number) ledger.summary().get("pending_callbacks")).intValue());
            assertTrue(validSignature.get());
            status.set(200);
            ledger.transaction(
                    db -> {
                        LocalLedger.execute(db, "UPDATE outbox SET due=0");
                        return null;
                    });
            callbacks.tickOnce();
            await(() -> received.size() >= 2);
            Thread.sleep(50);
            callbacks.tickOnce();
            await(() -> received.size() >= 3);
            assertEquals(List.of(1, 1, 2), received);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void boundedLargeCallbackBatchUsesV2AndRequiresExactDurableAcknowledgement() throws Exception {
        var accepted = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/webhooks/telephony/batch-v2",
                request -> {
                    var body = Support.object(request.getRequestBody().readAllBytes());
                    assertEquals(2, Support.integer(body, "version", 0));
                    var events = (List<Map<String, Object>>) body.get("events");
                    accepted.set(events.size());
                    byte[] reply =
                            Support.json(
                                    Map.of(
                                            "version",
                                            2,
                                            "result",
                                            "received",
                                            "accepted",
                                            events.stream().map(e -> e.get("id")).toList()));
                    request.sendResponseHeaders(200, reply.length);
                    request.getResponseBody().write(reply);
                    request.close();
                });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var settings =
                settings(
                        Map.of(
                                "VOICE_CALLBACK_BASE_URL",
                                base,
                                "VOICE_CALLBACK_BATCH_ENABLED",
                                "true",
                                "VOICE_CALLBACK_BATCH_SIZE",
                                "64",
                                "VOICE_CALLBACK_CONCURRENCY",
                                "64"));
        try (var ledger = new LocalLedger(settings);
                var callbacks =
                        new VoiceCallbacks(settings, ledger, new VoiceSecurity(settings, ledger))) {
            callbacks.atomic(
                    () -> {
                        for (int i = 0; i < 64; i++)
                            callbacks.post(
                                    base + "/api/v1/webhooks/telephony/status",
                                    Map.of("call_id", "v2-" + i, "sequence", 1));
                    });
            assertEquals(64, callbacks.tickOnce());
            await(() -> accepted.get() == 64);
            await(() -> ((Number) ledger.summary().get("pending_callbacks")).longValue() == 0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void callbackRequestSlotsAndEventBudgetAreIndependentlyBounded() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var executor = Executors.newCachedThreadPool();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext(
                "/api/v1/webhooks/telephony/batch",
                request -> {
                    var body = Support.object(request.getRequestBody().readAllBytes());
                    var events = (List<Map<String, Object>>) body.get("events");
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new IOException("fixture release timeout");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                    byte[] reply =
                            Support.json(
                                    Map.of(
                                            "version",
                                            1,
                                            "result",
                                            "received",
                                            "accepted",
                                            events.stream().map(e -> e.get("id")).toList()));
                    request.sendResponseHeaders(200, reply.length);
                    request.getResponseBody().write(reply);
                    request.close();
                });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var config =
                settings(
                        Map.of(
                                "VOICE_CALLBACK_BASE_URL",
                                base,
                                "VOICE_CALLBACK_BATCH_ENABLED",
                                "true",
                                "VOICE_CALLBACK_BATCH_SIZE",
                                "16",
                                "VOICE_CALLBACK_CONCURRENCY",
                                "2",
                                "VOICE_CALLBACK_MAX_INFLIGHT_EVENTS",
                                "64"));
        try (var ledger = new LocalLedger(config);
                var callbacks =
                        new VoiceCallbacks(config, ledger, new VoiceSecurity(config, ledger))) {
            callbacks.atomic(
                    () -> {
                        for (int i = 0; i < 64; i++)
                            callbacks.post(
                                    base + "/api/v1/webhooks/telephony/status",
                                    Map.of("call_id", "bounded-" + i, "sequence", 1));
                    });
            assertEquals(32, callbacks.tickOnce());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(2, callbacks.deliveries());
            assertEquals(32, callbacks.inflight());
            assertEquals(0, callbacks.tickOnce());
            assertEquals(64, ((Number) ledger.summary().get("pending_callbacks")).intValue());
            release.countDown();
            await(() -> callbacks.deliveries() == 0);
            assertEquals(32, ((Number) ledger.summary().get("pending_callbacks")).intValue());
            assertEquals(32, callbacks.tickOnce());
            await(() -> callbacks.deliveries() == 0);
            assertEquals(0, callbacks.inflight());
            assertEquals(0, ((Number) ledger.summary().get("pending_callbacks")).intValue());
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void signedSourceCleanupNeedsCompletionChecksumAndRetention() throws Exception {
        var settings = settings(Map.of("VOICE_NODE_ID", "node-a"));
        var source = new RecordingSource(settings);
        String filename = "test-call.wav";
        byte[] audio = new byte[] {1, 2, 3, 4};
        Files.write(directory.resolve(filename), audio);
        long expires = System.currentTimeMillis() / 1000 + 60;
        assertEquals(
                directory.resolve(filename).toRealPath(),
                source.authorized(filename, expires, source.signature(filename, expires)));
        assertThrows(Support.Failure.class, () -> source.authorized(filename, expires, "forged"));
        assertThrows(
                Support.Failure.class,
                () -> source.reclaim(filename, Support.hex(Support.sha(audio)), 4));
        source.complete(filename);
        assertThrows(Support.Failure.class, () -> source.reclaim(filename, "0".repeat(64), 4));
        assertTrue(Files.exists(directory.resolve(filename)));
        assertEquals(
                Map.of("deleted", true),
                source.reclaim(filename, Support.hex(Support.sha(audio)), 4));
        assertEquals(
                Map.of("deleted", true),
                source.reclaim(filename, Support.hex(Support.sha(audio)), 4));
    }

    @Test
    void eslFrameParserRejectsTruncationAndBoundedLength() throws Exception {
        var frame =
                EslClient.read(
                        new ByteArrayInputStream(
                                "Content-Type: text/event-json\nContent-Length: 2\n\n{}"
                                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals("{}", new String(frame.body(), StandardCharsets.UTF_8));
        assertThrows(
                IOException.class,
                () ->
                        EslClient.read(
                                new ByteArrayInputStream(
                                        "Content-Length: 10000000\n\n"
                                                .getBytes(StandardCharsets.UTF_8))));
        assertThrows(
                IOException.class,
                () ->
                        EslClient.read(
                                new ByteArrayInputStream(
                                        "Content-Length: 5\n\n{}"
                                                .getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void nestedTerminalTransactionRollsBackAllOutboxAndSettlementChanges() throws Exception {
        try (var ledger = new LocalLedger(settings(Map.of()))) {
            assertThrows(
                    Support.Failure.class,
                    () ->
                            ledger.transaction(
                                    db -> {
                                        LocalLedger.execute(
                                                db, "INSERT INTO flags VALUES ('first','1')");
                                        try {
                                            ledger.transaction(
                                                    inner -> {
                                                        LocalLedger.execute(
                                                                inner,
                                                                "INSERT INTO flags VALUES"
                                                                        + " ('second','2')");
                                                        throw new Support.Failure(
                                                                503,
                                                                "simulated terminal persistence"
                                                                        + " failure");
                                                    });
                                        } catch (Support.Failure expected) {
                                        }
                                        return null;
                                    }));
            assertEquals(
                    0L,
                    ledger.<Long>read(
                                    db ->
                                            LocalLedger.scalar(
                                                    db,
                                                    "SELECT COUNT(*) FROM flags WHERE `key` NOT"
                                                            + " LIKE '\\_\\_%'"))
                            .longValue());
        }
    }

    @Test
    void signedCallbackBatchesRespectReceiverPartitionOwnersAndKeepExactAcks() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var received = new AtomicInteger();
        var owners = new CopyOnWriteArrayList<Set<Integer>>();
        var signatures = new AtomicBoolean(true);
        server.createContext(
                "/api/v1/webhooks/telephony/",
                request -> {
                    byte[] bytes = request.getRequestBody().readAllBytes();
                    String stamp = request.getRequestHeaders().getFirst("x-webhook-timestamp");
                    byte[] signed =
                            (stamp + "." + new String(bytes, StandardCharsets.UTF_8))
                                    .getBytes(StandardCharsets.UTF_8);
                    if (!Support.equals(
                            Support.hmac("partition-signing-test", signed),
                            request.getRequestHeaders().getFirst("x-webhook-signature")))
                        signatures.set(false);
                    var body = Support.object(bytes);
                    var events = (List<Map<String, Object>>) body.get("events");
                    var group = new HashSet<Integer>();
                    for (var event : events) {
                        String call =
                                UUID.fromString(
                                                ((Map<?, ?>) event.get("payload"))
                                                        .get("call_id")
                                                        .toString())
                                        .toString();
                        long hash =
                                Integer.toUnsignedLong(
                                        ByteBuffer.wrap(JsonSupport.digest(call)).getInt());
                        group.add((int) (hash % 64) % 2);
                    }
                    owners.add(group);
                    received.addAndGet(events.size());
                    byte[] reply =
                            Support.json(
                                    Map.of(
                                            "version",
                                            body.get("version"),
                                            "result",
                                            "received",
                                            "accepted",
                                            events.stream()
                                                    .map(event -> event.get("id"))
                                                    .toList()));
                    request.sendResponseHeaders(200, reply.length);
                    request.getResponseBody().write(reply);
                    request.close();
                });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var settings =
                settings(
                        Map.of(
                                "VOICE_CALLBACK_BASE_URL",
                                base,
                                "WEBHOOK_SECRET",
                                "partition-signing-test",
                                "VOICE_CALLBACK_BATCH_ENABLED",
                                "true",
                                "VOICE_CALLBACK_BATCH_SIZE",
                                "64",
                                "VOICE_CALLBACK_CONCURRENCY",
                                "128",
                                "VOICE_CALLBACK_PARTITION_GROUPS",
                                "2"));
        try (var ledger = new LocalLedger(settings);
                var callbacks =
                        new VoiceCallbacks(settings, ledger, new VoiceSecurity(settings, ledger))) {
            callbacks.atomic(
                    () -> {
                        for (int i = 0; i < 96; i++) {
                            String call =
                                    UUID.nameUUIDFromBytes(
                                                    ("partition-fixture-" + i)
                                                            .getBytes(StandardCharsets.UTF_8))
                                            .toString();
                            callbacks.post(
                                    base + "/api/v1/webhooks/telephony/status",
                                    Map.of(
                                            "call_id",
                                            i % 2 == 0 ? call : call.toUpperCase(Locale.ROOT),
                                            "sequence",
                                            1));
                        }
                    });
            assertEquals(96, callbacks.tickOnce());
            await(() -> received.get() == 96);
            await(() -> ((Number) ledger.summary().get("pending_callbacks")).longValue() == 0);
            assertTrue(signatures.get());
            assertTrue(owners.size() >= 2);
            assertTrue(owners.stream().allMatch(owner -> owner.size() == 1));
            assertEquals(
                    Set.of(0, 1), owners.stream().flatMap(Set::stream).collect(Collectors.toSet()));
        } finally {
            server.stop(0);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for asynchronous evidence");
            Thread.sleep(10);
        }
    }

    @Test
    void callbackReceiptsCanDrainWhileAdmissionWriterIsBusyWithoutBreakingFifo() throws Exception {
        var received = new CopyOnWriteArrayList<Integer>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/webhooks/telephony/status",
                request -> {
                    received.add(
                            ((Number)
                                            Support.object(request.getRequestBody().readAllBytes())
                                                    .get("sequence"))
                                    .intValue());
                    request.sendResponseHeaders(200, -1);
                    request.close();
                });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var cfg = settings(Map.of("VOICE_CALLBACK_BASE_URL", base));
        var worker = Executors.newSingleThreadExecutor();
        try (var ledger = new LocalLedger(cfg);
                var callbacks = new VoiceCallbacks(cfg, ledger, new VoiceSecurity(cfg, ledger))) {
            for (int sequence : List.of(1, 2))
                callbacks.post(
                        base + "/api/v1/webhooks/telephony/status",
                        Map.of("call_id", "writer-contention", "sequence", sequence));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var held =
                    worker.submit(
                            () ->
                                    ledger.transaction(
                                            db -> {
                                                entered.countDown();
                                                if (!release.await(10, TimeUnit.SECONDS))
                                                    throw new IllegalStateException(
                                                            "fixture release timeout");
                                                return null;
                                            }));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(1, callbacks.tickOnce());
                await(() -> ((Number) ledger.summary().get("pending_callbacks")).longValue() == 1);
                assertFalse(held.isDone());
                assertEquals(List.of(1), received);
                assertEquals(1, callbacks.tickOnce());
                await(() -> ((Number) ledger.summary().get("pending_callbacks")).longValue() == 0);
                assertEquals(List.of(1, 2), received);
                assertFalse(held.isDone());
            } finally {
                release.countDown();
                held.get(3, TimeUnit.SECONDS);
            }
        } finally {
            worker.shutdownNow();
            server.stop(0);
        }
    }
}
