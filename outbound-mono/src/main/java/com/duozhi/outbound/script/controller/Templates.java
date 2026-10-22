package com.duozhi.outbound.script.controller;

import com.duozhi.outbound.auth.domain.AdminPermission;
import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.common.web.Contract;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/script-templates")
@AdminPermission("script-templates:write")
public class Templates {
    private final Store store;
    private final Contract contract;

    public Templates(Store store, Contract contract) {
        this.store = store;
        this.contract = contract;
    }

    private Identity actor(HttpServletRequest request) {
        com.duozhi.outbound.auth.domain.Identity actor = Identity.from(request);
        actor.admin("script-templates:write");
        return actor;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "false") boolean active_only,
            @RequestParam(required = false) String category) {
        com.duozhi.outbound.auth.domain.Identity actor = actor(request);
        int offset = Contract.page(page, size);
        return store
                .query(
                        "scripttemplate",
                        "searchTemplates",
                        JsonSupport.map(
                                "tenantId",
                                actor.tenant(),
                                "activeOnly",
                                active_only,
                                "category",
                                category,
                                "offset",
                                offset,
                                "limit",
                                size))
                .stream()
                .map(row -> contract.output("ScriptTemplateOut", row))
                .toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id, HttpServletRequest request) {
        return contract.output(
                "ScriptTemplateOut", store.require("scripttemplate", actor(request).tenant(), id));
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        com.duozhi.outbound.auth.domain.Identity actor = actor(request);
        java.util.Map<java.lang.String, java.lang.Object> values =
                contract.input("ScriptTemplateCreate", body, true);
        values.put("tenant_id", actor.tenant());
        values.put("version", 1);
        return contract.output("ScriptTemplateOut", store.insert("scripttemplate", values));
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> update(
            @PathVariable long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        com.duozhi.outbound.auth.domain.Identity actor = actor(request);
        java.util.Map<java.lang.String, java.lang.Object> data =
                contract.input("ScriptTemplateUpdate", body, false);
        java.util.Map<java.lang.String, java.lang.Object> row =
                store.one(
                        "scripttemplate",
                        "lockById",
                        Values.map("tenantId", actor.tenant(), "id", id));
        if (row == null) throw new ApiError(404, "template not found");
        data.values().removeIf(Objects::isNull);
        data.put("version", ((Number) row.get("version")).intValue() + 1);
        return contract.output(
                "ScriptTemplateOut", store.update("scripttemplate", actor.tenant(), id, data));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, String> delete(@PathVariable long id, HttpServletRequest request) {
        store.update("scripttemplate", actor(request).tenant(), id, Values.map("is_active", false));
        return Values.map("result", "deleted");
    }
}
