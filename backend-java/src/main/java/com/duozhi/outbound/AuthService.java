package com.duozhi.outbound;

import java.time.LocalDateTime;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final Store store; private final Crypto crypto; private final Config config; private final AgentMedia media;
    public AuthService(Store store, Crypto crypto, Config config, AgentMedia media) { this.store=store; this.crypto=crypto; this.config=config; this.media=media; }
    @Transactional
    public Map<String,Object> login(String username, String password) {
        Map<String,Object> user = store.one("user", "WHERE username=?" + store.lock(), username);
        if (user == null || !Boolean.TRUE.equals(user.get("enabled"))) return null;
        LocalDateTime now = Store.now();
        if (user.get("locked_until") != null && LocalDateTime.parse(user.get("locked_until").toString()).isAfter(now)) return null;
        long tenant = ((Number)user.get("tenant_id")).longValue();
        LinkedHashMap<String,Object> changes = new LinkedHashMap<String,Object>();
        int failed = user.get("locked_until") == null ? ((Number)user.get("failed_login_attempts")).intValue() : 0;
        if (!crypto.verifyPassword(user.get("password_hash").toString(), password)) {
            changes.put("failed_login_attempts", failed+1);
            if (failed+1 >= Math.max(1, config.number("AUTH_MAX_FAILED_ATTEMPTS", 5))) changes.put("locked_until", now.plusSeconds(Math.max(30,config.number("AUTH_LOCKOUT_SECONDS", 900))));
            store.update("user", tenant, user.get("id"), changes);
            return null; // Return normally so failed-login counters commit.
        }
        changes.put("failed_login_attempts", 0); changes.put("locked_until", null); changes.put("last_login_at", now);
        if (!user.get("password_hash").toString().startsWith("pbkdf2_sha256$")) changes.put("password_hash", crypto.password(password));
        if ("agent".equals(user.get("role"))) {
            changes.put("agent_status", config.flag("WEBRTC_ENABLED", false) ? "offline" : "ready");
            changes.put("last_seen_at", now);
        }
        return store.update("user", tenant, user.get("id"), changes);
    }
    @Transactional
    public void logout(Identity identity) {
        Map<String,Object> user = identity.requireUser();
        media.clear(identity.tenant(), user.get("id"));
        // Atomic increment prevents concurrent logouts from losing revocations.
        store.jdbc().update("UPDATE \"user\" SET token_version=token_version+1,agent_status='offline',updated_at=? WHERE id=? AND tenant_id=?", Store.now(), user.get("id"), identity.tenant());
    }
    public static Map<String,Object> publicUser(Map<String,Object> user) {
        LinkedHashMap<String,Object> out = new LinkedHashMap<String,Object>();
        for (String key : Values.list("id","tenant_id","username","full_name","role","is_supervisor","agent_status","last_seen_at","enabled")) out.put(key,user.get(key));
        return out;
    }
}
