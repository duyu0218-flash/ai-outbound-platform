package com.duozhi.outbound.client.service;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.client.dao.ClientStore;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Staff access to client organisations: administrators manage every client, delivery specialists
 * (agents listed in client_staff_scope) only the clients assigned to them.
 */
@Service
public class ClientAccess {
    public static final String PERMISSION = "admin:clients";
    private final ClientStore db;

    public ClientAccess(ClientStore db) {
        this.db = db;
    }

    public Identity staff(HttpServletRequest request) {
        Identity identity = Identity.from(request);
        if (identity.user() == null && !identity.permissions().contains(PERMISSION))
            throw new ApiError(403, "admin permission required");
        return identity;
    }

    public boolean admin(Identity identity) {
        return identity.user() == null || "admin".equals(identity.user().get("role"));
    }

    public Identity requireAdmin(HttpServletRequest request) {
        Identity identity = staff(request);
        if (!admin(identity)) throw new ApiError(403, "admin permission required");
        return identity;
    }

    /** Null for administrators; otherwise the user whose client scope limits listings. */
    public Object scopeUser(Identity identity) {
        return admin(identity) ? null : identity.userId();
    }

    public long actor(Identity identity) {
        return identity.userId() == null ? 0 : ((Number) identity.userId()).longValue();
    }

    public Map<String, Object> client(Identity identity, long clientId) {
        Map<String, Object> org =
                db.one(
                        "orgGet",
                        JsonSupport.map("tenantId", identity.tenant(), "clientId", clientId));
        if (org == null) throw new ApiError(404, "企业租户不存在");
        if (!admin(identity)
                && db.count(
                                "staffHas",
                                JsonSupport.map(
                                        "tenantId",
                                        identity.tenant(),
                                        "clientId",
                                        clientId,
                                        "userId",
                                        identity.userId()))
                        == 0) throw new ApiError(404, "企业租户不存在");
        return org;
    }
}
