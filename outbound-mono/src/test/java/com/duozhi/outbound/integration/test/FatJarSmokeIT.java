package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.*;
import java.net.ServerSocket;
import java.net.http.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class FatJarSmokeIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Test
    void packagedSingleProcessServesHealthAndBuiltVueWithoutOtherApplicationProcesses()
            throws Exception {
        Path folder = Path.of("target/fatjar-smoke");
        Files.createDirectories(folder);
        Path log = folder.resolve("application.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Set<String> builtAssets = new TreeSet<>();
        try (var assets = Files.walk(Path.of("src/main/resources/static"))) {
            assets.filter(Files::isRegularFile)
                    .forEach(
                            file ->
                                    builtAssets.add(
                                            "BOOT-INF/classes/static/"
                                                    + Path.of("src/main/resources/static")
                                                            .relativize(file)
                                                            .toString()
                                                            .replace('\\', '/')));
        }
        Set<String> packagedAssets = new TreeSet<>();
        try (var jar = new JarFile("target/outbound-mono.jar")) {
            jar.stream()
                    .filter(
                            entry ->
                                    !entry.isDirectory()
                                            && entry.getName()
                                                    .startsWith("BOOT-INF/classes/static/"))
                    .forEach(entry -> packagedAssets.add(entry.getName()));
            assertThat(jar.getEntry("BOOT-INF/classes/com/duozhi/outbound/embedded/Metrics.class"))
                    .as("removed manual Prometheus helper must not remain as stale bytecode")
                    .isNull();
        }
        assertThat(packagedAssets)
                .as(
                        "jar must contain exactly the current Vue build, without obsolete hashed"
                                + " assets")
                .isEqualTo(builtAssets);
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        ProcessBuilder command =
                new ProcessBuilder(
                        java,
                        "-XX:+UseG1GC",
                        "-XX:MaxGCPauseMillis=20",
                        "-Xms128m",
                        "-Xmx512m",
                        "-Xlog:gc*:file=target/fatjar-smoke/gc.log:time,uptime,level,tags",
                        "-jar",
                        "target/outbound-mono.jar",
                        "--server.port=" + port);
        command.redirectErrorStream(true).redirectOutput(log.toFile());
        var env = command.environment();
        env.put("ENV", "dev");
        env.put("JWT_SECRET", "fatjar-synthetic-secret-with-more-than-thirty-two-characters");
        env.put("MYSQL_JDBC_URL", MYSQL.getJdbcUrl());
        env.put("MYSQL_USER", MYSQL.getUsername());
        env.put("FLYWAY_USER", "root");
        env.put("FLYWAY_PASSWORD", MYSQL.getPassword());
        env.put("MYSQL_PASSWORD", MYSQL.getPassword());
        env.put("REDIS_HOST", "127.0.0.1");
        env.put("REDIS_PORT", "9");
        env.put("REDIS_URL", "");
        env.put("JAVA_WORKERS_ENABLED", "true");
        env.put("JAVA_DISPATCH_ENABLED", "true");
        env.put("DEMO_USERS_ENABLED", "true");
        env.put("DEMO_ADMIN_USERNAME", "fatjar-retry-admin");
        env.put("DEMO_ADMIN_PASSWORD", "SyntheticFatJarRetry123!");
        env.put("METRICS_TOKEN", "fatjar-synthetic-metrics-token-000000000000");
        Process process = command.start();
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            String origin = "http://127.0.0.1:" + port;
            boolean ready = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline && process.isAlive()) {
                try {
                    var response =
                            client.send(
                                    HttpRequest.newBuilder(URI.create(origin + "/healthz"))
                                            .timeout(Duration.ofSeconds(1))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        ready = true;
                        break;
                    }
                } catch (IOException starting) {
                    /* Wait for the listening socket. */
                }
                Thread.sleep(100);
            }
            assertThat(ready).as("fat jar HTTP startup: %s", Files.readString(log)).isTrue();
            var health =
                    client.send(
                            HttpRequest.newBuilder(URI.create(origin + "/healthz"))
                                    .timeout(Duration.ofSeconds(5))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).isEqualTo("{\"status\":\"ok\"}");
            var login =
                    client.send(
                            HttpRequest.newBuilder(URI.create(origin + "/admin/login")).build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(login.body()).contains("id=\"app\"");
            var asset = Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(login.body());
            assertThat(asset.find()).isTrue();
            var javascript =
                    client.send(
                            HttpRequest.newBuilder(URI.create(origin + asset.group(1))).build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(javascript.statusCode()).isEqualTo(200);
            assertThat(javascript.body().length()).isGreaterThan(10000);
            var denied =
                    client.send(
                            HttpRequest.newBuilder(URI.create(origin + "/metrics/runtime")).build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(401);
            var metrics =
                    client.send(
                            HttpRequest.newBuilder(URI.create(origin + "/metrics/runtime"))
                                    .header("Authorization", "Bearer " + env.get("METRICS_TOKEN"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(metrics.statusCode()).isEqualTo(200);
            assertThat(metrics.body())
                    .contains("ai_outbound_process_id", "ai_outbound_jvm_threads");
            // Tomcat's "Started" log precedes ApplicationRunner's development account seed.
            boolean seeded = false;
            try (var db =
                            DriverManager.getConnection(
                                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                    var q = db.prepareStatement("SELECT COUNT(*) FROM sys_user WHERE username=?")) {
                q.setString(1, env.get("DEMO_ADMIN_USERNAME"));
                for (int poll = 0; poll < 100; poll++) {
                    try (var rows = q.executeQuery()) {
                        rows.next();
                        seeded = rows.getInt(1) == 1;
                    }
                    if (seeded) break;
                    Thread.sleep(100);
                }
            }
            assertThat(seeded).as("development seed must exist before attempting login").isTrue();
            var retry =
                    new ProcessBuilder("sh", "tools/reproduce-retry.sh")
                            .redirectErrorStream(true)
                            .redirectOutput(folder.resolve("retry-http.log").toFile());
            retry.environment().put("MONO_RETRY_URL", origin);
            retry.environment().put("MONO_RETRY_USER", env.get("DEMO_ADMIN_USERNAME"));
            retry.environment().put("MONO_RETRY_PASSWORD", env.get("DEMO_ADMIN_PASSWORD"));
            var replay = retry.start();
            boolean finished = replay.waitFor(45, TimeUnit.SECONDS);
            if (!finished) replay.destroyForcibly();
            assertThat(finished)
                    .as(
                            "retry curl timeout; %s",
                            Files.readString(folder.resolve("retry-http.log")))
                    .isTrue();
            assertThat(replay.exitValue())
                    .as(Files.readString(folder.resolve("retry-http.log")))
                    .isZero();
            Files.writeString(
                    folder.resolve("result.json"),
                    "{\"fat_jar_started\":true,\"healthz\":200,\"vue_login\":200,\"vue_asset\":200,\"metrics_authenticated\":200,\"metrics_anonymous\":401,\"application_processes\":1,\"gc\":\"G1"
                            + " MaxGCPauseMillis=20; media load not performed\"}\n");
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
