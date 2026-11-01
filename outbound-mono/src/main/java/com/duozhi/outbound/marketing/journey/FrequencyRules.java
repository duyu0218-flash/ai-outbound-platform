package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.marketing.common.MarketingStore;
import com.duozhi.outbound.marketing.common.OutcomeCodes;
import java.time.*;
import java.util.Map;
import org.springframework.stereotype.Service;

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
        int daily = s.dailyCap(channel);
        if (dispatches(tenant, phoneHash, channel, dayStart, now) >= daily)
            return new Decision(
                    false,
                    label + "已达每日上限（" + daily + " 次）",
                    local.toLocalDate().plusDays(1).atTime(start).atZone(s.zone()).toInstant());

        Instant weekStart = now.minus(Duration.ofDays(7));
        int weekly = s.weeklyCap(channel);
        if (dispatches(tenant, phoneHash, channel, weekStart, now) >= weekly) {
            Map<String, Object> oldest =
                    db.one(
                            "SELECT MIN(created_at) t FROM mk_dispatch WHERE tenant_id=? AND phone_hash=? AND channel=? AND created_at>=? AND created_at<=?",
                            tenant,
                            phoneHash,
                            channel,
                            clock.db(weekStart),
                            clock.db(now));
            Instant retry =
                    oldest == null || oldest.get("t") == null
                            ? now.plus(Duration.ofDays(1))
                            : clock.fromDb(local(oldest.get("t"))).plus(Duration.ofDays(7));
            return new Decision(false, label + "已达每周上限（" + weekly + " 次）", retry);
        }

        if (OutcomeCodes.CALL.equals(channel) && s.smsCallGapMinutes() > 0) {
            Instant since = now.minus(Duration.ofMinutes(s.smsCallGapMinutes()));
            Map<String, Object> sms =
                    db.one(
                            "SELECT MAX(created_at) t FROM mk_dispatch WHERE tenant_id=? AND phone_hash=? AND channel=? AND created_at>? AND created_at<=?",
                            tenant,
                            phoneHash,
                            OutcomeCodes.SMS,
                            clock.db(since),
                            clock.db(now));
            if (sms != null && sms.get("t") != null) {
                Instant sentAt = clock.fromDb(local(sms.get("t")));
                boolean clicked =
                        db.count(
                                        "SELECT COUNT(*) FROM mk_touch_event WHERE tenant_id=? AND phone_hash=? AND code=? AND occurred_at>=?",
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

    private long dispatches(
            long tenant, String phoneHash, String channel, Instant from, Instant to) {
        return db.count(
                "SELECT COUNT(*) FROM mk_dispatch WHERE tenant_id=? AND phone_hash=? AND channel=? AND created_at>=? AND created_at<=?",
                tenant,
                phoneHash,
                channel,
                clock.db(from),
                clock.db(to));
    }

    static LocalDateTime local(Object value) {
        if (value instanceof LocalDateTime l) return l;
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }
}
