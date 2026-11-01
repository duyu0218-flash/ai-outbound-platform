package com.duozhi.outbound.marketing.dataset;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.common.support.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

class MatchingIT extends DatasetTestSupport {
    @Autowired
    AntDasMatchAdapter das;

    DasGateway original;

    /** Scripted DAS transport: records every upstream call per phone. */
    static class Scripted implements DasGateway {
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        final List<String> params = Collections.synchronizedList(new ArrayList<>());
        volatile Function<String, Response> answer;

        Scripted(Function<String, Response> answer) {
            this.answer = answer;
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public Response query(String phone, int topN, String authorizationRef) throws Exception {
            calls.computeIfAbsent(phone, k -> new AtomicInteger()).incrementAndGet();
            params.add(phone + "|" + topN + "|" + authorizationRef);
            Thread.sleep(5);
            return answer.apply(phone);
        }

        int total() {
            return calls.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }

    static DasGateway.Response scores(String phone) {
        int last = phone.charAt(phone.length() - 1) - '0';
        if (last % 2 == 1)
            return new DasGateway.Response("10029", "rid-" + phone.substring(7), null);
        return new DasGateway.Response(
                "OK",
                "rid-" + phone.substring(7),
                "[{\"hour\":9,\"score\":\"0.82\"},{\"hour\":20,\"score\":0.61},{\"hour\":\"13\",\"score\":\"0.3\"},{\"hour\":7,\"score\":\"0.1\"}]");
    }

    @AfterEach
    void restore() {
        if (original != null) das.useGateway(original);
    }

    Scripted install(Function<String, DasGateway.Response> answer) {
        var s = new Scripted(answer);
        DasGateway previous = das.useGateway(s);
        if (original == null) original = previous;
        return s;
    }

    @Test
    void dasWithoutCredentialsIsRefusedWith503AndInventsNothing() {
        String version = version(13200000000L, 3);
        String partner = partner(MatchAdapter.ANT_DAS);
        var r =
                req(
                        admin,
                        HttpMethod.POST,
                        "/api/v1/marketing/match-jobs",
                        Map.of("partnerId", partner, "versionId", version));
        assertThat(r.getStatusCode().value()).isEqualTo(503);
        assertThat(r.getBody().toString()).contains("意愿分服务尚未配置");
        assertThat(count("SELECT COUNT(*) FROM mk_match_result WHERE tenant_id=?", tenant))
                .isZero();
    }

    @Test
    void dasStateMachineSendsEachRequestOnceEvenWithConcurrentWorkers() throws Exception {
        var gateway = install(MatchingIT::scores);
        String version = version(13100000000L, 40);
        String partner = partner(MatchAdapter.ANT_DAS);
        String job = submit(partner, version).path("id").asText();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND state='reserved'",
                                job))
                .isEqualTo(40);

