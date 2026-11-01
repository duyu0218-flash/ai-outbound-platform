package com.duozhi.outbound.marketing.outcome;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.*;
import com.duozhi.outbound.marketing.outcome.OutcomeClassifier.Layer;
import com.duozhi.outbound.marketing.outcome.OutcomeClassifier.Verdict;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists outcomes and emits one touch event per layer. Touch sources: the call id for the first
 * attempt ({@code callId@n} for retried attempts), {@code <source>#v<version>} when a rerun under a
 * newer rule changes a layer's code, and {@code <source>#review} for manual corrections. Reruns are
 * idempotent: the same rule over the same inputs writes nothing new.
 */
@Service
public class OutcomeService {
    public static final String WORKER = "worker", RERUN = "rerun", MANUAL = "manual";
    static final List<String> LAYERS = List.of("connection", "answer", "intent");

    private final MarketingStore db;
    private final JsonSupport json;
    private final CallSignals signals;
    private final OutcomeClassifier classifier;
    private final OutcomeRules rules;
    private final PhoneIdentity phones;
    private final TouchEvents touches;
    private final Attribution attribution;
    private final TransactionTemplate tx;

    public OutcomeService(
            MarketingStore db,
            JsonSupport json,
            CallSignals signals,
            OutcomeClassifier classifier,
            OutcomeRules rules,
            PhoneIdentity phones,
            TouchEvents touches,
            Attribution attribution,
            PlatformTransactionManager transactions) {
        this.db = db;
        this.json = json;
        this.signals = signals;
        this.classifier = classifier;
        this.rules = rules;
        this.phones = phones;
        this.touches = touches;
        this.attribution = attribution;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Result(String callId, String state, boolean changed, Map<String, Object> row) {}

    /**
     * Classifies one finished call in its own transaction. The worker skips calls already
     * classified for the same attempt; reruns always recompute against the active rule.
     */
    public Result classify(long tenant, String callId, String trigger) {
        return tx.execute(st -> classifyLocked(tenant, callId, trigger));
    }

    private Result classifyLocked(long tenant, String callId, String trigger) {
        CallSignals.Signals s = signals.load(tenant, callId);
        if (s == null) throw new ApiError(404, "通话不存在");
        Map<String, Object> existing =
                db.one(
                        "SELECT * FROM mk_call_outcome WHERE tenant_id=? AND call_id=? FOR UPDATE",
                        tenant,
                        callId);
        boolean sameAttempt =
                existing != null && ((Number) existing.get("attempt")).intValue() == s.attempt();
        if (WORKER.equals(trigger) && sameAttempt && "CLASSIFIED".equals(existing.get("state")))
            return new Result(callId, "CLASSIFIED", false, existing);
        String e164 = PhoneIdentity.normalize(s.phone());
        String hash = null, skip = null;
        if (e164 == null) skip = "号码无法规范化为 E.164，未写入触达事件";
        else
            try {
                hash = phones.hash(tenant, e164);
            } catch (ApiError e) {
                skip = "消息加密主密钥（MESSAGING_MASTER_KEY）未配置，无法计算号码哈希";
            }
        OutcomeRules.Rule rule = rules.active(tenant);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (skip != null) {
            upsertSkipped(tenant, s, existing, skip, rule.version(), trigger, now);
            return new Result(callId, "SKIPPED", existing == null, null);
        }
        Verdict v = classifier.classify(s, rule);
        Map<String, Object> evidence = new LinkedHashMap<>(v.evidence());
        evidence.put("trigger", trigger);
        boolean locked =
                sameAttempt
                        && Set.of("CONFIRMED", "CORRECTED").contains(existing.get("review_state"));
        boolean fresh =
                existing == null || !sameAttempt || !"CLASSIFIED".equals(existing.get("state"));
        String[] auto = {code(v.connection()), code(v.answer()), code(v.intent())};
        String[] effective =
                locked
                        ? new String[] {
                            (String) existing.get("connection_code"),
                            (String) existing.get("answer_code"),
                            (String) existing.get("intent_code")
                        }
                        : auto;
        String reviewState =
                locked
                        ? (String) existing.get("review_state")
                        : v.needsReview() ? "PENDING" : "NONE";
        boolean needsReview = !locked && v.needsReview();
        if (existing == null)
            db.write(
                    "INSERT INTO mk_call_outcome(id,tenant_id,call_id,attempt,campaign_id,phone_hash,state,skip_reason,connection_code,answer_code,intent_code,auto_connection_code,auto_answer_code,auto_intent_code,connection_confidence,answer_confidence,intent_confidence,confidence,counts_against_user,rule_version,evidence,needs_review,review_reason,review_state,call_finished_at,classified_at) VALUES(?,?,?,?,?,?,'CLASSIFIED',NULL,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    MarketingStore.id("mco"),
                    tenant,
                    callId,
                    s.attempt(),
                    s.campaignId(),
                    hash,
                    effective[0],
                    effective[1],
                    effective[2],
                    auto[0],
                    auto[1],
                    auto[2],
                    conf(v.connection()),
                    conf(v.answer()),
                    conf(v.intent()),
                    v.confidence(),
                    v.countsAgainstUser(),
                    rule.version(),
                    json.json(evidence),
                    needsReview,
                    v.reviewReason(),
                    reviewState,
                    s.finishedAt(),
                    now);
        else
            db.write(
                    "UPDATE mk_call_outcome SET attempt=?,campaign_id=?,phone_hash=?,state='CLASSIFIED',skip_reason=NULL,connection_code=?,answer_code=?,intent_code=?,auto_connection_code=?,auto_answer_code=?,auto_intent_code=?,connection_confidence=?,answer_confidence=?,intent_confidence=?,confidence=?,counts_against_user=?,rule_version=?,evidence=?,needs_review=?,review_reason=?,review_state=?,call_finished_at=?,classified_at=? WHERE tenant_id=? AND call_id=?",
                    s.attempt(),
                    s.campaignId(),
                    hash,
                    effective[0],
                    effective[1],
                    effective[2],
                    auto[0],
                    auto[1],
                    auto[2],
                    conf(v.connection()),
                    conf(v.answer()),
                    conf(v.intent()),
                    v.confidence(),
                    v.countsAgainstUser(),
                    rule.version(),
                    json.json(evidence),
                    needsReview,
                    locked ? existing.get("review_reason") : v.reviewReason(),
                    reviewState,
                    s.finishedAt(),
                    now,
                    tenant,
                    callId);
        String base = source(callId, s.attempt());
        Attribution.Dispatch dispatch = attribution.find(tenant, Attribution.CALL, callId);
        boolean changed = fresh;
        Layer[] layers = {v.connection(), v.answer(), v.intent()};
        for (int i = 0; i < 3; i++) {
            if (auto[i] == null) continue;
            String sourceId;
            Map<String, Object> detail = detail(LAYERS.get(i), layers[i], rule.version(), trigger);
            if (fresh) sourceId = base;
            else {
                String before = (String) existing.get("auto_" + LAYERS.get(i) + "_code");
                if (auto[i].equals(before) || locked) continue;
                sourceId = base + "#v" + rule.version();
                detail.put("supersedes", before);
                changed = true;
            }
            touches.record(
                    new TouchEvents.Touch(
                            tenant,
                            hash,
                            s.contactId(),
                            OutcomeCodes.CALL,
                            auto[i],
                            Attribution.CALL,
                            sourceId,
                            dispatch,
                            detail,
                            s.finishedAt()));
        }
        return new Result(callId, "CLASSIFIED", changed, null);
    }

    private void upsertSkipped(
            long tenant,
            CallSignals.Signals s,
            Map<String, Object> existing,
            String reason,
            int version,
            String trigger,
            LocalDateTime now) {
        String evidence =
                json.json(
                        JsonSupport.map(
                                "trigger",
                                trigger,
                                "skip_reason",
                                reason,
                                "rule_version",
                                version));
        if (existing == null)
            db.write(
                    "INSERT INTO mk_call_outcome(id,tenant_id,call_id,attempt,campaign_id,state,skip_reason,rule_version,evidence,call_finished_at,classified_at) VALUES(?,?,?,?,?,'SKIPPED',?,?,?,?,?)",
                    MarketingStore.id("mco"),
                    tenant,
                    s.callId(),
                    s.attempt(),
                    s.campaignId(),
                    reason,
                    version,
                    evidence,
                    s.finishedAt(),
                    now);
        else
            db.write(
                    "UPDATE mk_call_outcome SET state='SKIPPED',skip_reason=?,attempt=?,rule_version=?,evidence=?,classified_at=? WHERE tenant_id=? AND call_id=? AND state='SKIPPED'",
                    reason,
                    s.attempt(),
                    version,
                    evidence,
                    now,
                    tenant,
                    s.callId());
    }

    /** Records an unexpected failure so the worker can move on; a rerun retries it. */
    public void recordFailure(long tenant, String callId, String reason) {
        tx.executeWithoutResult(
                st -> {
                    var s = signals.load(tenant, callId);
                    if (s == null) return;
                    var existing =
                            db.one(
                                    "SELECT * FROM mk_call_outcome WHERE tenant_id=? AND call_id=?",
                                    tenant,
                                    callId);
                    upsertSkipped(
                            tenant,
                            s,
                            existing,
                            OutcomeClassifier.clip("判定异常：" + reason, 250),
                            rules.active(tenant).version(),
                            WORKER,
                            LocalDateTime.now(ZoneOffset.UTC));
                });
    }

    static String source(String callId, int attempt) {
        return attempt <= 1 ? callId : callId + "@" + attempt;
    }

    private static String code(Layer l) {
        return l == null ? null : l.code();
    }

    private static Double conf(Layer l) {
        return l == null ? null : Math.round(l.confidence() * 1000) / 1000.0;
    }

    private static Map<String, Object> detail(String layer, Layer l, int version, String trigger) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("layer", layer);
        d.put("confidence", conf(l));
        d.put("reason", l.reason());
        d.put("rule_version", version);
        d.put("trigger", trigger);
        return d;
    }

    // ---------------------------------------------------------------- rerun

    public Map<String, Object> rerun(
            long tenant, LocalDateTime from, LocalDateTime to, Long campaign, int limit) {
        if (from == null || to == null || !from.isBefore(to)) throw new ApiError(422, "请选择有效的时间范围");
        if (to.isAfter(from.plusDays(93))) throw new ApiError(422, "单次重跑最多 93 天");
        List<Object> args = new ArrayList<>(List.of(tenant, from, to));
        String sql =
                "SELECT id FROM callsession WHERE tenant_id=? AND finished_at>=? AND finished_at<? AND status IN ('COMPLETED','FAILED','NO_ANSWER','BUSY','VOICEMAIL')";
        if (campaign != null) {
            sql += " AND campaign_id=?";
            args.add(campaign);
        }
        sql += " ORDER BY finished_at,id LIMIT ?";
        args.add(limit + 1);
        List<Map<String, Object>> ids = db.rows(sql, args.toArray());
        int processed = 0, changed = 0, skipped = 0;
        for (Map<String, Object> r : ids.subList(0, Math.min(limit, ids.size()))) {
            Result res = classify(tenant, r.get("id").toString(), RERUN);
            processed++;
            if (res.changed()) changed++;
            if ("SKIPPED".equals(res.state())) skipped++;
        }
        return JsonSupport.map(
                "processed",
                processed,
                "changed",
                changed,
                "skipped",
                skipped,
                "truncated",
                ids.size() > limit,
                "rule_version",
                rules.active(tenant).version());
    }

    // ---------------------------------------------------------------- manual review

    public Map<String, Object> review(
            long tenant, String callId, Map<String, Object> body, long reviewer) {
        return tx.execute(
                st -> {
                    Map<String, Object> row =
                            db.one(
                                    "SELECT * FROM mk_call_outcome WHERE tenant_id=? AND call_id=? FOR UPDATE",
                                    tenant,
                                    callId);
                    if (row == null || !"CLASSIFIED".equals(row.get("state")))
                        throw new ApiError(404, "该通话尚无判定结果");
                    String[] next = new String[3];
                    List<List<String>> vocab =
                            List.of(
                                    OutcomeClassifier.CONNECTION,
                                    OutcomeClassifier.ANSWER,
                                    OutcomeClassifier.INTENT);
                    for (int i = 0; i < 3; i++) {
                        String key = LAYERS.get(i) + "_code";
                        Object given = body.get(key);
                        next[i] =
                                body.containsKey(key)
                                        ? (given == null || given.toString().isBlank()
                                                ? null
                                                : given.toString())
                                        : (String) row.get(key);
                        if (next[i] != null && !vocab.get(i).contains(next[i]))
                            throw new ApiError(422, "结果码 " + next[i] + " 不属于该层");
                    }
                    if (next[0] == null) throw new ApiError(422, "接通结果不能为空");
                    if (!OutcomeCodes.CALL_ANSWERED.equals(next[0])) next[1] = next[2] = null;
                    else if (next[1] == null) throw new ApiError(422, "已接通的通话需要接听质量结果");
                    if (!OutcomeCodes.ANS_CONVERSATION.equals(next[1])) next[2] = null;
                    else if (next[2] == null) throw new ApiError(422, "有效对话需要意向等级");
                    String note =
                            OutcomeClassifier.clip(Objects.toString(body.get("note"), ""), 500);
                    boolean corrected = false;
                    String hash = (String) row.get("phone_hash");
                    var dispatch = attribution.find(tenant, Attribution.CALL, callId);
                    String base = source(callId, ((Number) row.get("attempt")).intValue());
                    Long contact =
                            db.one(
                                                    "SELECT contact_id FROM callsession WHERE tenant_id=? AND id=?",
                                                    tenant,
                                                    callId)
                                            instanceof Map<String, Object> c
                                    ? CallSignals.longValue(c.get("contact_id"))
                                    : null;
                    for (int i = 0; i < 3; i++) {
                        String layer = LAYERS.get(i);
                        String auto = (String) row.get("auto_" + layer + "_code");
                        String previous = (String) row.get(layer + "_code");
                        if (!Objects.equals(auto, next[i])) corrected = true;
                        db.write(
                                "INSERT INTO mk_outcome_review(id,tenant_id,call_id,layer,auto_code,previous_code,corrected_code,note,reviewer) VALUES(?,?,?,?,?,?,?,?,?)",
                                MarketingStore.id("mor"),
                                tenant,
                                callId,
                                layer,
                                auto,
                                previous,
                                next[i],
                                note,
                                reviewer);
                        if (next[i] != null
                                && !Objects.equals(previous, next[i])
                                && !Objects.equals(auto, next[i]))
                            touches.record(
                                    new TouchEvents.Touch(
                                            tenant,
                                            hash,
                                            contact,
                                            OutcomeCodes.CALL,
                                            next[i],
                                            Attribution.CALL,
                                            base + "#review",
                                            dispatch,
                                            JsonSupport.map(
                                                    "layer",
                                                    layer,
                                                    "auto_code",
                                                    auto,
                                                    "previous_code",
                                                    previous,
                                                    "reviewer",
                                                    reviewer,
                                                    "note",
                                                    note),
                                            CallSignals.time(row.get("call_finished_at"))));
                    }
                    String state = corrected ? "CORRECTED" : "CONFIRMED";
                    db.write(
                            "UPDATE mk_call_outcome SET connection_code=?,answer_code=?,intent_code=?,needs_review=FALSE,review_state=?,review_note=?,reviewed_by=?,reviewed_at=? WHERE tenant_id=? AND call_id=?",
                            next[0],
                            next[1],
                            next[2],
                            state,
                            note,
                            reviewer,
                            LocalDateTime.now(ZoneOffset.UTC),
                            tenant,
                            callId);
                    return JsonSupport.map(
                            "call_id",
                            callId,
                            "review_state",
                            state,
                            "connection_code",
                            next[0],
                            "answer_code",
                            next[1],
                            "intent_code",
                            next[2]);
                });
    }

    /** Sends a call's opening customer words to the voice-assistant phrase library draft. */
    public Map<String, Object> learnAssistantPhrase(
            long tenant, String callId, String phrase, long by) {
        String p = phrase;
        if (p == null || p.isBlank()) {
            Map<String, Object> row =
                    db.one(
                            "SELECT attempt FROM mk_call_outcome WHERE tenant_id=? AND call_id=?",
                            tenant,
                            callId);
            if (row == null) throw new ApiError(404, "该通话尚无判定结果");
            Map<String, Object> turn =
                    db.one(
                            "SELECT normalized_transcript FROM speechturn WHERE tenant_id=? AND call_session_id=? AND attempt=? AND speaker_role IN ('customer','user') ORDER BY id LIMIT 1",
                            tenant,
                            callId,
                            row.get("attempt"));
            if (turn == null) throw new ApiError(422, "该通话没有客户首轮转写，请手动填写话术");
            p =
                    OutcomeClassifier.clip(
                            Objects.toString(turn.get("normalized_transcript"), "").strip(), 60);
        }
        return rules.addAssistantPhrase(tenant, p, by);
    }
}
