package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.dao.MessageStore;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Durable external-submit boundary and authenticated provider inbox. Never resubmits unknown work.
 */
@Service
public class MessageDelivery {
    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.common.config.Config config;

    private final MessageStore db;
    private final Transactions tx;
    private final MessageSecrets secrets;
    private final MessageChannels channels;
    private final ObjectProvider<Messaging> messages;
    private final Map<String, ChannelProtocol> protocols;

    public MessageDelivery(
            MessageStore db,
            Transactions tx,
            MessageSecrets secrets,
            MessageChannels channels,
            ObjectProvider<Messaging> messages,
            List<ChannelProtocol> protocols) {
        this.db = db;
        this.tx = tx;
        this.secrets = secrets;
        this.channels = channels;
        this.messages = messages;
        var map = new HashMap<String, ChannelProtocol>();
        for (var p : protocols)
            if (map.put(p.protocol(), p) != null)
                throw new IllegalStateException("duplicate channel protocol");
        this.protocols = Map.copyOf(map);
    }

    public Object probe(long tenant, String channelId) {
        var channel = channels.internal(tenant, channelId);
        try {
            protocols.get(channel.get("protocol")).probe(channel, channels.credentials(channel));
            channels.connectionVerified(tenant, channelId);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ApiError(502, "通道连接或接口能力验证失败，请核对配置；本次未发送消息");
        }
        return channels.get(tenant, channelId);
    }

    public MessageAdapter.Result submit(long tenant, String id) {
        var request =
                tx.run(
                        () -> {
                            tenantLock(tenant);
                            if (db.one("SELECT message_id FROM msg_delivery WHERE message_id=?", id)
                                    != null) return null;
                            var prepared = messages.getObject().deliverySubmission(tenant, id);
                            String channelId = prepared.options().get("channel_id").toString();
                            channels.usable(tenant, channelId, prepared.form());
                            db.jdbc()
                                    .update(
                                            "INSERT INTO msg_delivery(message_id,tenant_id,channel_id,next_query_at) VALUES(?,?,?,CURRENT_TIMESTAMP(6)+INTERVAL 1 MINUTE)",
                                            id,
                                            tenant,
                                            channelId);
                            return prepared;
                        });
        if (request != null) {
            var channel = channels.internal(tenant, request.options().get("channel_id").toString());
            ChannelProtocol.Result outcome;
            try {
                var content = Messaging.object(request.options().get("compiled_content"));
                var assets = new LinkedHashMap<String, String>();
                for (var e : Messaging.object(request.options().get("asset_refs")).entrySet())
                    assets.put(e.getKey(), e.getValue().toString());
                outcome =
                        protocols
                                .get(channel.get("protocol"))
                                .submit(
                                        channel,
                                        channels.credentials(channel),
                                        new ChannelProtocol.Submission(
                                                id,
                                                request.form(),
                                                request.recipient(),
                                                content,
                                                request.options().get("sender_ref").toString(),
                                                request.options().get("template_ref").toString(),
                                                assets));
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                outcome = ChannelProtocol.Result.unknown();
            }
            persist(tenant, id, channel.get("id").toString(), outcome);
        }
        var result =
                db.one(
                        "SELECT state,provider_id FROM msg_delivery WHERE tenant_id=? AND message_id=?",
                        tenant,
                        id);
        return new MessageAdapter.Result(
                result == null ? "UNKNOWN" : result.get("state").toString(),
                result == null ? "" : Objects.toString(result.get("provider_id"), ""),
                0,
                false);
    }

    private void tenantLock(long tenant) {
        db.one("SELECT id FROM tenant WHERE id=? FOR UPDATE", tenant);
    }

