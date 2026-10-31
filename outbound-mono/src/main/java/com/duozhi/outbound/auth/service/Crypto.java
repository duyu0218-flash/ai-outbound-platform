package com.duozhi.outbound.auth.service;

import com.auth0.jwt.algorithms.Algorithm;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.Values;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.Base64;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

@Component
public class Crypto {
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private final ObjectMapper mapper;
    private final Config config;

    public Crypto(ObjectMapper mapper, Config config) {
        this.mapper = mapper;
        this.config = config;
    }

    public static boolean equal(String a, String b) {
        return a != null
                && b != null
                && MessageDigest.isEqual(
                        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] derive(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    public String password(String raw) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            Base64.Encoder encoder = Base64.getUrlEncoder();
            return "pbkdf2_sha256$600000$"
                    + encoder.encodeToString(salt)
                    + "$"
                    + encoder.encodeToString(derive(raw, salt, 600000));
        } catch (Exception e) {
            throw new IllegalStateException("password hashing failed", e);
        }
    }

    public boolean verifyPassword(String stored, String raw) {
        try {
            if (!stored.startsWith("pbkdf2_sha256$")) {
                byte[] hash =
                        MessageDigest.getInstance("SHA-256")
                                .digest((secret() + ":" + raw).getBytes(StandardCharsets.UTF_8));
                return equal(stored, hex(hash));
            }
            String[] parts = stored.split("\\$", -1);
            if (parts.length != 4) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 100000 || iterations > 2000000) return false;
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            return MessageDigest.isEqual(
                    expected, derive(raw, Base64.getUrlDecoder().decode(parts[2]), iterations));
        } catch (Exception e) {
            return false;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) {
            text.append(Character.forDigit((b >>> 4) & 15, 16));
            text.append(Character.forDigit(b & 15, 16));
        }
        return text.toString();
    }

    private String secret() {
        String secret = config.get("JWT_SECRET", "");
        if (secret.trim().isEmpty()) throw new IllegalStateException("JWT_SECRET is required");
        return secret;
    }

    private byte[] sign(String data) throws Exception {
        return Algorithm.HMAC256(secret()).sign(data.getBytes(StandardCharsets.US_ASCII));
    }

    public String token(Map<String, Object> user) {
        return signed(
                Values.map(
                        "sub",
                        user.get("username"),
                        "uid",
                        user.get("id"),
                        "tenant_id",
                        user.get("tenant_id"),
                        "role",
                        user.get("role"),
                        "tv",
                        user.get("token_version"),
                        "exp",
                        Instant.now().getEpochSecond() + config.number("JWT_TTL_SECONDS", 43200)));
    }

    /** Client portal token: typed so the staff realm rejects it and vice versa. */
    public String clientToken(Map<String, Object> user) {
        return signed(
                Values.map(
                        "typ",
                        "client",
                        "sub",
                        user.get("username"),
                        "cuid",
                        user.get("id"),
                        "cid",
                        user.get("client_id"),
                        "tenant_id",
                        user.get("tenant_id"),
                        "tv",
                        user.get("token_version"),
                        "exp",
                        Instant.now().getEpochSecond()
                                + config.number("CLIENT_JWT_TTL_SECONDS", 28800)));
    }

    private String signed(Map<String, Object> claims) {
        try {
            String body =
                    URL.encodeToString(
                                    mapper.writeValueAsBytes(
                                            Values.map("alg", "HS256", "typ", "JWT")))
                            + "."
                            + URL.encodeToString(mapper.writeValueAsBytes(claims));
            return body + "." + URL.encodeToString(sign(body));
        } catch (Exception e) {
            throw new IllegalStateException("token signing failed", e);
        }
    }

    public Map<String, Object> claims(String token) {
        try {
            if (token.length() > 8192) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3
                    || !MessageDigest.isEqual(
                            sign(parts[0] + "." + parts[1]),
                            Base64.getUrlDecoder().decode(parts[2])))
                throw new IllegalArgumentException();
            Map<String, Object> header =
                    mapper.readValue(
                            Base64.getUrlDecoder().decode(parts[0]),
                            new TypeReference<Map<String, Object>>() {});
            if (!"HS256".equals(header.get("alg"))) throw new IllegalArgumentException();
            Map<String, Object> claims =
                    mapper.readValue(
                            Base64.getUrlDecoder().decode(parts[1]),
                            new TypeReference<Map<String, Object>>() {});
            long now = Instant.now().getEpochSecond();
            if (!(claims.get("exp") instanceof Number)
                    || ((Number) claims.get("exp")).longValue() <= now)
                throw new IllegalArgumentException();
            if (claims.get("nbf") instanceof Number
                    && ((Number) claims.get("nbf")).longValue() > now)
                throw new IllegalArgumentException();
            return claims;
        } catch (Exception e) {
            throw new ApiError(401, "Invalid token");
        }
    }
}
