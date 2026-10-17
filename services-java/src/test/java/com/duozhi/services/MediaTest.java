package com.duozhi.services;

import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaTest {
    private ConfigurableApplicationContext fixture;
    private String origin;
    private final List<Map<String,Object>> events = new CopyOnWriteArrayList<>();
    @BeforeEach void start() {
        fixture = SpringApplication.run(SpeechFixture.class, "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off", "--logging.level.root=ERROR");
        origin = "127.0.0.1:" + ((ServletWebServerApplicationContext)fixture).getWebServer().getPort();
    }
    @AfterEach void stop() { fixture.close(); }
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @EnableWebSocket
    static class SpeechFixture implements WebSocketConfigurer {
        static volatile CountDownLatch pendingStartup = new CountDownLatch(1);
        @Bean SpeechController speechController() { return new SpeechController(); }
        @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(new AbstractWebSocketHandler() {
                @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                    var data = Support.object(message.asBytes()); String name = Support.str(Support.map(data.get("header")), "name", "");
                    if (name.equals("StartTranscription") && session.getUri().getPath().equals("/asr-delayed")) pendingStartup.countDown();
                    else if (name.equals("StartTranscription")) session.sendMessage(new TextMessage(Support.json(Map.of("header", Map.of("name", "TranscriptionStarted", "status", 20000000)))));
                    else if (name.equals("StopTranscription")) session.sendMessage(new TextMessage(Support.json(Map.of("header", Map.of("name", "TranscriptionCompleted", "status", 20000000)))));
                }
                @Override protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
                    session.sendMessage(new TextMessage(Support.json(Map.of("header", Map.of("name", "SentenceEnd", "status", 20000000, "message_id", "sentence-1"), "payload", Map.of("result", "你好，请介绍一下", "index", 1, "begin_time", 0, "time", 200, "confidence", .95)))));
                }
            }, "/asr", "/asr-delayed");
        }
    }
    @RestController
    static class SpeechController {
        @PostMapping(value="/v1/audio/speech", produces="application/octet-stream") byte[] tts(@RequestBody Map<String,Object> payload) {
            assertEquals("pcm", payload.get("response_format")); assertEquals("gpt-4o-mini-tts", payload.get("model"));
            byte[] pcm = new byte[4800]; for (int i = 0; i < pcm.length / 2; i++) { short sample = (short)(Math.sin(2 * Math.PI * 440 * i / 24000) * 10000); pcm[2 * i] = (byte)sample; pcm[2 * i + 1] = (byte)(sample >> 8); } return pcm;
        }
    }
    private Settings settings(Map<String,String> extra) {
        var settings = new HashMap<>(Map.of("PIPECAT_OPENAI_API_KEY", "synthetic-key", "PIPECAT_OPENAI_BASE_URL", "http://" + origin + "/v1", "PIPECAT_STT_PROVIDER", "aliyun-nls", "ALIYUN_NLS_APPKEY", "synthetic-app", "ALIYUN_NLS_TOKEN", "synthetic-token", "ALIYUN_NLS_GATEWAY_URL", "ws://" + origin + "/asr", "PIPECAT_SAMPLE_RATE", "8000", "PIPECAT_MEDIA_WS_BASE", "ws://127.0.0.1/v1/pipecat/media")); settings.putAll(extra); return new Settings(settings);
    }
    private static WebSocketSession socket(List<WebSocketMessage<?>> output) {
        AtomicBoolean open = new AtomicBoolean(true);
        return (WebSocketSession)Proxy.newProxyInstance(MediaTest.class.getClassLoader(), new Class[]{WebSocketSession.class}, (proxy, method, args) -> switch(method.getName()) {
            case "getId" -> "synthetic-pbx";
            case "isOpen" -> open.get();
            case "sendMessage" -> { output.add((WebSocketMessage<?>)args[0]); yield null; }
            case "close" -> { open.set(false); yield null; }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "synthetic-pbx";
            default -> null;
        });
    }
    @Test void actualAsrWebSocketAndTtsHttpProduceTranscriptAndPcm() throws Exception {
        List<WebSocketMessage<?>> audio = new CopyOnWriteArrayList<>(); List<String> permits = new CopyOnWriteArrayList<>();
        try (var media = new NativeMedia(settings(Map.of()), (url, payload) -> events.add(payload), (session, id, kind, acquire, confirmed) -> permits.add(kind + ":" + acquire + ":" + confirmed), 2)) {
            var session = media.create("call-a", "http://controller/speech", "http://controller/media", Map.of("tenant_id", 1, "attempt", 1)); var socket = socket(audio);
            media.connect(socket, session.token); assertTrue(session.started.isDone());
            media.audio(socket, new byte[320]); await(() -> events.stream().anyMatch(e -> "sentence-1".equals(e.get("event_id"))));
            var transcript = events.stream().filter(e -> "sentence-1".equals(e.get("event_id"))).findFirst().orElseThrow(); assertEquals("你好，请介绍一下", transcript.get("transcript")); assertEquals(.95, transcript.get("confidence")); assertEquals("sentence-1", session.latest);
            String playback = media.speak(session.call, "你好", "sentence-1", "reply-1");
            assertEquals(playback, media.speak(session.call, "你好", "sentence-1", "reply-1"));
            assertThrows(Support.Failure.class, () -> media.speak(session.call, "different", "sentence-1", "reply-1"));
            await(() -> audio.stream().anyMatch(message -> message instanceof BinaryMessage));
            await(() -> permits.contains("tts:false:true"));
            int bytes = audio.stream().filter(message -> message instanceof BinaryMessage).mapToInt(WebSocketMessage::getPayloadLength).sum(); assertEquals(1600, bytes);
            assertThrows(Support.Failure.class, () -> media.fence(session.call, "stale-generation", false));
            media.closeSession(session.call, true); assertTrue(session.terminated.isDone()); assertTrue(permits.contains("asr:false:false"));
        }
    }
    @Test void modulePlayoutOwnsCompletionAndIgnoresStaleEvents() throws Exception {
        List<WebSocketMessage<?>> audio = new CopyOnWriteArrayList<>();
        try (var media = new NativeMedia(settings(Map.of("PIPECAT_MEDIA_PROTOCOL", "voismart")), (url, payload) -> events.add(payload), (session, id, kind, acquire, confirmed) -> {}, 1)) {
            var session = media.create("call-a", "", "http://controller/media", Map.of("attempt", 1)); media.connect(socket(audio), session.token);
            media.speak(session.call, "你好", null, "reply-1"); await(() -> audio.stream().anyMatch(m -> m instanceof TextMessage && ((TextMessage)m).getPayload().contains("response.output_audio.done")));
            assertFalse(session.playback.isEmpty()); assertThrows(Support.Failure.class, () -> media.speak(session.call, "next", null, "reply-2"));
            media.module(session.call, "openai_speech_start", 100); assertTrue(session.speaking);
            media.module(session.call, "openai_speech_stop", 99); assertTrue(session.speaking);
            media.module(session.call, "openai_speech_stop", 101); assertFalse(session.speaking); assertTrue(session.playback.isEmpty());
        }
    }
    @Test void closingDuringUnacknowledgedAsrStartupDoesNotWaitForStartupTimeout() throws Exception {
        SpeechFixture.pendingStartup = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        List<String> permits = new CopyOnWriteArrayList<>();
        try (var media = new NativeMedia(settings(Map.of("ALIYUN_NLS_GATEWAY_URL", "ws://" + origin + "/asr-delayed")), (url, payload) -> {},
            (session, id, kind, acquire, confirmed) -> permits.add(kind + ":" + acquire + ":" + confirmed), 1)) {
            var session = media.create("pending", "", "", Map.of("attempt", 1));
            var connecting = executor.submit(() -> { try { media.connect(socket(new CopyOnWriteArrayList<>()), session.token); return false; } catch (Exception closed) { return true; } });
            assertTrue(SpeechFixture.pendingStartup.await(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> media.closeSession(session.call, false));
            assertTrue(connecting.get(1, TimeUnit.SECONDS)); assertTrue(session.terminated.isDone());
            assertEquals(1, permits.stream().filter(p -> p.startsWith("asr:false:")).count());
        } finally { executor.shutdownNow(); }
    }
    @Test void resamplingCarriesSamplePhaseAcrossArbitraryChunks() {
        byte[] pcm = new byte[4800]; new Random(42).nextBytes(pcm);
        var whole = new NativeMedia.PcmResampler(24000, 8000); var expected = new java.io.ByteArrayOutputStream(); expected.writeBytes(whole.convert(pcm)); expected.writeBytes(whole.finish());
        var resampler = new NativeMedia.PcmResampler(24000, 8000); var output = new java.io.ByteArrayOutputStream();
        for (int offset = 0; offset < pcm.length; offset += 37) output.writeBytes(resampler.convert(Arrays.copyOfRange(pcm, offset, Math.min(pcm.length, offset + 37))));
        output.writeBytes(resampler.finish()); assertArrayEquals(expected.toByteArray(), output.toByteArray()); assertEquals(1600, expected.size());
    }
    @Test void downsamplingFiltersOutOfBandAudio() {
        double low = resampledRms(440), high = resampledRms(10000);
        assertTrue(low > 6500 && low < 7500, "speech-band amplitude must be retained"); assertTrue(high < low * .02, "out-of-band content must not alias into telephone audio");
    }
    private double resampledRms(int frequency) {
        byte[] pcm = new byte[48000]; for (int i = 0; i < 24000; i++) { short sample = (short)(Math.sin(2 * Math.PI * frequency * i / 24000) * 10000); pcm[2*i] = (byte)sample; pcm[2*i+1] = (byte)(sample >> 8); }
        var converter = new NativeMedia.PcmResampler(24000, 8000); var bytes = new java.io.ByteArrayOutputStream(); bytes.writeBytes(converter.convert(pcm)); bytes.writeBytes(converter.finish());
        byte[] output = bytes.toByteArray(); double energy = 0; int count = 0;
        for (int i = 200; i < output.length - 200; i += 2) { short value = (short)((output[i] & 255) | (output[i+1] << 8)); energy += value * (double)value; count++; } return Math.sqrt(energy / count);
    }
    @Test void rtpAndG711MatchPythonVectorsAcrossFullSampleDomain() throws Exception {
        var vectors = Support.object(getClass().getResourceAsStream("/rtp-python-vectors.json").readAllBytes()); var base64 = Base64.getDecoder();
        byte[] samples = base64.decode(vectors.get("samples").toString()); assertArrayEquals(base64.decode(vectors.get("pcma").toString()), Rtp.encode(samples, true)); assertArrayEquals(base64.decode(vectors.get("pcmu").toString()), Rtp.encode(samples, false));
        byte[] codes = new byte[256]; for (int i = 0; i < 256; i++) codes[i] = (byte)i;
        assertArrayEquals(base64.decode(vectors.get("alaw_decoded").toString()), Rtp.decode(codes, true)); assertArrayEquals(base64.decode(vectors.get("ulaw_decoded").toString()), Rtp.decode(codes, false));
        var packet = new Rtp.Packet(8, 65537, 0xffffffffL, 1234, new byte[]{1,2,3}, true); var restored = Rtp.Packet.decode(packet.encode()); assertEquals(1, restored.sequence()); assertEquals(0xffffffffL, restored.timestamp()); assertArrayEquals(packet.payload(), restored.payload()); assertTrue(restored.marker());
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception { long deadline = System.nanoTime() + 5_000_000_000L; while (!condition.getAsBoolean()) { if (System.nanoTime() >= deadline) fail("timed out waiting for media evidence"); Thread.sleep(10); } }
}
