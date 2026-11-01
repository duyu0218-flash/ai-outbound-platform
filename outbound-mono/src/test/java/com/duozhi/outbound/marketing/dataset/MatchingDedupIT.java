package com.duozhi.outbound.marketing.dataset;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class MatchingDedupIT extends DatasetTestSupport {
    @Autowired
    AntDasMatchAdapter das;
    @Autowired
    PhoneIdentity phones;
    @Autowired
    JsonSupport json;
    @Autowired
    Config config;
    @Autowired
    List<MatchAdapter> adapters;
    DasGateway original;

    @AfterEach
    void restore() {
        if (original != null) das.useGateway(original);
    }

    MatchingIT.Scripted install(java.util.function.Function<String, DasGateway.Response> answer) {
        var g = new MatchingIT.Scripted(answer);
        original = das.useGateway(g);
        return g;
    }

    Matching otherInstance() {
        var ds =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var db = new MarketingStore(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        return new Matching(db, phones, json, config, tx, adapters, das, new MatchFlights(db, tx));
    }

    static void drain(Matching m, String job) {
        for (int i = 0; i < 200 && m.step(job); i++) {}
    }

    String job(String partner, String version) {
        return submit(partner, version).path("id").asText();
    }

    @Test
    void overlappingJobsAcrossIndependentInstancesShareExactlyOneQueryPerNumber() throws Exception {
        var g = install(MatchingIT::scores);
        String v = version(13400000000L, 20), p = partner(MatchAdapter.ANT_DAS);
        List<String> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) jobs.add(job(p, v));
        Matching other = otherInstance();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) {
                String id = jobs.get(i);
                Matching worker = i % 2 == 0 ? matching : other;
                tasks.add(pool.submit(() -> drain(worker, id)));
            }
            for (var t : tasks) t.get(60, TimeUnit.SECONDS);
        }
        for (String id : jobs) runToEnd(id);
        assertThat(g.calls).hasSize(20);
        assertThat(g.calls.values()).allSatisfy(n -> assertThat(n.get()).isEqualTo(1));
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE tenant_id=? AND charged=TRUE",
                                tenant))
                .isEqualTo(20);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE tenant_id=? AND state IN ('success','no_data','cached')",
                                tenant))
                .isEqualTo(160);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_job WHERE tenant_id=? AND state='finished'",
                                tenant))
                .isEqualTo(8);
    }

    @Test
    void waiterHasNoChargeAndReceivesOwnersResultAfterCompletion() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var g =
                install(
                        phone -> {
                            entered.countDown();
                            try {
                                if (!release.await(15, TimeUnit.SECONDS))
                                    throw new IllegalStateException("test gate timeout");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                            return MatchingIT.scores(phone);
                        });
        String v = version(13410000000L, 1),
                p = partner(MatchAdapter.ANT_DAS),
                owner = job(p, v),
                waiter = job(p, v);
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<?> running = pool.submit(() -> runToEnd(owner));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                drain(otherInstance(), waiter);
                assertThat(
                                count(
                                        "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND state='waiting' AND charged=FALSE AND shared_request_id IS NOT NULL",
                                        waiter))
                        .isEqualTo(1);
                assertThat(matching.job(tenant, waiter).get("state")).isEqualTo("running");
            } finally {
                release.countDown();
            }
            running.get(30, TimeUnit.SECONDS);
        }
        // A long-offline waiter must retain the first answer even after a cache generation changes.
        jdbc.update(
                "UPDATE mk_match_result SET expires_at=NOW(6)-INTERVAL 1 DAY WHERE tenant_id=?",
                tenant);
        jdbc.update(
                "UPDATE mk_match_flight SET expires_at=NOW(6)-INTERVAL 1 DAY WHERE tenant_id=?",
                tenant);
        g.answer =
                phone -> new DasGateway.Response("OK", "renewed", "[{\"hour\":9,\"score\":0.12}]");
        String renewed = job(p, v);
        runToEnd(renewed);
        runToEnd(waiter);
        assertThat(g.total()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT score FROM mk_match_request WHERE job_id=?",
                                java.math.BigDecimal.class,
                                waiter))
                .isEqualByComparingTo("82.00");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT score FROM mk_match_request WHERE job_id=?",
                                java.math.BigDecimal.class,
                                renewed))
                .isEqualByComparingTo("12.00");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND state='cached' AND charged=FALSE",
                                waiter))
                .isEqualTo(1);
    }

    @Test
    void timeoutAndDefiniteErrorBothBlockFreshJobsUntilVerifiedImport() {
        var g =
                install(
                        phone -> {
                            throw new RuntimeException("synthetic connection closed");
                        });
        String v = version(13420000000L, 1), p = partner(MatchAdapter.ANT_DAS);
        String owner = job(p, v);
        runToEnd(owner);
        String second = job(p, v);
        drain(otherInstance(), second);
        assertThat(g.total()).isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND state='uncertain' AND charged=FALSE AND code='SHARED_REVIEW'",
                                second))
                .isEqualTo(1);
        // Resolve by importing a confirmed previous response, not by issuing a new query.
        String csv =
                "批次ID,原始行号,手机号,推荐序号,时间（小时）,分值,查询状态,结果码,上游请求ID,本次流水\nold,1,13420000000,1,9,0.82,成功,OK,verified-1,op-old\n";
        matching.importAntPlatform(
                tenant, p, v, csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0);
        String third = job(p, v);
        runToEnd(third);
        assertThat(g.total()).isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND state='cached'",
                                third))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_flight WHERE tenant_id=? AND state='success'",
                                tenant))
                .isEqualTo(1);

        g.answer = phone -> new DasGateway.Response("10027", "rejected", null);
        String newVersion = version(13420000002L, 1), failed = job(p, newVersion);
        runToEnd(failed);
        String duplicate = job(p, newVersion);
        runToEnd(duplicate);
        assertThat(g.total()).isEqualTo(2);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND charged=FALSE AND state='uncertain'",
                                duplicate))
                .isEqualTo(1);
    }

    @Test
    void persistenceFailureAndStalePendingNeverGrantAnotherSendPermit() {
        var g = install(MatchingIT::scores);
        String v = version(13430000000L, 1), p = partner(MatchAdapter.ANT_DAS), owner = job(p, v);
        String trigger = "test_match_fail_" + tenant;
        var root =
                new org.springframework.jdbc.core.JdbcTemplate(
                        new DriverManagerDataSource(
                                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        root.execute(
                "CREATE TRIGGER "
                        + trigger
                        + " BEFORE INSERT ON mk_match_result FOR EACH ROW BEGIN IF NEW.tenant_id="
                        + tenant
                        + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic save failure'; END IF; END");
        try {
            runToEnd(owner);
        } finally {
            root.execute("DROP TRIGGER " + trigger);
        }
        assertThat(g.total()).isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND state='pending' AND charged=TRUE",
                                owner))
                .isEqualTo(1);
        String waiter = job(p, v);
        drain(otherInstance(), waiter);
        jdbc.update(
                "UPDATE mk_match_request SET sent_at=NOW(6)-INTERVAL 10 MINUTE WHERE job_id=?",
                owner);
        drain(otherInstance(), waiter);
        assertThat(g.total()).isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND state='uncertain' AND charged=FALSE",
                                waiter))
                .isEqualTo(1);
        String fresh = job(p, v);
        runToEnd(fresh);
        assertThat(g.total()).isEqualTo(1);
    }

    @Test
    void independentJvmProcessesCannotClaimTheSameNumberInDifferentJobs() throws Exception {
        install(MatchingIT::scores);
        String v = version(13440000000L, 1), p = partner(MatchAdapter.ANT_DAS);
        List<Process> processes = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                String id = job(p, v);
                var pb =
                        new ProcessBuilder(
                                System.getProperty("java.home") + "/bin/java",
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                MatchFlightProcess.class.getName());
                pb.environment().put("MATCH_TEST_JDBC", MYSQL.getJdbcUrl());
                pb.environment().put("MATCH_TEST_USER", MYSQL.getUsername());
                pb.environment().put("MATCH_TEST_PASSWORD", MYSQL.getPassword());
                pb.environment().put("MATCH_TEST_JOB", id);
                processes.add(pb.redirectErrorStream(true).start());
            }
            int sends = 0;
            for (var process : processes) {
                assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
                String output =
                        new String(
                                process.getInputStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                assertThat(process.exitValue()).as(output).isZero();
                if (output.lines().anyMatch("SEND"::equals)) sends++;
            }
            assertThat(sends).isEqualTo(1);
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM mk_match_request WHERE tenant_id=? AND charged=TRUE",
                                    tenant))
                    .isEqualTo(1);
        } finally {
            processes.forEach(Process::destroyForcibly);
        }
    }

    @Test
    void differentProductsAndTenantsDoNotSharePrivateResults() {
        var g = install(MatchingIT::scores);
        String v = version(13450000000L, 1), p = partner(MatchAdapter.ANT_DAS);
        runToEnd(job(p, v));
        admin(
                HttpMethod.PUT,
                "/api/v1/marketing/partners/" + p,
                Map.of("name", "另一个产品", "adapter", "ant_das", "product", "another"));
        runToEnd(job(p, v));
        assertThat(g.total()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM mk_match_flight WHERE tenant_id=?", tenant))
                .isEqualTo(2);
        assertThat(
                        req(agent, HttpMethod.GET, "/api/v1/marketing/match-jobs", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        tenant += 100_000;
        var isolatedTenant = new com.duozhi.outbound.auth.domain.Tenant();
        isolatedTenant.setId(tenant);
        isolatedTenant.setCode("dedup-isolated-" + tenant);
        isolatedTenant.setName("隔离测试租户");
        tenants.insert(isolatedTenant);
        admin = crypto.token(com.duozhi.outbound.auth.support.UserRecords.map(user("admin")));
        agent = crypto.token(com.duozhi.outbound.auth.support.UserRecords.map(user("agent")));
        String isolated = version(13450000000L, 1), isolatedPartner = partner(MatchAdapter.ANT_DAS);
        runToEnd(job(isolatedPartner, isolated));
        assertThat(g.total()).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM mk_match_flight WHERE tenant_id=?", tenant))
                .isEqualTo(1);
    }

    @Test
    void partialOverlapOnlyQueriesTheUnionOfNumbers() throws Exception {
        var g = install(MatchingIT::scores);
        String p = partner(MatchAdapter.ANT_DAS);
        String a = job(p, version(13460000000L, 5)), b = job(p, version(13460000003L, 5));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> runToEnd(a));
            var second = pool.submit(() -> drain(otherInstance(), b));
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }
        runToEnd(a);
        runToEnd(b);
        assertThat(g.total()).isEqualTo(8);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE tenant_id=? AND charged=TRUE",
                                tenant))
                .isEqualTo(8);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE tenant_id=? AND state IN ('success','no_data','cached')",
                                tenant))
                .isEqualTo(10);
    }
}
