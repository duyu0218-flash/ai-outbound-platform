package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.marketing.MarketingTestBase;
import com.duozhi.outbound.marketing.common.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

/** Journey-module harness: fake P1 contracts, a fixed marketing clock and touch helpers. */
@Import(JourneyFakes.class)
abstract class JourneyTestBase extends MarketingTestBase {
    static final ZoneId ZONE = ZoneId.of(PolicyConfig.DEFAULT_ZONE);

    @Autowired
    MarketingClock clock;
    @Autowired
    JourneyFakes.Members members;
    @Autowired
    JourneyFakes.Sender sender;
    @Autowired
    PhoneIdentity phones;
    @Autowired
    TouchEvents touches;
    @Autowired
    JourneyEngine engine;
    @Autowired
    JourneyService journeys;
    @Autowired
    PolicyConfig policies;

    /** 10:00 local tomorrow: inside the default contact window, never in the real past. */
    Instant start;

    @BeforeEach
    void fixClock() {
        start = LocalDate.now(ZONE).plusDays(1).atTime(10, 0).atZone(ZONE).toInstant();
        clock.fix(start);
    }

    @AfterEach
    void releaseClock() {
        // Keep later tests' cross-tenant worker passes away from this tenant's members.
        jdbc.update(
                "UPDATE mk_journey SET status='stopped' WHERE tenant_id=? AND status IN ('running','paused')",
                tenant);
        clock.reset();
    }

    PhoneIdentity.Phone phone(int n) {
        return phones.of(tenant, String.format("139%08d", n));
    }

    SnapshotMembers.Member member(long sequence, PhoneIdentity.Phone p, String group) {
        return new SnapshotMembers.Member(
                sequence, p.hash(), phones.encrypt(tenant, p.e164()), null, group);
    }

    String snapshot(List<SnapshotMembers.Member> list) {
        String id = "snap-" + tenant + "-" + UUID.randomUUID().toString().substring(0, 8);
        members.put(id, list);
        return id;
    }

    /** Records a touch fact at the marketing clock, attributed to the member's last dispatch. */
    String touch(PhoneIdentity.Phone p, String channel, String code, String memberId) {
        Attribution.Dispatch d = null;
        if (memberId != null) {
            var row =
                    jdbc.queryForList(
                            "SELECT * FROM mk_dispatch WHERE tenant_id=? AND journey_member_id=? ORDER BY created_at DESC LIMIT 1",
                            tenant,
                            memberId);
            if (!row.isEmpty()) {
                var r = row.getFirst();
                d =
                        new Attribution.Dispatch(
                                (String) r.get("id"),
                                tenant,
                                (String) r.get("channel"),
                                (String) r.get("target_type"),
                                (String) r.get("target_id"),
                                p.hash(),
                                null,
                                (String) r.get("snapshot_id"),
                                (String) r.get("journey_id"),
                                memberId);
            }
        }
        return touches.record(
                new TouchEvents.Touch(
                        tenant,
                        p.hash(),
                        null,
                        channel,
                        code,
                        "test",
                        UUID.randomUUID().toString(),
                        d,
                        Map.of(),
                        clock.appNow()));
    }

    String memberId(String journeyId, PhoneIdentity.Phone p) {
        return jdbc.queryForObject(
                "SELECT id FROM mk_journey_member WHERE tenant_id=? AND journey_id=? AND phone_hash=?",
                String.class,
                tenant,
                journeyId,
                p.hash());
    }

    Map<String, Object> memberRow(String journeyId, PhoneIdentity.Phone p) {
        return jdbc.queryForMap(
                "SELECT * FROM mk_journey_member WHERE tenant_id=? AND journey_id=? AND phone_hash=?",
                tenant,
                journeyId,
                p.hash());
    }

    /** Runs worker passes until nothing of this tenant is due. */
    void drain() {
        for (int i = 0; i < 20; i++) {
            engine.step(500);
            Long due =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mk_journey_member WHERE tenant_id=? AND state IN ('ready','waiting') AND next_run_at<=?",
                            Long.class,
                            tenant,
                            clock.appNow());
            if (due == 0) return;
        }
    }

    static Map<String, Object> node(String id, String type, Object... kv) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        for (int i = 0; i < kv.length; i += 2) n.put((String) kv[i], kv[i + 1]);
        return n;
    }

    static Map<String, Object> sms(String template) {
        return Map.of(
                "applicationId",
                "app-1",
                "templateId",
                template,
                "landingUrl",
                "https://example.com/a");
    }

    /** SMS → wait 24h → clicked? → call : second SMS. */
    static Map<String, Object> clickThenCall() {
        return Map.of(
                "start",
                "s1",
                "nodes",
                List.of(
                        node("s1", "sms", "name", "首条短信", "sms", sms("tpl-1"), "next", "w1"),
                        node("w1", "wait", "hours", 24, "next", "c1"),
                        node(
                                "c1",
                                "condition",
                                "codes",
                                List.of(OutcomeCodes.LINK_CLICKED),
                                "windowHours",
                                0,
                                "yes",
                                "call",
                                "no",
                                "s2"),
                        node("call", "call", "campaignId", 42, "next", "end"),
                        node("s2", "sms", "sms", sms("tpl-2"), "next", "end"),
                        node("end", "end")));
    }

    String createJourney(String name, Map<String, ?> definition) {
        return admin(
                        HttpMethod.POST,
                        "/api/v1/marketing/journeys",
                        Map.of("name", name, "definition", definition))
                .path("id")
                .asText();
    }

    void publish(String journeyId) {
        admin(HttpMethod.POST, "/api/v1/marketing/journeys/" + journeyId + "/publish", Map.of());
    }

    String startJourney(String journeyId, String snapshotId) {
        return admin(
                        HttpMethod.POST,
                        "/api/v1/marketing/journeys/" + journeyId + "/start",
                        Map.of("snapshotId", snapshotId))
                .path("status")
                .asText();
    }
}
