package com.duozhi.outbound.dial.dao;

import com.duozhi.outbound.call.service.CallLifecycle;
import com.duozhi.outbound.capacity.service.CapacityCommitEvidence;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.dial.domain.DialBatchScope;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** One immutable business identity survives terminal status and later attempts. */
@Service
public class DialCommandJournal {
    @org.springframework.beans.factory.annotation.Autowired private Config config;
    @org.springframework.beans.factory.annotation.Autowired private CapacityCommitEvidence timing;
    private final JdbcTemplate jdbc;
    private final JsonSupport json;

    public DialCommandJournal(JdbcTemplate jdbc, JsonSupport json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public int protocol(Map<String, Object> call) {
        if (call.get("_dial_protocol") instanceof Number number) return number.intValue();
        var scope = DialBatchScope.current();
        if (scope != null && scope.journalPrepared) {
            var saved = scope.commands.get(call.get("id") + ":" + call.get("attempts"));
            return saved == null
                    ? config.number("JAVA_DIAL_AUTH_PROTOCOL", 1)
                    : JsonSupport.integer(((Map<?, ?>) saved.get("call")).get("_dial_protocol"), 1);
        }
        var rows =
                jdbc.queryForList(
                        "SELECT protocol_version FROM dial_command_journal WHERE tenant_id=? AND"
                                + " call_id=? AND attempt=?",
                        call.get("tenant_id"),
                        call.get("id"),
                        call.get("attempts"));
        return rows.isEmpty()
                ? config.number("JAVA_DIAL_AUTH_PROTOCOL", 1)
                : ((Number) rows.get(0).get("protocol_version")).intValue();
    }

    public Map<String, Object> frozen(long tenant, Object callId, int attempt) {
        var scope = DialBatchScope.current();
        if (scope != null && scope.journalPrepared)
            return scope.commands.get(callId + ":" + attempt);
        var rows =
                jdbc.queryForList(
                        "SELECT frozen_payload FROM dial_command_journal WHERE tenant_id=? AND"
                                + " call_id=? AND attempt=?",
                        tenant,
                        callId,
                        attempt);
        return rows.isEmpty() ? null : json.object(rows.get(0).get("frozen_payload").toString());
    }

    void preload(DialBatchScope scope, long tenant, List<String> ids) {
        if (ids.isEmpty()) return;
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.addAll(ids);
        var rows =
                jdbc.queryForList(
                        "SELECT call_id,attempt,frozen_payload FROM dial_command_journal WHERE"
                                + " tenant_id=? AND call_id IN ("
                                + String.join(",", Collections.nCopies(ids.size(), "?"))
                                + ")",
                        args.toArray());
        for (var row : rows)
            scope.commands.put(
                    row.get("call_id") + ":" + row.get("attempt"),
                    json.object(row.get("frozen_payload").toString()));
        scope.journalPrepared = true;
    }

    public void flush(DialBatchScope scope) {
        if (!scope.pendingCommands.isEmpty())
            for (int changed :
                    jdbc.batchUpdate(
                            "INSERT INTO"
                                    + " dial_command_journal(tenant_id,call_id,attempt,semantic_digest,frozen_payload,protocol_version)"
                                    + " VALUES(?,?,?,?,?,?)",
                            scope.pendingCommands))
                if (changed == 0 || changed == java.sql.Statement.EXECUTE_FAILED)
                    throw new IllegalStateException("command identity batch was not persisted");
        scope.pendingCommands.clear();
        if (!scope.readmissions.isEmpty()) jdbc.batchUpdate(readmissionSql(), scope.readmissions);
        scope.readmissions.clear();
    }

    private String readmissionSql() {
        return "UPDATE dial_command_journal j SET"
                + " state='PREPARED',recovery_at=UTC_TIMESTAMP(3),manual_hold_at=NULL WHERE"
                + " tenant_id=? AND call_id=? AND attempt=? AND state='REJECTED' AND"
                + " EXISTS(SELECT 1 FROM dial_reservation r WHERE r.tenant_id=j.tenant_id AND"
                + " r.call_id=j.call_id AND r.attempt=j.attempt AND r.state='RESERVED' AND"
                + " r.authorization_state='UNINSTALLED')";
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> metadata(Map<String, Object> call) {
        var scope = DialBatchScope.current();
        if (scope != null && scope.journalPrepared) {
            var command = scope.commands.get(call.get("id") + ":" + call.get("attempts"));
            return command == null ? null : (Map<String, Object>) command.get("metadata");
        }
        var rows =
                jdbc.queryForList(
                        "SELECT frozen_payload FROM dial_command_journal WHERE tenant_id=? AND"
                                + " call_id=? AND attempt=?",
                        call.get("tenant_id"),
                        call.get("id"),
                        call.get("attempts"));
        return rows.isEmpty()
                ? null
                : (Map<String, Object>)
                        json.object(rows.get(0).get("frozen_payload").toString()).get("metadata");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> freeze(Map<String, Object> call, Map<String, Object> metadata) {
        int protocol = config.number("JAVA_DIAL_AUTH_PROTOCOL", 1);
        if (protocol < 1 || protocol > 2) throw new ApiError(503, "unsupported dial protocol");
        call.put("_dial_protocol", protocol);
        var saved = new LinkedHashMap<String, Object>();
        for (String key :
                List.of(
                        "id",
                        "tenant_id",
                        "phone",
                        "attempts",
                        "telephony_line_id",
                        "gateway_node_id",
                        "gateway_endpoint",
                        "voice_ai_pipeline",
                        "_dial_protocol",
                        "_dial_route")) saved.put(key, call.get(key));
        var frozen = JsonSupport.map("call", saved, "metadata", metadata);
        String encoded = json.canonical(frozen, false), digest = JsonSupport.sha(encoded);
        var scope = DialBatchScope.current();
        if (scope != null && scope.journalPrepared) {
            String key = call.get("id") + ":" + call.get("attempts");
            var original = scope.commands.get(key);
            if (original == null) {
                scope.commands.put(key, frozen);
                scope.pendingCommands.add(
                        new Object[] {
                            call.get("tenant_id"),
                            call.get("id"),
                            call.get("attempts"),
                            digest,
                            encoded,
                            protocol
                        });
                return frozen;
            }
            var originalCall = (Map<String, Object>) original.get("call");
            validateRoute(call, originalCall);
            call.put("_dial_protocol", JsonSupport.integer(originalCall.get("_dial_protocol"), 1));
            scope.readmissions.add(
                    new Object[] {call.get("tenant_id"), call.get("id"), call.get("attempts")});
            return original;
        }
        jdbc.update(
                "INSERT IGNORE INTO"
                        + " dial_command_journal(tenant_id,call_id,attempt,semantic_digest,frozen_payload,protocol_version)"
                        + " VALUES(?,?,?,?,?,?)",
                call.get("tenant_id"),
                call.get("id"),
                call.get("attempts"),
                digest,
                encoded,
                protocol);
        var row =
                jdbc.queryForMap(
                        "SELECT frozen_payload,protocol_version FROM dial_command_journal WHERE"
                                + " tenant_id=? AND call_id=? AND attempt=? FOR UPDATE",
                        call.get("tenant_id"),
                        call.get("id"),
                        call.get("attempts"));
        var original = json.object(row.get("frozen_payload").toString());
        var originalCall = (Map<String, Object>) original.get("call");
        validateRoute(call, originalCall);
        jdbc.update(readmissionSql(), call.get("tenant_id"), call.get("id"), call.get("attempts"));
        call.put("_dial_protocol", ((Number) row.get("protocol_version")).intValue());
        return original;
    }

    private void validateRoute(Map<String, Object> call, Map<String, Object> originalCall) {
        for (String key :
                List.of("phone", "telephony_line_id", "gateway_node_id", "gateway_endpoint"))
            if (!Objects.equals(
                    Objects.toString(originalCall.get(key), ""),
                    Objects.toString(call.get(key), "")))
                throw new ApiError(
                        409, "frozen dial semantics require the original route and phone");
        if (originalCall.get("_dial_route") != null
                && !Objects.equals(originalCall.get("_dial_route"), call.get("_dial_route")))
            throw new ApiError(
                    409,
                    "frozen provider endpoint and credential reference cannot change during"
                            + " readmission");
    }

    @SuppressWarnings("unchecked")
    public void acceptedBatch(List<Map<String, Object>> entries) {
        if (entries.isEmpty()) return;
        var args = new ArrayList<Object>();
        var selects = new ArrayList<String>();
        for (var entry : entries) {
            var call = (Map<String, Object>) entry.get("call");
            var response = (Map<String, Object>) entry.get("response");
            selects.add(
                    "SELECT ? AS tenant_id,? AS call_id,? AS attempt,? AS provider_uuid,? AS"
                            + " result");
            Collections.addAll(
                    args,
                    call.get("tenant_id"),
                    call.get("id"),
                    call.get("attempts"),
                    response.get("provider_call_id"),
                    json.canonical(response, false));
        }
        int changed =
                jdbc.update(
                        "UPDATE dial_command_journal j JOIN ("
                                + String.join(" UNION ALL ", selects)
                                + ") r ON j.tenant_id=r.tenant_id AND j.call_id=r.call_id AND"
                                + " j.attempt=r.attempt SET"
                                + " j.state='ACCEPTED',j.provider_uuid=r.provider_uuid,j.accepted_result=r.result,j.accepted_at=COALESCE(j.accepted_at,UTC_TIMESTAMP(3))"
                                + " WHERE j.state<>'REJECTED' AND (j.provider_uuid IS NULL OR"
                                + " j.provider_uuid=r.provider_uuid)",
                        args.toArray());
        if (changed != entries.size())
            throw new ApiError(409, "dial confirmation batch conflicts with original identities");
        timing.recordBatch(
                "ack",
                entries.stream()
                        .map(
                                entry -> {
                                    var call = (Map<String, Object>) entry.get("call");
                                    return new Object[] {
                                        call.get("tenant_id"), call.get("id"), call.get("attempts")
                                    };
                                })
                        .toList());
    }

    public void accepted(Map<String, Object> call, Map<String, Object> response) {
        int changed =
                jdbc.update(
                        "UPDATE dial_command_journal SET"
                                + " state='ACCEPTED',provider_uuid=?,accepted_result=?,accepted_at=COALESCE(accepted_at,UTC_TIMESTAMP(3))"
                                + " WHERE tenant_id=? AND call_id=? AND attempt=? AND state<>'REJECTED'"
                                + " AND (provider_uuid IS NULL OR provider_uuid=?)",
                        response.get("provider_call_id"),
                        json.canonical(response, false),
                        call.get("tenant_id"),
                        call.get("id"),
                        call.get("attempts"),
                        response.get("provider_call_id"));
        if (changed != 1)
            throw new ApiError(
                    409, "dial confirmation does not match its original command identity");
        timing.record(
                "ack",
                CallLifecycle.tenant(call),
                call.get("id"),
                CallLifecycle.number(call, "attempts"));
    }

    public Map<String, Object> confirmation(Map<String, Object> call) {
        var rows =
                jdbc.queryForList(
                        "SELECT accepted_result FROM dial_command_journal WHERE tenant_id=? AND"
                                + " call_id=? AND attempt=?",
                        call.get("tenant_id"),
                        call.get("id"),
                        call.get("attempts"));
        return rows.size() == 1 && rows.get(0).get("accepted_result") != null
                ? json.object(rows.get(0).get("accepted_result").toString())
                : Map.of("result", "pending_reconciliation");
    }
}
