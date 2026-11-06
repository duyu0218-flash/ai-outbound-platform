package com.duozhi.outbound.auth.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.job.service.IntegrationTasks;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PhoneVerification {
    private final WorkspaceDocuments docs;
    private final AccountSecurity security;
    private final IntegrationTasks messages;

    public PhoneVerification(
            WorkspaceDocuments docs, AccountSecurity security, IntegrationTasks messages) {
        this.docs = docs;
        this.security = security;
        this.messages = messages;
    }

    private String phone(Identity actor, Object target) {
        actor.requireUser();
        if (!actor.userId().toString().equals(target.toString())) actor.admin("admin:users");
        var user =
                docs.one(
                        "SELECT phone FROM sys_user WHERE tenant_id=? AND id=? AND enabled=1",
                        actor.tenant(),
                        target);
        if (user == null) throw new ApiError(404, "账号不存在");
        String phone = Objects.toString(user.get("phone"), "");
        if (!phone.matches("\\+?[0-9]{6,15}")) throw new ApiError(422, "请先保存有效手机号");
        return phone;
    }

    public boolean verified(long tenant, Object user, Object phone) {
        var doc = docs.find(tenant, "account_phone", user.toString());
        return doc != null
                && Boolean.TRUE.equals(map(doc.get("draft")).get("verified"))
                && Objects.equals(map(doc.get("draft")).get("phone"), phone);
    }

    public Object send(Identity actor, Object target, Map<String, Object> body) {
        security.verifyChange(actor, body);
        String number = phone(actor, target);
        String code =
                String.format(
                        Locale.ROOT, "%06d", new java.security.SecureRandom().nextInt(1000000));
        String context = "phone:" + actor.tenant() + ":" + target;
        String delivery = UUID.randomUUID().toString();
        docs.tx.serialized(
                () -> {
                    var old = docs.find(actor.tenant(), "account_phone", target.toString());
                    var v =
                            old == null
                                    ? new LinkedHashMap<String, Object>()
                                    : new LinkedHashMap<>(map(old.get("draft")));
                    long now = Instant.now().getEpochSecond(),
                            last = v.get("sent_at") instanceof Number n ? n.longValue() : 0;
                    int count = now - last >= 3600 ? 0 : number(v.get("sent_count"));
                    if (now - last < 60 || count >= 5) throw new ApiError(429, "验证码发送频繁，请稍后重试");
                    v.putAll(
                            Map.of(
                                    "name",
                                    "手机号验证",
                                    "phone",
                                    number,
                                    "code_enc",
                                    security.seal(context, code),
                                    "expires_at",
                                    now + 300,
                                    "sent_at",
                                    now,
                                    "sent_count",
                                    count + 1,
                                    "attempts",
                                    0,
                                    "deliverable",
                                    false,
                                    "delivery",
                                    delivery));
                    v.put("verified", false);
                    docs.save(
                            actor,
                            "account_phone",
                            target.toString(),
                            "",
                            v,
                            old == null ? 0 : number(old.get("revision")));
                    return null;
                });
        var response =
                messages.send(
                        actor.tenant(),
                        number,
                        "您的手机号验证码为" + code + "，5分钟内有效。请勿向他人透露。",
                        "phone:" + delivery);
        if (!Set.of("sent", "accepted", "queued", "delivered")
                .contains(Objects.toString(response.get("state"), "")))
            throw new ApiError(503, "短信通道未确认发送；模拟通道不能用于真实手机号验证");
        docs.tx.serialized(
                () -> {
                    var doc = docs.require(actor.tenant(), "account_phone", target.toString());
                    var v = new LinkedHashMap<>(map(doc.get("draft")));
                    if (delivery.equals(v.get("delivery"))) {
                        v.put("deliverable", true);
                        docs.save(
                                actor,
                                "account_phone",
                                target.toString(),
                                "",
                                v,
                                number(doc.get("revision")));
                    }
                    return null;
                });
        return Map.of("sent", true, "expires_seconds", 300);
    }

    public Object confirm(Identity actor, Object target, Map<String, Object> body) {
        String number = phone(actor, target);
        boolean accepted =
                docs.tx.serialized(
                        () -> {
                            var doc = docs.find(actor.tenant(), "account_phone", target.toString());
                            if (doc == null) return false;
                            var v = new LinkedHashMap<>(map(doc.get("draft")));
                            if (!number.equals(v.get("phone"))
                                    || !Boolean.TRUE.equals(v.get("deliverable"))
                                    || !v.containsKey("code_enc")
                                    || ((Number) v.get("expires_at")).longValue()
                                            < Instant.now().getEpochSecond()
                                    || number(v.get("attempts")) >= 5) return false;
                            boolean valid =
                                    Crypto.equal(
                                            security.unseal(
                                                    "phone:" + actor.tenant() + ":" + target,
                                                    v.get("code_enc").toString()),
                                            Objects.toString(body.get("code"), ""));
                            v.put("attempts", number(v.get("attempts")) + 1);
                            if (valid) {
                                v.put("verified", true);
                                v.remove("code_enc");
                                v.put("deliverable", false);
                            }
                            docs.save(
                                    actor,
                                    "account_phone",
                                    target.toString(),
                                    "",
                                    v,
                                    number(doc.get("revision")));
                            return valid;
                        });
        if (!accepted) throw new ApiError(401, "验证码无效、已使用或已过期");
        return Map.of("verified", true);
    }
}
