package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.marketing.common.MarketingStore;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Per-tenant marketing policy (mk_policy_config). Without a row the defaults apply; the contact
 * window and zone defaults are explicit settings (MARKETING_CONTACT_WINDOW, MARKETING_TIMEZONE) so
 * a deployment, or a test harness, states them instead of inheriting them silently.
 */
@Service
public class PolicyConfig {
    public static final String DEFAULT_WINDOW = "09:00-20:00";
    public static final String DEFAULT_ZONE = "Asia/Shanghai";

    public record Settings(
            String timezone,
            String windowStart,
            String windowEnd,
            int smsDailyCap,
            int smsWeeklyCap,
            int callDailyCap,
            int callWeeklyCap,
            int smsCallGapMinutes,
            BigDecimal smsUnitPrice,
            BigDecimal callUnitPrice,
            BigDecimal approvalThreshold,
            List<Map<String, Object>> stageRules,
            boolean customized) {
        public ZoneId zone() {
            return ZoneId.of(timezone);
        }

        public LocalTime start() {
            return LocalTime.parse(windowStart);
        }

        /** Null means the window runs to midnight ("24:00"). */
        public LocalTime end() {
            return "24:00".equals(windowEnd) ? null : LocalTime.parse(windowEnd);
        }

        public int dailyCap(String channel) {
            return "call".equals(channel) ? callDailyCap : smsDailyCap;
        }

        public int weeklyCap(String channel) {
            return "call".equals(channel) ? callWeeklyCap : smsWeeklyCap;
        }

        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timezone", timezone);
            m.put("window_start", windowStart);
            m.put("window_end", windowEnd);
            m.put("sms_daily_cap", smsDailyCap);
            m.put("sms_weekly_cap", smsWeeklyCap);
            m.put("call_daily_cap", callDailyCap);
            m.put("call_weekly_cap", callWeeklyCap);
            m.put("sms_call_gap_minutes", smsCallGapMinutes);
            m.put("sms_unit_price", smsUnitPrice);
            m.put("call_unit_price", callUnitPrice);
            m.put("approval_threshold", approvalThreshold);
            m.put("stage_rules", stageRules);
            m.put("customized", customized);
            return m;
        }
    }

    private final MarketingStore db;
    private final Config config;
    private final JsonSupport json;
    private final MarketingClock clock;

    public PolicyConfig(MarketingStore db, Config config, JsonSupport json, MarketingClock clock) {
        this.db = db;
        this.config = config;
        this.json = json;
        this.clock = clock;
    }

    public Settings defaults() {
        String[] window = config.get("MARKETING_CONTACT_WINDOW", DEFAULT_WINDOW).split("-");
        return new Settings(
                config.get("MARKETING_TIMEZONE", DEFAULT_ZONE),
                window[0].trim(),
                window[1].trim(),
                2,
                6,
                2,
                6,
                120,
                new BigDecimal("0.0500"),
                new BigDecimal("0.1500"),
                new BigDecimal("5000.00"),
                StageRules.DEFAULT,
                false);
    }

    public Settings get(long tenant) {
        Map<String, Object> r = db.one("SELECT * FROM mk_policy_config WHERE tenant_id=?", tenant);
        if (r == null) return defaults();
        List<Map<String, Object>> rules = StageRules.DEFAULT;
        if (r.get("stage_rules") != null) rules = parseRules(r.get("stage_rules").toString());
        return new Settings(
                (String) r.get("timezone"),
                (String) r.get("window_start"),
                (String) r.get("window_end"),
                ((Number) r.get("sms_daily_cap")).intValue(),
                ((Number) r.get("sms_weekly_cap")).intValue(),
                ((Number) r.get("call_daily_cap")).intValue(),
                ((Number) r.get("call_weekly_cap")).intValue(),
                ((Number) r.get("sms_call_gap_minutes")).intValue(),
                (BigDecimal) r.get("sms_unit_price"),
                (BigDecimal) r.get("call_unit_price"),
                (BigDecimal) r.get("approval_threshold"),
                rules,
                true);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseRules(String text) {
        Object value = json.object("{\"r\":" + text + "}").get("r");
        return value instanceof List<?> l ? (List<Map<String, Object>>) l : StageRules.DEFAULT;
    }

    /** Merges the given fields over the current settings, validates and stores them. */
    @SuppressWarnings("unchecked")
    public Settings save(long tenant, Map<String, Object> body, long actor) {
        Settings c = get(tenant);
        String zone = text(body, "timezone", c.timezone());
        try {
            ZoneId.of(zone);
        } catch (Exception e) {
            throw new ApiError(422, "时区无效");
        }
        String start = time(text(body, "window_start", c.windowStart()), "可联系开始时间");
        String end = time(text(body, "window_end", c.windowEnd()), "可联系结束时间");
        if (!"24:00".equals(end) && LocalTime.parse(end).compareTo(LocalTime.parse(start)) <= 0)
            throw new ApiError(422, "可联系结束时间必须晚于开始时间");
        int smsDaily = cap(body, "sms_daily_cap", c.smsDailyCap());
        int smsWeekly = cap(body, "sms_weekly_cap", c.smsWeeklyCap());
        int callDaily = cap(body, "call_daily_cap", c.callDailyCap());
        int callWeekly = cap(body, "call_weekly_cap", c.callWeeklyCap());
        if (smsWeekly < smsDaily || callWeekly < callDaily) throw new ApiError(422, "每周上限不能小于每日上限");
        int gap = cap(body, "sms_call_gap_minutes", c.smsCallGapMinutes());
        if (gap > 7 * 24 * 60) throw new ApiError(422, "短信与外呼间隔过长");
        BigDecimal sms = price(body, "sms_unit_price", c.smsUnitPrice());
        BigDecimal call = price(body, "call_unit_price", c.callUnitPrice());
        BigDecimal threshold = price(body, "approval_threshold", c.approvalThreshold());
        List<Map<String, Object>> rules = c.stageRules();
        if (body.containsKey("stage_rules")) {
            Object raw = body.get("stage_rules");
            if (raw instanceof String s) raw = s.isBlank() ? null : parseRules(s);
            rules = raw == null ? StageRules.DEFAULT : (List<Map<String, Object>>) raw;
            StageRules.validate(rules);
        }
        db.write(
                "INSERT INTO mk_policy_config(tenant_id,timezone,window_start,window_end,sms_daily_cap,sms_weekly_cap,call_daily_cap,call_weekly_cap,sms_call_gap_minutes,sms_unit_price,call_unit_price,approval_threshold,stage_rules,updated_by,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE timezone=VALUES(timezone),window_start=VALUES(window_start),window_end=VALUES(window_end),sms_daily_cap=VALUES(sms_daily_cap),sms_weekly_cap=VALUES(sms_weekly_cap),call_daily_cap=VALUES(call_daily_cap),call_weekly_cap=VALUES(call_weekly_cap),sms_call_gap_minutes=VALUES(sms_call_gap_minutes),sms_unit_price=VALUES(sms_unit_price),call_unit_price=VALUES(call_unit_price),approval_threshold=VALUES(approval_threshold),stage_rules=VALUES(stage_rules),updated_by=VALUES(updated_by),updated_at=VALUES(updated_at)",
                tenant,
                zone,
                start,
                end,
                smsDaily,
                smsWeekly,
                callDaily,
                callWeekly,
                gap,
                sms,
                call,
                threshold,
                json.json(rules),
                actor,
                clock.appNow());
        return get(tenant);
    }

    private static String text(Map<String, Object> body, String key, String fallback) {
        Object v = body.get(key);
        return v == null || v.toString().isBlank() ? fallback : v.toString().trim();
    }

    private static String time(String value, String label) {
        if ("24:00".equals(value)) return value;
        if (!value.matches("([01][0-9]|2[0-3]):[0-5][0-9]"))
            throw new ApiError(422, label + "格式应为 HH:mm");
        return value;
    }

    private static int cap(Map<String, Object> body, String key, int fallback) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) return fallback;
        try {
            int n = new BigDecimal(v.toString()).intValueExact();
            if (n < 0 || n > 100_000) throw new ApiError(422, key + " 超出范围");
            return n;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ApiError(422, key + " 必须是整数");
        }
    }

    private static BigDecimal price(Map<String, Object> body, String key, BigDecimal fallback) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank()) return fallback;
        try {
            BigDecimal d = new BigDecimal(v.toString());
            if (d.signum() < 0 || d.compareTo(new BigDecimal("1000000000")) > 0)
                throw new ApiError(422, key + " 超出范围");
            return d;
        } catch (NumberFormatException e) {
            throw new ApiError(422, key + " 必须是数字");
        }
    }
}
