package com.duozhi.outbound.auth.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.stereotype.Service;

/** User security settings are enforced on login, every authenticated request and exports. */
@Service
public class AccountSecurity {
    private final WorkspaceDocuments docs;
    private final Crypto crypto;
    private final Config config;
    private final Store store;

    public AccountSecurity(WorkspaceDocuments docs, Crypto crypto, Config config, Store store) {
        this.docs = docs;
        this.crypto = crypto;
        this.config = config;
        this.store = store;
    }

    private Map<String, Object> document(long tenant, Object user) {
        var d = docs.find(tenant, "account_security", user.toString());
        return d == null
                ? Map.of(
                        "revision",
                        0,
                        "draft",
                        Map.of(
                                "name",
                                "账号安全",
                                "ips",
                                List.of(),
                                "export_verification",
                                false,
                                "password_disabled",
                                false))
                : d;
    }

    public Map<String, Object> settings(long tenant, Object user) {
        return new LinkedHashMap<>(map(document(tenant, user).get("draft")));
    }

    public Object read(Identity i) {
        var d = document(i.tenant(), i.userId());
        var v = settings(i.tenant(), i.userId());
        return Map.of(
                "revision",
                d.get("revision"),
                "config",
                Map.of(
                        "ips",
                        v.getOrDefault("ips", List.of()),
                        "export_verification",
                        v.getOrDefault("export_verification", false),
                        "password_disabled",
                        v.getOrDefault("password_disabled", false),
                        "mfa_enabled",
                        v.containsKey("totp_secret"),
                        "key_configured",
                        v.containsKey("key_hash")));
    }

    private void persist(Identity i, Map<String, Object> value, int revision) {
        docs.save(i, "account_security", i.userId().toString(), "", value, revision);
    }

    private void password(Identity i, Map<String, Object> b) {
        if (!crypto.verifyPassword(
                i.requireUser().get("password_hash").toString(),
                Objects.toString(b.get("current_password"), "")))
            throw new ApiError(422, "请输入正确的当前密码");
    }

    public void verifyChange(Identity i, Map<String, Object> b) {
        password(i, b);
        if (settings(i.tenant(), i.userId()).containsKey("totp_secret"))
            requireOtp(i, Objects.toString(b.get("code"), ""));
    }

    public void ip(Map<String, Object> v, String address) {
        var ips = (List<?>) v.getOrDefault("ips", List.of());
        if (!ips.isEmpty() && !ips.contains(address)) throw new ApiError(403, "当前IP不在账号白名单");
    }

    public void request(Identity i, HttpServletRequest r) {
        if (i.user() != null) ip(settings(i.tenant(), i.userId()), r.getRemoteAddr());
    }

    public boolean passwordDisabled(Map<String, Object> user) {
        return Boolean.TRUE.equals(
                settings(number(user.get("tenant_id")), user.get("id")).get("password_disabled"));
    }

    public boolean login(Map<String, Object> user, String code, String address) {
        var v = settings(number(user.get("tenant_id")), user.get("id"));
        try {
            ip(v, address);
            return !v.containsKey("totp_secret")
                    || consumeOtp(new Identity(number(user.get("tenant_id")), user, Set::of), code);
        } catch (ApiError invalid) {
            return false;
        }
    }

    public Object save(Identity i, Map<String, Object> b, String remote) {
        password(i, b);
        return docs.tx.serialized(
                () -> {
                    var v = settings(i.tenant(), i.userId());
                    if (v.containsKey("totp_secret"))
                        requireOtp(i, Objects.toString(b.get("code"), ""));
                    v = settings(i.tenant(), i.userId());
                    var c = map(b.get("config"));
                    if (!(c.get("ips") instanceof List<?> ips) || ips.size() > 30)
                        throw new ApiError(422, "IP白名单最多30个地址");
                    var clean = new LinkedHashSet<String>();
                    for (var item : (List<?>) c.get("ips")) {
                        String ip = Objects.toString(item, "").trim();
                        if (!ip.matches("[0-9a-fA-F:.]{2,45}"))
                            throw new ApiError(422, "请输入有效IP地址");
                        try {
                            clean.add(java.net.InetAddress.getByName(ip).getHostAddress());
                        } catch (Exception invalid) {
                            throw new ApiError(422, "请输入有效IP地址");
                        }
                    }
                    v.put("ips", new ArrayList<>(clean));
                    ip(v, remote);
                    for (String key : List.of("export_verification", "password_disabled")) {
                        if (!(c.get(key) instanceof Boolean)) throw new ApiError(422, "安全开关无效");
                        v.put(key, c.get(key));
                    }
                    if (Boolean.TRUE.equals(v.get("export_verification"))
                            && !v.containsKey("totp_secret")) throw new ApiError(422, "请先开启双因子验证");
                    if (Boolean.TRUE.equals(v.get("password_disabled"))
                            && !v.containsKey("key_hash"))
                        throw new ApiError(422, "禁用密码前请先创建并保存API密钥");
                    // OTP consumption changes only the private counter, not the public revision.
                    persist(i, v, number(b.get("revision")));
                    return read(i);
                });
    }

