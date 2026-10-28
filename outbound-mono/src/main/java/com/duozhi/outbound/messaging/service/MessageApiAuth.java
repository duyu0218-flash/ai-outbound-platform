package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.dao.MessageStore;
import jakarta.servlet.http.HttpServletRequest;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class MessageApiAuth {
    private final MessageStore db;
    private final MessageSecrets secrets;
    private final Transactions tx;

    public MessageApiAuth(MessageStore db, MessageSecrets secrets, Transactions tx) {
        this.db = db;
        this.secrets = secrets;
        this.tx = tx;
    }

    private String header(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) throw new ApiError(401, "签名头缺失或重复");
        return values.getFirst();
    }

    public static String query(String query) {
        if (query == null || query.isEmpty()) return "";
        var sorted = new TreeMap<String, String>();
        try {
            for (String pair : query.split("&", -1)) {
                var parts = pair.split("=", 2);
                if (parts.length != 2) throw new IllegalArgumentException();
                String k = URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        v = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                if (sorted.put(k, v) != null) throw new IllegalArgumentException();
            }
            var out = new StringJoiner("&");
            sorted.forEach((k, v) -> out.add(encode(k) + "=" + encode(v)));
            return out.toString();
        } catch (Exception e) {
            throw new ApiError(400, "查询参数无效或重复");
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%7E", "~")
                .replace("*", "%2A");
    }

    public Messaging.Principal verify(HttpServletRequest request, byte[] body) {
        String authorization = header(request, "Authorization");
        var matcher =
                java.util.regex.Pattern.compile(
                                "HMAC-SHA256 Credential=(app_[a-f0-9]{32}),Signature=([a-f0-9]{64})")
                        .matcher(authorization);
        if (!matcher.matches()) throw new ApiError(401, "应用签名无效");
        String app = matcher.group(1),
                signature = matcher.group(2),
                timestamp = header(request, "X-Timestamp"),
                nonce = header(request, "X-Nonce"),
                hash = header(request, "X-Content-SHA256");
        if (request.getHeader("X-Delegation-Id") != null) throw new ApiError(422, "委托代发尚未开放");
        long now = Instant.now().getEpochSecond(), received;
        try {
            received = Long.parseLong(timestamp);
        } catch (Exception e) {
            throw new ApiError(401, "签名时间无效");
        }
        if (received < now - 300 || received > now + 300 || !nonce.matches("[a-fA-F0-9]{32,128}"))
            throw new ApiError(401, "请求过期或随机数无效");
        String path = request.getRequestURI();
        if (!path.matches("/openapi/v1/[A-Za-z0-9_/-]+") || path.contains("//"))
            throw new ApiError(400, "路径不是规范路径");
        String key = Objects.toString(request.getHeader("Idempotency-Key"), "");
        if (!request.getMethod().equals("GET")) key = header(request, "Idempotency-Key");
        if (!Support.equals(hash, Support.hex(Support.sha(body))))
            throw new ApiError(401, "请求体摘要不匹配");
        String canonical =
                String.join(
                        "\n",
                        request.getMethod(),
                        path,
                        query(request.getQueryString()),
                        timestamp,
                        nonce,
                        hash,
                        key,
                        "");
        try {
            return tx.run(
                    () -> {
                        var row =
                                db.one(
                                        "SELECT * FROM msg_application WHERE id=? AND enabled=TRUE FOR UPDATE",
                                        app);
                        if (row == null) throw new ApiError(401, "应用签名无效");
                        long tenant = ((Number) row.get("tenant_id")).longValue();
                        String secret =
                                secrets.decrypt(
                                        "app:" + tenant + ":" + app,
                                        row.get("secret_cipher").toString());
                        if (!Support.equals(
                                signature,
                                Support.hmac(secret, canonical.getBytes(StandardCharsets.UTF_8))))
                            throw new ApiError(401, "应用签名无效");
                        var t = db.one("SELECT enabled FROM tenant WHERE id=?", tenant);
                        if (t == null || !Boolean.TRUE.equals(t.get("enabled")))
                            throw new ApiError(403, "企业已停用");
                        Long count =
                                db.jdbc()
                                        .queryForObject(
                                                "SELECT COUNT(*) FROM msg_nonce WHERE application_id=? AND expires_at>?",
                                                Long.class,
                                                app,
                                                Messaging.time(Instant.ofEpochSecond(now + 540)));
                        if (count >= 120) throw new ApiError(429, "应用请求频率超限");
                        db.jdbc()
                                .update(
                                        "INSERT INTO msg_nonce(application_id,nonce,expires_at) VALUES(?,?,?)",
                                        app,
                                        nonce,
                                        Messaging.time(Instant.ofEpochSecond(now + 600)));
                        return new Messaging.Principal(tenant, app);
                    });
        } catch (DuplicateKeyException replay) {
            throw new ApiError(409, "请求随机数已使用");
        }
    }
}
