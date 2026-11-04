package com.duozhi.outbound.ai;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.ai.service.ScriptIntents;
import com.duozhi.outbound.common.exception.ApiError;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScriptIntentsTest {
    @Test
    void publishedKeywordsAndRegexPreserveMandatoryStop() {
        var rules =
                ScriptIntents.normalize(
                        List.of(
                                Map.of(
                                        "name",
                                        "业务咨询",
                                        "target",
                                        "interested",
                                        "keywords",
                                        "绿色方案",
                                        "regex",
                                        "^选项[甲乙]$"),
                                Map.of("name", "兜底", "target", "continue", "regex", ".*")));
        Map<String, Object> policy = Map.of("intent_rules", rules);
        assertThat(ScriptIntents.classify("绿色方案", policy)).isEqualTo("interested");
        assertThat(ScriptIntents.classify("选项乙", policy)).isEqualTo("interested");
        assertThat(ScriptIntents.classify("绿色方案，不要再联系", policy)).isEqualTo("stop_contact");
        assertThatThrownBy(
                        () ->
                                ScriptIntents.normalize(
                                        List.of(
                                                Map.of(
                                                        "name", "错误", "target", "affirm", "regex",
                                                        "("))))
                .isInstanceOf(ApiError.class);
    }
}
