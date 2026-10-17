package com.duozhi.services;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

final class VoiceCallbacks implements AutoCloseable {
    private final Settings settings;
    private final LocalLedger ledger;
    private final VoiceSecurity security;
    private final HttpClient client = Support.client();
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();
    private final ExecutorService delivery;
    private final ScheduledExecutorService poll;
    private final int concurrency;
    VoiceCallbacks(Settings settings, LocalLedger ledger, VoiceSecurity security) {
        this.settings = settings; this.ledger = ledger; this.security = security;
        concurrency = (int)settings.number("VOICE_CALLBACK_CONCURRENCY", 16, 1, 128);
        delivery = Executors.newFixedThreadPool(concurrency, r -> { Thread t = new Thread(r, "voice-callback"); t.setDaemon(true); return t; });
        poll = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "voice-outbox"); t.setDaemon(true); return t; });
    }
    void start() {
        long interval = (long)(settings.decimal("VOICE_CALLBACK_POLL_SEC", .1, .01, 10) * 1000);
        if (settings.flag("VOICE_CALLBACK_BATCH_ENABLED", false)) interval = Math.max(1, Math.min(interval, (long)settings.decimal("VOICE_CALLBACK_BATCH_DELAY_MS", 5, 0, 5)));
        poll.scheduleWithFixedDelay(this::tick, 0, interval, TimeUnit.MILLISECONDS);
        poll.scheduleWithFixedDelay(this::purge, 60, 60, TimeUnit.SECONDS);
    }
    void atomic(Runnable work) { ledger.transaction(db -> { work.run(); return null; }); }
    void post(String url, Map<String,Object> payload) {
        security.callback(url); byte[] body = Support.canonical(payload); byte[] target = (url + "\0").getBytes(StandardCharsets.UTF_8); byte[] identity = Arrays.copyOf(target, target.length + body.length); System.arraycopy(body, 0, identity, target.length, body.length);
        String id = Support.hex(Support.sha(identity)); String stream = Support.str(payload, "call_id", id);
        ledger.transaction(db -> { double now = LocalLedger.now(); LocalLedger.execute(db, "INSERT OR IGNORE INTO outbox(id,url,body,created,due,stream_key) VALUES (?,?,?,?,?,?)", id, url, body, now, now, stream); return null; });
    }
    synchronized int tickOnce() {
        int available = concurrency - inflight.size(); if (available <= 0) return 0;
        var rows = ledger.read(db -> LocalLedger.query(db, "SELECT o.* FROM outbox o WHERE o.due<=? AND NOT EXISTS(SELECT 1 FROM outbox e WHERE e.stream_key=o.stream_key AND (e.created<o.created OR(e.created=o.created AND e.rowid<o.rowid))) ORDER BY o.created,o.rowid LIMIT ?", LocalLedger.now(), concurrency));
        rows.removeIf(row -> inflight.contains(row.get("id"))); if (rows.size() > available) rows = new ArrayList<>(rows.subList(0, available));
        for (var row : rows) inflight.add((String)row.get("id"));
        int count = rows.size();
        boolean batch = settings.flag("VOICE_CALLBACK_BATCH_ENABLED", false);
        int batchSize = (int)settings.number("VOICE_CALLBACK_BATCH_SIZE", 16, 1, 16);
        for (int i = 0; i < rows.size();) {
            List<Map<String,Object>> group = new ArrayList<>(); group.add(rows.get(i++));
            String url = Support.str(group.get(0), "url", ""); String prefix = url.substring(0, url.lastIndexOf('/'));
            if (batch) while (i < rows.size() && group.size() < batchSize && Support.str(rows.get(i), "url", "").startsWith(prefix + "/")) group.add(rows.get(i++));
            delivery.submit(() -> deliver(group));
        }
        return count;
    }
    private void tick() { try { tickOnce(); } catch (Exception unavailable) { /* Durable rows remain and stop new admission when overdue. */ } }
    private HttpResponse<byte[]> send(String url, byte[] body) throws Exception {
        security.callback(url); String stamp = Long.toString(System.currentTimeMillis() / 1000);
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis((long)(settings.decimal("REQUEST_TIMEOUT_SEC", 10, .1, 300) * 1000))).header("Content-Type", "application/json").header("x-webhook-token", settings.secret("WEBHOOK_TOKEN"));
        String secret = settings.secret("WEBHOOK_SECRET"); if (!secret.isEmpty()) {
            byte[] prefix = (stamp + ".").getBytes(StandardCharsets.UTF_8), signed = Arrays.copyOf(prefix, prefix.length + body.length); System.arraycopy(body, 0, signed, prefix.length, body.length);
            builder.header("x-webhook-timestamp", stamp).header("x-webhook-signature", Support.hmac(secret, signed));
        }
        var response = client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var input = Support.timed(response.body(), (long)(settings.decimal("REQUEST_TIMEOUT_SEC", 10, .1, 300) * 1000))) { return new BufferedResponse(response, Support.read(input, 1048576)); }
    }
    private void deliver(List<Map<String,Object>> rows) {
        try {
            if (rows.size() == 1) { deliverSingle(rows.get(0)); return; }
            String first = Support.str(rows.get(0), "url", ""), target = first.substring(0, first.lastIndexOf('/')) + "/batch";
            List<Map<String,Object>> events = new ArrayList<>();
            for (var row : rows) { String url = Support.str(row, "url", ""); events.add(Map.of("id", row.get("id"), "kind", url.substring(url.lastIndexOf('/') + 1), "payload", Support.object((byte[])row.get("body")))); }
            var response = send(target, Support.canonical(Map.of("version", 1, "events", events)));
            if (Set.of(400, 404, 405, 409, 413, 422).contains(response.statusCode()) || response.headers().firstValue("x-callback-batch-split").orElse("").equals("true")) { for (var row : rows) deliverSingle(row); return; }
            boolean accepted = false;
            if (response.statusCode() == 200) {
                var ack = Support.object(response.body()); Object ids = ack.get("accepted");
                accepted = "received".equals(ack.get("result")) && Support.integer(ack, "version", 0) == 1 && ids instanceof List<?> list && list.size() == rows.size() && new HashSet<>(list).equals(new HashSet<>(rows.stream().map(r -> r.get("id")).toList()));
            }
            for (var row : rows) complete(row, accepted, response.headers().firstValue("Retry-After").orElse("0"));
        } catch (Exception error) { for (var row : rows) { try { complete(row, false, "0"); } catch (Exception ignored) { } } }
        finally { for (var row : rows) inflight.remove((String)row.get("id")); }
    }
    private void deliverSingle(Map<String,Object> row) throws Exception {
        var response = send(Support.str(row, "url", ""), (byte[])row.get("body")); complete(row, response.statusCode() == 200, response.headers().firstValue("Retry-After").orElse("0"));
    }
    private void complete(Map<String,Object> row, boolean accepted, String retry) {
        double after = 0; try { after = Double.parseDouble(retry); if (!Double.isFinite(after)) after = 0; } catch (NumberFormatException ignored) { }
        double delay = Math.max(Math.min(300, after), Math.min(60, Math.pow(2, Support.integer(row, "failures", 0)))) + ThreadLocalRandom.current().nextDouble(.25);
        ledger.transaction(db -> { if (accepted) LocalLedger.execute(db, "DELETE FROM outbox WHERE id=?", row.get("id")); else LocalLedger.execute(db, "UPDATE outbox SET failures=failures+1,due=? WHERE id=?", LocalLedger.now() + delay, row.get("id")); return null; });
    }
    private void purge() {
        try { ledger.transaction(db -> {
            double now = LocalLedger.now();
            var rows = LocalLedger.query(db, "SELECT uuid FROM attempts WHERE state='ended' AND ended<? AND payload!='{}' LIMIT 1000", now - settings.number("VOICE_SENSITIVE_RETENTION_DAYS", 90, 1, 36500) * 86400);
            for (var row : rows) LocalLedger.execute(db, "UPDATE attempts SET payload='{}',result=? WHERE uuid=?", new String(Support.canonical(Map.of("result", "ended", "provider_call_id", row.get("uuid"))), StandardCharsets.UTF_8), row.get("uuid"));
            LocalLedger.execute(db, "UPDATE audit SET detail='' WHERE id IN(SELECT id FROM audit WHERE at<? AND detail!='' LIMIT 1000)", now - settings.number("VOICE_AUDIT_RETENTION_DAYS", 180, 1, 36500) * 86400); return null;
        }); } catch (Exception unavailable) { }
    }
    @Override public void close() {
        poll.shutdownNow(); delivery.shutdown(); try { if (!delivery.awaitTermination(15, TimeUnit.SECONDS)) delivery.shutdownNow(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); delivery.shutdownNow(); }
    }
    private record BufferedResponse(HttpResponse<?> original, byte[] body) implements HttpResponse<byte[]> {
        public int statusCode() { return original.statusCode(); } public HttpRequest request() { return original.request(); }
        public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); } public HttpHeaders headers() { return original.headers(); }
        public Optional<javax.net.ssl.SSLSession> sslSession() { return original.sslSession(); } public URI uri() { return original.uri(); } public HttpClient.Version version() { return original.version(); }
    }
}
