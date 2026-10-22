package com.duozhi.services;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RealS3Test {
    @TempDir
    Path directory;
    @Test
    void realS3SigV4UploadReadAndDelete() throws Exception {
        String endpoint = System.getenv("JAVA_TEST_S3_ENDPOINT");
        Assumptions.assumeTrue(endpoint != null, "requires a dedicated local SeaweedFS test instance");
        URI uri = URI.create(endpoint);
        if (!"127.0.0.1".equals(uri.getHost()) && !"seaweedfs".equals(uri.getHost())) throw new IllegalArgumentException("only the isolated test S3 endpoint is allowed");
        byte[] audio = new byte[8192]; new Random(42).nextBytes(audio);
        var source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext("/recording.wav", request -> { request.getResponseHeaders().set("Content-Type", "audio/wav"); request.sendResponseHeaders(200, audio.length); request.getResponseBody().write(audio); request.close(); }); source.start();
        try {
            var settings = new Settings(Map.of("S3_ENDPOINT_URL", endpoint, "S3_ACCESS_KEY_ID", "java-isolated-access", "S3_SECRET_ACCESS_KEY", "java-isolated-secret", "S3_BUCKET", "java-isolated-recordings", "S3_KEY_PREFIX", "migration-check-" + UUID.randomUUID(), "RECORDING_SOURCE_ALLOWED_HOSTS", "127.0.0.1", "RECORDING_SPOOL_DIR", directory.toString(), "RECORDING_MAX_BYTES", "16384", "RECORDING_SPOOL_RESERVE_BYTES", "0"));
            var storage = new RecordingStorage(settings); storage.initialize(); assertTrue(storage.ready());
            var payload = new HashMap<String,Object>(Map.of("tenant_id", 1, "recording_asset_id", 1, "call_id", "synthetic-s3", "provider_url", "http://127.0.0.1:" + source.getAddress().getPort() + "/recording.wav"));
            var result = storage.ingest(payload); String key = URI.create(result.get("storage_uri").toString()).getPath().substring(1);
            try {
                try (var downloaded = new S3Store(settings).download(key)) { assertArrayEquals(audio, Support.read(downloaded, audio.length)); }
                assertEquals(Support.hex(Support.sha(audio)), result.get("checksum_sha256")); payload.put("storage_uri", result.get("storage_uri")); assertTrue(storage.delete(payload));
                assertThrows(Support.Failure.class, () -> new S3Store(settings).download(key)); assertThrows(Support.Failure.class, () -> storage.ingest(payload));
            } finally { new S3Store(settings).delete(key); }
        } finally { source.stop(0); }
    }
}
