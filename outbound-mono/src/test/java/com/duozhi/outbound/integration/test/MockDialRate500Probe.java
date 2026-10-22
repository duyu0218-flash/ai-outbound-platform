package com.duozhi.outbound.integration.test;

import static org.junit.jupiter.api.Assertions.*;

import com.duozhi.outbound.ai.service.ConversationPolicy;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.common.support.Values;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.*;
import org.testcontainers.containers.*;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Explicit opt-in. Measures dial acknowledgements, never substitutes API acceptance or AI turns for
 * CPS.
 */
class MockDialRate500Probe {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final Map<String, Object> result = new LinkedHashMap<>();
    final String run = UUID.randomUUID().toString().substring(0, 8);
    final int seconds = Integer.getInteger("dial.seconds", 5);
    final String SECRET = "isolated-dialrate-jwt-secret-with-more-than-32-characters",
            HOOK = "isolated-dialrate-hook-secret-with-more-than-32-characters";
    final int dbPool = Integer.getInteger("dial.db.pool", 4);
    final int poolSize = Integer.getInteger("dial.pool", 10000);
    final int warmup = Integer.getInteger("dial.warmup.seconds", 1);
    final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
    Path folder;
    String origin, token, jdbc, dbUser, dbPassword;
    Process application;

    @Test
    void dialPathActuallyStarts500NewMockCallsPerSecond() throws Exception {
        run(false);
    }

    void run(boolean calibration) throws Exception {
        run(calibration, false);
    }

