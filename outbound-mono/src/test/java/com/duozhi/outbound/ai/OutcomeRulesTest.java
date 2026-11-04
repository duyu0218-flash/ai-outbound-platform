package com.duozhi.outbound.ai;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.ai.service.OutcomeRules;
import java.util.*;
import org.junit.jupiter.api.Test;

class OutcomeRulesTest {
    @Test
    void eventTagsPersistAcrossTurnsAndHangupUsesFirstMatchingRating() {
        var event =
                Map.of(
                        "id",
                        "first",
                        "action",
                        "tag",
                        "value",
                        "意向",
                        "once",
                        true,
                        "conditions",
                        List.of(Map.of("field", "affirm", "op", "gte", "value", "1")));
        var grade =
                Map.of(
                        "id",
                        "priority",
                        "action",
                        "grade",
                        "value",
                        "A",
                        "conditions",
                        List.of(
                                Map.of("field", "tags", "op", "any", "value", "意向,预约"),
                                Map.of("field", "deny", "op", "lte", "value", "0")));
        var fallback =
                Map.of(
                        "id",
                        "fallback",
                        "action",
                        "grade",
                        "value",
                        "B",
                        "conditions",
                        List.of(Map.of("field", "rounds", "op", "gte", "value", "1")));
        Map<String, Object> p =
                Map.of(
                        "events",
                        OutcomeRules.normalize(List.of(event)),
                        "hangup_rules",
                        OutcomeRules.normalize(List.of(grade, fallback)));
        Map<String, Object> s = new HashMap<>();
        OutcomeRules.turn(p, s, "affirm", true, false);
        OutcomeRules.turn(p, s, "affirm", false, false);
        assertThat(s.get("rule_events_done")).isEqualTo(List.of("first"));
        assertThat(OutcomeRules.finish(p, s))
                .containsEntry("grade", "A")
                .containsEntry("tags", List.of("意向"));
        OutcomeRules.turn(p, s, "deny", false, true);
        assertThat(OutcomeRules.finish(p, s)).containsEntry("grade", "B");
    }
}
