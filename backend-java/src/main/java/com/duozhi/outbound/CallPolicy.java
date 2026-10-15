package com.duozhi.outbound;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** The same preflight rules as Python can_call_contact_sync. No outbound side effects. */
@Service
public class CallPolicy {
    private final Store store;
    private final Settings settings;
    private final Config config;

    public CallPolicy(Store store, Settings settings, Config config) {
        this.store = store;
        this.settings = settings;
        this.config = config;
    }

    private boolean allowed(String phone, Object prefixes) {
        List<String> configured = new ArrayList<>();
        for (String prefix : Objects.toString(prefixes, "").split(",")) {
            if (!prefix.trim().isEmpty()) configured.add(prefix.trim());
        }
        return configured.isEmpty() || configured.stream().anyMatch(phone::startsWith);
    }

    private int number(Map<String, Object> values, String key, int fallback, int min, int max) {
        Object value = values.get(key);
        int result;
        try { result = Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException e) { result = fallback; }
        return Math.max(min, Math.min(max, result));
    }

    public String check(long tenant, String phone, boolean campaign, Object excludeCall) {
        return checkAt(tenant, phone, campaign, excludeCall, Instant.now());
    }

    String checkAt(long tenant, String phone, boolean campaign, Object excludeCall, Instant instant) {
        if (store.count("phonesuppression", "WHERE tenant_id=? AND phone=?", tenant, phone) > 0)
            return "contact_dnc";
        Map<String, Object> policy = settings.get(tenant, "compliance");
        Map<String, Object> contact = store.one("contact", "WHERE tenant_id=? AND phone=?", tenant, phone);
        if (!allowed(phone, config.get("OUTBOUND_ALLOWED_PHONE_PREFIXES", ""))) return "platform_destination_not_allowed";
        if (!allowed(phone, policy.get("allowed_phone_prefixes"))) return "destination_not_allowed";
        if (contact != null) {
            if (Boolean.TRUE.equals(contact.get("dnc")) && Boolean.TRUE.equals(policy.get("dnc_enforced"))) return "contact_dnc";
            if ("not_consented".equals(contact.get("consent_state"))) return "not_consented";
            if ("revoked".equals(contact.get("consent_state"))) return "consent_revoked";
        }
        String consentKey = campaign ? "require_explicit_consent" : "require_explicit_consent_for_direct_calls";
        if (Boolean.TRUE.equals(policy.get(consentKey)) && (contact == null || !"consented".equals(contact.get("consent_state"))))
            return "explicit_consent_required";
        String zone = contact == null ? "" : Objects.toString(contact.get("timezone"), "");
        if (zone.isEmpty()) zone = Objects.toString(policy.get("timezone"), "Asia/Shanghai");
        ZonedDateTime local;
        try { local = instant.atZone(ZoneId.of(zone)); }
        catch (DateTimeException e) { return "invalid_compliance_timezone"; }
        int start = number(policy, "allowed_start_hour", 9, 0, 23);
        int end = number(policy, "allowed_end_hour", 20, 0, 23);
        if (start != end && !(start <= local.getHour() && local.getHour() < end)) return "outside_calling_hours";
        LocalDateTime utcStart = LocalDateTime.ofInstant(local.toLocalDate().atStartOfDay(local.getZone()).toInstant(), ZoneOffset.UTC);
        Object bound = store.encode("callsession", "created_at", utcStart);
        Long legacy = store.jdbc().queryForObject("SELECT COALESCE(SUM(attempts),0) FROM callsession WHERE tenant_id=? AND phone=? AND created_at>=?", Long.class, tenant, phone, bound);
        Long events = store.jdbc().queryForObject("SELECT COUNT(*) FROM callevent e JOIN callsession c ON c.id=e.call_session_id WHERE c.tenant_id=? AND c.phone=? AND e.event_type='dial_attempt_claimed' AND e.created_at>=?", Long.class, tenant, phone, bound);
        if (Math.max(legacy == null ? 0 : legacy, events == null ? 0 : events) >= number(policy, "max_attempts_per_day", 3, 1, 20))
            return "daily_attempt_limit";
        int interval = number(policy, "min_attempt_interval_sec", 0, 0, 604800);
        if (interval > 0) {
            List<Object> args = new ArrayList<>(Arrays.asList(tenant, phone));
            String where = "WHERE tenant_id=? AND phone=?";
            if (excludeCall != null) { where += " AND id<>?"; args.add(store.encode("callsession", "id", excludeCall)); }
            Map<String, Object> latest = store.one("callsession", where + " ORDER BY created_at DESC LIMIT 1", args.toArray());
            if (latest != null) {
                for (String key : Arrays.asList("finished_at", "started_at", "updated_at", "created_at")) {
                    if (latest.get(key) == null) continue;
                    LocalDateTime anchor = LocalDateTime.parse(latest.get(key).toString());
                    if (Duration.between(anchor.toInstant(ZoneOffset.UTC), instant).getSeconds() < interval) return "retry_interval_not_elapsed";
                    break;
                }
            }
        }
        return "";
    }

    public static String code(String reason) {
        Map<String, String> codes = Values.map("contact_dnc", "CONTACT_DNC", "not_consented", "CONTACT_NOT_CONSENTED",
                "consent_revoked", "CONTACT_CONSENT_REVOKED", "retry_interval_not_elapsed", "CALL_RETRY_INTERVAL_NOT_ELAPSED");
        if (codes.containsKey(reason)) return codes.get(reason);
        if (Values.set("outside_calling_hours", "daily_attempt_limit", "invalid_compliance_timezone", "invalid_compliance_interval", "explicit_consent_required", "destination_not_allowed", "tenant_daily_call_limit").contains(reason))
            return reason.toUpperCase(Locale.ROOT);
        return "CALL_PRECHECK_" + reason.substring(0, Math.min(40, reason.length())).toUpperCase(Locale.ROOT);
    }
}
