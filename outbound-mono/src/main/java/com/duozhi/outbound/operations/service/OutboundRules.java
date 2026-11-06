package com.duozhi.outbound.operations.service;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Published tenant rules are rechecked at admission, including explicitly restarted calls. */
@Service
public class OutboundRules {
    @org.springframework.beans.factory.annotation.Autowired
    private TaskStrategies strategies;
    private final WorkspaceDocuments docs;

    public OutboundRules(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    public static Map<String, Object> defaults() {
        var value = new LinkedHashMap<String, Object>();
        value.put("name", "任务设置");
        value.put("mode", "basic");
        value.put("timezone", "Asia/Shanghai");
        value.put("weekdays", List.of(0, 1, 2, 3, 4, 5, 6));
        value.put("windows", List.of(Map.of("start", "00:00", "end", "24:00")));
        value.put("pauses", List.of());
        value.put("auto_start", true);
        value.put("max_daily", 20);
        value.put("min_interval_seconds", 0);
        value.put("low_balance", 0);
        value.put("target_intent_rate", 0);
        value.put("block_successful", false);
        return value;
    }

    public Map<String, Object> read(long tenant) {
        var doc = docs.find(tenant, "task_rules", "tenant");
        return doc == null ? Map.of("draft", defaults(), "revision", 0) : doc;
    }

    public Map<String, Object> save(Identity i, Map<String, Object> body) {
        var value = normalize(map(body.get("config")));
        return docs.tx.serialized(
                () -> {
                    var saved =
                            docs.save(
                                    i,
                                    "task_rules",
                                    "tenant",
                                    "",
                                    value,
                                    (int) number(body.get("revision")));
                    return docs.publish(
                            i, "task_rules", "tenant", (int) number(saved.get("revision")), value);
                });
    }

    public static int integer(Object raw, int min, int max) {
        try {
            int n = Integer.parseInt(Objects.toString(raw, ""));
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new ApiError(422, "数值范围无效");
        }
    }

    public static int minute(String value) {
        if ("24:00".equals(value)) return 1440;
        try {
            if (!value.matches("\\d{2}:\\d{2}")) throw new DateTimeException("format");
            var t = LocalTime.parse(value);
            return t.getHour() * 60 + t.getMinute();
        } catch (DateTimeException e) {
            throw new ApiError(422, "时间须为HH:mm");
        }
    }

    private static List<Map<String, Object>> windows(Object raw, boolean required) {
        if (!(raw instanceof List<?> list) || list.size() > 12 || (required && list.isEmpty()))
            throw new ApiError(422, "请配置1至12个外呼时段，暂停时段最多12个");
        var result = new ArrayList<Map<String, Object>>();
        for (Object item : list) {
            var v = map(item);
            String start = Objects.toString(v.get("start"), ""),
                    end = Objects.toString(v.get("end"), "");
            if (minute(start) >= minute(end)) throw new ApiError(422, "时段结束须晚于开始");
            result.add(Map.of("start", start, "end", end));
        }
        return result;
    }

    public static Map<String, Object> normalize(Map<String, Object> raw) {
        var v = defaults();
        v.putAll(raw);
        v.put("name", "任务设置");
        if (!Set.of("basic", "advanced").contains(v.get("mode"))) throw new ApiError(422, "启用模式无效");
        try {
            ZoneId.of(Objects.toString(v.get("timezone")));
        } catch (DateTimeException e) {
            throw new ApiError(422, "时区无效");
        }
        if (!(v.get("weekdays") instanceof List<?> days) || days.isEmpty() || days.size() > 7)
            throw new ApiError(422, "请选择外呼工作日");
        v.put(
                "weekdays",
                ((List<?>) v.get("weekdays"))
                        .stream().map(d -> integer(d, 0, 6)).distinct().toList());
        v.put("windows", windows(v.get("windows"), true));
        v.put("pauses", windows(v.get("pauses"), false));
        if (!(v.get("auto_start") instanceof Boolean)) throw new ApiError(422, "自动启动选项无效");
        v.put("max_daily", integer(v.get("max_daily"), 1, 20));
        v.put("min_interval_seconds", integer(v.get("min_interval_seconds"), 0, 604800));
        v.put("low_balance", integer(v.get("low_balance"), 0, 10000000));
        v.put("target_intent_rate", integer(v.get("target_intent_rate"), 0, 100));
        if (!(v.get("block_successful") instanceof Boolean)) throw new ApiError(422, "成功单拦截选项无效");
        return v;
    }

    private Map<String, Object> active(long tenant) {
        var batch = com.duozhi.outbound.dial.domain.DialBatchScope.current();
        String key = "outbound-rules:" + tenant;
        if (batch != null && batch.settings.containsKey(key)) return batch.settings.get(key);
        var doc = docs.find(tenant, "task_rules", "tenant");
        var value =
                new LinkedHashMap<>(
                        doc == null || doc.get("published") == null
                                ? defaults()
                                : map(doc.get("published")));
        value.put("_configured", doc != null && doc.get("published") != null);
        value.put("_filter_groups", docs.list(tenant, "filter_group", null));
        if (batch != null) batch.settings.put(key, value);
        return value;
    }

    public static boolean inWindow(Map<String, Object> v, Instant now) {
        var local = now.atZone(ZoneId.of(v.get("timezone").toString()));
        if (!((List<?>) v.get("weekdays")).contains(local.getDayOfWeek().getValue() - 1))
            return false;
        int at = local.getHour() * 60 + local.getMinute();
        return contains(v.get("windows"), at) && !contains(v.get("pauses"), at);
    }

    private static boolean contains(Object raw, int at) {
        return ((List<?>) raw)
                .stream()
                        .map(WorkspaceDocuments::map)
                        .anyMatch(
                                w ->
                                        at >= minute(w.get("start").toString())
                                                && at < minute(w.get("end").toString()));
    }

    public boolean open(long tenant, Instant now) {
        return inWindow(active(tenant), now);
    }

    public boolean automatic(long tenant) {
        return Boolean.TRUE.equals(active(tenant).get("auto_start"));
    }

    public String check(long tenant, String phone, Instant now) {
        var v = active(tenant);
        var groups = (List<?>) v.get("_filter_groups");
        var batches =
                docs.jdbc.queryForList(
                        "SELECT b.result_json FROM platform_contact_batch b JOIN platform_contact_batch_member m ON m.tenant_id=b.tenant_id AND m.batch_id=b.id JOIN contact c ON c.tenant_id=m.tenant_id AND c.id=m.contact_id WHERE c.tenant_id=? AND c.phone=? AND b.request_key NOT LIKE 'workspace-chunk:%'",
                        tenant, phone);
        boolean usable = batches.isEmpty();
        for (var b : batches) {
            String expiry =
                    Objects.toString(
                            docs.json.object(b.get("result_json").toString()).get("expires_at"),
                            "");
            if (expiry.isBlank() || now.isBefore(Instant.parse(expiry))) {
                usable = true;
                break;
            }
        }
        if (!usable) return "dataset_expired";
        String frequency = strategies.frequency(tenant, phone, null, now);
        if (!frequency.isEmpty()) return frequency;
        if (Boolean.TRUE.equals(v.get("block_successful")) && strategies.successful(tenant, phone))
            return "successful_order_intercept";
        if (!Boolean.TRUE.equals(v.get("_configured")) && groups.isEmpty()) return "";
        var local = now.atZone(ZoneId.of(v.get("timezone").toString()));
        var since =
                LocalDateTime.ofInstant(
                        local.toLocalDate().atStartOfDay(local.getZone()).toInstant(),
                        ZoneOffset.UTC);
        var stats =
                docs.jdbc.queryForMap(
                        "SELECT COUNT(*) AS n,MAX(e.created_at) AS last_at FROM callevent e JOIN callsession c ON c.id=e.call_session_id WHERE c.tenant_id=? AND c.phone=? AND e.event_type='dial_attempt_claimed' AND e.created_at>=?",
                        tenant,
                        phone,
                        since);
        if (((Number) stats.get("n")).intValue() >= integer(v.get("max_daily"), 1, 20))
            return "task_frequency_daily";
        int interval = integer(v.get("min_interval_seconds"), 0, 604800);
        if (interval > 0
                && docs.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM callevent e JOIN callsession c ON c.id=e.call_session_id WHERE c.tenant_id=? AND c.phone=? AND e.event_type='dial_attempt_claimed' AND e.created_at>?",
                                Long.class,
                                tenant,
                                phone,
                                LocalDateTime.ofInstant(now.minusSeconds(interval), ZoneOffset.UTC))
                        > 0) return "task_frequency_interval";
        for (var raw : groups) {
            var doc = map(raw);
            var policy = map(doc.get("published"));
            if (!Boolean.TRUE.equals(policy.get("enabled"))) continue;
            for (Object item : (List<?>) policy.getOrDefault("rules", List.of())) {
                var rule = map(item);
                String kind = Objects.toString(rule.get("type"), ""),
                        value = Objects.toString(rule.get("value"), "");
                boolean blocked =
                        switch (kind) {
                            case "phone" -> phone.equals(value);
                            case "prefix" -> phone.startsWith(value);
                            case "city" ->
                                    docs.jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM ops_call_dimension d JOIN callsession c ON c.tenant_id=d.tenant_id AND c.id=d.call_id WHERE c.tenant_id=? AND c.phone=? AND d.city=?",
                                                    Long.class,
                                                    tenant,
                                                    phone,
                                                    value)
                                            > 0;
                            case "frequency" ->
                                    ((Number) stats.get("n")).intValue() >= integer(value, 1, 20);
                            default -> false;
                        };
                if (blocked) {
                    if ("blacklist".equals(policy.get("action")))
                        docs.jdbc.update(
                                "INSERT INTO phonesuppression(tenant_id,phone,reason,created_at) VALUES(?,?,'过滤策略自动拉黑',UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE reason=VALUES(reason)",
                                tenant,
                                phone);
                    return "filter_group:" + doc.get("object_id") + ":" + kind;
                }
            }
        }
        return "";
    }

    public void blockedDuring(Map<String, Object> call, String reason) {
        blocked(
                ((Number) call.get("tenant_id")).longValue(),
                call.get("id"),
                call.get("phone").toString(),
                "during:" + reason);
    }

    public void blocked(long tenant, Object call, String phone, String reason) {
        docs.jdbc.update(
                "INSERT INTO ops_interception(tenant_id,call_id,phone,reason,created_at) VALUES(?,?,?,?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE reason=VALUES(reason)",
                tenant,
                call,
                phone,
                reason);
    }
}
