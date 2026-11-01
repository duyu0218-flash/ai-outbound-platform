package com.duozhi.outbound.marketing.journey;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.auth.support.UserRecords;
import com.duozhi.outbound.marketing.common.OutcomeCodes;
import com.duozhi.outbound.marketing.common.PhoneIdentity;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class JourneyEngineIT extends JourneyTestBase {

    private List<String> channels(PhoneIdentity.Phone p) {
        return sender.of(tenant).stream()
                .filter(s -> s.phoneHash().equals(p.hash()))
                .map(s -> s.channel() + ":" + templateOrCampaign(s.request()))
                .toList();
    }

    private static String templateOrCampaign(Object request) {
        if (request instanceof com.duozhi.outbound.marketing.common.ChannelSender.Sms s)
            return s.templateId();
        if (request instanceof com.duozhi.outbound.marketing.common.ChannelSender.Call c)
            return String.valueOf(c.campaignId());
        return "?";
    }

    private Map<String, String> steps(String memberId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var r :
                jdbc.queryForList(
                        "SELECT node_id,action FROM mk_journey_step WHERE member_id=? ORDER BY id",
                        memberId)) out.put((String) r.get("node_id"), (String) r.get("action"));
        return out;
    }

    @Test
    void smsWaitClickedThenCallElseSecondSmsEndToEnd() {
        var clicker = phone(1);
        var silent = phone(2);
        var control = phone(3);
        String snap =
                snapshot(
                        List.of(
                                member(1, clicker, "test"),
                                member(2, silent, "test"),
                                member(3, control, "control")));
        String jid = createJourney("短信点击后外呼", clickThenCall());
        publish(jid);
        assertThat(startJourney(jid, snap)).isEqualTo("running");

        drain();
        assertThat(channels(clicker)).containsExactly("sms:tpl-1");
        assertThat(channels(silent)).containsExactly("sms:tpl-1");
        assertThat(channels(control)).isEmpty();
        assertThat(memberRow(jid, clicker)).containsEntry("node_id", "w1");

        String clickerId = memberId(jid, clicker);
        clock.advance(Duration.ofHours(1));
        assertThat(touch(clicker, OutcomeCodes.LINK, OutcomeCodes.LINK_CLICKED, clickerId))
                .isNotNull();

        clock.advance(Duration.ofHours(22));
        drain();
        assertThat(sender.of(tenant)).hasSize(2);

        clock.advance(Duration.ofHours(1));
        drain();
        assertThat(channels(clicker)).containsExactly("sms:tpl-1", "call:42");
        assertThat(channels(silent)).containsExactly("sms:tpl-1", "sms:tpl-2");
        assertThat(channels(control)).isEmpty();

        assertThat(steps(clickerId))
                .containsExactly(
                        Map.entry("s1", "sent"),
                        Map.entry("w1", "waited"),
                        Map.entry("c1", "yes"),
                        Map.entry("call", "sent"),
                        Map.entry("end", "end"));
        assertThat(steps(memberId(jid, silent)))
                .containsEntry("c1", "no")
                .containsEntry("s2", "sent");
        assertThat(steps(memberId(jid, control)))
                .containsEntry("s1", "control")
                .containsEntry("c1", "no")
                .containsEntry("s2", "control")
                .containsEntry("end", "end");
        // Every send was attributed back to its journey member.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mk_dispatch WHERE tenant_id=? AND journey_id=? AND journey_member_id IS NOT NULL",
                                Long.class,
                                tenant,
                                jid))
                .isEqualTo(4);
        for (var p : List.of(clicker, silent, control))
            assertThat(memberRow(jid, p)).containsEntry("state", "done");
        var runtime = admin(HttpMethod.GET, "/api/v1/marketing/journeys/" + jid + "/runtime", null);
        assertThat(runtime.path("journey").path("status").asText()).isEqualTo("completed");
        var s1 = runtime.path("nodes").get(0);
        assertThat(s1.path("steps").path("sent").asInt()).isEqualTo(2);
        assertThat(s1.path("steps").path("control").asInt()).isEqualTo(1);
        assertThat(runtime.path("outcomes").get(0).path("code").asText())
                .isEqualTo(OutcomeCodes.LINK_CLICKED);
    }

    @Test
    void conditionWakesOnTouchEventsAndTimesOutToDefaultBranch() {
        var attributed = phone(11);
        var byPhone = phone(12);
        var timeout = phone(13);
        String snap =
                snapshot(
                        List.of(
                                member(1, attributed, "test"),
                                member(2, byPhone, "test"),
                                member(3, timeout, "test")));
        var def =
                Map.of(
                        "start",
                        "s1",
                        "nodes",
                        List.of(
                                node("s1", "sms", "sms", sms("tpl-1"), "next", "c1"),
                                node(
                                        "c1",
                                        "condition",
                                        "codes",
                                        List.of(OutcomeCodes.INTENT_A, OutcomeCodes.INTENT_B),
                                        "windowHours",
                                        48,
                                        "yes",
                                        "hot",
                                        "no",
                                        "s2"),
                                node("hot", "end", "name", "意向客户"),
                                node("s2", "sms", "sms", sms("tpl-2"), "next", "end"),
                                node("end", "end")));
        String jid = createJourney("意向判断", def);
        publish(jid);
        startJourney(jid, snap);
        drain();
        assertThat(memberRow(jid, attributed)).containsEntry("state", "waiting");

        clock.advance(Duration.ofHours(5));
        touch(attributed, OutcomeCodes.CALL, OutcomeCodes.INTENT_B, memberId(jid, attributed));
        // No dispatch link: matched by phone hash instead.
        touch(byPhone, OutcomeCodes.CALL, OutcomeCodes.INTENT_A, null);
        // Codes the condition does not look for wake the member but do not satisfy it.
        touch(timeout, OutcomeCodes.CALL, OutcomeCodes.INTENT_C, null);
        assertThat(memberRow(jid, attributed)).containsEntry("state", "ready");
        drain();
        assertThat(memberRow(jid, attributed))
                .containsEntry("state", "done")
                .containsEntry("node_id", "hot");
        assertThat(memberRow(jid, byPhone)).containsEntry("node_id", "hot");
        assertThat(memberRow(jid, timeout))
                .containsEntry("state", "waiting")
                .containsEntry("node_id", "c1");

        clock.fix(start.plus(Duration.ofHours(47)));
        drain();
        assertThat(channels(timeout)).containsExactly("sms:tpl-1");
        clock.fix(start.plus(Duration.ofHours(48)));
        drain();
        assertThat(channels(timeout)).containsExactly("sms:tpl-1", "sms:tpl-2");
        assertThat(steps(memberId(jid, timeout))).containsEntry("c1", "no");
        assertThat(channels(attributed)).containsExactly("sms:tpl-1");
    }

    @Test
    void publishedVersionsAreImmutableAndRunsKeepTheirVersion() {
        String jid = createJourney("版本测试", clickThenCall());
        var v1 = admin(HttpMethod.POST, "/api/v1/marketing/journeys/" + jid + "/publish", Map.of());
        assertThat(v1.path("version").asInt()).isEqualTo(1);
        assertThat(v1.path("created").asBoolean()).isTrue();
        String v1Definition =
                admin(HttpMethod.GET, "/api/v1/marketing/journeys/" + jid + "/versions/1", null)
                        .path("definition")
                        .toString();
        var again =
                admin(HttpMethod.POST, "/api/v1/marketing/journeys/" + jid + "/publish", Map.of());
        assertThat(again.path("version").asInt()).isEqualTo(1);
        assertThat(again.path("created").asBoolean()).isFalse();

        var changed = new LinkedHashMap<>(clickThenCall());
        var nodes = new ArrayList<Object>((List<?>) changed.get("nodes"));
        nodes.set(0, node("s1", "sms", "sms", sms("tpl-1b"), "next", "w1"));
        changed.put("nodes", nodes);
        admin(
                HttpMethod.PUT,
                "/api/v1/marketing/journeys/" + jid,
                Map.of("name", "版本测试", "definition", changed));
        assertThat(
                        admin(
                                        HttpMethod.POST,
                                        "/api/v1/marketing/journeys/" + jid + "/publish",
                                        Map.of())
                                .path("version")
                                .asInt())
                .isEqualTo(2);
        assertThat(
                        admin(
                                        HttpMethod.GET,
                                        "/api/v1/marketing/journeys/" + jid + "/versions/1",
                                        null)
                                .path("definition")
                                .toString())
                .isEqualTo(v1Definition)
                .contains("tpl-1\"");

        var p = phone(21);
        startJourney(jid, snapshot(List.of(member(1, p, "test"))));
        String v2Id =
                jdbc.queryForObject(
                        "SELECT id FROM mk_journey_version WHERE tenant_id=? AND journey_id=? AND version=2",
                        String.class,
                        tenant,
                        jid);
        assertThat(memberRow(jid, p)).containsEntry("version_id", v2Id);

        nodes.set(0, node("s1", "sms", "sms", sms("tpl-1c"), "next", "w1"));
        admin(HttpMethod.PUT, "/api/v1/marketing/journeys/" + jid, Map.of("definition", changed));
        admin(HttpMethod.POST, "/api/v1/marketing/journeys/" + jid + "/publish", Map.of());
        drain();
        assertThat(channels(p)).containsExactly("sms:tpl-1b");
        var detail = admin(HttpMethod.GET, "/api/v1/marketing/journeys/" + jid, null);
        assertThat(detail.path("run_version").asInt()).isEqualTo(2);
        assertThat(detail.path("latest_version").asInt()).isEqualTo(3);
        assertThat(detail.path("versions").size()).isEqualTo(3);
        // A running journey cannot be started twice.
        assertThat(
                        req(
                                        admin,
                                        HttpMethod.POST,
                                        "/api/v1/marketing/journeys/" + jid + "/start",
                                        Map.of("snapshotId", "x"))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
    }

    @Test
    void invalidDefinitionsAreRejectedWithReadableMessages() {
        var cyclic =
                Map.of(
                        "start",
                        "a",
                        "nodes",
                        List.of(
                                node("a", "wait", "hours", 1, "next", "b"),
                                node("b", "wait", "hours", 1, "next", "a")));
        var r =
                req(
                        admin,
                        HttpMethod.POST,
                        "/api/v1/marketing/journeys",
                        Map.of("name", "x", "definition", cyclic));
        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat(r.getBody().path("detail").asText()).contains("循环");
        var badCode =
                Map.of(
                        "nodes",
                        List.of(
                                node(
                                        "c",
                                        "condition",
                                        "codes",
                                        List.of("NOPE"),
                                        "yes",
                                        "e",
                                        "no",
                                        "e"),
                                node("e", "end")));
        assertThat(
                        req(
                                        admin,
                                        HttpMethod.POST,
                                        "/api/v1/marketing/journeys",
                                        Map.of("name", "x", "definition", badCode))
                                .getBody()
                                .path("detail")
                                .asText())
                .contains("未知结果码");
        assertThat(
                        req(agent, HttpMethod.GET, "/api/v1/marketing/journeys", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    @Test
    void concurrentWorkersClaimEachMemberOnceAndSendOnce() throws Exception {
        List<com.duozhi.outbound.marketing.common.SnapshotMembers.Member> list = new ArrayList<>();
        for (int i = 1; i <= 60; i++) list.add(member(i, phone(1000 + i), "test"));
        var def =
                Map.of(
                        "nodes",
                        List.of(
                                node("s1", "sms", "sms", sms("tpl-1"), "next", "end"),
                                node("end", "end")));
        String claimOnly = createJourney("并发领取", def);
        publish(claimOnly);
        startJourney(claimOnly, snapshot(list));

        var pool = Executors.newFixedThreadPool(6);
        try {
            var gate = new CountDownLatch(1);
            List<Future<JourneyEngine.Claim>> futures = new ArrayList<>();
            for (int t = 0; t < 6; t++)
                futures.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return engine.claim(15);
                                }));
            gate.countDown();
            List<String> claimed = new ArrayList<>();
            Set<String> tokens = new HashSet<>();
            for (var f : futures) {
                var c = f.get(60, TimeUnit.SECONDS);
                claimed.addAll(c.members());
                if (!c.members().isEmpty()) tokens.add(c.token());
            }
            Set<String> mine =
                    new HashSet<>(
                            jdbc.queryForList(
                                    "SELECT id FROM mk_journey_member WHERE tenant_id=? AND journey_id=?",
                                    String.class,
                                    tenant,
                                    claimOnly));
            // SKIP LOCKED lets a racing worker come back short instead of waiting; later passes
            // pick up the rest. No member is ever handed to two workers.
            assertThat(claimed).doesNotHaveDuplicates();
            assertThat(tokens.size()).isGreaterThan(1);
            for (int k = 0; k < 10; k++) claimed.addAll(engine.claim(15).members());
            List<String> ours = claimed.stream().filter(mine::contains).toList();
            assertThat(claimed).doesNotHaveDuplicates();
            assertThat(ours).hasSize(60);
            assertThat(engine.claim(50).members()).noneMatch(mine::contains);

            // An expired lease is recovered; the stale worker's token no longer works.
            String one = ours.getFirst();
            String oldToken =
                    jdbc.queryForObject(
                            "SELECT claim_token FROM mk_journey_member WHERE id=?",
                            String.class,
                            one);
            clock.advance(Duration.ofMinutes(6));
            assertThat(engine.recoverExpired()).isGreaterThanOrEqualTo(60);
            engine.run(one, oldToken);
            assertThat(sender.of(tenant)).isEmpty();
            jdbc.update(
                    "UPDATE mk_journey SET status='stopped' WHERE tenant_id=? AND id=?",
                    tenant,
                    claimOnly);

            // Full worker passes racing each other: every member gets exactly one SMS.
            List<com.duozhi.outbound.marketing.common.SnapshotMembers.Member> second =
                    new ArrayList<>();
            for (int i = 1; i <= 60; i++) second.add(member(i, phone(2000 + i), "test"));
            String racing = createJourney("并发执行", def);
            publish(racing);
            startJourney(racing, snapshot(second));
            var gate2 = new CountDownLatch(1);
            List<Future<?>> runs = new ArrayList<>();
            for (int t = 0; t < 6; t++)
                runs.add(
                        pool.submit(
                                () -> {
                                    gate2.await();
                                    for (int k = 0; k < 10; k++) engine.step(7);
                                    return null;
                                }));
            gate2.countDown();
            for (var f : runs) f.get(120, TimeUnit.SECONDS);
            drain();
            var sent = sender.of(tenant);
            assertThat(sent).hasSize(60);
            assertThat(sent.stream().map(JourneyFakes.Sent::journeyMemberId).distinct().count())
                    .isEqualTo(60);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM mk_journey_step WHERE tenant_id=? AND journey_id=? AND node_id='s1' AND action='sent'",
                                    Long.class,
                                    tenant,
                                    racing))
                    .isEqualTo(60);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pauseResumeAndStop() {
        var p = phone(31);
        var q = phone(32);
        var def =
                Map.of(
                        "nodes",
                        List.of(
                                node("w", "wait", "hours", 2, "next", "s1"),
                                node("s1", "sms", "sms", sms("tpl-1"), "next", "end"),
                                node("end", "end")));
        String jid = createJourney("暂停测试", def);
        publish(jid);
        startJourney(jid, snapshot(List.of(member(1, p, "test"), member(2, q, "test"))));
        drain();
        admin(HttpMethod.POST, "/api/v1/marketing/journeys/" + jid + "/pause", Map.of());
        clock.advance(Duration.ofHours(3));
        drain();
        assertThat(sender.of(tenant)).isEmpty();
        assertThat(
                        req(
                                        admin,
                                        HttpMethod.POST,
                                        "/api/v1/marketing/journeys/" + jid + "/pause",
                                        Map.of())
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        admin(HttpMethod.POST, "/api/v1/marketing/journeys/" + jid + "/resume", Map.of());
        drain();
        assertThat(sender.of(tenant)).hasSize(2);

        String other = createJourney("停止测试", def);
        publish(other);
        startJourney(other, snapshot(List.of(member(1, p, "test"))));
        var stopped =
                admin(HttpMethod.POST, "/api/v1/marketing/journeys/" + other + "/stop", Map.of());
        assertThat(stopped.path("status").asText()).isEqualTo("stopped");
        assertThat(memberRow(other, p)).containsEntry("state", "stopped");
        clock.advance(Duration.ofHours(3));
        drain();
        assertThat(sender.of(tenant)).hasSize(2);
    }

    @Test
    void estimateFiltersByAdmissionAndLargeStartsNeedASecondAdmin() {
        var ok = phone(41);
        var unsubscribed = phone(42);
        var control = phone(43);
        jdbc.update(
                "INSERT INTO msg_suppression(tenant_id,recipient_hash,reason) VALUES(?,?,?)",
                tenant,
                unsubscribed.hash(),
                "TD");
        String snap =
                snapshot(
                        List.of(
                                member(1, ok, "test"),
                                member(2, unsubscribed, "test"),
                                member(3, control, "control")));
        String jid = createJourney("审批测试", clickThenCall());
        publish(jid);
        var estimate =
                admin(
                        HttpMethod.POST,
                        "/api/v1/marketing/journeys/" + jid + "/estimate",
                        Map.of("snapshotId", snap));
        assertThat(estimate.path("total").asInt()).isEqualTo(3);
        assertThat(estimate.path("control").asInt()).isEqualTo(1);
        assertThat(estimate.path("reachable").asInt()).isEqualTo(1);
        assertThat(estimate.path("filtered").asInt()).isEqualTo(1);
        assertThat(estimate.path("filtered_reasons").path("已退订").asInt()).isEqualTo(1);
        // Worst path: SMS 0.05 + call 0.15 (the call branch costs more than the second SMS).
        assertThat(estimate.path("cost_per_member").decimalValue()).isEqualByComparingTo("0.20");
        assertThat(estimate.path("needs_approval").asBoolean()).isFalse();

        admin(HttpMethod.PUT, "/api/v1/marketing/policy", Map.of("approval_threshold", "0.10"));
        assertThat(startJourney(jid, snap)).isEqualTo("pending_approval");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mk_journey_member WHERE tenant_id=? AND journey_id=?",
                                Long.class,
                                tenant,
                                jid))
                .isZero();
        var self =
                req(
                        admin,
                        HttpMethod.POST,
                        "/api/v1/marketing/journeys/" + jid + "/approve",
                        Map.of());
        assertThat(self.getStatusCode().value()).isEqualTo(403);
        assertThat(self.getBody().path("detail").asText()).contains("另一位管理员");

        String second = crypto.token(UserRecords.map(user("admin")));
        var approved =
                ok(
                        req(
                                second,
                                HttpMethod.POST,
                                "/api/v1/marketing/journeys/" + jid + "/approve",
                                Map.of()));
        assertThat(approved.path("status").asText()).isEqualTo("running");
        assertThat(approved.path("estimate").path("estimated_cost").decimalValue())
                .isEqualByComparingTo("0.20");
        drain();
        assertThat(sender.of(tenant)).hasSize(1);
        assertThat(sender.of(tenant).getFirst().phoneHash()).isEqualTo(ok.hash());
        // The unsubscribed member was enrolled but its send was denied by admission.
        assertThat(steps(memberId(jid, unsubscribed))).containsEntry("s1", "denied");
    }
}
