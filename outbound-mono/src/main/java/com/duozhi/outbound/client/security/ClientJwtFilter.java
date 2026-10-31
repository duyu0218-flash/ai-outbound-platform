package com.duozhi.outbound.client.security;

import com.duozhi.outbound.common.exception.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.util.Map;
import org.apache.shiro.web.filter.AccessControlFilter;

/** Accepts only typed client portal bearer tokens; staff tokens and API keys are refused. */
public final class ClientJwtFilter extends AccessControlFilter {
    private final ClientAuthenticator clients;
    private final ObjectMapper json;

    public ClientJwtFilter(ClientAuthenticator clients, ObjectMapper json) {
        this.clients = clients;
        this.json = json;
    }

    @Override
    protected boolean isAccessAllowed(
            ServletRequest request, ServletResponse response, Object mapped) {
        return false;
    }

    @Override
    protected boolean onAccessDenied(ServletRequest rawRequest, ServletResponse rawResponse)
            throws Exception {
        var request = (HttpServletRequest) rawRequest;
        var response = (HttpServletResponse) rawResponse;
        response.setHeader("Cache-Control", "no-store");
        try {
            if (request.getHeader("x-api-key") != null)
                throw new ApiError(403, "client portal requires a client account");
            request.setAttribute(
                    ClientPrincipal.ATTRIBUTE,
                    clients.authenticate(
                            request.getHeader("Authorization"), request.getRequestURI()));
            return true;
        } catch (ApiError error) {
            // A valid staff token is a different principal, not a missing credential.
            int status = error.status == 401 && staff(request) ? 403 : error.status;
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getOutputStream(), Map.of("detail", error.getMessage()));
            return false;
        }
    }

    private boolean staff(HttpServletRequest request) {
        String value = request.getHeader("Authorization");
        if (value == null || !value.startsWith("Bearer ")) return false;
        try {
            String[] parts = value.substring(7).split("\\.");
            if (parts.length != 3) return false;
            var claims = json.readTree(java.util.Base64.getUrlDecoder().decode(parts[1]));
            return claims.has("uid") && !claims.has("typ");
        } catch (Exception e) {
            return false;
        }
    }
}
