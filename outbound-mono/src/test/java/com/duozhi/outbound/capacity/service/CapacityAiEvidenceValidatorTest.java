package com.duozhi.outbound.capacity.service;

import static com.duozhi.outbound.capacity.service.CapacityAiEvidenceValidator.*;
import static com.duozhi.outbound.capacity.service.CapacityEvidenceValidator.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CapacityAiEvidenceValidatorTest {
    record Fixture(List<Span> spans, List<Slot> slots, List<Reply> replies) {}

    private Event e(String id, long time) {
        return new Event(id, time, time);
    }

    private Fixture fixture(int seconds) {
        var spans = new ArrayList<Span>();
        var slots = new ArrayList<Slot>();
        var replies = new ArrayList<Reply>();
        for (int i = 0; i < 500; i++) {
            String session = "session" + i;
            spans.add(
                    new Span(
                            session, e(session, -20 * SECOND), e(session, (seconds + 1) * SECOND)));
        }
        for (int i = -500; i < 50 * seconds; i++) {
            String id = "slot" + i, session = "session" + Math.floorMod(i, 500);
            long time = i * 20_000L;
            slots.add(new Slot(id, session, false, e(id, time)));
            replies.add(new Reply(id, e(id, time + 100_000), true));
        }
        return new Fixture(spans, slots, replies);
    }

    @Test
    void phasedFollowupWithWarmupProvesSameWindowActivityAndLatency() {
        var f = fixture(20);
        var result = validate(f.spans(), f.slots(), f.replies(), 0, 20);
        assertThat(result.errors()).isEmpty();
        assertThat(result.passed()).isTrue();
        assertThat(result.minimumInteractive()).isEqualTo(500);
        assertThat(result.plannedSubsequent()).containsOnly(50);
        assertThat(result.completedSubsequent()).containsOnly(50);
        assertThat(result.p95Micros()).isEqualTo(100_000);
    }

    @Test
    void fiveHundredIdleConnectionsAndOpeningResponsesCannotReplaceFollowup() {
        var f = fixture(2);
        var openings =
                f.slots().stream()
                        .map(s -> new Slot(s.identity(), s.session(), true, s.planned()))
                        .toList();
        var result = validate(f.spans(), openings, f.replies(), 0, 2);
        assertThat(result.minimumInteractive()).isZero();
        assertThat(result.plannedSubsequent()).containsOnly(0);
        assertThat(result.passed()).isFalse();
    }

    @Test
    void missingMinorityAndLateReplyCannotDisappearBehindSuccessfulP95() {
        var f = fixture(20);
        var replies = new ArrayList<>(f.replies());
        replies.removeIf(r -> r.identity().equals("slot999"));
        replies.replaceAll(
                r ->
                        r.identity().equals("slot998")
                                ? new Reply(r.identity(), e(r.identity(), 25 * SECOND), true)
                                : r);
        var result = validate(f.spans(), f.slots(), replies, 0, 20);
        assertThat(result.p95Micros()).isEqualTo(100_000);
        assertThat(result.errors())
                .anyMatch(x -> x.contains("missing AI completion"))
                .anyMatch(x -> x.contains("hard deadline"));
        assertThat(result.passed()).isFalse();
    }

    @Test
    void repeatedSessionEvidenceCannotMultiplyConcurrentSessions() {
        var f = fixture(2);
        var sameSession = Collections.nCopies(500, f.spans().get(0));
        var result = validate(sameSession, f.slots(), f.replies(), 0, 2);
        assertThat(result.minimumInteractive()).isEqualTo(1);
        assertThat(result.passed()).isFalse();
    }

    @Test
    void intervalUncertaintyAndMissingLastSecondAreKeptAsFailures() {
        var f = fixture(2);
        var spans = new ArrayList<>(f.spans());
        String session = "session0";
        spans.set(0, new Span(session, new Event(session, -SECOND, 1), e(session, SECOND)));
        var result = validate(spans, f.slots(), f.replies(), 0, 2);
        assertThat(result.minimumInteractive()).isEqualTo(499);
        assertThat(result.worstInteractiveAt()).isZero();
        assertThat(result.plannedSubsequent()).hasSize(2);
        assertThat(result.passed()).isFalse();
    }

    @Test
    void duplicateOrUnplannedCompletionsNeverBecomeAdditionalThroughput() {
        var f = fixture(2);
        var replies = new ArrayList<>(f.replies());
        replies.add(f.replies().get(500));
        replies.add(new Reply("unknown", e("unknown", SECOND), true));
        var result = validate(f.spans(), f.slots(), replies, 0, 2);
        assertThat(result.errors())
                .anyMatch(x -> x.contains("duplicate AI completion"))
                .anyMatch(x -> x.contains("unplanned AI completion"));
        assertThat(result.passed()).isFalse();
    }

    @Test
    void otherwiseHealthyMixedWindowStillRejectsAReplyAfterItsSessionEnded() {
        var f = fixture(2);
        var spans = new ArrayList<>(f.spans());
        var slots = new ArrayList<>(f.slots());
        var replies = new ArrayList<>(f.replies());
        spans.add(new Span("ended", e("ended", -SECOND), e("ended", -1)));
        slots.add(new Slot("late-opening", "ended", true, e("late-opening", 0)));
        replies.add(new Reply("late-opening", e("late-opening", 100_000), true));
        var result = validate(spans, slots, replies, 0, 2);
        assertThat(result.minimumInteractive()).isEqualTo(500);
        assertThat(result.errors()).anyMatch(x -> x.contains("proven interactive lifetime"));
        assertThat(result.passed()).isFalse();
    }
}
