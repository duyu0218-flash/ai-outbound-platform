package com.duozhi.outbound.workspace.service;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class DebugCollectionTest {
    Map<String, Object> snapshot(boolean confirm, int retries, String missing) {
        return Map.of(
                "opening",
                "您好",
                "ending",
                "感谢配合",
                "policy",
                Map.of(
                        "slots",
                        List.of(
                                Map.of(
                                        "key",
                                        "age",
                                        "label",
                                        "年龄",
                                        "kind",
                                        "integer",
                                        "question",
                                        "您的年龄？",
                                        "required",
                                        true,
                                        "confirm",
                                        confirm))),
                "slot_rules",
                List.of(
                        Map.of(
                                "key",
                                "age",
                                "min_length",
                                1,
                                "max_length",
                                3,
                                "min_value",
                                18,
                                "max_value",
                                70,
                                "max_retries",
                                retries,
                                "missing_action",
                                missing)));
    }

    @Test
    void rehearsalUsesTheSameRangeValidatorAndRequiresExplicitConfirmation() {
        var s = snapshot(true, 2, "end");
        var state = new LinkedHashMap<String, Object>();
        var values = new LinkedHashMap<String, Object>();
        assertThat(DebugCollection.step(s, state, values, "开始", "continue", 0).get("reply"))
                .isEqualTo("您的年龄？");
        assertThat(
                        DebugCollection.step(s, state, values, "17", "continue", 1)
                                .get("reply")
                                .toString())
                .contains("范围");
        assertThat(values).doesNotContainKey("age");
        assertThat(
                        DebugCollection.step(s, state, values, "30", "continue", 2)
                                .get("reply")
                                .toString())
                .contains("对吗");
        assertThat(values).doesNotContainKey("age");
        assertThat(DebugCollection.step(s, state, values, "是的", "affirm", 3).get("action"))
                .isEqualTo("hangup");
        assertThat(values.get("age")).isEqualTo("30");
    }

    @Test
    void retriesExhaustIntoConfiguredMissingActionInsteadOfAcceptingBadData() {
        var s = snapshot(false, 1, "handoff");
        var state = new LinkedHashMap<String, Object>();
        var values = new LinkedHashMap<String, Object>();
        DebugCollection.step(s, state, values, "开始", "continue", 0);
        DebugCollection.step(s, state, values, "不说", "continue", 1);
        var result = DebugCollection.step(s, state, values, "不说", "continue", 2);
        assertThat(result.get("action")).isEqualTo("handoff");
        assertThat(values).isEmpty();
    }
}
