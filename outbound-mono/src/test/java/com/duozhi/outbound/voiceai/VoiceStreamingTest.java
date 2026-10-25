package com.duozhi.outbound.voiceai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.media.service.NativeMedia;
import com.duozhi.outbound.voiceai.service.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.*;

class VoiceStreamingTest {
    @Test
    void cancelledScopesCloseInFlightReadsAndRejectLateResources() throws Exception {
        var scope = new ResponseScope();
        var closed = new AtomicInteger();
        scope.own(closed::incrementAndGet);
        scope.close();
        scope.close();
        assertThat(closed.get()).isEqualTo(1);
        assertThatThrownBy(scope::check).isInstanceOf(InterruptedIOException.class);
        assertThatThrownBy(() -> scope.own(closed::incrementAndGet))
                .isInstanceOf(InterruptedIOException.class);
        assertThat(closed.get()).isEqualTo(2);
    }

    @Test
    void sentencesAreApprovedCumulativelyAndIncompleteOrBlockedTailsDoNotLeak() throws Exception {
        var heard = new ArrayList<String>();
        var committer =
                new TextCommitter(
                        Map.of("forbidden_phrases", "保证收益", "max_reply_chars", 100), heard::add);
        committer.append("您好。");
        committer.append("我们保");
        assertThat(heard).containsExactly("您好。");
        assertThatThrownBy(() -> committer.append("证收益。")).isInstanceOf(IOException.class);
        assertThat(heard).containsExactly("您好。");
        var crossed = new TextCommitter(Map.of("forbidden_phrases", "免 费"), heard::add);
        crossed.append("免。");
        assertThatThrownBy(() -> crossed.append("费。")).isInstanceOf(IOException.class);
        var facts = new TextCommitter(Map.of("require_fact_templates", true), heard::add);
        assertThatThrownBy(() -> facts.append("已预约成功。")).isInstanceOf(IOException.class);
    }

    static byte[] audio(int milliseconds, int rate, int level) {
        byte[] pcm = new byte[milliseconds * rate / 1000 * 2];
        for (int i = 0; i < pcm.length; i += 2) {
            pcm[i] = (byte) level;
            pcm[i + 1] = (byte) (level >> 8);
        }
        return pcm;
    }

    @Test
    void frozenEndpointPolicyUsesSamplesAndProtectsNumbersAndConsecutiveSpeech() {
        var mutable =
                new HashMap<String, Object>(
                        Map.of(
                                "vad_start_ms",
                                150,
                                "vad_end_ms",
                                300,
                                "vad_long_end_ms",
                                800,
                                "vad_adaptive",
                                false));
        var turn = new TurnManager(mutable, 16000);
        mutable.put("vad_end_ms", 2000);
        assertThat(turn.audio(audio(100, 16000, 5000))).isEqualTo(TurnManager.Signal.NONE);
        assertThat(turn.audio(audio(50, 16000, 5000))).isEqualTo(TurnManager.Signal.START);
        assertThat(turn.audio(audio(299, 16000, 0))).isEqualTo(TurnManager.Signal.NONE);
        assertThat(turn.audio(audio(1, 16000, 0))).isEqualTo(TurnManager.Signal.COMMIT);
        turn.audio(audio(150, 16000, 5000));
        turn.partial("地址是123");
        assertThat(turn.audio(audio(500, 16000, 0))).isEqualTo(TurnManager.Signal.NONE);
        assertThat(turn.audio(audio(300, 16000, 0))).isEqualTo(TurnManager.Signal.COMMIT);
        turn.audio(audio(100, 16000, 5000));
        turn.audio(audio(20, 16000, 0));
        assertThat(turn.audio(audio(100, 16000, 5000))).isEqualTo(TurnManager.Signal.NONE);
    }

    @Test
    void answerDecisionRequiresFinalEvidenceAndNeverReopensOrTreatsQuestionsAsMachines() {
        var classifier = new AnswerClassifier();
        assertThat(classifier.transcript("语音信箱", false))
                .isEqualTo(AnswerClassifier.Answer.CLASSIFYING);
        assertThat(classifier.transcript("你是电话助理吗？", true))
                .isEqualTo(AnswerClassifier.Answer.HUMAN);
        assertThat(classifier.transcript("滴声后留言", true)).isEqualTo(AnswerClassifier.Answer.HUMAN);
        var machine = new AnswerClassifier();
        assertThat(machine.transcript("请在滴声后留言", true)).isEqualTo(AnswerClassifier.Answer.MACHINE);
        assertThat(machine.timeout()).isEqualTo(AnswerClassifier.Answer.MACHINE);
        assertThat(new AnswerClassifier().timeout()).isEqualTo(AnswerClassifier.Answer.UNKNOWN);
    }

