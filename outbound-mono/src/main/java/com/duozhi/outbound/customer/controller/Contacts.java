package com.duozhi.outbound.customer.controller;

import com.duozhi.outbound.auth.domain.AdminPermission;
import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.annotation.PolicyWrite;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.common.web.Contract;
import com.duozhi.outbound.system.service.Audit;
import jakarta.servlet.http.HttpServletRequest;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/contacts")
@AdminPermission("contacts:write")
public class Contacts {
    private final Store store;
    private final Contract contract;
    private final Audit audit;

    public Contacts(Store store, Contract contract, Audit audit) {
        this.store = store;
        this.contract = contract;
        this.audit = audit;
    }

    public static String phone(Object input) {
        String value = Objects.toString(input, "").replaceAll("[^0-9]", "");
        if (value.length() < 6 || value.length() > 15)
            throw new ApiError(400, "phone must contain 6 to 15 digits");
        return value;
    }

    public static void timezone(Object value) {
        if (value == null || value.toString().trim().isEmpty()) return;
        try {
            ZoneId.of(value.toString());
        } catch (DateTimeException e) {
            throw new ApiError(400, "invalid contact timezone");
        }
    }

    private Identity actor(HttpServletRequest request) {
        com.duozhi.outbound.auth.domain.Identity identity = Identity.from(request);
        identity.admin("contacts:write");
        return identity;
    }

    private void lockPolicy(Identity actor) {
        if (store.one("tenant", "lockForTransaction", Values.map("id", actor.tenant())) == null)
            throw new ApiError(404, "tenant not found");
    }

    private void consent(Map<String, Object> data, Identity actor) {
        if (data.containsKey("consent_state")) {
            boolean yes = "consented".equals(data.get("consent_state"));
            data.put("consented_at", yes ? Store.now() : null);
            data.put("consented_by", yes ? actor.actor() : null);
        }
    }

    @PostMapping
    @PolicyWrite
    public Map<String, Object> create(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        com.duozhi.outbound.auth.domain.Identity actor = actor(request);
        java.util.Map<java.lang.String, java.lang.Object> values =
                contract.input("ContactCreate", body, true);
        lockPolicy(actor);
        values.put("phone", phone(values.get("phone")));
        timezone(values.get("timezone"));
        values.put("tenant_id", actor.tenant());
        consent(values, actor);
        if ("".equals(values.get("dnc_reason"))) values.put("dnc_reason", null);
        if (store.scalar(
                        "contact",
                        "countPhone",
                        Values.map("tenantId", actor.tenant(), "phone", values.get("phone")))
                > 0) throw new ApiError(409, "contact phone already exists");
        java.util.Map<java.lang.String, java.lang.Object> row = store.insert("contact", values);
        audit.record(actor, "create", "contact", row.get("id"), "contact created");
        return contract.output("ContactOut", row);
    }

    @GetMapping
    public List<Map<String, Object>> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<Long> ids,
            @RequestParam(required = false) Boolean dnc,
            @RequestParam(required = false) String consent) {
        com.duozhi.outbound.auth.domain.Identity actor = actor(request);
        int offset = Contract.page(page, size);
        if (ids != null) {
            if (ids.size() > 200) throw new ApiError(422, "too many ids");
            if (ids.isEmpty()) return Values.list();
        }
        var filters = new LinkedHashMap<String, Object>();
        filters.put("tenantId", actor.tenant());
        filters.put("keyword", keyword);
        filters.put("ids", ids);
        filters.put("dnc", dnc);
        filters.put(
                "consent",
                consent == null || consent.isEmpty()
                        ? null
                        : store.encode("contact", "consent_state", consent));
        filters.put("limit", size);
        filters.put("offset", offset);
        return store.query("contact", "searchContacts", filters).stream()
                .map(row -> contract.output("ContactOut", row))
                .toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id, HttpServletRequest request) {
        return contract.output("ContactOut", store.require("contact", actor(request).tenant(), id));
    }

    @PatchMapping("/{id}")
    @PolicyWrite
    public Map<String, Object> update(
            @PathVariable long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        com.duozhi.outbound.auth.domain.Identity actor = actor(request);
        java.util.Map<java.lang.String, java.lang.Object> data =
                contract.input("ContactPatch", body, false);
        timezone(data.get("timezone"));
        consent(data, actor);
        lockPolicy(actor);
        java.util.Map<java.lang.String, java.lang.Object> row =
                store.update("contact", actor.tenant(), id, data);
        audit.record(actor, "update", "contact", id, "fields=" + data.keySet());
        return contract.output("ContactOut", row);
    }

    @DeleteMapping("/{id}")
    @PolicyWrite
    public Map<String, String> delete(@PathVariable long id, HttpServletRequest request) {
        com.duozhi.outbound.auth.domain.Identity actor = actor(request);
        lockPolicy(actor);
        store.require("contact", actor.tenant(), id);
        long campaignReferences =
                store.scalar(
                        "contact",
                        "campaignReferences",
                        Values.map("id", id, "tenantId", actor.tenant()));
        if (campaignReferences > 0
                || store.scalar(
                                "callsession",
                                "countContactReferences",
                                Values.map("tenantId", actor.tenant(), "contactId", id))
                        > 0)
            throw new ApiError(
                    409, "contact is referenced by campaign or call history; mark it DNC instead");
        audit.record(actor, "delete", "contact", id, "contact deleted");
        store.delete("contact", actor.tenant(), id);
        return Values.map("result", "deleted");
    }

    @PatchMapping("/batch-dnc")
    @PolicyWrite
    public Map<String, Object> batch(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        com.duozhi.outbound.auth.domain.Identity actor = actor(request);
        java.util.Map<java.lang.String, java.lang.Object> input =
                contract.input("ContactBatchDncPatch", body, true);
        lockPolicy(actor);
        java.util.TreeSet<java.lang.Long> ids = new TreeSet<Long>();
        for (Object id : (List<?>) input.get("contact_ids")) ids.add(((Number) id).longValue());
        java.util.ArrayList<java.lang.Long> missing = new ArrayList<Long>();
        int updated = 0;
        for (long id : ids) {
            java.util.Map<java.lang.String, java.lang.Object> row =
                    store.one(
                            "contact",
                            "findById",
                            Values.map("tenantId", actor.tenant(), "id", id));
            if (row == null) {
                missing.add(id);
                continue;
            }
            java.util.LinkedHashMap<java.lang.String, java.lang.Object> change =
                    new LinkedHashMap<String, Object>();
            change.put("dnc", input.get("dnc"));
            change.put("dnc_reason", input.get("dnc_reason"));
            if (Boolean.TRUE.equals(input.get("dnc"))) change.put("consented_at", null);
            store.update("contact", actor.tenant(), id, change);
            updated++;
        }
        audit.record(actor, "batch_update", "contact", null, "updated=" + updated);
        return Values.map(
                "total",
                ids.size(),
                "updated",
                updated,
                "skipped",
                missing.size(),
                "missing_contact_ids",
                missing);
    }
}
