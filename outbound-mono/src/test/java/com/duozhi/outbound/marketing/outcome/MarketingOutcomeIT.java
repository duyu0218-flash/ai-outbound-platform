package com.duozhi.outbound.marketing.outcome;

import static com.duozhi.outbound.marketing.common.OutcomeCodes.*;
import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.marketing.MarketingTestBase;
import com.duozhi.outbound.marketing.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/**
 * Synthetic callsession / callusage / speechturn / callmetric / callevent rows driven through the
 * three classification layers, rule versions, reruns, manual review and touch attribution.
 */
class MarketingOutcomeIT extends MarketingTestBase {
    private static final AtomicInteger PHONE = new AtomicInteger(1000);
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    OutcomeService outcomes;
    @Autowired
    OutcomeWorker worker;
    @Autowired
    OutcomeLlm llm;
    @Autowired
    PhoneIdentity phones;
    @Autowired
    Attribution attribution;

    @AfterEach
    void restoreLlm() {
        llm.override(null);
    }

    /** One synthetic finished call. talk == null means never answered. */
    static final class Call {
        final String id = UUID.randomUUID().toString();
        String phone = "1390" + String.format("%07d", PHONE.getAndIncrement());
        String status = "COMPLETED";
        String lastError;
        Long campaign;
        String node;
        double ring = 6;
        Double talk;
        Double opening;
        final List<String[]> turns = new ArrayList<>();
        final Map<String, Object> event = new LinkedHashMap<>();
        LocalDateTime base = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(30);

        Call status(String s) {
            status = s;
            return this;
        }

        Call cause(String c) {
            lastError = c;
            return this;
        }

        Call ring(double s) {
            ring = s;
            return this;
        }

        Call talk(double s) {
            talk = s;
            return this;
        }

        Call customer(String... texts) {
            for (String t : texts) turns.add(new String[] {"customer", t});
            return this;
        }

        Call ai(String text) {
            turns.add(new String[] {"ai", text});
            return this;
        }

        Call event(String k, Object v) {
            event.put(k, v);
            return this;
        }
    }

    private static LocalDateTime plus(LocalDateTime t, double sec) {
        return t.plusNanos((long) (sec * 1_000_000_000L));
    }

    String insert(Call c) {
        LocalDateTime started = c.base;
        LocalDateTime answered = c.talk == null ? null : plus(started, c.ring);
        LocalDateTime finished = answered == null ? plus(started, c.ring) : plus(answered, c.talk);
        jdbc.update(
                "INSERT INTO callsession(id,tenant_id,campaign_id,flow_node_key,phone,mode,status,attempts,max_attempts,last_error,voice_ai_pipeline,started_at,finished_at,created_at,updated_at) VALUES(?,?,?,?,?,'AI_ONLY',?,1,1,?,'classic',?,?,?,?)",
                c.id,
                tenant,
                c.campaign,
                c.node,
                c.phone,
                c.status,
                c.lastError,
                started,
                finished,
                started,
                LocalDateTime.now(ZoneOffset.UTC));
        jdbc.update(
                "INSERT INTO callusage(tenant_id,call_session_id,attempt,answered_at,ended_at,duration_source,created_at) VALUES(?,?,1,?,?,'observed_estimate',?)",
                tenant,
                c.id,
                answered,
                finished,
                started);
        int i = 0;
        for (String[] t : c.turns) {
            i++;
            jdbc.update(
                    "INSERT INTO speechturn(tenant_id,call_session_id,provider_event_key,turn_index,attempt,speaker_role,channel_id,transcript,normalized_transcript,is_final,confidence,asr_provider,created_at) VALUES(?,?,?,?,1,?,'inbound',?,?,TRUE,0.95,'mock',?)",
                    tenant,
                    c.id,
                    "k" + i,
                    i,
                    t[0],
                    t[1],
                    t[1],
                    plus(
                            answered == null ? started : answered,
                            Math.min(i, c.talk == null ? 0 : c.talk)));
        }
        if (c.opening != null && answered != null)
            jdbc.update(
                    "INSERT INTO callmetric(tenant_id,call_session_id,stage,provider,success,detail,created_at) VALUES(?,?,'media.listening','freeswitch',TRUE,'',?)",
                    tenant,
                    c.id,
                    plus(answered, c.opening));
        if (!c.event.isEmpty()) {
            StringBuilder p = new StringBuilder("{");
            c.event.forEach(
                    (k, v) ->
                            p.append(p.length() > 1 ? "," : "")
                                    .append('"')
                                    .append(k)
                                    .append("\":")
                                    .append(v instanceof Number ? v.toString() : "\"" + v + "\""));
            jdbc.update(
                    "INSERT INTO callevent(call_session_id,event_type,source,payload,created_at) VALUES(?,'status','telephony',?,?)",
                    c.id,
                    p.append('}').toString(),
                    finished);
        }
        return c.id;
    }

