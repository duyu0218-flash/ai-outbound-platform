package com.duozhi.outbound.marketing.outcome;

import static com.duozhi.outbound.marketing.common.OutcomeCodes.*;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.MarketingStore;
import com.duozhi.outbound.marketing.common.PhoneIdentity;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;

/** Read side of the outcome page: browsing, distribution, review queue and accuracy. */
@Service
public class OutcomeQueries {
    private final MarketingStore db;
    private final JsonSupport json;

    public OutcomeQueries(MarketingStore db, JsonSupport json) {
        this.db = db;
        this.json = json;
    }

    public record Filter(
            LocalDateTime from,
            LocalDateTime to,
            Long campaign,
            String code,
            String reviewState,
            boolean pendingOnly) {}

    private String where(long tenant, Filter f, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE o.tenant_id=?");
        args.add(tenant);
        if (f.from() != null) {
            sql.append(" AND o.call_finished_at>=?");
            args.add(f.from());
        }
        if (f.to() != null) {
            sql.append(" AND o.call_finished_at<?");
            args.add(f.to());
        }
        if (f.campaign() != null) {
            sql.append(" AND o.campaign_id=?");
            args.add(f.campaign());
        }
        if (f.code() != null && !f.code().isBlank()) {
            if ("SKIPPED".equals(f.code())) sql.append(" AND o.state='SKIPPED'");
            else {
                sql.append(" AND (o.connection_code=? OR o.answer_code=? OR o.intent_code=?)");
                args.addAll(List.of(f.code(), f.code(), f.code()));
            }
        }
        if (f.reviewState() != null && !f.reviewState().isBlank()) {
            sql.append(" AND o.review_state=?");
            args.add(f.reviewState());
        }
        if (f.pendingOnly()) sql.append(" AND o.needs_review=TRUE AND o.review_state='PENDING'");
        return sql.toString();
    }

    public Map<String, Object> list(long tenant, Filter f, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = where(tenant, f, args);
        long total = db.count("SELECT COUNT(*) FROM mk_call_outcome o" + where, args.toArray());
        args.add(Math.max(1, Math.min(size, 200)));
        args.add(Math.max(0, page - 1) * Math.max(1, Math.min(size, 200)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r :
                db.rows(
                        "SELECT o.call_id,o.attempt,o.campaign_id,c.name AS campaign_name,s.phone,o.state,o.skip_reason,o.connection_code,o.answer_code,o.intent_code,o.auto_connection_code,o.auto_answer_code,o.auto_intent_code,o.confidence,o.counts_against_user,o.rule_version,o.needs_review,o.review_reason,o.review_state,o.call_finished_at,o.classified_at FROM mk_call_outcome o LEFT JOIN callsession s ON s.tenant_id=o.tenant_id AND s.id=o.call_id LEFT JOIN campaign c ON c.tenant_id=o.tenant_id AND c.id=o.campaign_id"
                                + where
                                + " ORDER BY o.call_finished_at DESC,o.call_id LIMIT ? OFFSET ?",
                        args.toArray())) rows.add(view(r));
        return JsonSupport.map("items", rows, "total", total, "page", page);
    }

    private static Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        String e164 = PhoneIdentity.normalize(Objects.toString(m.remove("phone"), ""));
        m.put("phone_mask", e164 == null ? "****" : PhoneIdentity.mask(e164));
        return m;
    }

    public Map<String, Object> detail(long tenant, String callId) {
        Map<String, Object> r =
                db.one(
                        "SELECT o.*,c.name AS campaign_name,s.phone,s.status AS call_status FROM mk_call_outcome o LEFT JOIN callsession s ON s.tenant_id=o.tenant_id AND s.id=o.call_id LEFT JOIN campaign c ON c.tenant_id=o.tenant_id AND c.id=o.campaign_id WHERE o.tenant_id=? AND o.call_id=?",
                        tenant,
                        callId);
        if (r == null) throw new ApiError(404, "该通话尚无判定结果");
        Map<String, Object> m = view(r);
        m.remove("phone_hash");
        m.put("evidence", json.object(Objects.toString(r.get("evidence"), "{}")));
        m.put(
                "turns",
                db.rows(
                        "SELECT speaker_role,transcript,start_ms,end_ms,created_at FROM speechturn WHERE tenant_id=? AND call_session_id=? AND attempt=? AND is_final=TRUE ORDER BY id LIMIT 200",
                        tenant,
                        callId,
                        r.get("attempt")));
        m.put(
                "reviews",
                db.rows(
                        "SELECT layer,auto_code,previous_code,corrected_code,note,reviewer,created_at FROM mk_outcome_review WHERE tenant_id=? AND call_id=? ORDER BY created_at DESC LIMIT 60",
                        tenant,
                        callId));
        return m;
    }

