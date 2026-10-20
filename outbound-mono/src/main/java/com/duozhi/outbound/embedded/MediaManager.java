package com.duozhi.outbound.embedded;

import java.util.*;
import java.util.concurrent.*;

interface MediaManager extends AutoCloseable {
    final class Session {
        final String call, id, token, speechUrl, mediaUrl;
        final Map<String,Object> metadata;
        final long created = System.currentTimeMillis();
        final CompletableFuture<Void> started = new CompletableFuture<>(), terminated = new CompletableFuture<>();
        volatile String latest = "", error = "", playback = "";
        volatile boolean closing, connected, speaking, interrupting;
        volatile long lastModuleEvent;
        final Map<String,Map<String,Object>> operations = Collections.synchronizedMap(new LinkedHashMap<>());
        Session(Map<String,Object> data) {
            call = Support.required(data, "call_id", 128); id = Support.required(data, "session_id", 128); token = Support.required(data, "token", 256);
            speechUrl = Support.str(data, "speech_webhook_url", ""); mediaUrl = Support.str(data, "media_webhook_url", ""); metadata = Collections.unmodifiableMap(new LinkedHashMap<>(Support.map(data.get("metadata"))));
        }
        Map<String,Object> data() { return Map.of("call_id", call, "session_id", id, "token", token, "speech_webhook_url", speechUrl, "media_webhook_url", mediaUrl, "metadata", metadata); }
        Map<String,Object> state() { return Map.of("session_id", id, "started", started.isDone(), "terminated", terminated.isDone(), "latest_final_event_id", latest, "media_error_code", error); }
    }
    Session create(String call, String speechUrl, String mediaUrl, Map<String,Object> metadata) throws Exception;
    Session session(String call);
    Collection<Session> sessions();
    String url(Session session);
    String speak(String call, String text, String expected, String operation) throws Exception;
    void interrupt(String call, String expected) throws Exception;
    void module(String call, String kind, long timestamp) throws Exception;
    void closeSession(String call, boolean notify) throws Exception;
    boolean ready();
    int capacity();
    default Map<String,Number> metrics() { return Map.of(); }
    default void fence(String call, String expected, boolean closing) throws Exception {
        Session session = session(call); if (session == null || session.closing || session.terminated.isDone() || (expected != null && !session.latest.equals(expected))) throw new Support.Failure(409, "stale media session or speech generation");
        if (closing) session.closing = true;
    }
    default Map<String,Object> lookup(String call, String operation, String text) { return Map.of("confirmed", false); }
}
