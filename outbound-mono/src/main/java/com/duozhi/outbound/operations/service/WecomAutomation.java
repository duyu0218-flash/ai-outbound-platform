package com.duozhi.outbound.operations.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.auth.service.AccountSecurity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.job.worker.TaskWorker;
import com.duozhi.outbound.messaging.service.PinnedWebhookTransport;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** A signed third-party bridge request is distinct from a customer accepting the invitation. */
@Service
public class WecomAutomation {
    private final WorkspaceDocuments docs;
    private final AccountSecurity vault;
    private final PinnedWebhookTransport transport;

    public WecomAutomation(
            WorkspaceDocuments docs, AccountSecurity vault, PinnedWebhookTransport transport) {
        this.docs = docs;
        this.vault = vault;
        this.transport = transport;
    }

    public Object accounts(long tenant) {
        return docs.list(tenant, "wecom_account", null);
    }

    public Object account(Identity i, String id, Map<String, Object> b) {
        if (!id.matches("[0-9]{11}")) throw new ApiError(422, "微信号须为11位数字");
        String name = text(map(b.get("config")), "name", 128, true);
        return docs.tx.serialized(
                () -> {
                    if (docs.find(i.tenant(), "wecom_account", id) == null
                            && docs.list(i.tenant(), "wecom_account", null).size() >= 100)
                        throw new ApiError(422, "每个公司最多100个微信号");
                    return docs.save(
                            i,
                            "wecom_account",
                            id,
                            "",
                            Map.of("name", name, "wecom_id", id),
                            number(b.get("revision")));
                });
    }

    public Object providers(long tenant) {
        return docs.list(tenant, "wecom_provider", null).stream().map(this::redact).toList();
    }

    private Map<String, Object> redact(Map<String, Object> doc) {
        var result = new LinkedHashMap<>(doc);
        var v = new LinkedHashMap<>(map(doc.get("draft")));
        v.put("secret_configured", v.remove("secret_enc") != null);
        result.put("draft", v);
        return result;
    }

    public Object provider(Identity i, String id, Map<String, Object> b) {
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new ApiError(422, "配置编号无效");
        var raw = map(b.get("config"));
        var v = new LinkedHashMap<String, Object>();
        for (String key : List.of("name", "app_key", "rule_id", "version", "endpoint"))
            v.put(key, text(raw, key, key.equals("endpoint") ? 2048 : 160, true));
        try {
            PinnedWebhookTransport.validate(URI.create(v.get("endpoint").toString()));
        } catch (Exception invalid) {
            throw new ApiError(422, "服务商桥接地址须为HTTPS，端口443");
        }
        if (!(raw.get("enabled") instanceof Boolean)) throw new ApiError(422, "配置状态无效");
        v.put("enabled", raw.get("enabled"));
        return docs.tx.serialized(
                () -> {
                    var old = docs.find(i.tenant(), "wecom_provider", id);
                    if (old == null && docs.list(i.tenant(), "wecom_provider", null).size() >= 30)
                        throw new ApiError(422, "最多30个服务商配置");
                    String secret = Objects.toString(b.get("app_secret"), "");
                    if (!secret.isBlank()) {
                        if (secret.length() < 16 || secret.length() > 256)
                            throw new ApiError(422, "app_secret须为16至256个字符");
                        v.put("secret_enc", vault.seal("wecom:" + i.tenant() + ":" + id, secret));
                    } else if (old != null)
                        v.put("secret_enc", map(old.get("draft")).get("secret_enc"));
                    if (v.get("secret_enc") == null) throw new ApiError(422, "请填写app_secret");
                    return redact(
                            docs.save(i, "wecom_provider", id, "", v, number(b.get("revision"))));
                });
    }

    public void validate(long tenant, boolean enabled) {
        if (enabled
                && (docs.list(tenant, "wecom_account", null).isEmpty()
                        || docs.list(tenant, "wecom_provider", null).stream()
                                .noneMatch(
                                        d ->
                                                Boolean.TRUE.equals(
                                                        map(d.get("draft")).get("enabled")))))
            throw new ApiError(422, "请先配置企业微信号和启用的服务商桥接");
    }

