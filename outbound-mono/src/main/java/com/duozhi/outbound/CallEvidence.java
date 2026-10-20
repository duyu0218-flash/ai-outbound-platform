package com.duozhi.outbound;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/calls")
public class CallEvidence {
    private final Store store;
    private final Contract contract;
    public CallEvidence(Store store, Contract contract) { this.store = store; this.contract = contract; }

    private long visible(HttpServletRequest request, UUID id) {
        Identity identity = Identity.from(request);
        Map<String, Object> call = store.require("callsession", identity.tenant(), id);
        if (identity.user() != null && "agent".equals(identity.user().get("role")) && !Boolean.TRUE.equals(identity.user().get("is_supervisor"))
                && call.get("human_agent_id") != null && !call.get("human_agent_id").toString().equals(identity.userId().toString()))
            throw new ApiError(403, "call is assigned to another agent");
        return identity.tenant();
    }

    private List<Map<String, Object>> rows(String table, String type, long tenant, UUID id, String extra, String order) {
        return store.query(table,"callEvidence",Values.map("tenantId",tenant,"callId",id,"finalOnly",!extra.isEmpty())).stream().map(row -> contract.output(type, row)).collect(Collectors.toList());
    }

    @GetMapping("/{id}/speech-turns")
    public List<Map<String, Object>> speech(@PathVariable UUID id, @RequestParam(defaultValue="false") boolean final_only, HttpServletRequest request) {
        return rows("speechturn", "SpeechTurnOut", visible(request, id), id, final_only ? " AND is_final=TRUE" : "", "created_at ASC,id ASC");
    }

    @GetMapping("/{id}/metrics")
    public List<Map<String, Object>> metrics(@PathVariable UUID id, HttpServletRequest request) {
        return rows("callmetric", "CallMetricOut", visible(request, id), id, "", "created_at ASC");
    }

    @GetMapping("/{id}/recordings")
    public List<Map<String, Object>> recordings(@PathVariable UUID id, HttpServletRequest request) {
        return rows("recordingasset", "RecordingAssetOut", visible(request, id), id, "", "created_at DESC");
    }

    @GetMapping("/{id}/realtime")
    public Object realtime(@PathVariable UUID id, HttpServletRequest request) {
        long tenant = visible(request, id);
        Map<String, Object> row = store.one("realtimesession","sourceCallEvidence47_1",Store.queryParameters(0,tenant,store.encode("realtimesession", "call_session_id", id)));
        return row == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : contract.output("RealtimeSessionOut", row);
    }
}