    void run(boolean calibration, boolean drainDiagnostic) throws Exception {
        assertTrue(dbPool >= 1 && dbPool <= 32, "Bounded explicit business JDBC pool");
        assertTrue(
                seconds >= 1 && seconds <= 300 && warmup >= 0 && warmup <= 60,
                "Explicit bounded measurement and warmup durations");
        boolean queued = "queue".equals(System.getProperty("dial.source", "http"));
        assertTrue(Set.of("queue", "http").contains(System.getProperty("dial.source", "http")));
        if (drainDiagnostic) {
            assertTrue(queued, "stop-and-drain diagnostic requires an explicit queued cohort");
            assertFalse(calibration, "calibration and stop-and-drain are separate workloads");
        }
        folder = Path.of(System.getProperty("dial.output", "verification/dial500-" + run));
        assertFalse(Files.exists(folder));
        Files.createDirectories(folder);
        Path jar = Path.of("target/outbound-mono.jar");
        result.put(
                "scope",
                (queued
                                ? "Preloaded number pool with real durable scheduling"
                                : "Full HTTP creation and admission")
                        + " / real MySQL+Redis / embedded mock telephony. No real SIP, RTP, audio,"
                        + " cloud models or public numbers.");
        result.put("target_cps", 500);
        result.put("seconds", seconds);
        result.put(
                "jar_sha256",
                HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(jar))));
        result.put(
                "dial_source",
                queued
                        ? "Preloaded QUEUED pool; real enqueueDue/outbox/admission/mock gateway, no"
                                + " HTTP creation requests"
                        : "Fixed-rate full HTTP call creation/admission/mock gateway");
        result.put("drain_diagnostic_only", drainDiagnostic);
        result.put("calibration_only", calibration);
        result.put("capacity_qualified", false);
        if (calibration) assertTrue(queued, "storage calibration requires a finite queued cohort");
        var futures = new ArrayList<CompletableFuture<?>>();
        var codes = new ConcurrentHashMap<Integer, AtomicInteger>();
        var missed = new AtomicInteger();
        var errors = new ConcurrentHashMap<String, String>();
        try {
            try (var mysql = new MySQLContainer("mysql:8.4");
                    var redis =
                            new GenericContainer<>(DockerImageName.parse("redis:7"))
                                    .withExposedPorts(6379)) {
                CapacityDatabaseProfile.apply(mysql, result);
                mysql.start();
                redis.start();
                jdbc = mysql.getJdbcUrl() + "?connectTimeout=5000&socketTimeout=15000";
                dbUser = mysql.getUsername();
                dbPassword = mysql.getPassword();
                result.put(
                        "mysql_actual_variables",
                        CapacityDatabaseProfile.actual(jdbc, dbUser, dbPassword));
                result.put("mysql_container", mysql.getContainerId());
                result.put("redis_container", redis.getContainerId());
                result.put("mysql_jdbc", jdbc);
                result.put("profile_jfr", Boolean.getBoolean("capacity.jfr"));
                write();
                try {
                    int port;
                    try (var reservation =
                            new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                        port = reservation.getLocalPort();
                    }
                    origin = "http://127.0.0.1:" + port;
                    var command =
                            new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                    "-Xms512m",
                                    "-Xmx2g",
                                    "-XX:+UseG1GC",
                                    "-XX:MaxGCPauseMillis=20",
                                    "-XX:NativeMemoryTracking=summary",
                                    "-Xlog:gc*:file="
                                            + folder.resolve("gc.log")
                                            + ":time,uptime,level,tags",
                                    "-jar",
                                    jar.toString(),
                                    "--server.port=" + port);
                    if (Boolean.getBoolean("capacity.jfr")) {
                        command.command().add(1, "-XX:FlightRecorderOptions=stackdepth=128");
                        command.command()
                                .add(
                                        1,
                                        "-XX:StartFlightRecording=filename="
                                                + folder.resolve("profile.jfr")
                                                + ",settings=profile,dumponexit=true");
                    }
                    command.redirectErrorStream(true)
                            .redirectOutput(folder.resolve("application.log").toFile());
                    var env = command.environment();
                    Map<String, String> cfg = new LinkedHashMap<>();
                    cfg.put("SPRING_DATASOURCE_DRUID_MAX_ACTIVE", "" + dbPool);
                    cfg.put("ENV", "dev");
                    cfg.put("JWT_SECRET", SECRET);
                    cfg.put("DEMO_USERS_ENABLED", "false");
                    cfg.put("MYSQL_JDBC_URL", jdbc);
                    cfg.put("MYSQL_USER", dbUser);
                    cfg.put("FLYWAY_USER", "root");
                    cfg.put("FLYWAY_PASSWORD", dbPassword);
                    cfg.put("MYSQL_PASSWORD", dbPassword);
                    cfg.put("REDIS_HOST", redis.getHost());
                    cfg.put("REDIS_PORT", "" + redis.getMappedPort(6379));
                    cfg.put(
                            "REDIS_URL",
                            "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
                    cfg.put("JAVA_WORKERS_ENABLED", "true");
                    cfg.put("JAVA_DISPATCH_ENABLED", "true");
                    cfg.put("CALLBACK_INBOX_ENABLED", "true");
                    cfg.put("OUTBOUND_PLATFORM_MAX_CONCURRENT", "20000");
                    cfg.put("MAX_CONCURRENT_CALLS", "20000");
                    cfg.put("TELEPHONY_PROVIDER", "http");
                    cfg.put("TELEPHONY_PROVIDER_ENDPOINT", origin);
                    cfg.put("VOICE_GATEWAY_DRIVER", "mock");
                    cfg.put("VOICE_AI_PIPELINE", "legacy");
                    cfg.put("VOICE_COMMAND_SECRET", HOOK);
                    cfg.put("VOICE_CALLBACK_BASE_URL", origin);
                    cfg.put("TELEPHONY_WEBHOOK_BASE", origin);
                    cfg.put("TELEPHONY_WEBHOOK_SECRET", HOOK);
                    cfg.put("TELEPHONY_WEBHOOK_TOKEN", HOOK);
                    cfg.put("RATE_LIMIT_RPS", "2000");
                    cfg.put("RATE_LIMIT_BURST", "2000");
                    cfg.put("METRICS_TOKEN", HOOK);
                    cfg.put("LLM_PROVIDER", "rule");
                    cfg.put("JAVA_DIAL_BATCH_SIZE", System.getProperty("dial.batch", "100"));
                    cfg.put(
                            "JAVA_CALLBACK_BATCH_SIZE",
                            System.getProperty("dial.receipt.batch", "100"));
                    cfg.put("VOICE_CALLBACK_BATCH_ENABLED", "true");
                    cfg.put("JAVA_DIAL_PROFILE", "true");
                    env.putAll(cfg);
                    // Diagnostic profile only. Formal v5 evidence uses bounded commit
                    // intervals and request deadlines, not this legacy timestamp probe.
                    env.put("VOICE_MAX_CONCURRENT", "20000");
                    env.put("VOICE_CPS", "1000");
                    env.put("VOICE_DAILY_CALL_LIMIT", "10000000");
                    env.put("VOICE_HOUR_BUDGET_MINOR", "1000000000");
                    env.put("VOICE_DAY_BUDGET_MINOR", "1000000000");
                    env.put(
                            "VOICE_MOCK_OCCUPANCY_SEC",
                            System.getProperty("dial.occupancy.seconds", "20"));
                    env.put("OUTBOUND_DAILY_CALL_LIMIT", "10000000");
                    env.put("JAVA_CALLBACK_TERMINAL_BATCH_ENABLED", "true");
                    env.put(
                            "VOICE_ADMISSION_BATCH_CACHE_ENABLED",
                            System.getProperty("dial.voice.batch.cache", "false"));
                    env.put(
                            "JAVA_DIAL_RATE_CONTROL",
                            System.getProperty("dial.rate.control", "legacy"));
                    env.put("JAVA_DIAL_TOKEN_BURST", System.getProperty("dial.token.burst", "35"));
                    env.put(
                            "JAVA_DIAL_AUTH_PROTOCOL",
                            System.getProperty("dial.authorization.protocol", "2"));
                    env.put(
                            "VOICE_MATERIALIZED_LIMITS_ENABLED",
                            System.getProperty("dial.voice.materialized", "true"));
                    env.put("VOICE_SECURITY_ADMIN_TOKEN", HOOK);
                    // Explicit higher call ceiling and API rate allowance; worker and DB pools
                    // remain at delivered defaults.
                    result.put(
                            "configuration",
                            Values.map(
                                    "jvm_heap_max_gib",
                                    2,
                                    "db_pool_max",
                                    dbPool,
                                    "voice_ledger_pool_max",
                                    4,
                                    "model_quota_window",
                                    "disabled; rule AI",
                                    "http_inflight_limit",
                                    100,
                                    "api_rate_limit_rps",
                                    2000,
                                    "ai_lane_threads_max",
                                    4,
                                    "callback_consumer_threads",
                                    1,
                                    "external_model",
                                    "not used",
                                    "audio",
                                    "not exercised",
                                    "capacity_knobs",
                                    20000,
                                    "application_jvms",
                                    1));
                    var actualConfiguration =
                            new LinkedHashMap<>((Map<String, Object>) result.get("configuration"));
                    actualConfiguration.put("dial_batch_size", cfg.get("JAVA_DIAL_BATCH_SIZE"));
                    actualConfiguration.put("gateway_command_batch_max", 100);
                    actualConfiguration.put(
                            "callback_batch_size", cfg.get("JAVA_CALLBACK_BATCH_SIZE"));
                    actualConfiguration.put("voice_callback_batch_enabled", true);
                    result.put("configuration", actualConfiguration);
                    for (String key :
                            List.of(
                                    "VOICE_MAX_CONCURRENT",
                                    "VOICE_CPS",
                                    "VOICE_DAILY_CALL_LIMIT",
                                    "VOICE_HOUR_BUDGET_MINOR",
                                    "VOICE_DAY_BUDGET_MINOR",
                                    "VOICE_MOCK_OCCUPANCY_SEC",
                                    "OUTBOUND_DAILY_CALL_LIMIT",
                                    "JAVA_CALLBACK_TERMINAL_BATCH_ENABLED",
                                    "VOICE_ADMISSION_BATCH_CACHE_ENABLED",
                                    "JAVA_DIAL_RATE_CONTROL"))
                        actualConfiguration.put(key, env.get(key));
                    for (String key :
                            List.of(
                                    "JAVA_DIAL_AUTH_PROTOCOL",
                                    "VOICE_MATERIALIZED_LIMITS_ENABLED",
                                    "JAVA_DIAL_TOKEN_BURST"))
                        actualConfiguration.put(key, env.get(key));
                    env.put("JAVA_DIAL_WORKERS", System.getProperty("dial.workers", "2"));
                    actualConfiguration.put("dial_executor_threads", env.get("JAVA_DIAL_WORKERS"));
                    actualConfiguration.put("general_task_executor_threads", 4);
                    env.put("JAVA_TASK_WORKERS", System.getProperty("dial.task.workers", "4"));
                    env.put(
                            "JAVA_TASK_CLAIM_PARALLEL_ENABLED",
                            System.getProperty("dial.task.parallel", "false"));
                    actualConfiguration.put(
                            "general_task_executor_threads", env.get("JAVA_TASK_WORKERS"));
                    actualConfiguration.put(
                            "JAVA_TASK_CLAIM_PARALLEL_ENABLED",
                            env.get("JAVA_TASK_CLAIM_PARALLEL_ENABLED"));
                    env.put(
                            "JAVA_ANALYSIS_TASK_OWNERS",
                            System.getProperty("dial.analysis.owners", "0"));
                    actualConfiguration.put(
                            "JAVA_ANALYSIS_TASK_OWNERS", env.get("JAVA_ANALYSIS_TASK_OWNERS"));
                    for (var knob :
                            Map.of(
                                            "JAVA_AI_TASK_WORKERS",
                                            "dial.ai.workers",
                                            "JAVA_CONTROL_TASK_WORKERS",
                                            "dial.control.workers",
                                            "JAVA_CONTROL_TASK_OWNERS",
                                            "dial.control.owners",
                                            "JAVA_ANALYSIS_TASK_WORKERS",
                                            "dial.analysis.workers")
                                    .entrySet()) {
                        env.put(
                                knob.getKey(),
                                System.getProperty(
                                        knob.getValue(),
                                        knob.getKey().equals("JAVA_ANALYSIS_TASK_WORKERS")
                                                ? "2"
                                                : "4"));
                        actualConfiguration.put(knob.getKey(), env.get(knob.getKey()));
                    }
                    actualConfiguration.put("realtime_jdbc_pool_max", 4);
                    actualConfiguration.put("analysis_jdbc_pool_max", 1);
                    actualConfiguration.put("total_business_jdbc_pool_max", dbPool + 5);
                    env.put(
                            "JAVA_WORKER_POLL_MS",
                            System.getProperty("dial.worker.poll.ms", "250"));
                    actualConfiguration.put("worker_poll_ms", env.get("JAVA_WORKER_POLL_MS"));
                    actualConfiguration.put("task_scheduler_threads", 1);
                    actualConfiguration.put(
                            "dial_cohort_sizing",
                            "fixed configured batch; live per-item token check");
                    actualConfiguration.put(
                            "logical_task_lanes",
                            2
                                    + 4
                                    + Integer.parseInt(env.get("JAVA_CONTROL_TASK_OWNERS"))
                                    + Math.max(
                                            1,
                                            Integer.parseInt(
                                                    env.get("JAVA_ANALYSIS_TASK_OWNERS"))));
                    env.put(
                            "JAVA_ANALYSIS_BATCH_SIZE",
                            System.getProperty("dial.analysis.batch", "1"));
                    actualConfiguration.put(
                            "JAVA_ANALYSIS_BATCH_SIZE", env.get("JAVA_ANALYSIS_BATCH_SIZE"));
                    int callbackWorkers = Integer.getInteger("dial.callback.workers", 1);
                    assertTrue(callbackWorkers >= 1 && callbackWorkers <= 4);
                    env.put("JAVA_CALLBACK_WORKERS", "" + callbackWorkers);
                    actualConfiguration.put("callback_consumer_threads", callbackWorkers);
                    env.put(
                            "VOICE_CALLBACK_CONCURRENCY",
                            System.getProperty("dial.callback.concurrency", "64"));
                    actualConfiguration.put(
                            "voice_callback_delivery_concurrency",
                            env.get("VOICE_CALLBACK_CONCURRENCY"));
                    env.put(
                            "VOICE_CALLBACK_MAX_INFLIGHT_EVENTS",
                            System.getProperty(
                                    "dial.callback.events", env.get("VOICE_CALLBACK_CONCURRENCY")));
                    actualConfiguration.put(
                            "voice_callback_max_inflight_events",
                            env.get("VOICE_CALLBACK_MAX_INFLIGHT_EVENTS"));
                    env.put(
                            "VOICE_MOCK_ANSWER_PERCENT",
                            System.getProperty("dial.mock.answerPercent", "100"));
                    env.put(
                            "VOICE_MOCK_DISTRIBUTION_SEED",
                            System.getProperty("dial.mock.seed", "v5-simulation-20261021"));
                    actualConfiguration.put(
                            "VOICE_MOCK_ANSWER_PERCENT", env.get("VOICE_MOCK_ANSWER_PERCENT"));
                    actualConfiguration.put(
                            "VOICE_MOCK_DISTRIBUTION_SEED",
                            env.get("VOICE_MOCK_DISTRIBUTION_SEED"));
                    env.put(
                            "VOICE_CALLBACK_BATCH_SIZE",
                            System.getProperty("dial.callback.batch", "64"));
                    actualConfiguration.put(
                            "voice_callback_batch_size", env.get("VOICE_CALLBACK_BATCH_SIZE"));
                    env.put(
                            "VOICE_CALLBACK_PARTITION_GROUPS",
                            System.getProperty("dial.callback.groups", "1"));
                    actualConfiguration.put(
                            "voice_callback_partition_groups",
                            env.get("VOICE_CALLBACK_PARTITION_GROUPS"));
                    actualConfiguration.put(
                            "voice_callback_protocol",
                            "v1 for <=16; additive signed v2 for 17..64");
                    env.put(
                            "JAVA_CALLBACK_POLL_MS",
                            System.getProperty("dial.callback.poll.ms", "100"));
                    actualConfiguration.put(
                            "callback_consumer_poll_ms", env.get("JAVA_CALLBACK_POLL_MS"));
                    application = command.start();
                    result.put("application_pid", application.pid());
                    write();
                    long deadline = System.currentTimeMillis() + 60000;
                    boolean healthy = false;
                    while (System.currentTimeMillis() < deadline && application.isAlive()) {
                        try {
                            healthy = send("GET", "/healthz", null, false).statusCode() == 200;
                            if (healthy) break;
                        } catch (Exception starting) {
                        }
                        Thread.sleep(200);
                    }
                    assertTrue(healthy, "fat jar startup: " + folder.resolve("application.log"));
                    try (Connection c = connection()) {
                        seed(c);
                    }
                    var environment = new StandardEnvironment();
                    environment
                            .getPropertySources()
                            .addFirst(
                                    new MapPropertySource(
                                            "capacity", Map.of("JWT_SECRET", SECRET)));
                    token =
                            new Crypto(json, new Config(environment))
                                    .token(
                                            Map.of(
                                                    "id",
                                                    1,
                                                    "username",
                                                    "capacity-admin",
                                                    "tenant_id",
                                                    1,
                                                    "role",
                                                    "admin",
                                                    "token_version",
                                                    0));
                    if (Boolean.getBoolean("dial.daily.counter")) {
                        admin("/v1/admin/dial-control/begin");
                        admin("/v1/admin/dial-control/close-sending");
                        admin("/v1/admin/dial-control/freeze-mock");
                        var generation = admin("/v1/admin/dial-counters/build");
                        result.put("daily_generation", generation);
                        admin(
                                "/v1/admin/dial-counters/publish?generation="
                                        + generation.get("generation_id")
                                        + "&read=true");
                        admin("/v1/admin/dial-control/cancel");
                    }
                    actualConfiguration.put(
                            "daily_counter_read", Boolean.getBoolean("dial.daily.counter"));
                    if (calibration) {
                        admin("/v1/admin/dial-control/begin");
                        admin("/v1/admin/dial-control/close-sending");
                    }
                    result.put("mysql_physical_before_workload", physicalSample(mysql));

                    long phase = System.currentTimeMillis(), begin = System.nanoTime();
                    result.put("phase_start_ms", phase);
                    if (queued) {
                        try (var c = connection()) {
                            c.setAutoCommit(false);
                            var defaults = new Store(null, json, null);
                            String fixtureSeed =
                                    System.getProperty("dial.mock.seed", "v5-simulation-20261021");
                            if (calibration) result.put("finite_identity_seed", fixtureSeed);
                            for (int i = 0; i < poolSize; i++) {
                                var input =
                                        new LinkedHashMap<String, Object>(
                                                Map.of(
                                                        "tenant_id",
                                                        1,
                                                        "phone",
                                                        phone(i),
                                                        "mode",
                                                        "ai_only",
                                                        "status",
                                                        "queued",
                                                        "max_attempts",
                                                        1));
                                // Identical K values give compression/control cohorts the
                                // same answer distribution, independent of random run IDs.
                                if (calibration)
                                    input.put(
                                            "id",
                                            ConversationPolicy.uuid5(
                                                    "v5-storage:" + fixtureSeed + ":" + i));
                                fixture(c, defaults, "callsession", input);
                            }
                            result.put("db_stats_before", databaseStats());
                            phase = System.currentTimeMillis();
                            result.put("phase_start_ms", phase);
                            c.commit();
                        }
                        result.put("mysql_physical_after_queue_commit", physicalSample(mysql));
                        result.put(
                                "fixtures",
                                "Isolated explicitly-consented synthetic contacts and QUEUED calls"
                                        + " committed together; actual durable scheduler performs all"
                                        + " admission and dial transitions. No public telephone"
                                        + " calls.");
                        result.put("queued_pool_size", poolSize);
                        if (calibration) {
                            phase = System.currentTimeMillis();
                            result.put("phase_start_ms", phase);
                            admin("/v1/admin/dial-control/cancel");
                            awaitFiniteDrain();
                        } else {
                            Thread.sleep((seconds + warmup + 1) * 1000L);
                            if (drainDiagnostic) stopAndDrain();
                        }
                    }
                    for (int i = 0; !queued && i < 500 * (seconds + warmup + 1); i++) {
                        final int index = i;
                        long due = begin + i * 2_000_000L;
                        while (System.nanoTime() < due)
                            java.util.concurrent.locks.LockSupport.parkNanos(
                                    due - System.nanoTime());
                        if (System.nanoTime() - due > 1_000_000_000L) missed.incrementAndGet();
                        futures.add(
                                http.sendAsync(
                                                request(
                                                        "POST",
                                                        "/api/v1/calls",
                                                        Map.of(
                                                                "phone",
                                                                phone(index),
                                                                "mode",
                                                                "ai_only",
                                                                "max_attempts",
                                                                1),
                                                        false),
                                                HttpResponse.BodyHandlers.ofString())
                                        .handle(
                                                (response, error) -> {
                                                    if (error != null) {
                                                        codes.computeIfAbsent(
                                                                        0, k -> new AtomicInteger())
                                                                .incrementAndGet();
                                                        errors.putIfAbsent(
                                                                "transport", error.toString());
                                                    } else {
                                                        codes.computeIfAbsent(
                                                                        response.statusCode(),
                                                                        k -> new AtomicInteger())
                                                                .incrementAndGet();
                                                        if (response.statusCode() != 200)
                                                            errors.putIfAbsent(
                                                                    "http_" + response.statusCode(),
                                                                    response.body()
                                                                            .substring(
                                                                                    0,
                                                                                    Math.min(
                                                                                            1000,
                                                                                            response.body()
                                                                                                    .length())));
                                                    }
                                                    return null;
                                                }));
                    }
                    for (var future : futures) future.get(60, TimeUnit.SECONDS);
                    result.put("planned_calls", queued ? poolSize : 500 * (seconds + warmup + 1));
                    result.put("warmup_seconds", warmup);
                    result.put("measured_seconds", seconds);
                    result.put(
                            "measurement_note",
                            "Every measured second must have >=500 durable dial acknowledgements;"
                                    + " all earlier cold-start buckets are retained separately and"
                                    + " never relabelled as passes");
                    result.put("submitted_calls", futures.size());
                    result.put("generator_capacity_misses", missed.get());
                    result.put("http_codes", codes);
                    result.put("error_examples", errors);
                    result.put(
                            "generator",
                            queued
                                    ? "Preloaded durable QUEUED pool committed at phase start; no"
                                            + " calls inserted as DIALING or IN_AI"
                                    : "Fixed 2 ms async HTTP schedule; every planned call is"
                                            + " submitted even when server admission is full");
                    var buckets = new TreeMap<Long, Long>();
                    long within = 0, accepted = 0;
                    try (var c = connection();
                            var q =
                                    c.prepareStatement(
                                            "SELECT"
                                                    + " FLOOR((UNIX_TIMESTAMP(e.created_at)*1000-?)/1000)"
                                                    + " AS bucket,COUNT(*) AS n FROM callevent e JOIN"
                                                    + " callsession c ON c.id=e.call_session_id WHERE"
                                                    + " c.tenant_id=1 AND"
                                                    + " e.event_type='dial_command_accepted' GROUP BY"
                                                    + " bucket ORDER BY bucket")) {
                        q.setLong(1, phase);
                        try (var rows = q.executeQuery()) {
                            while (rows.next()) {
                                long bucket = rows.getLong(1), n = rows.getLong(2);
                                buckets.put(bucket, n);
                                accepted += n;
                                if (bucket >= warmup && bucket < warmup + seconds) within += n;
                            }
                        }
                    }
                    result.put("dial_accepted_per_second", buckets);
                    result.put("dial_accepted_within_window", within);
                    result.put("dial_accepted_at_observation_end", accepted);
                    result.put("actual_cps", within / (double) seconds);
                    result.put("real_telephony_verified", false);
                    result.put("db_stats_after", databaseStats());
                    result.put("mysql_physical_at_observation_end", physicalSample(mysql));
                    try (var c = connection();
                            var q = c.createStatement();
                            var rows =
                                    q.executeQuery(
                                            "SELECT"
                                                    + " COUNT(*),COALESCE(SUM(JSON_UNQUOTE(JSON_EXTRACT(result,'$.mock_answered'))='true'),0),COALESCE(SUM(state='ended'),0),MIN(CASE"
                                                    + " WHEN state='ended' THEN ended-created"
                                                    + " END),AVG(CASE WHEN state='ended' THEN"
                                                    + " ended-created END),MAX(CASE WHEN state='ended'"
                                                    + " THEN ended-created"
                                                    + " END),COALESCE(SUM(state='ended' AND"
                                                    + " ended-created<19.9),0) FROM voice_attempts"
                                                    + " WHERE send_state IN ('PBX_STARTED','ENDED') AND"
                                                    + " JSON_VALID(result)")) {
                        if (rows.next())
                            result.put(
                                    "observed_synthetic_lifetimes",
                                    Values.map(
                                            "accepted",
                                            rows.getLong(1),
                                            "answered",
                                            rows.getLong(2),
                                            "ended",
                                            rows.getLong(3),
                                            "min_ended_age_sec",
                                            rows.getObject(4),
                                            "mean_ended_age_sec",
                                            rows.getObject(5),
                                            "max_ended_age_sec",
                                            rows.getObject(6),
                                            "ended_under_19_9_sec",
                                            rows.getLong(7),
                                            "note",
                                            "Includes real application hangup commands; unfinished"
                                                    + " calls are excluded from ended-age aggregates."
                                                    + " Diagnostic only, not formal occupancy"
                                                    + " coverage."));
                    }
                    try (var c = connection();
                            var q = c.createStatement();
                            var rows =
                                    q.executeQuery(
                                            "SELECT tenant,call_id,attempt,ended-created AS"
                                                    + " age_sec,result FROM voice_attempts WHERE"
                                                    + " state='ended' AND send_state='ENDED' AND"
                                                    + " ended-created<19.9 LIMIT 20")) {
                        var examples = new ArrayList<Map<String, Object>>();
                        while (rows.next())
                            examples.add(
                                    Values.map(
                                            "tenant",
                                            rows.getLong(1),
                                            "call_id",
                                            rows.getString(2),
                                            "attempt",
                                            rows.getInt(3),
                                            "age_sec",
                                            rows.getDouble(4),
                                            "voice_result",
                                            rows.getString(5)));
                        for (var example : examples) {
                            try (var events =
                                    c.prepareStatement(
                                            "SELECT event_type,source,payload,created_at FROM"
                                                    + " callevent WHERE"
                                                    + " REPLACE(call_session_id,'-','')=REPLACE(?,'-','')"
                                                    + " ORDER BY created_at,id LIMIT 100")) {
                                events.setObject(1, example.get("call_id"));
                                try (var history = events.executeQuery()) {
                                    var trace = new ArrayList<Map<String, Object>>();
                                    while (history.next())
                                        trace.add(
                                                Values.map(
                                                        "event_type",
                                                        history.getString(1),
                                                        "source",
                                                        history.getString(2),
                                                        "payload",
                                                        history.getString(3),
                                                        "at",
                                                        history.getString(4)));
                                    example.put("business_events", trace);
                                }
                            }
                        }
                        result.put("short_occupancy_examples", examples);
                    }
                    try (var c = connection();
                            var q = c.createStatement();
                            var rows =
                                    q.executeQuery(
                                            "SELECT"
                                                    + " task_type,state,COUNT(*),COALESCE(MAX(TIMESTAMPDIFF(MICROSECOND,available_at,UTC_TIMESTAMP(3)))/1000000,0)"
                                                    + " FROM taskoutbox GROUP BY task_type,state")) {
                        var tasks = new ArrayList<Map<String, Object>>();
                        while (rows.next())
                            tasks.add(
                                    Map.of(
                                            "type",
                                            rows.getString(1),
                                            "state",
                                            rows.getString(2),
                                            "count",
                                            rows.getLong(3),
                                            "oldest_due_age_sec",
                                            rows.getDouble(4)));
                        result.put("tasks_at_observation_end", tasks);
                    }
                    try (var c = connection();
                            var q = c.createStatement();
                            var rows =
                                    q.executeQuery(
                                            "SELECT table_name,table_rows,data_length,index_length"
                                                    + " FROM information_schema.tables WHERE"
                                                    + " table_schema=DATABASE() ORDER BY"
                                                    + " data_length+index_length DESC")) {
                        var sizes = new ArrayList<Map<String, Object>>();
                        while (rows.next())
                            sizes.add(
                                    Map.of(
                                            "table",
                                            rows.getString(1),
                                            "estimated_rows",
                                            rows.getLong(2),
                                            "data_bytes",
                                            rows.getLong(3),
                                            "index_bytes",
                                            rows.getLong(4)));
                        result.put("storage_estimates_at_observation_end", sizes);
                    }
                    try (var c = DriverManager.getConnection(jdbc, "root", dbPassword);
                            var q = c.createStatement();
                            var rows =
                                    q.executeQuery(
                                            "SELECT"
                                                    + " LEFT(DIGEST_TEXT,2048),COUNT_STAR,SUM_TIMER_WAIT/1000000000,MAX_TIMER_WAIT/1000000000,SUM_ROWS_EXAMINED,SUM_ROWS_SENT"
                                                    + " FROM"
                                                    + " performance_schema.events_statements_summary_by_digest"
                                                    + " WHERE DIGEST_TEXT IS NOT NULL ORDER BY"
                                                    + " SUM_TIMER_WAIT DESC LIMIT 30")) {
                        var digests = new ArrayList<Map<String, Object>>();
                        while (rows.next())
                            digests.add(
                                    Map.of(
                                            "normalized_sql",
                                            rows.getString(1),
                                            "count",
                                            rows.getLong(2),
                                            "total_ms",
                                            rows.getDouble(3),
                                            "max_ms",
                                            rows.getDouble(4),
                                            "rows_examined",
                                            rows.getLong(5),
                                            "rows_sent",
                                            rows.getLong(6)));
                        result.put("mysql_statement_digests", digests);
                    } catch (SQLException unavailable) {
                        result.put(
                                "mysql_statement_diagnostic_error",
                                unavailable.getSQLState() + ":" + unavailable.getErrorCode());
                    }
                    try (var c = DriverManager.getConnection(jdbc, "root", dbPassword);
                            var q = c.createStatement();
                            var rows =
                                    q.executeQuery(
                                            "SELECT"
                                                    + " LEFT(SQL_TEXT,4096),COUNT_EXECUTE,SUM_TIMER_EXECUTE/1000000000,MAX_TIMER_EXECUTE/1000000000,SUM_ROWS_EXAMINED"
                                                    + " FROM"
                                                    + " performance_schema.prepared_statements_instances"
                                                    + " ORDER BY SUM_TIMER_EXECUTE DESC LIMIT 30")) {
                        var statements = new ArrayList<Map<String, Object>>();
                        while (rows.next())
                            statements.add(
                                    Values.map(
                                            "parameterized_sql",
                                            rows.getString(1),
                                            "count",
                                            rows.getLong(2),
                                            "total_ms",
                                            rows.getDouble(3),
                                            "max_ms",
                                            rows.getDouble(4),
                                            "rows_examined",
                                            rows.getLong(5)));
                        result.put("mysql_prepared_statement_statistics", statements);
                    } catch (SQLException unavailable) {
                        result.put(
                                "mysql_prepared_diagnostic_error",
                                unavailable.getSQLState() + ":" + unavailable.getErrorCode());
                    }
                    try (var c = DriverManager.getConnection(jdbc, "root", dbPassword);
                            var q = c.createStatement();
                            var rows =
                                    q.executeQuery(
                                            "SELECT"
                                                    + " EVENT_NAME,COUNT_STAR,SUM_TIMER_WAIT/1000000000,COUNT_WRITE,SUM_NUMBER_OF_BYTES_WRITE"
                                                    + " FROM"
                                                    + " performance_schema.file_summary_by_event_name"
                                                    + " WHERE COUNT_STAR>0 ORDER BY SUM_TIMER_WAIT DESC"
                                                    + " LIMIT 15")) {
                        var waits = new ArrayList<Map<String, Object>>();
                        while (rows.next())
                            waits.add(
                                    Values.map(
                                            "event",
                                            rows.getString(1),
                                            "operations",
                                            rows.getLong(2),
                                            "total_ms",
                                            rows.getDouble(3),
                                            "writes",
                                            rows.getLong(4),
                                            "written_bytes",
                                            rows.getLong(5)));
                        result.put("mysql_file_waits", waits);
                    } catch (SQLException unavailable) {
                        result.put(
                                "mysql_file_wait_diagnostic_error",
                                unavailable.getSQLState() + ":" + unavailable.getErrorCode());
                    }
                    try (var c = connection();
                            var q = c.createStatement();
                            var rows =
                                    q.executeQuery(
                                            "SELECT status,COUNT(*) FROM callsession WHERE"
                                                    + " tenant_id=1 GROUP BY status")) {
                        var states = new TreeMap<String, Long>();
                        while (rows.next()) states.put(rows.getString(1), rows.getLong(2));
                        result.put("call_states_at_observation_end", states);
                    }
                    try (var c = DriverManager.getConnection(jdbc, "root", dbPassword);
                            var q = c.createStatement();
                            var rows = q.executeQuery("SHOW ENGINE INNODB STATUS")) {
                        if (rows.next())
                            Files.writeString(
                                    folder.resolve("innodb-final.txt"), rows.getString(3));
                    } catch (SQLException diagnostic) {
                        result.put("innodb_diagnostic_error", diagnostic.getMessage());
                    }
                    try (var c = connection();
                            var q = c.createStatement();
                            var rows =
                                    q.executeQuery(
                                            "SELECT"
                                                    + " state,COUNT(*),MAX(TIMESTAMPDIFF(MICROSECOND,received_at,UTC_TIMESTAMP(3)))/1000000"
                                                    + " FROM callbackinbox GROUP BY state")) {
                        var receipts = new ArrayList<Map<String, Object>>();
                        while (rows.next())
                            receipts.add(
                                    Map.of(
                                            "state",
                                            rows.getString(1),
                                            "count",
                                            rows.getLong(2),
                                            "oldest_age_sec",
                                            rows.getDouble(3)));
                        result.put("callback_receipts", receipts);
                    }
                    try (var c = connection();
                            var q = c.createStatement();
                            var rows =
                                    q.executeQuery(
                                            "SELECT"
                                                    + " COUNT(*),COALESCE(UNIX_TIMESTAMP(UTC_TIMESTAMP(3))-MIN(created),0),COALESCE(MAX(failures),0)"
                                                    + " FROM voice_outbox")) {
                        if (rows.next())
                            result.put(
                                    "gateway_callback_backlog",
                                    Map.of(
                                            "count",
                                            rows.getLong(1),
                                            "oldest_age_sec",
                                            rows.getDouble(2),
                                            "max_failures",
                                            rows.getLong(3)));
                    }
                    boolean passed =
                            !calibration
                                    && within >= 500L * seconds
                                    && missed.get() == 0
                                    && java.util.stream.LongStream.range(warmup, warmup + seconds)
                                            .allMatch(
                                                    bucket ->
                                                            buckets.getOrDefault(bucket, 0L)
                                                                    >= 500);
                    result.put("passed", passed);
                    if (drainDiagnostic) {
                        assertEquals(
                                true,
                                result.get("stop_drain_verified"),
                                "no diagnostic pass without actual drain");
                        var lifetime =
                                (Map<String, Object>) result.get("observed_synthetic_lifetimes");
                        assertTrue(
                                ((Number) lifetime.get("accepted")).longValue() > 0
                                        && ((Number) lifetime.get("answered")).longValue() > 0,
                                "drain diagnostic must exercise admitted and answered mock work");
                        var stopped = (Map<String, Object>) result.get("backlog_at_stop_request");
                        assertTrue(
                                ((Number) stopped.get("business_active")).longValue() > 0
                                        && ((Number) stopped.get("lifecycle_tasks_remaining"))
                                                        .longValue()
                                                > 0,
                                "stop-and-drain must cut a nonempty live cohort with durable work");
                        assertEquals(
                                ((Number) lifetime.get("accepted")).longValue(),
                                ((Number) lifetime.get("ended")).longValue(),
                                "all mock attempts ended");
                        if (Integer.getInteger("dial.occupancy.seconds", 20) >= 20)
                            assertEquals(
                                    0L,
                                    ((Number) lifetime.get("ended_under_19_9_sec")).longValue(),
                                    "do not shorten the 20-second occupancy to clear queues");
                        result.put("backlog_drain_passed", true);
                        result.put("diagnostic_passed", true);
                    }
                    json.writerWithDefaultPrettyPrinter()
                            .writeValue(folder.resolve("result.json").toFile(), result);
                    if (calibration) {
                        assertEquals(
                                poolSize,
                                accepted,
                                "every finite attempt must have one durable acknowledgement");
                        var conservation = new LinkedHashMap<String, Long>();
                        conservation.put("planned", (long) poolSize);
                        conservation.put("accepted", accepted);
                        try (var db = connection();
                                var q = db.createStatement()) {
                            try (var rows =
                                    q.executeQuery(
                                            "SELECT COUNT(*) FROM (SELECT"
                                                    + " c.id,c.attempts,COUNT(e.id) AS acknowledgements"
                                                    + " FROM callsession c LEFT JOIN callevent e ON"
                                                    + " e.call_session_id=c.id AND"
                                                    + " e.event_type='dial_command_accepted' WHERE"
                                                    + " c.tenant_id=1 GROUP BY c.id,c.attempts HAVING"
                                                    + " c.attempts<>1 OR COUNT(e.id)<>1) invalid")) {
                                rows.next();
                                long invalid = rows.getLong(1);
                                conservation.put("invalid_business_attempts", invalid);
                                assertEquals(0, invalid, "per-call acceptance conservation");
                            }
                            try (var rows =
                                    q.executeQuery(
                                            "SELECT COUNT(*),COUNT(DISTINCT uuid),COUNT(DISTINCT"
                                                    + " tenant,call_id,attempt) FROM voice_attempts")) {
                                rows.next();
                                int index = 0;
                                for (String key :
                                        List.of(
                                                "voice_rows",
                                                "unique_voice_uuid",
                                                "unique_voice_k")) {
                                    long value = rows.getLong(++index);
                                    conservation.put(key, value);
                                    assertEquals(
                                            poolSize,
                                            value,
                                            "one original voice identity per finite attempt");
                                }
                            }
                            for (var check :
                                    Map.of(
                                                    "reserved",
                                                    "SELECT COUNT(*) FROM dial_reservation WHERE"
                                                            + " state='RESERVED'",
                                                    "phone_guards",
                                                    "SELECT COUNT(*) FROM dial_phone_guard",
                                                    "platform_counter_active",
                                                    "SELECT COALESCE(SUM(active),0) FROM"
                                                            + " dial_quota_counter",
                                                    "voice_counter_active",
                                                    "SELECT COALESCE(SUM(fact_count),0) FROM"
                                                            + " voice_scope_counter WHERE"
                                                            + " bucket_kind='active'")
                                            .entrySet()) {
                                try (var rows = q.executeQuery(check.getValue())) {
                                    rows.next();
                                    long value = rows.getLong(1);
                                    conservation.put(check.getKey(), value);
                                    assertEquals(
                                            0,
                                            value,
                                            "drained resource projection: " + check.getKey());
                                }
                            }
                        }
                        result.put("finite_identity_conservation", conservation);
                        result.put("storage_calibration_passed", true);
                    } else if (!drainDiagnostic)
                        assertTrue(
                                passed,
                                "500 CPS NOT achieved; evidence " + folder.resolve("result.json"));
                } finally {
                    if (application != null) {
                        application.destroy();
                        if (!application.waitFor(10, TimeUnit.SECONDS))
                            application.destroyForcibly();
                    }
                }
            }
        } catch (Throwable error) {
            result.put("probe_error", error.toString());
            throw error;
        } finally {
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(folder.resolve("result.json").toFile(), result);
        }
    }

    Map<String, Object> backlogSample() throws Exception {
        var sample = new LinkedHashMap<String, Object>();
        sample.put("at_ms", System.currentTimeMillis());
        // A read-only transaction provides one consistent snapshot; observers do not join
        // production writes.
        try (var db = connection();
                var q = db.createStatement()) {
            db.setAutoCommit(false);
            for (var check :
                    Map.ofEntries(
                                    Map.entry(
                                            "business_active",
                                            "SELECT COUNT(*) FROM callsession WHERE status IN"
                                                    + " ('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN')"),
                                    Map.entry(
                                            "gateway_active",
                                            "SELECT COUNT(*) FROM voice_attempts WHERE"
                                                    + " state!='ended'"),
                                    Map.entry(
                                            "mock_schedules_remaining",
                                            "SELECT COUNT(*) FROM voice_mock_schedule"),
                                    Map.entry(
                                            "receipts_remaining",
                                            "SELECT COUNT(*) FROM callbackinbox WHERE state NOT IN"
                                                    + " ('done','ignored')"),
                                    Map.entry(
                                            "gateway_callbacks_remaining",
                                            "SELECT COUNT(*) FROM voice_outbox"),
                                    Map.entry(
                                            "lifecycle_tasks_remaining",
                                            "SELECT COUNT(*) FROM taskoutbox t WHERE"
                                                    + " t.state!='COMPLETED' AND"
                                                    + " (t.task_type!='dial_call' OR EXISTS(SELECT 1"
                                                    + " FROM dial_command_journal j WHERE"
                                                    + " j.tenant_id=t.tenant_id AND"
                                                    + " j.call_id=t.aggregate_id AND"
                                                    + " j.attempt=JSON_EXTRACT(t.payload_json,'$.attempt')+1))"),
                                    Map.entry(
                                            "dial_processing",
                                            "SELECT COUNT(*) FROM taskoutbox WHERE"
                                                    + " task_type='dial_call' AND state='PROCESSING'"),
                                    Map.entry(
                                            "dead_tasks",
                                            "SELECT COUNT(*) FROM taskoutbox WHERE state='DEAD'"),
                                    Map.entry(
                                            "reserved",
                                            "SELECT COUNT(*) FROM dial_reservation WHERE"
                                                    + " state='RESERVED'"),
                                    Map.entry(
                                            "phone_guards",
                                            "SELECT COUNT(*) FROM dial_phone_guard"),
                                    Map.entry(
                                            "platform_counter_active",
                                            "SELECT COALESCE(SUM(active),0) FROM"
                                                    + " dial_quota_counter"),
                                    Map.entry(
                                            "voice_counter_active",
                                            "SELECT COALESCE(SUM(fact_count),0) FROM"
                                                    + " voice_scope_counter WHERE"
                                                    + " bucket_kind='active'"))
                            .entrySet())
                try (var rows = q.executeQuery(check.getValue())) {
                    rows.next();
                    sample.put(check.getKey(), rows.getLong(1));
                }
            var tasks = new ArrayList<Map<String, Object>>();
            try (var rows =
                    q.executeQuery(
                            "SELECT"
                                    + " task_type,state,COUNT(*),SUM(available_at<=UTC_TIMESTAMP(3)),SUM(available_at>UTC_TIMESTAMP(3)),GREATEST(0,COALESCE(MAX(TIMESTAMPDIFF(MICROSECOND,available_at,UTC_TIMESTAMP(3))),0))/1000000"
                                    + " FROM taskoutbox WHERE state!='COMPLETED' GROUP BY"
                                    + " task_type,state")) {
                while (rows.next())
                    tasks.add(
                            Values.map(
                                    "type",
                                    rows.getString(1),
                                    "state",
                                    rows.getString(2),
                                    "count",
                                    rows.getLong(3),
                                    "due",
                                    rows.getLong(4),
                                    "future",
                                    rows.getLong(5),
                                    "oldest_due_age_sec",
                                    rows.getDouble(6)));
            }
            sample.put("task_backlog", tasks);
            db.commit();
        }
        return sample;
    }

    void stopAndDrain() throws Exception {
        long stopped = System.nanoTime();
        result.put("stop_requested_at_ms", System.currentTimeMillis());
        result.put("backlog_at_stop_request", backlogSample());
        var begin = admin("/v1/admin/dial-control/begin");
        result.put("drain_begin", begin);
        // The begin transaction captures admitted/sending K identities while closing new admission.
        // QUEUED/unadmitted rows remain durable but never become a second load phase.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        boolean closed = false;
        while (System.nanoTime() < deadline) {
            var reply =
                    http.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    origin
                                                            + "/v1/admin/dial-control/close-sending"))
                                    .timeout(Duration.ofSeconds(30))
                                    .header("Authorization", "Bearer " + HOOK)
                                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (reply.statusCode() == 200) {
                result.put("sending_closed", json.readValue(reply.body(), Map.class));
                closed = true;
                break;
            }
            assertEquals(
                    409,
                    reply.statusCode(),
                    "only unresolved sending may postpone the barrier: " + reply.body());
            Thread.sleep(200);
        }
        assertTrue(closed, "sending barrier must close before drain deadline");
        long accepted;
        try (var db = connection();
                var q = db.createStatement();
                var rows = q.executeQuery("SELECT COUNT(*) FROM voice_attempts")) {
            rows.next();
            accepted = rows.getLong(1);
        }
        result.put("fixed_attempt_count_after_sending_closed", accepted);
        var samples = new ArrayList<Map<String, Object>>();
        int stable = 0;
        while (System.nanoTime() < deadline) {
            var sample = backlogSample();
            samples.add(sample);
            result.put("stop_drain_samples", samples);
            write();
            boolean empty =
                    sample.entrySet().stream()
                            .filter(
                                    e ->
                                            e.getValue() instanceof Number
                                                    && !e.getKey().equals("at_ms"))
                            .allMatch(e -> ((Number) e.getValue()).longValue() == 0);
            stable = empty ? stable + 1 : 0;
            if (stable == 3) break;
            Thread.sleep(1000);
        }
        assertEquals(
                3,
                stable,
                "stopped cohort must drain without dead tasks, unknown sends or resource leaks");
        result.put("stop_drain_elapsed_sec", (System.nanoTime() - stopped) / 1e9);
        result.put("stop_drain_final", admin("/v1/admin/dial-control/freeze-mock"));
        try (var db = connection();
                var q = db.createStatement()) {
            // Full bidirectional identity match, not just equal total counts.
            String invalid =
                    "SELECT COUNT(*) FROM voice_attempts v LEFT JOIN callsession c ON"
                            + " c.tenant_id=v.tenant AND REPLACE(c.id,'-','')=REPLACE(v.call_id,'-','')"
                            + " AND c.attempts=v.attempt WHERE c.id IS NULL OR v.state!='ended' OR"
                            + " v.send_state!='ENDED' OR c.status NOT IN"
                            + " ('COMPLETED','FAILED','NO_ANSWER','BUSY','VOICEMAIL') OR (SELECT"
                            + " COUNT(*) FROM callevent e WHERE e.call_session_id=c.id AND"
                            + " e.event_type='dial_command_accepted' AND"
                            + " JSON_EXTRACT(e.payload,'$.attempt')=v.attempt)<>1 OR NOT EXISTS(SELECT"
                            + " 1 FROM callusage u WHERE u.tenant_id=v.tenant AND"
                            + " u.call_session_id=c.id AND u.attempt=v.attempt AND u.ended_at IS NOT"
                            + " NULL)";
            try (var rows = q.executeQuery(invalid)) {
                rows.next();
                long n = rows.getLong(1);
                result.put("drain_invalid_identities", n);
                assertEquals(0, n, "gateway/business/acceptance/usage K reconciliation");
            }
            try (var rows =
                    q.executeQuery(
                            "SELECT COUNT(*) FROM callevent e JOIN callsession c ON"
                                    + " c.id=e.call_session_id WHERE"
                                    + " e.event_type='dial_command_accepted' AND NOT EXISTS(SELECT 1"
                                    + " FROM voice_attempts v WHERE v.tenant=c.tenant_id AND"
                                    + " REPLACE(v.call_id,'-','')=REPLACE(c.id,'-','') AND"
                                    + " v.attempt=JSON_EXTRACT(e.payload,'$.attempt'))")) {
                rows.next();
                assertEquals(
                        0, rows.getLong(1), "no business acknowledgement without voice identity");
            }
            try (var rows =
                    q.executeQuery(
                            "SELECT COUNT(*),COUNT(DISTINCT tenant,call_id,attempt) FROM"
                                    + " voice_attempts")) {
                rows.next();
                assertEquals(accepted, rows.getLong(1), "no new attempts after SEND_CLOSED");
                assertEquals(accepted, rows.getLong(2), "unique K");
            }
            try (var rows =
                    q.executeQuery(
                            "SELECT COUNT(*) FROM callsession WHERE attempts=0 AND"
                                    + " status='QUEUED'")) {
                rows.next();
                result.put("unadmitted_queued_preserved", rows.getLong(1));
            }
            try (var rows =
                    q.executeQuery(
                            "SELECT"
                                    + " task_type,COUNT(*),SUM(updated_at>available_at),GREATEST(0,MAX(TIMESTAMPDIFF(MICROSECOND,available_at,updated_at)))/1000.0"
                                    + " FROM taskoutbox WHERE last_error='superseded by terminal"
                                    + " attempt' GROUP BY task_type")) {
                var cancelled = new ArrayList<Map<String, Object>>();
                while (rows.next())
                    cancelled.add(
                            Values.map(
                                    "type",
                                    rows.getString(1),
                                    "count",
                                    rows.getLong(2),
                                    "superseded_after_due",
                                    rows.getLong(3),
                                    "max_superseded_after_due_ms",
                                    rows.getDouble(4)));
                result.put("superseded_control_tasks", cancelled);
                result.put(
                        "superseded_note",
                        "Kept as original durable task rows, never treated as successful control"
                                + " dispatch. Supersession after due is reported separately and cannot"
                                + " erase earlier SLO misses; validity at each deadline needs"
                                + " independent event evidence.");
            }

            try (var rows =
                    q.executeQuery(
                            "SELECT"
                                    + " task_type,TIMESTAMPDIFF(MICROSECOND,available_at,updated_at)/1000.0"
                                    + " FROM taskoutbox WHERE task_type IN"
                                    + " ('ai_turn','after_playback','interrupt_playback') AND"
                                    + " state='COMPLETED' AND last_error='' AND"
                                    + " available_at>=FROM_UNIXTIME("
                                    + (((Number) result.get("phase_start_ms")).longValue() / 1000.0)
                                    + ") ORDER BY task_type")) {
                var latency = new TreeMap<String, List<Double>>();
                while (rows.next())
                    latency.computeIfAbsent(rows.getString(1), key -> new ArrayList<>())
                            .add(Math.max(0, rows.getDouble(2)));
                var summaries = new TreeMap<String, Object>();
                for (var entry : latency.entrySet()) {
                    var values = entry.getValue();
                    Collections.sort(values);
                    summaries.put(
                            entry.getKey(),
                            Values.map(
                                    "count",
                                    values.size(),
                                    "p99_ms",
                                    values.get((int) Math.ceil(values.size() * .99) - 1),
                                    "max_ms",
                                    values.get(values.size() - 1),
                                    "over_2000ms",
                                    values.stream().filter(v -> v > 2000).count()));
                }
                result.put("task_due_to_completion_latency", summaries);
                result.put(
                        "task_latency_note",
                        "Upper-bound diagnostic includes execution and commit; excludes superseded"
                                + " tasks, is not independently collected action-dispatch evidence or a"
                                + " formal V3 SLO pass.");
            }
        }
        result.put("stop_drain_verified", true);
        write();
    }

    void awaitFiniteDrain() throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
        var samples = new ArrayList<Map<String, Object>>();
        int stable = 0;
        while (System.nanoTime() < limit) {
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("at_ms", System.currentTimeMillis());
            try (var db = connection();
                    var q = db.createStatement()) {
                for (var entry :
                        Map.of(
                                        "calls_remaining",
                                        "SELECT COUNT(*) FROM callsession WHERE tenant_id=1 AND"
                                                + " status NOT IN"
                                                + " ('COMPLETED','FAILED','NO_ANSWER','BUSY','VOICEMAIL')",
                                        "voice_remaining",
                                        "SELECT COUNT(*) FROM voice_attempts WHERE state!='ended'",
                                        "tasks_remaining",
                                        "SELECT COUNT(*) FROM taskoutbox WHERE state!='COMPLETED'",
                                        "receipts_remaining",
                                        "SELECT COUNT(*) FROM callbackinbox WHERE state!='done'",
                                        "gateway_callbacks_remaining",
                                        "SELECT COUNT(*) FROM voice_outbox")
                                .entrySet()) {
                    try (var rows = q.executeQuery(entry.getValue())) {
                        rows.next();
                        sample.put(entry.getKey(), rows.getLong(1));
                    }
                }
            }
            samples.add(sample);
            result.put("finite_drain_samples", samples);
            write();
            boolean empty =
                    sample.entrySet().stream()
                            .filter(e -> !e.getKey().equals("at_ms"))
                            .allMatch(e -> ((Number) e.getValue()).longValue() == 0);
            stable = empty ? stable + 1 : 0;
            if (stable == 3) {
                admin("/v1/admin/dial-control/begin");
                admin("/v1/admin/dial-control/close-sending");
                result.put("finite_drain_snapshot", admin("/v1/admin/dial-control/freeze-mock"));
                return;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("finite cohort did not fully drain; no storage qualification");
    }

    Map<String, Object> physicalSample(MySQLContainer mysql) throws Exception {
        var total = mysql.execInContainer("du", "-sk", "/var/lib/mysql");
        var files =
                mysql.execInContainer("sh", "-c", "du -ak /var/lib/mysql | sort -nr | head -18");
        assertEquals(0, total.getExitCode(), "physical MySQL directory measurement");
        var categories = new LinkedHashMap<String, Long>();
        for (var entry :
                Map.of(
                                "business_tables",
                                "/var/lib/mysql/test",
                                "redo",
                                "/var/lib/mysql/#innodb_redo")
                        .entrySet()) {
            var measured = mysql.execInContainer("du", "-sk", entry.getValue());
            assertEquals(0, measured.getExitCode(), "physical category: " + entry.getKey());
            categories.put(
                    entry.getKey(),
                    Long.parseLong(measured.getStdout().strip().split("\\s+", 2)[0]));
        }
        // Include every rotated log, not only files visible in the top-N list.
        var logs =
                mysql.execInContainer("sh", "-c", "du -ck /var/lib/mysql/binlog.[0-9]* | tail -1");
        assertEquals(0, logs.getExitCode(), "physical binlog measurement");
        categories.put("binlogs", Long.parseLong(logs.getStdout().strip().split("\\s+", 2)[0]));
        return Values.map(
                "at_ms",
                System.currentTimeMillis(),
                "exit",
                total.getExitCode(),
                "du_sk",
                total.getStdout().trim(),
                "physical_categories_kib",
                categories,
                "largest_paths_du_kib",
                files.getStdout().trim());
    }

    void write() throws IOException {
        json.writerWithDefaultPrettyPrinter()
                .writeValue(folder.resolve("result.json").toFile(), result);
    }

    Map<String, Object> admin(String path) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(origin + path))
                        .timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer " + HOOK)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
        var reply = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(
                200, reply.statusCode(), "isolated maintenance: " + path + ": " + reply.body());
        return json.readValue(reply.body(), Map.class);
    }

    Map<String, Long> databaseStats() throws SQLException {
        var result = new LinkedHashMap<String, Long>();
        try (var c = connection();
                var q = c.createStatement();
                var rows =
                        q.executeQuery(
                                "SHOW GLOBAL STATUS WHERE Variable_name IN"
                                        + " ('Questions','Com_insert','Com_update','Com_select','Com_commit','Com_rollback','Com_stmt_execute','Innodb_deadlocks','Innodb_row_lock_time')")) {
            while (rows.next()) result.put(rows.getString(1), rows.getLong(2));
        }
        return result;
    }

    String phone(int i) {
        return "1555" + String.format(Locale.ROOT, "%07d", i);
    }

    Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbc, dbUser, dbPassword);
    }

    void seed(Connection c) throws Exception {
        c.setAutoCommit(false);
        Store sourceDefaults = new Store(null, json, null);
        fixture(
                c,
                sourceDefaults,
                "tenant",
                Map.of("id", 1, "code", "capacity-" + run, "name", "Synthetic capacity fixture"));
        fixture(
                c,
                sourceDefaults,
                "user",
                Map.of(
                        "id",
                        1,
                        "tenant_id",
                        1,
                        "username",
                        "capacity-admin",
                        "password_hash",
                        "not-a-login-credential",
                        "full_name",
                        "Synthetic capacity admin",
                        "role",
                        "admin"));
        fixture(
                c,
                sourceDefaults,
                "adminsetting",
                Map.of(
                        "tenant_id",
                        1,
                        "section",
                        "compliance",
                        "data_json",
                        "{\"allowed_start_hour\":0,\"allowed_end_hour\":0,\"max_calls_per_day\":10000000}"));
        for (int i = 0; i < Math.max(poolSize, 500 * (seconds + warmup + 1)); i++)
            fixture(
                    c,
                    sourceDefaults,
                    "contact",
                    Map.of(
                            "tenant_id",
                            1,
                            "phone",
                            phone(i),
                            "name",
                            "Synthetic " + i,
                            "consent_state",
                            "consented"));
        c.commit();
        c.setAutoCommit(true);
        result.put(
                "fixtures",
                "Isolated explicitly-consented synthetic contacts; full HTTP to embedded mock dial"
                        + " path. No public telephone calls.");
    }

    void fixture(Connection c, Store defaults, String table, Map<String, Object> input)
            throws Exception {
        var values = defaults.defaults(table);
        values.putAll(input);
        if (values.get("id") == null) values.remove("id");
        String physical = table.equals("user") ? "sys_user" : table;
        String columns =
                String.join(",", values.keySet().stream().map(k -> "`" + k + "`").toList());
        String slots = String.join(",", Collections.nCopies(values.size(), "?"));
        try (var s =
                c.prepareStatement(
                        "INSERT INTO `"
                                + physical
                                + "` ("
                                + columns
                                + ") VALUES ("
                                + slots
                                + ")")) {
            int index = 1;
            for (var entry : values.entrySet()) {
                Object value = defaults.encode(table, entry.getKey(), entry.getValue());
                s.setObject(index++, value instanceof UUID ? value.toString() : value);
            }
            s.executeUpdate();
        }
    }

    HttpResponse<String> send(String method, String path, Object data, boolean webhook)
            throws Exception {
        return http.send(
                request(method, path, data, webhook), HttpResponse.BodyHandlers.ofString());
    }

    HttpRequest request(String method, String path, Object data, boolean webhook) throws Exception {
        String body = data == null ? "" : json.writeValueAsString(data);
        var request =
                HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10));
        if (token != null && !webhook) request.header("Authorization", "Bearer " + token);
        if (data != null) request.header("Content-Type", "application/json");
        if (webhook) {
            String stamp = "" + Instant.now().getEpochSecond();
            request.header("X-Webhook-Token", HOOK)
                    .header("X-Webhook-Timestamp", stamp)
                    .header(
                            "X-Webhook-Signature",
                            JsonSupport.hmac(
                                    HOOK,
                                    (stamp + "." + body)
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        request.method(
                method,
                data == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return request.build();
    }
}
