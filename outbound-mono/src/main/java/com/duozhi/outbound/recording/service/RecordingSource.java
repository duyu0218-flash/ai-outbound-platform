package com.duozhi.outbound.recording.service;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.dao.LocalLedger;
import com.duozhi.outbound.integration.support.Support;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

public final class RecordingSource {
    private final Settings settings;

    public RecordingSource(Settings settings) {
        this.settings = settings;
    }

    public String signature(String filename, long expires) {
        String key = settings.secret("VOICE_COMMAND_SECRET");
        if (key.isEmpty()) throw new Support.Failure(503, "recording signing key unavailable");
        return Support.hmac(
                key,
                ("recording-source:"
                                + settings.get("VOICE_NODE_ID", "")
                                + ":"
                                + filename
                                + ":"
                                + expires)
                        .getBytes(StandardCharsets.UTF_8));
    }

    public String url(String filename) {
        long expires = System.currentTimeMillis() / 1000 + 86400;
        return settings.get("VOICE_RECORDING_SOURCE_BASE_URL", "").replaceAll("/+$", "")
                + "/v1/recordings/"
                + filename
                + "?expires="
                + expires
                + "&signature="
                + signature(filename, expires);
    }

    Path path(String filename) throws Exception {
        if (!filename.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,240}\\.wav"))
            throw new Support.Failure(400, "invalid source filename");
        Path root = Path.of(settings.get("FREESWITCH_RECORDING_DIR", "/recordings")).toRealPath(),
                path = root.resolve(filename);
        if (Files.isSymbolicLink(path) || !path.toAbsolutePath().getParent().equals(root))
            throw new Support.Failure(403, "source must be a regular owner-node recording");
        return path;
    }

    public Path authorized(String filename, long expires, String signature) throws Exception {
        long now = System.currentTimeMillis() / 1000;
        if (expires <= now
                || expires > now + 86400
                || !Support.equals(signature(filename, expires), signature))
            throw new Support.Failure(403, "invalid or expired recording download permit");
        Path path = path(filename);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new Support.Failure(404, "recording unavailable on owner node");
        return path;
    }

    public Map<String, Object> disk() {
        long reserve = settings.number("VOICE_RECORDING_RESERVE_BYTES", 0, 0, Long.MAX_VALUE);
        if (reserve == 0) return Map.of("enabled", false, "safe", true);
        try {
            long free =
                    Files.getFileStore(
                                    Path.of(
                                            settings.get(
                                                    "FREESWITCH_RECORDING_DIR", "/recordings")))
                            .getUsableSpace();
            return Map.of(
                    "enabled",
                    true,
                    "safe",
                    free >= reserve,
                    "free_bytes",
                    free,
                    "reserve_bytes",
                    reserve);
        } catch (Exception error) {
            return Map.of("enabled", true, "safe", false, "error", "source disk unavailable");
        }
    }

    public void complete(String filename) throws Exception {
        Path path = path(filename);
        if (!Files.isRegularFile(path)) return;
        Path marker = Path.of(path + ".complete");
        if (!Files.exists(marker))
            Support.persist(
                    marker,
                    Support.json(
                            Map.of(
                                    "completed_at",
                                    LocalLedger.now(),
                                    "size_bytes",
                                    Files.size(path))));
    }

    public Map<String, Object> reclaim(String filename, String checksum, long size)
            throws Exception {
        if (!checksum.matches("[a-f0-9]{64}") || size < 1)
            throw new Support.Failure(422, "invalid verified recording identity");
        Path path = path(filename), marker = Path.of(path + ".complete");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS))
            throw new Support.Failure(409, "recording has no PBX completion receipt");
        try (var channel =
                        FileChannel.open(
                                marker, StandardOpenOption.READ, StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            Map<String, Object> receipt = Support.object(Files.readAllBytes(marker));
            if (!Files.exists(path)) {
                if (checksum.equals(receipt.get("checksum_sha256"))
                        && size == Support.integer(receipt, "size_bytes", 0))
                    return Map.of("deleted", true);
                throw new Support.Failure(409, "missing source without matching deletion receipt");
            }
            if (LocalLedger.now() - ((Number) receipt.get("completed_at")).doubleValue()
                    < settings.number("VOICE_RECORDING_RETENTION_SEC", 0, 0, 315360000))
                throw new Support.Failure(409, "source retention period has not elapsed");
            var before =
                    Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = input.read(buffer)) != -1; ) digest.update(buffer, 0, n);
            }
            if (before.size() != size
                    || Support.integer(receipt, "size_bytes", 0) != size
                    || !Support.hex(digest.digest()).equals(checksum))
                throw new Support.Failure(409, "source differs from verified object");
            receipt.put("checksum_sha256", checksum);
            byte[] bytes = Support.json(receipt);
            channel.position(0);
            channel.truncate(0);
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
            var after =
                    Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!Objects.equals(before.fileKey(), after.fileKey())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || after.size() != before.size())
                throw new Support.Failure(409, "source changed during verification");
            Files.delete(path);
            try (var directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
            return Map.of("deleted", true);
        }
    }
}
