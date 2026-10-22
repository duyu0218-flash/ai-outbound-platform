package com.duozhi.outbound.dial.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import java.util.*;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One persistent control domain. Lifecycle consumers remain enabled while draining. */
@Service
public class DialDrainControl {
    @Autowired
    private Config config;
    private final JdbcTemplate jdbc;
    private final Transactions tx;

    public DialDrainControl(JdbcTemplate jdbc, Transactions tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public boolean acceptingInTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("admission gate requires a transaction");
        return "RUNNING"
                .equals(
                        jdbc.queryForObject(
                                "SELECT phase FROM dial_admission_control WHERE id=1 FOR SHARE",
                                String.class));
    }

    public boolean sending(Map<String, Object> call) {
        var control = jdbc.queryForMap("SELECT phase,epoch FROM dial_admission_control WHERE id=1");
        String phase = control.get("phase").toString();
        if (phase.equals("RUNNING")) return true;
        if (!phase.equals("ADMISSION_CLOSED")) return false;
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM dial_drain_member WHERE epoch=? AND tenant_id=? AND"
                                + " call_id=? AND attempt=?",
                        Long.class,
                        control.get("epoch"),
                        call.get("tenant_id"),
                        call.get("id").toString().replace("-", ""),
                        call.get("attempts"))
                == 1;
    }

    /**
     * Uses the same existing platform mutex as legacy admissions before taking the persistent
     * barrier, so the captured cohort includes in-progress claims.
     */
    public Map<String, Object> begin() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("drain capture requires a fresh transaction");
        return tx.serialized(
                () -> {
                    var row =
                            jdbc.queryForMap(
                                    "SELECT phase,epoch FROM dial_admission_control WHERE id=1 FOR"
                                            + " UPDATE");
                    if (!row.get("phase").equals("RUNNING"))
                        throw new ApiError(409, "drain already in progress");
                    long epoch = Math.addExact(((Number) row.get("epoch")).longValue(), 1);
                    // INSERT...SELECT under RR takes source-row locks. Voice fact
                    // writers already hold their row before the freeze trigger reads
                    // this barrier, so locking those rows here reverses that order.
                    // Plain consistent reads share one RR snapshot under the exclusive
                    // barrier and platform admission lock; insert only captured values.
                    var members = new ArrayList<Object[]>();
                    for (var call :
                            jdbc.queryForList(
                                    "SELECT tenant_id,REPLACE(id,'-','') AS call_id,attempts AS"
                                            + " attempt FROM callsession WHERE status IN"
                                            + " ('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN')"))
                        members.add(
                                new Object[] {
                                    epoch,
                                    call.get("tenant_id"),
                                    call.get("call_id"),
                                    call.get("attempt")
                                });
                    for (var call :
                            jdbc.queryForList(
                                    "SELECT tenant AS tenant_id,REPLACE(call_id,'-','') AS"
                                            + " call_id,attempt FROM voice_attempts WHERE"
                                            + " state!='ended' AND REPLACE(call_id,'-','') REGEXP"
                                            + " '^[0-9a-fA-F]{32}$'"))
                        members.add(
                                new Object[] {
                                    epoch,
                                    call.get("tenant_id"),
                                    call.get("call_id"),
                                    call.get("attempt")
                                });
                    jdbc.batchUpdate(
                            "INSERT IGNORE INTO dial_drain_member(epoch,tenant_id,call_id,attempt)"
                                    + " VALUES(?,?,?,?)",
                            members,
                            100,
                            (statement, member) -> {
                                for (int i = 0; i < member.length; i++)
                                    statement.setObject(i + 1, member[i]);
                            });
                    jdbc.update(
                            "UPDATE dial_admission_control SET"
                                    + " phase='ADMISSION_CLOSED',epoch=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(3)"
                                    + " WHERE id=1",
                            epoch);
                    return snapshot();
                });
    }

    public Map<String, Object> closeSending() {
        return tx.serialized(
                () -> {
                    String phase =
                            jdbc.queryForObject(
                                    "SELECT phase FROM dial_admission_control WHERE id=1 FOR"
                                            + " UPDATE",
                                    String.class);
                    if (!"ADMISSION_CLOSED".equals(phase))
                        throw new ApiError(
                                409, "close admissions and process the captured cohort first");
                    long uncertain =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM voice_attempts WHERE state!='ended' AND"
                                            + " send_state NOT IN ('PBX_ACCEPTED','PBX_STARTED')",
                                    Long.class);
                    long unconfirmed =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM callsession c WHERE c.status IN"
                                            + " ('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN')"
                                            + " AND NOT EXISTS(SELECT 1 FROM callevent e WHERE"
                                            + " e.call_session_id=c.id AND"
                                            + " e.event_type='dial_command_accepted' AND"
                                            + " JSON_EXTRACT(e.payload,'$.attempt')=c.attempts) AND NOT"
                                            + " EXISTS(SELECT 1 FROM voice_attempts v WHERE"
                                            + " REPLACE(v.call_id,'-','')=REPLACE(c.id,'-','') AND"
                                            + " v.tenant=c.tenant_id AND v.attempt=c.attempts AND"
                                            + " v.send_state IN"
                                            + " ('PBX_ACCEPTED','PBX_STARTED','ENDED'))",
                                    Long.class);
                    if (uncertain != 0 || unconfirmed != 0)
                        throw new ApiError(
                                409,
                                "reserved, sending or unknown commands still need completion or"
                                        + " proven cancellation");
                    jdbc.update(
                            "UPDATE dial_admission_control SET"
                                    + " phase='SEND_CLOSED',revision=revision+1,updated_at=CURRENT_TIMESTAMP(3)"
                                    + " WHERE id=1");
                    return snapshot();
                });
    }

    /** A cancelled migration resumes the existing binary without discarding history. */
    public Map<String, Object> cancelDrain() {
        return tx.serialized(
                () -> {
                    jdbc.queryForMap(
                            "SELECT phase FROM dial_admission_control WHERE id=1 FOR UPDATE");
                    jdbc.update(
                            "UPDATE dial_admission_control SET"
                                    + " phase='RUNNING',revision=revision+1,updated_at=CURRENT_TIMESTAMP(3)"
                                    + " WHERE id=1");
                    return snapshot();
                });
    }

    public boolean sendingClosed() {
        return Set.of("SEND_CLOSED", "FROZEN")
                .contains(
                        jdbc.queryForObject(
                                "SELECT phase FROM dial_admission_control WHERE id=1",
                                String.class));
    }

    public boolean frozen() {
        return "FROZEN"
                .equals(
                        jdbc.queryForObject(
                                "SELECT phase FROM dial_admission_control WHERE id=1",
                                String.class));
    }

    public Map<String, Object> freezeMockFacts() {
        if (!"mock".equals(config.get("VOICE_GATEWAY_DRIVER", "mock")))
            throw new ApiError(
                    409,
                    "real PBX drain requires independent external evidence; synthetic freeze is"
                            + " forbidden");
        return tx.serialized(
                () -> {
                    if (!"SEND_CLOSED"
                            .equals(
                                    jdbc.queryForObject(
                                            "SELECT phase FROM dial_admission_control WHERE id=1"
                                                    + " FOR UPDATE",
                                            String.class)))
                        throw new ApiError(409, "close all sending before freezing writers");
                    var state = snapshot();
                    for (String key :
                            List.of(
                                    "business_active",
                                    "gateway_active",
                                    "pending_callbacks",
                                    "pending_inbox",
                                    "pending_lifecycle_tasks"))
                        if (((Number) state.get(key)).longValue() != 0)
                            throw new ApiError(409, "drain incomplete: " + key);
                    jdbc.update(
                            "UPDATE dial_admission_control SET phase='FROZEN',revision=revision+1"
                                    + " WHERE id=1");
                    return snapshot();
                });
    }

    public Map<String, Object> snapshot() {
        var result =
                new LinkedHashMap<>(
                        jdbc.queryForMap(
                                "SELECT phase,epoch,revision,updated_at FROM dial_admission_control"
                                        + " WHERE id=1"));
        result.put(
                "captured",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM dial_drain_member WHERE epoch=?",
                        Long.class,
                        result.get("epoch")));
        result.put(
                "business_active",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM callsession WHERE status IN"
                                + " ('DIALING','ANSWERED','IN_AI','WAITING_HUMAN','HANDOFF_TRANSFERRING','IN_HUMAN')",
                        Long.class));
        result.put(
                "gateway_active",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM voice_attempts WHERE state!='ended'", Long.class));
        result.put(
                "pending_callbacks",
                jdbc.queryForObject("SELECT COUNT(*) FROM voice_outbox", Long.class));
        result.put(
                "pending_inbox",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM callbackinbox WHERE state NOT IN ('done','ignored')",
                        Long.class));
        result.put(
                "pending_lifecycle_tasks",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM taskoutbox WHERE task_type!='dial_call' AND state IN"
                                + " ('PENDING','FAILED','PROCESSING','DEAD')",
                        Long.class));
        result.put("external_pbx_verified", false);
        result.put("certificate_issued", false);
        return result;
    }
}
