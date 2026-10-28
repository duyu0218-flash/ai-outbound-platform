package com.duozhi.outbound.voiceai;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.voiceai.service.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VoiceOptimizationTest {
    @Test
    void callbackContractPreservesTurnQualityAndMediaEvidence() throws Exception {
        var contract =
                new com.duozhi.outbound.common.web.Contract(
                        new com.fasterxml.jackson.databind.ObjectMapper());
        var speech = new LinkedHashMap<String, Object>();
        speech.put("call_id", UUID.randomUUID().toString());
        speech.put("event_id", "local-contract-event");
        speech.put("is_final", true);
        speech.put("provider_final", true);
        speech.put("turn_committed", false);
        speech.put("recognition_quality", RecognitionQuality.assess(Map.of("text", "三点"), true));
        assertThat(contract.input("SpeechWebhookEvent", speech, true))
                .containsEntry("turn_committed", false)
                .containsKey("recognition_quality");
        var media = new LinkedHashMap<String, Object>();
        media.put("call_id", speech.get("call_id"));
        media.put("event_id", "local-media-event");
        media.put("state", "listening");
        media.put("voice_evidence", Map.of("input_dropped_samples", 160));
        media.put("content_safety", List.of(Map.of("status", "approved")));
        assertThat(contract.input("MediaWebhookEvent", media, true))
                .containsKeys("voice_evidence", "content_safety");
    }

    static Map<String, Object> passed() {
        return Map.of("risk_level", "none", "request_id", "local-review");
    }

    static Map<String, Object> finalEvent(String id, String text) {
        return Map.of(
                "event_id",
                id + ":final",
                "utterance_id",
                id,
                "text",
                text,
                "is_final",
                true,
                "start_ms",
                0,
                "end_ms",
                200);
    }

    @Test
    void ttsHandshakeDoesNotBlockGenerationAndOnlyReviewedSentencesReachTts() throws Exception {
        var connecting = new CompletableFuture<Void>();
        var generating = new CompletableFuture<Void>();
        var approve = new CompletableFuture<Void>();
        var heard = new CopyOnWriteArrayList<String>();
        var trace = new CopyOnWriteArrayList<Map<String, Object>>();
        var ttsClosed = new AtomicBoolean();
        try (var scope = new ResponseScope();
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var result =
                    tasks.submit(
                            () ->
                                    CascadePipeline.run(
                                            scope,
                                            Map.of(),
                                            () -> {
                                                connecting.complete(null);
                                                generating.get(2, TimeUnit.SECONDS);
                                                return new VoiceAiBackends.TextSession() {
                                                    public void append(String text) {
                                                        heard.add(text);
                                                    }

                                                    public void finish() {}

                                                    public void close() {
                                                        ttsClosed.set(true);
                                                    }
                                                };
                                            },
                                            delta -> {
                                                connecting.get(2, TimeUnit.SECONDS);
                                                generating.complete(null);
                                                delta.accept("这是有用回答。");
                                                delta.accept("这是后一句。");
                                            },
                                            (text, child) -> {
                                                if (text.contains("后一句"))
                                                    throw new ContentSafety.Failure(
                                                            "S2S_CONTENT_BLOCKED");
                                                assertThat(heard).isEmpty();
                                                approve.get(2, TimeUnit.SECONDS);
                                                return passed();
                                            },
                                            trace::add,
                                            stage -> {}));
            generating.get(2, TimeUnit.SECONDS);
            assertThat(heard).isEmpty();
            approve.complete(null);
            assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            assertThat(heard).doesNotContain("这是后一句。");
            assertThat(scope.cancelled()).isTrue();
            assertThat(trace.toString()).contains("approved", "blocked", "segment_sha256");
        }
    }

    @Test
    void slowReviewDoesNotBlockReadingNextModelDeltaAndStillGatesTts() throws Exception {
        var reviewing = new CompletableFuture<Void>();
        var generated = new CompletableFuture<Void>();
        var heard = new CopyOnWriteArrayList<String>();
        var reviews = new CopyOnWriteArrayList<String>();
        try (var scope = new ResponseScope()) {
            String result =
                    CascadePipeline.run(
                            scope,
                            Map.of(),
                            () ->
                                    new VoiceAiBackends.TextSession() {
                                        public void append(String text) {
                                            heard.add(text);
                                        }

                                        public void finish() {}

                                        public void close() {}
                                    },
                            delta -> {
                                delta.accept("第一句回答。");
                                reviewing.get(2, TimeUnit.SECONDS);
                                delta.accept("第二句回答。");
                                generated.complete(null);
                            },
                            (text, child) -> {
                                reviewing.complete(null);
                                generated.get(2, TimeUnit.SECONDS);
                                reviews.add(text);
                                return passed();
                            },
                            e -> {},
                            stage -> {});
            assertThat(result).isEqualTo("第一句回答。第二句回答。");
            assertThat(heard).containsExactly("第一句回答。", "第二句回答。");
            assertThat(reviews.getLast()).contains("第一句回答。", "第二句回答。");
        }
    }

    @Test
    void aLateApprovalAfterCancellationCannotSubmitAnyText() throws Exception {
        var reviewing = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var heard = new CopyOnWriteArrayList<String>();
        try (var scope = new ResponseScope();
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate =
                    new SegmentSafetyGate(
                            scope,
                            Map.of(),
                            (text, child) -> {
                                reviewing.complete(null);
                                release.get(2, TimeUnit.SECONDS);
                                return passed();
                            },
                            heard::add,
                            e -> {});
            var pending =
                    tasks.submit(
                            () -> {
                                gate.append("完整回答。");
                                return null;
                            });
            reviewing.get(2, TimeUnit.SECONDS);
            scope.close();
            release.complete(null);
            assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            assertThat(heard).isEmpty();
        }
    }

    @Test
    void failedTtsPreparationCancelsBlockedGenerationAndReturnsOriginalFailure() throws Exception {
        var closed = new CompletableFuture<Void>();
        try (var scope = new ResponseScope()) {
            scope.own(() -> closed.complete(null));
            assertThatThrownBy(
                            () ->
                                    CascadePipeline.run(
                                            scope,
                                            Map.of(),
                                            () -> {
                                                throw new IOException(
                                                        "synthetic TTS handshake failed");
                                            },
                                            delta -> {
                                                closed.get(2, TimeUnit.SECONDS);
                                                scope.check();
                                            },
                                            (t, s) -> passed(),
                                            e -> {},
                                            s -> {}))
                    .hasMessageContaining("synthetic TTS handshake failed");
            assertThat(scope.cancelled()).isTrue();
        }
    }

    @Test
    void multipleFinalsRemainEvidenceUntilOneBusinessTurnCommits() throws Exception {
        var events = new ArrayList<Map<String, Object>>();
        var controller =
                new InputTurnController(
                        Map.of("input_turn_controller", "enforce", "vad_adaptive", false),
                        8000,
                        events::add);
        controller.audio(VoiceStreamingTest.audio(160, 8000, 2000));
        controller.recognition(finalEvent("u1", "明天下午"));
        controller.audio(VoiceStreamingTest.audio(500, 8000, 0));
        assertThat(events).hasSize(1);
        controller.audio(VoiceStreamingTest.audio(180, 8000, 2000));
        controller.recognition(finalEvent("u2", "三点以后才有空。"));
        controller.audio(VoiceStreamingTest.audio(400, 8000, 0));
        assertThat(events).hasSize(3);
        assertThat(events.getLast())
                .containsEntry("text", "明天下午三点以后才有空。")
                .containsEntry("turn_committed", true);
        assertThat(events.getFirst())
                .containsEntry("is_final", true)
                .containsEntry("turn_committed", false);
        controller.recognition(finalEvent("u2", "三点以后才有空。"));
        assertThat(events).hasSize(3);
    }

    @Test
    void lateFinalAfterEndpointDoesNotWaitASecondSilenceWindow() throws Exception {
        var events = new ArrayList<Map<String, Object>>();
        var controller =
                new InputTurnController(
                        Map.of("input_turn_controller", "enforce", "vad_adaptive", false),
                        8000,
                        events::add);
        controller.audio(VoiceStreamingTest.audio(160, 8000, 2000));
        controller.audio(VoiceStreamingTest.audio(400, 8000, 0));
        controller.recognition(finalEvent("u1", "三点可以。"));
        assertThat(events).hasSize(2);
        assertThat(events.getLast()).containsEntry("turn_committed", true);
    }

    @Test
    void gapInAnEarlierFinalCannotBeErasedByACleanLaterFinal() throws Exception {
        var events = new ArrayList<Map<String, Object>>();
        var controller =
                new InputTurnController(
                        Map.of("input_turn_controller", "enforce", "vad_adaptive", false),
                        8000,
                        events::add);
        controller.audio(VoiceStreamingTest.audio(160, 8000, 2000));
        var damaged = new HashMap<>(finalEvent("u1", "电话是123"));
        damaged.put("recognition_quality", RecognitionQuality.assess(damaged, true));
        controller.recognition(damaged);
        controller.recognition(finalEvent("u2", "456。"));
        controller.audio(VoiceStreamingTest.audio(800, 8000, 0));
        assertThat(events.getLast().get("recognition_quality").toString())
                .contains("needs_confirmation", "audio_gap");
    }

    @ParameterizedTest
    @ValueSource(strings = {"三点可以", "我不要了", "明天下午三点以后可以", "不是现在卖，过半年再说"})
    void completeAnswersDoNotWaitJustBecauseTheyContainANumberOrNegation(String text) {
        assertThat(TurnManager.unfinished(text)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"电话号码是123", "如果", "明天下午", "地址是", "然后……"})
    void unfinishedFieldsAndConditionsKeepTheirLongWindow(String text) {
        assertThat(TurnManager.unfinished(text)).isTrue();
    }

    @Test
    void recognitionParametersRejectUnknownFieldsAndBiasedOutputAuditing() {
        assertThatThrownBy(
                        () ->
                                RecognitionOptions.parse(
                                        "{\"aliyun\":{\"context\":\"猜测文字\"}}", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RecognitionOptions.parse(
                                        "{\"tencent\":{\"hotword_list\":\"蜜制|100\"}}", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RecognitionOptions.parse(
                                        "{\"aliyun\":{\"vocabulary_id\":\"expected-text\"}}", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> RecognitionOptions.parse("{\"tencent\":{\"filter_dirty\":1}}", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        RecognitionOptions.parse(
                                "{\"tencent\":{\"hotword_list\":\"车型|5\",\"word_info\":1}}", false))
                .containsKey("tencent");
    }

    @Test
    void independentAuditModelChangesBothDeclaredRateAndActualResamplingTarget() {
        var customer =
                Map.<String, Object>of(
                        "vendor",
                        "aliyun",
                        "route",
                        "cascade",
                        "asr_model",
                        "fun-asr-flash-8k-realtime");
        var audit =
                RecognitionOptions.attach(
                        customer, "{\"aliyun\":{\"asr_model\":\"fun-asr-realtime\"}}", true);
        assertThat(RecognitionOptions.rate(customer)).isEqualTo(8000);
        assertThat(RecognitionOptions.rate(audit)).isEqualTo(16000);
        assertThat(RecognitionOptions.aliyunParameters(audit)).containsEntry("sample_rate", 16000);
        assertThat(customer.get("asr_model")).isEqualTo("fun-asr-flash-8k-realtime");
    }

    @Test
    void pcmWindowReleasesPrefixesWithoutLosingAbsoluteOffsetsOrRetainedBytes() {
        var window = new PcmWindow();
        for (int i = 0; i < 100; i++) {
            byte[] bytes = new byte[4800];
            Arrays.fill(bytes, (byte) i);
            window.writeBytes(bytes);
            assertThat(window.range(i * 4800L, (i + 1) * 4800L)).containsOnly((byte) i);
            window.release((i + 1) * 4800L);
            assertThat(window.retainedBytes()).isZero();
        }
        assertThat(window.size()).isEqualTo(480000);
        assertThatThrownBy(() -> window.range(0, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void historyBudgetsKeepWholeRecentRecordsAndMissingConfidenceStaysUnknown() {
        assertThat(HistoryWindow.recent(List.of("旧记录", "新记录", "最新"), 3, 5, String::length))
                .containsExactly("新记录", "最新");
        assertThat(HistoryWindow.recent(List.of("小", "这个记录超过预算"), 3, 3, String::length)).isEmpty();
        assertThat(RecognitionQuality.assess(Map.of("text", "不是现在卖"), false))
                .containsEntry("status", "usable")
                .containsEntry("confidence_status", "unknown");
        assertThat(RecognitionQuality.assess(Map.of("text", "不是现在卖"), true))
                .containsEntry("status", "needs_confirmation");
    }
}
