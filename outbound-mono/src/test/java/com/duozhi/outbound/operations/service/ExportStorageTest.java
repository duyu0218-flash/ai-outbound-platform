package com.duozhi.outbound.operations.service;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ExportStorageTest {
    @TempDir
    Path directory;

    private Config config() {
        return new Config(
                new MockEnvironment()
                        .withProperty("EXPORT_STORAGE_MODE", "local")
                        .withProperty("EXPORT_STORAGE_DIR", directory.toString()));
    }

    @Test
    void byteBudgetRejectsBeforeWritingOverLimitAndCountsUtf8AcrossMembers() throws Exception {
        var first = directory.resolve("first.csv");
        var second = directory.resolve("second.csv");
        var budget = new ExportStorage.Budget(7);
        try (var out = budget.wrap(Files.newOutputStream(first))) {
            out.write("测试".getBytes(StandardCharsets.UTF_8));
        }
        try (var out = budget.wrap(Files.newOutputStream(second))) {
            out.write(65);
            assertThatThrownBy(() -> out.write("中".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ApiError.class);
        }
        assertThat(Files.size(first) + Files.size(second)).isEqualTo(7);
    }

    @Test
    void localObjectsSurviveStorageRestartAndCannotEscapePrivateDirectory() throws Exception {
        String key;
        try (var storage = new ExportStorage(config())) {
            key = storage.key(12, UUID.randomUUID().toString());
            var file = storage.temporary();
            Files.writeString(file, "phone\n13100000000\n");
            storage.put(key, file, "text/csv");
            assertThat(Files.exists(file)).isFalse();
            assertThatThrownBy(() -> storage.open("local:../../credentials"))
                    .isInstanceOf(java.io.IOException.class);
        }
        try (var reopened = new ExportStorage(config());
                var input = reopened.open(key)) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("13100000000");
            reopened.delete(key);
            assertThatThrownBy(() -> reopened.open(key)).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    void cleanupOnlyRemovesOldUnpublishedParts() throws Exception {
        try (var storage = new ExportStorage(config())) {
            var old = storage.temporary();
            var current = storage.temporary();
            Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(8000)));
            String key = storage.key(13, UUID.randomUUID().toString());
            var published = storage.temporary();
            Files.writeString(published, "persist");
            storage.put(key, published, "text/csv");
            storage.cleanupPartials();
            assertThat(old).doesNotExist();
            assertThat(current).exists();
            try (var input = storage.open(key)) {
                assertThat(input.read()).isEqualTo('p');
            }
        }
    }
}
