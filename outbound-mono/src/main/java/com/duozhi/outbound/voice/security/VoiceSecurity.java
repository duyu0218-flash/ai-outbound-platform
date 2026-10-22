package com.duozhi.outbound.voice.security;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.dao.LocalLedger;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.voice.service.DialSendGate;
import com.duozhi.outbound.voice.service.VoiceAdmissionBatchScope;
import com.duozhi.outbound.voice.service.VoiceAuthorizations;
import com.duozhi.outbound.voice.service.VoiceQuota;
import com.duozhi.outbound.voice.service.VoiceScopeFacts;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;

public final class VoiceSecurity {
    private final Settings settings;
    final LocalLedger ledger;
    public final Map<String, Object> routes;

    public VoiceSecurity(Settings settings, LocalLedger ledger) throws Exception {
        this.settings = settings;
        this.ledger = ledger;
        String file = settings.get("VOICE_SECURITY_ROUTES_FILE", "");
        byte[] routeBytes;
        if (file.isEmpty())
            routeBytes =
                    settings.get("VOICE_SECURITY_ROUTES_JSON", "{}")
                            .getBytes(StandardCharsets.UTF_8);
        else {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path) || Files.size(path) > 1048576)
                throw new IllegalArgumentException("invalid route file");
            routeBytes = Files.readAllBytes(path);
        }
        routes = Support.object(routeBytes);
        for (var route : routes.entrySet()) {
            if (!route.getKey().matches("[1-9][0-9]*:[0-9]+"))
                throw new IllegalArgumentException("invalid route key");
            Map<String, Object> value = Support.map(route.getValue());
            if (!Support.required(value, "gateway", 128).matches("[A-Za-z0-9_.-]+")
                    || !Support.required(value, "caller_id", 32).matches("\\+?[0-9]{6,15}"))
                throw new IllegalArgumentException("invalid route gateway or caller ID");
            for (String field :
                    List.of(
                            "max_concurrent",
                            "cps",
                            "calls_per_day",
                            "hour_budget_minor",
                            "day_budget_minor",
                            "rate_minor_per_minute",
                            "max_duration_sec"))
                if (Support.integer(value, field, 0) < 1)
                    throw new IllegalArgumentException("invalid route limit: " + field);
            long multiplier = Support.integer(value, "billing_multiplier", 2);
            if (multiplier < 2
                    || multiplier > 100
                    || Support.integer(value, "max_duration_sec", 0) > 3600)
                throw new IllegalArgumentException("invalid billing or duration limit");
            for (String key : List.of("allowed_prefixes", "denied_prefixes")) {
                Object prefixes = value.getOrDefault(key, List.of());
                if (!(prefixes instanceof List<?> list)
                        || (key.equals("allowed_prefixes") && list.isEmpty())
                        || list.stream()
                                .anyMatch(
                                        p ->
                                                !(p instanceof String s)
                                                        || !s.matches("[1-9][0-9]{0,14}")))
                    throw new IllegalArgumentException("invalid destination prefixes");
            }
        }
        String driver = settings.get("VOICE_GATEWAY_DRIVER", "mock");
        if (!driver.equals("mock")) {
            if (!driver.equals("freeswitch_esl"))
                throw new IllegalArgumentException(
                        "PBX HTTP forwarding remains disabled without verified hard-stop contract");
            Set<String> credentials = new HashSet<>();
            for (String name :
                    List.of(
                            "SERVICE_TOKEN",
                            "VOICE_COMMAND_SECRET",
                            "VOICE_SECURITY_ADMIN_TOKEN",
                            "WEBHOOK_TOKEN",
                            "WEBHOOK_SECRET",
                            "FREESWITCH_ESL_PASSWORD")) {
                String value = settings.secret(name);
                if (value.length() < 32
                        || value.contains("\r")
                        || value.contains("\n")
                        || Support.asciiLower(value).matches(".*(change-me|replace-me|your-).*"))
                    throw new IllegalArgumentException(
                            "real dialing requires independent strong credentials: " + name);
                if (!credentials.add(value))
                    throw new IllegalArgumentException(
                            "voice security credentials must be distinct");
            }
            var base = Support.endpoint(settings.get("VOICE_CALLBACK_BASE_URL", ""));
            if (!Set.of("", "/").contains(base.getPath())
                    || base.getQuery() != null
                    || (!base.getScheme().equals("https")
                            && !settings.flag("VOICE_CALLBACK_ALLOW_PRIVATE_HTTP", false)))
                throw new IllegalArgumentException(
                        "callback requires a trusted origin and explicit internal HTTP approval");
            if (!settings.get("FREESWITCH_DIALPLAN_CONTEXT", "agent-restricted")
                    .equals("agent-restricted"))
                throw new IllegalArgumentException("restricted dialplan required");
        } else if (settings.production())
            throw new IllegalArgumentException("production cannot use mock voice driver");
        new VoiceQuota(settings, ledger).validate();
    }

    public void callback(String url) {
        String base = settings.get("VOICE_CALLBACK_BASE_URL", "").replaceAll("/+$", "");
        if (base.isEmpty()
                || List.of(
                                "status",
                                "speech",
                                "media",
                                "recording",
                                "transcript",
                                "dtmf",
                                "batch",
                                "batch-v2")
                        .stream()
                        .noneMatch(kind -> url.equals(base + "/api/v1/webhooks/telephony/" + kind)))
            throw new Support.Failure(403, "callback target is not registered");
    }

    public void command(String path, byte[] body, String stamp, String nonce, String signature) {
        double now = LocalLedger.now();
        if (stamp == null
                || !stamp.matches("[0-9]{1,12}")
                || Math.abs(now - Long.parseLong(stamp)) > 60
                || nonce == null
                || !nonce.matches("[a-zA-Z0-9_-]{20,100}"))
            throw new Support.Failure(401, "missing or expired voice command permit");
        byte[] prefix = (stamp + "." + nonce + "." + path + ".").getBytes(StandardCharsets.UTF_8);
        byte[] value = Arrays.copyOf(prefix, prefix.length + body.length);
        System.arraycopy(body, 0, value, prefix.length, body.length);
        if (!Support.equals(
                Support.hmac(settings.secret("VOICE_COMMAND_SECRET"), value),
                signature == null ? "" : signature))
            throw new Support.Failure(401, "invalid voice command permit");
        var projection = VoiceAdmissionBatchScope.current();
        if (projection != null && projection.preloaded())
            ledger.transaction(
                    db -> {
                        if (!projection.claimNonce(db, nonce, now + 125))
                            throw new Support.Failure(409, "voice command permit was already used");
                        return null;
                    });
        else ledger.consumeNonce(nonce, now + 125);
    }

    public Map<String, Object> policy(Map<String, Object> payload) {
        Map<String, Object> metadata = Support.map(payload.get("metadata"));
        long tenant = Support.integer(metadata, "tenant_id", 0),
                attempt = Support.integer(metadata, "attempt", 0);
        if (tenant < 1 || attempt < 1)
            throw new Support.Failure(403, "dial requires tenant and attempt identity");
        String node = settings.get("VOICE_NODE_ID", "");
        if (!node.isEmpty() && !node.equals(metadata.get("gateway_node_id")))
            throw new Support.Failure(403, "dial permit targets a different gateway node");
        String line = String.valueOf(Support.integer(metadata, "telephony_line_id", 0));
        Map<String, Object> route = Support.map(routes.get(tenant + ":" + line));
        if (route.isEmpty()) throw new Support.Failure(403, "tenant line is not authorized");
        String phone = Support.required(payload, "phone", 32);
        if (!phone.matches("\\+?[1-9][0-9]{5,14}"))
            throw new Support.Failure(
                    403, "destination must include country code and contain 6-15 ASCII digits");
        String digits = phone.replaceFirst("^\\+", "");
        if (((List<?>) route.get("allowed_prefixes"))
                        .stream().noneMatch(p -> digits.startsWith((String) p))
                || ((List<?>) route.getOrDefault("denied_prefixes", List.of()))
                        .stream().anyMatch(p -> digits.startsWith((String) p)))
            throw new Support.Failure(403, "destination is not authorized");
        for (String key : List.of("freeswitch_gateway", "caller_id")) {
            String expected = String.valueOf(route.get(key.equals("caller_id") ? key : "gateway"));
            if (metadata.get(key) != null
                    && !String.valueOf(metadata.get(key)).isEmpty()
                    && !expected.equals(metadata.get(key)))
                throw new Support.Failure(403, "route/caller ID override is forbidden");
        }
        callback(Support.required(payload, "webhook_url", 8192));
        metadata.forEach(
                (key, value) -> {
                    if (key.endsWith("webhook_url") && value != null && !value.toString().isEmpty())
                        callback(value.toString());
                });
        return route;
    }

    public record Admission(Map<String, Object> row, boolean fresh) {}

    public Admission admit(
            Map<String, Object> payload,
            Map<String, Object> route,
            boolean diskSafe,
            int capacity) {
        var metadata = Support.map(payload.get("metadata"));
        long tenant = Support.integer(metadata, "tenant_id", 0),
                attempt = Support.integer(metadata, "attempt", 0);
        String line = Long.toString(Support.integer(metadata, "telephony_line_id", 0));
        byte[] frozenBytes = Support.canonical(payload);
        String frozen = new String(frozenBytes, StandardCharsets.UTF_8);
        int protocol = VoiceAuthorizations.version(payload);
        String call = Support.required(payload, "call_id", 128),
                digest = VoiceAuthorizations.digest(payload);
        double now = LocalLedger.now();
        long duration =
                Math.min(
                        Support.integer(route, "max_duration_sec", 300),
                        settings.number("VOICE_MAX_DURATION_SEC", 300, 1, 3600));
        long multiplier = Support.integer(route, "billing_multiplier", 2),
                rate = Support.integer(route, "rate_minor_per_minute", 1),
                cost =
                        Math.multiplyExact(
                                Math.multiplyExact((duration + 59) / 60, rate), multiplier);
        return ledger.transaction(
                db -> {
                    var projection = VoiceAdmissionBatchScope.current();
                    var cached =
                            projection != null && projection.preloaded()
                                    ? projection.prior(db, call, attempt)
                                    : null;
                    var prior =
                            projection != null && projection.preloaded()
                                    ? (cached == null
                                            ? List.<Map<String, Object>>of()
                                            : List.of(cached))
                                    : LocalLedger.query(
                                            db,
                                            "SELECT * FROM attempts WHERE call_id=? AND attempt=?",
                                            call,
                                            attempt);
                    Map<String, Object> auth =
                            protocol == 2
                                    ? VoiceAuthorizations.requireInstalled(db, payload)
                                    : null;
                    Map<String, Object> replaced = null;
                    if (!prior.isEmpty()) {
                        var row = prior.get(0);
                        if (Support.integer(row, "tenant", 0) != tenant
                                || !row.get("digest").equals(digest))
                            throw new Support.Failure(409, "idempotency key has different payload");
                        if (protocol == 2
                                && row.get("send_state").equals("REJECTED")
                                && row.get("state").equals("ended")
                                && row.get("result") == null
                                && auth != null
                                && !Objects.equals(
                                        auth.get("reservation_id"), row.get("registration_id")))
                            replaced = row;
                        else {
                            if (auth != null
                                    && !Objects.equals(
                                            auth.get("reservation_id"), row.get("registration_id")))
                                throw new Support.Failure(
                                        409,
                                        "original registration has not been conditionally"
                                                + " replaced");
                            return new Admission(row, false);
                        }
                    }
                    if (protocol == 1
                            && "db"
                                    .equals(
                                            LocalLedger.query(
                                                            db,
                                                            "SELECT admission_mode FROM"
                                                                    + " dial_admission_control WHERE"
                                                                    + " id=1 FOR SHARE")
                                                    .get(0)
                                                    .get("admission_mode")))
                        throw new Support.Failure(
                                503, "DB admission requires the installed authorization protocol");
                    if (projection != null) projection.begin();
                    if (!DialSendGate.permitted(db, call, tenant, attempt))
                        throw new Support.Failure(
                                429,
                                "dial send barrier is closed",
                                Map.of("X-Voice-Dial-Admitted", "false"));
                    if (!diskSafe)
                        throw new Support.Failure(
                                429,
                                "recording source disk below reserve; new dialing stopped",
                                Map.of("X-Voice-Dial-Admitted", "false", "Retry-After", "5"));
                    if ((projection != null && projection.preloaded()
                                    ? projection.activeFor(db, call, tenant)
                                    : LocalLedger.scalar(
                                            db,
                                            "SELECT COUNT(*) FROM attempts WHERE call_id=? AND"
                                                    + " (state!='ended' OR tenant!=?)",
                                            call,
                                            tenant))
                            != 0)
                        throw new Support.Failure(
                                409, "previous attempt is active or belongs to another tenant");
                    if (count(
                                    projection,
                                    db,
                                    "__stopped",
                                    "SELECT COUNT(*) FROM flags WHERE key='stopped' AND value='1'")
                            != 0) throw new Support.Failure(503, "emergency stop is active");
                    boolean overdue;
                    if (projection == null)
                        overdue =
                                LocalLedger.scalar(
                                                db,
                                                "SELECT COUNT(*) FROM outbox WHERE created<?",
                                                now
                                                        - settings.number(
                                                                "VOICE_CALLBACK_FAILURE_STOP_SEC",
                                                                30,
                                                                1,
                                                                60))
                                        != 0;
                    else {
                        double oldest = projection.oldestCallback(db);
                        overdue =
                                oldest != 0
                                        && oldest
                                                < now
                                                        - settings.number(
                                                                "VOICE_CALLBACK_FAILURE_STOP_SEC",
                                                                30,
                                                                1,
                                                                60);
                    }
                    if (overdue)
                        throw new Support.Failure(
                                503, "callback delivery unhealthy; new calls stopped");
                    checkScope(
                            db,
                            "1=1",
                            List.of(),
                            now,
                            cost,
                            Math.min(
                                    capacity,
                                    settings.number("VOICE_MAX_CONCURRENT", 20, 1, 100000)),
                            settings.number("VOICE_CPS", 2, 1, 1000),
                            settings.number("VOICE_DAILY_CALL_LIMIT", 1000, 1, 10000000),
                            settings.number("VOICE_HOUR_BUDGET_MINOR", 10000, 1, Long.MAX_VALUE),
                            settings.number("VOICE_DAY_BUDGET_MINOR", 50000, 1, Long.MAX_VALUE));
                    checkScope(
                            db,
                            "tenant=?",
                            List.of(tenant),
                            now,
                            cost,
                            settings.number("VOICE_MAX_CONCURRENT", 20, 1, 100000),
                            settings.number("VOICE_CPS", 2, 1, 1000),
                            settings.number("VOICE_DAILY_CALL_LIMIT", 1000, 1, 10000000),
                            settings.number("VOICE_HOUR_BUDGET_MINOR", 10000, 1, Long.MAX_VALUE),
                            settings.number("VOICE_DAY_BUDGET_MINOR", 50000, 1, Long.MAX_VALUE));
                    checkScope(
                            db,
                            "tenant=? AND line=?",
                            List.of(tenant, line),
                            now,
                            cost,
                            Support.integer(route, "max_concurrent", 1),
                            Support.integer(route, "cps", 1),
                            Support.integer(route, "calls_per_day", 1),
                            Support.integer(route, "hour_budget_minor", 1),
                            Support.integer(route, "day_budget_minor", 1));
                    if (settings.flag("VOICE_QUOTA_ENABLED", false))
                        new VoiceQuota(settings, ledger).reserve(db, call, attempt);
                    String uuid =
                            replaced == null
                                    ? UUID.randomUUID().toString()
                                    : replaced.get("uuid").toString();
                    double deadline =
                            now
                                    + settings.number(
                                            "FREESWITCH_ORIGINATE_TIMEOUT_SEC", 45, 5, 300)
                                    + duration
                                    + 10;
                    if (replaced == null)
                        LocalLedger.execute(
                                db,
                                "INSERT INTO"
                                        + " attempts(call_id,attempt,tenant,line,uuid,digest,payload,created,deadline,cost,rate,multiplier,send_state,protocol_version,registration_id)"
                                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'REGISTERED',?,?)",
                                call,
                                attempt,
                                tenant,
                                line,
                                uuid,
                                digest,
                                frozen,
                                now,
                                deadline,
                                cost,
                                rate,
                                multiplier,
                                protocol,
                                auth == null ? null : auth.get("reservation_id"));
                    else if (LocalLedger.update(
                                    db,
                                    "UPDATE attempts SET"
                                            + " payload=?,created=?,deadline=?,cost=?,rate=?,multiplier=?,send_state='REGISTERED',state='pending',registration_id=?,ended=NULL,send_intent_at=NULL,send_fence=0"
                                            + " WHERE uuid=? AND send_state='REJECTED' AND"
                                            + " state='ended' AND result IS NULL AND registration_id=?",
                                    frozen,
                                    now,
                                    deadline,
                                    cost,
                                    rate,
                                    multiplier,
                                    auth.get("reservation_id"),
                                    uuid,
                                    replaced.get("registration_id"))
                            != 1)
                        throw new Support.Failure(
                                409, "registration replacement lost its rejection proof");
                    if (projection != null) projection.accepted();
                    var row = new LinkedHashMap<String, Object>();
                    row.put("call_id", call);
                    row.put("attempt", attempt);
                    row.put("tenant", tenant);
                    row.put("line", line);
                    row.put("uuid", uuid);
                    row.put("digest", digest);
                    row.put("payload", frozen);
                    row.put("created", now);
                    row.put("deadline", deadline);
                    row.put("cost", cost);
                    row.put("rate", rate);
                    row.put("multiplier", multiplier);
                    row.put("state", "pending");
                    row.put("send_state", "REGISTERED");
                    row.put("observed", 0);
                    row.put("result", null);
                    row.put("protocol_version", protocol);
                    row.put("registration_id", auth == null ? null : auth.get("reservation_id"));
                    if (projection != null && projection.preloaded()) projection.remember(row);
                    return new Admission(row, true);
                });
    }

    private void checkScope(
            Connection db,
            String where,
            List<Object> args,
            double now,
            long cost,
            long concurrency,
            long cps,
            long daily,
            long hourlyBudget,
            long dailyBudget)
            throws Exception {
        var projection = VoiceAdmissionBatchScope.current();
        String prefix = where + args;
        var compact =
                settings.flag("VOICE_MATERIALIZED_LIMITS_ENABLED", false)
                        ? (projection == null
                                ? VoiceScopeFacts.read(db, where, args, now)
                                : projection.compact(db, where, args, now))
                        : null;
        // The three where clauses are fixed literals above; all values are bound parameters.
        if (fact(
                        compact,
                        "active",
                        projection,
                        db,
                        prefix + ":active",
                        "SELECT COUNT(*) FROM attempts WHERE " + where + " AND state!='ended'",
                        args.toArray())
                >= concurrency)
            throw new Support.Failure(
                    429,
                    "voice concurrency/CPS hard limit reached",
                    Map.of("X-Voice-Dial-Admitted", "false", "Retry-After", "1"));
        List<Object> recent = new ArrayList<>(args);
        recent.add(now - 1);
        if (count(
                        projection,
                        db,
                        prefix + ":recent",
                        "SELECT COUNT(*) FROM attempts WHERE "
                                + where
                                + " AND send_state!='REJECTED' AND created>=?",
                        recent.toArray())
                >= cps)
            throw new Support.Failure(
                    429,
                    "voice concurrency/CPS hard limit reached",
                    Map.of("X-Voice-Dial-Admitted", "false", "Retry-After", "1"));
        recent.set(recent.size() - 1, Math.floor(now / 86400) * 86400);
        String dayKey = prefix + ":day:" + recent.get(recent.size() - 1);
        if (fact(
                        compact,
                        "daily",
                        projection,
                        db,
                        dayKey,
                        "SELECT COUNT(*) FROM attempts WHERE "
                                + where
                                + " AND send_state!='REJECTED' AND created>=?",
                        recent.toArray())
                >= daily) throw new Support.Failure(429, "voice daily call limit reached");
        for (long window : new long[] {3600, 86400}) {
            double start = Math.floor(now / window) * window;
            List<Object> params = new ArrayList<>(args);
            params.add(start);
            params.add(start);
            String budgetKey = prefix + ":budget:" + window + ":" + start;
            long spent =
                    fact(
                            compact,
                            window == 3600 ? "hour_budget" : "day_budget",
                            projection,
                            db,
                            budgetKey,
                            "SELECT COALESCE(SUM(cost),0) FROM attempts WHERE "
                                    + where
                                    + " AND (created>=? OR ended>=? OR state!='ended')",
                            params.toArray());
            if (spent > (window == 3600 ? hourlyBudget : dailyBudget) - cost)
                throw new Support.Failure(429, "voice budget hard limit reached");
            if (projection != null) projection.add(budgetKey, cost);
        }
        if (projection != null) {
            projection.add(prefix + ":active", 1);
            projection.add(prefix + ":recent", 1);
            projection.add(dayKey, 1);
        }
    }

    private static long count(
            VoiceAdmissionBatchScope projection,
            Connection db,
            String key,
            String sql,
            Object... args)
            throws Exception {
        return projection == null
                ? LocalLedger.scalar(db, sql, args)
                : projection.count(db, key, sql, args);
    }

    private static long fact(
            Map<String, Object> compact,
            String field,
            VoiceAdmissionBatchScope projection,
            Connection db,
            String key,
            String sql,
            Object... args)
            throws Exception {
        if (compact == null) return count(projection, db, key, sql, args);
        long initial = ((Number) compact.get(field)).longValue();
        return projection == null ? initial : projection.projected(key, initial);
    }

    public Map<String, Object> lookup(String call, long tenant) {
        return ledger.read(
                db -> {
                    var rows =
                            LocalLedger.query(
                                    db,
                                    "SELECT * FROM attempts WHERE call_id=? AND tenant=? ORDER BY"
                                            + " attempt DESC LIMIT 1",
                                    call,
                                    tenant);
                    if (rows.isEmpty()) throw new Support.Failure(404, "call not found in tenant");
                    return rows.get(0);
                });
    }

    /**
     * Only the durable synthetic schedule calls this with rows already locked by the ledger writer.
     * Preserves finish(uuid,null), including reserved cost and cleanup of this exact attempt;
     * physical ASR/TTS permits stay untouched.
     */
    public void finishMockBatch(Connection db, List<Map<String, Object>> lockedRows, double endedAt)
            throws Exception {
        if (!settings.get("VOICE_GATEWAY_DRIVER", "mock").equals("mock"))
            throw new IllegalStateException("synthetic completion requires mock driver");
        var rows = lockedRows.stream().filter(row -> !row.get("state").equals("ended")).toList();
        if (rows.isEmpty()) return;
        for (var row : rows) {
            var evidence =
                    row.get("result") == null
                            ? Map.<String, Object>of()
                            : Support.object(
                                    row.get("result").toString().getBytes(StandardCharsets.UTF_8));
            if (!"PBX_STARTED".equals(row.get("send_state"))
                    || !"synthetic_executor".equals(evidence.get("evidence_source"))
                    || !row.get("uuid").equals(evidence.get("provider_call_id")))
                throw new Support.Failure(
                        503, "synthetic completion lacks its original accepted evidence");
        }
        var ids = rows.stream().map(row -> row.get("uuid")).toList();
        String slots = String.join(",", Collections.nCopies(ids.size(), "?"));
        var args = new ArrayList<Object>();
        args.add(endedAt);
        args.addAll(ids);
        if (LocalLedger.update(
                        db,
                        "UPDATE attempts SET state='ended',send_state='ENDED',ended=? WHERE uuid IN"
                                + " ("
                                + slots
                                + ") AND state!='ended' AND send_state='PBX_STARTED'",
                        args.toArray())
                != rows.size())
            throw new Support.Failure(503, "synthetic completion lost its original attempt");
        LocalLedger.execute(
                db,
                "DELETE c FROM voice_action_commands c JOIN attempts a ON a.call_id=c.call_id AND"
                        + " a.attempt=c.attempt WHERE a.uuid IN ("
                        + slots
                        + ")",
                ids.toArray());
        LocalLedger.execute(
                db,
                "UPDATE voice_permits p JOIN attempts a ON a.call_id=p.call_id AND"
                        + " a.attempt=p.attempt SET p.state='closed' WHERE a.uuid IN ("
                        + slots
                        + ") AND p.kind='reserve'",
                ids.toArray());
    }

    public void finish(String uuid, Long billsec) {
        ledger.transaction(
                db -> {
                    var rows = LocalLedger.query(db, "SELECT * FROM attempts WHERE uuid=?", uuid);
                    if (rows.isEmpty() || rows.get(0).get("state").equals("ended")) return null;
                    var row = rows.get(0);
                    long charge =
                            billsec == null
                                    ? Support.integer(row, "cost", 0)
                                    : Math.max(1, (billsec + 59) / 60)
                                            * Support.integer(row, "rate", 1)
                                            * Support.integer(row, "multiplier", 2);
                    LocalLedger.execute(
                            db,
                            "UPDATE attempts SET state='ended',send_state='ENDED',cost=?,ended=?"
                                    + " WHERE uuid=?",
                            charge,
                            LocalLedger.now(),
                            uuid);
                    LocalLedger.execute(
                            db,
                            "DELETE FROM voice_action_commands WHERE call_id=? AND attempt=?",
                            row.get("call_id"),
                            row.get("attempt"));
                    LocalLedger.execute(
                            db,
                            "UPDATE voice_permits SET state='closed' WHERE call_id=? AND attempt=?"
                                    + " AND kind='reserve'",
                            row.get("call_id"),
                            row.get("attempt"));
                    return null;
                });
    }
}
