package com.duozhi.outbound.operations.service;

import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Missing observations and zero denominators remain null. */
@Service
public class CompetitiveMetrics {
    private final WorkspaceDocuments docs;

    public CompetitiveMetrics(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    private static BigDecimal value(Object v) {
        return v == null ? null : new BigDecimal(v.toString());
    }

    private static Object divide(Object a, Object b) {
        var n = value(a);
        var d = value(b);
        return n == null || d == null || d.signum() == 0
                ? null
                : n.divide(d, 4, RoundingMode.HALF_UP);
    }

    public List<Map<String, Object>> enrich(
            long tenant, List<Map<String, Object>> rows, Map<String, Object> filters) {
        var assumption = docs.find(tenant, "cost_assumption", "current");
        var price =
                assumption == null
                        ? Map.<String, Object>of()
                        : WorkspaceDocuments.map(assumption.get("draft"));
        var observations = online(tenant, rows, filters);
        for (var row : rows) {
            row.put("online_seconds", null);
            row.put("online_people", null);
            String bucket = Objects.toString(row.get("bucket"), "");
            try {
                row.put(
                        "weekday",
                        LocalDate.parse(bucket.substring(0, 10)).getDayOfWeek().getValue());
            } catch (Exception ignored) {
                row.put("weekday", null);
            }
            row.put(
                    "loss_rate",
                    divide(row.get("loss_count"), row.get("handoff_requests"))
                                    instanceof BigDecimal d
                            ? d.multiply(BigDecimal.valueOf(100))
                            : null);
            row.put(
                    "average_intervention",
                    divide(row.get("human_seconds"), row.get("handoff_received")));
            row.put(
                    "per_agent_takeover",
                    divide(row.get("handoff_received"), row.get("active_agents")));
            row.put(
                    "intent_cost",
                    divide(row.get("statement_cost"), row.get("interested_sessions")));
            row.put("success_cost", divide(row.get("statement_cost"), row.get("successful_calls")));
            row.put("per_agent_cost", divide(row.get("statement_cost"), row.get("active_agents")));
            BigDecimal rate = value(price.get("telephony")),
                    seconds = value(row.get("telephony_seconds"));
            boolean dated =
                    !Objects.toString(price.get("valid_from"), "").isBlank()
                            || !Objects.toString(price.get("valid_until"), "").isBlank();
            boolean valid = !dated;
            if (dated && bucket.length() >= 10) {
                String day = bucket.substring(0, 10),
                        from = Objects.toString(price.get("valid_from"), ""),
                        until = Objects.toString(price.get("valid_until"), "");
                valid =
                        (from.isBlank() || day.compareTo(from) >= 0)
                                && (until.isBlank() || day.compareTo(until) <= 0);
            }
            Object reference =
                    valid && rate != null && seconds != null
                            ? seconds.multiply(rate)
                                    .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP)
                            : null;
            row.put("reference_cost", reference);
            row.put("reference_intent_cost", divide(reference, row.get("interested_sessions")));
            row.put("reference_success_cost", divide(reference, row.get("successful_calls")));
            var observation = observations.get(row);
            if (observation != null && ((Number) observation.get("samples")).longValue() > 0) {
                row.put("online_seconds", observation.get("seconds"));
                row.put(
                        "online_people",
                        observation.get("seconds") instanceof Number n && n.doubleValue() > 0
                                ? 1
                                : 0);
            }
        }
        return rows;
    }

    private record Window(int index, long user, LocalDateTime from, LocalDateTime until) {}

