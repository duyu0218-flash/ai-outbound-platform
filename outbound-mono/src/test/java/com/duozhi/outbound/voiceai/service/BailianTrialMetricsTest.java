package com.duozhi.outbound.voiceai.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.integration.config.Settings;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BailianTrialMetricsTest {
    @Test
    void separatesCostsAndClocksWithoutTreatingEstimateAsBill() {
        var clock = new AtomicLong(100_000_000);
        var rates =
                BailianTrialMetrics.prices(
                        Map.of(
                                "suite_per_1000_turns",
                                5.45,
                                "asr_per_minute",
                                0.0132,
                                "review_per_10000_calls",
                                15));
        var m = new BailianTrialMetrics(BailianTrialMetrics.REVIEW, rates, clock::get);
        m.start(1, clock.get());
        clock.set(300_000_000);
        m.provider(1, 48000 * 60);
        m.asr(48000 * 60);
        for (int i = 0; i < 10; i++) {
            m.reviewStarted();
            m.reviewFinished(50_000_000, true);
        }
        clock.set(900_000_000);
        assertThat(m.release(1)).isTrue();
        assertThat(m.release(1)).isFalse();
        clock.set(1_000_000_000);
        m.playback(1);
        m.complete(1, "completed");
        var report = m.snapshot();
        var cost = (Map<?, ?>) report.get("cost");
        assertThat((BigDecimal) cost.get("suite_estimate")).isEqualByComparingTo("0.00545");
        assertThat((BigDecimal) cost.get("secondary_asr_estimate")).isEqualByComparingTo("0.0132");
        assertThat((BigDecimal) cost.get("secondary_review_estimate"))
                .isEqualByComparingTo("0.015");
        assertThat((BigDecimal) cost.get("secondary_total_estimate"))
                .isEqualByComparingTo("0.0282");
        assertThat(cost.get("actual_total")).isNull();
        var turn = (Map<?, ?>) ((List<?>) report.get("turns")).getFirst();
        assertThat(turn.get("provider_first_audio_ms")).isEqualTo(200.0);
        assertThat(turn.get("platform_release_wait_ms")).isEqualTo(600.0);
        assertThat(turn.get("browser_playback_ack_ms")).isEqualTo(900.0);
    }

    @Test
    void unknownPriceAndMissingPlaybackRemainUnknownWhileUnusedExtrasAreZero() {
        var m = new BailianTrialMetrics(BailianTrialMetrics.NATIVE, Map.of());
        m.start(1, System.nanoTime());
        m.provider(1, 960);
        m.complete(1, "interrupted");
        var report = m.snapshot();
        var cost = (Map<?, ?>) report.get("cost");
        assertThat(cost.get("suite_estimate")).isNull();
        assertThat(cost.get("secondary_asr_estimate")).isEqualTo(BigDecimal.ZERO);
        assertThat(cost.get("secondary_review_estimate")).isEqualTo(BigDecimal.ZERO);
        var turn = (Map<?, ?>) ((List<?>) report.get("turns")).getFirst();
        assertThat(turn.get("browser_playback_ack_ms")).isNull();
        assertThat(turn.get("platform_release_wait_ms")).isNull();
    }

    @Test
    void validatesModesAndExplicitRates() {
        assertThat(BailianTrialMetrics.mode(Map.of())).isEqualTo("native");
        assertThatThrownBy(() -> BailianTrialMetrics.mode(Map.of("review_mode", "off")))
                .hasMessageContaining("模式");
        assertThatThrownBy(() -> BailianTrialMetrics.prices(Map.of("asr_per_minute", -1)))
                .hasMessageContaining("单价");
        assertThatThrownBy(() -> BailianTrialMetrics.prices(Map.of("asr_per_minute", Double.NaN)))
                .hasMessageContaining("单价");
        assertThatThrownBy(() -> BailianTrialMetrics.prices(Map.of("unknown", 1)))
                .hasMessageContaining("单价");
        assertThat(BailianTrialMetrics.prices(Map.of("asr_per_minute", 0)).get("asr_per_minute"))
                .isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void nativeReadinessDoesNotRequireSafetyAccountButReviewDoes() {
        var credentials = mock(VoiceAiCredentials.class);
        var runtime =
                new Settings(
                        Map.of(
                                "DASHSCOPE_API_KEY",
                                "test",
                                "VOICE_AI_BAILIAN_WORKSPACE_ID",
                                "workspace",
                                "VOICE_AI_BAILIAN_APP_ID",
                                "app",
                                "VOICE_AI_BAILIAN_APP_REVISION",
                                "v1",
                                "VOICE_AI_BAILIAN_TOOL_API_KEY",
                                "tool"));
        when(credentials.runtime(any(), eq(1L), eq(-1L))).thenReturn(runtime);
        var service =
                new BailianTrials(
                        new Config(new MockEnvironment()),
                        credentials,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        try {
            assertThat(service.readiness(1).get("configured")).isEqualTo(true);
            assertThat(service.readiness(1, BailianTrialMetrics.REVIEW).get("configured"))
                    .isEqualTo(false);
        } finally {
            service.stop();
        }
    }
}
