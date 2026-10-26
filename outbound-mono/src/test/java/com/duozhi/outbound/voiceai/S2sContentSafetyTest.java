package com.duozhi.outbound.voiceai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.media.service.NativeMedia;
import com.duozhi.outbound.voiceai.service.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.*;

class S2sContentSafetyTest {
    static byte[] voice(int ms) {
        return VoiceStreamingTest.audio(ms, 24000, 1400);
    }

    static Map<String, Object> guard() {
        return Map.of("max_reply_chars", 240, "require_fact_templates", true);
    }

    static Map<String, Object> passed() {
        return Map.of("risk_level", "none", "request_id", "test-approved");
    }

    static final class Recognition implements SentenceSafetyGate.RecognizerFactory {
        final CompletableFuture<VoiceAiBackends.Listener> listener = new CompletableFuture<>();
        final Semaphore frames = new Semaphore(0);
        final AtomicBoolean closed = new AtomicBoolean();

        public VoiceAiBackends.Connection open(
                VoiceAiBackends.Listener listener, ResponseScope scope) {
            this.listener.complete(listener);
            return new VoiceAiBackends.Connection() {
                public void audio(byte[] bytes) {
                    frames.release();
                }

                public void finishInput() {}

                public void awaitFinished() {}

                public void check() {}

                public void close() {
                    closed.set(true);
                }
            };
        }

        void emit(String text, long start, long end) throws Exception {
            assertThat(frames.tryAcquire(2, TimeUnit.SECONDS)).isTrue();
            listener.get(2, TimeUnit.SECONDS)
                    .recognition(
                            Map.of(
                                    "text",
                                    text,
                                    "is_final",
                                    true,
                                    "start_ms",
                                    start,
                                    "end_ms",
                                    end));
        }
    }

    @Test
    void firstActualAudioWaitsForApprovalButDoesNotWaitForResponseDone() throws Exception {
        var recognition = new Recognition();
        var reviewing = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var heard = new CompletableFuture<byte[]>();
        var checks = new CopyOnWriteArrayList<String>();
        var trace = new CopyOnWriteArrayList<Map<String, Object>>();
        try (var parent = new ResponseScope();
                var gate =
                        new SentenceSafetyGate(
                                parent,
                                Map.of(),
                                guard(),
                                recognition,
                                (text, scope) -> {
                                    checks.add(text);
                                    reviewing.complete(null);
                                    release.get(2, TimeUnit.SECONDS);
                                    return passed();
                                },
                                (audio, rate) -> heard.complete(audio),
                                trace::add,
                                () -> {})) {
            byte[] actual = voice(100);
            gate.accept(actual, 24000);
            // Partial transcripts cannot release audio even if they contain terminal punctuation.
            recognition
                    .listener
                    .get(2, TimeUnit.SECONDS)
                    .recognition(Map.of("text", "模型旁路猜测。", "is_final", false));
            assertThat(heard).isNotDone();
            recognition.emit("您好。", 0, 100);
            reviewing.get(2, TimeUnit.SECONDS);
            assertThat(heard).isNotDone();
            release.complete(null);
            assertThat(heard.get(2, TimeUnit.SECONDS)).isEqualTo(actual);
            // This assertion precedes finish(): first approved PCM can play while generation
            // continues.
            assertThat(gate.finish()).isEqualTo("您好。");
            assertThat(checks).containsExactly("您好。");
            assertThat(trace.getFirst().toString())
                    .contains("text_sha256", "review_ms")
                    .doesNotContain("您好");
        }
        assertThat(recognition.closed).isTrue();
    }

