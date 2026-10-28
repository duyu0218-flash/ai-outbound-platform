package com.duozhi.outbound.integration.support;

import com.duozhi.outbound.integration.config.Settings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.*;
import java.io.IOException;
import java.net.*;
import java.net.SocketTimeoutException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

public final class Support {
    private Support() {}

    private static final ScheduledThreadPoolExecutor READ_TIMEOUTS = timeouts();

    private static ScheduledThreadPoolExecutor timeouts() {
        var executor =
                new ScheduledThreadPoolExecutor(
                        1,
                        run -> {
                            Thread thread = new Thread(run, "service-read-timeouts");
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public static final JsonMapper JSON = JsonMapper.builder().build();

    public static byte[] json(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON", e);
        }
    }

    public static byte[] canonical(Object value) {
        return json(sorted(value));
    }

    public static byte[] canonicalAscii(Object value) {
        String text = new String(canonical(value), StandardCharsets.UTF_8);
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 127) ascii.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else ascii.append(c);
        }
        return ascii.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), sorted(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(Support::sorted).toList();
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(byte[] bytes) {
        try {
            Object value = JSON.readValue(bytes, Object.class);
            if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        } catch (IOException invalid) {
            throw new Failure(422, "invalid JSON object");
        }
        throw new Failure(422, "invalid JSON object");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    public static String str(Map<String, Object> value, String key, String fallback) {
        Object item = value.get(key);
        return item == null ? fallback : String.valueOf(item);
    }

    public static boolean truthy(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) return false;
        if (value instanceof Number number) return number.doubleValue() != 0;
        if (value instanceof CharSequence text) return !text.isEmpty();
        if (value instanceof Collection<?> collection) return !collection.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return true;
    }

    public static long integer(Map<String, Object> value, String key, long fallback) {
        Object item = value.get(key);
        if (item == null) return fallback;
        if (!(item instanceof Number n) || n.doubleValue() != n.longValue())
            throw new Failure(422, "invalid integer: " + key);
        return n.longValue();
    }

    public static String required(Map<String, Object> value, String key, int max) {
        Object item = value.get(key);
        if (!(item instanceof String text) || text.isEmpty() || text.length() > max)
            throw new Failure(422, "invalid field: " + key);
        return text;
    }

    public static byte[] read(InputStream input, long max) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        long count = 0;
        for (int n; (n = input.read(buffer)) != -1; ) {
            count += n;
            if (count > max) throw new IOException("response exceeds maximum size");
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }

    public static InputStream timed(InputStream input, long milliseconds) {
        if (milliseconds < 1) throw new IllegalArgumentException("invalid read timeout");
        return new FilterInputStream(input) {
            private volatile boolean expired;

            private int bounded(Callable<Integer> read) throws IOException {
                if (expired) throw new SocketTimeoutException("upstream read timed out");
                var task =
                        READ_TIMEOUTS.schedule(
                                () -> {
                                    expired = true;
                                    Thread.ofVirtual()
                                            .name("service-read-close")
                                            .start(
                                                    () -> {
                                                        try {
                                                            input.close();
                                                        } catch (IOException ignored) {
                                                        }
                                                    });
                                },
                                milliseconds,
                                TimeUnit.MILLISECONDS);
                try {
                    int result = read.call();
                    if (expired) throw new SocketTimeoutException("upstream read timed out");
                    return result;
                } catch (IOException error) {
                    throw error;
                } catch (Exception error) {
                    throw new IOException("upstream read failed");
                } finally {
                    task.cancel(false);
                }
            }

            @Override
            public int read() throws IOException {
                return bounded(input::read);
            }

            @Override
            public int read(byte[] bytes, int offset, int count) throws IOException {
                return bounded(() -> input.read(bytes, offset, count));
            }
        };
    }

    public static byte[] sha(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    public static byte[] hmac(byte[] secret, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    public static String hmac(String secret, byte[] value) {
        return hex(hmac(secret.getBytes(StandardCharsets.UTF_8), value));
    }

    public static boolean equals(String first, String second) {
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    public static String asciiLower(String value) {
        StringBuilder folded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            folded.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return folded.toString();
    }

    public static URI endpoint(String value) {
        try {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
            if (!uri.getHost().chars().allMatch(c -> c < 128)) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException error) {
            throw new Failure(400, "invalid HTTP endpoint");
        }
    }

    public static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(
                        new ProxySelector() {
                            public List<Proxy> select(URI uri) {
                                return List.of(Proxy.NO_PROXY);
                            }

                            public void connectFailed(
                                    URI uri, SocketAddress address, IOException error) {}
                        })
                .build();
    }

    public static void persist(Path path, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".pending-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            try (var directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void auth(Settings settings, String key, String supplied, boolean optionalDev) {
        String expected = settings.secret(key);
        if (expected.isEmpty() && optionalDev && !settings.production()) return;
        if (expected.isEmpty() || !equals("Bearer " + expected, supplied == null ? "" : supplied))
            throw new Failure(401, "invalid service token");
    }

    public static final class Failure extends RuntimeException {
        public final int status;
        public final Map<String, String> headers;

        public Failure(int status, String detail) {
            this(status, detail, Map.of());
        }

        public Failure(int status, String detail, Map<String, String> headers) {
            super(detail);
            this.status = status;
            this.headers = Map.copyOf(headers);
        }
    }
}
