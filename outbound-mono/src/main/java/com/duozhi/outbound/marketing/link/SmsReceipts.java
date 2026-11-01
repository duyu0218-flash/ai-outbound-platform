package com.duozhi.outbound.marketing.link;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.Attribution;
import com.duozhi.outbound.marketing.common.MarketingStore;
import com.duozhi.outbound.marketing.common.OutcomeCodes;
import com.duozhi.outbound.marketing.common.TouchEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns messaging facts into touch events without touching the messaging module: a watermark scan
 * over msg_event (cursor mk_link_cursor 'sms_receipts') keeps only messages that marketing sent (an
 * mk_dispatch row exists) and maps their states through mk_sms_status_map. Messages with no final
 * report 72 hours after submission become SMS_UNKNOWN.
 */
@Service
public class SmsReceipts {
    private static final Logger log = LoggerFactory.getLogger(SmsReceipts.class);
    static final String CURSOR = "sms_receipts";
    static final Set<String> FINAL =
            Set.of(
                    OutcomeCodes.SMS_DELIVERED,
                    OutcomeCodes.SMS_FAIL_INVALID,
                    OutcomeCodes.SMS_FAIL_BLOCKED,
                    OutcomeCodes.SMS_FAIL_TEMP,
                    OutcomeCodes.SMS_SUBMIT_FAIL,
                    OutcomeCodes.SMS_UNKNOWN);

    private final MarketingStore db;
    private final TouchEvents touches;
    private final Attribution attribution;
    private final TransactionTemplate tx;
    private final JsonSupport json;
    private final Config config;