    @Test
    void cumulativeSemanticReviewBlocksSecondSentenceAndNeverLeaksItsPcm() throws Exception {
        var recognition = new Recognition();
        var heard = new CopyOnWriteArrayList<byte[]>();
        var first = new CompletableFuture<Void>();
        var contexts = new CopyOnWriteArrayList<String>();
        var aborted = new CompletableFuture<Void>();
        try (var parent = new ResponseScope();
                var gate =
                        new SentenceSafetyGate(
                                parent,
                                Map.of(),
                                guard(),
                                recognition,
                                (text, scope) -> {
                                    contexts.add(text);
                                    if (text.contains("危险指导"))
                                        throw new ContentSafety.Failure("S2S_CONTENT_BLOCKED");
                                    return passed();
                                },
                                (audio, rate) -> {
                                    heard.add(audio);
                                    first.complete(null);
                                },
                                event -> {},
                                () -> aborted.complete(null))) {
            byte[] one = voice(100), two = VoiceStreamingTest.audio(100, 24000, 3000);
            gate.accept(one, 24000);
            recognition.emit("先介绍服务。", 0, 100);
            first.get(2, TimeUnit.SECONDS);
            gate.accept(two, 24000);
            recognition.emit("危险指导。", 100, 200);
            aborted.get(2, TimeUnit.SECONDS);
            assertThatThrownBy(gate::finish).hasMessage("S2S_CONTENT_BLOCKED");
            assertThat(heard).hasSize(1);
            assertThat(heard.getFirst()).isEqualTo(one);
            assertThat(contexts).containsExactly("先介绍服务。", "先介绍服务。危险指导。");
        }
    }

    @Test
    void nextSentenceIsReviewedWhilePreviousSentenceIsStillPlaying() throws Exception {
        var recognition = new Recognition();
        var playingFirst = new CompletableFuture<Void>();
        var releasePlayback = new CompletableFuture<Void>();
        var reviewedNext = new CompletableFuture<Void>();
        var heard = new CopyOnWriteArrayList<byte[]>();
        try (var parent = new ResponseScope();
                var gate =
                        new SentenceSafetyGate(
                                parent,
                                Map.of(),
                                guard(),
                                recognition,
                                (text, scope) -> {
                                    if (text.contains("第二句")) reviewedNext.complete(null);
                                    return passed();
                                },
                                (audio, rate) -> {
                                    if (heard.isEmpty()) {
                                        playingFirst.complete(null);
                                        releasePlayback.get(2, TimeUnit.SECONDS);
                                    }
                                    heard.add(audio);
                                },
                                event -> {},
                                () -> {})) {
            gate.accept(voice(100), 24000);
            recognition.emit("第一句。", 0, 100);
            playingFirst.get(2, TimeUnit.SECONDS);
            gate.accept(voice(100), 24000);
            recognition.emit("第二句。", 100, 200);
            reviewedNext.get(2, TimeUnit.SECONDS);
            assertThat(heard).isEmpty();
            releasePlayback.complete(null);
            assertThat(gate.finish()).isEqualTo("第一句。第二句。");
            assertThat(heard).hasSize(2);
        } finally {
            releasePlayback.complete(null);
        }
    }

    @Test
    void incompleteClauseIsHeldUntilCompleteSentenceOrSuccessfulEnd() throws Exception {
        var recognition = new Recognition();
        var heard = new CopyOnWriteArrayList<byte[]>();
        try (var parent = new ResponseScope();
                var gate =
                        new SentenceSafetyGate(
                                parent,
                                Map.of(),
                                guard(),
                                recognition,
                                (text, scope) -> passed(),
                                (audio, rate) -> heard.add(audio),
                                event -> {},
                                () -> {})) {
            gate.accept(voice(100), 24000);
            recognition.emit("服务介绍", 0, 100);
            // No sentence punctuation: the pending clause is not sent to the sink before
            // end-of-response.
            assertThat(heard).isEmpty();
            assertThat(gate.finish()).isEqualTo("服务介绍");
            assertThat(heard).hasSize(1);
        }
    }