    public Object enroll(Identity i, Map<String, Object> b) {
        password(i, b);
        return docs.tx.serialized(
                () -> {
                    var v = settings(i.tenant(), i.userId());
                    if (v.containsKey("totp_secret"))
                        requireOtp(i, Objects.toString(b.get("code"), ""));
                    byte[] bytes = new byte[20];
                    new SecureRandom().nextBytes(bytes);
                    String secret = base32(bytes);
                    v = settings(i.tenant(), i.userId());
                    v.put("pending_totp", seal("totp:" + i.tenant() + ":" + i.userId(), secret));
                    v.put("pending_until", Instant.now().plusSeconds(600).getEpochSecond());
                    persist(i, v, number(document(i.tenant(), i.userId()).get("revision")));
                    String uri =
                            "otpauth://totp/Outbound:"
                                    + java.net.URLEncoder.encode(i.actor(), StandardCharsets.UTF_8)
                                    + "?secret="
                                    + secret
                                    + "&issuer=Outbound&digits=6&period=30";
                    return Map.of("secret", secret, "uri", uri, "expires_seconds", 600);
                });
    }

    public Object confirm(Identity i, Map<String, Object> b) {
        return docs.tx.serialized(
                () -> {
                    var v = settings(i.tenant(), i.userId());
                    if (!v.containsKey("pending_totp")
                            || ((Number) v.get("pending_until")).longValue()
                                    < Instant.now().getEpochSecond())
                        throw new ApiError(422, "验证配置已过期，请重新开启");
                    long counter =
                            counter(
                                    unseal(
                                            "totp:" + i.tenant() + ":" + i.userId(),
                                            v.get("pending_totp").toString()),
                                    Objects.toString(b.get("code"), ""),
                                    Instant.now());
                    if (counter < 0) throw new ApiError(422, "验证码无效");
                    v.put("totp_secret", v.remove("pending_totp"));
                    v.remove("pending_until");
                    v.put("totp_counter", counter);
                    persist(i, v, number(document(i.tenant(), i.userId()).get("revision")));
                    return read(i);
                });
    }

    public void passwordReset(Identity actor, Object target) {
        docs.jdbc.update(
                "UPDATE platform_document SET draft_json=JSON_SET(JSON_REMOVE(draft_json,'$.key_hash','$.key_password_fingerprint'),'$.password_disabled',CAST('false' AS JSON)),revision=revision+1 WHERE tenant_id=? AND kind='account_security' AND object_id=?",
                actor.tenant(),
                target.toString());
    }

    public Object disableTotp(Identity i, Map<String, Object> body) {
        password(i, body);
        return docs.tx.serialized(
                () -> {
                    requireOtp(i, Objects.toString(body.get("code"), ""));
                    var v = settings(i.tenant(), i.userId());
                    v.remove("totp_secret");
                    v.remove("totp_counter");
                    v.remove("pending_totp");
                    v.remove("pending_until");
                    v.put("export_verification", false);
                    persist(i, v, number(document(i.tenant(), i.userId()).get("revision")));
                    return read(i);
                });
    }

    public void requireOtp(Identity i, String code) {
        if (!consumeOtp(i, code)) throw new ApiError(401, "验证码无效或已使用");
    }

    private boolean consumeOtp(Identity i, String code) {
        return docs.tx.serialized(
                () -> {
                    var v = settings(i.tenant(), i.userId());
                    if (!v.containsKey("totp_secret")) return false;
                    long n =
                            counter(
                                    unseal(
                                            "totp:" + i.tenant() + ":" + i.userId(),
                                            v.get("totp_secret").toString()),
                                    code,
                                    Instant.now());
                    long last =
                            v.get("totp_counter") instanceof Number value ? value.longValue() : -1;
                    if (n < 0 || n <= last) return false;
                    // Keep the visible revision stable when consuming a code.
                    v.put("totp_counter", n);
                    docs.jdbc.update(
                            "UPDATE platform_document SET draft_json=JSON_SET(draft_json,'$.totp_counter',?) WHERE tenant_id=? AND kind='account_security' AND object_id=?",
                            n,
                            i.tenant(),
                            i.userId().toString());
                    return true;
                });
    }

