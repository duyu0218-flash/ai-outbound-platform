package com.duozhi.outbound.messaging;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.messaging.service.MessageContent;
import java.util.*;
import org.junit.jupiter.api.Test;

class MessageContentTest {
    @Test
    void unicodeSignatureAndMultipartBoundariesArePricedTogether() {
        assertThat(MessageContent.smsUnits("中".repeat(70))).isEqualTo(1);
        assertThat(MessageContent.smsUnits("中".repeat(71))).isEqualTo(2);
        assertThat(MessageContent.smsUnits("中".repeat(134))).isEqualTo(2);
        assertThat(MessageContent.smsUnits("中".repeat(135))).isEqualTo(3);
        assertThatThrownBy(() -> MessageContent.smsUnits("您好😀")).isInstanceOf(ApiError.class);
    }

    @Test
    void richVariablesAreCollectedAcrossCardsAndRenderedExactlyOnce() {
        var template =
                Map.<String, Object>of(
                        "form",
                        "rcs",
                        "body",
                        "您好{{name}}",
                        "rich",
                        Map.of(
                                "title",
                                "{{title}}",
                                "cards",
                                List.of(
                                        Map.of(
                                                "title",
                                                "预约",
                                                "body",
                                                "{{date}}见",
                                                "actions",
                                                List.of(
                                                        Map.of(
                                                                "code",
                                                                "book",
                                                                "label",
                                                                "查看",
                                                                "type",
                                                                "url",
                                                                "value",
                                                                "https://example.com/book"))))));
        assertThat(MessageContent.variableNames(template))
                .containsExactlyInAnyOrder("name", "title", "date");
        var result =
                MessageContent.render(
                        template, Map.of("name", "李先生", "title", "预约通知", "date", "明天"), "测试");
        assertThat(result.get("text")).isEqualTo("【测试】您好李先生");
        assertThat(result.get("rich").toString()).contains("明天见");
        assertThatThrownBy(() -> MessageContent.render(template, Map.of("name", "李先生"), "测试"))
                .isInstanceOf(ApiError.class);
    }

    @Test
    void unsupportedSmsMediaAndUnreviewedButtonTargetsAreRejected() {
        assertThatThrownBy(
                        () ->
                                MessageContent.validate(
                                        Map.of(
                                                "form",
                                                "sms",
                                                "body",
                                                "测试",
                                                "rich",
                                                Map.of("title", "卡片"))))
                .isInstanceOf(ApiError.class);
        assertThatThrownBy(
                        () ->
                                MessageContent.validate(
                                        Map.of(
                                                "form",
                                                "yuexin",
                                                "body",
                                                "测试",
                                                "rich",
                                                Map.of(
                                                        "actions",
                                                        List.of(
                                                                Map.of(
                                                                        "code",
                                                                        "a",
                                                                        "label",
                                                                        "链接",
                                                                        "type",
                                                                        "url",
                                                                        "value",
                                                                        "javascript:alert(1)"))))))
                .isInstanceOf(ApiError.class);
        assertThatThrownBy(
                        () ->
                                MessageContent.render(
                                        Map.of("form", "sms", "body", "内容"), Map.of(), "伪造】签名"))
                .isInstanceOf(ApiError.class);
    }
}
