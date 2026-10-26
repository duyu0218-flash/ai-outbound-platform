package com.duozhi.outbound.call.service;

import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.common.web.Contract;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.operations.service.OperationsAccess;
import com.duozhi.outbound.voiceai.dao.VoiceAiBenchmarkMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/calls")
public class CallEvidence {
    @Autowired
    private VoiceAiBenchmarkMapper voiceAi;
    private final Store store;
    private final Contract contract;
    private final OperationsAccess operations;

    public CallEvidence(Store store, Contract contract, OperationsAccess operations) {
        this.store = store;
        this.contract = contract;
        this.operations = operations;
    }

    private long visible(HttpServletRequest request, UUID id) {
        Identity identity = Identity.from(request);
        Map<String, Object> call = store.require("callsession", identity.tenant(), id);
        operations.call(identity, id);
        return identity.tenant();
    }

    private List<Map<String, Object>> rows(
            String table, String type, long tenant, UUID id, String extra, String order) {
        return store
                .query(
                        table,
                        "callEvidence",
                        Values.map("tenantId", tenant, "callId", id, "finalOnly", !extra.isEmpty()))
                .stream()
                .map(row -> contract.output(type, row))
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}/speech-turns")
    public List<Map<String, Object>> speech(
            @PathVariable
            UUID id,
            @RequestParam(defaultValue = "false")
            boolean final_only,
            HttpServletRequest request) {
        return rows(
                "speechturn",
                "SpeechTurnOut",
                visible(request, id),
                id,
                final_only ? " AND is_final=TRUE" : "",
                "created_at ASC,id ASC");
    }

    @GetMapping("/{id}/metrics")
    public List<Map<String, Object>> metrics(@PathVariable
    UUID id, HttpServletRequest request) {
        return rows("callmetric", "CallMetricOut", visible(request, id), id, "", "created_at ASC");
    }

    @GetMapping("/{id}/recordings")
    public List<Map<String, Object>> recordings(@PathVariable
    UUID id, HttpServletRequest request) {
        return rows(
                "recordingasset",
                "RecordingAssetOut",
                visible(request, id),
                id,
                "",
                "created_at DESC");
    }

    @GetMapping("/{id}/voice-ai")
    public Object voiceAi(@PathVariable
    UUID id, HttpServletRequest request) {
        long tenant = visible(request, id);
        var attempts = new ArrayList<Map<String, Object>>();
        for (var row : voiceAi.callProfiles(tenant, id.toString())) {
            var policy =
                    Support.object(
                            row.get("policy_json").toString().getBytes(StandardCharsets.UTF_8));
            var profile = Support.map(Support.map(policy.get("_ai")).get("_voice_profile"));
            var item = new LinkedHashMap<String, Object>();
            item.put(
                    "realtime_playback_mode",
                    Support.map(policy.get("_ai"))
                            .getOrDefault("realtime_playback_mode", "reviewed"));
            item.put("attempt", row.get("attempt"));
            for (String key :
                    List.of(
                            "id",
                            "name",
                            "route",
                            "revision",
                            "asr_model",
                            "asr_resource",
                            "llm_model",
                            "tts_model",
                            "voice",
                            "realtime_model",
                            "realtime_voice"))
                if (profile.containsKey(key)) item.put(key, profile.get(key));
            if (profile.isEmpty()) {
                item.put("id", "legacy");
                item.put("name", "原有链路");
            }
            item.put(
                    "script_sha",
                    Support.hex(
                            Support.sha(
                                    Support.str(policy, "_script", "")
                                            .getBytes(StandardCharsets.UTF_8))));
            attempts.add(item);
        }
        return attempts;
    }

    @GetMapping("/{id}/realtime")
    public Object realtime(@PathVariable
    UUID id, HttpServletRequest request) {
        long tenant = visible(request, id);
        Map<String, Object> row =
                store.one(
                        "realtimesession",
                        "sourceCallEvidence47_1",
                        Store.queryParameters(
                                0, tenant, store.encode("realtimesession", "call_session_id", id)));
        return row == null ? NullNode.getInstance() : contract.output("RealtimeSessionOut", row);
    }
}
