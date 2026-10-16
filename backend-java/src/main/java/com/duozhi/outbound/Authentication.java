package com.duozhi.outbound;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

@Component
public class Authentication extends OncePerRequestFilter {
    private final Store store; private final Crypto crypto; private final Config config; private final ObjectMapper mapper;
    public Authentication(Store store, Crypto crypto, Config config, ObjectMapper mapper) {
        this.store = store; this.crypto = crypto; this.config = config; this.mapper = mapper;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "same-origin");
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || Values.set("/api/v1/runtime", "/api/v1/auth/login", "/api/v1/webhooks/telephony/status", "/api/v1/webhooks/telephony/speech", "/api/v1/webhooks/telephony/transcript", "/api/v1/webhooks/telephony/media", "/api/v1/webhooks/telephony/recording", "/api/v1/webhooks/sms/status", "/api/v1/webhooks/telephony/dtmf", "/api/v1/webhooks/telephony/batch").contains(path)) {
            chain.doFilter(request, response); return;
        }
        response.setHeader("Cache-Control", "no-store");
        try {
            com.duozhi.outbound.Identity identity = authenticate(request);
            String requestedTenant = request.getHeader("x-tenant-id");
            if (requestedTenant != null && !requestedTenant.equals(Long.toString(identity.tenant()))) throw new ApiError(403, "tenant scope mismatch");
            java.util.Map<java.lang.String,java.lang.Object> tenant = store.one("tenant", "WHERE id=?", identity.tenant());
            if (tenant == null || !Boolean.TRUE.equals(tenant.get("enabled"))) throw new ApiError(403, "tenant disabled");
            if (path.startsWith("/api/v1/admin/")) { identity.requireUser(); identity.admin(); }
            request.setAttribute("identity", identity);
            chain.doFilter(request, response);
        } catch (ApiError e) {
            response.setStatus(e.status); response.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(response.getOutputStream(), Values.map("detail", e.getMessage()));
        }
    }
    private Identity authenticate(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            if (!authorization.startsWith("Bearer ")) throw new ApiError(401, "Invalid token");
            java.util.Map<java.lang.String,java.lang.Object> claims = crypto.claims(authorization.substring(7));
            request.setAttribute("token_expires_at", ((Number)claims.get("exp")).longValue());
            if (!(claims.get("uid") instanceof Number)) throw new ApiError(401, "Invalid token payload");
            java.util.Map<java.lang.String,java.lang.Object> user = store.one("user", "WHERE id=?", ((Number)claims.get("uid")).longValue());
            if (user == null || !Boolean.TRUE.equals(user.get("enabled")) || !Objects.equals(user.get("username"), claims.get("sub"))) throw new ApiError(401, "Invalid token");
            if (!(claims.getOrDefault("tv", 0) instanceof Number) || ((Number)claims.getOrDefault("tv",0)).longValue() != ((Number)user.get("token_version")).longValue()) throw new ApiError(401, "Token has been revoked");
            return new Identity(((Number)user.get("tenant_id")).longValue(), user);
        }
        String key = request.getHeader("x-api-key");
        if (key == null || key.trim().isEmpty()) throw new ApiError(401, "invalid api key");
        long tenant = -1;
        try {
            Map<String,String> keys = mapper.readValue(config.get("TENANT_API_KEYS_JSON", "{}"), new TypeReference<Map<String,String>>() {});
            for (java.util.Map.Entry<java.lang.String,java.lang.String> entry : keys.entrySet()) if (Crypto.equal(key, entry.getValue())) tenant = Long.parseLong(entry.getKey());
        } catch (Exception e) { throw new ApiError(503, "tenant api key configuration is invalid"); }
        if (tenant < 0 && (Crypto.equal(key, config.get("API_KEY", "")) || Crypto.equal(key, config.get("UI_API_KEY", "")))) tenant = config.number("DEFAULT_TENANT_ID", 1);
        if (tenant < 1) throw new ApiError(401, "invalid api key");
        if (config.production() && !Values.set("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
            try {
                Map<String,List<String>> scopes = mapper.readValue(config.get("TENANT_API_SCOPES_JSON", "{}"), new TypeReference<Map<String,List<String>>>() {});
                String path = request.getRequestURI().replaceFirst("^/api/v1/", "");
                String scope = path.equals("calls") ? "calls:dial" : path.startsWith("calls/") ? "calls:control" : path.split("/")[0] + ":write";
                if (!scopes.getOrDefault("" + tenant, Values.list()).contains(scope)) throw new ApiError(403, "machine API key lacks scope: " + scope);
            } catch (ApiError e) { throw e; } catch (Exception e) { throw new ApiError(503, "machine API scope configuration invalid"); }
        }
        return new Identity(tenant, null);
    }
}
