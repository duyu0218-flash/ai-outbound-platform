package com.duozhi.services;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

final class RecordingStorage {
    private final Settings settings;
    private final S3Store store;
    private final Path spool, state;
    private final Semaphore slots;
    private final long maxBytes, reserve;
    private final int concurrency;
    private final String prefix;
    private final HttpClient client = Support.client();
    RecordingStorage(Settings settings) throws IOException {
        this.settings = settings; store = new S3Store(settings);
        String directory = settings.get("RECORDING_SPOOL_DIR", "");
        if (settings.production() && directory.isEmpty()) throw new IllegalArgumentException("production requires persistent recording spool");
        spool = directory.isEmpty() ? Files.createTempDirectory("java-recording-") : Path.of(directory); Files.createDirectories(spool);
        state = spool.resolve(".lifecycle"); Files.createDirectories(state);
        prefix = settings.get("S3_KEY_PREFIX", "recordings").replaceAll("^/+|/+$", "");
        if (prefix.length() > 256 || !prefix.matches("[A-Za-z0-9][A-Za-z0-9._/-]*") || Arrays.stream(prefix.split("/", -1)).anyMatch(v -> Set.of("", ".", "..").contains(v))) throw new IllegalArgumentException("invalid recording prefix");
        concurrency = (int) settings.number("RECORDING_INGEST_CONCURRENCY", 2, 1, 16); slots = new Semaphore(concurrency);
        maxBytes = settings.number("RECORDING_MAX_BYTES", 536870912, 1024, 10737418240L);
        reserve = settings.number("RECORDING_SPOOL_RESERVE_BYTES", 134217728, 0, Long.MAX_VALUE / 2);
        if (settings.list("RECORDING_SOURCE_ALLOWED_HOSTS").isEmpty()) throw new IllegalArgumentException("recording source allowlist is required");
        if (settings.production() && (settings.secret("SERVICE_TOKEN").length() < 24 || settings.secret("METRICS_TOKEN").length() < 24)) throw new IllegalArgumentException("production recording credentials must contain at least 24 characters");
    }
    void initialize() throws Exception { store.initialize(); }
    boolean ready() { return store.ready(); }
    String bucket() { return store.bucket(); }
    static String segment(Object value) {
        String result = String.valueOf(value).strip().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        if (result.isEmpty()) throw new Support.Failure(503, "recording object key contains an empty segment");
        return result.substring(0, Math.min(128, result.length()));
    }
    private Path stem(Map<String,Object> payload) {
        long tenant = Support.integer(payload, "tenant_id", 0), asset = Support.integer(payload, "recording_asset_id", 0);
        String call = Support.required(payload, "call_id", 128);
        if (tenant <= 0 || asset <= 0) throw new Support.Failure(422, "positive tenant and asset IDs required");
        return state.resolve(Support.hex(Support.sha((tenant + ":" + call + ":" + asset).getBytes(StandardCharsets.UTF_8))));
    }
    private Path suffix(Path stem, String extension) { return Path.of(stem + extension); }
    private FileLock lock(FileChannel channel) throws IOException {
        try { FileLock lock = channel.tryLock(); if (lock != null) return lock; }
        catch (OverlappingFileLockException busy) { }
        throw new Support.Failure(503, "recording asset operation is in progress; retry later");
    }
    URI source(String value) {
        URI source = Support.endpoint(value);
        if (settings.flag("RECORDING_SOURCE_REQUIRE_HTTPS", false) && !source.getScheme().equals("https")) throw new Support.Failure(400, "provider_url must use https");
        String host = Support.asciiLower(source.getHost()).replaceAll("\\.$", "");
        boolean allowed = settings.list("RECORDING_SOURCE_ALLOWED_HOSTS").stream().anyMatch(pattern -> pattern.startsWith("*.") ? host.endsWith(pattern.substring(1)) && !host.equals(pattern.substring(2)) : host.equals(pattern.replaceAll("\\.$", "")));
        if (!allowed) throw new Support.Failure(400, "provider_url host is not allowlisted");
        return source;
    }
    Map<String,Object> ingest(Map<String,Object> payload) throws Exception {
        Path stem = stem(payload);
        if (!slots.tryAcquire()) throw new Support.Failure(503, "recording ingestion capacity exhausted; retry later");
        try (var channel = FileChannel.open(suffix(stem, ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = lock(channel)) {
            long free = Files.getFileStore(spool).getUsableSpace();
            if (free < Math.addExact(Math.multiplyExact(maxBytes, concurrency), reserve)) throw new Support.Failure(503, "recording spool has insufficient free space");
            if (Files.exists(suffix(stem, ".deleted"))) throw new Support.Failure(503, "recording asset has a deletion tombstone");
            URI source = source(Support.required(payload, "provider_url", 8192));
            Path verified = suffix(stem, ".uploaded.json");
            if (!settings.get("RECORDING_SOURCE_CLEANUP_ORIGIN", "").isEmpty() && Files.exists(verified)) {
                Map<String,Object> saved = Support.object(Files.readAllBytes(verified));
                if (!Support.str(saved, "source", "").equals(source.toString().split("\\?", 2)[0])) throw new Support.Failure(503, "source identity changed");
                Map<String,Object> result = Support.map(saved.get("result")); reclaim(source, result); return result;
            }
            var response = client.send(HttpRequest.newBuilder(source).timeout(Duration.ofSeconds(settings.number("RECORDING_DOWNLOAD_TIMEOUT_SEC", 60, 1, 3600))).header("Accept", "audio/*, application/octet-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            Path temporary = Files.createTempFile(spool, ".recording-", ".part");
            try (var input = Support.timed(response.body(), settings.number("RECORDING_DOWNLOAD_TIMEOUT_SEC", 60, 1, 3600) * 1000)) {
                if (response.statusCode() / 100 != 2) throw new Support.Failure(502, "recording download failed");
                String type = Support.asciiLower(response.headers().firstValue("content-type").orElse("application/octet-stream").split(";", 2)[0].strip());
                if (!type.startsWith("audio/") && !type.equals("application/octet-stream")) throw new Support.Failure(502, "unsupported recording content type");
                if (response.headers().firstValueAsLong("content-length").orElse(0) > maxBytes) throw new Support.Failure(502, "recording exceeds configured maximum size");
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); long size = 0;
                try (var output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[65536]; for (int n; (n = input.read(buffer)) != -1;) {
                        size += n; if (size > maxBytes) throw new Support.Failure(502, "recording exceeds configured maximum size");
                        digest.update(buffer, 0, n); output.write(buffer, 0, n);
                    }
                }
                if (size == 0) throw new Support.Failure(502, "recording download was empty");
                long declaredSize = response.headers().firstValueAsLong("content-length").orElse(-1); if (declaredSize >= 0 && size != declaredSize) throw new Support.Failure(502, "recording download was truncated");
                String checksum = Support.hex(digest.digest());
                String extension = Map.of("audio/wav", ".wav", "audio/x-wav", ".wav", "audio/mpeg", ".mp3", "audio/ogg", ".ogg", "audio/flac", ".flac", "audio/basic", ".ulaw", "application/octet-stream", ".raw").getOrDefault(type, ".bin");
                String key = prefix + "/tenant-" + segment(payload.get("tenant_id")) + "/call-" + segment(payload.get("call_id")) + "/asset-" + segment(payload.get("recording_asset_id")) + extension;
                Path keyFile = suffix(stem, ".key");
                if (Files.exists(keyFile)) key = Files.readString(keyFile); else Support.persist(keyFile, key.getBytes(StandardCharsets.UTF_8));
                store.upload(key, temporary, type, Map.of("tenant-id", String.valueOf(payload.get("tenant_id")), "call-id", segment(payload.get("call_id")), "recording-asset-id", String.valueOf(payload.get("recording_asset_id")), "sha256", checksum));
                Map<String,Object> result = Map.of("storage_uri", "s3://" + store.bucket() + "/" + key, "checksum_sha256", checksum, "size_bytes", size);
                if (!settings.get("RECORDING_SOURCE_CLEANUP_ORIGIN", "").isEmpty()) {
                    Support.persist(verified, Support.json(Map.of("source", source.toString().split("\\?", 2)[0], "result", result))); reclaim(source, result);
                }
                return result;
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException error) { throw new Support.Failure(503, "recording storage or transport unavailable"); }
        finally { slots.release(); }
    }
    private void reclaim(URI source, Map<String,Object> result) throws Exception {
        URI origin = Support.endpoint(settings.get("RECORDING_SOURCE_CLEANUP_ORIGIN", ""));
        String filename = source.getPath().substring(source.getPath().lastIndexOf('/') + 1);
        if (!Objects.equals(origin.getScheme(), source.getScheme()) || !Objects.equals(origin.getRawAuthority(), source.getRawAuthority()) || !Set.of("", "/").contains(origin.getPath()) || !source.getPath().equals("/v1/recordings/" + filename) || !filename.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,240}\\.wav")) throw new Support.Failure(400, "source is outside the configured cleanup owner");
        String token = settings.secret("RECORDING_SOURCE_CLEANUP_TOKEN"); if (token.length() < 32) throw new Support.Failure(503, "independent cleanup credential is required");
        URI storage = URI.create(Support.str(result, "storage_uri", ""));
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); long size = 0, expected = Support.integer(result, "size_bytes", 0);
        try (var input = store.download(storage.getPath().substring(1))) {
            byte[] buffer = new byte[65536]; for (int n; (n = input.read(buffer)) != -1;) {
                size += n; if (size > expected) throw new Support.Failure(503, "remote recording size differs"); digest.update(buffer, 0, n);
            }
        }
        if (size != expected || !Support.hex(digest.digest()).equals(result.get("checksum_sha256"))) throw new Support.Failure(503, "remote recording checksum differs; source retained");
        var response = client.send(HttpRequest.newBuilder(URI.create(origin.toString().replaceAll("/+$", "") + "/v1/recording-cleanup/" + filename)).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(Support.json(Map.of("checksum_sha256", result.get("checksum_sha256"), "size_bytes", size)))).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
            if (response.statusCode() / 100 != 2 || !Boolean.TRUE.equals(Support.object(Support.read(input, 65536)).get("deleted"))) throw new Support.Failure(503, "source cleanup was not confirmed");
        }
    }
    boolean delete(Map<String,Object> payload) throws Exception {
        Path stem = stem(payload);
        try (var channel = FileChannel.open(suffix(stem, ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = lock(channel)) {
            Support.persist(suffix(stem, ".deleted"), "deleted".getBytes(StandardCharsets.UTF_8));
            Path key = suffix(stem, ".key"); if (Files.exists(key)) store.delete(Files.readString(key));
            String uri = Support.str(payload, "storage_uri", "");
            if (uri.isEmpty()) {
                if (!Support.str(payload, "provider_url", "").isEmpty() || !Support.str(payload, "provider_recording_id", "").isEmpty()) throw new Support.Failure(503, "managed storage_uri is missing; provider-side deletion is not supported by this adapter");
                return true;
            }
            URI parsed = URI.create(uri); String tenantPrefix = prefix + "/tenant-" + segment(payload.get("tenant_id")) + "/";
            String objectKey = parsed.getPath().substring(1);
            if (!"s3".equals(parsed.getScheme()) || !store.bucket().equals(parsed.getRawAuthority()) || !objectKey.startsWith(tenantPrefix) || parsed.getQuery() != null || parsed.getFragment() != null) throw new Support.Failure(503, "storage_uri is outside the managed tenant bucket or prefix");
            store.delete(objectKey); return true;
        } catch (IOException error) { throw new Support.Failure(503, "recording deletion unavailable"); }
    }
}
