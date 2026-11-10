package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DialRateWindowTest {
    @Test
    void boundaryIntervalsCannotInflateGuaranteedCapacityAndEmptySecondsRemainFailures() {
        var window = new DialRateWindow(10_000_000, 1, 3);
        window.add(10_990_000, 11_010_000); // straddles warmup/measurement boundary
        window.add(11_200_000, 11_250_000);
        window.add(11_990_000, 12_010_000); // may belong to either measured second
        window.add(9_990_000, 9_999_999); // before the phase
        window.add(14_000_000, 14_010_000); // after the window
        var summary = window.summary();
        var buckets = (List<Map<String, Object>>) summary.get("per_second");
        assertThat(buckets)
                .extracting(row -> row.get("guaranteed_commits"))
                .containsExactly(1L, 0L, 0L);
        assertThat(buckets)
                .extracting(row -> row.get("possible_commits"))
                .containsExactly(3L, 1L, 0L);
        assertThat(summary)
                .containsEntry("peak_cps_lower_bound", 1L)
                .containsEntry("peak_cps_upper_bound", 3L)
                .containsEntry("minimum_second_cps_lower_bound", 0L);
        assertThatThrownBy(() -> window.add(2, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
