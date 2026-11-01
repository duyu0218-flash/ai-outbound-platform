package com.duozhi.outbound.marketing.journey;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.MarketingTestBase;
import com.duozhi.outbound.marketing.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.PlatformTransactionManager;

/** Checks the integrated application and the service's optional-module fallback separately. */
class JourneyNotConnectedIT extends MarketingTestBase {
    @Autowired
    ApplicationContext context;

    @Test
    void journeysExplainThatTheDataModuleIsNotConnected() {
        var list = admin(HttpMethod.GET, "/api/v1/marketing/journeys", null);
        assertThat(list.path("capabilities").path("snapshot_members").asBoolean()).isTrue();
        assertThat(list.path("capabilities").path("channel_sender").asBoolean()).isTrue();
        var empty = new StaticListableBeanFactory();
        var disconnected =
                new JourneyService(
                        context.getBean(MarketingStore.class),
                        context.getBean(JsonSupport.class),
                        context.getBean(MarketingClock.class),
                        context.getBean(PolicyConfig.class),
                        context.getBean(FrequencyRules.class),
                        context.getBean(SuppressionContactPolicy.class),
                        context.getBean(PhoneIdentity.class),
                        context.getBean(JourneyEngine.class),
                        empty.getBeanProvider(SnapshotMembers.class),
                        empty.getBeanProvider(ChannelSender.class),
                        context.getBean(PlatformTransactionManager.class));
        assertThat(disconnected.capabilities())
                .containsEntry("snapshot_members", false)
                .containsEntry("channel_sender", false);

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
        assertThatThrownBy(() -> disconnected.publish(tenant, 0, id))
                .isInstanceOf(ApiError.class)
                .hasMessageContaining("数据模块未接入");
        admin(HttpMethod.POST, "/api/v1/marketing/journeys/" + id + "/publish", Map.of());

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
        assertThatThrownBy(() -> disconnected.start(tenant, 0, quiet, "snap-x"))
                .isInstanceOf(ApiError.class)
                .hasMessageContaining("数据模块未接入");
    }
}
