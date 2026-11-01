package com.duozhi.outbound.marketing;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.marketing.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionTemplate;

@Import(MarketingFoundationIT.Listener.class)
class MarketingFoundationIT extends MarketingTestBase {
    @TestConfiguration
    static class Listener {
        final List<TouchEvents.Recorded> seen = Collections.synchronizedList(new ArrayList<>());

        @EventListener
        void on(TouchEvents.Recorded r) {
            seen.add(r);
        }
    }

    @Autowired
    PhoneIdentity phones;
    @Autowired
    TouchEvents touches;
    @Autowired
    Attribution attribution;
    @Autowired
    ContactPolicy policy;
    @Autowired
    Listener listener;
    @Autowired
    TransactionTemplate tx;

    @Test
    void phoneIdentityMatchesMessagingHashAndNeverKeepsClearText() {
        var p = phones.of(tenant, "138-0013-8000");
        assertThat(p.e164()).isEqualTo("+8613800138000");
        assertThat(p.mask()).isEqualTo("138****8000");
        assertThat(p.hash()).hasSize(64).isEqualTo(phones.hash(tenant, "+8613800138000"));
        assertThat(phones.of(tenant + 1, "13800138000").hash()).isNotEqualTo(p.hash());
        String cipher = phones.encrypt(tenant, p.e164());
        assertThat(cipher).doesNotContain("13800138000");
        assertThat(phones.decrypt(tenant, cipher)).isEqualTo(p.e164());
        assertThat(PhoneIdentity.normalize("12345")).isNull();
    }

    @Test
    void touchFactsAreIdempotentAttributedAndPublishedAfterCommit() {
        var p = phones.of(tenant, "13900139000");
        var d =
                attribution.register(
                        tenant,
                        OutcomeCodes.SMS,
                        Attribution.MESSAGE,
                        "msg-1",
                        p.hash(),
                        null,
                        "snap-1",
                        "jr-1",
                        "jm-1");
        assertThat(
                        attribution
                                .register(
                                        tenant,
                                        OutcomeCodes.SMS,
                                        Attribution.MESSAGE,
                                        "msg-1",
                                        p.hash(),
                                        null,
                                        "snap-2",
                                        null,
                                        null)
                                .snapshotId())
                .isEqualTo("snap-1");
        int before = listener.seen.size();
        var touch =
                new TouchEvents.Touch(
                        tenant,
                        p.hash(),
                        null,
                        OutcomeCodes.SMS,
                        OutcomeCodes.SMS_DELIVERED,
                        Attribution.MESSAGE,
                        "msg-1",
                        d,
                        Map.of("provider", "mock"),
                        null);
        String first = tx.execute(s -> touches.record(touch));
        assertThat(first).isNotNull();
        assertThat(touches.record(touch)).isNull();
        assertThat(listener.seen.size()).isEqualTo(before + 1);
        var rows = touches.history(tenant, p.hash(), 10);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("snapshot_id", "snap-1")
                .containsEntry("journey_id", "jr-1");

        tx.executeWithoutResult(
                s -> {
                    touches.record(
                            new TouchEvents.Touch(
                                    tenant,
                                    p.hash(),
                                    null,
                                    OutcomeCodes.SMS,
                                    OutcomeCodes.SMS_FAIL_TEMP,
                                    Attribution.MESSAGE,
                                    "msg-2",
                                    null,
                                    null,
                                    null));
                    s.setRollbackOnly();
                });
        assertThat(listener.seen.size()).isEqualTo(before + 1);
        assertThatThrownBy(
                        () ->
                                touches.record(
                                        new TouchEvents.Touch(
                                                tenant, p.hash(), null, "sms", "MADE_UP", "x", "y",
                                                null, null, null)))
                .hasMessageContaining("未知触达结果码");
    }

    @Test
    void admissionHonoursEverySuppressionSource() {
        var clean = phones.of(tenant, "13700137000");
        assertThat(policy.admit(tenant, clean.e164(), clean.hash(), OutcomeCodes.CALL).allowed())
                .isTrue();

        var blocked = phones.of(tenant, "13600136000");
        jdbc.update(
                "INSERT INTO phonesuppression(tenant_id,phone,reason,created_at) VALUES(?,?,?,NOW(3))",
                tenant,
                "13600136000",
                "test");
        assertThat(policy.admit(tenant, blocked.e164(), blocked.hash(), "call").reason())
                .isEqualTo("通话黑名单");

        var unsubscribed = phones.of(tenant, "13500135000");
        jdbc.update(
                "INSERT INTO msg_suppression(tenant_id,recipient_hash,reason) VALUES(?,?,?)",
                tenant,
                unsubscribed.hash(),
                "TD");
        assertThat(policy.admit(tenant, unsubscribed.e164(), unsubscribed.hash(), "sms").reason())
                .isEqualTo("已退订");

        var refused = phones.of(tenant, "13400134000");
        touches.record(
                new TouchEvents.Touch(
                        tenant,
                        refused.hash(),
                        null,
                        OutcomeCodes.CALL,
                        OutcomeCodes.INTENT_D,
                        Attribution.CALL,
                        "call-9",
                        null,
                        null,
                        null));
        assertThat(policy.admit(tenant, refused.e164(), refused.hash(), "sms").allowed()).isFalse();
    }

    @Test
    void marketingPagesAreOperatorOnly() {
        assertThat(
                        req(
                                        agent,
                                        org.springframework.http.HttpMethod.GET,
                                        "/api/v1/marketing/ping",
                                        null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        assertThat(
                        admin(
                                        org.springframework.http.HttpMethod.GET,
                                        "/api/v1/marketing/ping",
                                        null)
                                .path("codes")
                                .size())
                .isEqualTo(OutcomeCodes.ALL.size());
    }
}
