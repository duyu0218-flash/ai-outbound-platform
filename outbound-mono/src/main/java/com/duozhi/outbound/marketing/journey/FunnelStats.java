package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.marketing.common.MarketingStore;
import com.duozhi.outbound.marketing.common.OutcomeCodes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hourly aggregates (mk_stat_hourly) and the dashboard built on them.
 *
 * <p>People metrics count a person once, in the hour of their first qualifying fact: journey
 * members (by journey, snapshot and test/control group) from their enrolment on, and snapshot sends
 * outside journeys per snapshot. Summing hours therefore never double-counts a person within a
 * scope. Event metrics count facts per outcome code; 'sent' counts mk_dispatch rows.
 */
@Service
public class FunnelStats {
    public static final List<String> STEPS =
            List.of("selected", "contacted", "engaged", "intent", "converted");
    static final Map<String, List<String>> CLASSES =
            Map.of(
                    "contacted", List.of(OutcomeCodes.SMS_DELIVERED, OutcomeCodes.CALL_ANSWERED),
                    "engaged", List.of(OutcomeCodes.LINK_CLICKED, OutcomeCodes.ANS_CONVERSATION),
                    "intent", List.of(OutcomeCodes.INTENT_A, OutcomeCodes.INTENT_B),
                    "converted", List.of(OutcomeCodes.CONVERTED, OutcomeCodes.LINK_CONVERTED));
    static final List<String> DIAL_RESULTS =
            List.of(
                    OutcomeCodes.CALL_NO_ANSWER,
                    OutcomeCodes.CALL_BUSY,
                    OutcomeCodes.CALL_REJECTED,
                    OutcomeCodes.CALL_INVALID,
                    OutcomeCodes.CALL_POWER_OFF,
                    OutcomeCodes.CALL_SUSPENDED,
                    OutcomeCodes.CALL_LINE_FAIL,
                    OutcomeCodes.CALL_ANSWERED);
    private static final String HOUR = "'%Y-%m-%d %H:00:00'";
    private static final Map<String, String> CHANNEL_FILTER =
            Map.of(
                    "",
                    "",
                    "sms",
                    " AND e.channel IN ('sms','link')",
                    "call",
                    " AND e.channel='call'");

    private final MarketingStore db;
    private final MarketingClock clock;
    private final PolicyConfig policies;
    private final TransactionTemplate tx;

    public FunnelStats(
            MarketingStore db,
            MarketingClock clock,
            PolicyConfig policies,
            PlatformTransactionManager manager) {
        this.db = db;
        this.clock = clock;
        this.policies = policies;
        this.tx = new TransactionTemplate(manager);
    }

    /** Recomputes the last {@code hours} hours for every tenant with recent activity. */
    public int recomputeRecent(int hours) {
        LocalDateTime to = clock.appNow().truncatedTo(ChronoUnit.HOURS).plusHours(1);
        LocalDateTime from = to.minusHours(Math.max(1, hours));
        // Cross-tenant scan by design: the aggregation job serves every tenant.
        List<Map<String, Object>> tenants =
                db.rows(
                        "SELECT tenant_id FROM mk_touch_event WHERE occurred_at>=? UNION SELECT tenant_id FROM mk_journey_member WHERE created_at>=? UNION SELECT tenant_id FROM mk_dispatch WHERE created_at>=?",
                        from,
                        from,
                        clock.db(MarketingClock.fromApp(from)));
        for (Map<String, Object> t : tenants)
            recompute(((Number) t.get("tenant_id")).longValue(), from, to);
        return tenants.size();
    }

