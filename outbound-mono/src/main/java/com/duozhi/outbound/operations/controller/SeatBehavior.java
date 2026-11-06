package com.duozhi.outbound.operations.controller;

import static com.duozhi.outbound.workspace.service.WorkspaceDocuments.*;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.operations.service.OutboundRules;
import com.duozhi.outbound.workspace.service.WorkspaceDocuments;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Administrator/personal precedence is applied separately to every seat behaviour. */
@RestController
@RequestMapping("/api/v1/ops/seat-behavior")
public class SeatBehavior {
    private final WorkspaceDocuments docs;

    @org.springframework.beans.factory.annotation.Autowired
    private com.duozhi.outbound.customer.controller.ContactFiles files;

    public SeatBehavior(WorkspaceDocuments docs) {
        this.docs = docs;
    }

    private static final List<String> FLAGS =
            List.of(
                    "auto_close",
                    "auto_takeover",
                    "seamless",
                    "manual_signin",
                    "lead_import",
                    "success_phone",
                    "success_invalid",
                    "ringtone");

    public static Map<String, Object> defaults() {
        var v = new LinkedHashMap<String, Object>();
        v.put("name", "坐席行为设置");
        v.putAll(
                Map.of(
                        "auto_close",
                        true,
                        "auto_takeover",
                        false,
                        "seamless",
                        false,
                        "manual_signin",
                        true,
                        "lead_import",
                        false,
                        "success_phone",
                        true,
                        "success_invalid",
                        true,
                        "ringtone",
                        false));
        v.put("close_seconds", 4);
        v.put("takeover_seconds", 0);
        v.put("ring_volume", 5);
        v.put("ring_audio", "");
        var scopes = new LinkedHashMap<String, Object>();
        FLAGS.forEach(k -> scopes.put(k, "personal"));
        v.put("scopes", scopes);
        return v;
    }

    private Identity actor(HttpServletRequest r) {
        var i = Identity.from(r);
        i.requireUser();
        return i;
    }

    private static String parent(String key) {
        return switch (key) {
            case "close_seconds" -> "auto_close";
            case "takeover_seconds" -> "auto_takeover";
            case "ring_volume", "ring_audio" -> "ringtone";
            default -> key;
        };
    }

    public Map<String, Object> effective(long tenant, Object user) {
        var global = docs.find(tenant, "seat_behavior", "tenant");
        var v = defaults();
        if (global != null) v.putAll(map(global.get("draft")));
        var own = docs.find(tenant, "seat_behavior", user.toString());
        if (own != null)
            for (var e : map(own.get("draft")).entrySet())
                if ("personal".equals(map(v.get("scopes")).get(parent(e.getKey()))))
                    v.put(e.getKey(), e.getValue());
        return v;
    }

    @GetMapping
    public Object read(@RequestParam(defaultValue = "false")
    boolean global, HttpServletRequest r) {
        var i = actor(r);
        if (global) i.admin("operations:write");
        var d = docs.find(i.tenant(), "seat_behavior", global ? "tenant" : i.userId().toString());
        return Map.of(
                "revision",
                d == null ? 0 : d.get("revision"),
                "config",
                global
                        ? (d == null ? defaults() : d.get("draft"))
                        : effective(i.tenant(), i.userId()));
    }

    @PostMapping("/import-leads")
    public Object importLeads(
            @RequestParam
            org.springframework.web.multipart.MultipartFile file,
            @RequestHeader("Idempotency-Key")
            String key,
            HttpServletRequest r)
            throws java.io.IOException {
        var i = actor(r);
        if (!Boolean.TRUE.equals(effective(i.tenant(), i.userId()).get("lead_import")))
            throw new ApiError(403, "当前坐席不允许导入跟进列表");
        if (file.isEmpty() || file.getSize() > 128 * 1024)
            throw new ApiError(422, "跟进导入文件须小于128KB");
        if (!key.matches("[A-Za-z0-9-]{1,64}")) throw new ApiError(422, "导入请求编号无效");
        var preview = files.process(file, false, null, i, true, Map.of());
        if (number(preview.get("total")) > 1000) throw new ApiError(422, "每次最多导入1000条跟进客户");
        return docs.tx.serialized(
                () -> {
                    try {
                        var result =
                                files.process(
                                        file,
                                        false,
                                        "seat-import:" + i.userId() + ":" + key,
                                        i,
                                        false,
                                        Map.of());
                        for (var row :
                                docs.jdbc.queryForList(
                                        "SELECT contact_id FROM platform_contact_batch_member WHERE tenant_id=? AND batch_id=?",
                                        i.tenant(),
                                        result.get("batch_id")))
                            docs.jdbc.update(
                                    "INSERT INTO ops_lead(tenant_id,contact_id,owner_id,fields_json,tags_json) VALUES(?,?,?,JSON_OBJECT(),JSON_ARRAY()) ON DUPLICATE KEY UPDATE owner_id=COALESCE(owner_id,VALUES(owner_id))",
                                    i.tenant(),
                                    row.get("contact_id"),
                                    i.userId());
                        return result;
                    } catch (java.io.IOException invalid) {
                        throw new ApiError(422, "跟进文件无法读取");
                    }
                });
    }

    @PutMapping
    public Object save(
            @RequestParam(defaultValue = "false")
            boolean global,
            @RequestBody
            Map<String, Object> b,
            HttpServletRequest r) {
        var i = actor(r);
        if (global) i.admin("operations:write");
        var raw = map(b.get("config"));
        var v = defaults();
        v.putAll(raw);
        for (String key : FLAGS)
            if (!(v.get(key) instanceof Boolean)) throw new ApiError(422, "坐席开关无效");
        v.put("close_seconds", OutboundRules.integer(v.get("close_seconds"), 0, 600));
        v.put("takeover_seconds", OutboundRules.integer(v.get("takeover_seconds"), 0, 60));
        v.put("ring_volume", OutboundRules.integer(v.get("ring_volume"), 0, 100));
        var scopes = map(v.get("scopes"));
        for (String key : FLAGS)
            if (!Set.of("personal", "uniform").contains(scopes.get(key)))
                throw new ApiError(422, "坐席配置作用域无效");
        String audio = Objects.toString(v.get("ring_audio"), "");
        if (!audio.isEmpty()
                && (!audio.matches("data:audio/(wav|mpeg);base64,[A-Za-z0-9+/=]+")
                        || audio.length() > 280000))
            throw new ApiError(422, "提示音须为200KB以内的WAV或MP3");
        v.put("name", "坐席行为设置");
        if (!global) {
            var allowed = map(effective(i.tenant(), i.userId()).get("scopes"));
            v.keySet()
                    .removeIf(
                            key ->
                                    !key.equals("name")
                                            && !"personal".equals(allowed.get(parent(key))));
        }
        docs.save(
                i,
                "seat_behavior",
                global ? "tenant" : i.userId().toString(),
                "",
                v,
                number(b.get("revision")));
        return read(global, r);
    }
}