    Map<String, Object> classify(Call c) {
        insert(c);
        outcomes.classify(tenant, c.id, OutcomeService.RERUN);
        return outcome(c.id);
    }

    Map<String, Object> outcome(String id) {
        return jdbc.queryForMap("SELECT * FROM mk_call_outcome WHERE call_id=?", id);
    }

    List<Map<String, Object>> touches(String phone) {
        String hash = phones.hash(tenant, PhoneIdentity.normalize(phone));
        return jdbc.queryForList(
                "SELECT * FROM mk_touch_event WHERE tenant_id=? AND phone_hash=? ORDER BY sequence",
                tenant,
                hash);
    }

    private void expect(Call c, String connection, String answer, String intent) {
        var o = classify(c);
        assertThat(o.get("state")).isEqualTo("CLASSIFIED");
        assertThat(o.get("connection_code"))
                .as(String.valueOf(o.get("evidence")))
                .isEqualTo(connection);
        assertThat(o.get("answer_code")).as(String.valueOf(o.get("evidence"))).isEqualTo(answer);
        assertThat(o.get("intent_code")).as(String.valueOf(o.get("evidence"))).isEqualTo(intent);
    }

    @Test
    void connectionLayerCoversEveryCode() {
        expect(new Call().status("NO_ANSWER").event("sip_code", 480), CALL_NO_ANSWER, null, null);
        expect(new Call().status("FAILED").ring(50), CALL_NO_ANSWER, null, null);
        expect(new Call().status("NO_ANSWER").cause("NO_ANSWER"), CALL_NO_ANSWER, null, null);
        expect(new Call().status("BUSY").cause("USER_BUSY"), CALL_BUSY, null, null);
        expect(new Call().status("BUSY").event("sip_code", 486), CALL_BUSY, null, null);
        expect(new Call().status("FAILED").event("sip_code", 603), CALL_REJECTED, null, null);
        expect(new Call().status("FAILED").ring(2), CALL_REJECTED, null, null);
        expect(new Call().status("FAILED").cause("UNALLOCATED_NUMBER"), CALL_INVALID, null, null);
        expect(
                new Call().status("FAILED").event("early_media_text", "对不起，您拨打的号码是空号"),
                CALL_INVALID,
                null,
                null);
        expect(
                new Call().status("FAILED").event("early_media_text", "您拨打的电话已关机"),
                CALL_POWER_OFF,
                null,
                null);
        expect(
                new Call().status("NO_ANSWER").cause("SUBSCRIBER_ABSENT"),
                CALL_POWER_OFF,
                null,
                null);
        // carrier answers and plays the tone; ASR captures it as the first customer turn
        expect(new Call().talk(8).customer("您好，您拨打的电话已停机"), CALL_SUSPENDED, null, null);
        expect(
                new Call().status("FAILED").event("early_media_text", "您拨打的用户已欠费"),
                CALL_SUSPENDED,
                null,
                null);
        Call line = new Call().status("FAILED").event("sip_code", 503);
        expect(line, CALL_LINE_FAIL, null, null);
        assertThat(outcome(line.id).get("counts_against_user")).isIn(false, 0, (byte) 0);
        expect(
                new Call().status("FAILED").cause("NORMAL_TEMPORARY_FAILURE"),
                CALL_LINE_FAIL,
                null,
                null);
        expect(
                new Call().talk(40).customer("是我，你说", "什么事", "好的我考虑一下"),
                CALL_ANSWERED,
                ANS_CONVERSATION,
                INTENT_B);
    }

