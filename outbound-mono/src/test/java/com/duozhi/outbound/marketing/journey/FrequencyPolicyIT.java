package com.duozhi.outbound.marketing.journey;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.marketing.common.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

class FrequencyPolicyIT extends JourneyTestBase {
    @Autowired
    ContactPolicy policy;
    @Autowired
    FrequencyRules rules;

    private void sent(PhoneIdentity.Phone p, String channel) {
        JourneyFakes.dispatch(
                jdbc,
                clock,
                tenant,
                channel,
                channel.equals("sms") ? Attribution.MESSAGE : Attribution.CALL,
                UUID.randomUUID().toString(),
                p.hash(),
                null,
                null,
                null);
    }

    private ContactPolicy.Admission admit(PhoneIdentity.Phone p, String channel) {
        return policy.admit(tenant, p.e164(), p.hash(), channel);
    }

    private void configure(Map<String, Object> body) {
        admin(HttpMethod.PUT, "/api/v1/marketing/policy", body);
    }

    private Instant at(int dayOffset, int hour, int minute) {
        return LocalDate.now(ZONE)
                .plusDays(1 + dayOffset)
                .atTime(hour, minute)
                .atZone(ZONE)
                .toInstant();
    }

    @Test
    void primaryPolicyDelegatesToSuppressionFirst() {
        assertThat(policy).isInstanceOf(JourneyContactPolicy.class);
        var blocked = phone(1);
        jdbc.update(
                "INSERT INTO phonesuppression(tenant_id,phone,reason,created_at) VALUES(?,?,?,NOW(3))",
                tenant,
                blocked.e164().substring(3),
                "test");
        assertThat(admit(blocked, "call").reason()).isEqualTo("通话黑名单");
        var refused = phone(2);
        touch(refused, OutcomeCodes.CALL, OutcomeCodes.ANS_REFUSE_HARD, null);
        assertThat(admit(refused, "sms").reason()).isEqualTo("历史触达结果禁止再次联系");
        assertThat(admit(phone(3), "sms").allowed()).isTrue();
    }

    @Test
    void dailyCapsPerChannelResetNextDay() {
        configure(
                Map.of(
                        "sms_daily_cap",
                        1,
                        "sms_weekly_cap",
                        5,
                        "call_daily_cap",
                        1,
                        "call_weekly_cap",
                        5,
                        "sms_call_gap_minutes",
                        0));
        var p = phone(10);
        sent(p, "sms");
        var denied = admit(p, "sms");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reason()).isEqualTo("短信已达每日上限（1 次）");
        // Next day at the window start (the harness window starts at 00:00).
        assertThat(rules.check(tenant, p.hash(), "sms").retryAt()).isEqualTo(at(1, 0, 0));
        // Channels are counted separately.
        assertThat(admit(p, "call").allowed()).isTrue();
        sent(p, "call");
        assertThat(admit(p, "call").reason()).isEqualTo("外呼已达每日上限（1 次）");
        // Other people are unaffected.
        assertThat(admit(phone(11), "sms").allowed()).isTrue();