    /** Rebuilds [from, to) for one tenant, hour by hour boundaries, in one transaction. */
    public void recompute(long tenant, LocalDateTime fromTime, LocalDateTime toTime) {
        LocalDateTime from = fromTime.truncatedTo(ChronoUnit.HOURS);
        LocalDateTime to = toTime.truncatedTo(ChronoUnit.HOURS);
        if (to.isBefore(toTime)) to = to.plusHours(1);
        if (!to.isAfter(from)) return;
        LocalDateTime f = from, t = to;
        LocalDateTime dbFrom = clock.db(MarketingClock.fromApp(from));
        LocalDateTime dbTo = clock.db(MarketingClock.fromApp(to));
        long shift = clock.dbToAppSeconds();
        tx.executeWithoutResult(
                s -> {
                    db.write(
                            "DELETE FROM mk_stat_hourly WHERE tenant_id=? AND stat_hour>=? AND stat_hour<?",
                            tenant,
                            f,
                            t);
                    String insert =
                            "INSERT INTO mk_stat_hourly(tenant_id,stat_hour,journey_id,snapshot_id,channel,grp,metric,value) ";
                    db.write(
                            insert
                                    + "SELECT e.tenant_id,DATE_FORMAT(e.occurred_at,"
                                    + HOUR
                                    + "),COALESCE(e.journey_id,''),COALESCE(e.snapshot_id,''),IF(e.channel='call','call','sms'),COALESCE(m.grp,''),CONCAT('code:',e.code),COUNT(*) "
                                    + "FROM mk_touch_event e LEFT JOIN mk_journey_member m ON m.tenant_id=e.tenant_id AND m.id=e.journey_member_id "
                                    + "WHERE e.tenant_id=? AND e.occurred_at>=? AND e.occurred_at<? GROUP BY 1,2,3,4,5,6,7",
                            tenant,
                            f,
                            t);
                    db.write(
                            insert
                                    + "SELECT d.tenant_id,DATE_FORMAT(d.created_at+INTERVAL ? SECOND,"
                                    + HOUR
                                    + "),COALESCE(d.journey_id,''),COALESCE(d.snapshot_id,''),IF(d.channel='call','call','sms'),COALESCE(m.grp,''),'sent',COUNT(*) "
                                    + "FROM mk_dispatch d LEFT JOIN mk_journey_member m ON m.tenant_id=d.tenant_id AND m.id=d.journey_member_id "
                                    + "WHERE d.tenant_id=? AND d.created_at>=? AND d.created_at<? GROUP BY 1,2,3,4,5,6,7",
                            shift,
                            tenant,
                            dbFrom,
                            dbTo);
                    db.write(
                            insert
                                    + "SELECT tenant_id,DATE_FORMAT(created_at,"
                                    + HOUR
                                    + "),journey_id,snapshot_id,'',grp,'people:selected',COUNT(*) FROM mk_journey_member "
                                    + "WHERE tenant_id=? AND created_at>=? AND created_at<? GROUP BY 1,2,3,4,5,6,7",
                            tenant,
                            f,
                            t);
                    db.write(
                            insert
                                    + "SELECT ?,DATE_FORMAT(x.first_at+INTERVAL ? SECOND,"
                                    + HOUR
                                    + ") h,'',x.snapshot_id,'','','people:selected',COUNT(*) FROM ("
                                    + "SELECT d.snapshot_id,d.phone_hash,MIN(d.created_at) first_at FROM mk_dispatch d "
                                    + "WHERE d.tenant_id=? AND d.journey_id IS NULL AND d.snapshot_id IS NOT NULL AND d.created_at<? GROUP BY d.snapshot_id,d.phone_hash) x "
                                    + "WHERE x.first_at>=? GROUP BY h,x.snapshot_id",
                            tenant,
                            shift,
                            tenant,
                            dbTo,
                            dbFrom);
                    for (var c : CLASSES.entrySet())
                        for (var ch : CHANNEL_FILTER.entrySet()) {
                            List<String> codes = c.getValue();
                            String marks = String.join(",", Collections.nCopies(codes.size(), "?"));
                            String metric = "people:" + c.getKey();
                            List<Object> a = new ArrayList<>();
                            a.add(tenant);
                            a.add(ch.getKey());
                            a.add(metric);
                            a.add(t);
                            a.addAll(codes);
                            a.add(tenant);
                            a.add(t);
                            a.add(f);
                            db.write(
                                    insert
                                            + "SELECT ?,DATE_FORMAT(x.first_at,"
                                            + HOUR
                                            + ") h,x.journey_id,x.snapshot_id,?,x.grp,?,COUNT(*) FROM ("
                                            + "SELECT m.id,m.journey_id,m.snapshot_id,m.grp,MIN(e.occurred_at) first_at FROM mk_journey_member m "
                                            + "JOIN mk_touch_event e ON e.tenant_id=m.tenant_id AND e.phone_hash=m.phone_hash AND e.occurred_at>=m.created_at AND e.occurred_at<? AND e.code IN ("
                                            + marks
                                            + ")"
                                            + ch.getValue()
                                            + " WHERE m.tenant_id=? AND m.created_at<? GROUP BY m.id,m.journey_id,m.snapshot_id,m.grp) x "
                                            + "WHERE x.first_at>=? GROUP BY h,x.journey_id,x.snapshot_id,x.grp",
                                    a.toArray());
                            List<Object> b = new ArrayList<>();
                            b.add(tenant);
                            b.add(ch.getKey());
                            b.add(metric);
                            b.add(tenant);
                            b.addAll(codes);
                            b.add(t);
                            b.add(f);
                            db.write(
                                    insert
                                            + "SELECT ?,DATE_FORMAT(x.first_at,"
                                            + HOUR
                                            + ") h,'',x.snapshot_id,?,'',?,COUNT(*) FROM ("
                                            + "SELECT e.snapshot_id,e.phone_hash,MIN(e.occurred_at) first_at FROM mk_touch_event e "
                                            + "WHERE e.tenant_id=? AND e.journey_id IS NULL AND e.snapshot_id IS NOT NULL AND e.code IN ("
                                            + marks
                                            + ")"
                                            + ch.getValue()
                                            + " AND e.occurred_at<? GROUP BY e.snapshot_id,e.phone_hash) x "
                                            + "WHERE x.first_at>=? GROUP BY h,x.snapshot_id",
                                    b.toArray());
                        }
                });
    }

