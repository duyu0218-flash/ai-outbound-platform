package com.duozhi.services;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;

final class VoiceQuota {
    private final Settings settings;
    private final LocalLedger ledger;
    VoiceQuota(Settings settings, LocalLedger ledger) { this.settings = settings; this.ledger = ledger; }
    record Budget(String scope, long concurrency, long rpm) { }
    Budget budget(String kind) {
        Map<String,Object> config = Support.map(Support.object(settings.get("VOICE_QUOTA_BUDGETS_JSON", "{}").getBytes(StandardCharsets.UTF_8)).get(kind));
        List<String> parts = new ArrayList<>();
        for (String field : List.of("provider", "account", "region", "product")) { String value = Support.str(config, field, "").strip(); if (value.isEmpty() || value.contains("/")) throw new IllegalArgumentException("invalid voice account scope"); parts.add(value); }
        long limit = Support.integer(config, "concurrency", 0), rpm = Support.integer(config, "requests_per_minute", 0);
        if (limit < 1 || (kind.equals("tts") && rpm < 1)) throw new IllegalArgumentException("invalid voice account quota");
        return new Budget(String.join("/", parts), limit, rpm);
    }
    void validate() { ledger.read(db -> {
        if (!settings.flag("VOICE_QUOTA_ENABLED", false)) { if (LocalLedger.scalar(db, "SELECT COUNT(*) FROM voice_permits WHERE state!='closed'") != 0) throw new IllegalArgumentException("drain or reconcile permits before disabling quota"); }
        else for (String kind : List.of("asr", "tts")) requireScope(db, budget(kind).scope, kind);
        return null;
    }); }
    private void requireScope(Connection db, String scope, String kind) throws Exception {
        if (LocalLedger.scalar(db, "SELECT COUNT(*) FROM voice_permits WHERE kind IN (?,?) AND state!='closed' AND scope!=?", kind, kind.equals("asr") ? "reserve" : "tts", scope) != 0) throw new Support.Failure(503, "drain or reconcile permits before changing account scope");
    }
    private long used(Connection db, Budget budget, String kind) throws Exception {
        long physical = LocalLedger.scalar(db, "SELECT COUNT(*) FROM voice_permits WHERE scope=? AND kind=? AND state!='closed'", budget.scope, kind);
        if (kind.equals("asr")) {
            long reserved = LocalLedger.scalar(db, "SELECT COUNT(*) FROM voice_permits r WHERE r.scope=? AND r.kind='reserve' AND r.state!='closed' AND NOT EXISTS(SELECT 1 FROM voice_permits p WHERE p.scope=r.scope AND p.call_id=r.call_id AND p.attempt=r.attempt AND p.kind='asr' AND p.state!='closed')", budget.scope);
            physical += reserved;
        }
        return physical;
    }
    void reserve(Connection db, String call, long attempt) throws Exception {
        Budget budget = budget("asr"); requireScope(db, budget.scope, "asr"); budget("tts");
        if (used(db, budget, "asr") >= budget.concurrency) throw new Support.Failure(429, "ASR call reservation budget exhausted", Map.of("X-Voice-Dial-Admitted", "false", "Retry-After", "1"));
        LocalLedger.execute(db, "INSERT INTO voice_permits VALUES (?,?,?,?,?,?,?,?)", "reserve:" + call + ":" + attempt, budget.scope, "reserve", call, attempt, "controller", "active", LocalLedger.now());
    }
    void acquire(Connection db, String id, String kind, String call, long attempt, String owner) throws Exception {
        if (!Set.of("asr", "tts").contains(kind)) throw new Support.Failure(422, "invalid voice permit kind");
        Budget budget = budget(kind); requireScope(db, budget.scope, kind);
        var previous = LocalLedger.query(db, "SELECT * FROM voice_permits WHERE id=?", id);
        if (!previous.isEmpty()) { var row = previous.get(0); if (!budget.scope.equals(row.get("scope")) || !kind.equals(row.get("kind")) || !call.equals(row.get("call_id")) || attempt != Support.integer(row, "attempt", 0) || !owner.equals(row.get("owner")) || !"active".equals(row.get("state"))) throw new Support.Failure(409, "voice permit identity or state conflict"); return; }
        if (LocalLedger.scalar(db, "SELECT COUNT(*) FROM voice_permits WHERE call_id=? AND attempt=? AND kind='reserve' AND state='active'", call, attempt) == 0) throw new Support.Failure(409, "call has no active voice reservation");
        boolean first = kind.equals("asr") && LocalLedger.scalar(db, "SELECT COUNT(*) FROM voice_permits WHERE scope=? AND call_id=? AND attempt=? AND kind='asr' AND state!='closed'", budget.scope, call, attempt) == 0;
        if (used(db, budget, kind) + (first ? 0 : 1) > budget.concurrency) throw new Support.Failure(429, "voice connection/request concurrency exhausted");
        double now = LocalLedger.now();
        if (kind.equals("tts") && LocalLedger.scalar(db, "SELECT COUNT(*) FROM voice_permits WHERE scope=? AND kind='tts' AND created>?", budget.scope, now - 60) >= budget.rpm) throw new Support.Failure(429, "TTS rolling request budget exhausted");
        LocalLedger.execute(db, "DELETE FROM voice_permits WHERE id IN(SELECT id FROM voice_permits WHERE state='closed' AND created<? LIMIT 100)", now - 3600);
        LocalLedger.execute(db, "INSERT INTO voice_permits VALUES (?,?,?,?,?,?,?,?)", id, budget.scope, kind, call, attempt, owner, "active", now);
    }
    void release(Connection db, String id, String owner, boolean confirmed) throws Exception {
        var rows = LocalLedger.query(db, "SELECT owner,state FROM voice_permits WHERE id=?", id);
        if (rows.isEmpty() || !owner.equals(rows.get(0).get("owner"))) throw new Support.Failure(409, "voice permit owner mismatch");
        if (!rows.get(0).get("state").equals("closed")) LocalLedger.execute(db, "UPDATE voice_permits SET state=? WHERE id=?", confirmed ? "closed" : "unknown", id);
    }
    Map<String,Object> snapshot() { return ledger.read(db -> {
        Map<String,Object> output = new LinkedHashMap<>(); for (String kind : List.of("asr", "tts")) { Budget budget = budget(kind); output.put(kind, Map.of("scope", budget.scope, "concurrency", budget.concurrency, "requests_per_minute", budget.rpm, "used", used(db, budget, kind), "unknown", LocalLedger.scalar(db, "SELECT COUNT(*) FROM voice_permits WHERE scope=? AND kind=? AND state='unknown'", budget.scope, kind))); } return output;
    }); }
}
