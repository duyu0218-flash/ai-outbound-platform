package com.duozhi.outbound.voiceai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.media.service.NativeMedia;
import com.duozhi.outbound.voiceai.service.*;
import com.sun.net.httpserver.HttpServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import org.springframework.web.socket.*;

class VoiceAiBackendsTest {
    static byte[] pcm() {
        return new byte[960];
    }

    @Test
    void profilesAreExactlySixAndNeverExposeCredentials() {
        var profiles =
                new VoiceAiProfiles(
                        new Settings(Map.of("DASHSCOPE_API_KEY", "never-return-this-secret")));
        assertThat(VoiceAiProfiles.PROFILES).hasSize(6);
        assertThat(profiles.catalog().toString()).doesNotContain("never-return-this-secret");
        assertThat(profiles.missing("aliyun-cascade")).isEmpty();
        assertThat(profiles.missing("aliyun-realtime")).contains("VOICE_AI_ALI_REALTIME_URL");
        assertThatThrownBy(() -> profiles.snapshot("invented-provider"))
                .isInstanceOf(Support.Failure.class);
        assertThatThrownBy(() -> profiles.endpoint("ws://example.com/", false))
                .isInstanceOf(Support.Failure.class);
        assertThatThrownBy(() -> profiles.endpoint("wss://user:pass@example.com/", false))
                .isInstanceOf(Support.Failure.class);
    }

