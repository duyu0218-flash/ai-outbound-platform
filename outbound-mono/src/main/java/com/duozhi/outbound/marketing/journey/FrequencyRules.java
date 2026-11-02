package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.marketing.common.MarketingStore;
import com.duozhi.outbound.marketing.common.OutcomeCodes;
import java.time.*;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cross-channel frequency control on top of the suppression lists: contact window, per-person daily
 * and weekly caps per channel, and SMS-to-call spacing. Counts come from mk_dispatch (every
 * marketing send, journeys and bulk tasks alike) and mk_touch_event (link clicks).
 */
@Service
public class FrequencyRules {
    public record Decision(boolean allowed, String reason, Instant retryAt) {
        static final Decision OK = new Decision(true, "", null);
    }

    private final MarketingStore db;
    private final PolicyConfig policies;
    private final MarketingClock clock;

    public FrequencyRules(MarketingStore db, PolicyConfig policies, MarketingClock clock) {
        this.db = db;
        this.policies = policies;
        this.clock = clock;
    }

    public Decision check(long tenant, String phoneHash, String channel) {
        return check(tenant, phoneHash, channel, clock.now());
    }

    public Decision check(long tenant, String phoneHash, String channel, Instant now) {
        return check(tenant, phoneHash, channel, now, true);
    }

    /**
     * @param window false skips the contact window (start estimates run at any hour, sends are then
     *     deferred into the window)
     */
    public Decision check(
            long tenant, String phoneHash, String channel, Instant now, boolean window) {
        return check(tenant, phoneHash, channel, now, window, false);
    }

    /** Current reads are only used after the send path has locked its phone guard. */
    public Decision checkForSend(long tenant, String phoneHash, String channel) {
        return check(
                tenant,
                phoneHash,
                channel,
                clock.now(),
                true,
                TransactionSynchronizationManager.isActualTransactionActive());
    }

    private Decision check(
            long tenant,
            String phoneHash,
            String channel,
            Instant now,
            boolean window,
            boolean current) {
        if (!OutcomeCodes.SMS.equals(channel) && !OutcomeCodes.CALL.equals(channel))
            return Decision.OK;
        PolicyConfig.Settings s = policies.get(tenant);
        String label = OutcomeCodes.SMS.equals(channel) ? "短信" : "外呼";

        ZonedDateTime local = now.atZone(s.zone());
        LocalTime start = s.start(), end = s.end(), t = local.toLocalTime();
        if (window && (t.isBefore(start) || (end != null && !t.isBefore(end)))) {
            LocalDate day =
                    t.isBefore(start) ? local.toLocalDate() : local.toLocalDate().plusDays(1);
            return new Decision(
                    false,
                    "不在可联系时段（" + s.windowStart() + "-" + s.windowEnd() + "）",
                    day.atTime(start).atZone(s.zone()).toInstant());
        }

        Instant dayStart = local.toLocalDate().atStartOfDay(s.zone()).toInstant();
        Instant weekStart = now.minus(Duration.ofDays(7));
        Instant smsSince = now.minus(Duration.ofMinutes(s.smsCallGapMinutes()));
        Map<String, Object> history =
                db.one(
                        "SELECT COALESCE(SUM(channel=? AND created_at>=?),0) daily_count,"
                                + "COALESCE(SUM(channel=? AND created_at>=?),0) weekly_count,"
                                + "MIN(CASE WHEN channel=? AND created_at>=? THEN created_at END) oldest,"
                                + "MAX(CASE WHEN channel=? AND created_at>? THEN created_at END) last_sms "
                                + "FROM mk_dispatch WHERE tenant_id=? AND phone_hash=? AND created_at>=? AND created_at<=?"
                                + currentRead(current),
                        channel,
                        clock.db(dayStart),
                        channel,
                        clock.db(weekStart),
                        channel,
                        clock.db(weekStart),
                        OutcomeCodes.SMS,
                        clock.db(smsSince),
                        tenant,
                        phoneHash,
                        clock.db(weekStart.isBefore(dayStart) ? weekStart : dayStart),
                        clock.db(now));
        int daily = s.dailyCap(channel);
        if (((Number) history.get("daily_count")).longValue() >= daily)
            return new Decision(
                    false,
                    label + "已达每日上限（" + daily + " 次）",
                    local.toLocalDate().plusDays(1).atTime(start).atZone(s.zone()).toInstant());

        int weekly = s.weeklyCap(channel);
        if (((Number) history.get("weekly_count")).longValue() >= weekly) {
            Object oldest = history.get("oldest");
            Instant retry =
                    oldest == null
                            ? now.plus(Duration.ofDays(1))
                            : clock.fromDb(local(oldest)).plus(Duration.ofDays(7));
            return new Decision(false, label + "已达每周上限（" + weekly + " 次）", retry);
        }

        if (OutcomeCodes.CALL.equals(channel) && s.smsCallGapMinutes() > 0) {
            Object sms = history.get("last_sms");
            if (sms != null) {
                Instant sentAt = clock.fromDb(local(sms));
                boolean clicked =
                        db.count(
                                        "SELECT COUNT(*) FROM mk_touch_event WHERE tenant_id=? AND phone_hash=? AND code=? AND occurred_at>=?"
                                                + currentRead(current),
                                        tenant,
                                        phoneHash,
                                        OutcomeCodes.LINK_CLICKED,
                                        MarketingClock.app(sentAt))
                                > 0;
                if (!clicked)
                    return new Decision(
                            false,
                            "短信发出 " + s.smsCallGapMinutes() + " 分钟内不外呼（未点击短链）",
                            sentAt.plus(Duration.ofMinutes(s.smsCallGapMinutes())));
            }
        }
        return Decision.OK;
    }

    private static String currentRead(boolean current) {
        return current ? " FOR UPDATE" : "";
    }

    static LocalDateTime local(Object value) {
        if (value instanceof LocalDateTime l) return l;
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }
}
