package com.duozhi.outbound.auth.security;

import com.duozhi.outbound.auth.dao.AuthIdentityMapper;
import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.auth.support.UserRecords;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.apache.shiro.authc.*;
import org.apache.shiro.authz.*;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;

public final class JwtRealm extends AuthorizingRealm {
    private final AuthIdentityMapper identities;
    private final Crypto crypto;
    private final Config config;
    private final ObjectMapper json;

    public JwtRealm(
            AuthIdentityMapper identities, Crypto crypto, Config config, ObjectMapper json) {
        this.identities = identities;
        this.crypto = crypto;
        this.config = config;
        this.json = json;
        setAuthenticationTokenClass(JwtCredentials.class);
        setAuthenticationCachingEnabled(false);
        setAuthorizationCachingEnabled(false);
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken supplied)
            throws AuthenticationException {
        try {
            var token = (JwtCredentials) supplied;
            Identity identity = authenticate(token);
            return new SimpleAuthenticationInfo(identity, token.getCredentials(), getName());
        } catch (ApiError error) {
            throw new AuthenticationException(error.getMessage(), error);
        }
    }

    private Identity authenticate(JwtCredentials token) {
        if (token.authorization != null) {
            if (!token.authorization.startsWith("Bearer "))
                throw new ApiError(401, "Invalid token");
            var claims = crypto.claims(token.authorization.substring(7));
            token.expiresAt = ((Number) claims.get("exp")).longValue();
            if (claims.containsKey("typ"))
                throw new ApiError(401, "Invalid token"); // client portal tokens stay isolated
            if (!(claims.get("uid") instanceof Number))
                throw new ApiError(401, "Invalid token payload");
            var row = identities.findByIdForJwt(((Number) claims.get("uid")).longValue());
            var user = UserRecords.map(row);
            if (row == null
                    || !Boolean.TRUE.equals(row.getEnabled())
                    || !Objects.equals(row.getUsername(), claims.get("sub")))
                throw new ApiError(401, "Invalid token");
            Object version = claims.getOrDefault("tv", 0);
            if (!(version instanceof Number)
                    || ((Number) version).longValue() != row.getTokenVersion().longValue())
                throw new ApiError(401, "Token has been revoked");
            return new Identity(row.getTenantId(), user, Set::of);
        }
        String key = token.apiKey;
        if (key == null || key.trim().isEmpty()) throw new ApiError(401, "invalid api key");
        long tenant = -1;
        try {
            Map<String, String> keys =
                    json.readValue(
                            config.get("TENANT_API_KEYS_JSON", "{}"),
                            new TypeReference<Map<String, String>>() {});
            for (var entry : keys.entrySet())
                if (Crypto.equal(key, entry.getValue())) tenant = Long.parseLong(entry.getKey());
        } catch (Exception error) {
            throw new ApiError(503, "tenant api key configuration is invalid");
        }
        if (tenant < 0
                && (Crypto.equal(key, config.get("API_KEY", ""))
                        || Crypto.equal(key, config.get("UI_API_KEY", ""))))
            tenant = config.number("DEFAULT_TENANT_ID", 1);
        if (tenant < 1) throw new ApiError(401, "invalid api key");
        long selectedTenant = tenant;
        // Original generic production write scopes remain for non-administrative routes.
        // Administrative routes now use their explicit permission under the approved API-key
        // correction.
        if (config.production()
                && !Set.of("GET", "HEAD", "OPTIONS").contains(token.method)
                && !token.path.startsWith("/api/v1/admin/")) {
            String path = token.path.replaceFirst("^/api/v1/", "");
            String scope =
                    Set.of("calls", "calls/batch", "calls/async").contains(path)
                            ? "calls:dial"
                            : path.startsWith("calls/")
                                    ? "calls:control"
                                    : path.split("/")[0] + ":write";
            if (!scopes(tenant).contains(scope))
                throw new ApiError(403, "machine API key lacks scope: " + scope);
        }
        return new Identity(tenant, null, () -> scopes(selectedTenant));
    }

    private Set<String> scopes(long tenant) {
        try {
            Map<String, List<String>> values =
                    json.readValue(
                            config.get("TENANT_API_SCOPES_JSON", "{}"),
                            new TypeReference<Map<String, List<String>>>() {});
            return Collections.unmodifiableSet(
                    new HashSet<>(values.getOrDefault("" + tenant, List.of())));
        } catch (Exception error) {
            throw new ApiError(503, "machine API scope configuration invalid");
        }
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        Identity identity = (Identity) principals.getPrimaryPrincipal();
        var info = new SimpleAuthorizationInfo();
        if (identity.user() != null) info.addRole(identity.user().get("role").toString());
        else info.setStringPermissions(identity.permissions());
        return info;
    }
}
