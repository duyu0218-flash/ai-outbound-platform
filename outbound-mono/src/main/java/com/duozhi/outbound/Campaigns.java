package com.duozhi.outbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@AdminPermission("campaigns:write")
@RequestMapping("/api/v1/campaigns")
public class Campaigns {
    private final Store store;
    private final Contract contract;
    private final CallPolicy policy;
    private final ObjectMapper mapper;
    private final Transactions transactions;
    private final DialService dial;
    private final CallLifecycle lifecycle;

    public Campaigns(Store store, Contract contract, CallPolicy policy, ObjectMapper mapper, Transactions transactions, DialService dial, CallLifecycle lifecycle) {
        this.store = store; this.contract = contract; this.policy = policy; this.mapper = mapper; this.transactions = transactions; this.dial = dial; this.lifecycle = lifecycle;
    }

    private long tenant(HttpServletRequest request) {
        Identity identity = Identity.from(request); identity.admin("campaigns:write"); return identity.tenant();
    }

    private Map<String, Object> locked(long tenant, long id) {
        transactions.platform();
        // Reserve the same transaction-scoped writer before reading the state.
        Map<String, Object> row = store.one("campaign","sourceCampaigns34_0",Store.queryParameters(0,tenant,id));
        if (row == null) throw new ApiError(404, "campaign not found");
        return row;
    }

    private List<Map<String, Object>> relations(long id) {
        return store.across("campaigncontact","scopedCampaigns40",Store.queryParameters(-1,id));
    }

    private Map<String, Object> output(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("contact_ids", relations(((Number)row.get("id")).longValue()).stream().map(r -> r.get("contact_id")).collect(Collectors.toList()));
        return contract.output("CampaignOut", result);
    }

    private List<Long> validate(long tenant, Map<String, Object> data) {
        List<Long> ids = new ArrayList<>();
        for (Object value : (List<?>)data.getOrDefault("contact_ids", Collections.emptyList())) {
            long id = ((Number)value).longValue();
            if (ids.contains(id)) throw new ApiError(400, "duplicate contact id");
            if (store.one("contact","sourceCampaigns54_2",Store.queryParameters(0,tenant,id)) == null) throw new ApiError(400, "contact not found: " + id);
            ids.add(id);
        }
        Object templateId = data.get("script_template_id");
        if (templateId != null) {
            Map<String, Object> template = store.one("scripttemplate","sourceCampaigns59_3",Store.queryParameters(0,tenant,templateId));
            if (template == null || !Boolean.TRUE.equals(template.get("is_active"))) throw new ApiError(400, "script template not found or inactive");
        }
        Object flowId = data.get("script_flow_version_id");
        if (flowId != null) {
            Map<String, Object> flow = store.one("scriptflowversion","sourceCampaigns64_4",Store.queryParameters(0,tenant,flowId));
            if (flow == null || templateId == null || !flow.get("script_template_id").toString().equals(templateId.toString()) || !"published".equals(flow.get("status")))
                throw new ApiError(400, "published flow version not found or does not belong to template");
        }
        return ids;
    }

    private void replaceRelations(long tenant,long id, List<Long> contacts) {
        store.execute("campaigncontact","replaceCampaignContacts",Values.map("tenantId",tenant,"campaignId",id));
        for (int index = 0; index < contacts.size(); index++)
            store.insert("campaigncontact", Values.map("campaign_id", id, "contact_id", contacts.get(index), "contact_order", index));
    }

    private String script(long tenant, Map<String, Object> campaign) {
        String script = Objects.toString(campaign.get("script"), "");
        if (!script.isEmpty() || campaign.get("script_template_id") == null) return script;
        Map<String, Object> template = store.one("scripttemplate","sourceCampaigns80_5",Store.queryParameters(0,tenant,campaign.get("script_template_id")));
        return template != null && Boolean.TRUE.equals(template.get("is_active")) ? Objects.toString(template.get("content"), "") : "";
    }

    private Object startNode(long tenant, Object flowId) {
        if (flowId == null) return null;
        Map<String, Object> flow = store.require("scriptflowversion", tenant, flowId);
        try {
            for (JsonNode node : mapper.readTree(flow.get("graph_json").toString()).path("nodes"))
                if ("start".equals(node.path("type").asText())) return node.path("id").asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new ApiError(409, "invalid published flow graph"); }
        throw new ApiError(409, "published flow has no start node");
    }