    @Test
    void answerQualityLayerCoversEveryCode() {
        expect(new Call().talk(2), CALL_ANSWERED, ANS_INSTANT_HANGUP, null);
        Call opening = new Call().talk(6).customer("喂");
        opening.opening = 8.0;
        expect(opening, CALL_ANSWERED, ANS_INSTANT_HANGUP, null);
        Call platformHangup = new Call().talk(2).event("hangup_disposition", "send_bye");
        expect(platformHangup, CALL_ANSWERED, ANS_SHORT_HANGUP, null);
        expect(new Call().talk(7).customer("我在开车"), CALL_ANSWERED, ANS_SHORT_HANGUP, null);
        expect(
                new Call().talk(15).customer("您好，我是机主的智能助理，请问您有什么事"),
                CALL_ANSWERED,
                ANS_VOICE_ASSISTANT,
                null);
        expect(new Call().status("VOICEMAIL").talk(20), CALL_ANSWERED, ANS_VOICEMAIL, null);
        expect(new Call().talk(12).customer("你打错了吧"), CALL_ANSWERED, ANS_NOT_OWNER, null);
        expect(new Call().talk(5).customer("别再打了"), CALL_ANSWERED, ANS_REFUSE_HARD, null);
        expect(
                new Call().talk(60).ai("您好").customer("是我，你说", "你说吧", "暂时不用"),
                CALL_ANSWERED,
                ANS_CONVERSATION,
                INTENT_C);
    }

    @Test
    void voiceAssistantLlmOnlyWhenConfiguredAndConfident() {
        assertThat(llm.available()).isFalse();
        Call plain = new Call().talk(8).customer("这里是小艺，请说出您的来意");
        expect(plain, CALL_ANSWERED, ANS_SHORT_HANGUP, null);
        llm.override(
                prompt ->
                        prompt.contains("小艺")
                                ? "{\"label\":\"assistant\",\"confidence\":0.92,\"reason\":\"系统助手开场\"}"
                                : "{\"label\":\"human\",\"confidence\":0.9,\"reason\":\"真人\"}");
        Call sure = new Call().talk(8).customer("这里是小艺，请说出您的来意");
        expect(sure, CALL_ANSWERED, ANS_VOICE_ASSISTANT, null);
        assertThat(outcome(sure.id).get("evidence").toString()).contains("voice_assistant");
        llm.override(p -> "{\"label\":\"assistant\",\"confidence\":0.6,\"reason\":\"不确定\"}");
        expect(new Call().talk(8).customer("这里是小艺，请说出您的来意"), CALL_ANSWERED, ANS_SHORT_HANGUP, null);
    }

