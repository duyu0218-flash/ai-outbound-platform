package com.duozhi.outbound.marketing.dataset;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.common.config.Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;

/** API -> matching -> real signed HTTP transport -> local stub -> shared persisted results. */
class DasGatewayIT extends DatasetTestSupport {
    @Autowired
    AntDasMatchAdapter das;
    @Autowired
    ObjectMapper mapper;
    HttpServer server;
    ExecutorService executor;
    DasGateway original;
    final AtomicInteger calls = new AtomicInteger(), validSignatures = new AtomicInteger();
    final Map<String, AtomicInteger> perPhone = new ConcurrentHashMap<>();
    volatile int status = 200;

    @BeforeEach
    void localGateway() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext(
                "/gateway.do",
                e -> {
                    try {
                        calls.incrementAndGet();
                        var params = HttpDasGatewayTest.parse(e.getRequestURI().getRawQuery());
                        params.putAll(
                                HttpDasGatewayTest.parse(
                                        new String(
                                                e.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8)));
                        String signature = params.remove("sign");
                        // Independent test-side canonicalization, matching the official SDK
                        // contract.
                        String canonical =
                                params.entrySet().stream()
                                        .map(
                                                x ->
                                                        HttpDasGatewayTest.encode(x.getKey())
                                                                + "="
                                                                + HttpDasGatewayTest.encode(
                                                                        x.getValue()))
                                        .collect(java.util.stream.Collectors.joining("&"));
                        if (!HttpDasGatewayTest.hmac(canonical).equals(signature)) {
                            e.sendResponseHeaders(401, -1);
                            return;
                        }
                        validSignatures.incrementAndGet();
                        String phone = mapper.readTree(params.get("params")).path("phone").asText();
                        perPhone.computeIfAbsent(phone, k -> new AtomicInteger()).incrementAndGet();
                        String response =
                                mapper.writeValueAsString(
                                        Map.of(
                                                "result_code",
                                                "OK",
                                                "req_msg_id",
                                                "local-" + phone.substring(7),
                                                "data",
                                                "[{\"hour\":9,\"score\":0.82}]"));
                        byte[] bytes =
                                HttpDasGatewayTest.signed(response)
                                        .getBytes(StandardCharsets.UTF_8);
                        e.sendResponseHeaders(status, bytes.length);
                        e.getResponseBody().write(bytes);
                    } catch (Exception expected) {
                        throw new RuntimeException(expected);
                    } finally {
                        e.close();
                    }
                });
        server.start();
        var env =
                new MockEnvironment()
                        .withProperty("ENV", "test")
                        .withProperty("DAS_ENABLED", "true")
                        .withProperty("DAS_ALLOW_LOCAL_HTTP", "true")
                        .withProperty(
                                "DAS_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort())
                        .withProperty("DAS_ACCESS_KEY_ID", "synthetic-id")
                        .withProperty("DAS_ACCESS_KEY_SECRET", HttpDasGatewayTest.SECRET)
                        .withProperty("DAS_DATASET_ID", "local-dataset")
                        .withProperty("DAS_PRODUCT_INSTANCE_ID", "local-product");
        original = das.useGateway(new HttpDasGateway(new Config(env), mapper));
    }

    @AfterEach
    void stop() {
        das.useGateway(original);
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void overlappingApiJobsUseSignedGatewayOnceAndShareReturnedScores() throws Exception {
        String v = version(13510000000L, 6), p = partner(MatchAdapter.ANT_DAS);
        List<String> jobs = new ArrayList<>();
        for (int i = 0; i < 4; i++) jobs.add(submit(p, v).path("id").asText());
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> results = new ArrayList<>();
            for (String job : jobs) results.add(pool.submit(() -> runToEnd(job)));
            for (var result : results) result.get(30, TimeUnit.SECONDS);
        }
        for (String job : jobs) runToEnd(job);
        assertThat(calls).hasValue(6);
        assertThat(validSignatures).hasValue(6);
        assertThat(perPhone.values()).allSatisfy(n -> assertThat(n.get()).isEqualTo(1));
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE tenant_id=? AND charged=TRUE",
                                tenant))
                .isEqualTo(6);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE tenant_id=? AND score=82 AND state IN ('success','cached')",
                                tenant))
                .isEqualTo(24);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_job WHERE tenant_id=? AND state='finished'",
                                tenant))
                .isEqualTo(4);
    }

    @Test
    void httpFailureMakesFreshJobsWaitForReviewInsteadOfRepeatingTheQuery() {
        status = 503;
        String v = version(13520000000L, 1), p = partner(MatchAdapter.ANT_DAS);
        String first = submit(p, v).path("id").asText();
        runToEnd(first);
        String again = submit(p, v).path("id").asText();
        runToEnd(again);
        assertThat(calls).hasValue(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND state='uncertain' AND charged=FALSE",
                                again))
                .isEqualTo(1);
    }
}
