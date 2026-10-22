package com.duozhi.services;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class VoiceTest {
    @TempDir
    Path directory;
    private Settings settings(Map<String,String> overrides) {
        var values = new HashMap<>(Map.of("VOICE_GATEWAY_DRIVER", "mock", "VOICE_SECURITY_DB_PATH", directory.resolve("voice.sqlite").toString(), "VOICE_CALLBACK_BASE_URL", "http://127.0.0.1:18000", "VOICE_CALLBACK_ALLOW_PRIVATE_HTTP", "true", "FREESWITCH_RECORDING_DIR", directory.toString(), "VOICE_COMMAND_SECRET", "command-secret-independent-00000000000"));
        var route = new HashMap<String,Object>(Map.of("gateway", "approved", "caller_id", "+8613800138000", "allowed_prefixes", List.of("86"), "denied_prefixes", List.of("86900"), "max_concurrent", 2, "cps", 100, "calls_per_day", 1000, "hour_budget_minor", 10000, "day_budget_minor", 50000, "rate_minor_per_minute", 10));
        route.put("max_duration_sec", 60);
        values.put("VOICE_SECURITY_ROUTES_JSON", new String(Support.json(Map.of("1:0", route)), StandardCharsets.UTF_8));
        values.put("VOICE_CPS", "100"); values.putAll(overrides); return new Settings(values);
    }
    private Map<String,Object> dial(String call, int tenant, int attempt) { return Map.of("call_id", call, "phone", "+8613900139000", "webhook_url", "http://127.0.0.1:18000/api/v1/webhooks/telephony/status", "metadata", Map.of("tenant_id", tenant, "attempt", attempt, "mode", "ai_only", "recording_enabled", false)); }
    @Test
    void admissionIdempotencyBudgetAndTenantFences() throws Exception {
        var settings = settings(Map.of()); try (var ledger = new LocalLedger(settings.get("VOICE_SECURITY_DB_PATH", ""))) {
            var security = new VoiceSecurity(settings, ledger); var request = dial("call-a", 1, 1); var route = security.policy(request);
            var first = security.admit(request, route, true, 10); assertTrue(first.fresh()); assertFalse(security.admit(request, route, true, 10).fresh());
            var changed = new HashMap<>(request); changed.put("phone", "+8613900139001"); assertEquals(409, assertThrows(Support.Failure.class, () -> security.admit(changed, route, true, 10)).status);
            assertEquals(403, assertThrows(Support.Failure.class, () -> security.policy(dial("b", 2, 1))).status);
            security.admit(dial("b", 1, 1), route, true, 10);
            assertEquals(429, assertThrows(Support.Failure.class, () -> security.admit(dial("c", 1, 1), route, true, 10)).status);
            assertThrows(Support.Failure.class, () -> security.lookup("call-a", 2));
            security.finish(first.row().get("uuid").toString(), null); assertEquals(20, ((Number)security.lookup("call-a", 1).get("cost")).intValue());
        }
    }
    @Test
    void signedCommandReplayIsDurableAcrossRestart() throws Exception {
        var settings = settings(Map.of()); String stamp = Long.toString(System.currentTimeMillis() / 1000), nonce = "nonce-012345678901234567890", path = "/v1/call/dial"; byte[] body = Support.json(dial("a", 1, 1));
        var prefix = (stamp + "." + nonce + "." + path + ".").getBytes(StandardCharsets.UTF_8); var signed = Arrays.copyOf(prefix, prefix.length + body.length); System.arraycopy(body, 0, signed, prefix.length, body.length); String signature = Support.hmac(settings.secret("VOICE_COMMAND_SECRET"), signed);
        try (var ledger = new LocalLedger(settings.get("VOICE_SECURITY_DB_PATH", ""))) { new VoiceSecurity(settings, ledger).command(path, body, stamp, nonce, signature); }
        try (var ledger = new LocalLedger(settings.get("VOICE_SECURITY_DB_PATH", ""))) {
            var security = new VoiceSecurity(settings, ledger); assertEquals(409, assertThrows(Support.Failure.class, () -> security.command(path, body, stamp, nonce, signature)).status);
            assertEquals(401, assertThrows(Support.Failure.class, () -> security.command(path, new byte[0], stamp, nonce, signature)).status);
        }
    }
    @Test
    void unsafeRoutesTargetsAndDuplicateOwnerFailClosed() throws Exception {
        var settings = settings(Map.of()); try (var ledger = new LocalLedger(settings.get("VOICE_SECURITY_DB_PATH", ""))) {
            var security = new VoiceSecurity(settings, ledger);
            assertThrows(Exception.class, () -> new LocalLedger(settings.get("VOICE_SECURITY_DB_PATH", "")));
            assertThrows(Support.Failure.class, () -> security.callback("http://127.0.0.1:18000/api/v1/webhooks/telephony/status?x=1"));
            var request = new HashMap<>(dial("a", 1, 1)); request.put("phone", "+86900123456"); assertThrows(Support.Failure.class, () -> security.policy(request));
            request.put("phone", "+８６13900139000"); assertThrows(Support.Failure.class, () -> security.policy(request));
            ledger.stopped(true, "test emergency stop"); assertThrows(Support.Failure.class, () -> security.admit(dial("a", 1, 1), Support.map(security.routes.get("1:0")), true, 10));
        }
    }
    @Test
    void unconfirmedVoicePermitsNeverExpireOrReleaseCallReservation() throws Exception {
        var budgets = Map.of("asr", Map.of("provider", "test", "account", "a", "region", "cn", "product", "asr", "concurrency", 1), "tts", Map.of("provider", "test", "account", "a", "region", "cn", "product", "tts", "concurrency", 1, "requests_per_minute", 2));
        var settings = settings(Map.of("VOICE_QUOTA_ENABLED", "true", "VOICE_QUOTA_BUDGETS_JSON", new String(Support.json(budgets), StandardCharsets.UTF_8)));
        try (var ledger = new LocalLedger(settings.get("VOICE_SECURITY_DB_PATH", ""))) {
            var quota = new VoiceQuota(settings, ledger); quota.validate();
            ledger.transaction(db -> { quota.reserve(db, "a", 1); quota.acquire(db, "physical-a", "asr", "a", 1, "epoch-a"); quota.release(db, "physical-a", "epoch-a", false); return null; });
            assertEquals(1, ((Number)Support.map(quota.snapshot().get("asr")).get("unknown")).intValue());
            var security = new VoiceSecurity(settings, ledger); assertThrows(Support.Failure.class, () -> security.admit(dial("b", 1, 1), security.policy(dial("b", 1, 1)), true, 10));
        }
    }
    @Test
    void callbacksKeepPerCallOrderAndRequireExactly200Ack() throws Exception {
        List<Integer> received = new CopyOnWriteArrayList<>(); AtomicInteger status = new AtomicInteger(202); AtomicBoolean validSignature = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/webhooks/telephony/status", request -> {
            byte[] body = request.getRequestBody().readAllBytes(); String stamp = request.getRequestHeaders().getFirst("x-webhook-timestamp"); byte[] prefix = (stamp + ".").getBytes(StandardCharsets.UTF_8), bytes = Arrays.copyOf(prefix, prefix.length + body.length); System.arraycopy(body, 0, bytes, prefix.length, body.length);
            validSignature.set(Support.equals(Support.hmac("callback-signing-test", bytes), request.getRequestHeaders().getFirst("x-webhook-signature")));
            received.add(((Number)Support.object(body).get("sequence")).intValue()); request.sendResponseHeaders(status.get(), -1); request.close();
        }); server.start(); String url = "http://127.0.0.1:" + server.getAddress().getPort(); var settings = settings(Map.of("VOICE_CALLBACK_BASE_URL", url, "WEBHOOK_SECRET", "callback-signing-test"));
        try (var ledger = new LocalLedger(settings.get("VOICE_SECURITY_DB_PATH", "")); var callbacks = new VoiceCallbacks(settings, ledger, new VoiceSecurity(settings, ledger))) {
            callbacks.post(url + "/api/v1/webhooks/telephony/status", Map.of("call_id", "a", "sequence", 1)); callbacks.post(url + "/api/v1/webhooks/telephony/status", Map.of("call_id", "a", "sequence", 2));
            callbacks.tickOnce(); await(() -> !received.isEmpty()); await(() -> callbacks.inflight() == 0); assertEquals(List.of(1), received); assertEquals(2, ((Number)ledger.summary().get("pending_callbacks")).intValue()); assertTrue(validSignature.get());
            status.set(200); ledger.transaction(db -> { LocalLedger.execute(db, "UPDATE outbox SET due=0"); return null; });
            callbacks.tickOnce(); await(() -> received.size() >= 2); await(() -> callbacks.inflight() == 0); callbacks.tickOnce(); await(() -> received.size() >= 3); assertEquals(List.of(1, 1, 2), received);
        } finally { server.stop(0); }
    }
    @Test
    void signedSourceCleanupNeedsCompletionChecksumAndRetention() throws Exception {
        var settings = settings(Map.of("VOICE_NODE_ID", "node-a")); var source = new RecordingSource(settings); String filename = "test-call.wav"; byte[] audio = new byte[]{1, 2, 3, 4}; Files.write(directory.resolve(filename), audio);
        long expires = System.currentTimeMillis() / 1000 + 60; assertEquals(directory.resolve(filename).toRealPath(), source.authorized(filename, expires, source.signature(filename, expires)));
        assertThrows(Support.Failure.class, () -> source.authorized(filename, expires, "forged")); assertThrows(Support.Failure.class, () -> source.reclaim(filename, Support.hex(Support.sha(audio)), 4));
        source.complete(filename); assertThrows(Support.Failure.class, () -> source.reclaim(filename, "0".repeat(64), 4)); assertTrue(Files.exists(directory.resolve(filename)));
        assertEquals(Map.of("deleted", true), source.reclaim(filename, Support.hex(Support.sha(audio)), 4)); assertEquals(Map.of("deleted", true), source.reclaim(filename, Support.hex(Support.sha(audio)), 4));
    }
    @Test
    void eslFrameParserRejectsTruncationAndBoundedLength() throws Exception {
        var frame = EslClient.read(new ByteArrayInputStream("Content-Type: text/event-json\nContent-Length: 2\n\n{}".getBytes(StandardCharsets.UTF_8))); assertEquals("{}", new String(frame.body(), StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> EslClient.read(new ByteArrayInputStream("Content-Length: 10000000\n\n".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> EslClient.read(new ByteArrayInputStream("Content-Length: 5\n\n{}".getBytes(StandardCharsets.UTF_8))));
    }
    @Test
    void nestedTerminalTransactionRollsBackAllOutboxAndSettlementChanges() throws Exception {
        try (var ledger = new LocalLedger(directory.resolve("atomic.sqlite").toString())) {
            assertThrows(Support.Failure.class, () -> ledger.transaction(db -> {
                LocalLedger.execute(db, "INSERT INTO flags VALUES ('first','1')");
                try { ledger.transaction(inner -> { LocalLedger.execute(inner, "INSERT INTO flags VALUES ('second','2')"); throw new Support.Failure(503, "simulated terminal persistence failure"); }); } catch (Support.Failure expected) { }
                return null;
            }));
            assertEquals(0L, ledger.<Long>read(db -> LocalLedger.scalar(db, "SELECT COUNT(*) FROM flags")).longValue());
        }
    }
    private static void await(BooleanSupplier condition) throws Exception { long deadline = System.nanoTime() + 5_000_000_000L; while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) fail("timed out waiting for asynchronous evidence"); Thread.sleep(10); } }
}
