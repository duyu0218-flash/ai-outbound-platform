package com.duozhi.outbound.marketing.common;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

/**
 * Marketing pages are operator tools: administrators, or API keys granted admin:marketing. Agents
 * have no access; publishing (match submission, task launch, journey publish) needs an admin.
 */
@Service
public class MarketingAccess {
    public static final String PERMISSION = "admin:marketing";

    public Identity operator(HttpServletRequest request) {
        Identity identity = Identity.from(request);
        if (identity.user() == null) {
            if (!identity.permissions().contains(PERMISSION))
                throw new ApiError(403, "admin permission required");
            return identity;
        }
        if (!"admin".equals(identity.user().get("role")))
            throw new ApiError(403, "admin permission required");
        return identity;
    }

    public long actor(Identity identity) {
        return identity.userId() == null ? 0 : ((Number) identity.userId()).longValue();
    }
}
