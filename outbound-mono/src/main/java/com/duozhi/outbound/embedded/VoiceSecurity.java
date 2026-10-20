package com.duozhi.outbound.embedded;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;

final class VoiceSecurity {
    private final Settings settings;
    final LocalLedger ledger;
    final Map<String,Object> routes;
    VoiceSecurity(Settings settings, LocalLedger ledger) throws Exception {
        this.settings = settings; this.ledger = ledger;
        String file = settings.get("VOICE_SECURITY_ROUTES_FILE", "");
        byte[] routeBytes;
        if (file.isEmpty()) routeBytes = settings.get("VOICE_SECURITY_ROUTES_JSON", "{}").getBytes(StandardCharsets.UTF_8);
        else { Path path = Path.of(file); if (!Files.isRegularFile(path) || Files.size(path) > 1048576) throw new IllegalArgumentException("invalid route file"); routeBytes = Files.readAllBytes(path); }
        routes = Support.object(routeBytes);
        for (var route : routes.entrySet()) {
            if (!route.getKey().matches("[1-9][0-9]*:[0-9]+")) throw new IllegalArgumentException("invalid route key");
            Map<String,Object> value = Support.map(route.getValue());
            if (!Support.required(value, "gateway", 128).matches("[A-Za-z0-9_.-]+") || !Support.required(value, "caller_id", 32).matches("\\+?[0-9]{6,15}")) throw new IllegalArgumentException("invalid route gateway or caller ID");
            for (String field : List.of("max_concurrent", "cps", "calls_per_day", "hour_budget_minor", "day_budget_minor", "rate_minor_per_minute", "max_duration_sec")) if (Support.integer(value, field, 0) < 1) throw new IllegalArgumentException("invalid route limit: " + field);
            long multiplier = Support.integer(value, "billing_multiplier", 2);
            if (multiplier < 2 || multiplier > 100 || Support.integer(value, "max_duration_sec", 0) > 3600) throw new IllegalArgumentException("invalid billing or duration limit");
            for (String key : List.of("allowed_prefixes", "denied_prefixes")) {
                Object prefixes = value.getOrDefault(key, List.of()); if (!(prefixes instanceof List<?> list) || (key.equals("allowed_prefixes") && list.isEmpty()) || list.stream().anyMatch(p -> !(p instanceof String s) || !s.matches("[1-9][0-9]{0,14}"))) throw new IllegalArgumentException("invalid destination prefixes");
            }
        }
        String driver = settings.get("VOICE_GATEWAY_DRIVER", "mock");
        if (!driver.equals("mock")) {
            if (!driver.equals("freeswitch_esl")) throw new IllegalArgumentException("PBX HTTP forwarding remains disabled without verified hard-stop contract");
            Set<String> credentials = new HashSet<>();
            for (String name : List.of("SERVICE_TOKEN", "VOICE_COMMAND_SECRET", "VOICE_SECURITY_ADMIN_TOKEN", "WEBHOOK_TOKEN", "WEBHOOK_SECRET", "FREESWITCH_ESL_PASSWORD")) {
                String value = settings.secret(name);
                if (value.length() < 32 || value.contains("\r") || value.contains("\n") || Support.asciiLower(value).matches(".*(change-me|replace-me|your-).*")) throw new IllegalArgumentException("real dialing requires independent strong credentials: " + name);
                if (!credentials.add(value)) throw new IllegalArgumentException("voice security credentials must be distinct");
            }
            var base = Support.endpoint(settings.get("VOICE_CALLBACK_BASE_URL", ""));
            if (!Set.of("", "/").contains(base.getPath()) || base.getQuery() != null || (!base.getScheme().equals("https") && !settings.flag("VOICE_CALLBACK_ALLOW_PRIVATE_HTTP", false))) throw new IllegalArgumentException("callback requires a trusted origin and explicit internal HTTP approval");
            if (!settings.get("FREESWITCH_DIALPLAN_CONTEXT", "agent-restricted").equals("agent-restricted")) throw new IllegalArgumentException("restricted dialplan required");
        } else if (settings.production()) throw new IllegalArgumentException("production cannot use mock voice driver");
        new VoiceQuota(settings, ledger).validate();
    }
    void callback(String url) {
        String base = settings.get("VOICE_CALLBACK_BASE_URL", "").replaceAll("/+$", "");
        if (base.isEmpty() || List.of("status", "speech", "media", "recording", "transcript", "dtmf", "batch", "batch-v2").stream().noneMatch(kind -> url.equals(base + "/api/v1/webhooks/telephony/" + kind))) throw new Support.Failure(403, "callback target is not registered");
    }
    void command(String path, byte[] body, String stamp, String nonce, String signature) {
        double now = LocalLedger.now();
        if (stamp == null || !stamp.matches("[0-9]{1,12}") || Math.abs(now - Long.parseLong(stamp)) > 60 || nonce == null || !nonce.matches("[a-zA-Z0-9_-]{20,100}")) throw new Support.Failure(401, "missing or expired voice command permit");
        byte[] prefix = (stamp + "." + nonce + "." + path + ".").getBytes(StandardCharsets.UTF_8);
        byte[] value = Arrays.copyOf(prefix, prefix.length + body.length); System.arraycopy(body, 0, value, prefix.length, body.length);
        if (!Support.equals(Support.hmac(settings.secret("VOICE_COMMAND_SECRET"), value), signature == null ? "" : signature)) throw new Support.Failure(401, "invalid voice command permit");
        ledger.transaction(db -> { LocalLedger.execute(db, "DELETE FROM nonces WHERE expires<?", now); if (LocalLedger.scalar(db, "SELECT COUNT(*) FROM nonces WHERE nonce=?", nonce) != 0) throw new Support.Failure(409, "voice command permit was already used"); LocalLedger.execute(db, "INSERT INTO nonces VALUES (?,?)", nonce, now + 125); return null; });
    }
    Map<String,Object> policy(Map<String,Object> payload) {
        Map<String,Object> metadata = Support.map(payload.get("metadata")); long tenant = Support.integer(metadata, "tenant_id", 0), attempt = Support.integer(metadata, "attempt", 0);
        if (tenant < 1 || attempt < 1) throw new Support.Failure(403, "dial requires tenant and attempt identity");
        String node = settings.get("VOICE_NODE_ID", ""); if (!node.isEmpty() && !node.equals(metadata.get("gateway_node_id"))) throw new Support.Failure(403, "dial permit targets a different gateway node");
        String line = String.valueOf(Support.integer(metadata, "telephony_line_id", 0));
        Map<String,Object> route = Support.map(routes.get(tenant + ":" + line)); if (route.isEmpty()) throw new Support.Failure(403, "tenant line is not authorized");
        String phone = Support.required(payload, "phone", 32); if (!phone.matches("\\+?[1-9][0-9]{5,14}")) throw new Support.Failure(403, "destination must include country code and contain 6-15 ASCII digits");
        String digits = phone.replaceFirst("^\\+", "");
        if (((List<?>)route.get("allowed_prefixes")).stream().noneMatch(p -> digits.startsWith((String)p)) || ((List<?>)route.getOrDefault("denied_prefixes", List.of())).stream().anyMatch(p -> digits.startsWith((String)p))) throw new Support.Failure(403, "destination is not authorized");
        for (String key : List.of("freeswitch_gateway", "caller_id")) {
            String expected = String.valueOf(route.get(key.equals("caller_id") ? key : "gateway"));
            if (metadata.get(key) != null && !String.valueOf(metadata.get(key)).isEmpty() && !expected.equals(metadata.get(key))) throw new Support.Failure(403, "route/caller ID override is forbidden");
        }
        callback(Support.required(payload, "webhook_url", 8192));
        metadata.forEach((key, value) -> { if (key.endsWith("webhook_url") && value != null && !value.toString().isEmpty()) callback(value.toString()); });
        return route;
    }
    record Admission(Map<String,Object> row, boolean fresh) { }
    Admission admit(Map<String,Object> payload, Map<String,Object> route, boolean diskSafe, int capacity) {
        var metadata = Support.map(payload.get("metadata")); long tenant = Support.integer(metadata, "tenant_id", 0), attempt = Support.integer(metadata, "attempt", 0); String line = Long.toString(Support.integer(metadata, "telephony_line_id", 0));
        String call = Support.required(payload, "call_id", 128), digest = Support.hex(Support.sha(Support.canonical(payload))); double now = LocalLedger.now();
        long duration = Math.min(Support.integer(route, "max_duration_sec", 300), settings.number("VOICE_MAX_DURATION_SEC", 300, 1, 3600));
        long multiplier = Support.integer(route, "billing_multiplier", 2), rate = Support.integer(route, "rate_minor_per_minute", 1), cost = Math.multiplyExact(Math.multiplyExact((duration + 59) / 60, rate), multiplier);
        return ledger.transaction(db -> {
            var prior = LocalLedger.query(db, "SELECT * FROM attempts WHERE call_id=? AND attempt=?", call, attempt);
            if (!prior.isEmpty()) { var row = prior.get(0); if (Support.integer(row, "tenant", 0) != tenant || !row.get("digest").equals(digest)) throw new Support.Failure(409, "idempotency key has different payload"); return new Admission(row, false); }
            if (!diskSafe) throw new Support.Failure(429, "recording source disk below reserve; new dialing stopped", Map.of("X-Voice-Dial-Admitted", "false", "Retry-After", "5"));
            if (LocalLedger.scalar(db, "SELECT COUNT(*) FROM attempts WHERE call_id=? AND (state!='ended' OR tenant!=?)", call, tenant) != 0) throw new Support.Failure(409, "previous attempt is active or belongs to another tenant");
            if (LocalLedger.scalar(db, "SELECT COUNT(*) FROM flags WHERE key='stopped' AND value='1'") != 0) throw new Support.Failure(503, "emergency stop is active");
            if (LocalLedger.scalar(db, "SELECT COUNT(*) FROM outbox WHERE created<?", now - settings.number("VOICE_CALLBACK_FAILURE_STOP_SEC", 30, 1, 60)) != 0) throw new Support.Failure(503, "callback delivery unhealthy; new calls stopped");
            checkScope(db, "1=1", List.of(), now, cost, Math.min(capacity, settings.number("VOICE_MAX_CONCURRENT", 20, 1, 10000)), settings.number("VOICE_CPS", 2, 1, 1000), settings.number("VOICE_DAILY_CALL_LIMIT", 1000, 1, 10000000), settings.number("VOICE_HOUR_BUDGET_MINOR", 10000, 1, Long.MAX_VALUE), settings.number("VOICE_DAY_BUDGET_MINOR", 50000, 1, Long.MAX_VALUE));
            checkScope(db, "tenant=?", List.of(tenant), now, cost, settings.number("VOICE_MAX_CONCURRENT", 20, 1, 10000), settings.number("VOICE_CPS", 2, 1, 1000), settings.number("VOICE_DAILY_CALL_LIMIT", 1000, 1, 10000000), settings.number("VOICE_HOUR_BUDGET_MINOR", 10000, 1, Long.MAX_VALUE), settings.number("VOICE_DAY_BUDGET_MINOR", 50000, 1, Long.MAX_VALUE));
            checkScope(db, "tenant=? AND line=?", List.of(tenant, line), now, cost, Support.integer(route, "max_concurrent", 1), Support.integer(route, "cps", 1), Support.integer(route, "calls_per_day", 1), Support.integer(route, "hour_budget_minor", 1), Support.integer(route, "day_budget_minor", 1));
            if (settings.flag("VOICE_QUOTA_ENABLED", false)) new VoiceQuota(settings, ledger).reserve(db, call, attempt);
            String uuid = UUID.randomUUID().toString();
            LocalLedger.execute(db, "INSERT INTO attempts(call_id,attempt,tenant,line,uuid,digest,payload,created,deadline,cost,rate,multiplier) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", call, attempt, tenant, line, uuid, digest, new String(Support.canonical(payload), StandardCharsets.UTF_8), now, now + settings.number("FREESWITCH_ORIGINATE_TIMEOUT_SEC", 45, 5, 300) + duration + 10, cost, rate, multiplier);
            return new Admission(LocalLedger.query(db, "SELECT * FROM attempts WHERE uuid=?", uuid).get(0), true);
        });
    }
    private void checkScope(Connection db, String where, List<Object> args, double now, long cost, long concurrency, long cps, long daily, long hourlyBudget, long dailyBudget) throws Exception {
        // The three where clauses are fixed literals above; all values are bound parameters.
        if (LocalLedger.scalar(db, "SELECT COUNT(*) FROM attempts WHERE " + where + " AND state!='ended'", args.toArray()) >= concurrency) throw new Support.Failure(429, "voice concurrency/CPS hard limit reached", Map.of("X-Voice-Dial-Admitted", "false", "Retry-After", "1"));
        List<Object> recent = new ArrayList<>(args); recent.add(now - 1);
        if (LocalLedger.scalar(db, "SELECT COUNT(*) FROM attempts WHERE " + where + " AND created>=?", recent.toArray()) >= cps) throw new Support.Failure(429, "voice concurrency/CPS hard limit reached", Map.of("X-Voice-Dial-Admitted", "false", "Retry-After", "1"));
        recent.set(recent.size() - 1, Math.floor(now / 86400) * 86400);
        if (LocalLedger.scalar(db, "SELECT COUNT(*) FROM attempts WHERE " + where + " AND created>=?", recent.toArray()) >= daily) throw new Support.Failure(429, "voice daily call limit reached");
        for (long window : new long[]{3600, 86400}) {
            double start = Math.floor(now / window) * window; List<Object> params = new ArrayList<>(args); params.add(start); params.add(start);
            long spent = LocalLedger.scalar(db, "SELECT COALESCE(SUM(cost),0) FROM attempts WHERE " + where + " AND (created>=? OR ended>=? OR state!='ended')", params.toArray());
            if (spent > (window == 3600 ? hourlyBudget : dailyBudget) - cost) throw new Support.Failure(429, "voice budget hard limit reached");
        }
    }
    Map<String,Object> lookup(String call, long tenant) { return ledger.read(db -> { var rows = LocalLedger.query(db, "SELECT * FROM attempts WHERE call_id=? AND tenant=? ORDER BY attempt DESC LIMIT 1", call, tenant); if (rows.isEmpty()) throw new Support.Failure(404, "call not found in tenant"); return rows.get(0); }); }
    void finish(String uuid, Long billsec) { ledger.transaction(db -> {
        var rows = LocalLedger.query(db, "SELECT * FROM attempts WHERE uuid=?", uuid); if (rows.isEmpty() || rows.get(0).get("state").equals("ended")) return null;
        var row = rows.get(0); long charge = billsec == null ? Support.integer(row, "cost", 0) : Math.max(1, (billsec + 59) / 60) * Support.integer(row, "rate", 1) * Support.integer(row, "multiplier", 2);
        LocalLedger.execute(db, "UPDATE attempts SET state='ended',cost=?,ended=? WHERE uuid=?", charge, LocalLedger.now(), uuid);
        LocalLedger.execute(db, "DELETE FROM voice_action_commands WHERE call_id=? AND attempt=?", row.get("call_id"), row.get("attempt"));
        LocalLedger.execute(db, "UPDATE voice_permits SET state='closed' WHERE call_id=? AND attempt=? AND kind='reserve'", row.get("call_id"), row.get("attempt")); return null;
    }); }
}