    public Map<String, Object> stats(long tenant, Filter f) {
        List<Object> args = new ArrayList<>();
        String where =
                where(tenant, new Filter(f.from(), f.to(), f.campaign(), null, null, false), args);
        Map<String, Map<String, Long>> layers = new LinkedHashMap<>();
        for (String layer : OutcomeService.LAYERS) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (Map<String, Object> r :
                    db.rows(
                            "SELECT o."
                                    + layer
                                    + "_code AS code,COUNT(*) AS n FROM mk_call_outcome o"
                                    + where
                                    + " AND o.state='CLASSIFIED' AND o."
                                    + layer
                                    + "_code IS NOT NULL GROUP BY o."
                                    + layer
                                    + "_code",
                            args.toArray()))
                counts.put(r.get("code").toString(), ((Number) r.get("n")).longValue());
            layers.put(layer, counts);
        }
        long skipped =
                db.count(
                        "SELECT COUNT(*) FROM mk_call_outcome o" + where + " AND o.state='SKIPPED'",
                        args.toArray());
        long pending =
                db.count(
                        "SELECT COUNT(*) FROM mk_call_outcome o"
                                + where
                                + " AND o.needs_review=TRUE AND o.review_state='PENDING'",
                        args.toArray());
        var conn = layers.get("connection");
        var ans = layers.get("answer");
        long total = conn.values().stream().mapToLong(Long::longValue).sum();
        long lineFail = conn.getOrDefault(CALL_LINE_FAIL, 0L);
        long answered = conn.getOrDefault(CALL_ANSWERED, 0L);
        long intents = layers.get("intent").values().stream().mapToLong(Long::longValue).sum();
        return JsonSupport.map(
                "total",
                total,
                "skipped",
                skipped,
                "pending_review",
                pending,
                "connection",
                conn,
                "answer",
                ans,
                "intent",
                layers.get("intent"),
                "connection_rate",
                rate(answered, total - lineFail),
                "line_fail_rate",
                rate(lineFail, total),
                "instant_hangup_rate",
                rate(ans.getOrDefault(ANS_INSTANT_HANGUP, 0L), answered),
                "voice_assistant_share",
                rate(ans.getOrDefault(ANS_VOICE_ASSISTANT, 0L), answered),
                "conversation_rate",
                rate(ans.getOrDefault(ANS_CONVERSATION, 0L), answered),
                "intent_ab_share",
                rate(
                        layers.get("intent").getOrDefault(INTENT_A, 0L)
                                + layers.get("intent").getOrDefault(INTENT_B, 0L),
                        intents));
    }

    private static Double rate(long n, long d) {
        return d <= 0 ? null : Math.round(n * 10000.0 / d) / 10000.0;
    }

    /** Per automatic code: how many reviewed samples the reviewer kept unchanged. */
    public List<Map<String, Object>> accuracy(long tenant, LocalDateTime from, LocalDateTime to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String layer : OutcomeService.LAYERS) {
            List<Object> args = new ArrayList<>(List.of(tenant));
            String range = "";
            if (from != null) {
                range += " AND reviewed_at>=?";
                args.add(from);
            }
            if (to != null) {
                range += " AND reviewed_at<?";
                args.add(to);
            }
            String auto = "auto_" + layer + "_code", eff = layer + "_code";
            for (Map<String, Object> r :
                    db.rows(
                            "SELECT "
                                    + auto
                                    + " AS code,COUNT(*) AS reviewed,SUM(CASE WHEN "
                                    + auto
                                    + "<=>"
                                    + eff
                                    + " THEN 1 ELSE 0 END) AS correct FROM mk_call_outcome WHERE tenant_id=? AND review_state IN ('CONFIRMED','CORRECTED') AND "
                                    + auto
                                    + " IS NOT NULL"
                                    + range
                                    + " GROUP BY "
                                    + auto
                                    + " ORDER BY "
                                    + auto,
                            args.toArray())) {
                long reviewed = ((Number) r.get("reviewed")).longValue();
                long correct = ((Number) r.get("correct")).longValue();
                out.add(
                        JsonSupport.map(
                                "layer",
                                layer,
                                "code",
                                r.get("code"),
                                "reviewed",
                                reviewed,
                                "correct",
                                correct,
                                "accuracy",
                                rate(correct, reviewed)));
            }
        }
        return out;
    }

    public List<Map<String, Object>> campaigns(long tenant) {
        return db.rows(
                "SELECT id,name FROM campaign WHERE tenant_id=? ORDER BY id DESC LIMIT 300",
                tenant);
    }
}