    @Test
    void unrecognizedLowEnergyGapIsMutedRatherThanSentWithoutReview() throws Exception {
        var recognition = new Recognition();
        var heard = new CopyOnWriteArrayList<byte[]>();
        var first = new CompletableFuture<Void>();
        try (var parent = new ResponseScope();
                var gate =
                        new SentenceSafetyGate(
                                parent,
                                Map.of(),
                                guard(),
                                recognition,
                                (text, scope) -> passed(),
                                (audio, rate) -> {
                                    heard.add(audio);
                                    first.complete(null);
                                },
                                event -> {},
                                () -> {})) {
            gate.accept(voice(100), 24000);
            recognition.emit("第一句。", 0, 100);
            first.get(2, TimeUnit.SECONDS);
            byte[] gap = VoiceStreamingTest.audio(20, 24000, 50), second = voice(100);
            var bytes = new ByteArrayOutputStream();
            bytes.writeBytes(gap);
            bytes.writeBytes(second);
            gate.accept(bytes.toByteArray(), 24000);
            recognition.emit("第二句。", 120, 220);
            gate.finish();
            assertThat(heard).hasSize(2);
            assertThat(Arrays.copyOfRange(heard.get(1), 0, gap.length)).containsOnly((byte) 0);
            assertThat(Arrays.copyOfRange(heard.get(1), gap.length, heard.get(1).length))
                    .isEqualTo(second);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing-time",
                "future-time",
                "unrecognized-prefix",
                "unrecognized-tail",
                "out-of-order"
            })
    void missingOrUntrustedAudioCoverageCannotAuthorizePlayback(String mode) throws Exception {
        var recognition = new Recognition();
        var heard = new AtomicInteger();
        try (var parent = new ResponseScope();
                var gate =
                        new SentenceSafetyGate(
                                parent,
                                Map.of(),
                                guard(),
                                recognition,
                                (text, scope) -> passed(),
                                (audio, rate) -> heard.incrementAndGet(),
                                event -> {},
                                () -> {})) {
            gate.accept(voice(100), 24000);
            if (mode.equals("missing-time")) {
                assertThatThrownBy(() -> recognition.emit("服务介绍。", -1, -1))
                        .hasMessageContaining("TIMESTAMPS");
            } else {
                recognition.emit(
                        "服务介绍",
                        mode.equals("unrecognized-prefix") ? 20 : 0,
                        mode.equals("future-time")
                                ? 120
                                : mode.equals("unrecognized-tail") ? 80 : 100);
                if (mode.equals("out-of-order")) {
                    gate.accept(voice(100), 24000);
                    recognition.emit("补充说明", 50, 150);
                }
                assertThatThrownBy(gate::finish).isInstanceOf(Exception.class);
            }
            assertThat(heard).hasValue(0);
        }
    }

