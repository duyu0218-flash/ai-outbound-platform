package com.duozhi.outbound.marketing.journey;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.marketing.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

class FunnelDashboardIT extends JourneyTestBase {
    @Autowired
    Attribution attribution;
    @Autowired
    FunnelStats stats;

    private JsonNode dashboard(String query) {
        String day = LocalDate.ofInstant(start, ZONE).toString();
        return admin(
                HttpMethod.GET,
                "/api/v1/marketing/dashboard?from=" + day + "&to=" + day + query,
                null);
    }

    private static void funnel(JsonNode d, long... values) {
        var steps = FunnelStats.STEPS;
        for (int i = 0; i < values.length; i++)
            assertThat(d.path(steps.get(i)).asLong()).as(steps.get(i)).isEqualTo(values[i]);
    }

    @Test
    void hourlyAggregatesFeedFunnelRatesIncrementAndCost() {
        var a = phone(1);
        var b = phone(2);
        var c = phone(3);
        var d = phone(4);
        var e = phone(5);
        String snap =
                snapshot(
                        List.of(
                                member(1, a, "test"),
                                member(2, b, "test"),
                                member(3, c, "test"),
                                member(4, d, "control"),
                                member(5, e, "control")));
        var def =
                Map.of(
                        "nodes",
                        List.of(
                                node("s1", "sms", "sms", sms("tpl-1"), "next", "end"),
                                node("end", "end")));
        String jid = createJourney("漏斗测试", def);
        publish(jid);
        startJourney(jid, snap);
        drain();
        assertThat(sender.of(tenant)).hasSize(3);

        clock.advance(Duration.ofHours(1));
        String ma = memberId(jid, a), mb = memberId(jid, b), mc = memberId(jid, c);
        touch(a, "sms", OutcomeCodes.SMS_DELIVERED, ma);
        touch(a, "link", OutcomeCodes.LINK_CLICKED, ma);
        touch(a, "call", OutcomeCodes.CALL_ANSWERED, ma);
        touch(a, "call", OutcomeCodes.ANS_CONVERSATION, ma);
        touch(a, "call", OutcomeCodes.INTENT_A, ma);
        touch(a, "sms", OutcomeCodes.CONVERTED, ma);
        touch(b, "sms", OutcomeCodes.SMS_DELIVERED, mb);
        touch(b, "call", OutcomeCodes.CALL_ANSWERED, mb);
        touch(b, "call", OutcomeCodes.ANS_INSTANT_HANGUP, mb);
        touch(b, "sms", OutcomeCodes.CONVERTED, mb);
        touch(c, "sms", OutcomeCodes.SMS_FAIL_TEMP, mc);
        touch(c, "call", OutcomeCodes.CALL_NO_ANSWER, mc);
        // A control member converts on their own (no attribution: matched by phone).
        touch(d, "sms", OutcomeCodes.CONVERTED, null);

        // A send straight from a snapshot, outside any journey.
        var f = phone(6);
        String target = "msg-direct-" + tenant;
        JourneyFakes.dispatch(
                jdbc,
                clock,
                tenant,
                "sms",
                Attribution.MESSAGE,
                target,
                f.hash(),
                "snap-direct",
                null,
                null);
        touches.record(
                new TouchEvents.Touch(
                        tenant,
                        f.hash(),
                        null,
                        "sms",
                        OutcomeCodes.SMS_DELIVERED,
                        Attribution.MESSAGE,
                        target,
                        attribution.find(tenant, Attribution.MESSAGE, target),
                        null,
                        clock.appNow()));

        String day = LocalDate.ofInstant(start, ZONE).toString();
        admin(
                HttpMethod.POST,
                "/api/v1/marketing/dashboard/refresh",
                Map.of("from", day, "to", day));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value FROM mk_stat_hourly WHERE tenant_id=? AND journey_id=? AND grp='test' AND channel='' AND metric='people:contacted'",
                                Long.class,
                                tenant,
                                jid))
                .isEqualTo(2);

        var all = dashboard("&journeyId=" + jid);
        funnel(all, 3, 2, 1, 1, 2);
        assertThat(all.path("funnel").size()).isEqualTo(5);
        assertThat(all.path("sms_sent").asLong()).isEqualTo(3);
        assertThat(all.path("sms_delivery_rate").decimalValue()).isEqualByComparingTo("0.6667");
        assertThat(all.path("click_rate").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(all.path("calls").asLong()).isEqualTo(3);
        assertThat(all.path("call_connect_rate").decimalValue()).isEqualByComparingTo("0.6667");
        assertThat(all.path("instant_hangup_rate").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(all.path("voice_assistant_rate").decimalValue()).isEqualByComparingTo("0");
        assertThat(all.path("conversation_rate").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(all.path("intent_rate").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(all.path("conversion_rate").decimalValue()).isEqualByComparingTo("0.6667");
        assertThat(all.path("control_selected").asLong()).isEqualTo(2);
        assertThat(all.path("control_conversion_rate").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(all.path("incremental_conversion").decimalValue())
                .isEqualByComparingTo("0.1667");
        // 3 SMS x 0.05 + 3 dialled calls x 0.15
        assertThat(all.path("cost").decimalValue()).isEqualByComparingTo("0.60");
        assertThat(all.path("cost_per_intent").decimalValue()).isEqualByComparingTo("0.60");

        funnel(dashboard("&journeyId=" + jid + "&channel=sms"), 3, 2, 1, 0, 2);
        var call = dashboard("&journeyId=" + jid + "&channel=call");
        funnel(call, 3, 2, 1, 1, 0);
        assertThat(call.path("sms_sent").asLong()).isZero();
        assertThat(call.path("call_answered").asLong()).isEqualTo(2);

        var direct = dashboard("&snapshotId=snap-direct");
        funnel(direct, 1, 1, 0, 0, 0);
        assertThat(direct.path("sms_delivery_rate").decimalValue()).isEqualByComparingTo("1");

        var everything = dashboard("");
        funnel(everything, 4, 3, 1, 1, 2);
        assertThat(everything.path("journeys").size()).isEqualTo(2);
        assertThat(everything.path("daily").size()).isEqualTo(1);

        // Recomputing is idempotent; unit prices are configurable.
        stats.recompute(
                tenant,
                MarketingClock.app(start.minus(Duration.ofHours(2))),
                MarketingClock.app(start.plus(Duration.ofHours(3))));
        admin(
                HttpMethod.PUT,
                "/api/v1/marketing/policy",
                Map.of("sms_unit_price", "0.1", "call_unit_price", "1"));
        var again = dashboard("&journeyId=" + jid);
        funnel(again, 3, 2, 1, 1, 2);
        assertThat(again.path("cost").decimalValue()).isEqualByComparingTo("3.30");
        assertThat(again.path("cost_per_intent").decimalValue()).isEqualByComparingTo("3.30");
        assertThat(dashboard("&journeyId=nope").path("selected").asLong()).isZero();
        assertThat(dashboard("&journeyId=nope").path("conversion_rate").isNull()).isTrue();
    }
}