    /** Rebuilds a date range for one tenant (operator "重新汇总"). */
    public int refresh(long tenant, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new ApiError(422, "结束日期不能早于开始日期");
        if (ChronoUnit.DAYS.between(from, to) > 92) throw new ApiError(422, "一次最多重新汇总 92 天");
        LocalDateTime start = from.atStartOfDay(), end = to.plusDays(1).atStartOfDay();
        // Daily slices; "first time" for people metrics still looks at all earlier facts.
        int days = 0;
        for (LocalDateTime d = start; d.isBefore(end); d = d.plusDays(1)) {
            recompute(tenant, d, d.plusDays(1));
            days++;
        }
        return days;
    }

    /** The dashboard: funnel, rates, incremental conversion and cost per intent. */
    public Map<String, Object> dashboard(
            long tenant,
            String journeyId,
            String snapshotId,
            LocalDate from,
            LocalDate to,
            String channel) {
        if (to.isBefore(from)) throw new ApiError(422, "结束日期不能早于开始日期");
        String ch = channel == null ? "" : channel;
        if (!CHANNEL_FILTER.containsKey(ch)) throw new ApiError(422, "渠道无效");
        StringBuilder where =
                new StringBuilder(" WHERE tenant_id=? AND stat_hour>=? AND stat_hour<?");
        List<Object> args =
                new ArrayList<>(
                        List.of(tenant, from.atStartOfDay(), to.plusDays(1).atStartOfDay()));
        if (journeyId != null && !journeyId.isBlank()) {
            where.append(" AND journey_id=?");
            args.add(journeyId);
        }
        if (snapshotId != null && !snapshotId.isBlank()) {
            where.append(" AND snapshot_id=?");
            args.add(snapshotId);
        }
        Totals all = new Totals();
        for (Map<String, Object> r :
                db.rows(
                        "SELECT channel,grp,metric,SUM(value) v FROM mk_stat_hourly"
                                + where
                                + " GROUP BY channel,grp,metric",
                        args.toArray())) all.add(r, ch);
        PolicyConfig.Settings s = policies.get(tenant);
        Map<String, Object> out = new LinkedHashMap<>(all.view(s));

        Map<String, Totals> byJourney = new LinkedHashMap<>();
        for (Map<String, Object> r :
                db.rows(
                        "SELECT journey_id,channel,grp,metric,SUM(value) v FROM mk_stat_hourly"
                                + where
                                + " GROUP BY journey_id,channel,grp,metric",
                        args.toArray()))
            byJourney.computeIfAbsent((String) r.get("journey_id"), k -> new Totals()).add(r, ch);
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> j :
                db.rows("SELECT id,name FROM mk_journey WHERE tenant_id=?", tenant))
            names.put((String) j.get("id"), (String) j.get("name"));
        List<Map<String, Object>> journeys = new ArrayList<>();
        byJourney.forEach(
                (id, tot) -> {
                    Map<String, Object> row = new LinkedHashMap<>(tot.view(s));
                    row.put("journey_id", id);
                    row.put("name", id.isEmpty() ? "非活动发送（直接按快照）" : names.getOrDefault(id, id));
                    row.remove("funnel");
                    journeys.add(row);
                });
        out.put("journeys", journeys);