    public Object key(Identity i, Map<String, Object> b) {
        password(i, b);
        return docs.tx.serialized(
                () -> {
                    var v = settings(i.tenant(), i.userId());
                    if (v.containsKey("totp_secret"))
                        requireOtp(i, Objects.toString(b.get("code"), ""));
                    v = settings(i.tenant(), i.userId());
                    byte[] random = new byte[32];
                    new SecureRandom().nextBytes(random);
                    String key =
                            "usr_"
                                    + i.tenant()
                                    + "_"
                                    + i.userId()
                                    + "_"
                                    + Base64.getUrlEncoder()
                                            .withoutPadding()
                                            .encodeToString(random);
                    v.put("key_hash", JsonSupport.sha(key));
                    v.put(
                            "key_password_fingerprint",
                            JsonSupport.sha(i.user().get("password_hash").toString()));
                    persist(i, v, number(document(i.tenant(), i.userId()).get("revision")));
                    return Map.of("api_key", key, "display_once", true);
                });
    }

    public Identity authenticateKey(String key) {
        try {
            String[] parts = key.split("_", 4);
            if (parts.length != 4) throw new IllegalArgumentException();
            long tenant = Long.parseLong(parts[1]), id = Long.parseLong(parts[2]);
            var v = settings(tenant, id);
            if (!Crypto.equal(Objects.toString(v.get("key_hash"), ""), JsonSupport.sha(key)))
                throw new IllegalArgumentException();
            var u = store.require("user", tenant, id);
            if (!Boolean.TRUE.equals(u.get("enabled"))
                    || !Crypto.equal(
                            Objects.toString(v.get("key_password_fingerprint"), ""),
                            JsonSupport.sha(u.get("password_hash").toString())))
                throw new IllegalArgumentException();
            return new Identity(tenant, u, Set::of);
        } catch (Exception invalid) {
            throw new ApiError(401, "API密钥无效或已撤销");
        }
    }

    public void export(Identity i, String code) {
        if (i.user() != null
                && Boolean.TRUE.equals(settings(i.tenant(), i.userId()).get("export_verification")))
            requireOtp(i, code);
    }

    public String seal(String context, String value) {
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] key =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    ("account-security-v1:" + config.get("JWT_SECRET", ""))
                                            .getBytes(StandardCharsets.UTF_8));
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder()
                    .encodeToString(
                            ByteBuffer.allocate(nonce.length + encrypted.length)
                                    .put(nonce)
                                    .put(encrypted)
                                    .array());
        } catch (Exception e) {
            throw new IllegalStateException("security encryption failed", e);
        }
    }

    public String unseal(String context, String value) {
        try {
            byte[] packed = Base64.getDecoder().decode(value);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] key =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    ("account-security-v1:" + config.get("JWT_SECRET", ""))
                                            .getBytes(StandardCharsets.UTF_8));
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Arrays.copyOf(packed, 12)));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(
                    cipher.doFinal(Arrays.copyOfRange(packed, 12, packed.length)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ApiError(503, "安全配置无法解密，请检查服务密钥");
        }
    }

    static String base32(byte[] bytes) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        var out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 255);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(chars.charAt((buffer >> bits) & 31));
            }
        }
        if (bits > 0) out.append(chars.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    public static String totp(String secret, long counter) {
        try {
            var out = new java.io.ByteArrayOutputStream();
            int buffer = 0, bits = 0;
            for (char c : secret.toCharArray()) {
                int n = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
                if (n < 0) throw new IllegalArgumentException();
                buffer = (buffer << 5) | n;
                bits += 5;
                if (bits >= 8) {
                    bits -= 8;
                    out.write((buffer >> bits) & 255);
                }
            }
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(out.toByteArray(), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int at = hash[hash.length - 1] & 15;
            int n =
                    ((hash[at] & 127) << 24)
                            | ((hash[at + 1] & 255) << 16)
                            | ((hash[at + 2] & 255) << 8)
                            | (hash[at + 3] & 255);
            return String.format(Locale.ROOT, "%06d", n % 1000000);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid TOTP secret", e);
        }
    }

    private static long counter(String secret, String code, Instant now) {
        if (!code.matches("[0-9]{6}")) return -1;
        long at = now.getEpochSecond() / 30;
        for (long n = at - 1; n <= at + 1; n++) if (Crypto.equal(totp(secret, n), code)) return n;
        return -1;
    }
}