    @Test
    void interruptDuringModerationClosesRecognizerAndNeverReleasesLateApproval() throws Exception {
        var recognition = new Recognition();
        var reviewing = new CompletableFuture<Void>();
        var late = new CompletableFuture<Void>();
        var heard = new AtomicInteger();
        try (var parent = new ResponseScope();
                var gate =
                        new SentenceSafetyGate(
                                parent,
                                Map.of(),
                                guard(),
                                recognition,
                                (text, scope) -> {
                                    reviewing.complete(null);
                                    late.get();
                                    return passed();
                                },
                                (audio, rate) -> heard.incrementAndGet(),
                                event -> {},
                                () -> {})) {
            gate.accept(voice(100), 24000);
            recognition.emit("您好。", 0, 100);
            reviewing.get(2, TimeUnit.SECONDS);
            parent.close();
            late.complete(null);
            assertThatThrownBy(gate::finish).isInstanceOf(InterruptedIOException.class);
            assertThat(recognition.closed).isTrue();
            assertThat(heard).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualMediaCannotBypassAudioReviewWithBenignModelTextOrLegacyStreamingMode(
            boolean preparedFallback, @TempDir
            Path directory) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var reviewed = new CompletableFuture<String>();
        server.createContext(
                "/",
                exchange -> {
                    reviewed.complete(
                            form(new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8))
                                    .get("ServiceParameters"));
                    byte[] body =
                            Support.json(
                                    Map.of(
                                            "Code",
                                            200,
                                            "RequestId",
                                            "blocked-audio",
                                            "Data",
                                            Map.of(
                                                    "RiskLevel",
                                                    "high",
                                                    "Result",
                                                    List.of(Map.of("Label", "contraband_act")))));
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try (var model = new VoiceAiBackendsTest.Fixture("aliyun", "realtime");
                var independent = new VoiceAiBackendsTest.Fixture("aliyun", "asr")) {
            independent.transcript = "违规测试内容。";
            var env = new HashMap<>(config(server));
            env.put("DASHSCOPE_API_KEY", "synthetic-test-key");
            env.put("VOICE_AI_PROFILES_ENABLED", "true");
            env.put("VOICE_AI_ALI_REALTIME_URL", model.url());
            env.put("VOICE_AI_ALI_ASR_URL", independent.url());
            env.put("VOICE_AI_OPENING_CACHE_DIR", directory.toString());
            var runtime = new Settings(env);
            var spec =
                    new LinkedHashMap<>(new VoiceAiProfiles(runtime).snapshot("aliyun-realtime"));
            spec.put("speech_profile", new VoiceAiProfiles(runtime).snapshot("aliyun-cascade"));
            String fallback = "抱歉，当前无法继续，请稍后联系。";
            byte[] approved = VoiceStreamingTest.audio(100, 24000, 3200);
            if (preparedFallback)
                Files.write(
                        directory.resolve(OpeningAudioCache.key(0, spec, fallback) + ".pcm"),
                        approved);
            var socket = mock(WebSocketSession.class);
            when(socket.getId()).thenReturn("safety-output");
            when(socket.isOpen()).thenReturn(true);
            var played = new AtomicInteger();
            var actual = new ByteArrayOutputStream();
            doAnswer(
                            inv -> {
                                if (inv.getArgument(0) instanceof BinaryMessage binary) {
                                    played.incrementAndGet();
                                    byte[] frame = new byte[binary.getPayload().remaining()];
                                    binary.getPayload().get(frame);
                                    actual.writeBytes(frame);
                                }
                                return null;
                            })
                    .when(socket)
                    .sendMessage(any());
            var closed = new CompletableFuture<Map<String, Object>>();
            try (var media =
                    new NativeMedia(
                            runtime,
                            (url, event) -> {
                                if ("closed".equals(event.get("state"))) closed.complete(event);
                            },
                            (session, id, kind, acquire, confirmed) -> {},
                            1)) {
                var session =
                        media.create(
                                "safety-call",
                                "",
                                "http://local/events",
                                Map.of(
                                        "attempt",
                                        1,
                                        "voice_ai_profile",
                                        spec,
                                        "voice_ai_guard",
                                        Map.of(
                                                "realtime_playback_mode",
                                                "streaming",
                                                "max_reply_chars",
                                                240,
                                                "safety_fallback",
                                                preparedFallback
                                                        ? "approved_audio_then_end"
                                                        : "end",
                                                "fallback_reply",
                                                fallback)));
                media.connectAsync(socket, session.token);
                session.started.get(2, TimeUnit.SECONDS);
                media.respond(session.call, "按批准话术回复", null, "safety-op", true);
                var evidence = closed.get(4, TimeUnit.SECONDS);
                assertThat(reviewed.get(2, TimeUnit.SECONDS))
                        .contains("违规测试内容")
                        .doesNotContain("好的，请问您方便了解吗");
                if (preparedFallback) {
                    assertThat(played.get()).isPositive();
                    assertThat(actual.toByteArray())
                            .isEqualTo(VoiceAiAudio.resample(approved, 24000, 8000));
                    assertThat(evidence.get("content_safety").toString())
                            .contains("fallback_sent", "prepared_audio");
                } else assertThat(played).hasValue(0);
                assertThat(evidence.get("error_code")).isEqualTo("S2S_CONTENT_BLOCKED");
                assertThat(evidence.get("content_safety").toString())
                        .contains("blocked")
                        .doesNotContain("违规测试内容");
                assertThat(session.terminated).isDone();
            }
        } finally {
            server.stop(0);
        }
    }

    static Map<String, String> config(HttpServer server) {
        return Map.of(
                "VOICE_AI_SAFETY_ENDPOINT",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                "VOICE_AI_SAFETY_ACCESS_KEY_ID",
                "synthetic-id",
                "VOICE_AI_SAFETY_ACCESS_KEY_SECRET",
                "synthetic-secret");
    }

    static Map<String, String> form(String body) {
        var params = new TreeMap<String, String>();
        for (String field : body.split("&")) {
            String[] pair = field.split("=", 2);
            params.put(
                    URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    @Test
    void realHttpRequestUsesOfficialRpcSignatureAndStrictSemanticResult() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var captured = new CompletableFuture<Map<String, String>>();
        server.createContext(
                "/",
                exchange -> {
                    captured.complete(
                            form(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8)));
                    byte[] body =
                            Support.json(
                                    Map.of(
                                            "Code",
                                            200,
                                            "RequestId",
                                            "review-proof-1",
                                            "Data",
                                            Map.of(
                                                    "RiskLevel",
                                                    "none",
                                                    "Result",
                                                    List.of(Map.of("Label", "nonLabel")))));
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try (var scope = new ResponseScope()) {
            var runtime = new Settings(config(server));
            var result =
                    new ContentSafety(runtime)
                            .review(ContentSafety.policy(runtime), "完整短句。", scope);
            assertThat(result.get("request_id")).isEqualTo("review-proof-1");
            var params = new TreeMap<>(captured.get(2, TimeUnit.SECONDS));
            String signature = params.remove("Signature");
            var query = new StringJoiner("&");
            params.forEach((k, v) -> query.add(encode(k) + "=" + encode(v)));
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(
                    new SecretKeySpec(
                            "synthetic-secret&".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            assertThat(signature)
                    .isEqualTo(
                            Base64.getEncoder()
                                    .encodeToString(
                                            mac.doFinal(
                                                    ("POST&%2F&" + encode(query.toString()))
                                                            .getBytes(StandardCharsets.UTF_8))));
            assertThat(params.get("Service")).isEqualTo("llm_response_moderation");
            assertThat(params.get("ServiceParameters")).contains("完整短句");
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "high",
                "medium",
                "low",
                "missing",
                "contradictory",
                "missing-id",
                "http-error",
                "invalid-json"
            })
    void uncertainRiskOrBadResponsesFailClosed(String mode) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    var data = new HashMap<String, Object>();
                    if (!mode.equals("missing"))
                        data.put(
                                "RiskLevel",
                                Set.of("high", "medium", "low").contains(mode) ? mode : "none");
                    data.put(
                            "Result",
                            List.of(
                                    Map.of(
                                            "Label",
                                            mode.equals("contradictory")
                                                    ? "violent_weapons"
                                                    : "nonLabel")));
                    byte[] body =
                            mode.equals("invalid-json")
                                    ? "broken".getBytes(StandardCharsets.UTF_8)
                                    : Support.json(
                                            Map.of(
                                                    "Code",
                                                    200,
                                                    "RequestId",
                                                    mode.equals("missing-id") ? "" : "proof",
                                                    "Data",
                                                    data));
                    exchange.sendResponseHeaders(
                            mode.equals("http-error") ? 429 : 200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try (var scope = new ResponseScope()) {
            var runtime = new Settings(config(server));
            assertThatThrownBy(
                            () ->
                                    new ContentSafety(runtime)
                                            .review(ContentSafety.policy(runtime), "待审核。", scope))
                    .isInstanceOf(ContentSafety.Failure.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stalledServiceIsBoundedAndMissingCredentialsCannotBeBypassed() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CompletableFuture<Void>();
        server.createContext(
                "/",
                exchange -> {
                    try {
                        exchange.getRequestBody().readAllBytes();
                        release.get(2, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try (var scope = new ResponseScope()) {
            var runtime = new Settings(config(server));
            var policy = new LinkedHashMap<>(ContentSafety.policy(runtime));
            policy.put("timeout_ms", 100);
            long begin = System.nanoTime();
            assertThatThrownBy(() -> new ContentSafety(runtime).review(policy, "待审核。", scope))
                    .isInstanceOf(ContentSafety.Failure.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).isLessThan(1500);
            assertThatThrownBy(
                            () ->
                                    new ContentSafety(new Settings(Map.of()))
                                            .requireReady(
                                                    ContentSafety.policy(new Settings(Map.of()))))
                    .hasMessage("S2S_SAFETY_NOT_CONFIGURED");
            var prod = new Settings(Map.of("ENV", "production"));
            assertThatThrownBy(() -> new ContentSafety(prod).requireReady(policy))
                    .hasMessage("S2S_SAFETY_ENDPOINT_INVALID");
        } finally {
            release.complete(null);
            server.stop(0);
        }
    }
}
