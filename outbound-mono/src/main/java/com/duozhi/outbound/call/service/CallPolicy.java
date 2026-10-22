package com.duozhi.outbound.call.service;

import com.duozhi.outbound.call.domain.DailyAttemptCount;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.support.Ascii;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.dial.domain.DialBatchScope;
import com.duozhi.outbound.dial.service.DialDailyCounters;
import com.duozhi.outbound.system.service.Settings;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** The same preflight rules as Python can_call_contact_sync. No outbound side effects. */
@Service
public class CallPolicy {
    @Autowired
    private DialDailyCounters daily;
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
        try {
            result = Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            result = fallback;
        }
        return Math.max(min, Math.min(max, result));
    }

    public String check(long tenant, String phone, boolean campaign, Object excludeCall) {
        return checkAt(
                tenant,
                phone,
                campaign,
                excludeCall,
                DialBatchScope.current() == null
                        ? Instant.now()
                        : DialBatchScope.current().instant);
    }

    public String checkAt(
            long tenant, String phone, boolean campaign, Object excludeCall, Instant instant) {
        var batch = DialBatchScope.current();
        String cacheKey = tenant + ":" + phone;
        var facts = batch == null ? null : batch.facts.get(cacheKey);
        if ((facts == null
                        ? store.scalar(
                                "phonesuppression",
                                "countPhone",
                                Values.map("tenantId", tenant, "phone", phone))
                        : ((Number) facts.get("suppressed")).longValue())
                > 0) return "contact_dnc";
        Map<String, Object> policy = settings.get(tenant, "compliance");
        Map<String, Object> contact =
                batch != null && batch.contacts.containsKey(cacheKey)
                        ? batch.contacts.get(cacheKey)
                        : store.one(
                                "contact",
                                "findByPhone",
                                Values.map("tenantId", tenant, "phone", phone));
        if (!allowed(phone, config.get("OUTBOUND_ALLOWED_PHONE_PREFIXES", "")))
            return "platform_destination_not_allowed";
        if (!allowed(phone, policy.get("allowed_phone_prefixes"))) return "destination_not_allowed";
        if (contact != null) {
            if (Boolean.TRUE.equals(contact.get("dnc"))
                    && Boolean.TRUE.equals(policy.get("dnc_enforced"))) return "contact_dnc";
            if ("not_consented".equals(contact.get("consent_state"))) return "not_consented";
            if ("revoked".equals(contact.get("consent_state"))) return "consent_revoked";
        }
        String consentKey =
                campaign ? "require_explicit_consent" : "require_explicit_consent_for_direct_calls";
        if (Boolean.TRUE.equals(policy.get(consentKey))
                && (contact == null || !"consented".equals(contact.get("consent_state"))))
            return "explicit_consent_required";
        String zone = contact == null ? "" : Objects.toString(contact.get("timezone"), "");
        if (zone.isEmpty()) zone = Objects.toString(policy.get("timezone"), "Asia/Shanghai");
        ZonedDateTime local;
        try {
            local = instant.atZone(ZoneId.of(zone));
        } catch (DateTimeException e) {
            return "invalid_compliance_timezone";
        }
        int start = number(policy, "allowed_start_hour", 9, 0, 23);
        int end = number(policy, "allowed_end_hour", 20, 0, 23);
        if (start != end && !(start <= local.getHour() && local.getHour() < end))
            return "outside_calling_hours";
        LocalDateTime utcStart =
                LocalDateTime.ofInstant(
                        local.toLocalDate().atStartOfDay(local.getZone()).toInstant(),
                        ZoneOffset.UTC);
        Object bound = store.encode("callsession", "created_at", utcStart);
        Long generation =
                daily == null
                        ? null
                        : batch == null ? daily.readGeneration() : batch.dailyGeneration;
        DailyAttemptCount count =
                facts == null && generation != null
                        ? daily.read(generation, tenant, phone, utcStart, zone)
                        : null;
        Long legacy =
                count != null
                        ? count.legacy()
                        : facts == null
                                ? store.scalar(
                                        "callsession",
                                        "dailyAttempts",
                                        Values.map(
                                                "tenantId", tenant, "phone", phone, "start", bound))
                                : ((Number) facts.get("legacy_attempts")).longValue();
        Long events =
                count != null
                        ? count.events()
                        : facts == null
                                ? store.scalar(
                                        "callevent",
                                        "dailyAttemptEvents",
                                        Values.map(
                                                "tenantId", tenant, "phone", phone, "start", bound))
                                : ((Number) facts.get("event_attempts")).longValue();
        if (Math.max(legacy == null ? 0 : legacy, events == null ? 0 : events)
                >= number(policy, "max_attempts_per_day", 3, 1, 20)) return "daily_attempt_limit";
        int interval = number(policy, "min_attempt_interval_sec", 0, 0, 604800);
        if (interval > 0) {
            Map<String, Object> filters =
                    JsonSupport.map(
                            "tenantId",
                            tenant,
                            "phone",
                            phone,
                            "exclude",
                            excludeCall == null
                                    ? null
                                    : store.encode("callsession", "id", excludeCall));
            Map<String, Object> latest = store.one("callsession", "latestAttempt", filters);
            if (latest != null) {
                for (String key :
                        Arrays.asList("finished_at", "started_at", "updated_at", "created_at")) {
                    if (latest.get(key) == null) continue;
                    LocalDateTime anchor = LocalDateTime.parse(latest.get(key).toString());
                    if (Duration.between(anchor.toInstant(ZoneOffset.UTC), instant).getSeconds()
                            < interval) return "retry_interval_not_elapsed";
                    break;
                }
            }
        }
        return "";
    }

    public static String code(String reason) {
        Map<String, String> codes =
                Values.map(
                        "contact_dnc",
                        "CONTACT_DNC",
                        "not_consented",
                        "CONTACT_NOT_CONSENTED",
                        "consent_revoked",
                        "CONTACT_CONSENT_REVOKED",
                        "retry_interval_not_elapsed",
                        "CALL_RETRY_INTERVAL_NOT_ELAPSED");
        if (codes.containsKey(reason)) return codes.get(reason);
        if (Values.set(
                        "outside_calling_hours",
                        "daily_attempt_limit",
                        "invalid_compliance_timezone",
                        "invalid_compliance_interval",
                        "explicit_consent_required",
                        "destination_not_allowed",
                        "tenant_daily_call_limit")
                .contains(reason)) return reason.transform(Ascii::upper);
        return "CALL_PRECHECK_"
                + reason.substring(0, Math.min(40, reason.length())).transform(Ascii::upper);
    }
}
