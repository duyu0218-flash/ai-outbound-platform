package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.messaging.dao.MessageStore;
import java.util.*;
import org.springframework.stereotype.Service;

/** Durable Austin-to-CMPP dispatch boundary. Duplicate Austin jobs never resend a handset SMS. */
@Service
public class SmsGateGateway {
    private final MessageStore db;
    private final Transactions tx;
    private final Messaging messages;
    private final SmsGateTransport transport;
    private final Config config;

    public SmsGateGateway(
            MessageStore db,
            Transactions tx,
            Messaging messages,
            SmsGateTransport transport,
            Config config) {
        this.db = db;
        this.tx = tx;
        this.messages = messages;
        this.transport = transport;
        this.config = config;
        transport.onReport(this::report);
    }

    public Map<String, Object> dispatch(String id) {
        if (!config.flag("SMS_AUSTIN_ENABLED", false) || !config.flag("SMS_SMSGATE_ENABLED", false))
            throw new ApiError(503, "短信链路未启用");
        var message = db.one("SELECT tenant_id FROM msg_message WHERE id=?", id);
        if (message == null) throw new ApiError(404, "消息不存在");
        long tenant = ((Number) message.get("tenant_id")).longValue();
        var submission =
                tx.run(
                        () -> {
                            db.one("SELECT id FROM tenant WHERE id=? FOR UPDATE", tenant);
                            if (db.one(
                                            "SELECT message_id FROM msg_sms_dispatch WHERE message_id=?",
                                            id)
                                    != null) return null;
                            var original = messages.gatewaySubmission(tenant, id);
                            db.jdbc()
                                    .update(
                                            "INSERT INTO msg_sms_dispatch(message_id,tenant_id) VALUES(?,?)",
                                            id,
                                            tenant);
                            return original;
                        });
        if (submission != null) {
            List<SmsGateTransport.Part> parts;
            try {
                parts = transport.submit(submission.recipient(), submission.content());
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                parts = List.of(new SmsGateTransport.Part("", "UNKNOWN"));
            }
            if (parts == null || parts.isEmpty())
                parts = List.of(new SmsGateTransport.Part("", "UNKNOWN"));
            var outcome = List.copyOf(parts);
            tx.run(
                    () -> {
                        db.one("SELECT id FROM tenant WHERE id=? FOR UPDATE", tenant);
                        for (int i = 0; i < outcome.size(); i++) {
                            var part = outcome.get(i);
                            db.jdbc()
                                    .update(
                                            "INSERT INTO msg_sms_part(message_id,part_no,provider_id,state) VALUES(?,?,?,?)",
                                            id,
                                            i + 1,
                                            part.providerId().isBlank() ? null : part.providerId(),
                                            part.state());
                        }
                        db.jdbc()
                                .update(
                                        "UPDATE msg_sms_dispatch SET part_count=?,state='ACCEPTED' WHERE message_id=?",
                                        outcome.size(),
                                        id);
                        return null;
                    });
            reconcile(tenant, id);
        }
        return db.one(
                "SELECT message_id,state,part_count FROM msg_sms_dispatch WHERE message_id=?", id);
    }

    public void report(SmsGateTransport.Part report) {
        if (report.providerId() == null
                || report.providerId().isBlank()
                || report.providerId().length() > 128
                || !Set.of("DELIVERED", "FAILED", "UNKNOWN").contains(report.state()))
            throw new ApiError(422, "CMPP 状态报告无效");
        db.jdbc()
                .update(
                        "INSERT INTO msg_sms_report(provider_id,state) VALUES(?,?) ON DUPLICATE KEY UPDATE state=IF(state='UNKNOWN',VALUES(state),state)",
                        report.providerId(),
                        report.state());
        var row =
                db.one(
                        "SELECT d.tenant_id,d.message_id FROM msg_sms_part p JOIN msg_sms_dispatch d ON d.message_id=p.message_id WHERE p.provider_id=?",
                        report.providerId());
        if (row != null)
            reconcile(
                    ((Number) row.get("tenant_id")).longValue(), row.get("message_id").toString());
    }

    private void reconcile(long tenant, String id) {
        tx.run(
                () -> {
                    db.one("SELECT id FROM tenant WHERE id=? FOR UPDATE", tenant);
                    var states =
                            db.jdbc()
                                    .queryForList(
                                            "SELECT COALESCE(r.state,p.state) AS state FROM msg_sms_part p LEFT JOIN msg_sms_report r ON r.provider_id=p.provider_id WHERE p.message_id=? ORDER BY p.part_no",
                                            String.class,
                                            id);
                    if (states.isEmpty()) return null;
                    String state =
                            states.stream().allMatch("DELIVERED"::equals)
                                    ? "DELIVERED"
                                    : states.stream().allMatch("REJECTED"::equals)
                                            ? "REJECTED"
                                            : states.stream()
                                                            .allMatch(
                                                                    s ->
                                                                            Set.of(
                                                                                            "DELIVERED",
                                                                                            "FAILED",
                                                                                            "REJECTED")
                                                                                    .contains(s))
                                                    ? "FAILED"
                                                    : states.stream().allMatch("ACCEPTED"::equals)
                                                            ? "ACCEPTED"
                                                            : "UNKNOWN";
                    db.jdbc()
                            .update(
                                    "UPDATE msg_sms_dispatch SET state=? WHERE message_id=?",
                                    state,
                                    id);
                    if (!"ACCEPTED".equals(state))
                        messages.providerReceipt(tenant, id, id + ":" + state, state);
                    return null;
                });
    }

    public List<Map<String, Object>> parts(long tenant, String id) {
        messages.message(new Messaging.Principal(tenant, "console"), id);
        return db.jdbc()
                .queryForList(
                        "SELECT p.part_no,p.provider_id,p.state AS submit_state,r.state AS delivery_state FROM msg_sms_part p JOIN msg_sms_dispatch d ON d.message_id=p.message_id LEFT JOIN msg_sms_report r ON r.provider_id=p.provider_id WHERE d.tenant_id=? AND d.message_id=? ORDER BY p.part_no",
                        tenant,
                        id);
    }
}
