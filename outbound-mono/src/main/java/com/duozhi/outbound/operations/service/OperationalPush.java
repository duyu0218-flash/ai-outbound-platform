package com.duozhi.outbound.operations.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.auth.service.AccountSecurity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.runtime.ScheduledWork;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.job.service.Outbox;
import com.duozhi.outbound.messaging.service.PinnedWebhookTransport;
import com.duozhi.outbound.messaging.service.WebhookTransport;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Configured signed/encrypted delivery uses the existing durable retry outbox. */
@Service
public class OperationalPush {
    private final WorkspaceDocuments docs;
    private final AccountSecurity vault;
    private final WebhookTransport transport;
    private final Outbox outbox;
    private final ScheduledWork scheduled;
    private final com.duozhi.outbound.operations.controller.VoiceStatements statements;

    public OperationalPush(
            WorkspaceDocuments docs,
            AccountSecurity vault,
            WebhookTransport transport,
            Outbox outbox,
            ScheduledWork scheduled,
            com.duozhi.outbound.operations.controller.VoiceStatements statements) {
        this.docs = docs;
        this.vault = vault;
        this.transport = transport;
        this.outbox = outbox;
        this.scheduled = scheduled;
        this.statements = statements;
    }

    public static Map<String, Object> defaults() {
        return new LinkedHashMap<>(
                Map.of(
                        "name",
                        "推送配置",
                        "enabled",
                        false,
                        "mode",
                        "normal",
                        "url",
                        "",
                        "algorithm",
                        "hmac_sha256",
                        "call_type",
                        "answered",
                        "parameters",
                        Map.of(),
                        "threshold",
                        0));
    }

    private String kind(String type) {
        if (!Set.of("calls", "balance").contains(type)) throw new ApiError(404, "推送类型不存在");
        return "push_" + type;
    }

    public Object read(long tenant, String type) {
        var d = docs.find(tenant, kind(type), "tenant");
        var c = d == null ? defaults() : new LinkedHashMap<>(map(d.get("draft")));
        c.put("key_configured", c.remove("secret_enc") != null);
        return Map.of("revision", d == null ? 0 : d.get("revision"), "config", c);
    }

    public Object save(Identity i, String type, Map<String, Object> b) {
        var v = defaults();
        v.putAll(map(b.get("config")));
        v.remove("secret_enc");
        v.put("name", type.equals("calls") ? "话单推送" : "欠费推送");
        if (!(v.get("enabled") instanceof Boolean)
                || !Set.of("normal", "advanced").contains(v.get("mode"))
                || !Set.of("hmac_sha256", "aes_gcm").contains(v.get("algorithm"))
                || !Set.of("all", "answered").contains(v.get("call_type")))
            throw new ApiError(422, "推送配置无效");
        String url = text(v, "url", 2048, false);
        if (!url.isEmpty())
            try {
                PinnedWebhookTransport.validate(URI.create(url));
            } catch (Exception e) {
                throw new ApiError(422, "推送地址须为HTTPS公网地址，端口443");
            }
        if (!(v.get("parameters") instanceof Map<?, ?> params)
                || params.size() > 30
                || docs.json.json(params).length() > 4000) throw new ApiError(422, "扩展参数过多");
        v.put("threshold", OutboundRules.integer(v.get("threshold"), 0, 10000000));
        return docs.tx.serialized(
                () -> {
                    var old = docs.find(i.tenant(), kind(type), "tenant");
                    String key = Objects.toString(b.get("secret_key"), "");
                    if (!key.isEmpty()) {
                        if (key.length() < 32 || key.length() > 256)
                            throw new ApiError(422, "推送密钥须为32至256个字符");
                        v.put("secret_enc", vault.seal("push:" + i.tenant() + ":" + type, key));
                    } else if (old != null && map(old.get("draft")).containsKey("secret_enc"))
                        v.put("secret_enc", map(old.get("draft")).get("secret_enc"));
                    if (Boolean.TRUE.equals(v.get("enabled"))
                            && (url.isEmpty() || !v.containsKey("secret_enc")))
                        throw new ApiError(422, "启用推送前请填写地址和密钥");
                    v.remove("key_configured");
                    docs.save(i, kind(type), "tenant", "", v, number(b.get("revision")));
                    return read(i.tenant(), type);
                });
    }

