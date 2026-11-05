package com.duozhi.outbound.common.runtime;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 8)
public final class RuntimeOwnershipBoundary extends OncePerRequestFilter {
    private final RuntimeOwnership ownership;

    public RuntimeOwnershipBoundary(RuntimeOwnership ownership) {
        this.ownership = ownership;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (ownership.state() == RuntimeOwnership.State.FROZEN
                || ownership.state() == RuntimeOwnership.State.CLOSED) {
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"detail\":\"RUNTIME_OWNERSHIP_FROZEN\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
