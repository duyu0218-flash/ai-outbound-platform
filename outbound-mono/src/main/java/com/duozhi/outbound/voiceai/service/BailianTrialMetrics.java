package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.common.exception.ApiError;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.LongSupplier;

/** Observed transport usage and separate estimates, never a vendor bill. */
public final class BailianTrialMetrics {
    public static final String NATIVE = "native", REVIEW = "secondary_review";
    private final String mode;
    private final Map<String, Object> prices;
    private final LongSupplier clock;
    private final Map<Long, Turn> turns = new LinkedHashMap<>();
    private long inputBytes, providerBytes, asrBytes, reviews, reviewResults, reviewNanos;

    public BailianTrialMetrics(String mode, Map<String, Object> prices) {
        this(mode, prices, System::nanoTime);
    }

    BailianTrialMetrics(String mode, Map<String, Object> prices, LongSupplier clock) {
        this.mode = mode;
        this.prices = Map.copyOf(prices);
        this.clock = clock;
    }

    public static String mode(Map<String, Object> body) {
        Object value = body.getOrDefault("review_mode", NATIVE);
        if (!NATIVE.equals(value) && !REVIEW.equals(value))
            throw new ApiError(400, "试聊模式须为 native 或 secondary_review");
        return value.toString();
    }

    public static Map<String, Object> prices(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map)) throw new ApiError(400, "估算单价格式无效");
        var result = new TreeMap<String, Object>();
        for (var entry : map.entrySet()) {
            String key = entry.getKey().toString();
            if (!Set.of("suite_per_1000_turns", "asr_per_minute", "review_per_10000_calls")
                    .contains(key)) throw new ApiError(400, "不支持的估算单价");
            try {
                if (!(entry.getValue() instanceof Number)) throw new IllegalArgumentException();
                var value = new BigDecimal(entry.getValue().toString()).stripTrailingZeros();
                if (value.signum() < 0
                        || value.compareTo(new BigDecimal("1000000")) > 0
                        || value.scale() > 8) throw new IllegalArgumentException();
                result.put(key, value);
            } catch (RuntimeException invalid) {
                throw new ApiError(400, "估算单价须为 0 至 1000000 的数字，最多八位小数");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public synchronized void start(long epoch, long inputEnd) {
        turns.put(epoch, new Turn(epoch, inputEnd));
    }

    public synchronized void input(int bytes) {
        inputBytes += bytes;
    }

    public synchronized void provider(long epoch, int bytes) {
        providerBytes += bytes;
        var t = turns.get(epoch);
        if (t != null && t.firstAudio == 0) t.firstAudio = clock.getAsLong();
    }

    public synchronized boolean release(long epoch) {
        var t = turns.get(epoch);
        if (t == null || t.released != 0) return false;
        t.released = clock.getAsLong();
        return true;
    }

    public synchronized void playback(long epoch) {
        var t = turns.get(epoch);
        if (t != null && t.playback == 0) t.playback = clock.getAsLong();
    }

    public synchronized void clientTiming(long epoch, Map<String, Object> command) {
        var t = turns.get(epoch);
        if (t == null) return;
        for (String key :
                List.of("client_input_to_schedule_ms", "scheduled_lead_ms", "capture_drain_ms")) {
            Object raw = command.get(key);
            if (raw instanceof Number n
                    && Double.isFinite(n.doubleValue())
                    && n.doubleValue() >= 0
                    && n.doubleValue() <= 300000) t.client.put(key, n.doubleValue());
        }
    }

    public synchronized void complete(long epoch, String status) {
        var t = turns.get(epoch);
        if (t != null) t.status = status;
    }

    public synchronized void asr(int bytes) {
        asrBytes += bytes;
    }

    public synchronized void reviewStarted() {
        reviews++;
    }

    public synchronized void reviewFinished(long nanos, boolean result) {
        reviewNanos += nanos;
        if (result) reviewResults++;
    }

    public synchronized Map<String, Object> snapshot() {
        var usage = new LinkedHashMap<String, Object>();
        usage.put("suite_response_turns", turns.size());
        usage.put("suite_input_audio_seconds", decimal(inputBytes, 32000));
        usage.put("suite_output_audio_seconds", decimal(providerBytes, 48000));
        usage.put("secondary_asr_audio_seconds", decimal(asrBytes, 48000));
        usage.put("secondary_review_requests", reviews);
        usage.put("secondary_review_results", reviewResults);
        usage.put("secondary_review_request_ms", reviewNanos / 1000000.0);
        var cost = new LinkedHashMap<String, Object>();
        cost.put("currency", "CNY");
        cost.put("status", "estimate_only_bill_pending");
        cost.put("actual_total", null);
        cost.put("suite_estimate", estimate("suite_per_1000_turns", turns.size(), 1000));
        cost.put("secondary_asr_estimate", estimate("asr_per_minute", asrBytes, 48000 * 60));
        cost.put("secondary_review_estimate", estimate("review_per_10000_calls", reviews, 10000));
        Object asr = cost.get("secondary_asr_estimate"),
                review = cost.get("secondary_review_estimate");
        cost.put(
                "secondary_total_estimate",
                asr == null || review == null ? null : ((BigDecimal) asr).add((BigDecimal) review));
        var latency = turns.values().stream().map(Turn::snapshot).toList();
        return Map.of(
                "version",
                1,
                "review_mode",
                mode,
                "prices",
                prices,
                "usage",
                usage,
                "cost",
                cost,
                "turns",
                latency,
                "latency_basis",
                "server_input_end_to_provider_audio; provider_audio_to_platform_release; server_input_end_to_browser_ack");
    }

    private BigDecimal estimate(String key, long units, long divisor) {
        if (units == 0) return BigDecimal.ZERO;
        if (!prices.containsKey(key)) return null;
        return new BigDecimal(prices.get(key).toString())
                .multiply(BigDecimal.valueOf(units))
                .divide(BigDecimal.valueOf(divisor), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(long units, long divisor) {
        return BigDecimal.valueOf(units)
                .divide(BigDecimal.valueOf(divisor), 6, RoundingMode.HALF_UP);
    }

    private static final class Turn {
        final long epoch, inputEnd;
        long firstAudio, released, playback;
        String status = "responding";
        final Map<String, Object> client = new LinkedHashMap<>();

        Turn(long epoch, long inputEnd) {
            this.epoch = epoch;
            this.inputEnd = inputEnd;
        }

        Map<String, Object> snapshot() {
            var result = new LinkedHashMap<String, Object>();
            result.put("epoch", epoch);
            result.put("status", status);
            result.put("provider_first_audio_ms", elapsed(inputEnd, firstAudio));
            result.put("platform_release_wait_ms", elapsed(firstAudio, released));
            result.put("browser_playback_ack_ms", elapsed(inputEnd, playback));
            result.put("evidence_level", "browser_scheduled_not_acoustic");
            result.putAll(client);
            return result;
        }

        static Double elapsed(long start, long end) {
            return start == 0 || end == 0 || end < start ? null : (end - start) / 1000000.0;
        }
    }
}
