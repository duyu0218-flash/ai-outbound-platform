package com.duozhi.outbound.client.service;

import com.duozhi.outbound.client.dao.ClientStore;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Lead filters, keyset paging and field trimming shared by staff and client endpoints. */
@Service
public class ClientLeadQuery {
    public static final Set<String> FOLLOW_STATES =
            Set.of("pending", "contacted", "valid", "invalid", "won");
    public static final Set<String> INVALID_REASONS =
            Set.of("empty_number", "wrong_person", "no_intent", "duplicate", "mismatch", "other");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1\\d{2})\\d{4}(\\d{4})(?!\\d)");
    private final ClientStore db;
    private final JsonSupport json;

    public ClientLeadQuery(ClientStore db, JsonSupport json) {
        this.db = db;
        this.json = json;
    }

    public static String maskText(Object text) {
        return PHONE.matcher(Objects.toString(text, "")).replaceAll("$1****$2");
    }

    static long number(String value, long min, long max) {
        try {
            long n = Long.parseLong(value);
            if (n < min || n > max) throw new IllegalArgumentException();
            return n;
        } catch (Exception e) {
            throw new ApiError(422, "数值范围无效");
        }
    }

    /** Dates are client business days (yyyy-MM-dd), converted to the stored UTC instants. */
    static LocalDateTime day(String value, ZoneId zone, boolean end) {
        try {
            LocalDate date = LocalDate.parse(value);
            return (end ? date.plusDays(1) : date)
                    .atStartOfDay(zone)
                    .withZoneSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (Exception e) {
            throw new ApiError(422, "日期格式应为 yyyy-MM-dd");
        }
    }

    public Map<String, Object> filters(
            long tenant, long clientId, Long assignee, Map<String, String> q, ZoneId zone) {
        Map<String, Object> p =
                JsonSupport.map("tenantId", tenant, "clientId", clientId, "assignee", assignee);
        if (present(q, "campaign_id"))
            p.put("campaign_id", number(q.get("campaign_id"), 1, Integer.MAX_VALUE));
        if (present(q, "status"))
            p.put("status", choice(q.get("status"), Set.of("delivered", "withdrawn")));
        if (present(q, "follow_state"))
            p.put("follow_state", choice(q.get("follow_state"), FOLLOW_STATES));
        if (present(q, "dispute_state"))
            p.put(
                    "dispute_state",
                    choice(
                            q.get("dispute_state"),
                            Set.of("none", "submitted", "accepted", "rejected")));
        if ("true".equals(q.get("unassigned"))) p.put("unassigned", true);
        if (present(q, "from")) p.put("from", day(q.get("from"), zone, false));
        if (present(q, "to")) p.put("until", day(q.get("to"), zone, true));
        if (present(q, "keyword")) {
            String keyword = q.get("keyword").trim();
            if (keyword.length() > 50) throw new ApiError(422, "关键词过长");
            p.put("keyword", keyword);
        }
        return p;
    }

    private static boolean present(Map<String, String> q, String key) {
        return q.get(key) != null && !q.get(key).isBlank();
    }

    private static String choice(String value, Set<String> allowed) {
        if (!allowed.contains(value)) throw new ApiError(422, "不支持的选项");
        return value;
    }

    public Map<String, Object> page(Map<String, Object> p, Map<String, String> q) {
        int limit = (int) number(q.getOrDefault("limit", "20"), 1, 100);
        String cursor = q.get("cursor");
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] parts =
                        new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                                .split("\\|");
                p.put("cursorAt", LocalDateTime.parse(parts[0]));
                p.put("cursorId", Long.parseLong(parts[1]));
            } catch (Exception e) {
                throw new ApiError(422, "分页游标无效");
            }
        }
        p.put("limit", limit + 1);
        List<Map<String, Object>> rows = db.rows("leadList", p);
        String next = null;
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            Map<String, Object> last = rows.get(limit - 1);
            next =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    (last.get("delivered_at") + "|" + last.get("id"))
                                            .getBytes(StandardCharsets.UTF_8));
        }
        p.remove("cursorAt");
        p.remove("cursorId");
        p.remove("limit");
        return JsonSupport.map(
                "items", rows, "next_cursor", next, "total", db.count("leadCount", p));
    }

    /** Staff see every stored field; clients only the fields their settings make visible. */
    public Map<String, Object> output(Map<String, Object> row, Map<String, Object> visible) {
        var out = new LinkedHashMap<>(row);
        out.put("slots", json.object(Objects.toString(out.remove("slots_json"), "{}")));
        out.put("consent_revoked", truthy(row.get("consent_revoked")));
        out.put("phone", row.get("phone_masked"));
        out.remove("phone_masked");
        out.put("unread", row.get("first_viewed_at") == null);
        if (visible != null) {
            out.remove("call_session_id");
            out.remove("contact_id");
            if (!Boolean.TRUE.equals(visible.get("summary"))) out.remove("summary");
            else out.put("summary", maskText(out.get("summary")));
            if (!Boolean.TRUE.equals(visible.get("slots"))) out.remove("slots");
        }
        return out;
    }

    public static boolean truthy(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0;
    }

    public List<Map<String, Object>> outputs(
            List<Map<String, Object>> rows, Map<String, Object> visible) {
        var out = new ArrayList<Map<String, Object>>();
        for (var row : rows) out.add(output(row, visible));
        return out;
    }

    public Map<String, Object> stats(
            long tenant, long clientId, Long assignee, int days, ZoneId zone) {
        LocalDateTime from =
                LocalDate.now(zone)
                        .minusDays(days - 1L)
                        .atStartOfDay(zone)
                        .withZoneSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime();
        Map<String, Object> p =
                JsonSupport.map(
                        "tenantId",
                        tenant,
                        "clientId",
                        clientId,
                        "assignee",
                        assignee,
                        "from",
                        from);
        Map<String, Object> summary = db.one("leadSummary", p);
        long followed =
                count(summary, "contacted")
                        + count(summary, "valid")
                        + count(summary, "invalid")
                        + count(summary, "won");
        long valid = count(summary, "valid") + count(summary, "won");
        return JsonSupport.map(
                "days",
                days,
                "summary",
                summary,
                "valid_rate",
                followed == 0 ? null : Math.round(valid * 1000.0 / followed) / 10.0,
                "trend",
                db.rows("leadTrend", p),
                "campaigns",
                db.rows("leadByCampaign", p),
                "invalid_reasons",
                db.rows("leadReasons", p));
    }

    private static long count(Map<String, Object> row, String key) {
        return row == null || row.get(key) == null ? 0 : ((Number) row.get(key)).longValue();
    }

    public List<Map<String, Object>> events(long tenant, long clientId, long lead) {
        var out = new ArrayList<Map<String, Object>>();
        for (var row :
                db.rows(
                        "eventsByLead",
                        JsonSupport.map(
                                "tenantId", tenant, "clientId", clientId, "leadId", lead))) {
            var item = new LinkedHashMap<>(row);
            item.put("detail", json.object(Objects.toString(item.remove("detail_json"), "{}")));
            out.add(item);
        }
        return out;
    }
}