    public SmsReceipts(
            MarketingStore db,
            TouchEvents touches,
            Attribution attribution,
            TransactionTemplate tx,
            JsonSupport json,
            Config config) {
        this.db = db;
        this.touches = touches;
        this.attribution = attribution;
        this.tx = tx;
        this.json = json;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${MARKETING_SMS_RECEIPT_MS:5000}")
    public void tick() {
        if (!config.flag("MARKETING_WORKERS_ENABLED", false)) return;
        try {
            Duration settle =
                    Duration.ofSeconds(config.number("MARKETING_SMS_RECEIPT_SETTLE_SECONDS", 5));
            while (scanOnce(settle, 1000) == 1000) {}
            markUnknown(500);
        } catch (RuntimeException e) {
            log.warn("sms receipt bridge failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * One watermark step. Events younger than {@code settle} are left for the next run so a
     * transaction that commits late cannot be skipped by the advancing watermark.
     *
     * @return events consumed (the watermark moved past them)
     */
    public int scanOnce(Duration settle, int limit) {
        Integer consumed =
                tx.execute(
                        s -> {
                            long from =
                                    ((Number)
                                                    db.one(
                                                                    "SELECT position FROM mk_link_cursor WHERE name=? FOR UPDATE",
                                                                    CURSOR)
                                                            .get("position"))
                                            .longValue();
                            // Cross-tenant scan: the bridge follows every tenant's message events.
                            var events =
                                    db.rows(
                                            "SELECT e.sequence,e.tenant_id,e.message_id,e.type,e.payload,e.created_at,d.id AS dispatch_id FROM msg_event e LEFT JOIN mk_dispatch d ON d.tenant_id=e.tenant_id AND d.target_type='msg_message' AND d.target_id=e.message_id WHERE e.sequence>? ORDER BY e.sequence LIMIT ?",
                                            from,
                                            Math.max(1, limit));
                            Instant cutoff = Instant.now().minus(settle);
                            long position = from;
                            int n = 0;
                            for (var e : events) {
                                Instant at = Links.instant(e.get("created_at"));
                                if (at.isAfter(cutoff)) break;
                                if (e.get("dispatch_id") != null) apply(e, at);
                                position = ((Number) e.get("sequence")).longValue();
                                n++;
                            }
                            if (position != from)
                                db.write(
                                        "UPDATE mk_link_cursor SET position=? WHERE name=?",
                                        position,
                                        CURSOR);
                            return n;
                        });
        return consumed == null ? 0 : consumed;
    }

    private void apply(Map<String, Object> e, Instant at) {
        long tenant = ((Number) e.get("tenant_id")).longValue();
        String messageId = e.get("message_id").toString(), type = e.get("type").toString();
        Map<String, Object> payload = json.object(String.valueOf(e.get("payload")));
        var dispatch = attribution.find(tenant, Attribution.MESSAGE, messageId);
        if (dispatch == null) return;
        if (type.equals("message.interaction")) {
            if ("unsubscribe".equals(payload.get("kind")) && payload.get("interaction_id") != null)
                record(
                        dispatch,
                        OutcomeCodes.SMS_REPLY_TD,
                        "msg_interaction",
                        payload.get("interaction_id").toString(),
                        Map.of(
                                "message_id",
                                messageId,
                                "source",
                                String.valueOf(payload.get("source"))),
                        at);
            return;
        }
        if (type.equals("message.submitting")) {
            record(
                    dispatch,
                    OutcomeCodes.SMS_SUBMITTED,
                    Attribution.MESSAGE,
                    messageId,
                    Map.of(),
                    at);
            return;
        }
        if (!Set.of("message.delivered", "message.rejected", "message.blocked", "message.expired")
                .contains(type)) return;
        String state = type.substring("message.".length()).toUpperCase(Locale.ROOT);
        var message =
                db.one(
                        "SELECT actual_transport FROM msg_message WHERE tenant_id=? AND id=?",
                        tenant,
                        messageId);
        String provider =
                message == null || message.get("actual_transport") == null
                        ? "*"
                        : message.get("actual_transport").toString();
        Object providerCode = payload.get("provider_code");
        String code =
                providerCode == null || providerCode.toString().isBlank()
                        ? null
                        : outcome(tenant, provider, providerCode.toString());
        if (code == null) code = outcome(tenant, provider, "platform:" + state);
        if (code == null) return;
        var detail = new LinkedHashMap<String, Object>();
        detail.put("state", state);
        detail.put("provider", provider);
        if (providerCode != null) detail.put("provider_code", providerCode.toString());
        record(dispatch, code, Attribution.MESSAGE, messageId, detail, at);
    }

    /** Most specific mapping wins: tenant+provider, tenant, platform+provider, platform. */
    String outcome(long tenant, String provider, String statusCode) {
        var r =
                db.one(
                        "SELECT outcome FROM mk_sms_status_map WHERE tenant_id IN (0,?) AND provider IN ('*',?) AND status_code=? ORDER BY tenant_id DESC,provider='*' LIMIT 1",
                        tenant,
                        provider,
                        statusCode);
        return r == null ? null : r.get("outcome").toString();
    }

    /** Marketing messages submitted more than 72 hours ago with no final report. */
    public int markUnknown(int limit) {
        int hours = config.number("MARKETING_SMS_UNKNOWN_HOURS", 72);
        // Cross-tenant scan over open messages (msg_due index on state), marketing-sent only.
        var open =
                db.rows(
                        "SELECT m.tenant_id,m.id,m.state,m.submitted_at FROM msg_message m JOIN mk_dispatch d ON d.tenant_id=m.tenant_id AND d.target_type='msg_message' AND d.target_id=m.id WHERE m.state IN ('SUBMITTING','UNKNOWN') AND m.submitted_at<CURRENT_TIMESTAMP(6)-INTERVAL ? HOUR AND NOT EXISTS (SELECT 1 FROM mk_touch_event t WHERE t.tenant_id=m.tenant_id AND t.source_type='msg_message' AND t.source_id=m.id AND t.code IN ('"
                                + String.join("','", FINAL.stream().sorted().toList())
                                + "')) LIMIT ?",
                        hours,
                        Math.max(1, limit));
        int n = 0;
        for (var m : open) {
            long tenant = ((Number) m.get("tenant_id")).longValue();
            String id = m.get("id").toString();
            var dispatch = attribution.find(tenant, Attribution.MESSAGE, id);
            if (dispatch == null) continue;
            if (record(
                            dispatch,
                            OutcomeCodes.SMS_UNKNOWN,
                            Attribution.MESSAGE,
                            id,
                            Map.of("state", m.get("state").toString(), "after_hours", hours),
                            Instant.now())
                    != null) n++;
        }
        return n;
    }

    private String record(
            Attribution.Dispatch dispatch,
            String code,
            String sourceType,
            String sourceId,
            Map<String, Object> detail,
            Instant at) {
        return touches.record(
                new TouchEvents.Touch(
                        dispatch.tenant(),
                        dispatch.phoneHash(),
                        dispatch.contactId(),
                        OutcomeCodes.SMS,
                        code,
                        sourceType,
                        sourceId,
                        dispatch,
                        detail,
                        Links.utc(at)));
    }
}