        var pool = Executors.newFixedThreadPool(3);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 3; i++) futures.add(pool.submit(() -> runToEnd(job)));
        for (var f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        runToEnd(job);

        assertThat(gateway.calls).hasSize(40);
        assertThat(gateway.calls.values()).allSatisfy(c -> assertThat(c.get()).isEqualTo(1));
        assertThat(gateway.params)
                .allSatisfy(p -> assertThat(p).matches("13100000[0-9]{3}\\|3\\|AUTH-" + tenant));
        var counts = matching.job(tenant, job);
        assertThat(counts.get("state")).isEqualTo("finished");
        @SuppressWarnings("unchecked")
        var c = (Map<String, Object>) counts.get("counts");
        assertThat(c)
                .containsEntry("success", 20L)
                .containsEntry("no_data", 20L)
                .containsEntry("charged", 40L);
        var best =
                jdbc.queryForMap(
                        "SELECT hit,score,grade,best_hours FROM mk_match_request WHERE job_id=? AND state='success' LIMIT 1",
                        job);
        assertThat(((Number) best.get("score")).doubleValue()).isEqualTo(82.0);
        assertThat(best.get("grade")).isEqualTo("A");
        assertThat(best.get("best_hours").toString()).isEqualTo("[9, 20, 13]");

        // Re-running a finished job, or matching the same numbers again, never calls upstream.
        assertThat(matching.step(job)).isFalse();
        String again = submit(partner, version).path("id").asText();
        runToEnd(again);
        assertThat(gateway.total()).isEqualTo(40);
        @SuppressWarnings("unchecked")
        var c2 = (Map<String, Object>) matching.job(tenant, again).get("counts");
        assertThat(c2).containsEntry("cached", 40L).containsEntry("charged", 0L);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_result WHERE tenant_id=? AND partner_id=?",
                                tenant,
                                partner))
                .isEqualTo(40);
    }

    @Test
    void transportErrorsPauseTheJobAndUncertainRequestsAreNeverResent() {
        var gateway =
                install(
                        phone -> {
                            throw new IllegalStateException("socket reset with secret " + phone);
                        });
        String version = version(13000000000L, 30);
        String partner = partner(MatchAdapter.ANT_DAS);
        String job = submit(partner, version).path("id").asText();
        runToEnd(job);
        var paused = matching.job(tenant, job);
        assertThat(paused.get("state")).isEqualTo("paused");
        assertThat(paused.get("pause_reason").toString()).contains("已暂停");
        assertThat(paused.get("pause_reason").toString()).doesNotContain("secret");
        int firstWave = gateway.total();
        assertThat(firstWave).isBetween(5, 8);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND state='uncertain'",
                                job))
                .isEqualTo(firstWave);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND state='uncertain' AND message LIKE '%secret%'",
                                job))
                .isZero();

        // Upstream recovers: resuming sends only the reserved requests.
        gateway.answer = MatchingIT::scores;
        admin(HttpMethod.POST, "/api/v1/marketing/match-jobs/" + job + "/resume", null);
        runToEnd(job);
        assertThat(matching.job(tenant, job).get("state")).isEqualTo("finished");
        assertThat(gateway.calls).hasSize(30);
        assertThat(gateway.calls.values()).allSatisfy(n -> assertThat(n.get()).isEqualTo(1));
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND state='uncertain'",
                                job))
                .isEqualTo(firstWave);
        assertThat(count("SELECT COUNT(*) FROM mk_match_result WHERE tenant_id=?", tenant))
                .isEqualTo(30 - firstWave);
    }

    @Test
    void criticalUpstreamCodePausesImmediately() {
        var gateway = install(phone -> new DasGateway.Response("10027", "", null));
        String job =
                submit(partner(MatchAdapter.ANT_DAS), version(13310000000L, 20))
                        .path("id")
                        .asText();
        runToEnd(job);
        assertThat(matching.job(tenant, job).get("state")).isEqualTo("paused");
        assertThat(gateway.total()).isLessThanOrEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT message FROM mk_match_request WHERE job_id=? AND state='failed' LIMIT 1",
                                String.class,
                                job))
                .contains("授权");
    }

    @Test
    void scoreParsingFollowsAntPlatformRules() {
        assertThat(
                        das.parseScores(
                                "[{\"hour\":\"09\",\"score\":\"5\"},{\"hour\":23,\"score\":7}]", 1))
                .extracting(AntDasMatchAdapter.HourScore::hour)
                .containsExactly(23);
        for (String bad :
                List.of(
                        "[{\"hour\":24,\"score\":1}]",
                        "[{\"hour\":true,\"score\":1}]",
                        "[{\"hour\":1,\"score\":-1}]",
                        "[{\"hour\":1,\"score\":1},{\"hour\":1,\"score\":2}]",
                        "{\"hour\":1}",
                        "not json"))
            assertThatThrownBy(() -> das.parseScores(bad, 3))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mockMatchIsDeterministicCachedAndAnalysed() {
        String version = version(13900000000L, 120);
        String partner = partner(MatchAdapter.MOCK);
        String first = submit(partner, version).path("id").asText();
        admin(HttpMethod.POST, "/api/v1/marketing/match-jobs/" + first + "/run", null);
        runToEnd(first);
        var a = admin(HttpMethod.GET, "/api/v1/marketing/match-jobs/" + first + "/analysis", null);
        long hits =
                count("SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND hit=TRUE", first);
        assertThat(hits).isBetween(50L, 110L);
        assertThat(a.path("hits").asLong()).isEqualTo(hits);
        assertThat(a.path("answered").asLong()).isEqualTo(120);
        assertThat(a.path("score_distribution")).hasSize(10);
        long distributed = 0;
        for (var b : a.path("score_distribution")) distributed += b.path("count").asLong();
        assertThat(distributed).isEqualTo(hits);
        long graded = 0;
        for (var g : List.of("A", "B", "C", "D")) graded += a.path("grades").path(g).asLong();
        assertThat(graded).isEqualTo(hits);
        assertThat(a.path("best_hours")).hasSize(24);
        assertThat(a.path("overlap").asLong()).isZero();

        String second = submit(partner, version).path("id").asText();
        runToEnd(second);
        var b = admin(HttpMethod.GET, "/api/v1/marketing/match-jobs/" + second + "/analysis", null);
        assertThat(b.path("job").path("counts").path("cached").asLong()).isEqualTo(120);
        assertThat(b.path("job").path("counts").path("charged").asLong()).isZero();
        assertThat(b.path("hits").asLong()).isEqualTo(hits);
        assertThat(b.path("overlap").asLong()).isEqualTo(120);
        assertThat(b.path("overlap_jobs").get(0).path("job_id").asText()).isEqualTo(first);
        // Expired cache entries are charged again.
        jdbc.update(
                "UPDATE mk_match_result SET expires_at=NOW(6) - INTERVAL 1 DAY WHERE tenant_id=?",
                tenant);
        String third = submit(partner, version).path("id").asText();
        runToEnd(third);
        assertThat(((Map<?, ?>) matching.job(tenant, third).get("counts")).get("charged"))
                .isEqualTo(120L);
    }

    @Test
    void hashFileExchangeExportsSha256AndImportsHits() {
        String version = version(13800000100L, 5);
        String partner = partner(MatchAdapter.HASH_FILE);
        var job = submit(partner, version);
        assertThat(job.path("state").asText()).isEqualTo("awaiting_file");
        String id = job.path("id").asText();
        var r =
                http.exchange(
                        "/api/v1/marketing/match-jobs/" + id + "/hash-file",
                        HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(bearer()),
                        String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        var lines = r.getBody().trim().split("\n");
        assertThat(lines).hasSize(6);
        assertThat(lines[0]).isEqualTo("sha256");
        assertThat(r.getBody()).doesNotContain("1380000010");
        String hit1 = JsonSupport.sha("13800000100"), hit2 = JsonSupport.sha("13800000102");
        assertThat(lines).contains(hit1, hit2);
        String file =
                "sha256,hit,score,labels,best_hours\n"
                        + hit1
                        + ",1,0.9,高净值|近期活跃,10|19\n"
                        + hit2
                        + ",1,55,,\n"
                        + JsonSupport.sha("x")
                        + ",1,1,,\n";
        var result =
                ok(
                        multipart(
                                "/api/v1/marketing/match-jobs/" + id + "/hit-file",
                                file.getBytes(StandardCharsets.UTF_8),
                                "hits.csv",
                                Map.of()));
        assertThat(result.path("matched").asInt()).isEqualTo(2);
        assertThat(result.path("missed").asInt()).isEqualTo(3);
        assertThat(result.path("unknown").asInt()).isEqualTo(1);
        var done = matching.job(tenant, id);
        assertThat(done.get("state")).isEqualTo("finished");
        var top =
                jdbc.queryForMap(
                        "SELECT score,grade,labels,best_hours FROM mk_match_request WHERE job_id=? AND hit=TRUE ORDER BY score DESC LIMIT 1",
                        id);
        assertThat(((Number) top.get("score")).doubleValue()).isEqualTo(90.0);
        assertThat(top.get("grade")).isEqualTo("A");
        assertThat(top.get("labels").toString()).contains("高净值");
        assertThat(top.get("best_hours").toString()).isEqualTo("[10, 19]");
    }

    @Test
    void antPlatformResultCsvBecomesMatchResultsAndCache() {
        var gateway = install(MatchingIT::scores);
        String version = version(13700000100L, 4);
        String partner = partner(MatchAdapter.ANT_DAS);
        String csv =
                "﻿批次ID,原始行号,手机号,推荐序号,时间（小时）,分值,查询状态,结果码,上游请求ID,本次流水\n"
                        + "b1,2,13700000100,1,09:00,0.91,成功,OK,req-1,op-1\n"
                        + "b1,2,13700000100,2,21:00,0.40,成功,OK,req-1,op-1\n"
                        + "b1,3,13700000101,,,,无数据,10029,req-2,op-2\n"
                        + "b1,4,13700000102,,,,待核对,TRANSPORT_ERROR,,op-3\n"
                        + "b1,5,'=bad,,,,成功,OK,,op-4\n";
        var imported =
                ok(
                        multipart(
                                "/api/v1/marketing/match-imports/ant-platform",
                                csv.getBytes(StandardCharsets.UTF_8),
                                "ant.csv",
                                Map.of("partnerId", partner, "versionId", version)));
        assertThat(imported.path("imported").asInt()).isEqualTo(2);
        assertThat(imported.path("skipped_rows").asInt()).isEqualTo(1);
        assertThat(imported.path("invalid_rows").asInt()).isEqualTo(1);
        String job = imported.path("id").asText();
        var hit =
                jdbc.queryForMap(
                        "SELECT score,grade,best_hours,row_id,charged FROM mk_match_request WHERE job_id=? AND hit=TRUE",
                        job);
        assertThat(((Number) hit.get("score")).doubleValue()).isEqualTo(91.0);
        assertThat(hit.get("best_hours").toString()).isEqualTo("[9, 21]");
        assertThat(hit.get("row_id")).isNotNull();
        // A new DAS job reuses the imported answers and only pays for the two unknown numbers.
        String fresh = submit(partner, version).path("id").asText();
        runToEnd(fresh);
        assertThat(gateway.total()).isEqualTo(2);
        assertThat(gateway.calls.keySet()).containsExactlyInAnyOrder("13700000102", "13700000103");
    }

    org.springframework.http.HttpHeaders bearer() {
        var h = new org.springframework.http.HttpHeaders();
        h.setBearerAuth(admin);
        return h;
    }
}
