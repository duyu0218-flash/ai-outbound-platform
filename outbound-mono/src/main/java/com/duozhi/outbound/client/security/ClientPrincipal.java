package com.duozhi.outbound.client.security;

import com.duozhi.outbound.common.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Authenticated client portal account. Deliberately unrelated to Identity: a null Identity user
 * means a tenant API key elsewhere, which would grant tenant-wide reads.
 */
public record ClientPrincipal(
        long tenant,
        long clientId,
        long userId,
        String username,
        String role,
        Map<String, Object> grants) {
    public static final String ATTRIBUTE = "client_principal";

    public static ClientPrincipal from(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof ClientPrincipal p) return p;
        throw new ApiError(401, "authentication required");
    }

    public boolean owner() {
        return "owner".equals(role);
    }

    public void requireOwner() {
        if (!owner()) throw new ApiError(403, "企业管理员权限不足");
    }

    /** Members only see leads assigned to them; owners see the whole client. */
    public Long assigneeFilter() {
        return owner() ? null : userId;
    }

    public boolean granted(String name) {
        return owner() || Boolean.TRUE.equals(grants.get(name));
    }
}
