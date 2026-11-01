package com.duozhi.outbound.marketing.dataset;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.marketing.common.*;
import com.duozhi.outbound.messaging.service.Messaging;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

class SegmentSendIT extends DatasetTestSupport {
    @Autowired
    TouchEvents touches;
    @Autowired
    PhoneIdentity phones;
    @Autowired
    SnapshotMembers members;
    @Autowired
    ChannelSender sender;
    @Autowired
    SnapshotChannelSender snapshotSender;
    @Autowired
    Messaging messaging;

    String version, job;
    long hits;

    @BeforeEach
    void matched() {
        version = version(13600000000L, 200);
        job = submit(partner(MatchAdapter.MOCK), version).path("id").asText();
        runToEnd(job);
        hits = count("SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND hit=TRUE", job);
        assertThat(hits).isGreaterThan(60);
    }

    long preview(Map<String, Object> rules) {
        return admin(HttpMethod.POST, "/api/v1/marketing/segments/preview", Map.of("rules", rules))
                .path("count")
                .asLong();
    }

    @Test
    void recentTouchWindowsUseUtcOnAShanghaiServer() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            String hash =
                    jdbc.queryForObject(
                            "SELECT phone_hash FROM mk_match_request WHERE job_id=? AND hit=TRUE ORDER BY position LIMIT 1",
                            String.class,
                            job);
            touches.record(
                    new TouchEvents.Touch(
                            tenant,
                            hash,
                            null,
                            OutcomeCodes.CALL,
                            OutcomeCodes.CALL_NO_ANSWER,
                            "test",
                            "recent-call-" + tenant,
                            null,
                            null,
                            com.duozhi.outbound.common.dao.Store.now().minusHours(23)));
            assertThat(preview(Map.of("matchJobId", job, "hitOnly", true, "noCallDays", 1)))
                    .isEqualTo(hits - 1);
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void rulesCombineDatasetMatchScoresAndTouchHistory() {
        assertThat(preview(Map.of("versionIds", List.of(version)))).isEqualTo(200);
        assertThat(
                        preview(
                                Map.of(
                                        "versionIds",
                                        List.of(version),
                                        "matchJobId",
                                        job,
                                        "hitOnly",
                                        true)))
                .isEqualTo(hits);
        assertThat(preview(Map.of("matchJobId", job, "hitOnly", true))).isEqualTo(hits);
        long gradeA =
                count(
                        "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND hit=TRUE AND grade='A'",
                        job);
        assertThat(
                        preview(
                                Map.of(
                                        "versionIds",
                                        List.of(version),
                                        "matchJobId",
                                        job,
                                        "grades",
                                        List.of("A"))))
                .isEqualTo(gradeA);
        long high =
                count(
                        "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND hit=TRUE AND score>=50 AND score<=90",
                        job);
        assertThat(preview(Map.of("matchJobId", job, "scoreMin", 50, "scoreMax", 90)))
                .isEqualTo(high);
        long labelled =
                count(
                        "SELECT COUNT(*) FROM mk_match_request WHERE job_id=? AND hit=TRUE AND JSON_CONTAINS(labels,'\"老客户\"')",
                        job);
        assertThat(preview(Map.of("matchJobId", job, "labels", List.of("老客户"))))
                .isEqualTo(labelled);

        String called =
                jdbc.queryForObject(
                        "SELECT phone_hash FROM mk_match_request WHERE job_id=? AND hit=TRUE ORDER BY position LIMIT 1",
                        String.class,
                        job);
        String texted =
                jdbc.queryForObject(
                        "SELECT phone_hash FROM mk_match_request WHERE job_id=? AND hit=TRUE ORDER BY position LIMIT 1 OFFSET 1",
                        String.class,
                        job);
        touches.record(
                new TouchEvents.Touch(
                        tenant,
                        called,
                        null,
                        OutcomeCodes.CALL,
                        OutcomeCodes.CALL_NO_ANSWER,
                        "test",
                        "call-" + tenant,
                        null,
                        null,
                        null));
        touches.record(
                new TouchEvents.Touch(
                        tenant,
                        texted,
                        null,
                        OutcomeCodes.SMS,
                        OutcomeCodes.SMS_DELIVERED,
                        "test",
                        "sms-" + tenant,
                        null,
                        null,
                        null));
        var hitRules =
                new HashMap<String, Object>(
                        Map.of("versionIds", List.of(version), "matchJobId", job, "hitOnly", true));
        hitRules.put("noCallDays", 7);
        assertThat(preview(hitRules)).isEqualTo(hits - 1);
        hitRules.remove("noCallDays");
        hitRules.put("linkNotClickedDays", 3);
        assertThat(preview(hitRules)).isEqualTo(1);
        touches.record(
                new TouchEvents.Touch(
                        tenant,
                        texted,
                        null,
                        OutcomeCodes.LINK,
                        OutcomeCodes.LINK_CLICKED,
                        "test",
                        "link-" + tenant,
                        null,
                        null,
                        null));
        assertThat(preview(hitRules)).isZero();
        hitRules.remove("linkNotClickedDays");
        hitRules.put("excludeCodes", List.of(OutcomeCodes.CALL_NO_ANSWER));
        assertThat(preview(hitRules)).isEqualTo(hits - 1);

        assertThat(
                        req(
                                        admin,
                                        HttpMethod.POST,
                                        "/api/v1/marketing/segments/preview",
                                        Map.of("rules", Map.of("grades", List.of("A"))))
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        assertThat(
                        req(
                                        agent,
                                        HttpMethod.POST,
                                        "/api/v1/marketing/segments/preview",
                                        Map.of("rules", Map.of("matchJobId", job)))
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    JsonNode snapshot(int controlPercent) {
        var segment =
                admin(
                        HttpMethod.POST,
                        "/api/v1/marketing/segments",
                        Map.of(
                                "name",
                                "高意向",
                                "rules",
                                Map.of(
                                        "versionIds",
                                        List.of(version),
                                        "matchJobId",
                                        job,
                                        "hitOnly",
                                        true)));
        return admin(
                HttpMethod.POST,
                "/api/v1/marketing/segments/" + segment.path("id").asText() + "/snapshots",
                Map.of("controlPercent", controlPercent));
    }

    @Test
    void freezingProducesAnImmutableSnapshotWithDeterministicControlGroup() {
        var snap = snapshot(20);
        String id = snap.path("id").asText();
        long size = snap.path("size").asLong(), control = snap.path("control_size").asLong();
        assertThat(size).isEqualTo(hits);
        assertThat(snap.path("test_size").asLong() + control).isEqualTo(size);
        assertThat(control).isBetween(size / 20, size * 2 / 5);
        assertThat(members.size(tenant, id)).isEqualTo(size);
        var first = members.page(tenant, id, 0, 10);
        assertThat(first)
                .extracting(SnapshotMembers.Member::sequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(members.page(tenant, id, 10, 1000)).hasSize((int) size - 10);
        assertThat(first).allSatisfy(m -> assertThat(m.group()).isIn("test", "control"));
        assertThat(phones.decrypt(tenant, first.getFirst().phoneCipher()))
                .startsWith("+8613600000");
        // The split is a pure function of (snapshot id, phone hash).
        long recomputed =
                count(
                        "SELECT COUNT(*) FROM mk_snapshot_member WHERE snapshot_id=? AND (CAST(CONV(SUBSTRING(SHA2(CONCAT(?,phone_hash),256),1,8),16,10) AS UNSIGNED) % 10000 < 2000) = (grp='control')",
                        id, id);
        assertThat(recomputed).isEqualTo(size);
        // New match results do not change a frozen snapshot.
        jdbc.update("UPDATE mk_match_request SET hit=FALSE WHERE job_id=?", job);
        assertThat(members.size(tenant, id)).isEqualTo(size);
        assertThat(preview(Map.of("matchJobId", job, "hitOnly", true))).isZero();
    }

    String resource(String kind, Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        var r =
                (Map<String, Object>)
                        messaging.createResource(
                                tenant, kind, Map.of("name", kind + "测试", "payload", payload));
        String id = r.get("id").toString();
        messaging.resourceState(tenant, id, "APPROVED");
        return id;
    }

    @Test
    void bulkCallAndSmsTasksAdmitAttributeAndHoldOutTheControlGroup() {
        var snap = snapshot(20);
        String id = snap.path("id").asText();
        long size = snap.path("size").asLong(),
                control = snap.path("control_size").asLong(),
                test = size - control;
        var blockedMember =
                members.page(tenant, id, 0, 1000).stream()
                        .filter(m -> m.group().equals("test"))
                        .findFirst()
                        .orElseThrow();
        String blockedPhone = phones.decrypt(tenant, blockedMember.phoneCipher()).substring(3);
        jdbc.update(
                "INSERT INTO phonesuppression(tenant_id,phone,reason,created_at) VALUES(?,?,?,NOW(3))",
                tenant,
                blockedPhone,
                "test");

        long campaign =
                admin(
                                HttpMethod.POST,
                                "/api/v1/campaigns",
                                Map.of("name", "营销外呼", "mode", "ai_only"))
                        .path("id")
                        .asLong();
        var task =
                admin(
                        HttpMethod.POST,
                        "/api/v1/marketing/send-tasks",
                        Map.of("snapshotId", id, "channel", "call", "campaignId", campaign));
        String taskId = task.path("id").asText();
        sends.run(tenant, taskId, 10);
        var done = admin(HttpMethod.GET, "/api/v1/marketing/send-tasks/" + taskId, null);
        assertThat(done.path("state").asText()).isEqualTo("finished");
        assertThat(done.path("total").asLong()).isEqualTo(size);
        assertThat(done.path("processed").asLong()).isEqualTo(size);
        assertThat(done.path("held_out").asLong()).isEqualTo(control);
        assertThat(done.path("rejected").asLong()).isEqualTo(1);
        assertThat(done.path("accepted").asLong()).isEqualTo(test - 1);
        assertThat(done.path("reasons").get(0).path("reason").asText()).isEqualTo("通话黑名单");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_dispatch WHERE tenant_id=? AND snapshot_id=? AND channel='call' AND target_type='callsession'",
                                tenant,
                                id))
                .isEqualTo(test - 1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM callsession WHERE tenant_id=? AND campaign_id=? AND status='CREATED'",
                                tenant,
                                campaign))
                .isEqualTo(test - 1);
        assertThat(count("SELECT COUNT(*) FROM campaigncontact WHERE campaign_id=?", campaign))
                .isEqualTo(test - 1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM callsession WHERE tenant_id=? AND phone=?",
                                tenant,
                                blockedPhone))
                .isZero();
        // Sending the same member again is idempotent.
        var member =
                members.page(tenant, id, 0, 1000).stream()
                        .filter(
                                m ->
                                        m.group().equals("test")
                                                && !m.phoneHash().equals(blockedMember.phoneHash()))
                        .findFirst()
                        .orElseThrow();
        var origin = new ChannelSender.Origin(id, null, null);
        var recipient = new ChannelSender.Recipient(member.phoneHash(), member.phoneCipher(), null);
        var once = sender.call(tenant, origin, recipient, new ChannelSender.Call(campaign));
        assertThat(
                        sender.call(tenant, origin, recipient, new ChannelSender.Call(campaign))
                                .targetId())
                .isEqualTo(once.targetId());
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM callsession WHERE tenant_id=? AND campaign_id=?",
                                tenant,
                                campaign))
                .isEqualTo(test - 1);

        // SMS through the messaging module, attributed to the snapshot.
        String product =
                resource(
                        "product",
                        Map.of("form", "sms", "unit_price", 1000, "daily_recipient_limit", 100));
        String sender = resource("sender", Map.of("brand", "营销测试"));
        String template =
                resource("template", Map.of("form", "sms", "body", "您好，{{name}}，秋季活动已开始。"));
        messaging.credit(tenant, "mk-credit-" + tenant, 1_000_000_000L);
        Map<String, Object> sms =
                Map.of(
                        "productId",
                        product,
                        "senderId",
                        sender,
                        "templateId",
                        template,
                        "variables",
                        Map.of("name", "客户"));
        String smsTask =
                admin(
                                HttpMethod.POST,
                                "/api/v1/marketing/send-tasks",
                                Map.of("snapshotId", id, "channel", "sms", "sms", sms))
                        .path("id")
                        .asText();
        sends.run(tenant, smsTask, 10);
        var smsDone = sends.task(tenant, smsTask);
        assertThat(smsDone.get("state")).isEqualTo("finished");
        assertThat(((Number) smsDone.get("accepted")).longValue()).isEqualTo(test - 1);
        assertThat(((Number) smsDone.get("held_out")).longValue()).isEqualTo(control);
        assertThat(count("SELECT COUNT(*) FROM msg_message WHERE tenant_id=?", tenant))
                .isEqualTo(test - 1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM mk_dispatch WHERE tenant_id=? AND snapshot_id=? AND target_type='msg_message'",
                                tenant,
                                id))
                .isEqualTo(test - 1);
        var items =
                admin(
                        HttpMethod.GET,
                        "/api/v1/marketing/send-tasks/" + smsTask + "/items?state=rejected",
                        null);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("phone_mask").asText()).contains("****");

        if (!snapshotSender.linksAvailable()) {
            var withLink = new HashMap<>(sms);
            withLink.put("landingUrl", "https://example.com/autumn");
            var refused =
                    req(
                            admin,
                            HttpMethod.POST,
                            "/api/v1/marketing/send-tasks",
                            Map.of("snapshotId", id, "channel", "sms", "sms", withLink));
            assertThat(refused.getStatusCode().value()).isEqualTo(503);
            assertThat(refused.getBody().toString()).contains("短链模块未接入");
            var direct =
                    this.sender.sms(
                            tenant,
                            origin,
                            recipient,
                            new ChannelSender.Sms(
                                    null,
                                    product,
                                    template,
                                    sender,
                                    Map.of("name", "客户"),
                                    "https://example.com/autumn",
                                    null));
            assertThat(direct.accepted()).isFalse();
            assertThat(direct.reason()).isEqualTo("短链模块未接入");
        }
    }
}