    @Test
    void ownershipAndPlaybackEvidenceAreIndependentAndTerminalIsAbsorbing() throws Exception {
        var coordinator = new SessionCoordinator();
        long first = coordinator.begin(new ResponseScope());
        coordinator.generated(first, 100);
        coordinator.sent(first, 40);
        coordinator.text(first, "未播完整句");
        assertThat(coordinator.evidence().get("played_samples")).isEqualTo(0L);
        coordinator.interrupt();
        assertThatThrownBy(() -> coordinator.sent(first, 20))
                .isInstanceOf(InterruptedIOException.class);
        long next = coordinator.begin(new ResponseScope());
        coordinator.sent(next, 80);
        coordinator.text(next, "确认播出的短句。");
        assertThat(coordinator.drained(next, true)).isEqualTo("确认播出的短句。");
        assertThat(coordinator.evidence().get("played_samples")).isEqualTo(80L);
        coordinator.close();
        assertThatThrownBy(() -> coordinator.begin(new ResponseScope()))
                .isInstanceOf(InterruptedIOException.class);
    }

    @Test
    void tupleQuotaDoesNotPartiallyAcquireAndReleaseIsIdempotent() throws Exception {
        var resources = new VoiceResources(new Settings(Map.of("VOICE_AI_MAX_SESSIONS", "2")));
        var profile =
                Map.<String, Object>of("vendor", "aliyun", "approved_quotas", Map.of("s2s", 2));
        var lease = resources.acquire(profile, 1, "ai", "s2s");
        assertThatThrownBy(() -> resources.acquire(profile, 2, "ai", "s2s"))
                .hasMessageContaining("exhausted");
        assertThat(resources.usage()).containsEntry("ai", 1).doesNotContainKey("tenant:2");
        lease.close();
        lease.close();
        assertThat(resources.usage()).isEmpty();
        assertThatThrownBy(
                        () ->
                                new VoiceResources(new Settings(Map.of("ENV", "production")))
                                        .acquire(Map.of("vendor", "aliyun"), 1, "s2s"))
                .isInstanceOf(RuntimeException.class);
    }

    static Settings settings(
            VoiceAiBackendsTest.Fixture asr, VoiceAiBackendsTest.Fixture tts, int port) {
        return new Settings(
                Map.of(
                        "DASHSCOPE_API_KEY",
                        "synthetic-local-key",
                        "VOICE_AI_PROFILES_ENABLED",
                        "true",
                        "VOICE_AI_ALI_ASR_URL",
                        asr.url(),
                        "VOICE_AI_ALI_TTS_URL",
                        tts.url(),
                        "VOICE_AI_ALI_LLM_URL",
                        "http://127.0.0.1:" + port + "/chat/completions"));
    }

