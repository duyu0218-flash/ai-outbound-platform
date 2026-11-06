package com.duozhi.outbound.ai;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.ai.service.*;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.media.service.PcmLevel;
import com.duozhi.outbound.voiceai.service.OpeningAudioCache;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SpeechRuntimeSettingsTest {
    @Test
    void pcmGainSaturatesAndNoiseGatePreservesSpeechAndReadOnlyOwnership() {
        var original =
                ByteBuffer.allocate(6)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putShort((short) 100)
                        .putShort((short) 10000)
                        .putShort((short) -10000)
                        .flip();
        assertThat(PcmLevel.apply(original, 0, false)).isSameAs(original);
        var gated =
                PcmLevel.apply(original.asReadOnlyBuffer(), 12, true)
                        .order(ByteOrder.LITTLE_ENDIAN);
        assertThat(gated.getShort(0)).isZero();
        assertThat(gated.getShort(2)).isEqualTo(Short.MAX_VALUE);
        assertThat(gated.getShort(4)).isEqualTo(Short.MIN_VALUE);
        assertThat(original.getShort(0)).isEqualTo((short) 100);
        assertThat(original.position()).isZero();
    }

    @Test
    void speechOverridesReachVendorProtocolAndOpeningCacheIdentity() {
        var options =
                FlowSpeech.normalize(
                        Map.of(
                                "voice",
                                "aixia",
                                "speed",
                                75,
                                "pitch",
                                25,
                                "volume",
                                80,
                                "silence_ms",
                                800,
                                "collect_silence_ms",
                                1600));
        var base =
                Map.<String, Object>of(
                        "vendor",
                        "aliyun",
                        "voice",
                        "default",
                        "tts_url",
                        "wss://example.com/tts",
                        "tts_model",
                        "cosyvoice");
        var profile = FlowSpeech.profile(base, options);
        assertThat(FlowSpeech.aliyunTts(profile))
                .containsEntry("voice", "aixia")
                .containsEntry("rate", 1.25)
                .containsEntry("pitch", .75)
                .containsEntry("volume", 80);
        assertThat(OpeningAudioCache.key(1, base, "您好"))
                .isNotEqualTo(OpeningAudioCache.key(1, profile, "您好"));
        assertThat(OpeningAudioCache.key(1, profile, "您好"))
                .isNotEqualTo(OpeningAudioCache.key(2, profile, "您好"));
        assertThatThrownBy(
                        () ->
                                FlowSpeech.normalize(
                                        Map.of("silence_ms", 1800, "collect_silence_ms", 1600)))
                .isInstanceOf(ApiError.class);
    }

    @Test
    void fillerFilteringNeverConvertsRejectionIntoPositiveIntent() {
        var settings = FlowSettings.normalize(Map.of("filter_fillers", true, "fillers", "嗯,呃"));
        assertThat(ScriptIntents.classify("嗯呃不需要", Map.of("flow_settings", settings)))
                .isEqualTo("rejected");
    }

    @Test
    void recordingObjectLinksAreShortLivedAndBoundToTenantPrefix() {
        var env =
                new org.springframework.mock.env.MockEnvironment()
                        .withProperty("RECORDING_S3_ACCESS_KEY_ID", "SyntheticAccess")
                        .withProperty("RECORDING_S3_SECRET_ACCESS_KEY", "SyntheticSecretOnly")
                        .withProperty("S3_ENDPOINT_URL", "https://objects.example.com");
        var links =
                new com.duozhi.outbound.recording.service.RecordingLinks(
                        new com.duozhi.outbound.common.config.Config(env));
        var asset =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "id",
                                1,
                                "tenant_id",
                                7,
                                "call_session_id",
                                "synthetic-call",
                                "state",
                                "available",
                                "storage_uri",
                                "s3://ai-outbound-recordings/recordings/tenant-7/call-synthetic-call/asset-1.wav"));
        var link = links.link(asset, true);
        assertThat(link.get("expires_seconds")).isEqualTo(300);
        assertThat(link.get("url").toString())
                .contains("X-Amz-Expires=300")
                .contains("X-Amz-Signature=")
                .doesNotContain("SyntheticSecretOnly");
        asset.put("tenant_id", 8);
        assertThatThrownBy(() -> links.link(asset, false)).isInstanceOf(ApiError.class);
    }
}
