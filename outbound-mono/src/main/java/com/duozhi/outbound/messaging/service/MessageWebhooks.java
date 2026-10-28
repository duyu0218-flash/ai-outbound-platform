package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.dao.MessageStore;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MessageWebhooks {
    private final MessageStore db;
    private final Transactions tx;
    private final MessageSecrets secrets;
    private final Config config;
    private final WebhookTransport transport;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("message-webhook").factory());
    private final AtomicBoolean busy = new AtomicBoolean();

    public MessageWebhooks(
            MessageStore db,
            Transactions tx,
            MessageSecrets secrets,
            Config config,
            WebhookTransport transport) {
        this.db = db;
        this.tx = tx;
        this.secrets = secrets;
        this.config = config;
        this.transport = transport;
    }

    public Object create(Messaging.Principal p, String key, Map<String, Object> input) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{8,128}"))
            throw new ApiError(422, "订阅需要幂等键");
        String hash = Support.hex(Support.sha(Support.canonical(input)));
        if (!config.flag("MESSAGING_ENABLED", false)) throw new ApiError(503, "消息模块尚未启用");
        String url = Messaging.required(input, "url", 2048);
        URI uri;
        try {
            uri = URI.create(url);
            PinnedWebhookTransport.validate(uri);
        } catch (Exception e) {
            throw new ApiError(422, "回调地址必须是有效的公网 HTTPS 地址");
        }
        if (!(input.get("event_types") instanceof List<?> types)
                || types.isEmpty()
                || types.size() > 20
                || types.stream()
                        .anyMatch(
                                t ->
                                        !(t instanceof String s)
                                                || !Set.of(
                                                                "message.queued",
                                                                "message.submitting",
                                                                "message.delivered",
                                                                "message.rejected",
                                                                "message.unknown",
                                                                "message.blocked",
                                                                "message.cancelled",
                                                                "message.expired",
                                                                "billing.refunded",
                                                                "message.interaction")
                                                        .contains(s)))
            throw new ApiError(422, "事件类型无效");
        return tx.run(
                () -> {
                    var app =
                            db.one(
                                    "SELECT id FROM msg_application WHERE tenant_id=? AND id=? AND enabled=TRUE FOR UPDATE",
                                    p.tenant(),
                                    p.application());
                    if (app == null) throw new ApiError(403, "应用已停用");
                    var old =
                            db.one(
                                    "SELECT id,request_hash FROM msg_webhook WHERE tenant_id=? AND application_id=? AND idempotency_key=?",
                                    p.tenant(),
                                    p.application(),
                                    key);
                    if (old != null) {
                        if (!hash.equals(old.get("request_hash")))
                            throw new ApiError(409, "订阅幂等键对应不同请求");
                        return get(p, old.get("id").toString());
                    }
                    Long count =
                            db.jdbc()
                                    .queryForObject(
                                            "SELECT COUNT(*) FROM msg_webhook WHERE tenant_id=? AND application_id=? AND state<>'DISABLED'",
                                            Long.class,
                                            p.tenant(),
                                            p.application());
                    if (count >= 5) throw new ApiError(409, "每个应用最多 5 个订阅");
                    String id = Messaging.id("wh"),
                            secret = MessageSecrets.random(),
                            challenge = MessageSecrets.random();
                    Long cursor =
                            db.jdbc()
                                    .queryForObject(
                                            "SELECT COALESCE(MAX(sequence),0) FROM msg_event",
                                            Long.class);
                    db.jdbc()
                            .update(
                                    "INSERT INTO msg_webhook(id,tenant_id,application_id,url,secret_cipher,challenge,event_types,cursor_sequence,idempotency_key,request_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",
                                    id,
                                    p.tenant(),
                                    p.application(),
                                    uri.toASCIIString(),
                                    secrets.encrypt("webhook:" + p.tenant() + ":" + id, secret),
                                    challenge,
                                    Messaging.json(types),
                                    cursor,
                                    key,
                                    hash);
                    return Map.of(
                            "subscription_id",
                            id,
                            "state",
                            "PENDING_VERIFICATION",
                            "secret",
                            secret,
                            "verification",
                            "请将验证请求中的 challenge 值通过 X-Webhook-Challenge 响应头原样返回");
                });
    }

    public Object get(Messaging.Principal p, String id) {
        var row =
                db.one(
                        "SELECT id,url,state,event_types,created_at FROM msg_webhook WHERE tenant_id=? AND application_id=? AND id=?",
                        p.tenant(),
                        p.application(),
                        id);
        if (row == null) throw new ApiError(404, "订阅不存在");
        return Messaging.publicDates(row);
    }

    public Object disable(Messaging.Principal p, String id) {
        get(p, id);
        db.jdbc()
                .update(
                        "UPDATE msg_webhook SET state='DISABLED' WHERE tenant_id=? AND application_id=? AND id=?",
                        p.tenant(),
                        p.application(),
                        id);
        return get(p, id);
    }

    private Map<String, String> headers(
            Map<String, Object> hook, String event, String delivery, byte[] body) {
        String timestamp = "" + Instant.now().getEpochSecond();
        String secret =
                secrets.decrypt(
                        "webhook:" + hook.get("tenant_id") + ":" + hook.get("id"),
                        hook.get("secret_cipher").toString());
        String sign =
                Support.hmac(
                        secret,
                        (timestamp + "\n" + event + "\n" + Support.hex(Support.sha(body)))
                                .getBytes(StandardCharsets.UTF_8));
        return Map.of(
                "X-Timestamp",
                timestamp,
                "X-Event-Id",
                event,
                "X-Delivery-Id",
                delivery,
                "X-Signature",
                "v1=" + sign);
    }

    public boolean processOne() {
        var verifying =
                db.one(
                        "SELECT w.* FROM msg_webhook w JOIN msg_application a ON a.id=w.application_id AND a.enabled=TRUE JOIN tenant t ON t.id=w.tenant_id AND t.enabled=TRUE WHERE w.state='PENDING_VERIFICATION' ORDER BY w.created_at LIMIT 1");
        if (verifying != null) {
            String id = verifying.get("id").toString();
            byte[] body =
                    Support.json(
                            Map.of(
                                    "type",
                                    "webhook.verification",
                                    "challenge",
                                    verifying.get("challenge")));
            String state = "VERIFICATION_FAILED";
            try {
                var reply =
                        transport.post(
                                URI.create(verifying.get("url").toString()),
                                headers(verifying, "verification:" + id, Messaging.id("whd"), body),
                                body);
                if (reply.status() / 100 == 2
                        && Support.equals(reply.challenge(), verifying.get("challenge").toString()))
                    state = "ACTIVE";
            } catch (Exception ignored) {
            }
            db.jdbc()
                    .update(
                            "UPDATE msg_webhook SET state=? WHERE id=? AND state='PENDING_VERIFICATION'",
                            state,
                            id);
            return true;
        }
        // Fanout is durable and independent from sending messages. Per-subscription cursors commit
        // with deliveries.
        for (var candidate :
                db.jdbc()
                        .queryForList(
                                "SELECT id FROM msg_webhook w WHERE state='ACTIVE' AND cursor_sequence<(SELECT COALESCE(MAX(sequence),0) FROM msg_event e WHERE e.tenant_id=w.tenant_id AND e.application_id=w.application_id) ORDER BY cursor_sequence,id LIMIT 200"))
            tx.run(
                    () -> {
                        var hook =
                                db.one(
                                        "SELECT * FROM msg_webhook WHERE id=? AND state='ACTIVE' FOR UPDATE",
                                        candidate.get("id"));
                        if (hook == null) return null;
                        List<?> types;
                        try {
                            types =
                                    Support.JSON.readValue(
                                            hook.get("event_types").toString(), List.class);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        var events =
                                db.jdbc()
                                        .queryForList(
                                                "SELECT * FROM msg_event WHERE tenant_id=? AND application_id=? AND sequence>? ORDER BY sequence LIMIT 100",
                                                hook.get("tenant_id"),
                                                hook.get("application_id"),
                                                hook.get("cursor_sequence"));
                        for (var event : events) {
                            if (types.contains(event.get("type")))
                                db.jdbc()
                                        .update(
                                                "INSERT IGNORE INTO msg_webhook_delivery(id,tenant_id,subscription_id,event_id,payload) VALUES(?,?,?,?,?)",
                                                Messaging.id("whd"),
                                                hook.get("tenant_id"),
                                                hook.get("id"),
                                                event.get("id"),
                                                Messaging.json(
                                                        Map.of(
                                                                "event_id",
                                                                event.get("id"),
                                                                "sequence",
                                                                event.get("sequence"),
                                                                "type",
                                                                event.get("type"),
                                                                "data",
                                                                Messaging.object(
                                                                        event.get("payload")))));
                        }
                        if (!events.isEmpty())
                            db.jdbc()
                                    .update(
                                            "UPDATE msg_webhook SET cursor_sequence=? WHERE id=?",
                                            events.getLast().get("sequence"),
                                            hook.get("id"));
                        return null;
                    });
        var claimed =
                tx.run(
                        () -> {
                            var row =
                                    db.one(
                                            "SELECT d.* FROM msg_webhook_delivery d JOIN msg_webhook w ON w.id=d.subscription_id AND w.state='ACTIVE' JOIN msg_application a ON a.id=w.application_id AND a.enabled=TRUE JOIN tenant t ON t.id=w.tenant_id AND t.enabled=TRUE WHERE d.state IN ('PENDING','SENDING') AND d.next_run_at<=CURRENT_TIMESTAMP(6) ORDER BY d.next_run_at,d.id LIMIT 1 FOR UPDATE");
                            if (row == null) return null;
                            String token = Messaging.id("lease");
                            if (((Number) row.get("attempts")).intValue() >= 12
                                    || timestamp(row.get("created_at"))
                                            .isBefore(Instant.now().minusSeconds(86400))) {
                                db.jdbc()
                                        .update(
                                                "UPDATE msg_webhook_delivery SET state='DEAD' WHERE id=?",
                                                row.get("id"));
                                return null;
                            }
                            db.jdbc()
                                    .update(
                                            "UPDATE msg_webhook_delivery SET state='SENDING',attempts=attempts+1,lease_token=?,next_run_at=CURRENT_TIMESTAMP(6)+INTERVAL 1 MINUTE WHERE id=?",
                                            token,
                                            row.get("id"));
                            row.put("lease_token", token);
                            return row;
                        });
        if (claimed == null) return false;
        var hook = db.one("SELECT * FROM msg_webhook WHERE id=?", claimed.get("subscription_id"));
        int status = 0;
        byte[] body = claimed.get("payload").toString().getBytes(StandardCharsets.UTF_8);
        try {
            status =
                    transport
                            .post(
                                    URI.create(hook.get("url").toString()),
                                    headers(
                                            hook,
                                            claimed.get("event_id").toString(),
                                            Messaging.id("attempt"),
                                            body),
                                    body)
                            .status();
        } catch (Exception ignored) {
        }
        int attempt = ((Number) claimed.get("attempts")).intValue() + 1;
        db.jdbc()
                .update(
                        "UPDATE msg_webhook_delivery SET state=?,last_status=?,next_run_at=? WHERE id=? AND lease_token=?",
                        status / 100 == 2 ? "DELIVERED" : attempt >= 12 ? "DEAD" : "PENDING",
                        status,
                        Messaging.time(
                                Instant.now()
                                        .plusSeconds(Math.min(3600, 5L << Math.min(attempt, 10)))),
                        claimed.get("id"),
                        claimed.get("lease_token"));
        return true;
    }

    private Instant timestamp(Object value) {
        return Messaging.instant(value);
    }

    public Object deliveries(long tenant) {
        return db.jdbc()
                .queryForList(
                        "SELECT id,subscription_id,event_id,state,attempts,last_status,next_run_at FROM msg_webhook_delivery WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200",
                        tenant);
    }

    @Scheduled(fixedDelayString = "${MESSAGING_WEBHOOK_POLL_MS:1000}")
    public void tick() {
        if (!config.flag("MESSAGING_WEBHOOKS_ENABLED", false)
                || !config.flag("MESSAGING_ENABLED", false)
                || !busy.compareAndSet(false, true)) return;
        try {
            worker.submit(
                    () -> {
                        try {
                            for (int i = 0;
                                    i < 20
                                            && !Thread.currentThread().isInterrupted()
                                            && processOne();
                                    i++) {}
                        } catch (RuntimeException error) {
                            org.slf4j.LoggerFactory.getLogger(MessageWebhooks.class)
                                    .warn(
                                            "webhook processing failed: {}",
                                            error.getClass().getSimpleName());
                        } finally {
                            busy.set(false);
                        }
                    });
        } catch (RejectedExecutionException closed) {
            busy.set(false);
        }
    }

    @PreDestroy
    public void close() {
        worker.shutdownNow();
    }
}