    private void persist(long tenant, String id, String channel, ChannelProtocol.Result outcome) {
        tx.run(
                () -> {
                    tenantLock(tenant);
                    var parts =
                            outcome.parts().isEmpty()
                                    ? List.of(
                                            new ChannelProtocol.Part(
                                                    outcome.providerId(), outcome.state()))
                                    : outcome.parts();
                    if (parts.size() > 100) throw new ApiError(502, "供应商分片数量异常");
                    for (int i = 0; i < parts.size(); i++) {
                        var part = parts.get(i);
                        if (!Set.of("ACCEPTED", "DELIVERED", "REJECTED", "FAILED", "UNKNOWN")
                                        .contains(part.state())
                                || part.providerId().length() > 128)
                            throw new ApiError(502, "供应商状态无效");
                        if (!part.providerId().isBlank()) {
                            var owner =
                                    db.one(
                                            "SELECT message_id FROM msg_delivery_part WHERE channel_id=? AND provider_id=?",
                                            channel,
                                            part.providerId());
                            if (owner != null && !id.equals(owner.get("message_id")))
                                throw new ApiError(409, "供应商编号已用于其他消息");
                        }
                        var existing =
                                db.one(
                                        "SELECT provider_id FROM msg_delivery_part WHERE message_id=? AND part_no=?",
                                        id,
                                        i + 1);
                        if (existing != null
                                && existing.get("provider_id") != null
                                && !part.providerId().isBlank()
                                && !part.providerId().equals(existing.get("provider_id")))
                            throw new ApiError(409, "供应商消息编号发生冲突，需人工核查");
                        db.jdbc()
                                .update(
                                        "INSERT INTO msg_delivery_part(message_id,channel_id,part_no,provider_id,state) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE provider_id=COALESCE(provider_id,VALUES(provider_id)),state=IF(state IN ('DELIVERED','FAILED','REJECTED'),state,VALUES(state))",
                                        id,
                                        channel,
                                        i + 1,
                                        part.providerId().isBlank() ? null : part.providerId(),
                                        part.state());
                    }
                    db.jdbc()
                            .update(
                                    "UPDATE msg_delivery SET provider_id=COALESCE(provider_id,?),part_count=?,updated_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND message_id=?",
                                    outcome.providerId().isBlank() ? null : outcome.providerId(),
                                    parts.size(),
                                    tenant,
                                    id);
                    for (var event :
                            db.jdbc()
                                    .queryForList(
                                            "SELECT event_key FROM msg_provider_event WHERE channel_id=? AND applied=FALSE AND (message_id=? OR provider_id IN (SELECT provider_id FROM msg_delivery_part WHERE message_id=?)) ORDER BY created_at,event_key LIMIT 1000",
                                            channel,
                                            id,
                                            id)) apply(channel, event.get("event_key").toString());
                    reconcile(tenant, id);
                    return null;
                });
    }

    private void reconcile(long tenant, String id) {
        var states =
                db.jdbc()
                        .queryForList(
                                "SELECT state FROM msg_delivery_part WHERE message_id=? ORDER BY part_no",
                                String.class,
                                id);
        if (states.isEmpty()) return;
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
                                        : states.stream()
                                                        .anyMatch(
                                                                s ->
                                                                        Set.of(
                                                                                        "UNKNOWN",
                                                                                        "REJECTED",
                                                                                        "FAILED")
                                                                                .contains(s))
                                                ? "UNKNOWN"
                                                : "ACCEPTED";
        db.jdbc()
                .update(
                        "UPDATE msg_delivery SET state=?,updated_at=CURRENT_TIMESTAMP(6),next_query_at=IF(? IN ('DELIVERED','FAILED','REJECTED'),NULL,next_query_at) WHERE tenant_id=? AND message_id=?",
                        state,
                        state,
                        tenant,
                        id);
        messages.getObject().providerOutcome(tenant, id, state);
    }

    public void carrierReport(String channel, String providerId, String state) {
        ingest(
                channel,
                Map.of(
                        "event_id",
                        "cmpp:" + providerId + ":" + state,
                        "kind",
                        "delivery",
                        "state",
                        state,
                        "provider_id",
                        providerId));
    }

    public void carrierReply(String channel, String eventId, String phone, String text) {
        if (phone == null || text == null) throw new ApiError(422, "上行消息无效");
        String normalized =
                phone.startsWith("+")
                        ? phone
                        : phone.matches("1[0-9]{10}")
                                ? "+86" + phone
                                : phone.matches("861[0-9]{10}") ? "+" + phone : phone;
        ingest(
                channel,
                Map.of(
                        "event_id",
                        "mo:" + eventId,
                        "kind",
                        Set.of("TD", "退订", "T").contains(text.strip().toUpperCase(Locale.ROOT))
                                ? "unsubscribe"
                                : "reply",
                        "state",
                        "RECEIVED",
                        "recipient",
                        normalized,
                        "text",
                        text));
    }

