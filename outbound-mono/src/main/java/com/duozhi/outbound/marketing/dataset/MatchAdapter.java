package com.duozhi.outbound.marketing.dataset;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * A joint-modelling partner. Online adapters answer one number per paid call; file adapters
 * exchange hashed number files instead. Every result is normalized to hit / score 0-100 / grade A-D
 * / best hours / labels before it is stored.
 */
public interface MatchAdapter {
    String MOCK = "mock", ANT_DAS = "ant_das", HASH_FILE = "hash_file";

    String id();

    String label();

    /** False when credentials or switches are missing; submissions are then refused with 503. */
    boolean configured();

    /** Why the adapter cannot be used, shown on the page. */
    default String unavailableReason() {
        return configured() ? "" : "合作方接口尚未配置";
    }

    /** True for one-request-per-number adapters driven by the match worker. */
    boolean online();

    /** Maximum calls per second for online adapters. */
    default int qps() {
        return 300;
    }

    record Query(String e164, int topN, String authorizationRef) {}

    record Normalized(
            boolean hit,
            BigDecimal score,
            String grade,
            List<Integer> bestHours,
            List<String> labels,
            Map<String, Object> raw) {
        static Normalized miss(Map<String, Object> raw) {
            return new Normalized(false, null, null, List.of(), List.of(), raw);
        }
    }

    /**
     * @param state success | no_data | failed | uncertain
     * @param httpStatus status the upstream outcome maps to (503 for missing service, 429 for
     *     throttling); used for the pause decision and shown on failures
     */
    record Outcome(
            String state,
            String code,
            String message,
            String requestId,
            int httpStatus,
            Normalized result) {}

    /** Online adapters only. Must never retry by itself: charging semantics are the partner's. */
    default Outcome query(Query query) {
        throw new UnsupportedOperationException("file exchange adapter");
    }

    /** Codes that pause a job at once (permissions, authorization, throttling). */
    default Set<String> criticalCodes() {
        return Set.of();
    }

    static String grade(BigDecimal score) {
        if (score == null) return null;
        if (score.compareTo(BigDecimal.valueOf(80)) >= 0) return "A";
        if (score.compareTo(BigDecimal.valueOf(60)) >= 0) return "B";
        if (score.compareTo(BigDecimal.valueOf(40)) >= 0) return "C";
        return "D";
    }

    /** Partner scores in 0..1 are probabilities; larger scales are clamped to 0..100. */
    static BigDecimal percent(BigDecimal value) {
        if (value == null) return null;
        BigDecimal v = value.compareTo(BigDecimal.ONE) <= 0 ? value.movePointRight(2) : value;
        if (v.compareTo(BigDecimal.valueOf(100)) > 0) v = BigDecimal.valueOf(100);
        if (v.signum() < 0) v = BigDecimal.ZERO;
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
