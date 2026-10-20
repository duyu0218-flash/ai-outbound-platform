package com.duozhi.outbound.embedded;

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
    Settings settings(Pbx pbx, Map<String,String> extra) {
        var values = new HashMap<String,String>();
        values.put("VOICE_GATEWAY_DRIVER", "freeswitch_esl");
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
        values.putAll(extra); return new Settings(LedgerTestDatabase.settings(values));
    }
    private static Map<String,Object> dial(String call) {
        return Map.of("call_id", call, "phone", "+8613900139000", "webhook_url", "http://127.0.0.1:18000/api/v1/webhooks/telephony/status", "metadata", Map.of("tenant_id", 1, "attempt", 1, "recording_notice", true));
    }
    private static VoiceController.Command signed(Settings settings,Map<String,Object> payload){
        byte[] body=Support.json(payload);String stamp=Long.toString(System.currentTimeMillis()/1000),nonce=UUID.randomUUID().toString();byte[] prefix=(stamp+"."+nonce+"./v1/call/dial.").getBytes(StandardCharsets.UTF_8),message=Arrays.copyOf(prefix,prefix.length+body.length);System.arraycopy(body,0,message,prefix.length,body.length);
        return new VoiceController.Command("dial",body,Map.of("Authorization","Bearer "+settings.secret("SERVICE_TOKEN"),"x-voice-timestamp",stamp,"x-voice-nonce",nonce,"x-voice-signature",Support.hmac(settings.secret("VOICE_COMMAND_SECRET"),message)));
    }
    @Test void batchedRealDriverCommandsStillAuthorizeEachCallAndRetryIdempotently()throws Exception{
        try(var pbx=new Pbx()){var settings=settings(pbx,Map.of());var controller=new VoiceController(settings);try{
            var valid=signed(settings,dial("batch-authorized"));var denied=new HashMap<>(dial("batch-denied"));denied.put("phone","+33123456789");
            var unauthorized=new VoiceController.Command("dial",Support.json(dial("batch-unsigned")),Map.of());
            var results=controller.commandsInternalBatch(List.of(valid,signed(settings,denied),unauthorized));assertEquals(List.of(200,403,401),results.stream().map(VoiceController.CommandResult::status).toList());
            assertEquals(1,pbx.count("bgapi originate "));assertEquals(200,controller.commandsInternalBatch(List.of(signed(settings,dial("batch-authorized")))).get(0).status());assertEquals(1,pbx.count("bgapi originate "));
        }finally{controller.close();}}
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
    @Test void failedAgentBridgeKeepsCustomerAndRestartsLegacyMedia()throws Exception{
        try(var pbx=new Pbx();var gateway=new VoiceGateway(settings(pbx,Map.of("FREESWITCH_MEDIA_START_COMMAND_TEMPLATE","uuid_setvar {uuid} synthetic_asr_started true","FREESWITCH_MEDIA_STOP_COMMAND_TEMPLATE","uuid_setvar {uuid} synthetic_asr_stopped true")))){
            var request=new HashMap<>(dial("transfer-recovery"));request.put("metadata",Map.of("tenant_id",1,"attempt",1,"recording_enabled",false));
            String uuid=gateway.post("dial",request).get("provider_call_id").toString();gateway.event(Map.of("Event-Name","CHANNEL_ANSWER","Unique-ID",uuid));
            await(()->pbx.commands.contains("api uuid_setvar "+uuid+" synthetic_asr_started true"));
            gateway.post("transfer",Map.of("call_id","transfer-recovery","tenant_id",1,"expected_attempt",1,"target_group","agent:7"));assertTrue(gateway.bindings.get(uuid).stopped);
            gateway.event(Map.of("Event-Name","CHANNEL_EXECUTE_COMPLETE","Unique-ID",uuid,"Application","bridge","Application-Response","NO_ANSWER"));
            await(()->pbx.commands.stream().filter(c->c.equals("api uuid_setvar "+uuid+" synthetic_asr_started true")).count()==2);
            assertFalse(gateway.bindings.get(uuid).stopped);assertFalse(gateway.bindings.get(uuid).human);assertEquals(0,pbx.count("api uuid_kill "));
            gateway.post("transfer",Map.of("call_id","transfer-recovery","tenant_id",1,"expected_attempt",1,"target_group","agent:8"));
            gateway.event(Map.of("Event-Name","CHANNEL_BRIDGE","Unique-ID",uuid));assertTrue(gateway.bindings.get(uuid).human);
            gateway.event(Map.of("Event-Name","CHANNEL_UNBRIDGE","Unique-ID",uuid));assertEquals(1,pbx.count("api uuid_kill "));
        }
    }
    @Test void rejectedTransferCommandCancelsBridgeAndRestartsAi()throws Exception{
        try(var pbx=new Pbx();var gateway=new VoiceGateway(settings(pbx,Map.of("FREESWITCH_MEDIA_START_COMMAND_TEMPLATE","uuid_setvar {uuid} synthetic_asr_started true")))){
            var request=new HashMap<>(dial("command-recovery"));request.put("metadata",Map.of("tenant_id",1,"attempt",1,"recording_enabled",false));
            String uuid=gateway.post("dial",request).get("provider_call_id").toString();gateway.event(Map.of("Event-Name","CHANNEL_ANSWER","Unique-ID",uuid));
            await(()->pbx.count("api uuid_setvar "+uuid+" synthetic_asr_started true")==1);pbx.rejectAgentTransfer=true;
            assertThrows(IOException.class,()->gateway.post("transfer",Map.of("call_id","command-recovery","tenant_id",1,"expected_attempt",1,"target_group","agent:7")));
            await(()->pbx.count("api uuid_setvar "+uuid+" synthetic_asr_started true")==2);
            assertTrue(pbx.commands.contains("api uuid_transfer "+uuid+" park inline"));assertFalse(gateway.bindings.get(uuid).stopped);assertEquals(0,pbx.count("api uuid_kill "));
        }
    }
    @Test void customerHangupWhileRecoveryChecksPbxCannotReopenMedia()throws Exception{
        try(var pbx=new Pbx();var gateway=new VoiceGateway(settings(pbx,Map.of("FREESWITCH_MEDIA_START_COMMAND_TEMPLATE","uuid_setvar {uuid} synthetic_asr_started true")))){
            var request=new HashMap<>(dial("recovery-hangup-fence"));request.put("metadata",Map.of("tenant_id",1,"attempt",1,"recording_enabled",false));
            String uuid=gateway.post("dial",request).get("provider_call_id").toString();gateway.event(Map.of("Event-Name","CHANNEL_ANSWER","Unique-ID",uuid));
            await(()->pbx.count("api uuid_setvar "+uuid+" synthetic_asr_started true")==1);
            gateway.post("transfer",Map.of("call_id","recovery-hangup-fence","tenant_id",1,"expected_attempt",1,"target_group","agent:7"));
            pbx.existsEntered=new CountDownLatch(1);pbx.existsRelease=new CountDownLatch(1);
            var recovery=CompletableFuture.runAsync(()->{try{gateway.event(Map.of("Event-Name","CHANNEL_EXECUTE_COMPLETE","Unique-ID",uuid,"Application","bridge","Application-Response","NO_ANSWER"));}catch(Exception error){throw new CompletionException(error);}});
            assertTrue(pbx.existsEntered.await(3,TimeUnit.SECONDS));gateway.event(Map.of("Event-Name","CHANNEL_HANGUP","Unique-ID",uuid));pbx.existsRelease.countDown();recovery.get(5,TimeUnit.SECONDS);
            assertTrue(gateway.bindings.get(uuid).terminal);assertTrue(gateway.bindings.get(uuid).stopped);assertEquals(1,pbx.count("api uuid_setvar "+uuid+" synthetic_asr_started true"));
        }
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) fail("no expected ESL command evidence"); Thread.sleep(10); }
    }
    static final class Pbx implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        final List<String> commands = new CopyOnWriteArrayList<>();
        final ExecutorService io = Executors.newCachedThreadPool(); volatile boolean running = true, rejectMediaStart, rejectAgentTransfer;
        volatile long delayKillMillis;
        volatile CountDownLatch existsEntered,existsRelease;
        Pbx() throws IOException { io.submit(() -> { while (running) try { Socket socket = server.accept(); io.submit(() -> connection(socket)); } catch (IOException stopped) { return; } }); }
        int count(String prefix) { return (int)commands.stream().filter(c -> c.startsWith(prefix)).count(); }
        void connection(Socket socket) {
            try (socket) {
                socket.setSoTimeout(3000); var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)); var output = socket.getOutputStream();
                output.write("Content-Type: auth/request\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
                String auth = input.readLine(); input.readLine(); if (auth == null || !auth.startsWith("auth FREESWITCH_ESL_PASSWORD-")) return;
                output.write("Content-Type: command/reply\nReply-Text: +OK accepted\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
                String command = input.readLine(); input.readLine(); if (command == null) return; commands.add(command);
                if(command.startsWith("api uuid_exists ")&&existsEntered!=null){existsEntered.countDown();try{existsRelease.await(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}
                if (command.startsWith("api uuid_kill ") && delayKillMillis > 0) try { Thread.sleep(delayKillMillis); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                if (command.startsWith("bgapi ")) output.write(("Content-Type: command/reply\nReply-Text: +OK Job-UUID: " + UUID.randomUUID() + "\n\n").getBytes(StandardCharsets.UTF_8));
                else { byte[] body = (rejectAgentTransfer&&command.startsWith("api uuid_transfer ")&&command.contains(" XML agent-restricted") ? "-ERR synthetic transfer failure" : rejectMediaStart && command.startsWith("api uuid_raw_audio_stream ") && command.contains(" start ") ? "-ERR synthetic module failure" : command.startsWith("api uuid_exists ") ? "true" : "+OK").getBytes(StandardCharsets.UTF_8); output.write(("Content-Type: api/response\nContent-Length: " + body.length + "\n\n").getBytes(StandardCharsets.UTF_8)); output.write(body); }
                output.flush();
            } catch (IOException ignored) { }
        }
        @Override public void close() throws IOException { running = false; server.close(); io.shutdownNow(); }
    }
}
