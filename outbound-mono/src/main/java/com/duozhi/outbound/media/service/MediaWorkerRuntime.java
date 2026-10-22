package com.duozhi.outbound.media.service;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.dao.LocalLedger;
import com.duozhi.outbound.integration.support.Support;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

public class MediaWorkerRuntime {
    final NativeMedia manager;
    final String epoch = UUID.randomUUID().toString().replace("-", "");
    private final Settings settings;
    private final String worker;
    private final int capacity;
    private final HttpClient http = Support.client();
    private final Control control;

    interface Control {
        Map<String, Object> post(String path, Map<String, Object> body) throws Exception;
    }

    private final Map<String, String> closed = new LinkedHashMap<>();
    private final Map<String, MediaManager.Session> registered =
            new java.util.concurrent.ConcurrentHashMap<>();

    MediaWorkerRuntime(Settings settings, Control control) {
        this.settings = settings;
        worker = settings.get("MEDIA_WORKER_ID", "");
        capacity = (int) settings.number("MEDIA_WORKER_CAPACITY", 50, 1, 200);
        if (worker.isEmpty() || settings.secret("MEDIA_RPC_TOKEN").length() < 32)
            throw new IllegalArgumentException(
                    "media worker identity and independent RPC credential required");
        this.control = control;
        manager = new NativeMedia(settings, this::event, this::permit, capacity);
    }

    private void auth(String auth) {
        Support.auth(settings, "MEDIA_RPC_TOKEN", auth, false);
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("status", "ok", "runtime", "java-native");
    }

    @GetMapping("/readyz")
    Map<String, Object> ready(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        auth(auth);
        if (!manager.ready()) throw new Support.Failure(503, "media runtime unavailable");
        return Map.of("status", "ready");
    }

    @GetMapping("/internal/state")
    Map<String, Object> state(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        auth(auth);
        Map<String, Object> sessions = new LinkedHashMap<>();
        for (var session : registered.values()) sessions.put(session.call, session.state());
        return Map.of(
                "worker_id",
                worker,
                "epoch",
                epoch,
                "ready",
                manager.ready(),
                "capacity",
                capacity,
                "metrics",
                manager.metrics(),
                "sessions",
                sessions);
    }

    @PostMapping("/internal/command")
    Map<String, Object> command(
            @RequestBody Map<String, Object> command,
            @RequestHeader(value = "Authorization", required = false) String auth)
            throws Exception {
        auth(auth);
        if (!epoch.equals(command.get("epoch")))
            throw new Support.Failure(409, "worker epoch changed");
        if (command.get("expires_at") instanceof Number n
                && (!Double.isFinite(n.doubleValue()) || LocalLedger.now() > n.doubleValue()))
            throw new Support.Failure(409, "media command expired");
        String action = Support.required(command, "action", 128),
                call = Support.required(command, "call_id", 128),
                id = Support.required(command, "session_id", 128);
        long attempt = Support.integer(command, "attempt", 0);
        if (action.equals("create"))
            synchronized (closed) {
                var existing = registered.get(call);
                if (existing != null) {
                    if (!existing.id.equals(id)
                            || Support.integer(existing.metadata, "attempt", 0) != attempt)
                        throw new Support.Failure(409, "call already has another media generation");
                    return Map.of("created", true);
                }
                if (closed.containsKey(id))
                    throw new Support.Failure(409, "media generation was closed");
                var data = Support.map(command.get("session"));
                if (!call.equals(data.get("call_id"))
                        || !id.equals(data.get("session_id"))
                        || Support.integer(Support.map(data.get("metadata")), "attempt", 0)
                                != attempt
                        || attempt < 1) throw new Support.Failure(409, "media ownership mismatch");
                if (registered.size() >= capacity)
                    throw new Support.Failure(503, "media process at capacity");
                var created = manager.register(new MediaManager.Session(data));
                registered.put(call, created);
                return Map.of("created", true);
            }
        var session = registered.get(call);
        if (session == null) {
            if (action.equals("close")) {
                tombstone(id, call);
                return Map.of("closed", true);
            }
            throw new Support.Failure(404, "unknown media session");
        }
        {
            if (!session.id.equals(id)
                    || Support.integer(session.metadata, "attempt", 0) != attempt)
                throw new Support.Failure(409, "stale media session");
            String expected =
                    command.get("expected_speech_event_id") == null
                            ? null
                            : String.valueOf(command.get("expected_speech_event_id"));
            if (expected != null && !session.latest.equals(expected))
                throw new Support.Failure(409, "stale speech generation");
            return switch (action) {
                case "lookup-playback" ->
                        manager.lookup(
                                call,
                                Support.str(command, "operation_id", ""),
                                Support.str(command, "text", ""));
                case "speak" ->
                        Map.of(
                                "playback_id",
                                manager.speak(
                                        call,
                                        Support.required(command, "text", 50000),
                                        expected,
                                        Support.str(command, "operation_id", "")));
                case "fence" -> {
                    manager.fence(call, expected, Boolean.TRUE.equals(command.get("closing")));
                    yield Map.of("ok", true);
                }
                case "interrupt" -> {
                    manager.interrupt(call, expected);
                    yield Map.of("ok", true);
                }
                case "module" -> {
                    manager.module(
                            call,
                            Support.str(command, "kind", ""),
                            Support.integer(command, "timestamp_us", 0));
                    yield Map.of("ok", true);
                }
                case "close" -> {
                    manager.closeSession(call, !Boolean.FALSE.equals(command.get("notify")));
                    registered.remove(call, session);
                    tombstone(id, call);
                    yield Map.of("closed", true);
                }
                default -> throw new Support.Failure(400, "unsupported media operation");
            };
        }
    }

    private void tombstone(String id, String call) {
        synchronized (closed) {
            closed.put(id, call);
            while (closed.size() > 1000) closed.remove(closed.keySet().iterator().next());
        }
    }

    private Map<String, Object> post(String path, Map<String, Object> body) throws Exception {
        return control.post(path, body);
    }

    private void event(String url, Map<String, Object> payload) throws Exception {
        var session = registered.get(Support.str(payload, "call_id", ""));
        if (session == null || !session.id.equals(payload.get("provider_session_id")))
            throw new Support.Failure(409, "media event has no matching generation");
        Map<String, Object> body =
                Map.of(
                        "worker_id",
                        worker,
                        "epoch",
                        epoch,
                        "call_id",
                        session.call,
                        "session_id",
                        session.id,
                        "url",
                        url,
                        "payload",
                        payload);
        for (int attempt = 0; ; attempt++) {
            try {
                post("/v1/internal/media-events", body);
                return;
            } catch (Exception error) {
                if (attempt == 2) {
                    session.error = "MEDIA_CALLBACK_UNAVAILABLE";
                    session.terminated.complete(null);
                    throw error;
                }
                Thread.sleep(100L * (attempt + 1));
            }
        }
    }

    private void permit(
            MediaManager.Session session,
            String id,
            String kind,
            boolean acquire,
            boolean confirmed)
            throws Exception {
        if (!settings.flag("VOICE_QUOTA_ENABLED", false)) return;
        post(
                "/v1/internal/voice-permits",
                Map.of(
                        "permit_id",
                        id,
                        "action",
                        acquire ? "acquire" : "release",
                        "kind",
                        kind,
                        "call_id",
                        session.call,
                        "attempt",
                        Support.integer(session.metadata, "attempt", 0),
                        "worker_id",
                        worker,
                        "epoch",
                        epoch,
                        "session_id",
                        session.id,
                        "confirmed",
                        confirmed));
    }

    @PreDestroy
    void close() throws Exception {
        manager.close();
    }
}