    public void execute(Map<String, Object> task, Map<String, Object> event) {
        long tenant = number(task.get("tenant_id"));
        String id = JsonSupport.sha(event.get("call_id") + ":" + event.get("attempt"));
        var actor = new Identity(tenant, null, Set::of);
        Map<String, Object> request =
                docs.tx.serialized(
                        () -> {
                            TaskWorker.permitted();
                            var snapshot = docs.campaignSnapshot(tenant, event.get("campaign_id"));
                            if (snapshot == null
                                    || !Boolean.TRUE.equals(
                                            map(snapshot.getOrDefault("task_options", Map.of()))
                                                    .get("auto_wecom"))) return null;
                            var old = docs.find(tenant, "wecom_request", id);
                            if (old != null)
                                return "accepted".equals(map(old.get("draft")).get("state"))
                                        ? null
                                        : map(old.get("draft"));
                            var c =
                                    docs.one(
                                            "SELECT c.phone FROM callsession c JOIN contact p ON p.tenant_id=c.tenant_id AND p.id=c.contact_id WHERE c.tenant_id=? AND c.id=? AND c.campaign_id=? AND c.attempts=? AND c.status='COMPLETED' AND p.consent_state='CONSENTED' AND p.dnc=0",
                                            tenant,
                                            event.get("call_id"),
                                            event.get("campaign_id"),
                                            event.get("attempt"));
                            var accounts = docs.list(tenant, "wecom_account", null);
                            var providers =
                                    docs.list(tenant, "wecom_provider", null).stream()
                                            .filter(
                                                    d ->
                                                            Boolean.TRUE.equals(
                                                                    map(d.get("draft"))
                                                                            .get("enabled")))
                                            .toList();
                            if (c == null || accounts.isEmpty() || providers.isEmpty()) return null;
                            var selected =
                                    accounts.get(
                                            new java.security.SecureRandom()
                                                    .nextInt(accounts.size()));
                            var data =
                                    Map.<String, Object>of(
                                            "name",
                                            "企微添加请求",
                                            "state",
                                            "pending",
                                            "phone",
                                            c.get("phone"),
                                            "wecom_id",
                                            selected.get("object_id"),
                                            "provider_id",
                                            providers.getFirst().get("object_id"),
                                            "delivery_id",
                                            "wecom:" + id);
                            docs.save(actor, "wecom_request", id, "", data, 0);
                            return data;
                        });
        if (request == null) return;
        if (docs.one(
                        "SELECT c.id FROM callsession c JOIN contact p ON p.tenant_id=c.tenant_id AND p.id=c.contact_id WHERE c.tenant_id=? AND c.id=? AND c.attempts=? AND c.status='COMPLETED' AND p.consent_state='CONSENTED' AND p.dnc=0",
                        tenant,
                        event.get("call_id"),
                        event.get("attempt"))
                == null) return;
        var provider = docs.find(tenant, "wecom_provider", request.get("provider_id").toString());
        if (provider == null || !Boolean.TRUE.equals(map(provider.get("draft")).get("enabled")))
            return;
        var config = map(provider.get("draft"));
        byte[] body =
                docs.json
                        .json(
                                Map.of(
                                        "phone",
                                        request.get("phone"),
                                        "wecom_id",
                                        request.get("wecom_id"),
                                        "app_key",
                                        config.get("app_key"),
                                        "rule_id",
                                        config.get("rule_id"),
                                        "version",
                                        config.get("version")))
                        .getBytes(StandardCharsets.UTF_8);
        String stamp = Long.toString(Instant.now().getEpochSecond());
        String secret =
                vault.unseal(
                        "wecom:" + tenant + ":" + provider.get("object_id"),
                        config.get("secret_enc").toString());
        byte[] signed =
                (stamp + "." + new String(body, StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8);
        try {
            TaskWorker.permitted();
            var response =
                    transport.request(
                            URI.create(config.get("endpoint").toString()),
                            Map.of(
                                    "Idempotency-Key",
                                    request.get("delivery_id").toString(),
                                    "X-Webhook-Timestamp",
                                    stamp,
                                    "X-Webhook-Signature",
                                    "sha256=" + JsonSupport.hmac(secret, signed)),
                            body);
            if (response.status() < 200 || response.status() >= 300)
                throw new ApiError(502, "服务商未受理企微添加请求");
        } catch (ApiError e) {
            throw e;
        } catch (Exception e) {
            throw new ApiError(502, "企微桥接暂时不可用");
        }
        docs.tx.serialized(
                () -> {
                    TaskWorker.permitted();
                    var current = docs.require(tenant, "wecom_request", id);
                    var data = new LinkedHashMap<>(map(current.get("draft")));
                    data.put("state", "accepted");
                    docs.save(
                            actor, "wecom_request", id, "", data, number(current.get("revision")));
                    return null;
                });
    }
}
