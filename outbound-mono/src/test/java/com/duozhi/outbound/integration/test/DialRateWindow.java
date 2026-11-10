package com.duozhi.outbound.integration.test;

import java.util.*;

/** Conservative second buckets for commit intervals, including empty seconds. */
final class DialRateWindow {
    private final long phaseUs;
    private final int warmup, seconds;
    private final long[] guaranteed, possible;

    DialRateWindow(long phaseUs, int warmup, int seconds) {
        this.phaseUs = phaseUs;
        this.warmup = warmup;
        this.seconds = seconds;
        guaranteed = new long[seconds];
        possible = new long[seconds];
    }

    void add(long loUs, long hiUs) {
        if (hiUs < loUs) throw new IllegalArgumentException("inverted commit interval");
        long lo = Math.floorDiv(loUs - phaseUs, 1_000_000L) - warmup;
        long hi = Math.floorDiv(hiUs - phaseUs, 1_000_000L) - warmup;
        if (lo == hi && lo >= 0 && lo < seconds) guaranteed[(int) lo]++;
        for (long i = Math.max(0, lo); i <= Math.min(seconds - 1, hi); i++) possible[(int) i]++;
    }

    Map<String, Object> summary() {
        var buckets = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < seconds; i++) {
            buckets.add(
                    Map.of(
                            "second_after_phase",
                            warmup + i,
                            "guaranteed_commits",
                            guaranteed[i],
                            "possible_commits",
                            possible[i]));
        }
        return Map.of(
                "counting",
                        "unique tenant/call/attempt durable acknowledgements; JDBC commit intervals, not created_at or HTTP acceptance",
                "scope",
                        "observed saturation on this host/configuration; not a production limit or sustained capacity qualification",
                "per_second", buckets,
                "peak_cps_lower_bound", Arrays.stream(guaranteed).max().orElse(0),
                "peak_cps_upper_bound", Arrays.stream(possible).max().orElse(0),
                "minimum_second_cps_lower_bound", Arrays.stream(guaranteed).min().orElse(0),
                "average_cps_lower_bound", Arrays.stream(guaranteed).average().orElse(0));
    }
}
