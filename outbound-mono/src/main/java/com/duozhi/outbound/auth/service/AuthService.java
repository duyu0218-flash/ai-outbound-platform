package com.duozhi.outbound.auth.service;

import com.duozhi.outbound.auth.dao.AuthIdentityMapper;
import com.duozhi.outbound.auth.dao.SysUserMapper;
import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.auth.domain.SysUser;
import com.duozhi.outbound.auth.support.UserRecords;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.ExpiringValues;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final AuthIdentityMapper identities;
    private final SysUserMapper users;
    private final Crypto crypto;
    private final Config config;
    private final ExpiringValues media;

    public AuthService(
            AuthIdentityMapper identities,
            SysUserMapper users,
            Crypto crypto,
            Config config,
            ExpiringValues media) {
        this.identities = identities;
        this.users = users;
        this.crypto = crypto;
        this.config = config;
        this.media = media;
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    @Transactional
    public Map<String, Object> login(String username, String password) {
        SysUser user = identities.findByUsernameForLogin(username);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) return null;
        LocalDateTime now = now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) return null;
        int failed = user.getLockedUntil() == null ? user.getFailedLoginAttempts() : 0;
        var changes = new LinkedHashMap<String, Object>();
        if (!crypto.verifyPassword(user.getPasswordHash(), password)) {
            changes.put("failed_login_attempts", failed + 1);
            if (failed + 1 >= Math.max(1, config.number("AUTH_MAX_FAILED_ATTEMPTS", 5)))
                changes.put(
                        "locked_until",
                        now.plusSeconds(Math.max(30, config.number("AUTH_LOCKOUT_SECONDS", 900))));
            patch(user.getId(), user.getTenantId(), changes);
            return null;
        }
        changes.put("failed_login_attempts", 0);
        changes.put("locked_until", null);
        changes.put("last_login_at", now);
        if (!user.getPasswordHash().startsWith("pbkdf2_sha256$"))
            changes.put("password_hash", crypto.password(password));
        if ("agent".equals(user.getRole())) {
            changes.put("agent_status", config.flag("WEBRTC_ENABLED", false) ? "offline" : "ready");
            changes.put("last_seen_at", now);
        }
        return patch(user.getId(), user.getTenantId(), changes);
    }

    public Map<String, Object> patch(long id, int tenant, Map<String, Object> changes) {
        if (id < Integer.MIN_VALUE
                || id > Integer.MAX_VALUE
                || users.findById((int) id, tenant) == null)
            throw new ApiError(404, "user not found");
        identities.updateUser(id, tenant, changes, now());
        return UserRecords.map(users.findById((int) id, tenant));
    }

    @Transactional
    public void logout(Identity identity) {
        Map<String, Object> user = identity.requireUser();
        media.delete("ai-outbound:agent-media:" + identity.tenant() + ":" + user.get("id"));
        identities.revoke(((Number) user.get("id")).longValue(), (int) identity.tenant(), now());
    }

    @Transactional
    public Map<String, Object> presence(Identity identity, String status) {
        Map<String, Object> actor = identity.requireUser();
        if (!"agent".equals(actor.get("role")))
            throw new ApiError(409, "presence is only available for agents");
        if (status.equals("ready") && config.flag("WEBRTC_ENABLED", false)) {
            String raw =
                    media.get(
                            "ai-outbound:agent-media:" + identity.tenant() + ":" + actor.get("id"));
            try {
                var state =
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(raw == null ? "{}" : raw);
                if (!"registered".equals(state.path("registration_state").asText())
                        || !"granted".equals(state.path("microphone_permission").asText()))
                    throw new ApiError(
                            409,
                            "SIP registration and microphone readiness are required before"
                                    + " accepting calls");
            } catch (java.io.IOException invalid) {
                throw new ApiError(409, "SIP media registration is invalid");
            }
        }
        if (status.equals("ready") && identitiesBusy(identity)) status = "busy";
        return publicUser(
                patch(
                        ((Number) actor.get("id")).longValue(),
                        (int) identity.tenant(),
                        Map.of("agent_status", status, "last_seen_at", now())));
    }

    private boolean identitiesBusy(Identity identity) {
        return users.activeAgentCalls(
                        Map.of("tenantId", identity.tenant(), "agentId", identity.userId()))
                > 0;
    }

    public static Map<String, Object> publicUser(Map<String, Object> user) {
        var out = new LinkedHashMap<String, Object>();
        for (String key :
                List.of(
                        "id",
                        "tenant_id",
                        "username",
                        "full_name",
                        "role",
                        "is_supervisor",
                        "agent_status",
                        "last_seen_at",
                        "enabled")) out.put(key, user.get(key));
        return out;
    }
}
