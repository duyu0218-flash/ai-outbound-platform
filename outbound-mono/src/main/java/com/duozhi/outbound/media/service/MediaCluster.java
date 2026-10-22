package com.duozhi.outbound.media.service;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.dao.LocalLedger;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.voice.service.VoiceQuota;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Audio bypasses the controller; durable worker/epoch/session ownership fences every RPC. */
public final class MediaCluster implements MediaManager {
    public record Spec(String id, String endpoint, String wsBase, int capacity) {}

    public record Owner(Spec spec, String epoch, Session session) {}

    private final Settings settings;
    private final LocalLedger ledger;
    private final List<Spec> specs = new ArrayList<>();
    public final Map<String, Owner> owners = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> health = new ConcurrentHashMap<>();
    private final Map<String, Long> checked = new ConcurrentHashMap<>();
    private final Map<String, MediaWorkerRuntime> workers = new ConcurrentHashMap<>();
    private final NativeMedia.Events events;
    private final ScheduledExecutorService monitor;
    private final ExecutorService probes;

    public MediaCluster(Settings settings, LocalLedger ledger, NativeMedia.Events events)
            throws Exception {
        this.settings = settings;
        this.ledger = ledger;
        this.events = events;
        if (settings.secret("MEDIA_RPC_TOKEN").length() < 32)
            throw new IllegalArgumentException("independent media RPC credential required");
        Object raw = Support.JSON.readValue(settings.get("MEDIA_WORKERS_JSON", "[]"), Object.class);
        if (!(raw instanceof List<?> rows) || rows.isEmpty() || rows.size() > 16)
            throw new IllegalArgumentException("media workers must contain 1..16 processes");
        Set<String> ids = new HashSet<>(), endpoints = new HashSet<>();
        for (Object item : rows) {
            var row = Support.map(item);
            String id = Support.required(row, "id", 128),
                    endpoint = Support.required(row, "endpoint", 2048),
                    wsBase = Support.required(row, "ws_base", 2048);
            URI api = Support.endpoint(endpoint), ws = URI.create(wsBase);
            int capacity = (int) Support.integer(row, "capacity", 0);
            if (!api.getScheme().equals("http")
                    || !api.getHost().equals("127.0.0.1")
                    || api.getPort() <= 0
                    || api.getQuery() != null
                    || !Set.of("", "/").contains(api.getPath())
                    || !"ws".equals(ws.getScheme())
                    || !"127.0.0.1".equals(ws.getHost())
                    || ws.getPort() != api.getPort()
                    || ws.getUserInfo() != null
                    || ws.getQuery() != null
                    || ws.getFragment() != null
                    || !ws.getPath().replaceAll("/+$", "").equals("/v1/pipecat/media")
                    || capacity < 1
                    || capacity > 200
                    || !ids.add(id)
                    || !endpoints.add(endpoint))
                throw new IllegalArgumentException(
                        "media workers require unique identities and matching loopback HTTP/WS"
                                + " endpoints");
            specs.add(
                    new Spec(
                            id,
                            endpoint.replaceAll("/+$", ""),
                            wsBase.replaceAll("/+$", ""),
                            capacity));
        }
        if (specs.stream().mapToInt(Spec::capacity).sum()
                < settings.number("PIPECAT_MAX_ACTIVE_SESSIONS", 100, 1, 10000))
            throw new IllegalArgumentException("media roster capacity is below host limit");
        for (Spec spec : specs) {
            Settings local =
                    new Settings() {
                        @Override
                        public String get(String key, String fallback) {
                            return switch (key) {
                                case "MEDIA_WORKER_ID" -> spec.id;
                                case "MEDIA_WORKER_CAPACITY" -> Integer.toString(spec.capacity);
                                default -> settings.get(key, fallback);
                            };
                        }
                    };
            workers.put(spec.id, new MediaWorkerRuntime(local, this::control));
        }
        var saved =
                ledger.read(
                        db ->
                                LocalLedger.query(
                                        db, "SELECT * FROM media_owners WHERE state!='closed'"));
        for (var row : saved) {
            Spec spec =
                    specs.stream()
                            .filter(s -> s.id.equals(row.get("worker_id")))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "active media owner removed; drain before"
                                                            + " changing roster"));
            Session session =
                    new Session(
                            Support.object(
                                    row.get("session_json")
                                            .toString()
                                            .getBytes(StandardCharsets.UTF_8)));
            owners.put(session.call, new Owner(spec, row.get("epoch").toString(), session));
        }
        probes =
                Executors.newFixedThreadPool(
                        specs.size(),
                        r -> {
                            Thread t = new Thread(r, "media-worker-probe");
                            t.setDaemon(true);
                            return t;
                        });
        monitor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "media-worker-monitor");
                            t.setDaemon(true);
                            return t;
                        });
        refresh();
        monitor.scheduleWithFixedDelay(this::refresh, 250, 250, TimeUnit.MILLISECONDS);
    }

    private Map<String, Object> request(
            Spec spec, String path, Map<String, Object> payload, double timeout) throws Exception {
        MediaWorkerRuntime worker = workers.get(spec.id);
        String auth = "Bearer " + settings.secret("MEDIA_RPC_TOKEN");
        if (worker == null)
            throw new Support.Failure(503, "media worker acknowledgement unavailable");
        if (path.equals("/internal/state")) return worker.state(auth);
        if (path.equals("/internal/command")) return worker.command(payload, auth);
        throw new Support.Failure(404, "unsupported media operation");
    }

    private double ttl() {
        return settings.decimal("MEDIA_HEALTH_TTL_SEC", 2, .5, 30);
    }

    private boolean healthy(Spec spec) {
        return Boolean.TRUE.equals(health.getOrDefault(spec.id, Map.of()).get("ready"))
                && System.nanoTime() - checked.getOrDefault(spec.id, Long.MIN_VALUE / 2)
                        <= ttl() * 1_000_000_000L;
    }

    private void refresh() {
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (Spec spec : specs)
                tasks.add(
                        () -> {
                            long start = System.nanoTime();
                            Map<String, Object> result;
                            try {
                                result =
                                        request(
                                                spec,
                                                "/internal/state",
                                                null,
                                                Math.min(
                                                        ttl() / 2,
                                                        settings.decimal(
                                                                "MEDIA_RPC_TIMEOUT_SEC",
                                                                5,
                                                                .1,
                                                                60)));
                                if (!spec.id.equals(result.get("worker_id"))
                                        || Support.integer(result, "capacity", 0) != spec.capacity
                                        || !(result.get("sessions") instanceof Map)
                                        || Support.str(result, "epoch", "").isEmpty())
                                    throw new IllegalArgumentException();
                            } catch (Exception unavailable) {
                                result = Map.of("ready", false);
                            }
                            health.put(spec.id, result);
                            checked.put(spec.id, start);
                            for (var owner : owners.values())
                                if (owner.spec.id.equals(spec.id)) {
                                    Session session = owner.session;
                                    var state =
                                            Support.map(
                                                    Support.map(result.get("sessions"))
                                                            .get(session.call));
                                    if (!Boolean.TRUE.equals(result.get("ready"))
                                            || !owner.epoch.equals(result.get("epoch"))) {
                                        session.error = "MEDIA_WORKER_UNAVAILABLE";
                                        session.terminated.complete(null);
                                        session.started.complete(null);
                                    } else if (!state.isEmpty()) {
                                        if (!session.id.equals(state.get("session_id"))) {
                                            session.error = "MEDIA_WORKER_IDENTITY_CHANGED";
                                            session.terminated.complete(null);
                                        }
                                        if (Boolean.TRUE.equals(state.get("started")))
                                            session.started.complete(null);
                                        if (Boolean.TRUE.equals(state.get("terminated")))
                                            session.terminated.complete(null);
                                        session.latest =
                                                Support.str(
                                                        state,
                                                        "latest_final_event_id",
                                                        session.latest);
                                        session.error =
                                                Support.str(
                                                        state, "media_error_code", session.error);
                                    }
                                }
                            return null;
                        });
            for (var future : probes.invokeAll(tasks)) future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean ready() {
        return settings.flag("MEDIA_ALLOW_DEGRADED_ADMISSION", false)
                ? capacity() > 0
                : specs.stream().allMatch(this::healthy);
    }

    @Override
    public int capacity() {
        return Math.min(
                (int) settings.number("PIPECAT_MAX_ACTIVE_SESSIONS", 100, 1, 10000),
                specs.stream().filter(this::healthy).mapToInt(Spec::capacity).sum());
    }

    @Override
    public Session session(String call) {
        Owner owner = owners.get(call);
        return owner == null ? null : owner.session;
    }

    @Override
    public Collection<Session> sessions() {
        return owners.values().stream().map(Owner::session).toList();
    }

    @Override
    public Map<String, Number> metrics() {
        Map<String, Number> totals = new LinkedHashMap<>();
        for (var spec : specs)
            if (healthy(spec))
                Support.map(health.get(spec.id).get("metrics"))
                        .forEach(
                                (key, raw) -> {
                                    if (raw instanceof Number n
                                            && Double.isFinite(n.doubleValue())) {
                                        double previous = totals.getOrDefault(key, 0).doubleValue();
                                        totals.put(
                                                key,
                                                key.contains("_max_")
                                                        ? Math.max(previous, n.doubleValue())
                                                        : previous + n.doubleValue());
                                    }
                                });
        return totals;
    }

    private long used(Spec spec) {
        Set<String> known =
                new HashSet<>(
                        Support.map(health.getOrDefault(spec.id, Map.of()).get("sessions"))
                                .keySet());
        for (var owner : owners.values())
            if (owner.spec.id.equals(spec.id)) known.add(owner.session.call);
        return known.size();
    }

    @Override
    public Session create(
            String call, String speechUrl, String mediaUrl, Map<String, Object> metadata)
            throws Exception {
        Owner owner;
        synchronized (owners) {
            var existing = owners.get(call);
            if (existing != null) {
                if (!existing.session.metadata.equals(metadata)
                        || existing.session.closing
                        || existing.session.terminated.isDone())
                    throw new Support.Failure(
                            409, "previous media generation must be closed before reuse");
                return existing.session;
            }
            if (owners.size() >= settings.number("PIPECAT_MAX_ACTIVE_SESSIONS", 100, 1, 10000))
                throw new Support.Failure(503, "host media capacity reached");
            Spec spec =
                    specs.stream()
                            .filter(s -> healthy(s) && used(s) < s.capacity)
                            .min(
                                    Comparator.comparingDouble(
                                                    (Spec s) -> used(s) / (double) s.capacity)
                                            .thenComparing(Spec::id))
                            .orElseThrow(
                                    () ->
                                            new Support.Failure(
                                                    503, "no media process has capacity"));
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            Session session =
                    new Session(
                            Map.of(
                                    "call_id",
                                    call,
                                    "session_id",
                                    UUID.randomUUID().toString(),
                                    "token",
                                    Base64.getUrlEncoder().withoutPadding().encodeToString(secret),
                                    "speech_webhook_url",
                                    speechUrl,
                                    "media_webhook_url",
                                    mediaUrl,
                                    "metadata",
                                    metadata));
            owner = new Owner(spec, health.get(spec.id).get("epoch").toString(), session);
            ledger.transaction(
                    db -> {
                        LocalLedger.execute(
                                db,
                                "INSERT INTO media_owners VALUES (?,?,?,?,?) ON DUPLICATE KEY"
                                        + " UPDATE"
                                        + " worker_id=VALUES(worker_id),epoch=VALUES(epoch),session_json=VALUES(session_json),state=VALUES(state)",
                                call,
                                spec.id,
                                owner.epoch,
                                new String(Support.json(session.data()), StandardCharsets.UTF_8),
                                "pending");
                        return null;
                    });
            owners.put(call, owner);
        }
        rpc(owner, "create", Map.of("session", owner.session.data()));
        return owner.session;
    }

    private Owner required(String call) {
        Owner owner = owners.get(call);
        if (owner == null) throw new Support.Failure(404, "media session ended");
        return owner;
    }

    private Map<String, Object> rpc(Owner owner, String action, Map<String, Object> options)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("epoch", owner.epoch);
        payload.put("call_id", owner.session.call);
        payload.put("session_id", owner.session.id);
        payload.put("attempt", owner.session.metadata.get("attempt"));
        payload.put("operation_id", UUID.randomUUID().toString().replace("-", ""));
        payload.put(
                "expires_at",
                LocalLedger.now() + settings.decimal("MEDIA_RPC_TIMEOUT_SEC", 5, .1, 60));
        payload.putAll(options);
        return request(
                owner.spec,
                "/internal/command",
                payload,
                settings.decimal("MEDIA_RPC_TIMEOUT_SEC", 5, .1, 60));
    }

    @Override
    public String url(Session session) {
        return settings.get("PIPECAT_MEDIA_WS_BASE", "").replaceAll("/+$", "")
                + "/"
                + session.token;
    }

    @Override
    public String speak(String call, String text, String expected, String operation)
            throws Exception {
        Owner owner = required(call);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        payload.put("expected_speech_event_id", expected);
        payload.put(
                "operation_id",
                operation.isEmpty() && expected != null
                        ? Support.hex(
                                Support.sha(
                                        (owner.session.id + ":speak:" + expected)
                                                .getBytes(StandardCharsets.UTF_8)))
                        : operation.isEmpty() ? UUID.randomUUID().toString() : operation);
        return rpc(owner, "speak", payload).get("playback_id").toString();
    }

    @Override
    public void fence(String call, String expected, boolean closing) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("expected_speech_event_id", expected);
        payload.put("closing", closing);
        rpc(required(call), "fence", payload);
    }

    @Override
    public void interrupt(String call, String expected) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("expected_speech_event_id", expected);
        rpc(required(call), "interrupt", payload);
    }

    @Override
    public void module(String call, String kind, long timestamp) throws Exception {
        Owner owner = owners.get(call);
        if (owner != null) rpc(owner, "module", Map.of("kind", kind, "timestamp_us", timestamp));
    }

    @Override
    public Map<String, Object> lookup(String call, String operation, String text) {
        try {
            return rpc(
                    required(call),
                    "lookup-playback",
                    Map.of("operation_id", operation, "text", text));
        } catch (Exception error) {
            return Map.of("confirmed", false);
        }
    }

    @Override
    public void closeSession(String call, boolean notify) throws Exception {
        Owner owner = owners.get(call);
        if (owner == null) return;
        synchronized (owner.session) {
            if (owners.get(call) != owner) return;
            try {
                rpc(owner, "close", Map.of("notify", notify));
            } catch (Exception error) {
                String epoch =
                        Support.str(health.getOrDefault(owner.spec.id, Map.of()), "epoch", "");
                if (epoch.isEmpty() || epoch.equals(owner.epoch)) throw error;
            }
            ledger.transaction(
                    db -> {
                        LocalLedger.execute(
                                db,
                                "DELETE FROM media_owners WHERE call_id=? AND epoch=?",
                                call,
                                owner.epoch);
                        return null;
                    });
            owners.remove(call, owner);
            owner.session.terminated.complete(null);
            owner.session.started.complete(null);
        }
    }

    public void validate(Map<String, Object> event) {
        Owner owner = owners.get(Support.str(event, "call_id", ""));
        var payload = Support.map(event.get("payload"));
        if (owner == null
                || !owner.spec.id.equals(event.get("worker_id"))
                || !owner.epoch.equals(event.get("epoch"))
                || !owner.session.id.equals(event.get("session_id"))
                || !owner.session.call.equals(payload.get("call_id"))
                || !owner.session.id.equals(payload.get("provider_session_id"))
                || Support.integer(payload, "attempt", 0)
                        != Support.integer(owner.session.metadata, "attempt", 0)
                || !List.of(owner.session.speechUrl, owner.session.mediaUrl)
                        .contains(Support.str(event, "url", "")))
            throw new Support.Failure(409, "stale or unregistered media callback");
    }

    public NativeMedia manager(String token) {
        for (var worker : workers.values()) if (worker.manager.owns(token)) return worker.manager;
        return null;
    }

    private Map<String, Object> control(String path, Map<String, Object> body) throws Exception {
        if (path.equals("/v1/internal/media-events")) {
            validate(body);
            events.post(Support.required(body, "url", 8192), Support.map(body.get("payload")));
            return Map.of("accepted", true);
        }
        if (!path.equals("/v1/internal/voice-permits"))
            throw new Support.Failure(404, "unsupported media operation");
        String action = Support.required(body, "action", 10),
                call = Support.required(body, "call_id", 128),
                id = Support.required(body, "permit_id", 128),
                worker = Support.str(body, "worker_id", ""),
                epoch = Support.str(body, "epoch", ""),
                session = Support.required(body, "session_id", 128),
                kind = Support.required(body, "kind", 4);
        long attempt = Support.integer(body, "attempt", 0);
        if (!Set.of("acquire", "release").contains(action)
                || attempt < 1
                || !Set.of("asr", "tts").contains(kind))
            throw new Support.Failure(422, "invalid voice permit request");
        if (action.equals("acquire")) {
            Owner owner = owners.get(call);
            if (owner == null
                    || !owner.spec.id.equals(worker)
                    || !owner.epoch.equals(epoch)
                    || !owner.session.id.equals(session)
                    || Support.integer(owner.session.metadata, "attempt", 0) != attempt)
                throw new Support.Failure(409, "stale media worker epoch");
        }
        String owner =
                new String(Support.json(List.of(worker, epoch, session)), StandardCharsets.UTF_8);
        VoiceQuota quota = new VoiceQuota(settings, ledger);
        ledger.transaction(
                db -> {
                    if (action.equals("acquire")) quota.acquire(db, id, kind, call, attempt, owner);
                    else quota.release(db, id, owner, Boolean.TRUE.equals(body.get("confirmed")));
                    return null;
                });
        return Map.of("accepted", true);
    }

    @Override
    public void close() throws Exception {
        monitor.shutdownNow();
        probes.shutdownNow();
        Exception failure = null;
        for (var worker : workers.values())
            try {
                worker.close();
            } catch (Exception error) {
                failure = error;
            }
        if (failure != null) throw failure;
    }
}
