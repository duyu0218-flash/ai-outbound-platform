package com.duozhi.outbound.client.service;

import com.duozhi.outbound.client.dao.ClientStore;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import java.util.*;
import org.springframework.stereotype.Service;

/** Immutable, versioned delivery configuration of one client organisation. */
@Service
public class ClientSettings {
    /** Analysis results a client may subscribe to; other results are never delivered. */
    public static final Set<String> RESULTS =
            Set.of("qualified_lead", "callback_confirmed", "interested");

    private final ClientStore db;
    private final JsonSupport json;

    public ClientSettings(ClientStore db, JsonSupport json) {
        this.db = db;
        this.json = json;
    }

    public static Map<String, Object> defaults() {
        return JsonSupport.map(
                "deliver_results",
                List.of("qualified_lead"),
                "auto_deliver",
                false,
                "phone_reveal",
                false,
                "visible",
                JsonSupport.map(
                        "summary", true, "slots", true, "transcript", false, "recording", false),
                "dedup_days",
                30,
                "export",
                JsonSupport.map("enabled", false, "daily_limit", 0),
                "dispute_days",
                7);
    }

    /** Latest configuration merged over defaults, with its revision (0 when never saved). */
    public Map<String, Object> read(long tenant, long clientId) {
        Map<String, Object> row =
                db.one("settingLatest", JsonSupport.map("tenantId", tenant, "clientId", clientId));
        Map<String, Object> config = defaults();
        if (row != null) config.putAll(json.object(row.get("config_json").toString()));
        config.put("revision", row == null ? 0 : ((Number) row.get("revision")).intValue());
        return config;
    }

    public Map<String, Object> save(
            long tenant, long clientId, Map<String, Object> input, long actor) {
        Map<String, Object> current = read(tenant, clientId);
        int revision = JsonSupport.integer(input.get("revision"), -1);
        if (revision != (int) current.get("revision")) throw new ApiError(409, "交付配置已被修改，请刷新后重试");
        Map<String, Object> config = validate(input);
        db.write(
                "settingInsert",
                JsonSupport.map(
                        "tenantId",
                        tenant,
                        "clientId",
                        clientId,
                        "revision",
                        revision + 1,
                        "config_json",
                        json.json(config),
                        "actorId",
                        actor));
        return read(tenant, clientId);
    }

    static Map<String, Object> validate(Map<String, Object> input) {
        Map<String, Object> out = defaults();
        if (input.get("deliver_results") instanceof List<?> list) {
            var results = new ArrayList<String>();
            for (Object item : list) {
                if (!RESULTS.contains(String.valueOf(item)))
                    throw new ApiError(422, "不支持的交付结果：" + item);
                if (!results.contains(item.toString())) results.add(item.toString());
            }
            if (results.isEmpty()) throw new ApiError(422, "至少选择一种交付结果");
            out.put("deliver_results", results);
        }
        for (String key : List.of("auto_deliver", "phone_reveal"))
            if (input.containsKey(key)) out.put(key, bool(input.get(key)));
        if (input.get("visible") instanceof Map<?, ?> visible) {
            var fields = new LinkedHashMap<String, Object>();
            for (String key : List.of("summary", "slots", "transcript", "recording"))
                fields.put(key, bool(visible.get(key)));
            out.put("visible", fields);
        }
        if (input.containsKey("dedup_days"))
            out.put("dedup_days", range(input.get("dedup_days"), 0, 365));
        if (input.containsKey("dispute_days"))
            out.put("dispute_days", range(input.get("dispute_days"), 0, 90));
        if (input.get("export") instanceof Map<?, ?> export)
            out.put(
                    "export",
                    JsonSupport.map(
                            "enabled",
                            bool(export.get("enabled")),
                            "daily_limit",
                            range(export.get("daily_limit"), 0, 100000)));
        return out;
    }

    private static boolean bool(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        throw new ApiError(422, "开关字段必须是布尔值");
    }

    private static int range(Object value, int min, int max) {
        int n = JsonSupport.integer(value, Integer.MIN_VALUE);
        if (n < min || n > max) throw new ApiError(422, "数值超出范围（" + min + "-" + max + "）");
        return n;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> visible(Map<String, Object> config) {
        return (Map<String, Object>) config.get("visible");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> export(Map<String, Object> config) {
        return (Map<String, Object>) config.get("export");
    }
}
