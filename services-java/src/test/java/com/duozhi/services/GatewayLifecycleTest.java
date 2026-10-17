package com.duozhi.services;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real TCP ESL handshake/commands with a synthetic PBX; no telephone line or cloud account. */
class GatewayLifecycleTest {
    @TempDir Path directory;
    private Settings settings(Pbx pbx, Map<String,String> extra) {
        var values = new HashMap<String,String>();
        values.put("VOICE_GATEWAY_DRIVER", "freeswitch_esl");
        values.put("VOICE_SECURITY_DB_PATH", directory.resolve("voice.sqlite").toString());
        values.put("FREESWITCH_RECORDING_DIR", directory.toString());
        values.put("FREESWITCH_RECORDING_MIN_FREE_BYTES", "0");
        values.put("VOICE_CALLBACK_BASE_URL", "http://127.0.0.1:18000");
        values.put("VOICE_CALLBACK_ALLOW_PRIVATE_HTTP", "true");
        values.put("FREESWITCH_ESL_HOST", "127.0.0.1");
        values.put("FREESWITCH_ESL_PORT", Integer.toString(pbx.server.getLocalPort()));
        values.put("FREESWITCH_TTS_ENGINE", "test"); values.put("FREESWITCH_TTS_VOICE", "test");
        values.put("VOICE_MAX_CONCURRENT", "100"); values.put("VOICE_CPS", "100");
        values.put("VOICE_SECURITY_ROUTES_JSON", new String(Support.json(Map.of("1:0", Map.of(
            "gateway", "approved", "caller_id", "+8613800138000", "allowed_prefixes", List.of("86"),
            "max_concurrent", 100, "cps", 100, "calls_per_day", 1000, "hour_budget_minor", 10000,
            "day_budget_minor", 50000, "rate_minor_per_minute", 10, "max_duration_sec", 60))), StandardCharsets.UTF_8));
        for (String name : List.of("SERVICE_TOKEN", "VOICE_COMMAND_SECRET", "VOICE_SECURITY_ADMIN_TOKEN", "WEBHOOK_TOKEN", "WEBHOOK_SECRET", "FREESWITCH_ESL_PASSWORD")) values.put(name, name + "-independent-0000000000000000000000000000");
        values.putAll(extra); return new Settings(values);
    }
    private static Map<String,Object> dial(String call) {
        return Map.of("call_id", call, "phone", "+8613900139000", "webhook_url", "http://127.0.0.1:18000/api/v1/webhooks/telephony/status", "metadata", Map.of("tenant_id", 1, "attempt", 1, "recording_notice", true));
    }
    @Test void pendingNoticesDoNotOccupyStartupWorkersAndHangupFencesLateCompletion() throws Exception {
        try (var pbx = new Pbx(); var gateway = new VoiceGateway(settings(pbx, Map.of("JAVA_VOICE_LIFECYCLE_THREADS", "4")))) {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String uuid = gateway.post("dial", dial("cohort-" + i)).get("provider_call_id").toString(); ids.add(uuid);
                gateway.event(Map.of("Event-Name", "CHANNEL_ANSWER", "Unique-ID", uuid));
            }
            await(() -> pbx.count("api uuid_broadcast ") == 12);
            String cancelled = ids.get(0);
            gateway.event(Map.of("Event-Name", "CHANNEL_HANGUP", "Unique-ID", cancelled));
            for (String uuid : ids) gateway.event(Map.of("Event-Name", "CHANNEL_EXECUTE_COMPLETE", "Unique-ID", uuid, "Application", "speak"));
            await(() -> pbx.count("api uuid_record ") == 11);
            assertTrue(pbx.commands.stream().noneMatch(c -> c.startsWith("api uuid_record " + cancelled + " ")));
            assertEquals(12, pbx.count("bgapi originate "));
        }
    }
    @Test void hangupWhileNoticeSynthesisIsPendingCannotStartMediaLater() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/tts", request -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); byte[] body = Support.json(Map.of("media_uri", "/installed/notice.wav")); request.sendResponseHeaders(200, body.length); request.getResponseBody().write(body); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            finally { request.close(); }
        }); http.start();
        try (var pbx = new Pbx(); var gateway = new VoiceGateway(settings(pbx, Map.of("FREESWITCH_TTS_HTTP_ENDPOINT", "http://127.0.0.1:" + http.getAddress().getPort() + "/tts")))) {
            String uuid = gateway.post("dial", dial("pending")).get("provider_call_id").toString();
            gateway.event(Map.of("Event-Name", "CHANNEL_ANSWER", "Unique-ID", uuid));
            assertTrue(entered.await(3, TimeUnit.SECONDS)); gateway.event(Map.of("Event-Name", "CHANNEL_HANGUP", "Unique-ID", uuid));
            release.countDown(); await(() -> gateway.bindings.get(uuid).stopped);
            // A barrier behind the HTTP response proves the late-start path has had time to run.
            Thread.sleep(150);
            assertEquals(0, pbx.count("api uuid_broadcast ")); assertEquals(0, pbx.count("api uuid_record "));
        } finally { release.countDown(); http.stop(0); }
    }
    @Test void explicitNativeFallbackRequiresPbxCleanupBeforeLegacyStarts() throws Exception {
        try (var pbx = new Pbx()) {
            pbx.rejectMediaStart = true;
            var config = settings(pbx, Map.of("VOICE_AI_PIPELINE", "pipecat", "PIPECAT_MEDIA_PROTOCOL", "voismart", "PIPECAT_OPENAI_API_KEY", "unused-synthetic-key",
                "PIPECAT_MEDIA_WS_BASE", "ws://127.0.0.1:1/v1/pipecat/media", "PIPECAT_FALLBACK_TO_LEGACY", "true",
                "FREESWITCH_MEDIA_START_COMMAND_TEMPLATE", "uuid_setvar {uuid} synthetic_legacy_started true"));
            try (var gateway = new VoiceGateway(config)) {
                var request = new HashMap<>(dial("fallback")); request.put("metadata", Map.of("tenant_id", 1, "attempt", 1, "recording_enabled", false));
                String uuid = gateway.post("dial", request).get("provider_call_id").toString();
                gateway.event(Map.of("Event-Name", "CHANNEL_ANSWER", "Unique-ID", uuid));
                await(() -> pbx.commands.contains("api uuid_setvar " + uuid + " synthetic_legacy_started true"));
                int stopped = pbx.commands.indexOf("api uuid_raw_audio_stream " + uuid + " stop");
                assertTrue(stopped >= 0 && stopped < pbx.commands.indexOf("api uuid_setvar " + uuid + " synthetic_legacy_started true"));
                assertEquals("legacy", gateway.bindings.get(uuid).pipeline); assertNull(gateway.media.session("fallback"));
            }
        }
    }
    @Test void slowPbxHangupDoesNotSerializeTheGlobalTimeoutScheduler() throws Exception {
        try (var pbx = new Pbx()) {
            pbx.delayKillMillis = 800;
            try (var gateway = new VoiceGateway(settings(pbx, Map.of("FREESWITCH_PLAYBACK_TIMEOUT_SEC", "1")))) {
                for (int i = 0; i < 12; i++) {
                    String uuid = gateway.post("dial", dial("timeout-" + i)).get("provider_call_id").toString();
                    gateway.event(Map.of("Event-Name", "CHANNEL_ANSWER", "Unique-ID", uuid));
                }
                await(() -> pbx.count("api uuid_broadcast ") == 12);
                await(() -> pbx.count("api uuid_kill ") == 12);
            }
        }
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) fail("no expected ESL command evidence"); Thread.sleep(10); }
    }
    static final class Pbx implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        final List<String> commands = new CopyOnWriteArrayList<>();
        final ExecutorService io = Executors.newCachedThreadPool(); volatile boolean running = true, rejectMediaStart;
        volatile long delayKillMillis;
        Pbx() throws IOException { io.submit(() -> { while (running) try { Socket socket = server.accept(); io.submit(() -> connection(socket)); } catch (IOException stopped) { return; } }); }
        int count(String prefix) { return (int)commands.stream().filter(c -> c.startsWith(prefix)).count(); }
        void connection(Socket socket) {
            try (socket) {
                socket.setSoTimeout(3000); var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)); var output = socket.getOutputStream();
                output.write("Content-Type: auth/request\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
                String auth = input.readLine(); input.readLine(); if (auth == null || !auth.startsWith("auth FREESWITCH_ESL_PASSWORD-")) return;
                output.write("Content-Type: command/reply\nReply-Text: +OK accepted\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
                String command = input.readLine(); input.readLine(); if (command == null) return; commands.add(command);
                if (command.startsWith("api uuid_kill ") && delayKillMillis > 0) try { Thread.sleep(delayKillMillis); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                if (command.startsWith("bgapi ")) output.write(("Content-Type: command/reply\nReply-Text: +OK Job-UUID: " + UUID.randomUUID() + "\n\n").getBytes(StandardCharsets.UTF_8));
                else { byte[] body = (rejectMediaStart && command.startsWith("api uuid_raw_audio_stream ") && command.contains(" start ") ? "-ERR synthetic module failure" : "+OK").getBytes(StandardCharsets.UTF_8); output.write(("Content-Type: api/response\nContent-Length: " + body.length + "\n\n").getBytes(StandardCharsets.UTF_8)); output.write(body); }
                output.flush();
            } catch (IOException ignored) { }
        }
        @Override public void close() throws IOException { running = false; server.close(); io.shutdownNow(); }
    }
}
