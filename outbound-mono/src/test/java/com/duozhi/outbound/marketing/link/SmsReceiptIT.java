package com.duozhi.outbound.marketing.link;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.marketing.common.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

class SmsReceiptIT extends LinkTestSupport {
    @Autowired
    SmsReceipts receipts;
    @Autowired
    Attribution attribution;

    String sent(String phone, String state) {
        String id = message(phone, state, now().minusMinutes(5), null);
        attribution.register(
                tenant,
                OutcomeCodes.SMS,
                Attribution.MESSAGE,
                id,
                phones.of(tenant, phone).hash(),
                null,
                "snap-r",
                null,
                null);
        return id;
    }

    void drain() {
        while (receipts.scanOnce(Duration.ZERO, 1000) > 0) {}
    }

    List<String> codes(String messageId) {
        return jdbc.queryForList(
                "SELECT code FROM mk_touch_event WHERE tenant_id=? AND source_id=? ORDER BY sequence",
                String.class,
                tenant,
                messageId);
    }

    @Test
    void deliveryStatesMapThroughTheConfigurableTableForMarketingMessagesOnly() {
        String delivered = sent("13900139001", "DELIVERED");
        event(delivered, "message.queued", "{\"status\":\"QUEUED\"}");
        event(delivered, "message.submitting", "{\"status\":\"SUBMITTING\"}");
        event(delivered, "message.delivered", "{\"status\":\"DELIVERED\"}");
        String invalid = sent("13900139002", "REJECTED");
        event(
                invalid,
                "message.rejected",
                "{\"status\":\"REJECTED\",\"provider_code\":\"MK:0001\"}");
        String blocked = sent("13900139003", "REJECTED");
        event(blocked, "message.rejected", "{\"status\":\"REJECTED\",\"provider_code\":\"BLACK\"}");
        String temp = sent("13900139004", "REJECTED");
        event(temp, "message.rejected", "{\"status\":\"REJECTED\",\"provider_code\":\"UNDELIV\"}");
        String plain = sent("13900139005", "REJECTED");
        event(plain, "message.rejected", "{\"status\":\"REJECTED\"}");
        String foreign = message("13900139006", "DELIVERED", now().minusMinutes(5), null);
        event(foreign, "message.delivered", "{\"status\":\"DELIVERED\"}");

        admin(
                HttpMethod.PUT,
                "/api/v1/marketing/links/status-map",
                Map.of("provider", "mock", "status_code", "X9", "outcome", "SMS_FAIL_BLOCKED"));
        assertThat(
                        req(
                                        admin,
                                        HttpMethod.PUT,
                                        "/api/v1/marketing/links/status-map",
                                        Map.of("status_code", "X", "outcome", "CALL_BUSY"))
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        String custom = sent("13900139007", "REJECTED");
        event(custom, "message.rejected", "{\"status\":\"REJECTED\",\"provider_code\":\"X9\"}");
        String unsubscribed = sent("13900139008", "DELIVERED");
        event(
                unsubscribed,
                "message.interaction",
                "{\"interaction_id\":\"int-"
                        + tenant
                        + "\",\"kind\":\"unsubscribe\",\"source\":\"sms_reply\"}");
        event(
                unsubscribed,
                "message.interaction",
                "{\"interaction_id\":\"int2-"
                        + tenant
                        + "\",\"kind\":\"reply\",\"source\":\"sms_reply\"}");

        drain();

        assertThat(codes(delivered)).containsExactly("SMS_SUBMITTED", "SMS_DELIVERED");
        assertThat(codes(invalid)).containsExactly("SMS_FAIL_INVALID");
        assertThat(codes(blocked)).containsExactly("SMS_FAIL_BLOCKED");
        assertThat(codes(temp)).containsExactly("SMS_FAIL_TEMP");
        assertThat(codes(plain)).containsExactly("SMS_FAIL_TEMP");
        assertThat(codes(custom)).containsExactly("SMS_FAIL_BLOCKED");
        assertThat(codes(foreign)).isEmpty();
        assertThat(codes("int-" + tenant)).containsExactly("SMS_REPLY_TD");
        assertThat(codes("int2-" + tenant)).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT snapshot_id FROM mk_touch_event WHERE tenant_id=? AND code='SMS_REPLY_TD'",
                                String.class,
                                tenant))
                .isEqualTo("snap-r");

        // Re-reading the same events (e.g. after a cursor reset) stores nothing twice.
        jdbc.update("UPDATE mk_link_cursor SET position=0 WHERE name='sms_receipts'");
        drain();
        assertThat(codes(delivered)).containsExactly("SMS_SUBMITTED", "SMS_DELIVERED");

        // Events younger than the settle window wait for the next run.
        String late = sent("13900139009", "DELIVERED");
        event(late, "message.delivered", "{\"status\":\"DELIVERED\"}");
        receipts.scanOnce(Duration.ofMinutes(10), 1000);
        assertThat(codes(late)).isEmpty();
        // msg_event timestamps come from MySQL; a small host/container clock skew can put
        // a freshly inserted event just beyond the application's zero-settle cutoff.
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(
                        () -> {
                            drain();
                            assertThat(codes(late)).containsExactly("SMS_DELIVERED");
                        });
    }

    @Test
    void liveMultipartFailureReachesMarketingAndAcceptanceIsNotDelivery() {
        String failed = sent("13900139021", "FAILED");
        String accepted = sent("13900139022", "ACCEPTED");
        for (String id : List.of(failed, accepted))
            jdbc.update("UPDATE msg_message SET actual_transport='austin_smsgate' WHERE id=?", id);
        event(failed, "message.failed", "{\"status\":\"FAILED\"}");
        event(accepted, "message.accepted", "{\"status\":\"ACCEPTED\"}");
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(
                        () -> {
                            drain();
                            assertThat(codes(failed)).containsExactly("SMS_FAIL_TEMP");
                        });
        assertThat(codes(accepted)).isEmpty();
        receipts.markUnknown(1000);
        assertThat(codes(accepted)).isEmpty();
        jdbc.update(
                "UPDATE msg_message SET submitted_at=? WHERE id=?", now().minusHours(73), accepted);
        receipts.markUnknown(1000);
        assertThat(codes(accepted)).containsExactly("SMS_UNKNOWN");
        jdbc.update("UPDATE mk_link_cursor SET position=0 WHERE name='sms_receipts'");
        drain();
        assertThat(codes(failed)).containsExactly("SMS_FAIL_TEMP");
    }

    @Test
    void messagesWithoutAFinalReportAfterSeventyTwoHoursBecomeUnknown() {
        String stale = message("13900139011", "UNKNOWN", now().minusHours(73), null);
        String recent = message("13900139012", "UNKNOWN", now().minusHours(10), null);
        String stuck = message("13900139013", "SUBMITTING", now().minusHours(80), null);
        String foreign = message("13900139014", "UNKNOWN", now().minusHours(90), null);
        for (String id : List.of(stale, recent, stuck))
            attribution.register(
                    tenant,
                    OutcomeCodes.SMS,
                    Attribution.MESSAGE,
                    id,
                    jdbc.queryForObject(
                            "SELECT recipient_hash FROM msg_message WHERE id=?", String.class, id),
                    null,
                    null,
                    null,
                    null);
        receipts.markUnknown(1000);
        assertThat(codes(stale)).containsExactly("SMS_UNKNOWN");
        assertThat(codes(stuck)).containsExactly("SMS_UNKNOWN");
        assertThat(codes(recent)).isEmpty();
        assertThat(codes(foreign)).isEmpty();
        assertThat(receipts.markUnknown(1000)).isZero();
    }
}