    @Test
    void intentLayerUsesRulesThenAnalysisThenLlmAndQueuesLowConfidence() {
        expect(
                new Call().talk(50).customer("是我，你说", "这个怎么办理"),
                CALL_ANSWERED,
                ANS_CONVERSATION,
                INTENT_A);
        expect(
                new Call().talk(50).customer("是我，你说", "有什么优惠"),
                CALL_ANSWERED,
                ANS_CONVERSATION,
                INTENT_B);
        expect(
                new Call().talk(50).customer("是我，你说", "我没兴趣"),
                CALL_ANSWERED,
                ANS_CONVERSATION,
                INTENT_C);
        expect(
                new Call().talk(50).customer("是我，你说", "听着呢", "别再打了"),
                CALL_ANSWERED,
                ANS_CONVERSATION,
                INTENT_D);
        Call analysed = new Call().talk(50).customer("是我，你说", "嗯可以的那就这样");
        insert(analysed);
        jdbc.update(
                "INSERT INTO callanalysis(tenant_id,call_session_id,result_code,sentiment,intent,summary,qa_score,qa_flags_json,structured_json,automatic_result_json,needs_review,review_state,created_at,updated_at) VALUES(?,?,'qualified_lead','positive','qualified_lead','',100,'[]','{}','{}',FALSE,'automatic',NOW(3),NOW(3))",
                tenant,
                analysed.id);
        outcomes.classify(tenant, analysed.id, OutcomeService.RERUN);
        assertThat(outcome(analysed.id).get("intent_code")).isEqualTo(INTENT_A);

        Call unclear = new Call().talk(50).customer("是我，你说", "嗯你说的那个东西");
        expect(unclear, CALL_ANSWERED, ANS_CONVERSATION, INTENT_E);
        var o = outcome(unclear.id);
        assertThat(o.get("needs_review")).isIn(true, 1, (byte) 1);
        assertThat(o.get("review_state")).isEqualTo("PENDING");

        llm.override(p -> "{\"label\":\"B\",\"confidence\":0.65,\"reason\":\"有点兴趣\"}");
        Call weak = new Call().talk(50).customer("是我，你说", "嗯你说的那个东西");
        expect(weak, CALL_ANSWERED, ANS_CONVERSATION, INTENT_B);
        assertThat(outcome(weak.id).get("review_state")).isEqualTo("PENDING");
        llm.override(p -> "{\"label\":\"A\",\"confidence\":0.88,\"reason\":\"明确要办\"}");
        Call strong = new Call().talk(50).customer("是我，你说", "嗯你说的那个东西");
        expect(strong, CALL_ANSWERED, ANS_CONVERSATION, INTENT_A);
        assertThat(outcome(strong.id).get("review_state")).isEqualTo("NONE");

        // script node hit configured in a published rule version
        var draft = admin(HttpMethod.POST, "/api/v1/marketing/outcomes/rules", Map.of());
        int v = draft.path("version").asInt();
        var config =
                admin(HttpMethod.GET, "/api/v1/marketing/outcomes/rules", null)
                        .path("draft")
                        .path("config");
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg =
                new com.fasterxml.jackson.databind.ObjectMapper().convertValue(config, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> intent = (Map<String, Object>) cfg.get("intent");
        intent.put(
                "nodes",
                Map.of("A", List.of("node_apply"), "B", List.of(), "C", List.of(), "D", List.of()));
        admin(HttpMethod.PUT, "/api/v1/marketing/outcomes/rules/" + v, Map.of("config", cfg));
        admin(HttpMethod.POST, "/api/v1/marketing/outcomes/rules/" + v + "/publish", Map.of());
        llm.override(null);
        Call node = new Call().talk(50).customer("是我，你说", "嗯你说的那个东西");
        node.node = "node_apply";
        expect(node, CALL_ANSWERED, ANS_CONVERSATION, INTENT_A);
        assertThat(outcome(node.id).get("rule_version")).isEqualTo(v);
    }

    @Test
    void touchEventsCarryAttributionAndPhoneHash() {
        Call c = new Call().talk(40).customer("是我，你说", "怎么申请");
        insert(c);
        String hash = phones.hash(tenant, PhoneIdentity.normalize(c.phone));
        attribution.register(
                tenant, CALL, Attribution.CALL, c.id, hash, null, "snap-p3", "jr-p3", "jm-p3");
        outcomes.classify(tenant, c.id, OutcomeService.RERUN);
        var rows = touches(c.phone);
        assertThat(rows)
                .extracting(r -> r.get("code"))
                .containsExactly(CALL_ANSWERED, ANS_CONVERSATION, INTENT_A);
        assertThat(rows)
                .allSatisfy(
                        r -> {
                            assertThat(r.get("source_type")).isEqualTo("callsession");
                            assertThat(r.get("source_id")).isEqualTo(c.id);
                            assertThat(r.get("snapshot_id")).isEqualTo("snap-p3");
                            assertThat(r.get("journey_member_id")).isEqualTo("jm-p3");
                            assertThat(r.get("channel")).isEqualTo(CALL);
                        });
        assertThat(outcome(c.id).get("phone_hash")).isEqualTo(hash);

        Call plain = new Call().status("BUSY").cause("USER_BUSY");
        classify(plain);
        assertThat(touches(plain.phone))
                .singleElement()
                .satisfies(r -> assertThat(r.get("dispatch_id")).isNull());

        Call bad = new Call().status("BUSY");
        bad.phone = "12345";
        var skipped = classify(bad);
        assertThat(skipped.get("state")).isEqualTo("SKIPPED");
        assertThat(skipped.get("skip_reason").toString()).contains("E.164");
    }

    @Test
    void workerScansByWatermarkOnceAndIsGatedOff() {
        jdbc.update(
                "INSERT INTO mk_outcome_watermark(name,last_updated_at,last_call_id) VALUES('call_outcome',?, '') ON DUPLICATE KEY UPDATE last_updated_at=VALUES(last_updated_at),last_call_id=''",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        Call a = new Call().status("NO_ANSWER").cause("NO_ANSWER");
        Call b = new Call().talk(30).customer("是我，你说", "有什么优惠");
        insert(a);
        insert(b);
        worker.tick(); // MARKETING_WORKERS_ENABLED=false in tests: nothing happens
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mk_call_outcome WHERE tenant_id=?",
                                Long.class,
                                tenant))
                .isZero();
        assertThat(worker.step(500, Duration.ZERO)).isGreaterThanOrEqualTo(2);
        assertThat(outcome(a.id).get("connection_code")).isEqualTo(CALL_NO_ANSWER);
        assertThat(outcome(b.id).get("intent_code")).isEqualTo(INTENT_B);
        int touches = touches(b.phone).size();
        assertThat(worker.step(500, Duration.ZERO)).isZero();
        // a later update (e.g. a recording URL) rescans the row but does not reclassify
        jdbc.update(
                "UPDATE callsession SET recording_url='x', updated_at=? WHERE id=?",
                LocalDateTime.now(ZoneOffset.UTC),
                b.id);
        assertThat(worker.step(500, Duration.ZERO)).isEqualTo(1);
        assertThat(touches(b.phone)).hasSize(touches);
    }

