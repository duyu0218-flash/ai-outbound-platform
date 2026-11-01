package com.duozhi.outbound.marketing.dataset;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.marketing.common.*;
import com.duozhi.outbound.marketing.journey.*;
import com.duozhi.outbound.marketing.link.Clicks;
import com.duozhi.outbound.marketing.outcome.OutcomeService;
import com.duozhi.outbound.messaging.service.Messaging;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/** Real P1-P4 implementations together; all execution workers and paid providers are disabled. */
class MarketingLifecycleIT extends DatasetTestSupport {
    @Autowired
    MarketingClock clock;
    @Autowired
    JourneyEngine engine;
    @Autowired
    Clicks clicks;
    @Autowired
    Messaging messaging;
    @Autowired
    ContactPolicy policy;
    @Autowired
    PhoneIdentity phones;
    @Autowired
    OutcomeService outcomes;

    @AfterEach
    void resetClock() {
        jdbc.update("UPDATE mk_journey SET status='stopped' WHERE tenant_id=?", tenant);
        clock.reset();
    }

    String resource(String kind, Map<String, Object> payload) {
        var row =
                (Map<?, ?>)
                        messaging.createResource(
                                tenant, kind, Map.of("name", "闭环测试" + kind, "payload", payload));
        String id = row.get("id").toString();
        messaging.resourceState(tenant, id, "APPROVED");
        return id;
    }

    @Test
    void importedSnapshotSmsLinkClickJourneyCallOutcomeAndProfileShareAttribution() {
        String version = version(13700000001L, 3);
        String segment =
                admin(
                                HttpMethod.POST,
                                "/api/v1/marketing/segments",
                                Map.of(
                                        "name",
                                        "闭环人群",
                                        "rules",
                                        Map.of("versionIds", List.of(version))))
                        .path("id")
                        .asText();
        String snapshot =
                admin(
                                HttpMethod.POST,
                                "/api/v1/marketing/segments/" + segment + "/snapshots",
                                Map.of("controlPercent", 0))
                        .path("id")
                        .asText();
        admin(
                HttpMethod.POST,
                "/api/v1/marketing/links/domains",
                Map.of("domain", "s" + tenant + ".example.test", "state", "active", "weight", 100));
        admin(
                HttpMethod.POST,
                "/api/v1/marketing/links/landing-domains",
                Map.of("domain", "landing.example.test"));
        String product =
                resource(
                        "product",
                        Map.of("form", "sms", "unit_price", 1000, "daily_recipient_limit", 100));
        String sender = resource("sender", Map.of("brand", "闭环测试"));
        String template = resource("template", Map.of("form", "sms", "body", "预约请访问 {{link}}"));
        messaging.credit(tenant, "lifecycle-credit", 1_000_000L);
        String task =
                admin(
                                HttpMethod.POST,
                                "/api/v1/marketing/send-tasks",
                                Map.of(
                                        "snapshotId",
                                        snapshot,
                                        "channel",
                                        "sms",
                                        "sms",
                                        Map.of(
                                                "productId",
                                                product,
                                                "senderId",
                                                sender,
                                                "templateId",
                                                template,
                                                "landingUrl",
                                                "https://landing.example.test/offer")))
                        .path("id")
                        .asText();
        sends.run(tenant, task, 10);
        assertThat(((Number) sends.task(tenant, task).get("accepted")).longValue()).isEqualTo(3);
        var link =
                jdbc.queryForMap(
                        "SELECT * FROM mk_short_link WHERE tenant_id=? ORDER BY code LIMIT 1",
                        tenant);
        assertThat(link.get("message_id")).isNotNull();
        String hash = link.get("phone_hash").toString();
        String e164 =
                phones.decrypt(
                        tenant,
                        jdbc.queryForObject(
                                "SELECT phone_cipher FROM mk_snapshot_member WHERE tenant_id=? AND snapshot_id=? AND phone_hash=?",
                                String.class,
                                tenant,
                                snapshot,
                                hash));
        // One SMS was sent, so the real P4 policy prevents an immediate call without a click.
        clock.fix(Instant.now().plusSeconds(20));
        assertThat(policy.admit(tenant, e164, hash, OutcomeCodes.CALL).allowed()).isFalse();
        long campaign =
                admin(
                                HttpMethod.POST,
                                "/api/v1/campaigns",
                                Map.of("name", "闭环外呼", "mode", "ai_only"))
                        .path("id")
                        .asLong();
        var definition =
                Map.of(
                        "nodes",
                        List.of(
                                Map.of(
                                        "id",
                                        "clicked",
                                        "type",
                                        "condition",
                                        "codes",
                                        List.of(OutcomeCodes.LINK_CLICKED),
                                        "windowHours",
                                        24,
                                        "yes",
                                        "call",
                                        "no",
                                        "end"),
                                Map.of(
                                        "id",
                                        "call",
                                        "type",
                                        "call",
                                        "campaignId",
                                        campaign,
                                        "next",
                                        "end"),
                                Map.of("id", "end", "type", "end")));
        String journey =
                admin(
                                HttpMethod.POST,
                                "/api/v1/marketing/journeys",
                                Map.of("name", "点击后外呼", "definition", definition))
                        .path("id")
                        .asText();
        admin(HttpMethod.POST, "/api/v1/marketing/journeys/" + journey + "/publish", Map.of());
        admin(
                HttpMethod.POST,
                "/api/v1/marketing/journeys/" + journey + "/start",
                Map.of("snapshotId", snapshot));
        engine.step(100);
        clock.advance(Duration.ofSeconds(1));
        assertThat(
                        clicks.offer(
                                new Clicks.Click(
                                        link.get("code").toString(),
                                        clock.now(),
                                        "192.0.2.0/24",
                                        "Mozilla/5.0 (iPhone) AppleWebKit/605.1.15 Safari/604.1",
                                        false)))
                .isTrue();
        clicks.flush();
        assertThat(policy.admit(tenant, e164, hash, OutcomeCodes.CALL).allowed()).isTrue();
        engine.step(100);
        var dispatch =
                jdbc.queryForMap(
                        "SELECT * FROM mk_dispatch WHERE tenant_id=? AND journey_id=? AND channel='call'",
                        tenant,
                        journey);
        assertThat(dispatch)
                .containsEntry("snapshot_id", snapshot)
                .containsEntry("phone_hash", hash);
        assertThat(dispatch.get("journey_member_id")).isNotNull();
        String call = dispatch.get("target_id").toString();
        jdbc.update(
                "UPDATE callsession SET status='FAILED',last_error='NO_ANSWER',finished_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND id=?",
                tenant,
                call);
        outcomes.classify(tenant, call, "manual");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_touch_event WHERE tenant_id=? AND source_id=? AND snapshot_id=? AND journey_id=?",
                                tenant,
                                call,
                                snapshot,
                                journey))
                .isPositive();
        var profile =
                jdbc.queryForMap(
                        "SELECT * FROM mk_profile WHERE tenant_id=? AND phone_hash=?",
                        tenant,
                        hash);
        assertThat(profile).containsEntry("link_clicks", 1).containsEntry("call_count", 1);
        engine.step(100);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM callsession WHERE tenant_id=? AND campaign_id=?",
                                tenant,
                                campaign))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_journey_member WHERE tenant_id=? AND journey_id=? AND state='waiting'",
                                tenant,
                                journey))
                .isEqualTo(2);
        assertThat(
                        req(agent, HttpMethod.GET, "/api/v1/marketing/journeys/" + journey, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }
}
