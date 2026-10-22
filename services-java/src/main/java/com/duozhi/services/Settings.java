package com.duozhi.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Settings {
    private final Map<String,String> values;
    public Settings() { this(System.getenv()); }
    public Settings(Map<String,String> values) { this.values = Map.copyOf(values); }
    public String get(String key, String fallback) { return values.getOrDefault(key, fallback).strip(); }
    public boolean flag(String key, boolean fallback) {
        String value = get(key, Boolean.toString(fallback));
        if (Set.of("true", "1", "yes", "on").contains(Support.asciiLower(value))) return true;
        if (Set.of("false", "0", "no", "off").contains(Support.asciiLower(value))) return false;
        throw new IllegalArgumentException("invalid boolean setting: " + key);
    }
    public long number(String key, long fallback, long min, long max) {
        long value = Long.parseLong(get(key, Long.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException("setting out of range: " + key);
        return value;
    }
    public double decimal(String key, double fallback, double min, double max) {
        double value = Double.parseDouble(get(key, Double.toString(fallback)));
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("setting out of range: " + key);
        return value;
    }
    public boolean production() { return Set.of("prod", "production").contains(Support.asciiLower(get("ENV", "development"))); }
    public String secret(String key) {
        String file = get(key + "_FILE", "");
        if (file.isEmpty()) return get(key, "");
        try {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path) || Files.size(path) > 4096) throw new IllegalArgumentException("invalid secret file: " + key);
            try (var input = Files.newInputStream(path)) {
                byte[] bytes = input.readNBytes(4097);
                if (bytes.length > 4096) throw new IllegalArgumentException("secret exceeds limit: " + key);
                return new String(bytes, StandardCharsets.UTF_8).strip();
            }
        } catch (IOException error) { throw new IllegalArgumentException("unable to read secret: " + key); }
    }
    public Set<String> list(String key) {
        Set<String> result = new HashSet<>();
        for (String value : get(key, "").split(",")) if (!value.isBlank()) result.add(Support.asciiLower(value.strip()));
        return result;
    }
}