    @Test
    void publishingANewRuleVersionReclassifiesARangeIdempotently() {
        Call c = new Call().talk(5);
        expect(c, CALL_ANSWERED, ANS_SHORT_HANGUP, null);
        assertThat(outcome(c.id).get("rule_version")).isEqualTo(0);
        var rules = admin(HttpMethod.GET, "/api/v1/marketing/outcomes/rules", null);
        assertThat(rules.path("active_version").asInt()).isZero();
        var cfg = new LinkedHashMap<String, Object>(Map.of("instant_hangup_sec", 6));
        int v =
                admin(
                                HttpMethod.POST,
                                "/api/v1/marketing/outcomes/rules",
                                Map.of("config", cfg, "note", "秒挂放宽到 6 秒"))
                        .path("version")
                        .asInt();
        assertThat(
                        req(
                                        admin,
                                        HttpMethod.PUT,
                                        "/api/v1/marketing/outcomes/rules/" + v,
                                        Map.of(
                                                "config",
                                                Map.of(
                                                        "short_hangup_sec",
                                                        2,
                                                        "instant_hangup_sec",
                                                        6)))
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        String from = ISO.format(c.base.minusMinutes(1)), to = ISO.format(c.base.plusMinutes(5));
        var published =
                admin(
                        HttpMethod.POST,
                        "/api/v1/marketing/outcomes/rules/" + v + "/publish",
                        Map.of("rerun_from", from, "rerun_to", to));
        assertThat(published.path("rerun").path("processed").asInt()).isGreaterThanOrEqualTo(1);
        var o = outcome(c.id);
        assertThat(o.get("answer_code")).isEqualTo(ANS_INSTANT_HANGUP);
        assertThat(o.get("rule_version")).isEqualTo(v);
        assertThat(o.get("evidence").toString()).contains("\"rule_version\": " + v);
        var rows = touches(c.phone);
        assertThat(rows)
                .extracting(r -> r.get("source_id") + "/" + r.get("code"))
                .containsExactly(
                        c.id + "/" + CALL_ANSWERED,
                        c.id + "/" + ANS_SHORT_HANGUP,
                        c.id + "#v" + v + "/" + ANS_INSTANT_HANGUP);
        var again =
                admin(
                        HttpMethod.POST,
                        "/api/v1/marketing/outcomes/rerun",
                        Map.of("from", from, "to", to));
        assertThat(again.path("changed").asInt()).isZero();
        assertThat(touches(c.phone)).hasSize(3);
        assertThat(
                        req(
                                        admin,
                                        HttpMethod.PUT,
                                        "/api/v1/marketing/outcomes/rules/" + v,
                                        Map.of("config", cfg))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        assertThat(
                        admin(HttpMethod.GET, "/api/v1/marketing/outcomes/rules", null)
                                .path("versions")
                                .get(0)
                                .path("state")
                                .asText())
                .isEqualTo("PUBLISHED");
    }

    @Test
    void manualCorrectionOverridesAndFeedsAccuracyAndPhraseLibrary() {
        Call unclear = new Call().talk(50).customer("是我，你说", "嗯你说的那个东西");
        expect(unclear, CALL_ANSWERED, ANS_CONVERSATION, INTENT_E);
        var queue = admin(HttpMethod.GET, "/api/v1/marketing/outcomes/review-queue", null);
        assertThat(queue.path("items"))
                .anySatisfy(i -> assertThat(i.path("call_id").asText()).isEqualTo(unclear.id));
        assertThat(queue.path("items").get(0).path("phone_mask").asText()).contains("****");
        var reviewed =
                admin(
                        HttpMethod.POST,
                        "/api/v1/marketing/outcomes/calls/" + unclear.id + "/review",
                        Map.of("intent_code", INTENT_B, "note", "客户说要再看看"));
        assertThat(reviewed.path("review_state").asText()).isEqualTo("CORRECTED");
        var o = outcome(unclear.id);
        assertThat(o.get("intent_code")).isEqualTo(INTENT_B);
        assertThat(o.get("auto_intent_code")).isEqualTo(INTENT_E);
        assertThat(touches(unclear.phone))
                .anySatisfy(
                        r -> {
                            assertThat(r.get("source_id")).isEqualTo(unclear.id + "#review");
                            assertThat(r.get("code")).isEqualTo(INTENT_B);
                        });
        outcomes.classify(tenant, unclear.id, OutcomeService.RERUN);
        assertThat(outcome(unclear.id).get("intent_code")).isEqualTo(INTENT_B);
        assertThat(
                        req(
                                        admin,
                                        HttpMethod.POST,
                                        "/api/v1/marketing/outcomes/calls/"
                                                + unclear.id
                                                + "/review",
                                        Map.of("connection_code", ANS_CONVERSATION))
                                .getStatusCode()
                                .value())
                .isEqualTo(422);

        Call good = new Call().talk(50).customer("是我，你说", "怎么办理");
        expect(good, CALL_ANSWERED, ANS_CONVERSATION, INTENT_A);
        admin(HttpMethod.POST, "/api/v1/marketing/outcomes/calls/" + good.id + "/review", Map.of());
        assertThat(outcome(good.id).get("review_state")).isEqualTo("CONFIRMED");

        Call assistant = new Call().talk(8).customer("这里是小艺请说出您的来意");
        expect(assistant, CALL_ANSWERED, ANS_SHORT_HANGUP, null);
        admin(
                HttpMethod.POST,
                "/api/v1/marketing/outcomes/calls/" + assistant.id + "/review",
                Map.of("answer_code", ANS_VOICE_ASSISTANT));
        var learned =
                admin(
                        HttpMethod.POST,
                        "/api/v1/marketing/outcomes/calls/" + assistant.id + "/assistant-phrase",
                        Map.of());
        assertThat(learned.path("phrase").asText()).isEqualTo("这里是小艺请说出您的来意");
        int v = learned.path("version").asInt();
        admin(HttpMethod.POST, "/api/v1/marketing/outcomes/rules/" + v + "/publish", Map.of());
        expect(
                new Call().talk(8).customer("这里是小艺，请说出您的来意。"),
                CALL_ANSWERED,
                ANS_VOICE_ASSISTANT,
                null);

        JsonNode accuracy =
                admin(HttpMethod.GET, "/api/v1/marketing/outcomes/accuracy", null).path("items");
        Map<String, String> byCode = new HashMap<>();
        accuracy.forEach(
                a ->
                        byCode.put(
                                a.path("layer").asText() + ":" + a.path("code").asText(),
                                a.path("correct").asInt() + "/" + a.path("reviewed").asInt()));
        assertThat(byCode)
                .containsEntry("intent:INTENT_E", "0/1")
                .containsEntry("intent:INTENT_A", "1/1")
                .containsEntry("answer:ANS_SHORT_HANGUP", "0/1")
                .containsEntry("connection:CALL_ANSWERED", "3/3");
    }

    @Test
    void browsingStatsAndAccessControl() {
        jdbc.update(
                "INSERT INTO campaign(tenant_id,name,script,mode,concurrency,retry_limit,retry_interval_sec,attempt_interval_sec,recording_enabled,hangup_sms_enabled,voice_ai_pipeline,status,dispatch_enabled,created_at,updated_at) VALUES(?,'P3 判定','s','AI_ONLY',1,0,60,60,FALSE,FALSE,'classic','running',FALSE,NOW(3),NOW(3))",
                tenant);
        long campaign =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM campaign WHERE tenant_id=?", Long.class, tenant);
        Call a = new Call().talk(2);
        a.campaign = campaign;
        Call b = new Call().talk(40).customer("是我，你说", "怎么申请");
        b.campaign = campaign;
        Call c = new Call().status("FAILED").event("sip_code", 503);
        Call d = new Call().status("NO_ANSWER").cause("NO_ANSWER");
        for (Call x : List.of(a, b, c, d)) classify(x);
        var stats = admin(HttpMethod.GET, "/api/v1/marketing/outcomes/stats", null);
        assertThat(stats.path("total").asInt()).isEqualTo(4);
        assertThat(stats.path("connection_rate").asDouble()).isEqualTo(0.6667);
        assertThat(stats.path("instant_hangup_rate").asDouble()).isEqualTo(0.5);
        assertThat(stats.path("intent").path(INTENT_A).asInt()).isEqualTo(1);
        var byCampaign =
                admin(HttpMethod.GET, "/api/v1/marketing/outcomes?campaign_id=" + campaign, null);
        assertThat(byCampaign.path("total").asInt()).isEqualTo(2);
        assertThat(byCampaign.path("items").get(0).path("campaign_name").asText())
                .isEqualTo("P3 判定");
        var byCode =
                admin(HttpMethod.GET, "/api/v1/marketing/outcomes?code=" + CALL_LINE_FAIL, null);
        assertThat(byCode.path("items"))
                .singleElement()
                .satisfies(i -> assertThat(i.path("call_id").asText()).isEqualTo(c.id));
        String day = c.base.toLocalDate().toString();
        assertThat(
                        admin(
                                        HttpMethod.GET,
                                        "/api/v1/marketing/outcomes?from="
                                                + day
                                                + "&to="
                                                + c.base.toLocalDate().plusDays(1),
                                        null)
                                .path("total")
                                .asInt())
                .isEqualTo(4);
        var detail = admin(HttpMethod.GET, "/api/v1/marketing/outcomes/calls/" + b.id, null);
        assertThat(detail.path("turns").size()).isEqualTo(2);
        assertThat(detail.path("evidence").path("layers").path("intent").path("code").asText())
                .isEqualTo(INTENT_A);
        assertThat(detail.has("phone_hash")).isFalse();
        var meta = admin(HttpMethod.GET, "/api/v1/marketing/outcomes/meta", null);
        assertThat(meta.path("llm_available").asBoolean()).isFalse();
        assertThat(meta.path("campaigns").size()).isEqualTo(1);
        assertThat(
                        req(agent, HttpMethod.GET, "/api/v1/marketing/outcomes/stats", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        assertThat(
                        req(
                                        agent,
                                        HttpMethod.POST,
                                        "/api/v1/marketing/outcomes/calls/" + b.id + "/review",
                                        Map.of())
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }
}
