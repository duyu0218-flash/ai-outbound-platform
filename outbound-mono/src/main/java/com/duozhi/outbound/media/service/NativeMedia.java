package com.duozhi.outbound.media.service;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.recording.storage.S3Store;
import com.duozhi.outbound.voiceai.service.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.web.socket.*;

/** Java PCM media runtime, preserving the former Pipecat HTTP/WS boundary. */
public final class NativeMedia implements MediaManager {
    public interface Events {
        void post(String url, Map<String, Object> payload) throws Exception;
    }

    public interface Permits {
        void change(Session session, String id, String kind, boolean acquire, boolean confirmed)
                throws Exception;
    }

    private final Settings settings;
    private final VoiceResources resources;
    private final Events events;
    private final Permits permits;
    private final Map<String, Session> calls = new ConcurrentHashMap<>();
    private final Map<String, Session> tokens = new ConcurrentHashMap<>();
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();
    private final ExecutorService work;
    private final ExecutorService asrEvents;
    private final ExecutorService lifecycle;
    private final Map<Session, String> pendingClosures = new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlines;
    private final HttpClient http = Support.client();
    private final int rate, limit;
    private final DirectAudioDispatcher ingress;
    private final boolean voismart;
    private final LongAdder inputFrames = new LongAdder(), firstAudioCount = new LongAdder();
    private final DoubleAdder firstAudioSeconds = new DoubleAdder();
    private final DoubleAccumulator arrivalGap = new DoubleAccumulator(Math::max, 0),
            firstAudioMax = new DoubleAccumulator(Math::max, 0);

