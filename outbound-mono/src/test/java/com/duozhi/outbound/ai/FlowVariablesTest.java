package com.duozhi.outbound.ai;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.ai.service.*;
import com.duozhi.outbound.auth.service.AccountSecurity;
import com.duozhi.outbound.common.exception.ApiError;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowVariablesTest {
    @Test
    void variablesInitializePerCallIncrementOnceAndDriveHangupGrade() {
        var vars =
                FlowVariables.normalize(
                        List.of(
                                Map.of("name", "挽回次数", "type", "NUM", "initial", 0, "export", true),
                                Map.of(
                                        "name", "内部状态", "type", "STR", "initial", "draft", "export",
                                        false)));
        var event =
                Map.of(
                        "id",
                        "deny-count",
                        "conditions",
                        List.of(Map.of("field", "deny", "op", "gte", "value", "1")),
                        "action",
                        "increment",
                        "value",
                        "挽回次数=1",
                        "once",
                        true);
        var rule =
                Map.of(
                        "id",
                        "grade",
                        "conditions",
                        List.of(Map.of("field", "variable:挽回次数", "op", "gte", "value", "1")),
                        "action",
                        "grade",
                        "value",
                        "C待跟进");
        Map<String, Object> policy =
                Map.of(
                        "custom_variables",
                        vars,
                        "events",
                        OutcomeRules.normalize(List.of(event)),
                        "hangup_rules",
                        OutcomeRules.normalize(List.of(rule)));
        var state = new LinkedHashMap<String, Object>();
        state.put("flow_variables", FlowVariables.initial(policy));
        OutcomeRules.turn(policy, state, "deny", false, false);
        OutcomeRules.turn(policy, state, "deny", false, false);
        assertThat(OutcomeRules.finish(policy, state)).containsEntry("grade", "C待跟进");
        var values =
                com.duozhi.outbound.workspace.service.WorkspaceDocuments.map(
                        state.get("flow_variables"));
        assertThat(FlowVariables.exported(policy, values).get("挽回次数").toString()).isEqualTo("1");
        assertThat(FlowVariables.exported(policy, values)).doesNotContainKey("内部状态");
        assertThat(FlowVariables.initial(policy).get("挽回次数").toString()).isEqualTo("0");
        assertThatThrownBy(
                        () ->
                                FlowVariables.normalize(
                                        List.of(
                                                Map.of(
                                                        "name", "counter", "type", "NUM", "initial",
                                                        "oops", "export", true))))
                .isInstanceOf(ApiError.class);
    }

    @Test
    void totpMatchesRfc6238Vectors() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        assertThat(AccountSecurity.totp(secret, 59 / 30)).isEqualTo("287082");
        assertThat(AccountSecurity.totp(secret, 1111111109L / 30)).isEqualTo("081804");
        assertThat(AccountSecurity.totp(secret, 20000000000L / 30)).isEqualTo("353130");
    }

    @Test
    void evaluationHandlesSampleSizeAndAllThreeThresholdBands() {
        var rule =
                Map.<String, Object>of(
                        "minimum_connected", 10, "metric", "intent", "recommend", 30, "reject", 10);
        assertThat(
                        com.duozhi.outbound.operations.service.TaskStrategies.assess(
                                rule, Map.of("connected", 9, "interested", 9)))
                .containsEntry("advice", "insufficient");
        assertThat(
                        com.duozhi.outbound.operations.service.TaskStrategies.assess(
                                rule, Map.of("connected", 10, "interested", 3)))
                .containsEntry("advice", "recommend");
        assertThat(
                        com.duozhi.outbound.operations.service.TaskStrategies.assess(
                                rule, Map.of("connected", 10, "interested", 2)))
                .containsEntry("advice", "caution");
        assertThat(
                        com.duozhi.outbound.operations.service.TaskStrategies.assess(
                                rule, Map.of("connected", 10, "interested", 1)))
                .containsEntry("advice", "reject");
    }
}