    @Test
    void actualMediaSendsFirstSentenceBeforeLlmDoneAndUsesOneTtsSession() throws Exception {
        var firstPlayed = new CompletableFuture<Void>();
        var completed = new CompletableFuture<Void>();
        var failures = new CopyOnWriteArrayList<String>();
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    requests.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (var output = exchange.getResponseBody()) {
                        byte[] first =
                                "data: {\"choices\":[{\"delta\":{\"content\":\"您好，欢迎了解。\"}}]}\r\n\r\n"
                                        .getBytes(StandardCharsets.UTF_8);
                        // Splitting every UTF-8 byte proves transport boundaries do not become
                        // token boundaries.
                        for (byte b : first) {
                            output.write(b);
                            output.flush();
                        }
                        firstPlayed.get(4, TimeUnit.SECONDS);
                        output.write(
                                "data: {\"choices\":[{\"delta\":{\"content\":\"请问有什么问题？\"}}]}\n\ndata: [DONE]\n\n"
                                        .getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    } catch (Exception failure) {
                        failures.add(failure.toString());
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try (var asr = new VoiceAiBackendsTest.Fixture("aliyun", "asr");
                var tts = new VoiceAiBackendsTest.Fixture("aliyun", "tts")) {
            var runtime = settings(asr, tts, server.getAddress().getPort());
            var spec = new VoiceAiProfiles(runtime).snapshot("aliyun-cascade");
            var socket = mock(WebSocketSession.class);
            when(socket.getId()).thenReturn("stream-test");
            when(socket.isOpen()).thenReturn(true);
            doAnswer(
                            inv -> {
                                if (inv.getArgument(0) instanceof BinaryMessage)
                                    firstPlayed.complete(null);
                                return null;
                            })
                    .when(socket)
                    .sendMessage(any());
            try (var media =
                    new NativeMedia(
                            runtime,
                            (url, event) -> {
                                if (event.get("error_code") != null) failures.add(event.toString());
                                if (firstPlayed.isDone() && "listening".equals(event.get("state")))
                                    completed.complete(null);
                            },
                            (session, id, kind, acquire, confirmed) -> {},
                            2)) {
                var session =
                        media.create(
                                "stream-call",
                                "",
                                "http://local/events",
                                Map.of(
                                        "attempt",
                                        1,
                                        "voice_ai_profile",
                                        spec,
                                        "voice_ai_guard",
                                        Map.of("max_reply_chars", 240)));
                media.connectAsync(socket, session.token);
                session.started.get(4, TimeUnit.SECONDS);
                media.respond(
                        session.call,
                        "prepared request",
                        null,
                        "op-one",
                        true,
                        Map.of("messages", List.of(Map.of("role", "user", "content", "介绍服务"))));
                completed.get(8, TimeUnit.SECONDS);
                assertThat(media.evidence(session.call).get("played_samples")).isEqualTo(0L);
                assertThat(requests.get()).isEqualTo(1);
                assertThat(failures).isEmpty();
                assertThat(tts.problem.get()).isNull();
                assertThat(tts.connections.get()).isEqualTo(1);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void s2sCommitsAudioWithoutWaitingForTranscriptAndRejectsLateCreatedAfterCancel()
            throws Exception {
        try (var fixture = new VoiceAiBackendsTest.Fixture("aliyun", "realtime")) {
            fixture.suppressTranscript = true;
            fixture.responseDelayMs = 150;
            var runtime =
                    new Settings(
                            Map.of(
                                    "DASHSCOPE_API_KEY",
                                    "synthetic-key",
                                    "VOICE_AI_ALI_REALTIME_URL",
                                    fixture.url(),
                                    "VOICE_AI_ALI_REALTIME_MODEL",
                                    "test-model"));
            var spec = new VoiceAiProfiles(runtime).snapshot("aliyun-realtime");
            var committed = new CompletableFuture<Void>();
            var transcript = new AtomicInteger();
            try (var connection =
                            new VoiceAiBackends(runtime)
                                    .connect(
                                            spec,
                                            "批准话术",
                                            new VoiceAiBackends.Listener() {
                                                public void transcript(String text, String event) {
                                                    transcript.incrementAndGet();
                                                }

                                                public void speechStarted() {}

                                                public void inputCommitted() {
                                                    committed.complete(null);
                                                }
                                            });
                    var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                connection.audio(audio(200, 16000, 1500));
                connection.audio(audio(500, 16000, 0));
                committed.get(2, TimeUnit.SECONDS);
                assertThat(transcript.get()).isZero();
                var sent = new AtomicInteger();
                var replying =
                        executor.submit(
                                () ->
                                        connection.reply(
                                                "", true, (pcm, rate) -> sent.incrementAndGet()));
                assertThat(fixture.responseRequested.await(2, TimeUnit.SECONDS)).isTrue();
                connection.interrupt();
                assertThatThrownBy(() -> replying.get(2, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class);
                assertThatThrownBy(
                                () ->
                                        connection.reply(
                                                "", true, (pcm, rate) -> sent.incrementAndGet()))
                        .hasMessageContaining("rebuilt");
                assertThat(fixture.cancellations.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(sent.get()).isZero();
            }
        }
    }

    @Test
    void incompleteSseDoesNotReleaseAnUncommittedTail() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] data =
                            "data: {\"choices\":[{\"delta\":{\"content\":\"不完整\"}}]}\n\n"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                    exchange.close();
                });
        server.start();
        var runtime =
                new Settings(
                        Map.of(
                                "DASHSCOPE_API_KEY",
                                "test",
                                "VOICE_AI_ALI_LLM_URL",
                                "http://127.0.0.1:"
                                        + server.getAddress().getPort()
                                        + "/chat/completions"));
        var approved = new ArrayList<String>();
        try (var scope = new ResponseScope()) {
            var committer = new TextCommitter(Map.of(), approved::add);
            assertThatThrownBy(
                            () ->
                                    new VoiceAiBackends(runtime)
                                            .generate(
                                                    new VoiceAiProfiles(runtime)
                                                            .snapshot("aliyun-cascade"),
                                                    List.of(
                                                            Map.of(
                                                                    "role", "user", "content",
                                                                    "测试")),
                                                    committer::append,
                                                    scope))
                    .hasMessageContaining("without DONE");
            assertThat(approved).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preparedOpeningIsReusableAndTenantScoped(@TempDir
    Path directory) throws Exception {
        try (var asr = new VoiceAiBackendsTest.Fixture("aliyun", "asr");
                var tts = new VoiceAiBackendsTest.Fixture("aliyun", "tts")) {
            var env =
                    new HashMap<String, String>(
                            Map.of(
                                    "DASHSCOPE_API_KEY",
                                    "test",
                                    "VOICE_AI_ALI_TTS_URL",
                                    tts.url(),
                                    "VOICE_AI_OPENING_CACHE_DIR",
                                    directory.toString()));
            var runtime = new Settings(env);
            var spec = new VoiceAiProfiles(runtime).snapshot("aliyun-cascade");
            var cache = new OpeningAudioCache(runtime);
            assertThat(cache.read(1, spec, "我是AI助手。")).isNull();
            var result = cache.prepare(1, spec, "我是AI助手。", new VoiceAiBackends(runtime));
            assertThat(result).containsEntry("prepared", true).containsEntry("played", false);
            assertThat(cache.read(1, spec, "我是AI助手。")).isNotEmpty();
            assertThat(cache.read(2, spec, "我是AI助手。")).isNull();
        }
    }

    @Test
    void machineClassificationNeverStartsTheS2sProviderAndPersistsEvidence() throws Exception {
        var classified = new CompletableFuture<Map<String, Object>>();
        try (var asr = new VoiceAiBackendsTest.Fixture("aliyun", "asr");
                var realtime = new VoiceAiBackendsTest.Fixture("aliyun", "realtime")) {
            asr.transcript = "请在滴声后留言";
            var runtime =
                    new Settings(
                            Map.of(
                                    "DASHSCOPE_API_KEY",
                                    "local",
                                    "VOICE_AI_PROFILES_ENABLED",
                                    "true",
                                    "VOICE_AI_ALI_ASR_URL",
                                    asr.url(),
                                    "VOICE_AI_ALI_REALTIME_URL",
                                    realtime.url()));
            var profiles = new VoiceAiProfiles(runtime);
            var profile = new LinkedHashMap<>(profiles.snapshot("aliyun-realtime"));
            profile.put("speech_profile", profiles.snapshot("aliyun-cascade"));
            var socket = mock(WebSocketSession.class);
            when(socket.getId()).thenReturn("machine");
            when(socket.isOpen()).thenReturn(true);
            try (var media =
                    new NativeMedia(
                            runtime,
                            (url, event) -> {
                                if ("machine".equals(event.get("answer_class"))
                                        && event.get("transcript") != null)
                                    classified.complete(event);
                            },
                            (session, id, kind, acquire, confirmed) -> {},
                            2)) {
                var session =
                        media.create(
                                "machine-call",
                                "http://speech",
                                "http://media",
                                Map.of(
                                        "attempt",
                                        1,
                                        "voice_ai_profile",
                                        profile,
                                        "voice_ai_guard",
                                        Map.of("answer_classification_enabled", true)));
                media.connectAsync(socket, session.token);
                session.started.get(4, TimeUnit.SECONDS);
                assertThat(realtime.connections.get()).isZero();
                media.enqueueAudio(socket, java.nio.ByteBuffer.wrap(audio(20, 8000, 5000)));
                assertThat(classified.get(4, TimeUnit.SECONDS))
                        .containsEntry("answer_class", "machine");
                assertThat(realtime.connections.get()).isZero();
                assertThat(media.metrics().get("ai_active_sessions").intValue()).isZero();
            }
        }
    }
}
