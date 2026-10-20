package com.duozhi.outbound;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class JsonSupport {
    private final ObjectMapper mapper;
    public JsonSupport(ObjectMapper mapper) { this.mapper = mapper; }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("invalid JSON", e); }
    }
    public Map<String, Object> object(String value) {
        try { return mapper.readValue(value, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { throw new ApiError(422, "invalid JSON object"); }
    }
    public String canonical(Object value, boolean spaced) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            ((Map<?, ?>)value).forEach((k, v) -> sorted.put(k.toString(), v));
            List<String> fields = new ArrayList<>();
            sorted.forEach((k, v) -> fields.add(json(k) + (spaced ? ": " : ":") + canonical(v, spaced)));
            return "{" + String.join(spaced ? ", " : ",", fields) + "}";
        }
        if (value instanceof Collection) {
            List<String> items = new ArrayList<>();
            for (Object item : (Collection<?>)value) items.add(canonical(item, spaced));
            return "[" + String.join(spaced ? ", " : ",", items) + "]";
        }
        return json(value);
    }
    public static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    public static String sha(String value) { return hex(digest(value)); }
    public static String hmac(String secret, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(value));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) value.put(pairs[i].toString(), pairs[i + 1]);
        return value;
    }
    public static int integer(Object value, int fallback) {
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException e) { return fallback; }
    }
}
