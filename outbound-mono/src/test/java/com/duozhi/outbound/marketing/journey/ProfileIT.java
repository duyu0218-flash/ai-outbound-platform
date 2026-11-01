package com.duozhi.outbound.marketing.journey;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.marketing.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.support.TransactionTemplate;

class ProfileIT extends JourneyTestBase {
    @Autowired
    ProfileService profiles;
    @Autowired
    TransactionTemplate tx;

    private String record(PhoneIdentity.Phone p, String channel, String code, String source) {
        return touches.record(
                new TouchEvents.Touch(
                        tenant,
                        p.hash(),
                        null,
                        channel,
                        code,
                        channel.equals("call") ? Attribution.CALL : Attribution.MESSAGE,
                        source,
                        null,
                        Map.of(),
                        clock.appNow()));
    }

    private Map<String, Object> profile(PhoneIdentity.Phone p) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM mk_profile WHERE tenant_id=? AND phone_hash=?",
                        tenant,
                        p.hash());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<String> stages(PhoneIdentity.Phone p) {
        return jdbc.queryForList(
                "SELECT to_stage FROM mk_profile_history WHERE tenant_id=? AND phone_hash=? ORDER BY id",
                String.class,
                tenant,
                p.hash());
    }

    @Test
    void countersAndStageTransitionsFollowTouchFacts() {
        var p = phone(1);
        record(p, "sms", OutcomeCodes.SMS_SUBMITTED, "msg-1");
        assertThat(profile(p)).containsEntry("sms_count", 1).containsEntry("stage", StageRules.NEW);
        record(p, "sms", OutcomeCodes.SMS_DELIVERED, "msg-1");
        assertThat(profile(p))
                .containsEntry("sms_count", 1)
                .containsEntry("sms_delivered", 1)
                .containsEntry("stage", StageRules.REACHED);
        record(p, "link", OutcomeCodes.LINK_CLICKED, "link-1");
        assertThat(profile(p))
                .containsEntry("link_clicks", 1)
                .containsEntry("stage", StageRules.ACTIVE);
        record(p, "call", OutcomeCodes.CALL_ANSWERED, "call-1");
        record(p, "call", OutcomeCodes.ANS_VOICE_ASSISTANT, "call-1");
        record(p, "call", OutcomeCodes.CALL_NO_ANSWER, "call-2");
        record(p, "call", OutcomeCodes.CALL_ANSWERED, "call-3");
        record(p, "call", OutcomeCodes.ANS_INSTANT_HANGUP, "call-3");
        record(p, "call", OutcomeCodes.INTENT_C, "call-3");
        record(p, "call", OutcomeCodes.INTENT_B, "call-4");
        assertThat(profile(p)).containsEntry("stage", StageRules.OPPORTUNITY);
        record(p, "call", OutcomeCodes.INTENT_A, "call-5");
        record(p, "call", OutcomeCodes.INTENT_C, "call-6");
        record(p, "sms", OutcomeCodes.CONVERTED, "order-1");
        var row = profile(p);
        assertThat(row)
                .containsEntry("call_count", 6)
                .containsEntry("call_connected", 2)
                .containsEntry("instant_hangups", 1)
                .containsEntry("voice_assistant", true)
                .containsEntry("best_intent", OutcomeCodes.INTENT_A)
                .containsEntry("converted", true)
                .containsEntry("stage", StageRules.CONVERTED)
                .containsEntry("last_channel", "sms");
        assertThat(row.get("last_touch_at")).isNotNull();
        // The conversion report is not an SMS send.
        assertThat(row).containsEntry("sms_count", 1);
        assertThat(stages(p))
                .containsExactly(
                        StageRules.REACHED,
                        StageRules.ACTIVE,
                        StageRules.OPPORTUNITY,
                        StageRules.CONVERTED);

        var silent = phone(2);
        for (int i = 1; i <= 3; i++) record(silent, "sms", OutcomeCodes.SMS_DELIVERED, "s-" + i);
        assertThat(stages(silent)).containsExactly(StageRules.REACHED, StageRules.SILENT);
        var refused = phone(3);
        record(refused, "call", OutcomeCodes.CALL_ANSWERED, "r-1");
        record(refused, "call", OutcomeCodes.INTENT_D, "r-1");
        assertThat(profile(refused)).containsEntry("stage", StageRules.UNREACHABLE);
        var warm = phone(4);
        record(warm, "call", OutcomeCodes.INTENT_C, "w-1");
        assertThat(profile(warm)).containsEntry("stage", StageRules.POTENTIAL);
    }

    @Test
    void stageRulesAreConfigurablePerTenant() {
        admin(
                HttpMethod.PUT,
                "/api/v1/marketing/policy",
                Map.of(
                        "stage_rules",
                        List.of(
                                Map.of(
                                        "stage",
                                        StageRules.OPPORTUNITY,
                                        "anyCodes",
                                        List.of("INTENT_C")),
                                Map.of(
                                        "stage",
                                        StageRules.SILENT,
                                        "minTouches",
                                        1,
                                        "noneCodes",
                                        List.of("LINK_CLICKED")))));
        var p = phone(10);
        record(p, "call", OutcomeCodes.INTENT_C, "c-1");
        assertThat(profile(p)).containsEntry("stage", StageRules.OPPORTUNITY);
        var q = phone(11);
        record(q, "sms", OutcomeCodes.SMS_DELIVERED, "m-1");
        assertThat(profile(q)).containsEntry("stage", StageRules.SILENT);
        var bad =
                req(
                        admin,
                        HttpMethod.PUT,
                        "/api/v1/marketing/policy",
                        Map.of("stage_rules", List.of(Map.of("stage", "不存在", "minTouches", 1))));
        assertThat(bad.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void duplicatesRollbacksAndCatchUpNeverDoubleCount() {
        var p = phone(20);
        assertThat(record(p, "sms", OutcomeCodes.SMS_DELIVERED, "dup-1")).isNotNull();
        assertThat(record(p, "sms", OutcomeCodes.SMS_DELIVERED, "dup-1")).isNull();
        assertThat(profile(p)).containsEntry("sms_delivered", 1);

        var rolled = phone(21);
        tx.executeWithoutResult(
                s -> {
                    record(rolled, "sms", OutcomeCodes.SMS_DELIVERED, "rb-1");
                    s.setRollbackOnly();
                });
        assertThat(profile(rolled)).isNull();
        profiles.catchUp(500);
        assertThat(profile(rolled)).isNull();

        // A fact stored without the listener firing (e.g. the process died right after commit).
        var missed = phone(22);
        jdbc.update(
                "INSERT INTO mk_touch_event(id,tenant_id,phone_hash,channel,code,source_type,source_id,detail,occurred_at) VALUES(?,?,?,?,?,?,?,'{}',?)",
                MarketingStore.id("tev"),
                tenant,
                missed.hash(),
                "sms",
                OutcomeCodes.SMS_DELIVERED,
                Attribution.MESSAGE,
                "missed-1",
                clock.appNow());
        long sequence =
                jdbc.queryForObject(
                        "SELECT sequence FROM mk_touch_event WHERE tenant_id=? AND source_id='missed-1'",
                        Long.class,
                        tenant);
        assertThat(profile(missed)).isNull();
        var first = profiles.catchUp(500);
        assertThat(first.applied()).isGreaterThanOrEqualTo(1);
        assertThat(profile(missed))
                .containsEntry("sms_delivered", 1)
                .containsEntry("sms_count", 1)
                .containsEntry("stage", StageRules.REACHED);
        // Fresh facts are re-read next time: the watermark stays below unsettled sequences.
        assertThat(first.watermark()).isLessThan(sequence);
        profiles.catchUp(500);
        var event =
                jdbc.queryForMap(
                        "SELECT sequence,id,tenant_id,phone_hash,contact_id,channel,code,source_type,source_id,occurred_at FROM mk_touch_event WHERE sequence=?",
                        sequence);
        assertThat(profiles.applyInNewTransaction(event)).isFalse();
        assertThat(profile(missed)).containsEntry("sms_delivered", 1);
        assertThat(profile(p)).containsEntry("sms_delivered", 1);
        assertThat(stages(missed)).containsExactly(StageRules.REACHED);
    }

    @Test
    void lookupByPhoneInTheBodyShowsMaskProfileHistoryAndTimeline() {
        var p = phone(30);
        record(p, "sms", OutcomeCodes.SMS_DELIVERED, "lk-1");
        record(p, "link", OutcomeCodes.LINK_CLICKED, "lk-2");
        var found =
                admin(
                        HttpMethod.POST,
                        "/api/v1/marketing/profiles/lookup",
                        Map.of("phone", p.e164().substring(3)));
        assertThat(found.path("mask").asText()).isEqualTo(p.mask());
        assertThat(found.path("exists").asBoolean()).isTrue();
        assertThat(found.path("profile").path("stage").asText()).isEqualTo(StageRules.ACTIVE);
        assertThat(found.path("history").size()).isEqualTo(2);
        assertThat(found.path("timeline").size()).isEqualTo(2);
        assertThat(found.toString()).doesNotContain(p.e164().substring(3)).doesNotContain(p.hash());

        var unknown =
                admin(
                        HttpMethod.POST,
                        "/api/v1/marketing/profiles/lookup",
                        Map.of("phone", "13700001111"));
        assertThat(unknown.path("exists").asBoolean()).isFalse();
        assertThat(
                        req(
                                        admin,
                                        HttpMethod.POST,
                                        "/api/v1/marketing/profiles/lookup",
                                        Map.of("phone", "12"))
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        assertThat(
                        req(
                                        agent,
                                        HttpMethod.POST,
                                        "/api/v1/marketing/profiles/lookup",
                                        Map.of("phone", "13700001111"))
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        var overview = admin(HttpMethod.GET, "/api/v1/marketing/profiles/overview", null);
        assertThat(overview.path("stages").path(StageRules.ACTIVE).asInt()).isEqualTo(1);
    }
}