    public Map<String, Object> callbackChannel(String id) {
        var row = db.one("SELECT tenant_id FROM msg_channel WHERE id=?", id);
        if (row == null) throw new ApiError(404, "通道不存在");
        return channels.internal(((Number) row.get("tenant_id")).longValue(), id);
    }

    public Object ingest(String channelId, Map<String, Object> event) {
        var channel = callbackChannel(channelId);
        long tenant = ((Number) channel.get("tenant_id")).longValue();
        String key = Messaging.required(event, "event_id", 128),
                kind = Messaging.required(event, "kind", 24);
        if (!Set.of("delivery", "display", "read", "click", "reply", "unsubscribe", "cost")
                .contains(kind)) throw new ApiError(422, "供应商事件类型无效");
        if (!key.matches("[A-Za-z0-9_.:/-]{1,128}"))
            throw new ApiError(422, "事件编号须为ASCII字母、数字或 . _ : / -");
        var supported = (List<?>) Messaging.object(channel.get("config")).get("supports");
        if (!kind.equals("cost") && !supported.contains(kind))
            throw new ApiError(422, "通道未声明此事件能力");
        if (Support.json(event).length > 16384) throw new ApiError(413, "供应商事件过大");
        String state =
                Objects.toString(
                        event.getOrDefault(
                                "state", kind.equals("delivery") ? "UNKNOWN" : "RECEIVED"));
        if (kind.equals("reply")) Messaging.required(event, "text", 2000);
        if (kind.equals("delivery")
                && !Set.of("ACCEPTED", "DELIVERED", "FAILED", "REJECTED", "UNKNOWN")
                        .contains(state)) throw new ApiError(422, "回执状态无效");
        String message = Objects.toString(event.get("message_id"), null),
                provider = Objects.toString(event.get("provider_id"), null);
        if (message != null && !message.matches("msg_[a-f0-9]{32}")
                || provider != null && (provider.isBlank() || provider.length() > 128))
            throw new ApiError(422, "供应商消息编号无效");
        if (message == null && provider == null && !Set.of("reply", "unsubscribe").contains(kind))
            throw new ApiError(422, "事件缺少关联消息编号");
        String hash = Support.hex(Support.sha(Support.canonical(event)));
        return tx.run(
                () -> {
                    tenantLock(tenant);
                    var prior =
                            db.one(
                                    "SELECT request_hash FROM msg_provider_event WHERE channel_id=? AND event_key=?",
                                    channelId,
                                    key);
                    if (prior != null) {
                        if (!hash.equals(prior.get("request_hash")))
                            throw new ApiError(409, "回执编号冲突");
                        return Map.of("accepted", true, "duplicate", true);
                    }
                    db.jdbc()
                            .update(
                                    "INSERT INTO msg_provider_event(channel_id,event_key,tenant_id,message_id,provider_id,kind,state,payload_cipher,request_hash) VALUES(?,?,?,?,?,?,?,?,?)",
                                    channelId,
                                    key,
                                    tenant,
                                    message,
                                    provider,
                                    kind,
                                    state,
                                    secrets.encrypt(
                                            "provider-event:" + channelId + ":" + key,
                                            Messaging.json(event)),
                                    hash);
                    apply(channelId, key);
                    return Map.of("accepted", true, "duplicate", false);
                });
    }

