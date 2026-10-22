package com.duozhi.outbound.system.controller;

import com.duozhi.outbound.auth.domain.AdminPermission;
import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.common.annotation.PolicyWrite;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.dial.service.DialQuotaCounters;
import com.duozhi.outbound.system.service.Audit;
import com.duozhi.outbound.system.service.Settings;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/settings")
@AdminPermission("settings:write")
public class AdminSettings {
    @org.springframework.beans.factory.annotation.Autowired private DialQuotaCounters quotas;
    private final Store store;
    private final Settings settings;
    private final Config config;
    private final Audit audit;
    private final ObjectMapper mapper;

    public AdminSettings(
            Store store, Settings settings, Config config, Audit audit, ObjectMapper mapper) {
        this.store = store;
        this.settings = settings;
        this.config = config;
        this.audit = audit;
        this.mapper = mapper;
    }

    @GetMapping("/{section}")
    public Map<String, Object> get(@PathVariable String section, HttpServletRequest request) {
        com.duozhi.outbound.auth.domain.Identity actor = Identity.from(request);
        java.util.Map<java.lang.String, java.lang.Object> data =
                settings.get(actor.tenant(), section);
        java.util.Map<java.lang.String, java.lang.Object> record =
                store.one(
                        "adminsetting",
                        "findBySection",
                        Values.map("tenantId", actor.tenant(), "section", section));
        java.util.LinkedHashMap<java.lang.String, java.lang.Object> result =
                new LinkedHashMap<String, Object>();
        result.put("section", section);
        result.put("data", data);
        result.put("updated_at", record == null ? null : record.get("updated_at"));
        return result;
    }

    @PutMapping("/{section}")
    @PolicyWrite
    public Map<String, Object> update(
            @PathVariable String section,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        com.duozhi.outbound.auth.domain.Identity actor = Identity.from(request);
        if (!(body.get("data") instanceof Map)) throw new ApiError(400, "data is required");
        Map<?, ?> input = (Map<?, ?>) body.get("data");
        java.util.LinkedHashMap<java.lang.String, java.lang.Object> values =
                new LinkedHashMap<String, Object>();
        for (java.util.Map.Entry<?, ?> entry : input.entrySet())
            values.put(entry.getKey().toString(), entry.getValue());
        java.util.Map<java.lang.String, java.lang.Object> data = settings.validate(section, values);
        // PolicyWrite already holds admission's compatibility boundary before
        // JDBC checkout; keep tenant enabled stable before any ordinary reads.
        if (store.one("tenant", "lockForTransaction", Values.map("id", actor.tenant())) == null)
            throw new ApiError(404, "tenant not found");
        if (Values.set("capacity", "compliance").contains(section)) {
            boolean real =
                    store.scalar(
                                    "telephonyline",
                                    "countRealEnabled",
                                    Values.map("tenantId", actor.tenant()))
                            > 0;
            if (real
                    || config.production()
                    || !config.get("TELEPHONY_PROVIDER", "mock").equals("mock")) {
                String expected = config.get("OUTBOUND_SECURITY_APPROVAL_TOKEN", "");
                if (expected.length() < 32
                        || !Crypto.equal(expected, request.getHeader("x-security-approval")))
                    throw new ApiError(403, "independent security approval required");
            }
            if (section.equals("capacity")
                    && ((Number) data.get("max_concurrent_calls")).longValue()
                            > config.number("OUTBOUND_PLATFORM_MAX_CONCURRENT", 20))
                throw new ApiError(422, "租户并发不能超过平台安全上限");
            if (section.equals("compliance")
                    && ((Number) data.get("max_calls_per_day")).longValue()
                            > config.number("OUTBOUND_DAILY_CALL_LIMIT", 10000))
                throw new ApiError(422, "租户日拨号量不能超过平台安全上限");
        }
        if (section.equals("capacity"))
            quotas.assertLimit(
                    actor.tenant(),
                    null,
                    null,
                    ((Number) data.get("max_concurrent_calls")).longValue());
        java.util.Map<java.lang.String, java.lang.Object> row =
                store.one(
                        "adminsetting",
                        "findBySection",
                        Values.map("tenantId", actor.tenant(), "section", section));
        java.util.LinkedHashMap<java.lang.String, java.lang.Object> saved =
                new LinkedHashMap<String, Object>();
        saved.put("data_json", mapper.writeValueAsString(data));
        saved.put("updated_by", actor.userId());
        if (row == null) {
            saved.put("tenant_id", actor.tenant());
            saved.put("section", section);
            store.insert("adminsetting", saved);
        } else store.update("adminsetting", actor.tenant(), row.get("id"), saved);
        audit.record(actor, "update", "setting", section, "fields=" + values.keySet());
        return get(section, request);
    }
}
