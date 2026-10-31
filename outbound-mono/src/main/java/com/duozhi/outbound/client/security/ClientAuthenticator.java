package com.duozhi.outbound.client.security;

import com.duozhi.outbound.auth.dao.AuthIdentityMapper;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.client.dao.ClientStore;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ClientAuthenticator {
    /** Paths a client account may use before replacing its one-time password. */
    private static final Set<String> FIRST_LOGIN =
            Set.of(
                    "/api/v1/client/me",
                    "/api/v1/client/auth/password",
                    "/api/v1/client/auth/logout");

    private final ClientStore db;
    private final Crypto crypto;
    private final Config config;
    private final AuthIdentityMapper identities;
    private final JsonSupport json;

    public ClientAuthenticator(
            ClientStore db,
            Crypto crypto,
            Config config,
            AuthIdentityMapper identities,
            JsonSupport json) {
        this.db = db;
        this.crypto = crypto;
        this.config = config;
        this.identities = identities;
        this.json = json;
    }

    public boolean enabled() {
        return config.flag("CLIENT_PORTAL_ENABLED", true);
    }

    /** Business time zone for contract days and date filters. */
    public ZoneId zone() {
        return ZoneId.of(config.get("CLIENT_TIMEZONE", "Asia/Shanghai"));
    }

    public LocalDate today() {
        return LocalDate.now(zone());
    }

    /** Client organisation may sign in and receive deliveries: enabled and inside its contract. */
    public boolean active(Map<String, Object> org) {
        if (org == null || !"active".equals(org.get("status"))) return false;
        LocalDate today = today();
        if (date(org.get("contract_start")) instanceof LocalDate start && today.isBefore(start))
            return false;
        return !(date(org.get("contract_end")) instanceof LocalDate end && today.isAfter(end));
    }

    static LocalDate date(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(value.toString().substring(0, 10));
    }

    public void requireTenant(long tenant) {
        var row = identities.findTenantById(tenant);
        if (row == null || !Boolean.TRUE.equals(row.getEnabled()))
            throw new ApiError(403, "tenant disabled");
    }

    public ClientPrincipal authenticate(String authorization, String path) {
        if (!enabled()) throw new ApiError(404, "Not Found");
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new ApiError(401, "Bearer token required");
        Map<String, Object> claims = crypto.claims(authorization.substring(7));
        if (!"client".equals(claims.get("typ"))
                || !(claims.get("cuid") instanceof Number uid)
                || !(claims.get("cid") instanceof Number cid)
                || !(claims.get("tv") instanceof Number version))
            throw new ApiError(401, "Invalid token");
        Map<String, Object> user = db.one("userById", JsonSupport.map("userId", uid.longValue()));
        if (user == null
                || !Boolean.TRUE.equals(user.get("enabled"))
                || !Objects.equals(user.get("username"), claims.get("sub"))
                || ((Number) user.get("client_id")).longValue() != cid.longValue())
            throw new ApiError(401, "Invalid token");
        if (((Number) user.get("token_version")).longValue() != version.longValue())
            throw new ApiError(401, "Token has been revoked");
        long tenant = ((Number) user.get("tenant_id")).longValue();
        requireTenant(tenant);
        Map<String, Object> org =
                db.one("orgGet", JsonSupport.map("tenantId", tenant, "clientId", cid.longValue()));
        if (!active(org)) throw new ApiError(401, "企业账号已停用或合同已到期");
        if (Boolean.TRUE.equals(user.get("must_change_password")) && !FIRST_LOGIN.contains(path))
            throw new ApiError(403, "首次登录请先修改密码");
        return new ClientPrincipal(
                tenant,
                cid.longValue(),
                uid.longValue(),
                user.get("username").toString(),
                user.get("role").toString(),
                json.object(Objects.toString(user.get("grants_json"), "{}")));
    }
}
