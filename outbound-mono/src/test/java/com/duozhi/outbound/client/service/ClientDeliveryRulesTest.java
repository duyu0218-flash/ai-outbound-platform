package com.duozhi.outbound.client.service;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.common.exception.ApiError;
import java.util.*;
import org.junit.jupiter.api.Test;

class ClientDeliveryRulesTest {
    static Map<String, Object> analysis(String result, String review, boolean needsReview) {
        var row = new HashMap<String, Object>();
        row.put("result_code", result);
        row.put("review_state", review);
        row.put("needs_review", needsReview);
        return row;
    }

    @Test
    void onlyReviewedSubscribedResultsQualifyUnlessAutoDeliveryIsOn() {
        var config = ClientSettings.defaults();
        assertThat(ClientDelivery.qualified(analysis("qualified_lead", "reviewed", false), config))
                .isTrue();
        assertThat(ClientDelivery.qualified(analysis("qualified_lead", "automatic", false), config))
                .isFalse();
        assertThat(ClientDelivery.qualified(analysis("qualified_lead", "reviewed", true), config))
                .isFalse();
        assertThat(ClientDelivery.qualified(analysis("interested", "reviewed", false), config))
                .isFalse();
        config.put("auto_deliver", true);
        config.put("deliver_results", List.of("qualified_lead", "interested"));
        assertThat(ClientDelivery.qualified(analysis("interested", "automatic", false), config))
                .isTrue();
        assertThat(ClientDelivery.qualified(null, config)).isFalse();
    }

    @Test
    void settingsRejectUnknownResultsAndOutOfRangeValues() {
        assertThatThrownBy(
                        () ->
                                ClientSettings.validate(
                                        Map.of("deliver_results", List.of("rejected"))))
                .isInstanceOf(ApiError.class);
        assertThatThrownBy(() -> ClientSettings.validate(Map.of("dedup_days", 400)))
                .isInstanceOf(ApiError.class);
        assertThatThrownBy(() -> ClientSettings.validate(Map.of("phone_reveal", "yes")))
                .isInstanceOf(ApiError.class);
        var saved = ClientSettings.validate(Map.of("dedup_days", 0, "phone_reveal", true));
        assertThat(saved).containsEntry("dedup_days", 0).containsEntry("phone_reveal", true);
        assertThat(saved.get("deliver_results")).isEqualTo(List.of("qualified_lead"));
    }

    @Test
    void phoneNumbersAreMaskedInFieldsAndFreeText() {
        assertThat(ClientDelivery.mask("13912345678")).isEqualTo("139****5678");
        assertThat(ClientDelivery.mask("123")).isEqualTo("****");
        assertThat(ClientLeadQuery.maskText("请回拨13912345678或13800001111"))
                .isEqualTo("请回拨139****5678或138****1111");
    }
}
