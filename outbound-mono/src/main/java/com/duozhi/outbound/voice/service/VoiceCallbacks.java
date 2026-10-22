package com.duozhi.outbound.voice.service;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.dao.LocalLedger;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.voice.security.VoiceSecurity;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class VoiceCallbacks implements AutoCloseable {
    private final Settings settings;
    private final LocalLedger ledger;
    private final VoiceSecurity security;
    private final HttpClient client = Support.client();
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();
    private final ExecutorService delivery;
    private final ScheduledExecutorService poll;
    private final int concurrency;
    private final java.util.concurrent.atomic.AtomicInteger deliveries =
            new java.util.concurrent.atomic.AtomicInteger();

    VoiceCallbacks(Settings settings, LocalLedger ledger, VoiceSecurity security) {
        this.settings = settings;
        this.ledger = ledger;
        this.security = security;
        concurrency = (int) settings.number("VOICE_CALLBACK_CONCURRENCY", 16, 1, 128);
        delivery =
                Executors.newFixedThreadPool(
                        concurrency,
                        r -> {
                            Thread t = new Thread(r, "voice-callback");
                            t.setDaemon(true);
                            return t;
                        });
        poll =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "voice-outbox");
                            t.setDaemon(true);
                            return t;
                        });
    }

    void start() {
        long interval = (long) (settings.decimal("VOICE_CALLBACK_POLL_SEC", .1, .01, 10) * 1000);
        if (settings.flag("VOICE_CALLBACK_BATCH_ENABLED", false))
            interval =
                    Math.max(
                            1,
                            Math.min(
                                    interval,
                                    (long)
                                            settings.decimal(
                                                    "VOICE_CALLBACK_BATCH_DELAY_MS", 5, 0, 5)));
        poll.scheduleWithFixedDelay(this::tick, 0, interval, TimeUnit.MILLISECONDS);
        poll.scheduleWithFixedDelay(this::purge, 60, 60, TimeUnit.SECONDS);
    }

    private final ThreadLocal<List<Object[]>> bufferedPosts = new ThreadLocal<>();

    public void atomic(Runnable work) {
        if (bufferedPosts.get() != null) {
            work.run();
            return;
        }
        ledger.transaction(
                db -> {
                    var posts = new ArrayList<Object[]>();
                    bufferedPosts.set(posts);
                    try {
                        work.run();
                        LocalLedger.executeBatch(
                                db,
                                "INSERT IGNORE INTO outbox(id,url,body,created,due,stream_key)"
                                        + " VALUES (?,?,?,?,?,?)",
                                posts);
                        return null;
                    } finally {
                        bufferedPosts.remove();
                    }
                });
    }

    public int inflight() {
        return inflight.size();
    }

    public int deliveries() {
        return deliveries.get();
    }

    public void post(String url, Map<String, Object> payload) {
        security.callback(url);
        byte[] body = Support.canonical(payload);
        byte[] target = (url + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] identity = Arrays.copyOf(target, target.length + body.length);
        System.arraycopy(body, 0, identity, target.length, body.length);
        String id = Support.hex(Support.sha(identity));
        String stream = Support.str(payload, "call_id", id);
        double now = LocalLedger.now();
        var projection = VoiceAdmissionBatchScope.current();
        if (projection != null) projection.noteCallback(now);
        var posts = bufferedPosts.get();
        if (posts != null) {
            posts.add(new Object[] {id, url, body, now, now, stream});
            return;
        }
        ledger.transaction(
                db -> {
                    LocalLedger.execute(
                            db,
                            "INSERT IGNORE INTO outbox(id,url,body,created,due,stream_key) VALUES"
                                    + " (?,?,?,?,?,?)",
                            id,
                            url,
                            body,
                            now,
                            now,
                            stream);
                    return null;
                });
    }

    synchronized int tickOnce() {
        boolean batch = settings.flag("VOICE_CALLBACK_BATCH_ENABLED", false);
        int batchSize = batch ? (int) settings.number("VOICE_CALLBACK_BATCH_SIZE", 16, 1, 64) : 1;
        // Bound HTTP requests and event bodies independently. The compatibility
        // default is still concurrency events; an explicit larger event budget
        // lets a slow batch occupy one request slot rather than 64 request slots.
        int eventBudget =
                (int) settings.number("VOICE_CALLBACK_MAX_INFLIGHT_EVENTS", concurrency, 1, 8192);
        int slots = concurrency - deliveries.get();
        int available = Math.min(eventBudget - inflight.size(), slots * batchSize);
        if (available <= 0) return 0;
        // Exclude deliveries already awaiting their HTTP acknowledgement before
        // fetching their potentially large bodies. Predecessor checks still see
        // those durable rows, so later events of the same call cannot overtake.
        var active = new ArrayList<>(inflight);
        var arguments = new ArrayList<Object>();
        arguments.add(LocalLedger.now());
        arguments.addAll(active);
        arguments.add(available);
        String exclude =
                active.isEmpty()
                        ? ""
                        : " AND o.id NOT IN ("
                                + String.join(",", Collections.nCopies(active.size(), "?"))
                                + ")";
        var rows =
                ledger.read(
                        db ->
                                LocalLedger.query(
                                        db,
                                        "SELECT o.* FROM outbox o WHERE o.due<=?"
                                                + exclude
                                                + " AND NOT EXISTS(SELECT 1 FROM outbox e WHERE"
                                                + " e.stream_key=o.stream_key AND"
                                                + " (e.created<o.created OR(e.created=o.created AND"
                                                + " e.enqueue_order<o.enqueue_order))) ORDER BY"
                                                + " o.created,o.enqueue_order LIMIT ?",
                                        arguments.toArray()));
        rows.removeIf(row -> inflight.contains(row.get("id")));
        if (rows.size() > available) rows = new ArrayList<>(rows.subList(0, available));
        int count = 0;
        int partitionGroups = (int) settings.number("VOICE_CALLBACK_PARTITION_GROUPS", 1, 1, 4);
        var owners = new IdentityHashMap<Map<String, Object>, Integer>();
        if (batch && partitionGroups > 1) {
            for (var row : rows) owners.put(row, partitionOwner(row, partitionGroups));
            rows.sort(Comparator.comparingInt(owners::get));
        }
        for (int i = 0; i < rows.size() && slots > 0; slots--) {
            List<Map<String, Object>> group = new ArrayList<>();
            group.add(rows.get(i++));
            String url = Support.str(group.get(0), "url", "");
            String prefix = url.substring(0, url.lastIndexOf('/'));
            int owner = owners.getOrDefault(group.get(0), 0);
            if (batch)
                while (i < rows.size()
                        && group.size() < batchSize
                        && owners.getOrDefault(rows.get(i), 0) == owner
                        && Support.str(rows.get(i), "url", "").startsWith(prefix + "/"))
                    group.add(rows.get(i++));
            for (var row : group) inflight.add((String) row.get("id"));
            deliveries.incrementAndGet();
            try {
                delivery.submit(() -> deliver(group));
            } catch (RejectedExecutionException closed) {
                for (var row : group) inflight.remove((String) row.get("id"));
                deliveries.decrementAndGet();
                throw closed;
            }
            count += group.size();
        }
        return count;
    }

    private static int partitionOwner(Map<String, Object> row, int groups) {
        String stream = Support.str(row, "stream_key", Support.str(row, "id", ""));
        try {
            stream = UUID.fromString(stream).toString();
        } catch (IllegalArgumentException opaque) {
        }
        long hash =
                Integer.toUnsignedLong(
                        java.nio.ByteBuffer.wrap(
                                        Support.sha(stream.getBytes(StandardCharsets.UTF_8)))
                                .getInt());
        return (int) (hash % 64) % groups;
    }

    private void tick() {
        try {
            tickOnce();
        } catch (Exception unavailable) {
            /* Durable rows remain and stop new admission when overdue. */
        }
    }

    private HttpResponse<byte[]> send(String url, byte[] body) throws Exception {
        security.callback(url);
        String stamp = Long.toString(System.currentTimeMillis() / 1000);
        var builder =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(
                                Duration.ofMillis(
                                        (long)
                                                (settings.decimal(
                                                                "REQUEST_TIMEOUT_SEC", 10, .1, 300)
                                                        * 1000)))
                        .header("Content-Type", "application/json")
                        .header("x-webhook-token", settings.secret("WEBHOOK_TOKEN"));
        String secret = settings.secret("WEBHOOK_SECRET");
        if (!secret.isEmpty()) {
            byte[] prefix = (stamp + ".").getBytes(StandardCharsets.UTF_8),
                    signed = Arrays.copyOf(prefix, prefix.length + body.length);
            System.arraycopy(body, 0, signed, prefix.length, body.length);
            builder.header("x-webhook-timestamp", stamp)
                    .header("x-webhook-signature", Support.hmac(secret, signed));
        }
        var response =
                client.send(
                        builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        try (var input =
                Support.timed(
                        response.body(),
                        (long) (settings.decimal("REQUEST_TIMEOUT_SEC", 10, .1, 300) * 1000))) {
            return new BufferedResponse(response, Support.read(input, 1048576));
        }
    }

    private void deliver(List<Map<String, Object>> rows) {
        try {
            if (rows.size() == 1) {
                deliverSingle(rows.get(0));
                return;
            }
            int version = rows.size() > 16 ? 2 : 1;
            String first = Support.str(rows.get(0), "url", ""),
                    target =
                            first.substring(0, first.lastIndexOf('/'))
                                    + (version == 2 ? "/batch-v2" : "/batch");
            List<Map<String, Object>> events = new ArrayList<>();
            for (var row : rows) {
                String url = Support.str(row, "url", "");
                events.add(
                        Map.of(
                                "id",
                                row.get("id"),
                                "kind",
                                url.substring(url.lastIndexOf('/') + 1),
                                "payload",
                                Support.object((byte[]) row.get("body"))));
            }
            var response =
                    send(target, Support.canonical(Map.of("version", version, "events", events)));
            if (Set.of(400, 404, 405, 409, 413, 422).contains(response.statusCode())
                    || response.headers()
                            .firstValue("x-callback-batch-split")
                            .orElse("")
                            .equals("true")) {
                for (var row : rows) deliverSingle(row);
                return;
            }
            boolean accepted = false;
            if (response.statusCode() == 200) {
                var ack = Support.object(response.body());
                Object ids = ack.get("accepted");
                accepted =
                        "received".equals(ack.get("result"))
                                && Support.integer(ack, "version", 0) == version
                                && ids instanceof List<?> list
                                && list.size() == rows.size()
                                && new HashSet<>(list)
                                        .equals(
                                                new HashSet<>(
                                                        rows.stream()
                                                                .map(r -> r.get("id"))
                                                                .toList()));
            }
            if (accepted)
                ledger.callbackReceipt(
                        db -> {
                            LocalLedger.execute(
                                    db,
                                    "DELETE FROM outbox WHERE id IN ("
                                            + String.join(
                                                    ",", Collections.nCopies(rows.size(), "?"))
                                            + ")",
                                    rows.stream().map(r -> r.get("id")).toArray());
                            return null;
                        });
            else
                ledger.callbackReceipt(
                        db -> {
                            var changes = new ArrayList<Object[]>();
                            for (var row : rows)
                                changes.add(
                                        new Object[] {
                                            LocalLedger.now()
                                                    + retryDelay(
                                                            row,
                                                            response.headers()
                                                                    .firstValue("Retry-After")
                                                                    .orElse("0")),
                                            row.get("id")
                                        });
                            LocalLedger.executeBatch(
                                    db,
                                    "UPDATE outbox SET failures=failures+1,due=? WHERE id=?",
                                    changes);
                            return null;
                        });
        } catch (Exception error) {
            for (var row : rows) {
                try {
                    complete(row, false, "0");
                } catch (Exception ignored) {
                }
            }
        } finally {
            for (var row : rows) inflight.remove((String) row.get("id"));
            deliveries.decrementAndGet();
        }
    }

    private void deliverSingle(Map<String, Object> row) throws Exception {
        var response = send(Support.str(row, "url", ""), (byte[]) row.get("body"));
        complete(
                row,
                response.statusCode() == 200,
                response.headers().firstValue("Retry-After").orElse("0"));
    }

    private void complete(Map<String, Object> row, boolean accepted, String retry) {
        double delay = retryDelay(row, retry);
        ledger.callbackReceipt(
                db -> {
                    if (accepted)
                        LocalLedger.execute(db, "DELETE FROM outbox WHERE id=?", row.get("id"));
                    else
                        LocalLedger.execute(
                                db,
                                "UPDATE outbox SET failures=failures+1,due=? WHERE id=?",
                                LocalLedger.now() + delay,
                                row.get("id"));
                    return null;
                });
    }

    private double retryDelay(Map<String, Object> row, String retry) {
        double after = 0;
        try {
            after = Double.parseDouble(retry);
            if (!Double.isFinite(after)) after = 0;
        } catch (NumberFormatException ignored) {
        }
        return Math.max(
                        Math.min(300, after),
                        Math.min(60, Math.pow(2, Support.integer(row, "failures", 0))))
                + ThreadLocalRandom.current().nextDouble(.25);
    }

    private void purge() {
        try {
            ledger.transaction(
                    db -> {
                        double now = LocalLedger.now();
                        var rows =
                                LocalLedger.query(
                                        db,
                                        "SELECT uuid FROM attempts WHERE state='ended' AND ended<?"
                                                + " AND payload!='{}' LIMIT 1000",
                                        now
                                                - settings.number(
                                                                "VOICE_SENSITIVE_RETENTION_DAYS",
                                                                90,
                                                                1,
                                                                36500)
                                                        * 86400);
                        for (var row : rows)
                            LocalLedger.execute(
                                    db,
                                    "UPDATE attempts SET payload='{}',result=? WHERE uuid=?",
                                    new String(
                                            Support.canonical(
                                                    Map.of(
                                                            "result",
                                                            "ended",
                                                            "provider_call_id",
                                                            row.get("uuid"))),
                                            StandardCharsets.UTF_8),
                                    row.get("uuid"));
                        LocalLedger.execute(
                                db,
                                "UPDATE audit SET detail='' WHERE id IN(SELECT id FROM (SELECT id"
                                        + " FROM audit WHERE at<? AND detail!='' LIMIT 1000) retained)",
                                now
                                        - settings.number(
                                                        "VOICE_AUDIT_RETENTION_DAYS", 180, 1, 36500)
                                                * 86400);
                        return null;
                    });
        } catch (Exception unavailable) {
        }
    }

    @Override
    public void close() {
        poll.shutdownNow();
        delivery.shutdown();
        try {
            if (!delivery.awaitTermination(15, TimeUnit.SECONDS)) delivery.shutdownNow();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            delivery.shutdownNow();
        }
    }

    private record BufferedResponse(HttpResponse<?> original, byte[] body)
            implements HttpResponse<byte[]> {
        public int statusCode() {
            return original.statusCode();
        }

        public HttpRequest request() {
            return original.request();
        }

        public Optional<HttpResponse<byte[]>> previousResponse() {
            return Optional.empty();
        }

        public HttpHeaders headers() {
            return original.headers();
        }

        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return original.sslSession();
        }

        public URI uri() {
            return original.uri();
        }

        public HttpClient.Version version() {
            return original.version();
        }
    }
}
