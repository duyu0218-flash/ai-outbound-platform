package com.duozhi.outbound.operations.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.runtime.ScheduledWork;
import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.config.UnifiedServiceSettings;
import com.duozhi.outbound.recording.storage.S3Store;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Private export objects; database rows retain authorization and expiry, never public URLs. */
@Service
public class ExportStorage implements AutoCloseable {
    public static final long MAX_BYTES = 12L * 1024 * 1024;
    private final Settings settings;
    private final Path directory;
    private final String mode;
    private S3Store s3;

    public ExportStorage(Config config) throws IOException {
        settings = new UnifiedServiceSettings(config, "recording");
        mode = settings.get("EXPORT_STORAGE_MODE", settings.production() ? "s3" : "local");
        if (!Set.of("local", "s3").contains(mode))
            throw new IllegalArgumentException("EXPORT_STORAGE_MODE must be local or s3");
        String configured = settings.get("EXPORT_STORAGE_DIR", "");
        if (settings.production() && mode.equals("local") && configured.isBlank())
            throw new IllegalArgumentException(
                    "local production exports require EXPORT_STORAGE_DIR");
        directory =
                Path.of(
                                configured.isBlank()
                                        ? (mode.equals("s3")
                                                ? System.getProperty("java.io.tmpdir")
                                                        + "/outbound-export-spool"
                                                : "data/exports")
                                        : configured)
                        .toAbsolutePath()
                        .normalize();
        Files.createDirectories(directory);
    }

    public int retentionHours() {
        return (int) settings.number("EXPORT_RETENTION_HOURS", 168, 1, 2160);
    }

    public Path temporary() throws IOException {
        return Files.createTempFile(directory, ".export-", ".part");
    }

    public String key(long tenant, String id) {
        if (tenant <= 0 || !id.matches("[0-9a-f-]{36}"))
            throw new IllegalArgumentException("invalid export key");
        return mode + ":exports/" + tenant + "/" + id;
    }

    private String object(String key) throws IOException {
        if (key == null || !key.matches("(?:local|s3):exports/[1-9][0-9]*/[0-9a-f-]{36}"))
            throw new IOException("invalid export object reference");
        return key.substring(key.indexOf(':') + 1);
    }

    private synchronized S3Store s3() {
        if (s3 == null) s3 = new S3Store(settings);
        return s3;
    }

    public void put(String key, Path file, String type) throws IOException, InterruptedException {
        String object = object(key);
        if (Files.size(file) > MAX_BYTES) throw new ApiError(422, "文件超过12MB，请缩小范围");
        if (key.startsWith("s3:")) {
            s3().initialize();
            s3().upload(object, file, type, Map.of("purpose", "private-export"));
        } else {
            Path target = directory.resolve(object);
            Files.createDirectories(target.getParent());
            Files.move(
                    file,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public InputStream open(String key) throws IOException {
        String object = object(key);
        try {
            return key.startsWith("s3:")
                    ? s3().download(object)
                    : Files.newInputStream(directory.resolve(object));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("export download interrupted", e);
        }
    }

    public void delete(String key) throws IOException, InterruptedException {
        String object = object(key);
        if (key.startsWith("s3:")) s3().delete(object);
        else Files.deleteIfExists(directory.resolve(object));
    }

    /** Crash leftovers are local spool files only; published objects follow database expiry. */
    public void cleanupPartials() throws IOException {
        try (var paths = Files.newDirectoryStream(directory, ".export-*.part")) {
            int scanned = 0;
            long before =
                    System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(2);
            for (var path : paths) {
                if (++scanned > 1000 || !ScheduledWork.withinBudget()) break;
                if (Files.getLastModifiedTime(path).toMillis() < before) Files.deleteIfExists(path);
            }
        }
    }

    public OutputStream bounded(Path file) throws IOException {
        return new Budget(MAX_BYTES).wrap(Files.newOutputStream(file));
    }

    /** Counts UTF-8 bytes while writing, including the uncompressed contents of custom ZIPs. */
    public static final class Budget {
        private final long max;
        private long written;

        public Budget(long max) {
            this.max = max;
        }

        public OutputStream wrap(OutputStream target) {
            return new FilterOutputStream(target) {
                private void reserve(int count) {
                    if (!ScheduledWork.withinBudget()) throw new ApiError(422, "导出超时，请缩小范围");
                    if (count > max - written) throw new ApiError(422, "文件超过12MB，请缩小范围");
                    written += count;
                }

                @Override
                public void write(int value) throws IOException {
                    reserve(1);
                    out.write(value);
                }

                @Override
                public void write(byte[] bytes, int offset, int count) throws IOException {
                    reserve(count);
                    out.write(bytes, offset, count);
                }
            };
        }
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        if (s3 != null) s3.close();
    }
}
