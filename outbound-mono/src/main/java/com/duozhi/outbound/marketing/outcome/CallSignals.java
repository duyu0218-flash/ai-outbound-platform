package com.duozhi.outbound.marketing.outcome;

import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.MarketingStore;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Everything the classifier reads about one finished call, gathered from the execution-layer
 * tables. Signals the current gateway does not emit yet (SIP code, hangup side, early-media text)
 * are read from optional callevent payload fields and listed in {@link Signals#missing} when
 * absent.
 */
@Service
public class CallSignals {
    private final MarketingStore db;
    private final JsonSupport json;

    public CallSignals(MarketingStore db, JsonSupport json) {
        this.db = db;
        this.json = json;
    }

    public record Turn(
            String role,
            String text,
            Double confidence,
            Integer startMs,
            Integer endMs,
            LocalDateTime at) {
        boolean customer() {
            return "customer".equals(role) || "user".equals(role);
        }
    }

    public record Signals(
            String callId,
            long tenant,
            Long campaignId,
            Long contactId,
            String phone,
            String status,
            int attempt,
            String lastError,
            String flowNode,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            LocalDateTime answeredAt,
            LocalDateTime endedAt,
            Integer sipCode,
            String hangupSide,
            String earlyMediaText,
            LocalDateTime openingDoneAt,
            List<Turn> turns,
            String analysisResult,
            String analysisIntent,
            boolean analysisReviewed,
            List<String> missing) {

        /** Seconds from dial start to answer (or to the end when never answered). */
        Double ringSeconds() {
            LocalDateTime end = answeredAt != null ? answeredAt : end();
            return startedAt == null || end == null ? null : seconds(startedAt, end);
        }

        Double talkSeconds() {
            return answeredAt == null || end() == null ? null : seconds(answeredAt, end());
        }

        LocalDateTime end() {
            return endedAt != null ? endedAt : finishedAt;
        }
    }

    static double seconds(LocalDateTime a, LocalDateTime b) {
        return Math.max(0, Duration.between(a, b).toMillis() / 1000.0);
    }

    static LocalDateTime time(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime t) return t;
        if (v instanceof Timestamp t) return t.toLocalDateTime();
        return LocalDateTime.parse(v.toString().replace(' ', 'T'));
    }

    static Long longValue(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    /** Null when the call does not exist in this tenant. */
    public Signals load(long tenant, String callId) {
        Map<String, Object> call =
                db.one(
                        "SELECT id,tenant_id,campaign_id,contact_id,phone,status,attempts,last_error,flow_node_key,started_at,finished_at FROM callsession WHERE tenant_id=? AND id=?",
                        tenant,
                        callId);
        if (call == null) return null;
        int attempt = ((Number) call.get("attempts")).intValue();
        List<String> missing = new ArrayList<>();
        Map<String, Object> usage =
                db.one(
                        "SELECT answered_at,ended_at FROM callusage WHERE tenant_id=? AND call_session_id=? ORDER BY attempt=? DESC,attempt DESC LIMIT 1",
                        tenant,
                        callId,
                        attempt);
        if (usage == null) missing.add("callusage");
        // callevent has no tenant_id column; the call id was tenant-checked above.
        List<Map<String, Object>> events =
                db.rows(
                        "SELECT event_type,payload FROM callevent WHERE call_session_id=? ORDER BY id DESC LIMIT 200",
                        callId);
        Integer sip = null;
        String side = null, early = null;
        for (Map<String, Object> e : events) {
            Map<String, Object> p;
            try {
                p = json.object(Objects.toString(e.get("payload"), "{}"));
            } catch (RuntimeException ex) {
                continue;
            }
            Object pa = p.get("attempt");
            if (pa != null && JsonSupport.integer(pa, attempt) != attempt) continue;
            if (sip == null && p.get("sip_code") != null) {
                int c = JsonSupport.integer(p.get("sip_code"), 0);
                if (c >= 100 && c < 700) sip = c;
            }
            if (side == null && p.get("hangup_disposition") != null)
                side = side(p.get("hangup_disposition").toString());
            if (side == null && p.get("hangup_side") != null)
                side = side(p.get("hangup_side").toString());
            if (early == null && p.get("early_media_text") != null)
                early = p.get("early_media_text").toString();
            if (early == null && "early_media".equals(e.get("event_type")) && p.get("text") != null)
                early = p.get("text").toString();
        }
        String lastError = Objects.toString(call.get("last_error"), null);
        if (sip == null && lastError != null) {
            var m =
                    java.util.regex.Pattern.compile("(?i)sip[ _:/-]*([1-6][0-9]{2})")
                            .matcher(lastError);
            if (m.find()) sip = Integer.parseInt(m.group(1));
        }
        if (sip == null) missing.add("sip_code");
        if (side == null) missing.add("hangup_side");
        if (early == null) missing.add("early_media_text");
        List<Turn> turns = new ArrayList<>();
        for (Map<String, Object> t :
                db.rows(
                        "SELECT speaker_role,transcript,normalized_transcript,is_final,confidence,start_ms,end_ms,created_at FROM speechturn WHERE tenant_id=? AND call_session_id=? AND attempt=? ORDER BY id",
                        tenant,
                        callId,
                        attempt)) {
            if (!Boolean.TRUE.equals(t.get("is_final"))
                    && !(t.get("is_final") instanceof Number n && n.intValue() == 1)) continue;
            String text = Objects.toString(t.get("normalized_transcript"), "");
            if (text.isBlank()) text = Objects.toString(t.get("transcript"), "");
            turns.add(
                    new Turn(
                            Objects.toString(t.get("speaker_role"), ""),
                            text.strip(),
                            t.get("confidence") == null
                                    ? null
                                    : ((Number) t.get("confidence")).doubleValue(),
                            t.get("start_ms") == null
                                    ? null
                                    : ((Number) t.get("start_ms")).intValue(),
                            t.get("end_ms") == null ? null : ((Number) t.get("end_ms")).intValue(),
                            time(t.get("created_at"))));
        }
        LocalDateTime started = time(call.get("started_at"));
        LocalDateTime opening = null;
        for (Map<String, Object> m :
                db.rows(
                        "SELECT stage,created_at FROM callmetric WHERE tenant_id=? AND call_session_id=? AND stage IN ('media.listening','media.playback_done','tts.done') ORDER BY id LIMIT 20",
                        tenant,
                        callId)) {
            LocalDateTime at = time(m.get("created_at"));
            if (started == null || !at.isBefore(started)) {
                opening = at;
                break;
            }
        }
        if (opening == null) missing.add("opening_done_at");
        Map<String, Object> analysis =
                db.one(
                        "SELECT result_code,intent,review_state FROM callanalysis WHERE tenant_id=? AND call_session_id=?",
                        tenant,
                        callId);
        if (analysis == null) missing.add("callanalysis");
        return new Signals(
                callId,
                tenant,
                longValue(call.get("campaign_id")),
                longValue(call.get("contact_id")),
                Objects.toString(call.get("phone"), ""),
                Objects.toString(call.get("status"), "").toLowerCase(Locale.ROOT),
                attempt,
                lastError,
                Objects.toString(call.get("flow_node_key"), null),
                started,
                time(call.get("finished_at")),
                usage == null ? null : time(usage.get("answered_at")),
                usage == null ? null : time(usage.get("ended_at")),
                sip,
                side,
                early,
                opening,
                turns,
                analysis == null ? null : Objects.toString(analysis.get("result_code"), null),
                analysis == null ? null : Objects.toString(analysis.get("intent"), null),
                analysis != null
                        && "reviewed"
                                .equalsIgnoreCase(
                                        Objects.toString(analysis.get("review_state"), "")),
                missing);
    }

    /** FreeSWITCH sip_hangup_disposition: recv_* means the far end acted, send_* means we did. */
    static String side(String raw) {
        String v = raw.strip().toLowerCase(Locale.ROOT);
        if (v.startsWith("recv_") || v.equals("remote") || v.equals("callee")) return "remote";
        if (v.startsWith("send_") || v.equals("platform") || v.equals("local")) return "platform";
        return null;
    }
}