    /** One bounded query per 200 visible buckets instead of one round trip for every row. */
    private Map<Map<String, Object>, Map<String, Object>> online(
            long tenant, List<Map<String, Object>> rows, Map<String, Object> filters) {
        var result = new IdentityHashMap<Map<String, Object>, Map<String, Object>>();
        if (!Set.of("account", "agent", "owner").contains(filters.get("dimension"))) return result;
        var windows = new ArrayList<Window>();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            if (!(row.get("dimension_key") instanceof Number user) || user.longValue() <= 0)
                continue;
            var start = (LocalDateTime) filters.get("from");
            var until = (LocalDateTime) filters.getOrDefault("until", now);
            String bucket = Objects.toString(row.get("bucket"), "");
            try {
                LocalDateTime a =
                        bucket.length() == 7
                                ? LocalDate.parse(bucket + "-01").atStartOfDay()
                                : bucket.length() == 10
                                        ? LocalDate.parse(bucket).atStartOfDay()
                                        : LocalDateTime.parse(bucket.replace(' ', 'T'));
                LocalDateTime b =
                        switch (Objects.toString(filters.get("granularity"))) {
                            case "month" -> a.plusMonths(1);
                            case "hour" -> a.plusHours(1);
                            case "minute" -> a.plusMinutes(1);
                            default -> a.plusDays(1);
                        };
                a = a.minusHours(8);
                b = b.minusHours(8);
                if (a.isAfter(start)) start = a;
                if (b.isBefore(until)) until = b;
            } catch (java.time.DateTimeException ignored) {
            }
            windows.add(new Window(index, user.longValue(), start, until));
        }
        for (int offset = 0; offset < windows.size(); offset += 200) {
            var chunk = windows.subList(offset, Math.min(offset + 200, windows.size()));
            var args = new ArrayList<Object>();
            var ranges = new ArrayList<String>();
            var users = new LinkedHashSet<Long>();
            LocalDateTime from = chunk.get(0).from(), until = chunk.get(0).until();
            for (var w : chunk) {
                ranges.add(
                        "SELECT ? AS row_index,? AS user_id,CAST(? AS DATETIME(3)) AS from_at,CAST(? AS DATETIME(3)) AS until_at");
                args.add(w.index());
                args.add(w.user());
                args.add(w.from());
                args.add(w.until());
                users.add(w.user());
                if (w.from().isBefore(from)) from = w.from();
                if (w.until().isAfter(until)) until = w.until();
            }
            args.add(tenant);
            args.addAll(users);
            args.add(from.minusSeconds(90));
            args.add(until);
            String sql =
                    "WITH ranges AS ("
                            + String.join(" UNION ALL ", ranges)
                            + "),spans AS (SELECT id,user_id,state,event_at,LEAD(event_at) OVER(PARTITION BY user_id ORDER BY event_at,id) AS next_at FROM ops_seat_state_sample WHERE tenant_id=? AND user_id IN ("
                            + String.join(",", Collections.nCopies(users.size(), "?"))
                            + ") AND event_at BETWEEN ? AND ?) SELECT r.row_index,COUNT(s.id) AS samples,SUM(CASE WHEN s.state IN ('ready','busy') THEN GREATEST(0,TIMESTAMPDIFF(MICROSECOND,GREATEST(s.event_at,r.from_at),LEAST(COALESCE(s.next_at,r.until_at),TIMESTAMPADD(SECOND,90,s.event_at),r.until_at)))/1000000 END) AS seconds FROM ranges r LEFT JOIN spans s ON s.user_id=r.user_id AND s.event_at BETWEEN TIMESTAMPADD(SECOND,-90,r.from_at) AND r.until_at GROUP BY r.row_index";
            for (var observation : docs.jdbc.queryForList(sql, args.toArray()))
                result.put(
                        rows.get(((Number) observation.get("row_index")).intValue()), observation);
        }
        return result;
    }

    public boolean formAllowed(long tenant, Object user, Object form, boolean admin) {
        if (admin) return true;
        var assignments =
                docs.list(tenant, "form_assignment", null).stream()
                        .map(d -> WorkspaceDocuments.map(d.get("published")))
                        .filter(
                                d ->
                                        Objects.equals(
                                                Objects.toString(d.get("form_id")),
                                                Objects.toString(form)))
                        .toList();
        return assignments.isEmpty()
                || assignments.stream()
                        .anyMatch(
                                d ->
                                        ((List<?>) d.getOrDefault("user_ids", List.of()))
                                                .stream()
                                                        .anyMatch(
                                                                id ->
                                                                        id.toString()
                                                                                .equals(
                                                                                        user
                                                                                                .toString())));
    }
}