    public void deliver(Map<String, Object> task, Map<String, Object> payload) throws Exception {
        long tenant = number(task.get("tenant_id"));
        String type = payload.get("push_type").toString();
        var d = docs.find(tenant, kind(type), "tenant");
        if (d == null) return;
        var c = map(d.get("draft"));
        if (!Boolean.TRUE.equals(c.get("enabled"))) return;
        var event = new LinkedHashMap<>(map(payload.get("data")));
        event.put("mode", c.get("mode"));
        event.put("extensions", c.get("parameters"));
        String secret = vault.unseal("push:" + tenant + ":" + type, c.get("secret_enc").toString());
        byte[] body = docs.json.json(event).getBytes(StandardCharsets.UTF_8);
        if (c.get("algorithm").equals("aes_gcm")) {
            byte[] nonce = new byte[12];
            new java.security.SecureRandom().nextBytes(nonce);
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            var key =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(secret.getBytes(StandardCharsets.UTF_8));
            cipher.init(
                    javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, nonce));
            body =
                    docs.json
                            .json(
                                    Map.of(
                                            "algorithm",
                                            "AES-256-GCM",
                                            "nonce",
                                            Base64.getEncoder().encodeToString(nonce),
                                            "ciphertext",
                                            Base64.getEncoder()
                                                    .encodeToString(cipher.doFinal(body))))
                            .getBytes(StandardCharsets.UTF_8);
        }
        String stamp = "" + Instant.now().getEpochSecond();
        byte[] prefix = (stamp + ".").getBytes(StandardCharsets.US_ASCII),
                signed = Arrays.copyOf(prefix, prefix.length + body.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        var response =
                transport.post(
                        URI.create(c.get("url").toString()),
                        Map.of(
                                "X-Delivery-ID",
                                task.get("id").toString(),
                                "Idempotency-Key",
                                task.get("id").toString(),
                                "X-Webhook-Timestamp",
                                stamp,
                                "X-Webhook-Signature",
                                "sha256=" + JsonSupport.hmac(secret, signed)),
                        body);
        if (response.status() < 200 || response.status() >= 300)
            throw new ApiError(502, "推送服务返回 " + response.status());
    }

    @Scheduled(fixedDelay = 10000)
    public void tick() {
        scheduled.submit(ScheduledWork.Job.OPERATIONS_PUSH, this::balances);
    }

    public synchronized void balances() {
        long after =
                docs.jdbc.queryForObject(
                        "SELECT last_id FROM platform_job_cursor WHERE job_key='balance-push'",
                        Long.class);
        // Keyset pages bound each scan. Persist the next position before work so an interrupted
        // dependency cannot force every subsequent tick to restart at the same tenant.
        for (int page = 0; page < 10 && ScheduledWork.withinBudget(); page++) {
            var rows =
                    docs.jdbc.queryForList(
                            "SELECT tenant_id,revision,draft_json FROM platform_document WHERE kind='push_balance' AND object_id='tenant' AND tenant_id>? ORDER BY tenant_id LIMIT 100",
                            after);
            if (rows.isEmpty()) {
                balanceCursor(0);
                return;
            }
            for (var d : rows) {
                if (!ScheduledWork.withinBudget()) return;
                after = ((Number) d.get("tenant_id")).longValue();
                balanceCursor(after);
                try {
                    balance(d);
                } catch (RuntimeException failed) {
                    org.slf4j.LoggerFactory.getLogger(OperationalPush.class)
                            .warn(
                                    "balance scan tenant={} error_type={}",
                                    after,
                                    failed.getClass().getSimpleName());
                    // A failed tenant is retried next cycle, without starving the other tenants.
                }
            }
            if (rows.size() < 100) {
                balanceCursor(0);
                return;
            }
        }
    }

    private void balanceCursor(long next) {
        docs.jdbc.update(
                "UPDATE platform_job_cursor SET last_id=?,updated_at=CURRENT_TIMESTAMP(6) WHERE job_key='balance-push'",
                next);
    }

    private void balance(Map<String, Object> d) {
        var c = docs.json.object(d.get("draft_json").toString());
        if (!Boolean.TRUE.equals(c.get("enabled"))) return;
        long tenant = number(d.get("tenant_id"));
        var balance = statements.balance(tenant);
        if (!Boolean.TRUE.equals(balance.get("available"))
                || !(balance.get("amount") instanceof Number n)
                || n.doubleValue() > ((Number) c.get("threshold")).doubleValue()) return;
        outbox.enqueue(
                tenant,
                "business_callback",
                "balance:" + tenant,
                "push-balance:"
                        + tenant
                        + ":"
                        + d.get("revision")
                        + ":"
                        + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")),
                Map.of(
                        "push_type",
                        "balance",
                        "data",
                        Map.of(
                                "event",
                                "balance.low",
                                "tenant_id",
                                tenant,
                                "balance",
                                balance.get("amount"),
                                "threshold",
                                c.get("threshold"))));
    }
}