    private void apply(String channel, String key) {
        var row =
                db.one(
                        "SELECT * FROM msg_provider_event WHERE channel_id=? AND event_key=?",
                        channel,
                        key);
        if (row == null
                || Boolean.TRUE.equals(row.get("applied"))
                || "".equals(row.get("payload_cipher"))) return;
        long tenant = ((Number) row.get("tenant_id")).longValue();
        var event =
                Messaging.object(
                        secrets.decrypt(
                                "provider-event:" + channel + ":" + key,
                                row.get("payload_cipher").toString()));
        String kind = row.get("kind").toString(),
                id = Objects.toString(row.get("message_id"), null);
        if (id == null && row.get("provider_id") != null) {
            var match =
                    db.one(
                            "SELECT message_id FROM msg_delivery_part WHERE channel_id=? AND provider_id=?",
                            channel,
                            row.get("provider_id"));
            if (match != null) id = match.get("message_id").toString();
        }
        if (id == null && Set.of("reply", "unsubscribe").contains(kind)) {
            String recipient = Messaging.required(event, "recipient", 32);
            if (!recipient.matches("\\+[1-9][0-9]{7,14}")) throw new ApiError(422, "上行号码无效");
            if (kind.equals("unsubscribe"))
                db.jdbc()
                        .update(
                                "INSERT IGNORE INTO msg_suppression(tenant_id,recipient_hash,reason) VALUES(?,?,?)",
                                tenant,
                                secrets.hash(tenant, recipient),
                                "供应商上行退订");
            var match =
                    db.one(
                            "SELECT id FROM msg_message WHERE tenant_id=? AND channel_id=? AND recipient_hash=? ORDER BY created_at DESC LIMIT 1",
                            tenant,
                            channel,
                            secrets.hash(tenant, recipient));
            if (match != null) id = match.get("id").toString();
        }
        if (id == null) return;
        var message =
                db.one(
                        "SELECT id FROM msg_message WHERE tenant_id=? AND channel_id=? AND id=?",
                        tenant,
                        channel,
                        id);
        if (message == null) return;
        if (kind.equals("delivery")) {
            String state = row.get("state").toString();
            int changed;
            if (row.get("provider_id") != null)
                changed =
                        db.jdbc()
                                .update(
                                        "UPDATE msg_delivery_part SET state=IF(state IN ('DELIVERED','FAILED','REJECTED'),state,?) WHERE message_id=? AND channel_id=? AND provider_id=?",
                                        state,
                                        id,
                                        channel,
                                        row.get("provider_id"));
            else
                changed =
                        db.jdbc()
                                .update(
                                        "UPDATE msg_delivery_part SET state=IF(state IN ('DELIVERED','FAILED','REJECTED'),state,?) WHERE message_id=? AND channel_id=?",
                                        state,
                                        id,
                                        channel);
            if (changed == 0) return;
            reconcile(tenant, id);
        } else if (kind.equals("cost")) {
            long amount = MessageChannels.integer(event, "amount", 0, 1000000000000L),
                    units = MessageChannels.integer(event, "units", 1, 100000);
            if (units < 1) throw new ApiError(422, "计费单位无效");
            db.jdbc()
                    .update(
                            "INSERT IGNORE INTO msg_cost_event(id,tenant_id,channel_id,message_id,event_key,amount,units,source) VALUES(?,?,?,?,?,?,?,'provider')",
                            Messaging.id("cost"),
                            tenant,
                            channel,
                            id,
                            key,
                            amount,
                            units);
            db.jdbc()
                    .update(
                            "UPDATE msg_message SET purchase_amount=(SELECT COALESCE(SUM(amount),0) FROM msg_cost_event WHERE message_id=?) WHERE tenant_id=? AND id=?",
                            id,
                            tenant,
                            id);
        } else messages.getObject().providerInteraction(tenant, id, key, kind, event);
        db.jdbc()
                .update(
                        "UPDATE msg_provider_event SET applied=TRUE,message_id=? WHERE channel_id=? AND event_key=?",
                        id,
                        channel,
                        key);
    }