        Map<String, Totals> byDay = new TreeMap<>();
        for (Map<String, Object> r :
                db.rows(
                        "SELECT DATE_FORMAT(stat_hour,'%Y-%m-%d') d,channel,grp,metric,SUM(value) v FROM mk_stat_hourly"
                                + where
                                + " GROUP BY d,channel,grp,metric",
                        args.toArray()))
            byDay.computeIfAbsent((String) r.get("d"), k -> new Totals()).add(r, ch);
        List<Map<String, Object>> daily = new ArrayList<>();
        byDay.forEach(
                (d, tot) -> {
                    Map<String, Object> row = new LinkedHashMap<>(tot.view(s));
                    row.put("date", d);
                    row.remove("funnel");
                    daily.add(row);
                });
        out.put("daily", daily);
        Map<String, Object> last =
                db.one("SELECT MAX(stat_hour) h FROM mk_stat_hourly WHERE tenant_id=?", tenant);
        out.put("latest_hour", last == null ? null : last.get("h"));
        out.put("unit_prices", Map.of("sms", s.smsUnitPrice(), "call", s.callUnitPrice()));
        return out;
    }

    /** Sums for one filter: people per group, codes and sends per channel. */
    static final class Totals {
        final Map<String, Long> test = new HashMap<>(), control = new HashMap<>();
        final Map<String, Long> codes = new HashMap<>();
        long smsSent, callSent;

        void add(Map<String, Object> r, String channel) {
            String c = (String) r.get("channel"), g = (String) r.get("grp");
            String metric = (String) r.get("metric");
            long v = ((Number) r.get("v")).longValue();
            if (metric.startsWith("people:")) {
                // Funnel steps read the channel-specific series (or the all-channel one).
                if (!c.equals(channel) && !(metric.equals("people:selected") && c.isEmpty()))
                    return;
                String step = metric.substring(7);
                (g.equals("control") ? control : test).merge(step, v, Long::sum);
            } else if (channel.isEmpty() || c.equals(channel)) {
                if (metric.equals("sent")) {
                    if (c.equals("call")) callSent += v;
                    else smsSent += v;
                } else codes.merge(metric.substring(5), v, Long::sum);
            }
        }

        long code(String c) {
            return codes.getOrDefault(c, 0L);
        }

        Map<String, Object> view(PolicyConfig.Settings s) {
            Map<String, Object> out = new LinkedHashMap<>();
            List<Map<String, Object>> funnel = new ArrayList<>();
            for (String step : STEPS)
                funnel.add(Map.of("step", step, "value", test.getOrDefault(step, 0L)));
            out.put("funnel", funnel);
            for (String step : STEPS) out.put(step, test.getOrDefault(step, 0L));
            long delivered = code(OutcomeCodes.SMS_DELIVERED);
            long answered = code(OutcomeCodes.CALL_ANSWERED);
            long dialed = DIAL_RESULTS.stream().mapToLong(this::code).sum();
            out.put("sms_sent", smsSent);
            out.put("sms_delivered", delivered);
            out.put("link_clicks", code(OutcomeCodes.LINK_CLICKED));
            out.put("call_dispatched", callSent);
            out.put("calls", dialed);
            out.put("call_answered", answered);
            out.put("sms_delivery_rate", rate(delivered, smsSent));
            out.put("click_rate", rate(code(OutcomeCodes.LINK_CLICKED), delivered));
            out.put("call_connect_rate", rate(answered, dialed));
            out.put("instant_hangup_rate", rate(code(OutcomeCodes.ANS_INSTANT_HANGUP), answered));
            out.put("voice_assistant_rate", rate(code(OutcomeCodes.ANS_VOICE_ASSISTANT), answered));
            out.put("conversation_rate", rate(code(OutcomeCodes.ANS_CONVERSATION), answered));
            long contacted = test.getOrDefault("contacted", 0L);
            long intent = test.getOrDefault("intent", 0L);
            out.put("intent_rate", rate(intent, contacted));
            BigDecimal conversion =
                    rate(test.getOrDefault("converted", 0L), test.getOrDefault("selected", 0L));
            BigDecimal controlConversion =
                    rate(
                            control.getOrDefault("converted", 0L),
                            control.getOrDefault("selected", 0L));
            out.put("conversion_rate", conversion);
            out.put("control_selected", control.getOrDefault("selected", 0L));
            out.put("control_converted", control.getOrDefault("converted", 0L));
            out.put("control_conversion_rate", controlConversion);
            out.put(
                    "incremental_conversion",
                    conversion == null || controlConversion == null
                            ? null
                            : conversion.subtract(controlConversion));
            BigDecimal cost =
                    s.smsUnitPrice()
                            .multiply(BigDecimal.valueOf(smsSent))
                            .add(s.callUnitPrice().multiply(BigDecimal.valueOf(dialed)))
                            .setScale(2, RoundingMode.HALF_UP);
            out.put("cost", cost);
            out.put(
                    "cost_per_intent",
                    intent == 0
                            ? null
                            : cost.divide(BigDecimal.valueOf(intent), 2, RoundingMode.HALF_UP));
            return out;
        }

        static BigDecimal rate(long part, long whole) {
            return whole == 0
                    ? null
                    : BigDecimal.valueOf(part)
                            .divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP);
        }
    }
}
