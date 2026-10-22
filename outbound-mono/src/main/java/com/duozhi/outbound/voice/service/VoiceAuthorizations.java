package com.duozhi.outbound.voice.service;

import com.duozhi.outbound.integration.dao.LocalLedger;
import com.duozhi.outbound.integration.support.Support;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;

/**
 * Versioned immutable semantics and a separately signed mutable grant. An installation
 * acknowledgement always commits before a new owner can send.
 */
public final class VoiceAuthorizations {
    private final LocalLedger ledger;

    VoiceAuthorizations(LocalLedger ledger) {
        this.ledger = ledger;
    }

    public static int version(Map<String, Object> payload) {
        long version = Support.integer(payload, "protocol_version", 1);
        if (version != 1 && version != 2)
            throw new Support.Failure(422, "unsupported dial protocol");
        return (int) version;
    }

    public static String digest(Map<String, Object> payload) {
        if (version(payload) == 1) return Support.hex(Support.sha(Support.canonical(payload)));
        var semantics = new LinkedHashMap<>(payload);
        semantics.remove("authorization");
        return Support.hex(Support.sha(Support.canonical(semantics)));
    }

    private static Map<String, Object> grant(Map<String, Object> payload) {
        return grant(payload, true);
    }

    private static Map<String, Object> grant(Map<String, Object> payload, boolean checkExpiry) {
        if (version(payload) != 2)
            throw new Support.Failure(422, "authorization installation requires dial protocol 2");
        var auth = Support.map(payload.get("authorization"));
        var metadata = Support.map(payload.get("metadata"));
        long tenant = Support.integer(metadata, "tenant_id", 0),
                attempt = Support.integer(metadata, "attempt", 0);
        if (tenant <= 0 || attempt <= 0 || Support.integer(payload, "tenant_id", 0) != tenant)
            throw new Support.Failure(403, "dial grant has different tenant or attempt");
        String id = Support.required(auth, "reservation_id", 36);
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new Support.Failure(422, "invalid reservation identity");
        }
        if (Support.integer(auth, "epoch", 0) <= 0
                || Support.integer(auth, "fence", 0) <= 0
                || !Support.required(auth, "owner", 128).matches("[A-Za-z0-9_.:-]{1,128}"))
            throw new Support.Failure(422, "invalid authorization fence");
        long now = System.currentTimeMillis() / 1000,
                expires = Support.integer(auth, "expires_at", 0);
        if (checkExpiry && (expires < now || expires > now + 120))
            throw new Support.Failure(401, "dial grant is expired or too far in the future");
        if (!digest(payload).equals(auth.get("semantic_digest")))
            throw new Support.Failure(409, "dial grant does not bind the immutable semantics");
        return auth;
    }

    Map<String, Object> reject(Map<String, Object> payload, String secret) {
        var auth = grant(payload, false);
        var metadata = Support.map(payload.get("metadata"));
        long tenant = Support.integer(metadata, "tenant_id", 0),
                attempt = Support.integer(metadata, "attempt", 0);
        String call = Support.required(payload, "call_id", 128);
        return ledger.transaction(
                db -> {
                    var rows =
                            LocalLedger.query(
                                    db,
                                    "SELECT * FROM voice_dial_authorization WHERE tenant_id=? AND"
                                            + " call_id=? AND attempt=? FOR UPDATE",
                                    tenant,
                                    call,
                                    attempt);
                    if (rows.size() != 1)
                        throw new Support.Failure(409, "no installed authorization to revoke");
                    var current = rows.get(0);
                    for (String field : List.of("owner", "semantic_digest"))
                        if (!Objects.equals(current.get(field), auth.get(field)))
                            throw new Support.Failure(409, "stale cancellation owner");
                    if (!Objects.equals(current.get("registration_id"), auth.get("reservation_id"))
                            || ((Number) current.get("epoch")).longValue()
                                    != Support.integer(auth, "epoch", 0)
                            || ((Number) current.get("fence")).longValue()
                                    != Support.integer(auth, "fence", 0))
                        throw new Support.Failure(409, "stale cancellation fence");
                    if (current.get("state").equals("REJECTED")) {
                        var history =
                                LocalLedger.query(
                                        db,
                                        "SELECT evidence FROM voice_dial_authorization_history"
                                                + " WHERE tenant_id=? AND call_id=? AND attempt=? AND"
                                                + " registration_id=? AND fence=? ORDER BY id DESC"
                                                + " LIMIT 1",
                                        tenant,
                                        call,
                                        attempt,
                                        auth.get("reservation_id"),
                                        auth.get("fence"));
                        if (history.isEmpty())
                            throw new Support.Failure(503, "rejection evidence is missing");
                        return Support.object(
                                history.get(0)
                                        .get("evidence")
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8));
                    }
                    var attempts =
                            LocalLedger.query(
                                    db,
                                    "SELECT * FROM attempts WHERE tenant=? AND call_id=? AND"
                                            + " attempt=? FOR UPDATE",
                                    tenant,
                                    call,
                                    attempt);
                    String uuid = "";
                    if (!attempts.isEmpty()) {
                        var row = attempts.get(0);
                        uuid = row.get("uuid").toString();
                        if (!Objects.equals(row.get("registration_id"), auth.get("reservation_id")))
                            throw new Support.Failure(
                                    409, "cancellation identifies another registration");
                        if (row.get("result") != null)
                            return Support.object(
                                    row.get("result").toString().getBytes(StandardCharsets.UTF_8));
                        if (!row.get("send_state").equals("REGISTERED")
                                || row.get("state").equals("ended"))
                            return Map.of(
                                    "result", "pending_reconciliation", "provider_call_id", uuid);
                        if (LocalLedger.update(
                                        db,
                                        "UPDATE attempts SET"
                                                + " state='ended',send_state='REJECTED',cost=0,ended=?"
                                                + " WHERE uuid=? AND send_state='REGISTERED' AND result"
                                                + " IS NULL",
                                        LocalLedger.now(),
                                        uuid)
                                != 1) throw new Support.Failure(409, "dispatch won cancellation");
                        LocalLedger.execute(
                                db,
                                "UPDATE voice_permits SET state='closed' WHERE call_id=? AND"
                                        + " attempt=? AND kind='reserve'",
                                call,
                                attempt);
                    }
                    String proofId = UUID.randomUUID().toString();
                    var proof = new LinkedHashMap<String, Object>();
                    proof.put("result", "rejected_before_dispatch");
                    proof.put("proof_id", proofId);
                    proof.put("tenant_id", tenant);
                    proof.put("call_id", call);
                    proof.put("attempt", attempt);
                    proof.put("provider_call_id", uuid);
                    for (String field :
                            List.of("reservation_id", "epoch", "owner", "fence", "semantic_digest"))
                        proof.put(field, auth.get(field));
                    proof.put("reason", "cancelled_before_dispatch");
                    proof.put("proof_signature", Support.hmac(secret, Support.canonical(proof)));
                    LocalLedger.execute(
                            db,
                            "UPDATE voice_dial_authorization SET"
                                    + " state='REJECTED',proof_id=?,reason='cancelled_before_dispatch',updated_at=UTC_TIMESTAMP(3)"
                                    + " WHERE tenant_id=? AND call_id=? AND attempt=?",
                            proofId,
                            tenant,
                            call,
                            attempt);
                    LocalLedger.execute(
                            db,
                            "INSERT INTO"
                                    + " voice_dial_authorization_history(tenant_id,call_id,attempt,registration_id,fence,evidence)"
                                    + " VALUES(?,?,?,?,?,?)",
                            tenant,
                            call,
                            attempt,
                            auth.get("reservation_id"),
                            auth.get("fence"),
                            new String(Support.canonical(proof), StandardCharsets.UTF_8));
                    return proof;
                });
    }

    Map<String, Object> install(Map<String, Object> payload) {
        var auth = grant(payload);
        var metadata = Support.map(payload.get("metadata"));
        long tenant = Support.integer(metadata, "tenant_id", 0),
                attempt = Support.integer(metadata, "attempt", 0);
        String call = Support.required(payload, "call_id", 128);
        return ledger.transaction(
                db -> {
                    if (!DialSendGate.permitted(db, call, tenant, attempt))
                        throw new Support.Failure(503, "dial authorization barrier is closed");
                    var projection = VoiceAdmissionBatchScope.current();
                    var control =
                            projection == null
                                    ? LocalLedger.query(
                                                    db,
                                                    "SELECT phase,epoch FROM dial_admission_control"
                                                            + " WHERE id=1 FOR SHARE")
                                            .get(0)
                                    : projection.control(db);
                    long epoch = Support.integer(auth, "epoch", 0),
                            current = ((Number) control.get("epoch")).longValue();
                    if (epoch > current
                            || control.get("phase").equals("RUNNING") && epoch != current)
                        throw new Support.Failure(409, "dial epoch has not been installed");
                    var cached =
                            projection != null && projection.preloaded()
                                    ? projection.authorization(db, tenant, call, attempt)
                                    : null;
                    var rows =
                            projection != null && projection.preloaded()
                                    ? (cached == null
                                            ? List.<Map<String, Object>>of()
                                            : List.of(cached))
                                    : LocalLedger.query(
                                            db,
                                            "SELECT * FROM voice_dial_authorization WHERE"
                                                    + " tenant_id=? AND call_id=? AND attempt=? FOR"
                                                    + " UPDATE",
                                            tenant,
                                            call,
                                            attempt);
                    if (!rows.isEmpty()) {
                        // A repeated K in the same cohort must observe its earlier INSERT before
                        // applying a refresh or a higher owner fence.
                        if (projection != null) projection.flushAuthorizations(db);
                        var old = rows.get(0);
                        long fence = Support.integer(auth, "fence", 0),
                                oldFence = ((Number) old.get("fence")).longValue();
                        if (!old.get("semantic_digest").equals(auth.get("semantic_digest")))
                            throw new Support.Failure(
                                    409, "dial identity has different immutable semantics");
                        boolean same =
                                old.get("registration_id").equals(auth.get("reservation_id"))
                                        && old.get("owner").equals(auth.get("owner"))
                                        && oldFence == fence
                                        && ((Number) old.get("epoch")).longValue() == epoch;
                        if (old.get("state").equals("REJECTED")
                                && old.get("registration_id").equals(auth.get("reservation_id")))
                            throw new Support.Failure(
                                    409, "rejected registration cannot be installed again");
                        if (!same && fence <= oldFence)
                            throw new Support.Failure(409, "stale dial owner fence");
                        if (!same
                                && !old.get("registration_id").equals(auth.get("reservation_id"))
                                && !old.get("state").equals("REJECTED"))
                            throw new Support.Failure(
                                    409, "previous reservation has no durable rejection");
                        if (!same) {
                            var snapshot = new LinkedHashMap<String, Object>();
                            for (String key :
                                    List.of(
                                            "registration_id",
                                            "epoch",
                                            "owner",
                                            "fence",
                                            "expires_at",
                                            "semantic_digest",
                                            "state")) snapshot.put(key, old.get(key));
                            LocalLedger.execute(
                                    db,
                                    "INSERT INTO"
                                            + " voice_dial_authorization_history(tenant_id,call_id,attempt,registration_id,fence,evidence)"
                                            + " VALUES(?,?,?,?,?,?)",
                                    tenant,
                                    call,
                                    attempt,
                                    old.get("registration_id"),
                                    old.get("fence"),
                                    new String(
                                            Support.canonical(snapshot), StandardCharsets.UTF_8));
                            LocalLedger.execute(
                                    db,
                                    "UPDATE voice_dial_authorization SET"
                                            + " registration_id=?,epoch=?,owner=?,fence=?,expires_at=?,state='INSTALLED',proof_id=NULL,reason=NULL,updated_at=UTC_TIMESTAMP(3)"
                                            + " WHERE tenant_id=? AND call_id=? AND attempt=?",
                                    auth.get("reservation_id"),
                                    epoch,
                                    auth.get("owner"),
                                    fence,
                                    auth.get("expires_at"),
                                    tenant,
                                    call,
                                    attempt);
                        } else
                            LocalLedger.execute(
                                    db,
                                    "UPDATE voice_dial_authorization SET expires_at=? WHERE"
                                            + " tenant_id=? AND call_id=? AND attempt=?",
                                    auth.get("expires_at"),
                                    tenant,
                                    call,
                                    attempt);
                    } else {
                        Object[] values = {
                            tenant,
                            call,
                            attempt,
                            auth.get("reservation_id"),
                            epoch,
                            auth.get("owner"),
                            auth.get("fence"),
                            auth.get("expires_at"),
                            auth.get("semantic_digest")
                        };
                        if (projection != null && projection.preloaded())
                            projection.newAuthorization(db, values);
                        else
                            LocalLedger.execute(
                                    db,
                                    "INSERT INTO"
                                            + " voice_dial_authorization(tenant_id,call_id,attempt,registration_id,epoch,owner,fence,expires_at,semantic_digest,state)"
                                            + " VALUES(?,?,?,?,?,?,?,?,?,'INSTALLED')",
                                    values);
                    }
                    if (projection != null && projection.preloaded()) {
                        var installed = new LinkedHashMap<>(auth);
                        installed.put("registration_id", auth.get("reservation_id"));
                        installed.put("tenant_id", tenant);
                        installed.put("call_id", call);
                        installed.put("attempt", attempt);
                        installed.put("state", "INSTALLED");
                        projection.authorized(installed);
                    }
                    return Map.of(
                            "result",
                            "authorization_installed",
                            "reservation_id",
                            auth.get("reservation_id"),
                            "epoch",
                            epoch,
                            "fence",
                            auth.get("fence"),
                            "owner",
                            auth.get("owner"),
                            "semantic_digest",
                            auth.get("semantic_digest"));
                });
    }

    public static Map<String, Object> requireInstalled(Connection db, Map<String, Object> payload)
            throws Exception {
        var auth = grant(payload);
        var metadata = Support.map(payload.get("metadata"));
        if (!DialSendGate.permitted(
                db,
                payload.get("call_id").toString(),
                Support.integer(metadata, "tenant_id", 0),
                Support.integer(metadata, "attempt", 0)))
            throw new Support.Failure(503, "dial send barrier is closed");
        var projection = VoiceAdmissionBatchScope.current();
        var control =
                projection == null
                        ? LocalLedger.query(
                                        db,
                                        "SELECT phase,epoch FROM dial_admission_control WHERE id=1"
                                                + " FOR SHARE")
                                .get(0)
                        : projection.control(db);
        long currentEpoch = ((Number) control.get("epoch")).longValue(),
                grantEpoch = Support.integer(auth, "epoch", 0);
        if (grantEpoch > currentEpoch
                || control.get("phase").equals("RUNNING") && grantEpoch != currentEpoch)
            throw new Support.Failure(409, "dial epoch is stale");
        var cached =
                projection != null && projection.preloaded()
                        ? projection.authorization(
                                db,
                                Support.integer(metadata, "tenant_id", 0),
                                payload.get("call_id").toString(),
                                Support.integer(metadata, "attempt", 0))
                        : null;
        var rows =
                projection != null && projection.preloaded()
                        ? (cached == null ? List.<Map<String, Object>>of() : List.of(cached))
                        : LocalLedger.query(
                                db,
                                "SELECT * FROM voice_dial_authorization WHERE tenant_id=? AND"
                                        + " call_id=? AND attempt=? FOR UPDATE",
                                metadata.get("tenant_id"),
                                payload.get("call_id"),
                                metadata.get("attempt"));
        if (rows.size() != 1)
            throw new Support.Failure(409, "dial authorization has not been installed");
        var row = rows.get(0);
        for (String field : List.of("owner", "semantic_digest"))
            if (!row.get(field).equals(auth.get(field)))
                throw new Support.Failure(409, "stale dial authorization");
        if (!row.get("registration_id").equals(auth.get("reservation_id"))
                || ((Number) row.get("epoch")).longValue() != Support.integer(auth, "epoch", 0)
                || ((Number) row.get("fence")).longValue() != Support.integer(auth, "fence", 0)
                || ((Number) row.get("expires_at")).longValue() < System.currentTimeMillis() / 1000)
            throw new Support.Failure(409, "stale dial authorization fence");
        if (!row.get("state").equals("INSTALLED"))
            throw new Support.Failure(409, "dial authorization was revoked or rejected");
        return auth;
    }
}