        clock.fix(at(1, 10, 0));
        assertThat(admit(p, "sms").allowed()).isTrue();
        assertThat(admit(p, "call").allowed()).isTrue();
    }

    @Test
    void weeklyCapsUseARollingSevenDays() {
        configure(Map.of("sms_daily_cap", 1, "sms_weekly_cap", 2));
        var p = phone(20);
        sent(p, "sms");
        clock.fix(at(1, 10, 0));
        sent(p, "sms");
        clock.fix(at(2, 10, 0));
        var d = rules.check(tenant, p.hash(), "sms");
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("短信已达每周上限（2 次）");
        assertThat(d.retryAt()).isEqualTo(start.plus(Duration.ofDays(7)));
        clock.fix(at(6, 23, 0));
        assertThat(rules.check(tenant, p.hash(), "sms", clock.now(), false).allowed()).isFalse();
        clock.fix(start.plus(Duration.ofDays(7)).plusSeconds(1));
        assertThat(admit(p, "sms").allowed()).isTrue();
    }

    @Test
    void noCallWithinTwoHoursOfAnSmsUnlessTheLinkWasClicked() {
        var clicked = phone(30);
        var ignored = phone(31);
        sent(clicked, "sms");
        sent(ignored, "sms");
        clock.advance(Duration.ofMinutes(30));
        var d = rules.check(tenant, ignored.hash(), "call");
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("短信发出 120 分钟内不外呼（未点击短链）");
        assertThat(d.retryAt()).isEqualTo(start.plus(Duration.ofMinutes(120)));
        // SMS after SMS is not spaced.
        assertThat(admit(ignored, "sms").allowed()).isTrue();

        clock.advance(Duration.ofMinutes(10));
        touch(clicked, OutcomeCodes.LINK, OutcomeCodes.LINK_CLICKED, null);
        assertThat(admit(clicked, "call").allowed()).isTrue();
        assertThat(admit(ignored, "call").allowed()).isFalse();

        clock.fix(start.plus(Duration.ofMinutes(121)));
        assertThat(admit(ignored, "call").allowed()).isTrue();

        configure(Map.of("sms_call_gap_minutes", 300));
        assertThat(admit(ignored, "call").reason()).contains("300 分钟");
    }

    @Test
    void contactWindowIsPerTenantAndExplicitByDefault() {
        var p = phone(40);
        // The harness sets MARKETING_CONTACT_WINDOW=00:00-24:00 explicitly: no hour-dependence.
        clock.fix(at(0, 3, 0));
        assertThat(policies.get(tenant).customized()).isFalse();
        assertThat(admit(p, "call").allowed()).isTrue();

        configure(Map.of("window_start", "09:00", "window_end", "20:00"));
        clock.fix(at(0, 8, 59));
        var early = rules.check(tenant, p.hash(), "sms");
        assertThat(early.reason()).isEqualTo("不在可联系时段（09:00-20:00）");
        assertThat(early.retryAt()).isEqualTo(at(0, 9, 0));
        clock.fix(at(0, 9, 0));
        assertThat(admit(p, "sms").allowed()).isTrue();
        clock.fix(at(0, 19, 59));
        assertThat(admit(p, "call").allowed()).isTrue();
        clock.fix(at(0, 20, 0));
        var late = rules.check(tenant, p.hash(), "call");
        assertThat(late.allowed()).isFalse();
        assertThat(late.retryAt()).isEqualTo(at(1, 9, 0));

        configure(Map.of("window_start", "08:00", "window_end", "21:00"));
        clock.fix(at(0, 20, 30));
        assertThat(admit(p, "call").allowed()).isTrue();
        // Another tenant keeps its own (default) window.
        assertThat(rules.check(tenant + 100_000, p.hash(), "call").allowed()).isTrue();

        var bad =
                req(
                        admin,
                        HttpMethod.PUT,
                        "/api/v1/marketing/policy",
                        Map.of("window_start", "21:00", "window_end", "08:00"));
        assertThat(bad.getStatusCode().value()).isEqualTo(422);
        assertThat(
                        req(
                                        admin,
                                        HttpMethod.PUT,
                                        "/api/v1/marketing/policy",
                                        Map.of("sms_daily_cap", 5, "sms_weekly_cap", 2))
                                .getBody()
                                .path("detail")
                                .asText())
                .contains("每周上限");
    }

    @Test
    void journeySendsOutsideTheWindowAreDeferredNotDropped() {
        configure(Map.of("window_start", "09:00", "window_end", "20:00"));
        clock.fix(at(0, 21, 0));
        var p = phone(50);
        var def =
                Map.of(
                        "nodes",
                        List.of(
                                node("s1", "sms", "sms", sms("tpl-1"), "next", "end"),
                                node("end", "end")));
        String jid = createJourney("夜间延后", def);
        publish(jid);
        startJourney(jid, snapshot(List.of(member(1, p, "test"))));
        drain();
        assertThat(sender.of(tenant)).isEmpty();
        var row = memberRow(jid, p);
        assertThat(row.get("last_result").toString()).startsWith("延后：不在可联系时段");
        assertThat(FrequencyRules.local(row.get("next_run_at")))
                .isEqualTo(MarketingClock.app(at(1, 9, 0)));
        clock.fix(at(1, 9, 0));
        drain();
        assertThat(sender.of(tenant)).hasSize(1);
        assertThat(memberRow(jid, p)).containsEntry("state", "done");
    }
}
