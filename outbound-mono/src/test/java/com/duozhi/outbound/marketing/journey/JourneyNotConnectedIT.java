package com.duozhi.outbound.marketing.journey;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.marketing.MarketingTestBase;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** The application without P1: starts normally and says clearly what is missing. */
class JourneyNotConnectedIT extends MarketingTestBase {
    @Test
    void journeysExplainThatTheDataModuleIsNotConnected() {
        var list = admin(HttpMethod.GET, "/api/v1/marketing/journeys", null);
        assertThat(list.path("capabilities").path("snapshot_members").asBoolean()).isFalse();
        assertThat(list.path("capabilities").path("channel_sender").asBoolean()).isFalse();

        var sending =
                Map.of(
                        "nodes",
                        List.of(
                                JourneyTestBase.node(
                                        "s1",
                                        "sms",
                                        "sms",
                                        JourneyTestBase.sms("tpl"),
                                        "next",
                                        "e"),
                                JourneyTestBase.node("e", "end")));
        String id =
                admin(
                                HttpMethod.POST,
                                "/api/v1/marketing/journeys",
                                Map.of("name", "未接入", "definition", sending))
                        .path("id")
                        .asText();
        var publish =
                req(
                        admin,
                        HttpMethod.POST,
                        "/api/v1/marketing/journeys/" + id + "/publish",
                        Map.of());
        assertThat(publish.getStatusCode().value()).isEqualTo(409);
        assertThat(publish.getBody().path("detail").asText()).contains("数据模块未接入");

        var waiting =
                Map.of(
                        "nodes",
                        List.of(
                                JourneyTestBase.node("w", "wait", "hours", 1, "next", "e"),
                                JourneyTestBase.node("e", "end")));
        String quiet =
                admin(
                                HttpMethod.POST,
                                "/api/v1/marketing/journeys",
                                Map.of("name", "仅等待", "definition", waiting))
                        .path("id")
                        .asText();
        admin(HttpMethod.POST, "/api/v1/marketing/journeys/" + quiet + "/publish", Map.of());
        var start =
                req(
                        admin,
                        HttpMethod.POST,
                        "/api/v1/marketing/journeys/" + quiet + "/start",
                        Map.of("snapshotId", "snap-x"));
        assertThat(start.getStatusCode().value()).isEqualTo(409);
        assertThat(start.getBody().path("detail").asText()).contains("数据模块未接入");
    }
}