    public boolean queryOne() {
        if (!config.flag("MESSAGING_ENABLED", false)
                || !config.flag("MESSAGING_TRANSPORT_ENABLED", false)) return false;
        var candidate =
                db.one(
                        "SELECT tenant_id,message_id,channel_id FROM msg_delivery WHERE state IN ('SUBMITTING','ACCEPTED','UNKNOWN') AND next_query_at<=CURRENT_TIMESTAMP(6) ORDER BY next_query_at LIMIT 1");
        if (candidate == null) return false;
        long tenant = ((Number) candidate.get("tenant_id")).longValue();
        String id = candidate.get("message_id").toString(),
                channelId = candidate.get("channel_id").toString();
        var row =
                tx.run(
                        () -> {
                            tenantLock(tenant);
                            var d =
                                    db.one(
                                            "SELECT * FROM msg_delivery WHERE tenant_id=? AND message_id=? AND state IN ('SUBMITTING','ACCEPTED','UNKNOWN') AND next_query_at<=CURRENT_TIMESTAMP(6) FOR UPDATE",
                                            tenant,
                                            id);
                            if (d == null) return null;
                            var channel = channels.internal(tenant, channelId);
                            int count = ((Number) d.get("query_count")).intValue();
                            if (!Boolean.TRUE.equals(
                                            Messaging.object(channel.get("config"))
                                                    .get("query_supported"))
                                    || count >= 24) {
                                db.jdbc()
                                        .update(
                                                "UPDATE msg_delivery SET next_query_at=NULL,last_error='MANUAL_REVIEW_REQUIRED' WHERE message_id=?",
                                                id);
                                return null;
                            }
                            db.jdbc()
                                    .update(
                                            "UPDATE msg_delivery SET query_count=query_count+1,next_query_at=? WHERE message_id=?",
                                            java.sql.Timestamp.from(
                                                    Instant.now()
                                                            .plusSeconds(
                                                                    Math.min(
                                                                            3600,
                                                                            60L
                                                                                    << Math.min(
                                                                                            count,
                                                                                            6)))),
                                            id);
                            return d;
                        });
        if (row == null) return true;
        var channel = channels.internal(tenant, channelId);
        try {
            var result =
                    protocols
                            .get(channel.get("protocol"))
                            .query(
                                    channel,
                                    channels.credentials(channel),
                                    id,
                                    Objects.toString(row.get("provider_id"), ""));
            if (!result.state().equals("UNKNOWN")) persist(tenant, id, channelId, result);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            db.jdbc()
                    .update(
                            "UPDATE msg_delivery SET last_error='QUERY_UNCONFIRMED' WHERE message_id=?",
                            id);
        }
        return true;
    }

    public Object scheduleQuery(long tenant, String id) {
        return tx.run(
                () -> {
                    tenantLock(tenant);
                    var row =
                            db.one(
                                    "SELECT channel_id,state FROM msg_delivery WHERE tenant_id=? AND message_id=?",
                                    tenant,
                                    id);
                    if (row == null) throw new ApiError(404, "发送记录不存在");
                    var channel = channels.internal(tenant, row.get("channel_id").toString());
                    if (!Boolean.TRUE.equals(
                            Messaging.object(channel.get("config")).get("query_supported")))
                        throw new ApiError(409, "通道不支持消息查询，请通过运营商对账核查");
                    if (Set.of("SUBMITTING", "ACCEPTED", "UNKNOWN").contains(row.get("state")))
                        db.jdbc()
                                .update(
                                        "UPDATE msg_delivery SET query_count=0,next_query_at=CURRENT_TIMESTAMP(6) WHERE message_id=?",
                                        id);
                    return Map.of("message_id", id, "query_scheduled", true);
                });
    }

    public Object detail(long tenant, String id) {
        messages.getObject().message(new Messaging.Principal(tenant, "console"), id);
        var row =
                db.one(
                        "SELECT message_id,channel_id,state,provider_id,part_count,query_count,next_query_at,last_error,created_at,updated_at FROM msg_delivery WHERE tenant_id=? AND message_id=?",
                        tenant,
                        id);
        if (row == null) return Map.of("parts", List.of());
        row.put(
                "parts",
                db.jdbc()
                        .queryForList(
                                "SELECT part_no,provider_id,state FROM msg_delivery_part WHERE message_id=? ORDER BY part_no",
                                id));
        row.put(
                "events",
                db.jdbc()
                        .queryForList(
                                "SELECT event_key,kind,state,applied,created_at FROM msg_provider_event WHERE tenant_id=? AND message_id=? ORDER BY created_at LIMIT 200",
                                tenant,
                                id));
        return row;
    }
}