    private void sync(long tenant, Map<String, Object> campaign, Map<String, Object> call, Map<String, Object> contact) {
        if (((Number)call.get("attempts")).intValue() != 0) return;
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("phone", Contacts.phone(contact.get("phone")));
        changes.put("mode", campaign.get("mode"));
        changes.put("max_attempts", ((Number)campaign.get("retry_limit")).intValue() + 1);
        changes.put("script_flow_version_id", campaign.get("script_flow_version_id"));
        changes.put("flow_node_key", startNode(tenant, campaign.get("script_flow_version_id")));
        changes.put("voice_ai_pipeline", "pending");
        store.update("callsession", tenant, call.get("id"), changes);
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long tenant = tenant(request);
        Map<String, Object> data = contract.input("CampaignCreate", body, true);
        List<Long> contacts = validate(tenant, data);
        data.remove("contact_ids"); data.put("tenant_id", tenant);
        Map<String, Object> row = store.insert("campaign", data);
        replaceRelations(tenant,((Number)row.get("id")).longValue(), contacts);
        return output(row);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request, @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="50") int size) {
        return store.query("campaign","sourceCampaigns120_6",Store.queryParameters(0,tenant(request),size,Contract.page(page, size)))
                .stream().map(this::output).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id, HttpServletRequest request) {
        long tenant = tenant(request); Map<String, Object> row = store.require("campaign", tenant, id);
        if ("deleted".equals(row.get("status"))) throw new ApiError(404, "campaign not found");
        row.put("script", script(tenant, row)); return output(row);
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        long tenant = tenant(request); Map<String, Object> data = contract.input("CampaignCreate", body, true);
        Map<String, Object> existing = locked(tenant, id);
        if (Values.set("deleted", "running", "paused").contains(existing.get("status"))) throw new ApiError(409, "stop campaign before editing");
        List<Long> contacts = validate(tenant, data); data.remove("contact_ids");
        data.put("script", script(tenant, data));
        Map<String, Object> row = store.update("campaign", tenant, id, data);
        replaceRelations(tenant,id, contacts);
        for (Map<String, Object> call : store.query("callsession","sourceCampaigns141_7",Store.queryParameters(0,tenant,id))) {
            Object contactId = call.get("contact_id");
            if (contactId != null && contacts.contains(((Number)contactId).longValue())) sync(tenant, row, call, store.require("contact", tenant, contactId));
            else {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("status", "failed"); change.put("next_attempt_at", null); change.put("last_error", "contact removed before dispatch");
                store.update("callsession", tenant, call.get("id"), change);
            }
        }
        return output(row);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, String> delete(@PathVariable long id, HttpServletRequest request) {
        long tenant = tenant(request); Map<String, Object> row = locked(tenant, id);
        if (Values.set("running", "paused").contains(row.get("status"))) throw new ApiError(409, "stop campaign before deleting");
        if (!"deleted".equals(row.get("status"))) store.update("campaign", tenant, id, Values.map("status", "deleted"));
        return Values.map("result", "deleted");
    }

    @PostMapping("/{id}/start")
    public Map<String, Object> start(@PathVariable long id, HttpServletRequest request,
            @RequestParam(defaultValue="true") boolean auto_dial, @RequestParam(defaultValue="true") boolean async_dial,
            @RequestParam(required=false) Integer max_dials) {
        long tenant = tenant(request);
        Map<String, Object> result = transactions.run(() -> prepare(tenant, id, auto_dial, async_dial, max_dials));
        @SuppressWarnings("unchecked") List<Object> selected = (List<Object>)result.remove("_call_ids");
        if (auto_dial && !async_dial) {
            int succeeded = 0;
            List<Map<String, Object>> errors = new ArrayList<>();
            Set<String> codes = new TreeSet<>();
            for (Object callId : selected) {
                String code = "", message = "";
                try {
                    boolean attempted = dial.dispatch(callId);
                    Map<String, Object> call = store.require("callsession", tenant, callId);
                    if ("failed".equals(call.get("status"))) { code = "DISPATCH_FAILED"; message = Objects.toString(call.get("last_error"), "failed to dispatch call"); }
                    else if (!attempted) { code = "CALL_NOT_DISPATCHABLE"; message = "call status is " + call.get("status"); }
                    else succeeded++;
                } catch (RuntimeException e) { code = "DISPATCH_EXCEPTION"; message = "failed to dispatch call"; }
                if (!code.isEmpty()) { codes.add(code); errors.add(JsonSupport.map("code", code, "message", message, "call_id", callId)); }
            }
            result.put("auto_dial_count", succeeded);
            result.put("dispatch_result", JsonSupport.map("total", selected.size(), "target", selected.size(), "succeeded", succeeded,
                    "failed", errors.size(), "skipped", 0, "status", "completed", "errors", errors, "error_codes", codes));
            @SuppressWarnings("unchecked") Collection<String> prechecks = (Collection<String>)result.get("error_codes");
            Set<String> allCodes = new TreeSet<>(prechecks); allCodes.addAll(codes); result.put("error_codes", allCodes);
            if (!errors.isEmpty()) { result.put("result_code", succeeded > 0 ? "PARTIAL_SUCCESS" : "FAILED"); result.put("result_message", "campaign started with errors"); }
            if (succeeded == 0 && !selected.isEmpty()) transactions.run(() -> {
                Map<String, Object> row = locked(tenant, id);
                if ("running".equals(row.get("status")) && store.scalar("callsession","sourceCampaigns192_8",Store.queryParameters(0,tenant,id)) == 0)
                    store.update("campaign", tenant, id, Values.map("status", "failed", "dispatch_enabled", false));
                return null;
            });
            String status = store.require("campaign", tenant, id).get("status").toString(); result.put("status", status); result.put("campaign_status", status);
        }
        return result;
    }

    private Map<String, Object> prepare(long tenant, long id, boolean auto_dial, boolean async_dial, Integer max_dials) {
        Map<String, Object> campaign = locked(tenant, id);
        if (max_dials != null && max_dials < 1) throw new ApiError(422, "invalid max_dials");
        if (!Values.set("draft", "failed", "stopped", "prepared").contains(campaign.get("status"))) throw new ApiError(409, "campaign cannot be started from status " + campaign.get("status"));
        if (auto_dial) dial.available();
        List<Map<String, Object>> relations = relations(id);
        Map<Long, Map<String, Object>> existing = new HashMap<>();
        for (Map<String, Object> call : store.query("callsession","sourceCampaigns208_9",Store.queryParameters(0,tenant,id))) {
            if (call.get("contact_id") == null) continue;
            long contact = ((Number)call.get("contact_id")).longValue();
            if (!existing.containsKey(contact) || ("campaign:" + id + ":contact:" + contact).equals(call.get("campaign_contact_key"))) existing.put(contact, call);
        }
        int created = 0, prepared = 0;
        List<Object> callIds = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>(); Set<String> codes = new TreeSet<>();
        for (Map<String, Object> relation : relations) {
            long contactId = ((Number)relation.get("contact_id")).longValue();
            Map<String, Object> contact = store.one("contact","sourceCampaigns218_10",Store.queryParameters(0,tenant,contactId));
            String reason = "", code = "", phone = contact == null ? "" : Objects.toString(contact.get("phone"), "");
            if (contact == null) { code = "CONTACT_NOT_FOUND"; reason = "contact not found"; }
            else if (!Boolean.TRUE.equals(relation.get("is_active"))) { code = "CONTACT_INACTIVE"; reason = "contact inactive in campaign"; }
            else {
                Map<String, Object> call = existing.get(contactId);
                if (call != null) {
                    if ("created".equals(call.get("status")) || "failed".equals(call.get("status")) && CallLifecycle.number(call,"attempts")<CallLifecycle.number(call,"max_attempts")) {
                        sync(tenant, campaign, call, contact);
                        Map<String, Object> change = new LinkedHashMap<>(); change.put("status", "created"); change.put("next_attempt_at", null);
                        store.update("callsession", tenant, call.get("id"), change); prepared++; callIds.add(call.get("id"));
                    }
                    continue;
                }
                try { phone = Contacts.phone(phone); }
                catch (ApiError e) { code = "INVALID_PHONE"; reason = "invalid phone or missing contact"; }
                if (code.isEmpty()) { reason = policy.check(tenant, phone, true, null); if (!reason.isEmpty()) code = CallPolicy.code(reason); }
                if (code.isEmpty()) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("tenant_id", tenant); values.put("campaign_id", id); values.put("contact_id", contactId);
                    values.put("campaign_contact_key", "campaign:" + id + ":contact:" + contactId);
                    values.put("phone", phone); values.put("mode", campaign.get("mode")); values.put("status", "created");
                    values.put("max_attempts", ((Number)campaign.get("retry_limit")).intValue() + 1);
                    values.put("script_flow_version_id", campaign.get("script_flow_version_id")); values.put("flow_node_key", startNode(tenant, campaign.get("script_flow_version_id")));
                    values.put("voice_ai_pipeline", "pending"); callIds.add(store.insert("callsession", values).get("id")); created++; prepared++; continue;
                }
            }
            Map<String, Object> error = new LinkedHashMap<>(); error.put("code", code); error.put("message", reason); error.put("contact_id", contactId);
            error.put("phone", contact == null ? null : phone); error.put("call_id", null); skipped.add(error); codes.add(code);
        }
        List<Object> selected = new ArrayList<>(callIds.subList(0, max_dials == null ? callIds.size() : Math.min(max_dials, callIds.size())));
        String status = auto_dial ? (selected.isEmpty() ? "failed" : "running") : (prepared > 0 ? "prepared" : "failed");
        if (auto_dial) for (Object callId : selected)
            store.update("callsession", tenant, callId, JsonSupport.map("status", "queued", "next_attempt_at", null, "last_error", null));
        store.update("campaign", tenant, id, Values.map("status", status, "dispatch_enabled", auto_dial && !selected.isEmpty()));
        String resultCode = skipped.isEmpty() ? (auto_dial ? "SUCCESS" : "NOT_DISPATCHED") : created > 0 ? "PARTIAL_SUCCESS" : "FAILED";
        String message = skipped.isEmpty() ? (auto_dial ? "campaign started" : "campaign prepared, auto dial disabled") : created > 0 ? "campaign started with precheck warnings" : "campaign started with precheck blocking";
        return JsonSupport.map("id", id, "tenant_id", tenant, "name", campaign.get("name"), "status", status,
                "total_contacts", relations.size(), "created", created, "skipped", skipped.size(), "campaign_status", status,
                "auto_dial_requested", auto_dial, "auto_dial_count", auto_dial && async_dial ? selected.size() : 0,
                "dispatch_mode", auto_dial ? (async_dial ? "async" : "sync") : "prepared", "result_code", resultCode,
                "result_message", message, "error_codes", codes, "skip_reasons", skipped, "_call_ids", selected,
                "dispatch_result", JsonSupport.map("total", auto_dial ? callIds.size() : relations.size(), "target", auto_dial ? selected.size() : 0,
                    "succeeded", 0, "failed", 0, "skipped", auto_dial ? 0 : skipped.size(), "status", auto_dial ? "queued" : "not_dispatched", "errors", Collections.emptyList(), "error_codes", codes));
    }

    @PostMapping("/{id}/pause")
    @Transactional
    public Map<String, Object> pause(@PathVariable long id, HttpServletRequest request) {
        long tenant = tenant(request); Map<String, Object> row = locked(tenant, id);
        if ("deleted".equals(row.get("status"))) throw new ApiError(404, "campaign not found");
        if (!"running".equals(row.get("status"))) throw new ApiError(409, "only a running campaign can be paused");
        return output(store.update("campaign", tenant, id, Values.map("status", "paused", "dispatch_enabled", false)));
    }

    @PostMapping("/{id}/resume")
    @Transactional
    public Map<String, Object> resume(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> row = locked(tenant(request), id);
        if ("deleted".equals(row.get("status"))) throw new ApiError(404, "campaign not found");
        if (!Values.set("paused", "prepared").contains(row.get("status"))) throw new ApiError(409, "only a paused or prepared campaign can be resumed");
        dial.available();
        long tenant = tenant(request);
        for (Map<String, Object> call : store.query("callsession","sourceCampaigns281_11",Store.queryParameters(0,tenant,id)))
            store.update("callsession", tenant, call.get("id"), Values.map("status", "queued"));
        store.update("campaign", tenant, id, Values.map("status", "running", "dispatch_enabled", true));
        Map<String, Object> sample = store.one("callsession","sourceCampaigns284_12",Store.queryParameters(0,tenant,id));
        if (sample != null) lifecycle.completeCampaign(sample);
        return output(store.require("campaign", tenant, id));
    }

    @PostMapping("/{id}/stop")
    @Transactional
    public Map<String, Object> stop(@PathVariable long id, HttpServletRequest request) {
        long tenant = tenant(request); Map<String, Object> row = locked(tenant, id);
        if ("deleted".equals(row.get("status"))) throw new ApiError(404, "campaign not found");
        if (!Values.set("running", "paused", "prepared").contains(row.get("status"))) throw new ApiError(409, "only a running, paused or prepared campaign can be stopped");
        for (Map<String, Object> call : store.query("callsession","sourceCampaigns295_13",Store.queryParameters(0,tenant,id))) {
            Map<String, Object> change = new LinkedHashMap<>();
            if (Values.set("created", "queued").contains(call.get("status"))) { change.put("status", "failed"); change.put("last_error", "campaign stopped before dispatch"); }
            if (call.get("next_attempt_at") != null) change.put("next_attempt_at", null);
            if (!change.isEmpty()) store.update("callsession", tenant, call.get("id"), change);
        }
        return output(store.update("campaign", tenant, id, Values.map("status", "stopped", "dispatch_enabled", false)));
    }
}
