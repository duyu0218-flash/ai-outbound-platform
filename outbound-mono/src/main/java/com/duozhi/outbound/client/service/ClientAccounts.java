package com.duozhi.outbound.client.service;

import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.client.dao.ClientStore;
import com.duozhi.outbound.client.security.ClientAuthenticator;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Client accounts: one-time initial passwords, lockout, revocation. */
@Service
public class ClientAccounts {
    public static final Set<String> ROLES = Set.of("owner", "member");
    public static final Set<String> GRANTS = Set.of("phone_reveal", "recording");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.@-]{3,64}");
    private static final String ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private final ClientStore db;
    private final Crypto crypto;
    private final Config config;
    private final JsonSupport json;
    private final ClientAuthenticator clients;
    private final SecureRandom random = new SecureRandom();

    public ClientAccounts(
            ClientStore db,
            Crypto crypto,
            Config config,
            JsonSupport json,
            ClientAuthenticator clients) {
        this.db = db;
        this.crypto = crypto;
        this.config = config;
        this.json = json;
        this.clients = clients;
    }

    public String oneTimePassword() {
        var out = new StringBuilder();
        for (int i = 0; i < 12; i++) out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return out.append(random.nextInt(10)).toString();
    }

    public static void policy(String password) {
        if (password == null
                || password.length() < 10
                || password.length() > 128
                || !password.chars().anyMatch(Character::isLetter)
                || !password.chars().anyMatch(Character::isDigit))
            throw new ApiError(422, "密码至少 10 位，且须同时包含字母和数字");
    }

    public Map<String, Object> grants(Object input) {
        var out = new LinkedHashMap<String, Object>();
        if (input instanceof Map<?, ?> map)
            for (String key : GRANTS) out.put(key, Boolean.TRUE.equals(map.get(key)));
        return out;
    }

    /** Creates an account and returns it with its one-time password, shown only in this reply. */
    public Map<String, Object> create(
            long tenant, long clientId, Map<String, Object> body, String role) {
        String username = Objects.toString(body.get("username"), "").trim();
        if (!USERNAME.matcher(username).matches())
            throw new ApiError(422, "用户名须为 3-64 位字母、数字或 _.@-");
        String name = Objects.toString(body.get("full_name"), "").trim();
        if (name.isEmpty() || name.length() > 100) throw new ApiError(422, "姓名不能为空");
        String phone = Objects.toString(body.get("phone"), "").trim();
        if (phone.length() > 32) throw new ApiError(422, "电话过长");
        if (!ROLES.contains(role)) throw new ApiError(422, "不支持的角色");
        String password = oneTimePassword();
        Map<String, Object> p =
                JsonSupport.map(
                        "tenantId",
                        tenant,
                        "clientId",
                        clientId,
                        "username",
                        username,
                        "password_hash",
                        crypto.password(password),
                        "full_name",
                        name,
                        "phone",
                        phone.isEmpty() ? null : phone,
                        "role",
                        role,
                        "grants_json",
                        json.json(grants(body.get("grants"))));
        try {
            db.write("userInsert", p);
        } catch (DuplicateKeyException e) {
            throw new ApiError(409, "用户名已存在");
        }
        Map<String, Object> user =
                publicUser(user(tenant, ((Number) p.get("user_id")).longValue()));
        user.put("initial_password", password);
        return user;
    }

    public Map<String, Object> user(long tenant, long id) {
        return db.one("userGet", JsonSupport.map("tenantId", tenant, "userId", id));
    }

    public Map<String, Object> publicUser(Map<String, Object> row) {
        var out = new LinkedHashMap<String, Object>();
        for (String key :
                List.of(
                        "id",
                        "client_id",
                        "username",
                        "full_name",
                        "phone",
                        "role",
                        "enabled",
                        "must_change_password",
                        "last_login_at",
                        "locked_until",
                        "created_at")) out.put(key, row.get(key));
        out.put("grants", json.object(Objects.toString(row.get("grants_json"), "{}")));
        return out;
    }

    /** Returns null for every failure so callers cannot distinguish unknown accounts. */
    public Map<String, Object> login(String username, String password) {
        Map<String, Object> user = db.one("userByName", JsonSupport.map("username", username));
        if (user == null || !Boolean.TRUE.equals(user.get("enabled"))) return null;
        long tenant = ((Number) user.get("tenant_id")).longValue();
        long id = ((Number) user.get("id")).longValue();
        LocalDateTime now = Store.now();
        if (user.get("locked_until") instanceof LocalDateTime locked && locked.isAfter(now))
            return null;
        if (!crypto.verifyPassword(user.get("password_hash").toString(), password)) {
            int failed =
                    (user.get("locked_until") == null
                                    ? ((Number) user.get("failed_login_attempts")).intValue()
                                    : 0)
                            + 1;
            db.write(
                    "userLoginFailed",
                    JsonSupport.map(
                            "tenantId",
                            tenant,
                            "userId",
                            id,
                            "failed",
                            failed,
                            "locked_until",
                            failed >= Math.max(1, config.number("AUTH_MAX_FAILED_ATTEMPTS", 5))
                                    ? now.plusSeconds(
                                            Math.max(
                                                    30, config.number("AUTH_LOCKOUT_SECONDS", 900)))
                                    : null));
            return null;
        }
        clients.requireTenant(tenant);
        Map<String, Object> org =
                db.one(
                        "orgGet",
                        JsonSupport.map("tenantId", tenant, "clientId", user.get("client_id")));
        if (!clients.active(org)) throw new ApiError(403, "企业账号已停用或合同已到期");
        db.write(
                "userLoginSucceeded",
                JsonSupport.map("tenantId", tenant, "userId", id, "now", now));
        return user(tenant, id);
    }

    public void password(long tenant, long id, String password, boolean mustChange) {
        policy(password);
        db.write(
                "userPassword",
                JsonSupport.map(
                        "tenantId",
                        tenant,
                        "userId",
                        id,
                        "password_hash",
                        crypto.password(password),
                        "must_change_password",
                        mustChange));
    }

    /** Resets to a fresh one-time password; existing tokens are revoked by the version bump. */
    public String reset(long tenant, long id) {
        String password = oneTimePassword();
        db.write(
                "userPassword",
                JsonSupport.map(
                        "tenantId",
                        tenant,
                        "userId",
                        id,
                        "password_hash",
                        crypto.password(password),
                        "must_change_password",
                        true));
        return password;
    }

    public void revoke(long tenant, long id) {
        db.write("userRevoke", JsonSupport.map("tenantId", tenant, "userId", id));
    }

    public boolean verify(long tenant, long id, String password) {
        Map<String, Object> user = user(tenant, id);
        return user != null
                && crypto.verifyPassword(user.get("password_hash").toString(), password);
    }

    /** Applies profile, enablement and grant changes; disabling also revokes tokens. */
    public Map<String, Object> update(
            long tenant, long clientId, Map<String, Object> current, Map<String, Object> body) {
        String name =
                Objects.toString(body.getOrDefault("full_name", current.get("full_name")), "");
        if (name.isBlank() || name.length() > 100) throw new ApiError(422, "姓名不能为空");
        String phone = Objects.toString(body.getOrDefault("phone", current.get("phone")), "");
        if (phone.length() > 32) throw new ApiError(422, "电话过长");
        Object enabledValue = body.getOrDefault("enabled", current.get("enabled"));
        if (!(enabledValue instanceof Boolean enabled)) throw new ApiError(422, "enabled 必须是布尔值");
        Map<String, Object> grants =
                body.containsKey("grants")
                        ? grants(body.get("grants"))
                        : json.object(Objects.toString(current.get("grants_json"), "{}"));
        db.write(
                "userUpdate",
                JsonSupport.map(
                        "tenantId",
                        tenant,
                        "clientId",
                        clientId,
                        "userId",
                        current.get("id"),
                        "full_name",
                        name.trim(),
                        "phone",
                        phone.isBlank() ? null : phone.trim(),
                        "enabled",
                        enabled,
                        "grants_json",
                        json.json(grants),
                        "revoke",
                        enabled ? 0 : 1));
        return publicUser(user(tenant, ((Number) current.get("id")).longValue()));
    }
}
