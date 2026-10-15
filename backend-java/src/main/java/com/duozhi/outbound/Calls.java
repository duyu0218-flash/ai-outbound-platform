package com.duozhi.outbound;

import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/calls")
public class Calls {
    private final Store store;
    private final Contract contract;

    public Calls(Store store, Contract contract) { this.store = store; this.contract = contract; }

    private boolean restricted(Identity identity) {
        return identity.user() != null && "agent".equals(identity.user().get("role")) && !Boolean.TRUE.equals(identity.user().get("is_supervisor"));
    }

    private Map<String, Object> visible(Identity identity, UUID id) {
        Map<String, Object> call = store.require("callsession", identity.tenant(), id);
        if (restricted(identity) && !Objects.toString(identity.userId(), "").equals(Objects.toString(call.get("human_agent_id"), "")))
            throw new ApiError(403, "call is not assigned to this agent");
        return call;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request,
            @RequestParam(name="status", required=false) String status,
            @RequestParam(required=false) Long campaign_id,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="50") int size) {
        Identity identity = Identity.from(request); int offset = Contract.page(page, size);
        List<Object> args = new ArrayList<>(); args.add(identity.tenant()); String where = "WHERE tenant_id=?";
        if (restricted(identity)) { where += " AND human_agent_id=?"; args.add(identity.userId()); }
        if (campaign_id != null) { where += " AND campaign_id=?"; args.add(campaign_id); }
        if (status != null && !status.isEmpty()) {
            try { args.add(store.encode("callsession", "status", status.toLowerCase(Locale.ROOT))); }
            catch (ApiError e) { throw new ApiError(400, "invalid status filter"); }
            where += " AND status=?";
        }
        args.add(size); args.add(offset);
        return store.list("callsession", where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", args.toArray()).stream()
                .map(row -> contract.output("CallSessionOut", row)).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id, HttpServletRequest request) {
        return contract.output("CallSessionOut", visible(Identity.from(request), id));
    }

    @GetMapping("/{id}/events")
    public List<Map<String, Object>> events(@PathVariable UUID id, HttpServletRequest request,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="50") int size) {
        visible(Identity.from(request), id);
        return store.list("callevent", "WHERE call_session_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?", store.encode("callevent", "call_session_id", id), size, Contract.page(page, size))
                .stream().map(row -> contract.output("CallEventOut", row)).collect(Collectors.toList());
    }

    @GetMapping("/{id}/webhook-events")
    public List<Map<String, Object>> webhookEvents(@PathVariable UUID id, HttpServletRequest request,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="50") int size) {
        visible(Identity.from(request), id);
        return store.list("webhookeventingest", "WHERE call_session_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?", store.encode("webhookeventingest", "call_session_id", id), size, Contract.page(page, size))
                .stream().map(row -> contract.output("WebhookEventIngestOut", row)).collect(Collectors.toList());
    }

    @GetMapping("/{id}/webhook-stats")
    public Map<String, Object> webhookStats(@PathVariable UUID id, HttpServletRequest request) {
        visible(Identity.from(request), id);
        Object boundId = store.encode("webhookeventingest", "call_session_id", id);
        List<Map<String, Object>> groups = store.jdbc().queryForList("SELECT source,event_type,COUNT(*) AS count FROM webhookeventingest WHERE call_session_id=? GROUP BY source,event_type ORDER BY source,event_type", boundId);
        Long duplicates = store.jdbc().queryForObject("SELECT COALESCE(SUM(CASE WHEN repeat_count IS NULL OR repeat_count=0 THEN 0 ELSE repeat_count-1 END),0) FROM webhookeventingest WHERE call_session_id=?", Long.class, boundId);
        long total = store.count("webhookeventingest", "WHERE call_session_id=?", boundId);
        return Values.map("total", total, "unique", total, "duplicate_estimate", duplicates == null ? 0 : duplicates, "buckets", groups);
    }
}