    @Test
    void tenantVersionCredentialsReachTheActualTextRequest() throws Exception {
        var called = new CompletableFuture<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    called.complete(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] data =
                            "data: {\"choices\":[{\"delta\":{\"content\":\"租户回复\"}}]}\n\ndata: [DONE]\n\n"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.start();
        try {
            var resolved = new Settings(Map.of("DASHSCOPE_API_KEY", "tenant-frozen-secret"));
            var root =
                    new Settings(Map.of("DASHSCOPE_API_KEY", "wrong-shared-secret")) {
                        @Override
                        public Settings voiceAi(long tenant, long revision) {
                            assertThat(tenant).isEqualTo(7);
                            assertThat(revision).isEqualTo(3);
                            return resolved;
                        }
                    };
            var spec = new LinkedHashMap<>(new VoiceAiProfiles(root).snapshot("aliyun-cascade"));
            spec.put(
                    "llm_url",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
            spec.put("credential_scope", Map.of("tenant", 7L, "revision", 3L));
            assertThat(
                            new VoiceAiBackends(root)
                                    .generate(
                                            spec, List.of(Map.of("role", "user", "content", "测试"))))
                    .isEqualTo("租户回复");
            assertThat(called.get(2, TimeUnit.SECONDS)).isEqualTo("Bearer tenant-frozen-secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pcmWavValidationAndRateConversionPreserveDuration() {
        byte[] input = new byte[16000];
        var wav = VoiceAiAudio.wav(input, 8000);
        assertThat(VoiceAiAudio.wav(Base64.getEncoder().encodeToString(wav)).rate())
                .isEqualTo(8000);
        assertThat(VoiceAiAudio.resample(input, 8000, 16000)).hasSize(32000);
        assertThatThrownBy(() -> VoiceAiAudio.wav(Base64.getEncoder().encodeToString(new byte[44])))
                .hasMessageContaining("PCM WAV");
    }

    @Test
    void binaryFramingRejectsTruncationAndPreservesSessions() throws Exception {
        byte[] bytes = VolcVoiceProtocol.event(352, "session-id", pcm(), true);
        var frame = VolcVoiceProtocol.parse(bytes);
        assertThat(frame.session()).isEqualTo("session-id");
        assertThat(frame.payload()).isEqualTo(pcm());
        assertThatThrownBy(() -> VolcVoiceProtocol.parse(Arrays.copyOf(bytes, bytes.length - 1)))
                .isInstanceOf(IOException.class);
        byte[] broken = bytes.clone();
        ByteBuffer.wrap(broken).putInt(8, Integer.MAX_VALUE);
        assertThatThrownBy(() -> VolcVoiceProtocol.parse(broken)).isInstanceOf(IOException.class);
    }

    @Test
    void serviceCredentialsAreIndependentAndSnapshotsDoNotChangeScheme() {
        var env = new HashMap<String, String>();
        env.put("VOICE_AI_VOLC_API_KEY", "shared-secret");
        env.put("VOICE_AI_VOLC_APP_ID", "shared-app");
        env.put("VOICE_AI_VOLC_ACCESS_KEY", "shared-token");
        env.put("VOICE_AI_VOLC_ASR_API_KEY", "asr-secret");
        env.put("VOICE_AI_VOLC_TTS_APP_ID", "tts-app");
        env.put("VOICE_AI_VOLC_LLM_API_KEY", "llm-secret");
        env.put("VOICE_AI_VOLC_LLM_MODEL", "test-model");
        var profiles = new VoiceAiProfiles(new Settings(env));
        assertThat(profiles.missing("volcengine-cascade"))
                .containsExactly("VOICE_AI_VOLC_TTS_ACCESS_KEY");
        assertThat(profiles.missing("volcengine-realtime")).isEmpty();
        var frozen = profiles.snapshot("volcengine-cascade");
        assertThat(profiles.volcAuth(frozen, "asr").references())
                .containsExactly("VOICE_AI_VOLC_ASR_API_KEY");
        assertThat(profiles.volcAuth(frozen, "tts").references())
                .containsExactly("VOICE_AI_VOLC_TTS_APP_ID", "VOICE_AI_VOLC_TTS_ACCESS_KEY");
        env.put("VOICE_AI_VOLC_TTS_API_KEY", "new-tts-secret");
        profiles = new VoiceAiProfiles(new Settings(env));
        profiles.requireReady("volcengine-cascade");
        assertThatThrownBy(() -> new VoiceAiProfiles(new Settings(env)).requireReady(frozen))
                .hasMessageContaining("VOICE_AI_VOLC_TTS_ACCESS_KEY");
        assertThat(profiles.catalog().toString())
                .doesNotContain(
                        "shared-secret",
                        "shared-token",
                        "asr-secret",
                        "new-tts-secret",
                        "llm-secret");
        assertThat(VoiceAiProfiles.DEFAULTS.get("VOICE_AI_TENCENT_LLM_URL"))
                .isEqualTo("https://tokenhub.tencentmaas.com/v1/chat/completions");
        var malformed = new LinkedHashMap<>(frozen);
        malformed.put(
                "volc_auth",
                Map.of("asr", Map.of("mode", "api_key", "api_key", "DASHSCOPE_API_KEY")));
        assertThatThrownBy(() -> new VoiceAiProfiles(new Settings(env)).requireReady(malformed))
                .hasMessageContaining("invalid voice AI credential snapshot");
    }

    @Test
    void separateApiKeyFilesAndOldSnapshotsWork(
            @org.junit.jupiter.api.io.TempDir
            java.nio.file.Path dir) throws Exception {
        var env = new HashMap<String, String>();
        for (String service : List.of("ASR", "TTS", "REALTIME")) {
            var file = dir.resolve(service);
            java.nio.file.Files.writeString(file, service + "-private-value");
            env.put("VOICE_AI_VOLC_" + service + "_API_KEY_FILE", file.toString());
        }
        env.put("VOICE_AI_VOLC_LLM_API_KEY", "llm-private-value");
        env.put("VOICE_AI_VOLC_LLM_MODEL", "test-model");
        var profiles = new VoiceAiProfiles(new Settings(env));
        profiles.requireReady("volcengine-cascade");
        profiles.requireReady("volcengine-realtime");
        assertThat(profiles.catalog().toString()).doesNotContain("private-value", dir.toString());
        java.nio.file.Files.delete(dir.resolve("ASR"));
        assertThat(profiles.missing("volcengine-cascade"))
                .containsExactly("VOICE_AI_VOLC_ASR_API_KEY");
        assertThat(profiles.missing("volcengine-realtime")).isEmpty();
        var old = new LinkedHashMap<>(profiles.snapshot("volcengine-realtime"));
        old.remove("volc_auth");
        assertThatThrownBy(() -> profiles.requireReady(old))
                .hasMessageContaining("VOICE_AI_VOLC_APP_ID");
        env.put("VOICE_AI_VOLC_APP_ID", "old-app");
        env.put("VOICE_AI_VOLC_ACCESS_KEY", "old-token");
        new VoiceAiProfiles(new Settings(env)).requireReady(old);
    }

    @ParameterizedTest
    @CsvSource({
        "aliyun-cascade,reviewed,shared-legacy",
        "volcengine-cascade,reviewed,shared-legacy",
        "tencent-cascade,reviewed,shared-legacy",
        "volcengine-realtime,reviewed,shared-legacy",
        "aliyun-realtime,reviewed,shared-legacy",
        "baidu-realtime,reviewed,shared-legacy",
        "volcengine-realtime,streaming,shared-legacy",
        "aliyun-realtime,streaming,shared-legacy",
        "baidu-realtime,streaming,shared-legacy",
        "volcengine-cascade,reviewed,service-api",
        "volcengine-cascade,reviewed,service-legacy",
        "volcengine-cascade,reviewed,mixed",
        "volcengine-cascade,reviewed,shared-api",
        "volcengine-realtime,reviewed,service-api",
        "volcengine-realtime,streaming,service-api",
        "volcengine-realtime,reviewed,service-legacy",
        "volcengine-realtime,reviewed,shared-api"
    })
    void sixCombinationsSpeakThroughTheirActualWireProtocols(
            String id, String playbackMode, String credentialPlan) throws Exception {
        String vendor = VoiceAiProfiles.profile(id).vendor();
        boolean realtime = id.endsWith("realtime");
        try (var recognition = new Fixture(vendor, realtime ? "realtime" : "asr");
                var synthesis = new Fixture(vendor, "tts");
                var outputAsr = new Fixture("aliyun", "asr")) {
            var llm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var calls = new AtomicInteger();
            llm.createContext(
                    "/chat/completions",
                    exchange -> {
                        calls.incrementAndGet();
                        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                                .isEqualTo("Bearer test-key");
                        var body = Support.object(exchange.getRequestBody().readAllBytes());
                        assertThat(body.get("stream")).isEqualTo(true);
                        assertThat(body.toString()).contains("统一测试话术");
                        byte[] response =
                                ("data: {\"choices\":[{\"delta\":{\"content\":\"好的，请问您方便了解吗？\"}}]}\n\ndata: [DONE]\n\n")
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
                        exchange.close();
                    });
            llm.createContext(
                    "/",
                    exchange -> {
                        exchange.getRequestBody().readAllBytes();
                        byte[] response =
                                Support.json(
                                        Map.of(
                                                "Code",
                                                200,
                                                "RequestId",
                                                "wire-safety-approved",
                                                "Data",
                                                Map.of(
                                                        "RiskLevel",
                                                        "none",
                                                        "Result",
                                                        List.of(Map.of("Label", "nonLabel")))));
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
                        exchange.close();
                    });
            llm.start();
            try {
                var env = new HashMap<String, String>();
                for (String key : VoiceAiProfiles.DEFAULTS.keySet())
                    if (!key.endsWith("_FILE")
                            && (key.endsWith("KEY") || key.endsWith("ID"))
                            && !key.startsWith("VOICE_AI_VOLC_")) env.put(key, "test-key");
                env.put("VOICE_AI_VOLC_LLM_MODEL", "test-model");
                env.put("VOICE_AI_VOLC_LLM_API_KEY", "test-key");
                if (vendor.equals("volcengine")) {
                    configureVolcCredentials(env, credentialPlan, recognition);
                    if (!realtime) configureVolcCredentials(env, credentialPlan, synthesis);
                }
                for (String prefix : List.of("ALI", "VOLC", "TENCENT", "BAIDU")) {
                    env.put("VOICE_AI_" + prefix + "_ASR_URL", recognition.url());
                    env.put("VOICE_AI_" + prefix + "_REALTIME_URL", recognition.url());
                    env.put("VOICE_AI_" + prefix + "_TTS_URL", synthesis.url());
                    env.put(
                            "VOICE_AI_" + prefix + "_LLM_URL",
                            "http://127.0.0.1:" + llm.getAddress().getPort() + "/chat/completions");
                }
                outputAsr.transcript = "好的，请问您方便了解吗？";
                if (realtime) env.put("VOICE_AI_ALI_ASR_URL", outputAsr.url());
                env.put(
                        "VOICE_AI_SAFETY_ENDPOINT",
                        "http://127.0.0.1:" + llm.getAddress().getPort() + "/");
                env.put("VOICE_AI_SAFETY_ACCESS_KEY_ID", "synthetic-id");
                env.put("VOICE_AI_SAFETY_ACCESS_KEY_SECRET", "synthetic-secret");
                var settings = new Settings(env);
                var profiles = new VoiceAiProfiles(settings);
                var spec = profiles.snapshot(id);
                var backend = new VoiceAiBackends(settings);
                var recognized = new CompletableFuture<String>();
                var output = new ByteArrayOutputStream();
                try (var connection =
                        backend.connect(
                                spec,
                                "统一测试话术",
                                new VoiceAiBackends.Listener() {
                                    public void transcript(String text, String event) {
                                        recognized.complete(text);
                                    }

                                    public void speechStarted() {}
                                })) {
                    connection.audio(new byte[6400]);
                    connection.finishInput();
                    assertThat(recognized.get(3, TimeUnit.SECONDS)).isEqualTo("我想了解服务");
                    String reply;
                    if (realtime)
                        reply =
                                connection.reply(
                                        "",
                                        true,
                                        (bytes, rate) -> {
                                            assertThat(rate).isEqualTo(24000);
                                            output.writeBytes(bytes);
                                        });
                    else {
                        reply = backend.generate(spec, "统一测试话术", recognized.join(), List.of());
                        backend.synthesize(
                                spec,
                                reply,
                                (bytes, rate) -> {
                                    assertThat(rate).isIn(16000, 24000);
                                    output.writeBytes(bytes);
                                });
                    }
                    assertThat(reply).isEqualTo("好的，请问您方便了解吗？");
                    assertThat(output.size()).isEqualTo(960);
                }
                assertThat(calls.get()).isEqualTo(realtime ? 0 : 1);
                env.put("VOICE_AI_PROFILES_ENABLED", "true");
                var heard = new CompletableFuture<String>();
                var played = new CompletableFuture<Void>();
                var outgoing = new AtomicInteger();
                var firstFrame = new AtomicLong();
                recognition.completedAt.set(0);
                var pbx = mock(WebSocketSession.class);
                when(pbx.getId()).thenReturn("pbx-" + id);
                when(pbx.isOpen()).thenReturn(true);
                doAnswer(
                                invocation -> {
                                    if (invocation.getArgument(0)
                                            instanceof BinaryMessage message) {
                                        firstFrame.compareAndSet(0, System.nanoTime());
                                        outgoing.addAndGet(message.getPayloadLength());
                                    }
                                    return null;
                                })
                        .when(pbx)
                        .sendMessage(any());
                try (var media =
                        new NativeMedia(
                                new Settings(env),
                                (url, event) -> {
                                    if (event.get("error_code") != null)
                                        heard.completeExceptionally(
                                                new AssertionError(
                                                        "native error " + event.get("error_code")));
                                    if ("customer".equals(event.get("speaker_role")))
                                        heard.complete(event.get("transcript").toString());
                                    if ("listening".equals(event.get("state"))
                                            && outgoing.get() > 0) played.complete(null);
                                },
                                (session, permit, kind, acquire, confirmed) -> {},
                                2)) {
                    var session =
                            media.create(
                                    "native-" + id,
                                    "http://speech",
                                    "http://media",
                                    Map.of(
                                            "attempt",
                                            1,
                                            "voice_ai_profile",
                                            spec,
                                            "voice_ai_script",
                                            "统一测试话术",
                                            "voice_ai_guard",
                                            Map.of(
                                                    "realtime_playback_mode",
                                                    playbackMode,
                                                    "max_reply_chars",
                                                    240,
                                                    "forbidden_phrases",
                                                    "保证收益")));
                    media.connectAsync(pbx, session.token);
                    session.started.get(3, TimeUnit.SECONDS);
                    byte[] voice = new byte[16000];
                    for (int i = 0; i < voice.length; i += 2) {
                        voice[i] = (byte) 0x88;
                        voice[i + 1] = 0x13;
                    }
                    media.enqueueAudio(pbx, ByteBuffer.wrap(voice));
                    media.enqueueAudio(pbx, ByteBuffer.wrap(new byte[16000]));
                    media.enqueueAudio(pbx, ByteBuffer.wrap(new byte[16000]));
                    assertThat(heard.get(4, TimeUnit.SECONDS)).isEqualTo("我想了解服务");
                    media.respond(
                            session.call,
                            "好的，请问您方便了解吗？",
                            session.latest,
                            "reply-operation",
                            realtime);
                    played.get(5, TimeUnit.SECONDS);
                    assertThat(outgoing.get()).isPositive();
                    // All legacy mode values now use the same mandatory sentence gate.
                    if (realtime) assertThat(outputAsr.connections.get()).isPositive();
                }
                assertThat(recognition.problem.get()).isNull();
                assertThat(synthesis.problem.get()).isNull();
            } finally {
                llm.stop(0);
            }
        }
    }

    static void configureVolcCredentials(Map<String, String> env, String plan, Fixture fixture) {
        String service = fixture.kind.toUpperCase(Locale.ROOT),
                prefix = "VOICE_AI_VOLC_" + service + "_";
        // Distinct decoys prove that a service-specific credential is never replaced by a shared
        // one.
        env.put("VOICE_AI_VOLC_APP_ID", "shared-app");
        env.put("VOICE_AI_VOLC_ACCESS_KEY", "shared-token");
        var headers = new LinkedHashMap<String, String>();
        boolean api = plan.endsWith("api") || (plan.equals("mixed") && service.equals("ASR"));
        boolean specific = plan.startsWith("service") || plan.equals("mixed");
        if (specific) env.put("VOICE_AI_VOLC_API_KEY", "unused-shared-key");
        if (api) {
            String key = specific ? service + "-api-key" : "shared-api-key";
            env.put(specific ? prefix + "API_KEY" : "VOICE_AI_VOLC_API_KEY", key);
            headers.put("X-Api-Key", key);
            // Presence of a complete old pair must not mix old headers into new API Key requests.
            if (specific) {
                env.put(prefix + "APP_ID", service + "-unused-app");
                env.put(prefix + "ACCESS_KEY", service + "-unused-token");
            }
        } else {
            String app = specific ? service + "-app" : "shared-app",
                    token = specific ? service + "-token" : "shared-token";
            if (specific) {
                env.put(prefix + "APP_ID", app);
                env.put(prefix + "ACCESS_KEY", token);
            }
            headers.put(service.equals("ASR") ? "X-Api-App-Key" : "X-Api-App-Id", app);
            headers.put("X-Api-Access-Key", token);
            if (service.equals("REALTIME")) headers.put("X-Api-App-Key", "PlgvMymc7f3tQnJ6");
        }
        headers.put(
                "X-Api-Resource-Id",
                switch (service) {
                    case "ASR" -> "volc.seedasr.sauc.duration";
                    case "TTS" -> "seed-tts-2.0";
                    default -> "volc.speech.dialog";
                });
        fixture.expectedHeaders = headers;
    }

    static final class Fixture implements AutoCloseable {
        final EventLoopGroup boss = new NioEventLoopGroup(1), workers = new NioEventLoopGroup(1);
        final Channel server;
        final AtomicReference<Throwable> problem = new AtomicReference<>();
        final AtomicLong completedAt = new AtomicLong();
        Map<String, String> expectedHeaders = Map.of();
        final String vendor, kind;
        volatile Map<String, Object> suitePayload = Map.of();
        final List<String> suiteCommands = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean suiteBlockResponse;
        String transcript = "我想了解服务";
        int responseDelayMs;
        boolean suppressTranscript;
        final CountDownLatch responseRequested = new CountDownLatch(1);
        final CountDownLatch cancellations = new CountDownLatch(2);
        final AtomicInteger connections = new AtomicInteger();

        static final class State {
            boolean recognized;
            String task = "";
        }

        Fixture(String vendor, String kind) throws Exception {
            this.vendor = vendor;
            this.kind = kind;
            server =
                    new ServerBootstrap()
                            .group(boss, workers)
                            .channel(NioServerSocketChannel.class)
                            .childHandler(
                                    new ChannelInitializer<SocketChannel>() {
                                        protected void initChannel(SocketChannel channel) {
                                            install(channel);
                                        }
                                    })
                            .bind("127.0.0.1", 0)
                            .sync()
                            .channel();
        }

        void install(SocketChannel channel) {
            channel.pipeline()
                    .addLast(new HttpServerCodec(), new HttpObjectAggregator(2 * 1024 * 1024));
            channel.pipeline()
                    .addLast(
                            new SimpleChannelInboundHandler<FullHttpRequest>() {
                                protected void channelRead0(
                                        ChannelHandlerContext ctx, FullHttpRequest request) {
                                    try {
                                        connections.incrementAndGet();
                                        if (vendor.equals("tencent"))
                                            verifySignature(request.uri());
                                        if (vendor.equals("volcengine")) {
                                            for (String header :
                                                    List.of(
                                                            "X-Api-Key",
                                                            "X-Api-App-Key",
                                                            "X-Api-App-Id",
                                                            "X-Api-Access-Key",
                                                            "X-Api-Resource-Id"))
                                                assertThat(request.headers().get(header))
                                                        .as(kind + " " + header)
                                                        .isEqualTo(expectedHeaders.get(header));
                                            assertThat(request.headers().get("X-Api-Connect-Id"))
                                                    .isNotBlank();
                                        }
                                    } catch (Throwable failed) {
                                        problem.set(failed);
                                    }
                                    ctx.fireChannelRead(request.retain());
                                }
                            });
            channel.pipeline()
                    .addLast(
                            new WebSocketServerProtocolHandler(
                                    WebSocketServerProtocolConfig.newBuilder()
                                            .websocketPath("/")
                                            .checkStartsWith(true)
                                            .maxFramePayloadLength(2 * 1024 * 1024)
                                            .build()));
            channel.pipeline().addLast(new WebSocketFrameAggregator(2 * 1024 * 1024));
            channel.pipeline()
                    .addLast(
                            new SimpleChannelInboundHandler<WebSocketFrame>() {
                                final State state = new State();

                                public void userEventTriggered(
                                        ChannelHandlerContext ctx, Object event) throws Exception {
                                    if (event
                                                    instanceof
                                                    WebSocketServerProtocolHandler.HandshakeComplete
                                            && vendor.equals("tencent"))
                                        text(ctx, Map.of("code", 0, "ready", 1));
                                    super.userEventTriggered(ctx, event);
                                }

                                protected void channelRead0(
                                        ChannelHandlerContext ctx, WebSocketFrame incoming) {
                                    try {
                                        byte[] bytes = new byte[incoming.content().readableBytes()];
                                        incoming.content().getBytes(0, bytes);
                                        if (vendor.equals("volcengine"))
                                            volc(ctx, VolcVoiceProtocol.parse(bytes), state);
                                        else if (incoming instanceof BinaryWebSocketFrame)
                                            recognition(ctx, state);
                                        else message(ctx, Support.object(bytes), state);
                                    } catch (Throwable error) {
                                        problem.set(error);
                                        ctx.close();
                                    }
                                }

                                public void exceptionCaught(
                                        ChannelHandlerContext ctx, Throwable cause) {
                                    problem.set(cause);
                                    ctx.close();
                                }
                            });
        }

        void verifySignature(String address) throws Exception {
            var uri = URI.create(address);
            var params = new TreeMap<String, String>();
            for (String field : uri.getRawQuery().split("&")) {
                String[] pair = field.split("=", 2);
                params.put(
                        URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            String signature = params.remove(kind.equals("tts") ? "Signature" : "signature");
            var query = new StringJoiner("&");
            params.forEach((key, value) -> query.add(key + "=" + value));
            String source =
                    (kind.equals("tts") ? "GET" : "") + "127.0.0.1" + uri.getPath() + "?" + query;
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec("test-key".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            assertThat(signature)
                    .isEqualTo(
                            Base64.getEncoder()
                                    .encodeToString(
                                            mac.doFinal(source.getBytes(StandardCharsets.UTF_8))));
        }

        void volc(ChannelHandlerContext ctx, VolcVoiceProtocol.Frame frame, State state)
                throws Exception {
            if (kind.equals("asr")) {
                if (!state.recognized) {
                    state.recognized = true;
                    ctx.writeAndFlush(
                            new BinaryWebSocketFrame(
                                    Unpooled.wrappedBuffer(
                                            VolcVoiceProtocol.asr(
                                                    Support.json(
                                                            Map.of(
                                                                    "result",
                                                                    Map.of(
                                                                            "utterances",
                                                                            List.of(
                                                                                    Map.of(
                                                                                            "text",
                                                                                            transcript,
                                                                                            "definite",
                                                                                            true,
                                                                                            "start_time",
                                                                                            0))))),
                                                    true,
                                                    1))));
                }
                return;
            }
            if (kind.equals("tts") && (frame.event() == 100 || frame.event() == 200))
                assertThat(Support.object(frame.payload()).get("event")).isEqualTo(frame.event());
            if (frame.event() == 1) binary(ctx, 50, "connection", Support.json(Map.of()));
            else if (frame.event() == 100) {
                state.task = frame.session();
                binary(ctx, 150, state.task, Support.json(Map.of()));
            } else if (frame.event() == 200 && kind.equals("tts"))
                binary(ctx, 352, state.task, pcm());
            else if (frame.event() == 102 && kind.equals("tts"))
                binary(ctx, 152, state.task, Support.json(Map.of()));
            else if (frame.event() == 200 && kind.equals("realtime") && !state.recognized) {
                state.recognized = true;
                binary(
                        ctx,
                        451,
                        state.task,
                        Support.json(
                                Map.of(
                                        "results",
                                        List.of(Map.of("text", transcript, "is_interim", false)))));
                binary(ctx, 459, state.task, Support.json(Map.of()));
                binary(ctx, 550, state.task, Support.json(Map.of("content", "好的，请问您方便了解吗？")));
                binary(ctx, 352, state.task, pcm());
                complete(ctx, () -> binary(ctx, 359, state.task, Support.json(Map.of())));
            }
        }

        void recognition(ChannelHandlerContext ctx, State state) {
            if (!kind.equals("asr") || state.recognized) return;
            state.recognized = true;
            if (vendor.equals("aliyun"))
                text(
                        ctx,
                        Map.of(
                                "header",
                                Map.of("event", "result-generated"),
                                "payload",
                                Map.of(
                                        "output",
                                        Map.of(
                                                "sentence",
                                                Map.of(
                                                        "text",
                                                        transcript,
                                                        "sentence_end",
                                                        true,
                                                        "begin_time",
                                                        0,
                                                        "end_time",
                                                        20)))));
            else
                text(
                        ctx,
                        Map.of(
                                "code",
                                0,
                                "result",
                                Map.of("slice_type", 2, "voice_text_str", transcript, "index", 0)));
        }

        void suiteEvent(
                ChannelHandlerContext ctx, State state, String name, Map<String, Object> extra) {
            var output = new LinkedHashMap<String, Object>(extra);
            output.put("event", name);
            output.put("dialog_id", "fixture-dialog");
            text(
                    ctx,
                    Map.of(
                            "header",
                            Map.of("event", "result-generated", "task_id", state.task),
                            "payload",
                            Map.of("output", output)));
        }

        void suite(ChannelHandlerContext ctx, Map<String, Object> message, State state) {
            var payload = Support.map(message.get("payload"));
            var input = Support.map(payload.get("input"));
            String directive = Support.str(input, "directive", "");
            suiteCommands.add(directive);
            if (directive.equals("Start")) {
                suitePayload = payload;
                state.task = Support.str(Support.map(message.get("header")), "task_id", "");
                assertThat(payload.get("model")).isEqualTo("multimodal-dialog");
                var parameters = Support.map(payload.get("parameters"));
                assertThat(
                                Support.str(
                                                Support.map(parameters.get("client_info")),
                                                "user_id",
                                                "")
                                        .length())
                        .isBetween(1, 36);
                suiteEvent(ctx, state, "Started", Map.of());
                suiteEvent(ctx, state, "DialogStateChanged", Map.of("state", "Listening"));
            } else if (directive.equals("StopSpeech") || directive.equals("RequestToRespond")) {
                if (directive.equals("StopSpeech"))
                    suiteEvent(
                            ctx,
                            state,
                            "SpeechContent",
                            Map.of("text", "我想了解服务", "finished", true));
                suiteEvent(ctx, state, "DialogStateChanged", Map.of("state", "Thinking"));
                if (suiteBlockResponse) return;
                suiteEvent(ctx, state, "RespondingStarted", Map.of());
                suiteEvent(
                        ctx,
                        state,
                        "RespondingContent",
                        Map.of(
                                "text",
                                "好的，请问您方便了解吗？",
                                "spoken",
                                "好的，请问您方便了解吗？",
                                "finished",
                                true,
                                "round_id",
                                "round-fixture",
                                "llm_request_id",
                                "llm-fixture"));
                ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(pcm())));
                suiteEvent(ctx, state, "RespondingEnded", Map.of());
            } else if (directive.equals("RequestToSpeak")
                    || directive.equals("CancelSpeech")
                    || directive.equals("LocalRespondingEnded"))
                suiteEvent(ctx, state, "DialogStateChanged", Map.of("state", "Listening"));
            else if (directive.equals("HeartBeat")) suiteEvent(ctx, state, "HeartBeat", Map.of());
        }

        void message(ChannelHandlerContext ctx, Map<String, Object> event, State state) {
            if (kind.equals("suite")) {
                suite(ctx, event, state);
                return;
            }
            String type = Support.str(event, "type", "");
            var header = Support.map(event.get("header"));
            String action = Support.str(header, "action", Support.str(event, "action", ""));
            switch (action) {
                case "run-task" -> {
                    state.task = Support.str(header, "task_id", "");
                    text(
                            ctx,
                            Map.of(
                                    "header",
                                    Map.of("event", "task-started", "task_id", state.task)));
                }
                case "continue-task", "ACTION_SYNTHESIS" ->
                        ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(pcm())));
                case "finish-task" ->
                        text(
                                ctx,
                                Map.of(
                                        "header",
                                        Map.of("event", "task-finished", "task_id", state.task)));
                case "ACTION_COMPLETE" -> text(ctx, Map.of("code", 0, "final", 1));
                default -> {
                    if (type.equals("response.cancel")) cancellations.countDown();
                    else if (type.equals("session.update"))
                        text(ctx, Map.of("type", "session.updated"));
                    else if (type.equals("input_audio_buffer.commit") && !suppressTranscript)
                        text(
                                ctx,
                                Map.of(
                                        "type",
                                        "conversation.item.input_audio_transcription.completed",
                                        "transcript",
                                        transcript,
                                        "item_id",
                                        "test-item"));
                    else if (type.equals("response.create")) {
                        responseRequested.countDown();
                        ctx.executor()
                                .schedule(
                                        () -> sendResponse(ctx),
                                        responseDelayMs,
                                        TimeUnit.MILLISECONDS);
                    }
                }
            }
        }

        void sendResponse(ChannelHandlerContext ctx) {
            text(
                    ctx,
                    Map.of(
                            "type",
                            "response.created",
                            "response",
                            Map.of("id", "fixture-response")));
            text(ctx, Map.of("type", "response.audio_transcript.delta", "delta", "好的，请问您方便了解吗？"));
            text(
                    ctx,
                    Map.of(
                            "type",
                            "response.audio.delta",
                            "delta",
                            Base64.getEncoder().encodeToString(pcm())));
            complete(
                    ctx,
                    () ->
                            text(
                                    ctx,
                                    Map.of(
                                            "type",
                                            "response.done",
                                            "response",
                                            Map.of(
                                                    "id",
                                                    "fixture-response",
                                                    "status",
                                                    "completed"))));
        }

        void complete(ChannelHandlerContext ctx, Runnable send) {
            ctx.executor()
                    .schedule(
                            () -> {
                                completedAt.set(System.nanoTime());
                                send.run();
                            },
                            400,
                            TimeUnit.MILLISECONDS);
        }

        String url() {
            return "ws://127.0.0.1:" + ((InetSocketAddress) server.localAddress()).getPort() + "/";
        }

        static void text(ChannelHandlerContext ctx, Object value) {
            ctx.writeAndFlush(
                    new TextWebSocketFrame(
                            new String(Support.json(value), StandardCharsets.UTF_8)));
        }

        static void binary(ChannelHandlerContext ctx, int event, String id, byte[] data) {
            byte[] session = id.getBytes(StandardCharsets.UTF_8);
            var out = ByteBuffer.allocate(16 + session.length + data.length);
            out.put((byte) 0x11)
                    .put((byte) (event == 352 ? 0xb4 : 0x94))
                    .put((byte) (event == 352 ? 0 : 0x10))
                    .put((byte) 0)
                    .putInt(event)
                    .putInt(session.length)
                    .put(session)
                    .putInt(data.length)
                    .put(data);
            ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(out.array())));
        }

        public void close() throws Exception {
            server.close().sync();
            boss.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