    public NativeMedia(Settings settings, Events events, Permits permits, int limit) {
        this.settings = settings;
        resources = new VoiceResources(settings);
        this.events = events;
        this.permits = permits;
        this.limit = limit;
        ingress = new DirectAudioDispatcher(limit);
        rate = (int) settings.number("PIPECAT_SAMPLE_RATE", 8000, 8000, 48000);
        if (!Set.of(8000, 16000, 24000, 48000).contains(rate)
                || settings.number("PIPECAT_CHANNELS", 1, 1, 1) != 1)
            throw new IllegalArgumentException("invalid mono PCM configuration");
        String provider = settings.get("PIPECAT_STT_PROVIDER", "openai-realtime");
        if (!Set.of("aliyun-nls", "openai-realtime").contains(provider))
            throw new IllegalArgumentException("unsupported speech provider");
        if (!settings.get("PIPECAT_TTS_PROVIDER", "openai").equals("openai"))
            throw new IllegalArgumentException("unsupported synthesis provider");
        if (!settings.flag("VOICE_AI_PROFILES_ENABLED", false)
                && settings.secret("PIPECAT_OPENAI_API_KEY").isEmpty())
            throw new IllegalArgumentException("speech synthesis credential required");
        if (provider.equals("aliyun-nls")
                && (settings.secret("ALIYUN_NLS_TOKEN").isEmpty()
                        || settings.get("ALIYUN_NLS_APPKEY", "").isEmpty()
                        || !Set.of(8000, 16000).contains(rate)))
            throw new IllegalArgumentException(
                    "Alibaba NLS requires appkey, token and 8/16 kHz PCM");
        String protocol = settings.get("PIPECAT_MEDIA_PROTOCOL", "raw_pcm");
        if (!Set.of("raw_pcm", "voismart").contains(protocol))
            throw new IllegalArgumentException("invalid PCM transport protocol");
        voismart = protocol.equals("voismart");
        int speechThreads = Math.min(128, Math.max(4, limit));
        var synthesis =
                new ThreadPoolExecutor(
                        speechThreads,
                        speechThreads,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(Math.max(32, limit * 2)),
                        r -> {
                            Thread t = new Thread(r, "java-tts");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        synthesis.allowCoreThreadTimeOut(true);
        work = synthesis;
        int eventThreads = Math.min(64, Math.max(4, limit));
        var receive =
                new ThreadPoolExecutor(
                        eventThreads,
                        eventThreads,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(Math.max(32, limit * 4)),
                        r -> {
                            Thread t = new Thread(r, "java-asr-events");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        receive.allowCoreThreadTimeOut(true);
        asrEvents = receive;
        int lifecycleThreads = Math.min(64, Math.max(4, limit));
        var connections =
                new ThreadPoolExecutor(
                        lifecycleThreads,
                        lifecycleThreads,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(Math.max(32, limit * 2)),
                        r -> {
                            Thread t = new Thread(r, "mono-media-lifecycle");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        connections.allowCoreThreadTimeOut(true);
        lifecycle = connections;
        deadlines =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "media-deadlines");
                            t.setDaemon(true);
                            return t;
                        });
        deadlines.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public synchronized Session create(
            String call, String speechUrl, String mediaUrl, Map<String, Object> metadata) {
        var previous = calls.get(call);
        if (previous != null) {
            if (!previous.metadata.equals(metadata)
                    || previous.closing
                    || previous.terminated.isDone())
                throw new Support.Failure(
                        409, "previous media generation must be closed before reuse");
            return previous;
        }
        byte[] token = new byte[32];
        new SecureRandom().nextBytes(token);
        return register(
                new Session(
                        Map.of(
                                "call_id",
                                call,
                                "session_id",
                                UUID.randomUUID().toString(),
                                "token",
                                Base64.getUrlEncoder().withoutPadding().encodeToString(token),
                                "speech_webhook_url",
                                speechUrl,
                                "media_webhook_url",
                                mediaUrl,
                                "metadata",
                                metadata)));
    }

    synchronized Session register(Session session) {
        Session previous = calls.get(session.call);
        if (previous != null) {
            if (!previous.id.equals(session.id) || !previous.metadata.equals(session.metadata))
                throw new Support.Failure(409, "media ownership mismatch");
            return previous;
        }
        if (calls.size() >= limit) throw new Support.Failure(503, "media process at capacity");
        if (tokens.containsKey(session.token))
            throw new Support.Failure(409, "media token conflict");
        calls.put(session.call, session);
        tokens.put(session.token, session);
        return session;
    }

    @Override
    public Session session(String call) {
        return calls.get(call);
    }

    @Override
    public Collection<Session> sessions() {
        return List.copyOf(calls.values());
    }

    @Override
    public String url(Session session) {
        return settings.get("PIPECAT_MEDIA_WS_BASE", "").replaceAll("/+$", "")
                + "/"
                + session.token;
    }

    @Override
    public boolean ready() {
        return !work.isShutdown();
    }

    @Override
    public int capacity() {
        return limit;
    }

    @Override
    public Map<String, Number> metrics() {
        return Map.of(
                "pcm_input_frames_total",
                inputFrames.sum(),
                "pcm_dropped_frames_total",
                ingress.dropped(),
                "pcm_arrival_gap_max_seconds",
                arrivalGap.get(),
                "tts_first_audio_count",
                firstAudioCount.sum(),
                "tts_first_audio_seconds_sum",
                firstAudioSeconds.sum(),
                "tts_first_audio_max_seconds",
                firstAudioMax.get(),
                "ai_active_sessions",
                resources.usage().getOrDefault("ai", 0));
    }

    boolean owns(String token) {
        return tokens.containsKey(token);
    }

    public void enqueueAudio(WebSocketSession socket, ByteBuffer pcm) {
        ingress.offer(socket.getId(), pcm);
    }

    void connect(WebSocketSession socket, String token) throws Exception {
        Stream stream = claim(socket, token);
        if (stream != null) start(stream);
    }

    public void connectAsync(WebSocketSession socket, String token) throws Exception {
        Stream stream = claim(socket, token);
        if (stream == null) return;
        try {
            lifecycle.execute(
                    () -> {
                        try {
                            start(stream);
                        } catch (Exception error) {
                            /* start already fenced and scheduled cleanup. */
                        }
                    });
        } catch (RejectedExecutionException full) {
            requestFailure(stream.session, "MEDIA_PROCESS_OVERLOADED");
        }
    }

    private Stream claim(WebSocketSession socket, String token) throws Exception {
        Session session = tokens.get(token);
        if (session == null) {
            socket.close(new CloseStatus(4404, "unknown media session"));
            return null;
        }
        if (session.closing || session.terminated.isDone() || session.connected) {
            socket.close(new CloseStatus(4409, "media generation already claimed"));
            return null;
        }
        Stream stream;
        synchronized (session) {
            if (session.closing || session.terminated.isDone() || session.connected) {
                socket.close(new CloseStatus(4409, "media generation already claimed"));
                return null;
            }
            if (System.currentTimeMillis() - session.created
                    > settings.decimal("PIPECAT_MEDIA_CONNECT_TIMEOUT_SEC", 15, .1, 300) * 1000) {
                socket.close(new CloseStatus(4408, "expired media session"));
                requestFailure(session, "MEDIA_CONNECT_TIMEOUT");
                return null;
            }
            session.connected = true;
            stream = new Stream(session, socket);
            streams.put(session.call, stream);
            socket.setBinaryMessageSizeLimit(65536);
            socket.setTextMessageSizeLimit(65536);
        }
        ingress.register(
                socket.getId(),
                frame -> {
                    // Startup buffering stays in the existing bounded per-socket PCM queue.
                    session.started.get(
                            (long)
                                    (settings.decimal("ALIYUN_NLS_CONNECT_TIMEOUT_SEC", 8, .1, 300)
                                                    * 2000
                                            + 1000),
                            TimeUnit.MILLISECONDS);
                    audio(socket, frame);
                },
                error -> requestFailure(session, "ASR_CONNECTION_ERROR"));
        return stream;
    }

    private void start(Stream stream) throws Exception {
        Session session = stream.session;
        // Provider acknowledgement must not hold the session monitor needed by close/fencing.
        try {
            stream.connectAsr();
            synchronized (session) {
                if (!session.closing && !session.terminated.isDone()) {
                    session.started.complete(null);
                    media(session, "listening", "", "");
                }
            }
        } catch (Exception error) {
            requestFailure(session, "ASR_CONNECTION_ERROR");
            throw error;
        }
    }

    void audio(WebSocketSession socket, ByteBuffer pcm) throws Exception {
        Stream stream =
                streams.values().stream()
                        .filter(s -> s.socket.getId().equals(socket.getId()))
                        .findFirst()
                        .orElse(null);
        if (stream == null || stream.session.closing || stream.session.terminated.isDone()) return;
        if (pcm.remaining() % 2 != 0 || pcm.remaining() > 65536)
            throw new Support.Failure(400, "invalid PCM frame");
        long now = System.nanoTime(), previous = stream.lastPcmAt;
        stream.lastPcmAt = now;
        inputFrames.increment();
        if (previous != 0) arrivalGap.accumulate((now - previous) / 1_000_000_000.0);
        stream.audio(pcm);
    }

    public void disconnected(WebSocketSession socket) {
        ingress.disconnect(socket.getId());
        for (var stream : streams.values())
            if (stream.socket.getId().equals(socket.getId()) && !stream.session.closing)
                requestFailure(stream.session, "MEDIA_DISCONNECTED");
    }

    private void requestFailure(Session session, String code) {
        // Fence without waiting for a media callback that may hold the session monitor.
        if (session.closing || !session.terminated.complete(null)) return;
        session.error = code;
        session.closing = true;
        session.started.complete(null);
        tokens.remove(session.token, session);
        Stream stream = streams.get(session.call);
        if (stream != null && stream.session == session)
            stream.asrStarted.completeExceptionally(new IOException("media generation terminated"));
        pendingClosures.put(session, code);
        try {
            lifecycle.execute(() -> finishPendingClose(session, code));
        } catch (RejectedExecutionException full) {
            /* The bounded live-session map is drained by media-deadlines. */
        }
    }

    private void finishPendingClose(Session session, String code) {
        if (pendingClosures.remove(session, code)) fail(session, code);
    }

    @Override
    public String speak(String call, String text, String expected, String operation)
            throws Exception {
        return respond(call, text, expected, operation, false);
    }

    @Override
    public String respond(
            String call, String text, String expected, String operation, boolean modelReply)
            throws Exception {
        return respond(call, text, expected, operation, modelReply, Map.of());
    }

    @Override
    public String respond(
            String call,
            String text,
            String expected,
            String operation,
            boolean modelReply,
            Map<String, Object> context)
            throws Exception {
        Session session = required(call);
        synchronized (session) {
            fence(call, expected, false);
            String digest =
                    Support.hex(
                            Support.sha(
                                    Support.canonical(Map.of("text", text, "context", context))));
            if (!operation.isEmpty() && session.operations.containsKey(operation)) {
                var previous = session.operations.get(operation);
                if (!digest.equals(previous.get("digest"))
                        || modelReply != Boolean.TRUE.equals(previous.get("model_reply")))
                    throw new Support.Failure(409, "reply operation payload changed");
                return previous.get("playback_id").toString();
            }
            if (voismart
                    && (!session.playback.isEmpty() || session.speaking || session.interrupting))
                throw new Support.Failure(409, "previous module playout has not drained");
            Stream stream = streams.get(call);
            if (stream == null) throw new Support.Failure(503, "media socket has not connected");
            String playback = UUID.randomUUID().toString();
            var response = new ResponseScope();
            long generation = stream.coordinator.begin(response);
            session.playback = playback;
            stream.response = response;
            stream.context = Map.copyOf(context);
            stream.generation = generation;
            long requested = System.nanoTime();
            Future<?> task;
            try {
                task =
                        work.submit(
                                () -> {
                                    try {
                                        synthesize(stream, text, generation, requested, modelReply);
                                    } catch (Exception error) {
                                        if (stream.generation == generation && !session.closing) {
                                            stream.coordinator.interrupt();
                                            fail(session, "TTS_REQUEST_FAILED");
                                        }
                                    }
                                });
            } catch (RejectedExecutionException full) {
                stream.coordinator.interrupt();
                session.playback = "";
                throw new Support.Failure(503, "voice response worker overloaded");
            }
            stream.tts = task;
            if (!operation.isEmpty()) {
                session.operations.put(
                        operation,
                        Map.of(
                                "digest",
                                digest,
                                "text_digest",
                                Support.hex(Support.sha(text.getBytes(StandardCharsets.UTF_8))),
                                "playback_id",
                                playback,
                                "model_reply",
                                modelReply));
                while (session.operations.size() > 64)
                    session.operations.remove(session.operations.keySet().iterator().next());
            }
            return playback;
        }
    }

    @Override
    public Map<String, Object> lookup(String call, String operation, String text) {
        Session session = session(call);
        if (session == null) return Map.of("confirmed", false);
        synchronized (session) {
            var previous = session.operations.get(operation);
            if (previous == null
                    || !Support.hex(Support.sha(text.getBytes(StandardCharsets.UTF_8)))
                            .equals(previous.getOrDefault("text_digest", previous.get("digest"))))
                return Map.of("confirmed", false);
            return Map.of("confirmed", true, "playback_id", previous.get("playback_id"));
        }
    }

    @Override
    public void interrupt(String call, String expected) throws Exception {
        Session session = required(call);
        Stream stream = streams.get(call);
        synchronized (session) {
            fence(call, expected, false);
            session.interrupting = true;
            if (stream != null) {
                stream.coordinator.interrupt();
                stream.generation++;
                if (stream.tts != null) stream.tts.cancel(true);
                if (stream.vendor != null
                        && "realtime".equals(stream.profile.get("route"))
                        && stream.engineReady.isDone()) stream.vendor.interrupt();
                if (voismart) stream.text(Map.of("type", "input_audio_buffer.speech_started"));
            }
            session.playback = "";
        }
        try {
            if (voismart && session.speaking) {
                long until = System.nanoTime() + 3_000_000_000L;
                while (session.speaking && System.nanoTime() < until) Thread.sleep(10);
                if (session.speaking)
                    throw new Support.Failure(409, "module interruption was not confirmed");
            }
            media(session, "interrupted", "", "");
            if (stream != null) stream.restartRealtime();
        } finally {
            session.interrupting = false;
        }
    }

    @Override
    public void module(String call, String kind, long timestamp) throws Exception {
        Session session = session(call);
        if (session == null || !voismart) return;
        synchronized (session) {
            if (timestamp != 0 && timestamp <= session.lastModuleEvent) return;
            if (timestamp != 0) session.lastModuleEvent = timestamp;
            if (kind.equals("openai_speech_start")) {
                session.speaking = true;
                media(session, "speaking", session.playback, "");
            } else if (kind.equals("openai_speech_stop")) {
                session.speaking = false;
                session.playback = "";
                Stream stream = streams.get(call);
                if (stream != null
                        && Boolean.TRUE.equals(stream.coordinator.evidence().get("active"))
                        && !session.interrupting) {
                    String heard = stream.coordinator.drained(stream.generation, false);
                    // This legacy module event has no playback/epoch correlation. It cannot
                    // establish a played fact; a correlated acknowledgement uses acknowledge().
                    if (!heard.isBlank()) {
                        stream.remember("助手已播放：" + heard);
                        replyTranscript(session, heard, stream.generation);
                    }
                }
                media(session, "listening", "", "");
            } else if (Set.of("error", "disconnect").contains(kind))
                fail(session, kind.equals("error") ? "MEDIA_MODULE_ERROR" : "MEDIA_DISCONNECTED");
        }
    }

    public Map<String, Object> evidence(String call) {
        Stream stream = streams.get(call);
        return stream == null ? Map.of() : stream.coordinator.evidence();
    }

    public void acknowledge(String call, String playback, long generation, long samples)
            throws Exception {
        Session session = required(call);
        Stream stream = streams.get(call);
        synchronized (session) {
            if (stream == null
                    || !session.playback.equals(playback)
                    || generation != stream.generation
                    || samples < 0
                    || stream.generationDone != generation
                    || samples == 0
                    || samples
                            != ((Number) stream.coordinator.evidence().get("sent_samples"))
                                    .longValue())
                throw new Support.Failure(409, "stale or incomplete playback acknowledgement");
            String heard = stream.coordinator.drained(generation, true);
            stream.disclosurePlayed = stream.disclosureSent;
            session.speaking = false;
            session.playback = "";
            if (!heard.isBlank()) {
                stream.remember("助手已播放：" + heard);
                replyTranscript(session, heard, generation);
            }
            media(session, "listening", "", "");
        }
    }

    private Session required(String call) {
        Session session = calls.get(call);
        if (session == null || session.closing || session.terminated.isDone())
            throw new Support.Failure(409, "media generation is no longer active");
        return session;
    }

    private void fail(Session session, String code) {
        session.error = code;
        session.terminated.complete(null);
        session.started.complete(null);
        try {
            closeSession(session, true);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void closeSession(String call, boolean notify) throws Exception {
        closeSession(calls.get(call), notify);
    }

    private void closeSession(Session session, boolean notify) throws Exception {
        if (session == null) return;
        pendingClosures.remove(session);
        synchronized (session) {
            session.closing = true;
            session.terminated.complete(null);
            session.started.complete(null);
            tokens.remove(session.token, session);
        }
        Stream stream = streams.get(session.call);
        if (stream != null && stream.session == session && streams.remove(session.call, stream)) {
            ingress.disconnect(stream.socket.getId());
            stream.close();
        }
        if (notify) media(session, "closed", "", session.error);
        calls.remove(session.call, session);
    }

    private void expire() {
        pendingClosures.forEach(this::finishPendingClose);
        for (var session : calls.values()) {
            var live = streams.get(session.call);
            if (live != null && live.vendor != null) {
                try {
                    live.vendor.check();
                } catch (Exception failed) {
                    requestFailure(session, "VOICE_AI_PROVIDER_FAILED");
                    continue;
                }
            }
            double age = (System.currentTimeMillis() - session.created) / 1000.0;
            if ((!session.connected
                            && age
                                    > settings.decimal(
                                            "PIPECAT_MEDIA_CONNECT_TIMEOUT_SEC", 15, .1, 300))
                    || age > settings.number("PIPECAT_SESSION_TIMEOUT_SEC", 300, 30, 86400))
                fail(
                        session,
                        session.connected ? "MEDIA_SESSION_TIMEOUT" : "MEDIA_CONNECT_TIMEOUT");
        }
    }

    void media(Session session, String state, String playback, String error) throws Exception {
        if (session.mediaUrl.isEmpty()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("call_id", session.call);
        payload.put("provider_session_id", session.id);
        payload.put(
                "event_id", "pipecat:" + session.id + ":media:" + state + ":" + UUID.randomUUID());
        payload.put("event_sequence", System.currentTimeMillis() * 1000);
        payload.put("state", state);
        payload.put("playback_id", playback.isEmpty() ? null : playback);
        payload.put("codec", "pcm_s16le");
        payload.put("sample_rate", rate);
        payload.put("channel_count", 1);
        payload.put("provider", "java-native");
        Stream stream = streams.get(session.call);
        if (stream != null) {
            payload.put("voice_evidence", stream.coordinator.evidence());
            payload.put("answer_class", stream.classifier.answer().name().toLowerCase(Locale.ROOT));
            payload.put("ai_disclosure_sent", stream.disclosureSent);
            payload.put("ai_disclosure_played", stream.disclosurePlayed);
        }
        payload.put("attempt", session.metadata.get("attempt"));
        payload.put("error_code", error.isEmpty() ? null : error);
        events.post(session.mediaUrl, payload);
    }

    void transcript(Session session, String text, String id, Map<String, Object> details)
            throws Exception {
        if (text.isBlank() || session.closing) return;
        var policy = Support.map(session.metadata.get("scenario_policy"));
        boolean playing = !session.playback.isEmpty() || session.speaking;
        boolean urgent =
                Set.of("stop_contact", "wrong_person", "handoff", "rejected", "end")
                        .contains(com.duozhi.outbound.ai.service.DialogueRules.classify(text));
        long min = Support.integer(policy, "min_interrupt_chars", 1);
        if (playing
                && !urgent
                && (Boolean.FALSE.equals(policy.get("allow_interruptions"))
                        || text.strip().length() < min)) return;
        if (playing && (urgent || min > 1)) interrupt(session.call, null);
        if (!Boolean.FALSE.equals(details.get("is_final"))) session.latest = id;
        if (session.speechUrl.isEmpty()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("call_id", session.call);
        payload.put("provider_session_id", session.id);
        payload.put("event_id", id);
        payload.put("transcript", text);
        boolean last = !Boolean.FALSE.equals(details.get("is_final"));
        payload.put("is_final", last);
        payload.put("speaker_role", "customer");
        payload.put("channel_id", "inbound");
        payload.put(
                "asr_provider",
                session.metadata.get("voice_ai_profile") instanceof Map<?, ?> profile
                        ? Objects.toString(profile.get("id"), "")
                        : "pipecat:" + settings.get("PIPECAT_STT_PROVIDER", "openai-realtime"));
        Stream source = streams.get(session.call);
        payload.put(
                "native_response",
                source != null
                        && !Boolean.FALSE.equals(details.get("is_final"))
                        && (source.nativeResponse.getAndSet(false)
                                || Boolean.TRUE.equals(
                                        Support.map(session.metadata.get("voice_ai_guard"))
                                                .get("direct_s2s_enabled"))));
        payload.put("barge_in", false);
        payload.put("attempt", session.metadata.get("attempt"));
        for (String key :
                List.of(
                        "confidence",
                        "start_ms",
                        "end_ms",
                        "latency_ms",
                        "utterance_id",
                        "revision",
                        "stable_prefix_length",
                        "answer_class")) payload.put(key, details.get(key));
        events.post(session.speechUrl, payload);
    }

    private void synthesize(
            Stream stream, String text, long generation, long requested, boolean modelReply)
            throws Exception {
        if (!stream.profile.isEmpty()) {
            synthesizeVendor(stream, text, generation, requested, modelReply);
            return;
        }
        if (settings.secret("PIPECAT_OPENAI_API_KEY").isEmpty())
            throw new IOException("legacy synthesis credential missing");
        String permit = UUID.randomUUID().toString();
        boolean acquired = false, confirmed = false;
        boolean first = true;
        try {
            permits.change(stream.session, permit, "tts", true, false);
            acquired = true;
            URI base =
                    Support.endpoint(
                            settings.get("PIPECAT_OPENAI_BASE_URL", "https://api.openai.com/v1"));
            if (settings.production() && !base.getScheme().equals("https"))
                throw new Support.Failure(503, "production TTS requires TLS");
            var policy = Support.map(stream.session.metadata.get("scenario_policy"));
            byte[] body =
                    Support.json(
                            Map.of(
                                    "model",
                                    settings.get("PIPECAT_TTS_MODEL", "gpt-4o-mini-tts"),
                                    "voice",
                                    Support.str(
                                            policy,
                                            "voice",
                                            settings.get("PIPECAT_TTS_VOICE", "alloy")),
                                    "input",
                                    text,
                                    "response_format",
                                    "pcm"));
            var response =
                    http.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    base.toString().replaceAll("/+$", "")
                                                            + "/audio/speech"))
                                    .timeout(Duration.ofSeconds(30))
                                    .header(
                                            "Authorization",
                                            "Bearer " + settings.secret("PIPECAT_OPENAI_API_KEY"))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                                    .build(),
                            HttpResponse.BodyHandlers.ofInputStream());
            try (var input = Support.timed(response.body(), 30000)) {
                if (response.statusCode() / 100 != 2) throw new IOException("TTS unavailable");
                if (!voismart) media(stream.session, "speaking", stream.session.playback, "");
                // OpenAI PCM is 24 kHz. Keep sample phase across chunks and pace output at the PBX
                // sample rate.
                PcmResampler resampler = new PcmResampler(24000, rate);
                ByteBuffer buffer = ByteBuffer.allocateDirect(4800);
                var channel = Channels.newChannel(input);
                long sent = 0, started = System.nanoTime();
                for (int n; (n = channel.read(buffer)) != -1; ) {
                    buffer.flip();
                    if (stream.generation != generation
                            || stream.session.closing
                            || Thread.currentThread().isInterrupted()) return;
                    ByteBuffer output = resampler.convertBuffer(buffer);
                    buffer.clear();
                    for (int offset = 0; offset < output.remaining(); offset += rate / 50 * 2) {
                        if (stream.generation != generation) return;
                        int end = Math.min(output.remaining(), offset + rate / 50 * 2);
                        var frame = output.duplicate();
                        frame.position(offset).limit(end);
                        stream.binary(frame.slice());
                        sent += end - offset;
                        if (first) {
                            first = false;
                            double elapsed = (System.nanoTime() - requested) / 1_000_000_000.0;
                            firstAudioCount.increment();
                            firstAudioSeconds.add(elapsed);
                            firstAudioMax.accumulate(elapsed);
                        }
                        long wait =
                                started + sent * 1_000_000_000L / (rate * 2L) - System.nanoTime();
                        if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
                    }
                }
                if (stream.generation == generation && !stream.session.closing) {
                    ByteBuffer tail = resampler.finishBuffer();
                    if (tail.hasRemaining()) {
                        stream.binary(tail);
                        if (first) {
                            double elapsed = (System.nanoTime() - requested) / 1_000_000_000.0;
                            firstAudioCount.increment();
                            firstAudioSeconds.add(elapsed);
                            firstAudioMax.accumulate(elapsed);
                        }
                    }
                }
                confirmed = true;
                if (stream.generation == generation) {
                    if (voismart) stream.text(Map.of("type", "response.output_audio.done"));
                    else {
                        stream.session.playback = "";
                        if (stream.profile.isEmpty()) stream.coordinator.drained(generation, false);
                        media(stream.session, "listening", "", "");
                    }
                }
            }
        } finally {
            if (acquired) permits.change(stream.session, permit, "tts", false, confirmed);
        }
    }

    private void synthesizeVendor(
            Stream stream, String text, long generation, long requested, boolean modelReply)
            throws Exception {
        if (modelReply) stream.session.started.get(10, TimeUnit.SECONDS);
        if (stream.session.closing || stream.generation != generation)
            throw new InterruptedException("superseded reply");
        String permit = UUID.randomUUID().toString();
        boolean needsTts = !modelReply || !"realtime".equals(stream.profile.get("route"));
        var speechSpec = OpeningAudioCache.speechProfile(stream.profile);
        AutoCloseable speechLease =
                resources.acquire(
                        speechSpec,
                        Support.integer(stream.session.metadata, "tenant_id", 0),
                        needsTts ? new String[] {"tts"} : new String[] {});
        boolean confirmed = false;
        try (speechLease) {
            if (needsTts) permits.change(stream.session, permit, "tts", true, false);
            if (modelReply) {
                stream.engineReady.get(10, TimeUnit.SECONDS);
                stream.providerReady.get(10, TimeUnit.SECONDS);
            }
            var player = new VendorPlayer(stream, generation, requested);
            var guardPolicy = Support.map(stream.session.metadata.get("voice_ai_guard"));
            if (!stream.disclosureStarted && guardPolicy.containsKey("ai_disclosure_text")) {
                stream.disclosureStarted = true;
                String disclosure = Support.str(guardPolicy, "ai_disclosure_text", "");
                long tenant = Support.integer(stream.session.metadata, "tenant_id", 0);
                byte[] prepared =
                        new OpeningAudioCache(settings).read(tenant, stream.profile, disclosure);
                if (prepared != null) player.accept(prepared, 24000);
                else if (settings.production())
                    throw new IOException("prepared AI disclosure is missing");
                else synthesizeScoped(stream, speechSpec, disclosure, player);
                stream.disclosureSent = true;
            }
            if ("realtime".equals(stream.profile.get("route")) && modelReply) {
                // Reviewed is the safe existing behavior. Streaming is an explicit tenant choice:
                // audio may already have played when the complete output guard rejects a response.
                var guard = Support.map(stream.session.metadata.get("voice_ai_guard"));
                boolean streaming = "streaming".equals(guard.get("realtime_playback_mode"));
                var pending = new ByteArrayOutputStream();
                String reply =
                        stream.vendor.reply(
                                text,
                                modelReply,
                                (pcm, sampleRate) -> {
                                    if (pending.size() + pcm.length > 1440000)
                                        throw new IOException("realtime reply audio limit");
                                    pending.writeBytes(pcm);
                                    if (streaming) player.accept(pcm, sampleRate);
                                });
                if (!modelReply && reply.isBlank()) reply = text;
                if (reply.isBlank()
                        || reply.codePointCount(0, reply.length())
                                > Support.integer(guard, "max_reply_chars", 240))
                    throw new IOException("realtime output review failed");
                var reviewer = new TextCommitter(guard, sentence -> {});
                reviewer.append(reply);
                reviewer.finish();
                byte[] bytes = pending.toByteArray();
                if (!streaming)
                    for (int offset = 0; offset < bytes.length; offset += 4800)
                        player.accept(
                                Arrays.copyOfRange(
                                        bytes, offset, Math.min(bytes.length, offset + 4800)),
                                24000);
                stream.coordinator.text(generation, reply);
            } else {
                var spec =
                        "realtime".equals(stream.profile.get("route"))
                                ? Support.map(stream.profile.get("speech_profile"))
                                : stream.profile;
                if (spec.isEmpty())
                    throw new IOException("deterministic speech profile is required");
                var backend = new VoiceAiBackends(settings);
                if (modelReply) {
                    Object source = stream.context.get("messages");
                    if (!(source instanceof List<?> sourceMessages) || sourceMessages.isEmpty())
                        throw new IOException("streaming response context is required");
                    var messages = new ArrayList<Map<String, String>>();
                    for (Object value : sourceMessages) {
                        var item = Support.map(value);
                        messages.add(
                                Map.of(
                                        "role",
                                        Support.str(item, "role", "user"),
                                        "content",
                                        Support.str(item, "content", "")));
                    }
                    try (var modelLease =
                                    resources.acquire(
                                            spec,
                                            Support.integer(
                                                    stream.session.metadata, "tenant_id", 0),
                                            "llm");
                            var tts = backend.synthesis(spec, player::accept, stream.response)) {
                        var committer =
                                new TextCommitter(
                                        Support.map(stream.session.metadata.get("voice_ai_guard")),
                                        sentence -> {
                                            stream.coordinator.stamp(
                                                    generation, "segment_approved");
                                            tts.append(sentence);
                                        });
                        backend.generate(
                                spec,
                                messages,
                                delta -> {
                                    stream.coordinator.stamp(generation, "llm_first");
                                    committer.append(delta);
                                },
                                stream.response);
                        String reply = committer.finish();
                        stream.coordinator.text(generation, reply);
                        tts.finish();
                    }
                } else {
                    stream.coordinator.text(generation, text);
                    synthesizeScoped(stream, spec, text, player);
                }
            }
            player.finish();
            stream.generationDone = generation;
            confirmed = true;
            if (stream.generation == generation && !stream.session.closing) {
                if (voismart) stream.text(Map.of("type", "response.output_audio.done"));
                else {
                    stream.session.playback = "";
                    stream.coordinator.drained(generation, false);
                    media(stream.session, "listening", "", "");
                }
            }
        } finally {
            if (needsTts) permits.change(stream.session, permit, "tts", false, confirmed);
        }
    }

    private void synthesizeScoped(
            Stream stream, Map<String, Object> spec, String text, VendorPlayer player)
            throws Exception {
        try (var tts =
                new VoiceAiBackends(settings).synthesis(spec, player::accept, stream.response)) {
            tts.append(text);
            tts.finish();
        }
    }

    private void replyTranscript(Session session, String text, long generation) throws Exception {
        if (session.speechUrl.isEmpty()) return;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("call_id", session.call);
        payload.put("provider_session_id", session.id);
        payload.put("event_id", session.id + ":reply:" + generation);
        payload.put("transcript", text);
        payload.put("is_final", true);
        payload.put("speaker_role", "ai");
        payload.put("channel_id", "outbound");
        payload.put(
                "asr_provider", Support.map(session.metadata.get("voice_ai_profile")).get("id"));
        payload.put("attempt", session.metadata.get("attempt"));
        events.post(session.speechUrl, payload);
    }

    private final class VendorPlayer {
        final Stream stream;
        final long generation, requested;
        long sent, started;
        boolean first = true;
        PcmResampler conversion;

        VendorPlayer(Stream stream, long generation, long requested) {
            this.stream = stream;
            this.generation = generation;
            this.requested = requested;
        }

        void accept(byte[] pcm, int sampleRate) throws Exception {
            stream.coordinator.stamp(generation, "tts_first");
            stream.coordinator.generated(generation, pcm.length / 2);
            if (conversion == null) conversion = new PcmResampler(sampleRate, rate);
            send(conversion.convert(pcm));
        }

        void send(byte[] pcm) throws Exception {
            for (int offset = 0; offset < pcm.length; offset += rate / 50 * 2) {
                if (stream.generation != generation
                        || stream.session.closing
                        || Thread.currentThread().isInterrupted())
                    throw new InterruptedException("superseded reply");
                if (first) {
                    first = false;
                    started = System.nanoTime();
                    double elapsed = (started - requested) / 1_000_000_000.0;
                    firstAudioCount.increment();
                    firstAudioSeconds.add(elapsed);
                    firstAudioMax.accumulate(elapsed);
                    if (!voismart) media(stream.session, "speaking", stream.session.playback, "");
                }
                int end = Math.min(pcm.length, offset + rate / 50 * 2);
                stream.coordinator.check(generation);
                stream.binary(ByteBuffer.wrap(pcm, offset, end - offset));
                stream.coordinator.sent(generation, (end - offset) / 2);
                stream.coordinator.stamp(generation, "media_sent");
                sent += end - offset;
                long wait = started + sent * 1_000_000_000L / (rate * 2L) - System.nanoTime();
                if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
            }
        }

        void finish() throws Exception {
            if (conversion != null) send(conversion.finish());
            if (first) throw new IOException("provider produced no audio");
        }
    }

    final class Stream implements AutoCloseable, WebSocket.Listener {
        final Session session;
        final WebSocketSession socket;
        final Map<String, Object> profile;
        final SessionCoordinator coordinator = new SessionCoordinator();
        final AnswerClassifier classifier = new AnswerClassifier();
        final AtomicBoolean activating = new AtomicBoolean();
        final CompletableFuture<Void> engineReady = new CompletableFuture<>();
        final ByteArrayOutputStream pendingInput = new ByteArrayOutputStream();
        final List<String> confirmedHistory = new ArrayList<>();
        volatile VoiceAiBackends.Connection classifierVendor;
        PcmResampler classifierInput;
        AutoCloseable asrLease, engineLease;
        volatile boolean rebuilding;
        volatile CompletableFuture<Void> providerReady = CompletableFuture.completedFuture(null);
        volatile long providerEpoch;
        volatile long generationDone;
        final boolean classify;
        volatile boolean disclosureStarted;
        volatile boolean disclosureSent;
        volatile boolean disclosurePlayed;
        final AtomicBoolean nativeResponse = new AtomicBoolean();
        volatile ResponseScope response;
        volatile Map<String, Object> context = Map.of();
        volatile VoiceAiBackends.Connection vendor;
        PcmResampler vendorInput;
        volatile WebSocket asr;
        volatile Future<?> tts;
        volatile long generation;
        volatile long lastPcmAt;
        final CompletableFuture<Void> asrStarted = new CompletableFuture<>(),
                asrStopped = new CompletableFuture<>();
        final String task = UUID.randomUUID().toString().replace("-", ""),
                asrPermit = UUID.randomUUID().toString();
        final StringBuilder message = new StringBuilder();
        final boolean aliyun =
                settings.get("PIPECAT_STT_PROVIDER", "openai-realtime").equals("aliyun-nls");
        final PcmResampler resampler = new PcmResampler(rate, 24000);
        boolean permitAcquired;
        final AtomicBoolean closed = new AtomicBoolean();
        long speechSamples, silenceSamples, bufferSamples;
        boolean localSpeech;

        Stream(Session session, WebSocketSession socket) {
            this.session = session;
            this.socket = socket;
            profile = Support.map(session.metadata.get("voice_ai_profile"));
            classify =
                    Boolean.TRUE.equals(
                            Support.map(session.metadata.get("voice_ai_guard"))
                                    .get("answer_classification_enabled"));
            if (!classify) classifier.timeout();
        }

        void connectAsr() throws Exception {
            synchronized (this) {
                if (closed.get() || session.closing)
                    throw new IOException("media closed before ASR connection");
                permits.change(session, asrPermit, "asr", true, false);
                permitAcquired = true;
            }
            if (!profile.isEmpty()) {
                var initial =
                        classify && "realtime".equals(profile.get("route"))
                                ? Support.map(profile.get("speech_profile"))
                                : profile;
                if (initial.isEmpty())
                    throw new IOException("answer classifier speech profile is required");
                long tenant = Support.integer(session.metadata, "tenant_id", 0);
                if ("cascade".equals(initial.get("route")))
                    asrLease = resources.acquire(initial, tenant, "asr");
                if (!classify) engineLease = acquireEngine(tenant);
                vendorInput = new PcmResampler(rate, inputRate(initial));
                vendor = provider(initial, false);
                if (closed.get() || session.closing) {
                    vendor.close();
                    throw new IOException("closed during startup");
                }
                if (classify) {
                    deadlines.schedule(
                            () -> {
                                if (classifier.answer() == AnswerClassifier.Answer.CLASSIFYING
                                        && !closed.get()) {
                                    classifier.timeout();
                                    activate(null, null, Map.of());
                                }
                            },
                            Support.integer(
                                    Support.map(session.metadata.get("voice_ai_guard")),
                                    "answer_classify_timeout_ms",
                                    2000),
                            TimeUnit.MILLISECONDS);
                } else {
                    engineReady.complete(null);
                }
                asrStarted.complete(null);
                return;
            }
            if (settings.secret("PIPECAT_OPENAI_API_KEY").isEmpty())
                throw new IOException("legacy speech credential missing");
            String target =
                    aliyun
                            ? settings.get(
                                    "ALIYUN_NLS_GATEWAY_URL",
                                    "wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1")
                            : settings.get(
                                    "PIPECAT_OPENAI_REALTIME_BASE_URL",
                                    "wss://api.openai.com/v1/realtime");
            URI endpoint = URI.create(target);
            if (!Set.of("ws", "wss").contains(endpoint.getScheme())
                    || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null
                    || endpoint.getFragment() != null
                    || (settings.production() && !endpoint.getScheme().equals("wss")))
                throw new IllegalArgumentException("invalid ASR endpoint");
            long startupMillis =
                    (long)
                            ((aliyun
                                            ? settings.decimal(
                                                    "ALIYUN_NLS_CONNECT_TIMEOUT_SEC", 8, .1, 300)
                                            : 8)
                                    * 1000);
            var builder =
                    http.newWebSocketBuilder().connectTimeout(Duration.ofMillis(startupMillis));
            if (aliyun) {
                if (endpoint.getQuery() != null
                        && Support.asciiLower(endpoint.getQuery()).contains("token="))
                    throw new IllegalArgumentException("ASR endpoint must not embed a token");
                target +=
                        (target.contains("?") ? "&" : "?")
                                + "token="
                                + S3Store.encode(settings.secret("ALIYUN_NLS_TOKEN"));
            } else {
                builder.header(
                        "Authorization", "Bearer " + settings.secret("PIPECAT_OPENAI_API_KEY"));
                target += target.contains("?") ? "&intent=transcription" : "?intent=transcription";
            }
            var connecting = builder.buildAsync(URI.create(target), this);
            connecting.thenAccept(
                    socket -> {
                        if (closed.get() || session.closing) socket.abort();
                    });
            var connected = connecting.get(startupMillis, TimeUnit.MILLISECONDS);
            synchronized (this) {
                asr = connected;
                if (closed.get() || session.closing) {
                    connected.abort();
                    throw new IOException("media closed during ASR connection");
                }
            }
            if (aliyun)
                sendAsr(
                        Map.of(
                                "header",
                                header("StartTranscription"),
                                "payload",
                                aliyunPayload(session)));
            else {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("format", Map.of("type", "audio/pcm", "rate", 24000));
                input.put(
                        "transcription",
                        Map.of(
                                "model",
                                settings.get("JAVA_OPENAI_STT_MODEL", "gpt-realtime-whisper"),
                                "language",
                                Support.str(
                                                Support.map(
                                                        session.metadata.get("scenario_policy")),
                                                "language",
                                                Support.str(session.metadata, "language", "zh-CN"))
                                        .split("-", 2)[0]));
                input.put("turn_detection", null);
                sendAsr(
                        Map.of(
                                "type",
                                "session.update",
                                "session",
                                Map.of("type", "transcription", "audio", Map.of("input", input))));
            }
            asrStarted.get(startupMillis, TimeUnit.MILLISECONDS);
        }

        Map<String, Object> header(String name) {
            return Map.of(
                    "appkey",
                    settings.get("ALIYUN_NLS_APPKEY", ""),
                    "message_id",
                    UUID.randomUUID().toString().replace("-", ""),
                    "task_id",
                    task,
                    "namespace",
                    "SpeechTranscriber",
                    "name",
                    name);
        }

        int inputRate(Map<String, Object> spec) {
            return "cascade".equals(spec.get("route"))
                            && Set.of("aliyun", "tencent").contains(spec.get("vendor"))
                    ? 8000
                    : 16000;
        }

        VoiceAiBackends.Connection provider(Map<String, Object> spec, boolean shadow)
                throws Exception {
            String script = Support.str(session.metadata, "voice_ai_script", "");
            synchronized (confirmedHistory) {
                if (!confirmedHistory.isEmpty())
                    script += "\n已确认对话记录：\n" + String.join("\n", confirmedHistory);
            }
            boolean realtime = "realtime".equals(spec.get("route"));
            long owner = realtime ? ++providerEpoch : 0;
            return new VoiceAiBackends(settings)
                    .connect(
                            spec,
                            script,
                            new VoiceAiBackends.Listener() {
                                public void transcript(String text, String event) throws Exception {
                                    if (!shadow && (!realtime || owner == providerEpoch))
                                        recognized(text, event, Map.of("is_final", true));
                                }

                                public void recognition(Map<String, Object> event)
                                        throws Exception {
                                    if (!shadow && (!realtime || owner == providerEpoch))
                                        recognized(
                                                event.get("text").toString(),
                                                event.get("event_id").toString(),
                                                event);
                                }

                                public void speechStarted() throws Exception {
                                    if (!shadow && (!realtime || owner == providerEpoch))
                                        speechStart(session);
                                }

                                public void inputCommitted() throws Exception {
                                    var guard = Support.map(session.metadata.get("voice_ai_guard"));
                                    if (Boolean.TRUE.equals(guard.get("direct_s2s_enabled"))
                                            && !session.closing
                                            && owner == providerEpoch) {
                                        nativeResponse.set(true);
                                        lifecycle.execute(
                                                () -> {
                                                    try {
                                                        respond(
                                                                session.call,
                                                                "按已批准话术回应当前语音",
                                                                null,
                                                                UUID.randomUUID().toString(),
                                                                true);
                                                    } catch (Exception failure) {
                                                        requestFailure(
                                                                session, "DIRECT_RESPONSE_FAILED");
                                                    }
                                                });
                                    }
                                }
                            });
        }

        void recognized(String text, String event, Map<String, Object> details) throws Exception {
            boolean last = !Boolean.FALSE.equals(details.get("is_final"));
            if (!last && vendor != null && "realtime".equals(profile.get("route")))
                vendor.recognitionHint(text);
            if (classify && classifier.answer() == AnswerClassifier.Answer.CLASSIFYING) {
                var answer = classifier.transcript(text, last);
                if (answer == AnswerClassifier.Answer.CLASSIFYING) return;
                if (answer == AnswerClassifier.Answer.MACHINE) {
                    engineReady.completeExceptionally(new IOException("non-human answer"));
                    synchronized (pendingInput) {
                        pendingInput.reset();
                    }
                    var evidence = new LinkedHashMap<>(details);
                    evidence.put("answer_class", "machine");
                    NativeMedia.this.transcript(session, text, event, evidence);
                    media(session, "listening", "", "");
                    return;
                }
                if (Set.of("stop_contact", "wrong_person", "handoff", "rejected", "end")
                        .contains(com.duozhi.outbound.ai.service.DialogueRules.classify(text))) {
                    engineReady.completeExceptionally(
                            new IOException("business control required before voice model"));
                    NativeMedia.this.transcript(session, text, event, details);
                    return;
                }
                activate(text, event, details);
                return;
            }
            if (classifier.answer() == AnswerClassifier.Answer.MACHINE) return;
            if (last) remember("客户：" + text);
            NativeMedia.this.transcript(session, text, event, details);
        }

        void remember(String text) {
            synchronized (confirmedHistory) {
                confirmedHistory.add(text);
                while (confirmedHistory.size() > 12) confirmedHistory.remove(0);
            }
        }

        AutoCloseable acquireEngine(long tenant) {
            return resources.acquire(
                    profile,
                    tenant,
                    "realtime".equals(profile.get("route"))
                            ? new String[] {"ai", "s2s"}
                            : new String[] {"ai"});
        }

        void activate(String text, String event, Map<String, Object> details) {
            if (!activating.compareAndSet(false, true)) return;
            try {
                lifecycle.execute(
                        () -> {
                            try {
                                engineLease =
                                        acquireEngine(
                                                Support.integer(session.metadata, "tenant_id", 0));
                                if ("realtime".equals(profile.get("route"))) {
                                    classifierVendor = vendor;
                                    classifierInput = vendorInput;
                                    rebuilding = true;
                                    installRealtime();
                                } else
                                    synchronized (pendingInput) {
                                        pendingInput.reset();
                                    }
                                if (closed.get() || session.closing)
                                    throw new IOException("classification owner closed");
                                engineReady.complete(null);
                                media(session, "listening", "", "");
                                if (text != null) {
                                    remember("客户：" + text);
                                    NativeMedia.this.transcript(session, text, event, details);
                                }
                            } catch (Exception failure) {
                                engineReady.completeExceptionally(failure);
                                if (failure instanceof Support.Failure f && f.status == 429) {
                                    try {
                                        media(session, "listening", "", "AI_CAPACITY_ABANDONED");
                                    } catch (Exception callback) {
                                        requestFailure(session, "VOICE_FAILURE_CALLBACK_FAILED");
                                    }
                                } else requestFailure(session, "VOICE_ENGINE_START_FAILED");
                            }
                        });
            } catch (RejectedExecutionException full) {
                requestFailure(session, "VOICE_ENGINE_OVERLOADED");
            }
        }

        void installRealtime() throws Exception {
            var replacement = provider(profile, classifierVendor != null);
            try {
                var conversion = new PcmResampler(rate, 16000);
                // Keep capture running while a provider is establishing its fresh context.
                for (; ; ) {
                    byte[] queued;
                    synchronized (pendingInput) {
                        if (pendingInput.size() == 0) {
                            if (closed.get() || session.closing)
                                throw new IOException("closed during restart");
                            vendorInput = conversion;
                            vendor = replacement;
                            rebuilding = false;
                            return;
                        }
                        queued = pendingInput.toByteArray();
                        pendingInput.reset();
                    }
                    byte[] converted = conversion.convert(queued);
                    if (converted.length > 0) replacement.audio(converted);
                }
            } catch (Exception error) {
                replacement.close();
                throw error;
            }
        }

        void restartRealtime() {
            if (!"realtime".equals(profile.get("route")) || closed.get() || !engineReady.isDone())
                return;
            synchronized (pendingInput) {
                if (rebuilding) return;
                rebuilding = true;
                providerReady = new CompletableFuture<>();
            }
            var ready = providerReady;
            var old = vendor;
            if (old != null && old != classifierVendor) old.close();
            try {
                lifecycle.execute(
                        () -> {
                            try {
                                installRealtime();
                                ready.complete(null);
                            } catch (Exception failure) {
                                ready.completeExceptionally(failure);
                                requestFailure(session, "REALTIME_CONTEXT_REBUILD_FAILED");
                            }
                        });
            } catch (RejectedExecutionException full) {
                requestFailure(session, "VOICE_ENGINE_OVERLOADED");
            }
        }

        void audio(ByteBuffer pcm) throws Exception {
            if (session.closing) return;
            if (!profile.isEmpty()) {
                byte[] bytes = pcmToArray(pcm);
                boolean pending = rebuilding || (classify && !engineReady.isDone());
                if (pending)
                    synchronized (pendingInput) {
                        if (pendingInput.size() + bytes.length > rate * 2 * 4)
                            throw new IOException("classification or restart input buffer full");
                        pendingInput.writeBytes(bytes);
                    }
                if (classifierVendor != null) {
                    byte[] classified = classifierInput.convert(bytes);
                    if (classified.length > 0) classifierVendor.audio(classified);
                }
                if (!rebuilding && vendor != null) {
                    byte[] input = vendorInput.convert(bytes);
                    if (input.length > 0) vendor.audio(input);
                }
                return;
            }
            if (asr == null) return;
            if (aliyun) {
                asr.sendBinary(pcm, true).get(5, TimeUnit.SECONDS);
                return;
            }
            ByteBuffer converted = resampler.convertBuffer(pcm);
            if (!converted.hasRemaining()) return;
            sendAsr(
                    Map.of(
                            "type",
                            "input_audio_buffer.append",
                            "audio",
                            StandardCharsets.ISO_8859_1
                                    .decode(Base64.getEncoder().encode(converted.duplicate()))
                                    .toString()));
            int samples = converted.remaining() / 2;
            double energy = 0;
            for (int index = 0; index < converted.remaining(); index += 2) {
                short value =
                        (short) ((converted.get(index) & 255) | (converted.get(index + 1) << 8));
                energy += value * (double) value;
            }
            boolean voiced =
                    Math.sqrt(energy / Math.max(1, samples))
                            >= settings.number("JAVA_VAD_RMS_THRESHOLD", 200, 1, 32767);
            bufferSamples += samples;
            if (voiced) {
                speechSamples += samples;
                silenceSamples = 0;
                if (!localSpeech
                        && speechSamples
                                >= 24000
                                        * settings.number("JAVA_VAD_START_MS", 100, 20, 1000)
                                        / 1000) {
                    localSpeech = true;
                    speechStart(session);
                }
            } else {
                silenceSamples += samples;
                if (!localSpeech) speechSamples = 0;
            }
            long silence = settings.number("JAVA_VAD_END_MS", 800, 200, 3000) * 24;
            if (localSpeech && (silenceSamples >= silence || bufferSamples >= 24000 * 20)) {
                sendAsr(Map.of("type", "input_audio_buffer.commit"));
                localSpeech = false;
                speechSamples = silenceSamples = bufferSamples = 0;
            } else if (!localSpeech && bufferSamples >= 24000 * 2) {
                sendAsr(Map.of("type", "input_audio_buffer.clear"));
                bufferSamples = 0;
            }
        }

        void sendAsr(Object value) throws Exception {
            asr.sendText(new String(Support.json(value), StandardCharsets.UTF_8), true)
                    .get(5, TimeUnit.SECONDS);
        }

        void binary(ByteBuffer value) throws Exception {
            synchronized (socket) {
                if (!socket.isOpen()) throw new IOException("media socket closed");
                socket.sendMessage(new BinaryMessage(value));
            }
        }

        void text(Object value) throws Exception {
            synchronized (socket) {
                if (socket.isOpen()) socket.sendMessage(new TextMessage(Support.json(value)));
            }
        }

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (message.length() + data.length() > 1048576) {
                deferFailure("ASR_FRAME_LIMIT");
                return null;
            }
            message.append(data);
            if (!last) {
                socket.request(1);
                return null;
            }
            byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
            message.setLength(0);
            // Stop evidence must remain consumable even if all event workers are closing other
            // streams.
            try {
                var parsed = Support.object(bytes);
                if (aliyun
                        && "TranscriptionCompleted"
                                .equals(Support.str(Support.map(parsed.get("header")), "name", ""))
                        && Support.integer(Support.map(parsed.get("header")), "status", 20000000)
                                == 20000000) {
                    asrStopped.complete(null);
                    socket.request(1);
                    return null;
                }
                asrEvents.execute(
                        () -> {
                            try {
                                asrEvent(this, parsed);
                            } catch (Exception error) {
                                fail(session, "ASR_PROTOCOL_ERROR");
                            } finally {
                                socket.request(1);
                            }
                        });
            } catch (RejectedExecutionException full) {
                deferFailure("MEDIA_PROCESS_OVERLOADED");
            } catch (Exception invalid) {
                deferFailure("ASR_PROTOCOL_ERROR");
            }
            return null;
        }

        private void deferFailure(String code) {
            asrStarted.completeExceptionally(new IOException("ASR connection failed"));
            requestFailure(session, code);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            if (!session.closing) deferFailure("ASR_DISCONNECTED");
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            if (!session.closing) deferFailure("ASR_CONNECTION_ERROR");
        }

        @Override
        public void close() throws Exception {
            if (!closed.compareAndSet(false, true)) return;
            asrStarted.completeExceptionally(new IOException("media closed during ASR startup"));
            coordinator.close();
            generation++;
            if (tts != null) tts.cancel(true);
            boolean confirmed = false;
            if (vendor != null) vendor.close();
            if (classifierVendor != null && classifierVendor != vendor) classifierVendor.close();
            if (asrLease != null) asrLease.close();
            if (engineLease != null) engineLease.close();
            if (asr != null) {
                try {
                    if (aliyun) {
                        sendAsr(Map.of("header", header("StopTranscription")));
                        asrStopped.get(
                                (long)
                                        (settings.decimal("ALIYUN_NLS_STOP_TIMEOUT_SEC", 3, .1, 30)
                                                * 1000),
                                TimeUnit.MILLISECONDS);
                        confirmed = true;
                    }
                    asr.sendClose(1000, "closing").get(3, TimeUnit.SECONDS);
                } catch (Exception unknown) {
                    asr.abort();
                }
            }
            try {
                synchronized (this) {
                    if (permitAcquired)
                        permits.change(
                                session,
                                asrPermit,
                                "asr",
                                false,
                                confirmed
                                        && settings.flag(
                                                "VOICE_QUOTA_NORMAL_CLOSE_RELEASES", false));
                }
            } finally {
                if (socket.isOpen()) socket.close();
            }
        }
    }

    private Map<String, Object> aliyunPayload(Session session) {
        var policy = Support.map(session.metadata.get("scenario_policy"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("format", "pcm");
        payload.put("sample_rate", rate);
        payload.put("enable_intermediate_result", true);
        for (String flag :
                List.of(
                        "enable_punctuation_prediction",
                        "enable_inverse_text_normalization",
                        "enable_words",
                        "enable_semantic_sentence_detection",
                        "enable_ignore_sentence_timeout",
                        "disfluency"))
            payload.put(
                    flag,
                    settings.flag(
                            "ALIYUN_NLS_" + flag.toUpperCase(Locale.ROOT),
                            !Set.of("enable_semantic_sentence_detection", "disfluency")
                                    .contains(flag)));
        payload.put(
                "max_sentence_silence",
                Support.integer(
                        policy,
                        "sentence_silence_ms",
                        settings.number("ALIYUN_NLS_MAX_SENTENCE_SILENCE_MS", 800, 200, 2000)));
        String vocabulary =
                Support.str(policy, "vocabulary_id", settings.get("ALIYUN_NLS_VOCABULARY_ID", ""));
        if (!vocabulary.isEmpty()) payload.put("vocabulary_id", vocabulary);
        String customization = settings.get("ALIYUN_NLS_CUSTOMIZATION_ID", "");
        if (!customization.isEmpty()) payload.put("customization_id", customization);
        return payload;
    }

    private void asrEvent(Stream stream, Map<String, Object> event) throws Exception {
        Session session = stream.session;
        String type =
                stream.aliyun
                        ? Support.str(Support.map(event.get("header")), "name", "")
                        : Support.str(event, "type", "");
        if (stream.aliyun) {
            var header = Support.map(event.get("header"));
            long status = Support.integer(header, "status", 20000000);
            if (status != 20000000) {
                stream.asrStarted.completeExceptionally(new IOException("ASR task rejected"));
                fail(session, "ASR_PROVIDER_ERROR");
                return;
            }
            if (type.equals("TranscriptionStarted")) stream.asrStarted.complete(null);
            else if (type.equals("TranscriptionCompleted")) stream.asrStopped.complete(null);
            else if (type.equals("SentenceBegin")) speechStart(session);
            else if (type.equals("SentenceEnd")) {
                var payload = Support.map(event.get("payload"));
                Map<String, Object> details = new HashMap<>();
                Object confidence = payload.get("confidence");
                if (confidence instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 1)
                    details.put("confidence", n.doubleValue());
                if (payload.get("begin_time") instanceof Number n)
                    details.put("start_ms", n.longValue());
                if (payload.get("time") instanceof Number n) details.put("end_ms", n.longValue());
                transcript(
                        session,
                        Support.str(payload, "result", ""),
                        Support.str(header, "message_id", UUID.randomUUID().toString()),
                        details);
            }
        } else {
            if (Set.of("session.updated", "transcription_session.updated").contains(type))
                stream.asrStarted.complete(null);
            else if (type.equals("input_audio_buffer.speech_started")) speechStart(session);
            else if (type.equals("conversation.item.input_audio_transcription.completed"))
                transcript(
                        session,
                        Support.str(event, "transcript", ""),
                        Support.str(
                                event,
                                "item_id",
                                Support.str(event, "event_id", UUID.randomUUID().toString())),
                        Map.of());
            else if (type.equals("error")) {
                stream.asrStarted.completeExceptionally(new IOException("ASR protocol rejected"));
                fail(session, "ASR_PROVIDER_ERROR");
            }
        }
    }

    private void speechStart(Session session) throws Exception {
        var policy = Support.map(session.metadata.get("scenario_policy"));
        boolean playing = !session.playback.isEmpty() || session.speaking;
        if (playing
                && (Boolean.FALSE.equals(policy.get("allow_interruptions"))
                        || Support.integer(policy, "min_interrupt_chars", 1) > 1)) return;
        session.latest = "vad:" + UUID.randomUUID().toString().replace("-", "");
        if (playing) interrupt(session.call, null);
    }

    @Override
    public void close() throws Exception {
        deadlines.shutdownNow();
        ingress.close();
        for (var session : sessions())
            try {
                closeSession(session.call, false);
            } catch (Exception ignored) {
            }
        lifecycle.shutdownNow();
        pendingClosures.clear();
        work.shutdownNow();
        asrEvents.shutdownNow();
    }

    private static byte[] pcmToArray(ByteBuffer source) {
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return bytes;
    }

    /** Stateful rate conversion keeps odd-byte carry and sample phase across network chunks. */
    public static final class PcmResampler {
        private static final int RADIUS = 16;
        final int input, output, phases;
        final double[][] weights;
        final short[] ring = new short[128];
        long samples, next;
        int carry = -1;
        boolean finished;

        public PcmResampler(int input, int output) {
            if (input <= 0 || output <= 0) throw new IllegalArgumentException("invalid PCM rate");
            this.input = input;
            this.output = output;
            int a = input, b = output;
            while (b != 0) {
                int remainder = a % b;
                a = b;
                b = remainder;
            }
            phases = output / a;
            if (phases > 48000) throw new IllegalArgumentException("unsupported PCM rate ratio");
            weights = new double[phases][RADIUS * 2];
            double cutoff = .94 * Math.min(1.0, output / (double) input);
            for (int phase = 0; phase < phases; phase++) {
                double fraction = phase / (double) phases, total = 0;
                for (int tap = 0; tap < RADIUS * 2; tap++) {
                    double distance = tap - RADIUS + 1 - fraction,
                            argument = Math.PI * cutoff * distance;
                    double sinc = Math.abs(argument) < 1e-12 ? 1 : Math.sin(argument) / argument;
                    double window =
                            Math.abs(distance) >= RADIUS
                                    ? 0
                                    : .42
                                            + .5 * Math.cos(Math.PI * distance / RADIUS)
                                            + .08 * Math.cos(2 * Math.PI * distance / RADIUS);
                    weights[phase][tap] = cutoff * sinc * window;
                    total += weights[phase][tap];
                }
                for (int tap = 0; tap < RADIUS * 2; tap++) weights[phase][tap] /= total;
            }
        }

        private ByteBuffer convertedBuffer = ByteBuffer.allocateDirect(8192);

        private void begin(int incoming) {
            long needed =
                    ((incoming + 1L) / 2 + RADIUS * 2L) * Math.max(1, outputRateRatio()) * 2 + 1024;
            if (needed > 8388608) throw new IllegalArgumentException("PCM frame too large");
            if (convertedBuffer.capacity() < needed)
                convertedBuffer = ByteBuffer.allocateDirect((int) needed);
            convertedBuffer.clear();
        }

        private int outputRateRatio() {
            return (int) Math.ceil(outputRate() / (double) input);
        }

        private int outputRate() {
            return output;
        }

        synchronized ByteBuffer convertBuffer(ByteBuffer bytes) {
            if (finished) throw new IllegalStateException("PCM stream is finished");
            begin(bytes.remaining());
            while (bytes.hasRemaining()) {
                int low = carry >= 0 ? carry : bytes.get() & 255;
                if (!bytes.hasRemaining()) {
                    carry = low;
                    break;
                }
                int high = bytes.get() & 255;
                carry = -1;
                ring[(int) (samples & 127)] = (short) (low | (high << 8));
                samples++;
                emit(convertedBuffer, false);
            }
            return convertedBuffer.flip().asReadOnlyBuffer();
        }

        synchronized ByteBuffer finishBuffer() {
            if (finished) return ByteBuffer.allocate(0);
            if (carry >= 0) throw new IllegalArgumentException("incomplete PCM16 sample");
            finished = true;
            begin(0);
            emit(convertedBuffer, true);
            return convertedBuffer.flip().asReadOnlyBuffer();
        }

        public synchronized byte[] convert(byte[] bytes) {
            ByteBuffer value = convertBuffer(ByteBuffer.wrap(bytes));
            byte[] result = new byte[value.remaining()];
            value.get(result);
            return result;
        }

        public synchronized byte[] finish() {
            ByteBuffer value = finishBuffer();
            byte[] result = new byte[value.remaining()];
            value.get(result);
            return result;
        }

        private void emit(ByteBuffer result, boolean flush) {
            while (next * input / output < samples) {
                long position = next * input, center = position / output;
                if (!flush && center + RADIUS >= samples) return;
                int phase = (int) ((position % output) * phases / output);
                double value = 0;
                for (int tap = 0; tap < RADIUS * 2; tap++) {
                    long index = center + tap - RADIUS + 1;
                    if (index >= 0 && index < samples)
                        value += ring[(int) (index & 127)] * weights[phase][tap];
                }
                int sample = (int) Math.max(-32768, Math.min(32767, Math.round(value)));
                result.put((byte) (sample & 255));
                result.put((byte) ((sample >> 8) & 255));
                next++;
            }
        }
    }
}
