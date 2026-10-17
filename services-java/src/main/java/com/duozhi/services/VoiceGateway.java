package com.duozhi.services;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** PBX call ownership, durable admission, lifecycle events and native speech. */
final class VoiceGateway implements AutoCloseable {
    final Settings settings;
    final LocalLedger ledger;
    final VoiceSecurity security;
    final VoiceCallbacks callbacks;
    final RecordingSource recordings;
    final EslClient esl;
    final MediaManager media;
    final Map<String,Binding> bindings = new ConcurrentHashMap<>();
    private final Map<String,CompletableFuture<Void>> notices = new ConcurrentHashMap<>();
    private final Map<String,CompletableFuture<Void>> mediaTasks = new ConcurrentHashMap<>();
    private final ExecutorService listener;
    private final ExecutorService lifecycle;
    private final ExecutorService termination;
    private final List<ThreadPoolExecutor> eventWorkers = new ArrayList<>();
    private final ScheduledExecutorService maintenance;
    private final Semaphore eventSlots = new Semaphore(2048);
    private volatile Socket eventSocket;
    volatile boolean draining, running = true, subscribed;
    private final HttpClient http = Support.client();
    static final class Binding {
        final String call, uuid, statusUrl;
        final Map<String,Object> metadata;
        volatile String recordingPath = "", failure = "";
        volatile boolean stopped, human;
        volatile String pipeline;
        volatile long answeredAt;
        Binding(Map<String,Object> row) {
            var payload = Support.object(Support.str(row, "payload", "{}").getBytes(StandardCharsets.UTF_8));
            call = Support.str(row, "call_id", ""); uuid = Support.str(row, "uuid", ""); statusUrl = Support.str(payload, "webhook_url", ""); metadata = Collections.synchronizedMap(new LinkedHashMap<>(Support.map(payload.get("metadata"))));
        }
    }
    VoiceGateway(Settings settings) throws Exception {
        this.settings = settings;
        String driver = settings.get("VOICE_GATEWAY_DRIVER", "mock"); if (!Set.of("mock", "freeswitch_esl").contains(driver)) throw new IllegalArgumentException("unsupported or unverified voice driver");
        if (!Set.of("legacy", "pipecat", "hybrid").contains(settings.get("VOICE_AI_PIPELINE", "legacy"))) throw new IllegalArgumentException("invalid media pipeline");
        if (settings.flag("PIPECAT_FALLBACK_TO_LEGACY", false) && settings.get("FREESWITCH_MEDIA_START_COMMAND_TEMPLATE", "").isBlank()) throw new IllegalArgumentException("native media fallback requires an explicit legacy start command");
        if (!driver.equals("freeswitch_esl") && !settings.get("VOICE_AI_PIPELINE", "legacy").equals("legacy")) throw new IllegalArgumentException("realtime media requires FreeSWITCH driver");
        String path = settings.get("VOICE_SECURITY_DB_PATH", "");
        if (path.isEmpty() && driver.equals("mock") && !settings.production()) path = Files.createTempDirectory("java-voice-").resolve("ledger.sqlite").toString();
        ledger = new LocalLedger(path);
        try { security = new VoiceSecurity(settings, ledger); }
        catch (Exception error) { ledger.close(); throw error; }
        callbacks = new VoiceCallbacks(settings, ledger, security); recordings = new RecordingSource(settings); esl = new EslClient(settings);
        try {
            media = settings.get("VOICE_AI_PIPELINE", "legacy").equals("legacy") ? null : settings.get("MEDIA_WORKERS_JSON", "[]").equals("[]") ? new NativeMedia(settings, callbacks::post, this::localPermit, (int)settings.number("PIPECAT_MAX_ACTIVE_SESSIONS", 100, 1, 10000)) : new MediaCluster(settings, ledger);
        } catch (Exception error) { callbacks.close(); ledger.close(); throw error; }
        listener = Executors.newSingleThreadExecutor(r -> daemon(r, "voice-esl"));
        int lifecycleThreads = (int)settings.number("JAVA_VOICE_LIFECYCLE_THREADS", 32, 4, 128);
        var starts = new ThreadPoolExecutor(lifecycleThreads, lifecycleThreads, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1024), r -> daemon(r, "voice-media"), new ThreadPoolExecutor.AbortPolicy());
        starts.allowCoreThreadTimeOut(true); lifecycle = starts;
        int terminationThreads = (int)settings.number("JAVA_VOICE_TERMINATION_THREADS", 16, 4, 64);
        var stops = new ThreadPoolExecutor(terminationThreads, terminationThreads, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1024), r -> daemon(r, "voice-stop"), new ThreadPoolExecutor.AbortPolicy());
        stops.allowCoreThreadTimeOut(true); termination = stops;
        maintenance = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "voice-reconcile"));
        for (int i = 0; i < 32; i++) eventWorkers.add(new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2048), r -> daemon(r, "voice-event")));
        if (real() && settings.get("FREESWITCH_TTS_HTTP_ENDPOINT", "").isEmpty() && (settings.get("FREESWITCH_TTS_ENGINE", "").isEmpty() || settings.get("FREESWITCH_TTS_VOICE", "").isEmpty())) { close(); throw new IllegalArgumentException("FreeSWITCH requires TTS for recording notices"); }
    }
    private static Thread daemon(Runnable run, String name) { Thread thread = new Thread(run, name); thread.setDaemon(true); return thread; }
    boolean real() { return settings.get("VOICE_GATEWAY_DRIVER", "mock").equals("freeswitch_esl"); }
    void start() { callbacks.start(); if (real()) { listener.submit(this::listen); maintenance.scheduleWithFixedDelay(this::reconcile, 2, 2, TimeUnit.SECONDS); } }
    boolean ready() {
        if (draining || !Boolean.TRUE.equals(recordings.disk().get("safe"))) return false;
        try {
            var state = ledger.summary(); if (Boolean.TRUE.equals(state.get("stopped")) || ((Number)state.get("oldest_callback_age_sec")).doubleValue() >= settings.number("VOICE_CALLBACK_FAILURE_STOP_SEC", 30, 1, 60)) return false;
            return !real() || (!security.routes.isEmpty() && subscribed && (media == null || media.ready()) && !esl.command("status", false).isBlank());
        } catch (Exception error) { return false; }
    }
    Map<String,Object> post(String action, Map<String,Object> payload) throws Exception {
        Support.required(payload, "call_id", 128);
        if (!Set.of("dial", "speak", "stop-speaking", "transfer", "hangup", "status").contains(action)) throw new Support.Failure(404, "unsupported call action");
        if (action.equals("dial") && draining) throw new Support.Failure(503, "voice gateway is draining");
        if (!real()) {
            if (action.equals("dial")) {
                String url = Support.required(payload, "webhook_url", 8192); security.callback(url);
                for (String status : List.of("dialing", "answered")) { Map<String,Object> data = new HashMap<>(Support.map(payload.get("metadata"))); data.put("status", status); callbacks.post(url, Map.of("call_id", payload.get("call_id"), "kind", "status", "payload", data)); }
            }
            return Map.of("result", "accepted", "action", action, "provider_call_id", "gateway-" + payload.get("call_id"));
        }
        try { return realPost(action, payload); }
        catch (Support.Failure error) { ledger.transaction(db -> { LocalLedger.execute(db, "INSERT INTO audit(at,action,detail) VALUES (?,'reject',?)", LocalLedger.now(), error.getMessage()); return null; }); throw error; }
    }
    private Map<String,Object> realPost(String action, Map<String,Object> payload) throws Exception {
        if (action.equals("dial")) {
            var route = security.policy(payload);
            String pipeline = pipeline(Support.map(payload.get("metadata")));
            if ("human_only".equals(Support.map(payload.get("metadata")).get("mode")) && Support.integer(Support.map(payload.get("metadata")), "human_agent_id", 0) > 0) agent(Support.map(payload.get("metadata")), Support.integer(Support.map(payload.get("metadata")), "human_agent_id", 0));
            var admission = security.admit(payload, route, Boolean.TRUE.equals(recordings.disk().get("safe")), media == null ? Integer.MAX_VALUE : media.ready() ? media.capacity() : 0);
            var row = admission.row(); if (!admission.fresh()) return row.get("result") == null ? Map.of("result", "pending_reconciliation", "provider_call_id", row.get("uuid")) : Support.object(row.get("result").toString().getBytes(StandardCharsets.UTF_8));
            var binding = new Binding(row); binding.pipeline = pipeline; bindings.put(binding.uuid, binding);
            Map<String,String> vars = new LinkedHashMap<>();
            vars.put("origination_uuid", binding.uuid); vars.put("originate_timeout", settings.get("FREESWITCH_ORIGINATE_TIMEOUT_SEC", "45")); vars.put("ignore_early_media", "true");
            long duration = Math.min(Support.integer(route, "max_duration_sec", 300), settings.number("VOICE_MAX_DURATION_SEC", 300, 1, 3600));
            vars.put("execute_on_answer", "'sched_hangup +" + duration + " ALLOTTED_TIMEOUT'"); vars.put("hangup_after_bridge", "true");
            vars.put("platform_call_id_b64", b64(binding.call)); vars.put("platform_status_webhook_b64", b64(binding.statusUrl)); vars.put("platform_metadata_b64", b64(new String(Support.json(binding.metadata), StandardCharsets.UTF_8)));
            String caller = Support.str(route, "caller_id", ""); vars.put("origination_caller_id_number", caller); vars.put("origination_caller_id_name", caller);
            for (String key : List.of("recording", "speech", "media")) if (binding.metadata.get(key + "_webhook_url") != null) vars.put("platform_" + key + "_webhook_b64", b64(String.valueOf(binding.metadata.get(key + "_webhook_url"))));
            String block = "{" + String.join(",", vars.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()) + "}";
            String destination = "sofia/gateway/" + Support.str(route, "gateway", "") + "/" + Support.required(payload, "phone", 32);
            String command = "originate " + block + ("human_only".equals(binding.metadata.get("mode")) && Support.integer(binding.metadata, "human_agent_id", 0) > 0 ? agent(binding.metadata, Support.integer(binding.metadata, "human_agent_id", 0)) + " &bridge(" + quote(destination) + ")" : destination + " &park()");
            String job = esl.command(command, true);
            Map<String,Object> result = Map.of("result", "accepted", "provider_call_id", binding.uuid, "job_uuid", job, "voice_ai_pipeline", binding.pipeline);
            ledger.transaction(db -> { LocalLedger.execute(db, "UPDATE attempts SET result=?,state=CASE WHEN state='ended' THEN state ELSE 'active' END WHERE uuid=?", new String(Support.canonical(result), StandardCharsets.UTF_8), binding.uuid); return null; }); return result;
        }
        long tenant = Support.integer(payload, "tenant_id", 0); if (tenant <= 0) throw new Support.Failure(403, "call control requires tenant identity");
        var row = security.lookup(Support.required(payload, "call_id", 128), tenant); String uuid = Support.str(row, "uuid", "");
        if (payload.get("expected_attempt") != null && Support.integer(payload, "expected_attempt", -1) != Support.integer(row, "attempt", 0)) throw new Support.Failure(409, "stale call attempt");
        if (payload.get("provider_call_id") != null && !uuid.equals(payload.get("provider_call_id"))) throw new Support.Failure(409, "stale provider call");
        String expected = payload.get("expected_speech_event_id") == null ? null : payload.get("expected_speech_event_id").toString();
        if (!row.get("state").equals("ended") && expected != null && Set.of("speak", "hangup", "stop-speaking").contains(action)) { if (media == null) throw new Support.Failure(409, "no media generation is active"); media.fence(Support.str(row, "call_id", ""), expected, action.equals("hangup")); }
        if (Set.of("status", "hangup").contains(action)) {
            if (row.get("state").equals("ended")) return Map.of("result", "hungup", "ended", true, "provider_call_id", uuid);
            if (action.equals("hangup")) try { esl.command("uuid_kill " + uuid + " NORMAL_CLEARING", false); } catch (IOException ambiguous) { }
            String exists = Support.asciiLower(esl.command("uuid_exists " + uuid, false).strip());
            if (exists.equals("true")) ledger.transaction(db -> { LocalLedger.execute(db, "UPDATE attempts SET observed=1 WHERE uuid=?", uuid); return null; });
            boolean ended = exists.equals("false") && Support.integer(row, "observed", 0) != 0 && LocalLedger.now() >= ((Number)row.get("deadline")).doubleValue();
            if (ended) {
                Binding finished = bindings.get(uuid);
                if (finished != null) stopMedia(finished);
                security.finish(uuid, null); bindings.remove(uuid);
            }
            return Map.of("result", ended ? "hungup" : "pending", "ended", ended, "provider_call_id", uuid);
        }
        if (row.get("state").equals("ended")) throw new Support.Failure(409, "call has ended");
        Binding binding = bindings.get(uuid); if (binding == null) throw new Support.Failure(409, "call binding requires authenticated PBX reconstruction");
        if (action.equals("stop-speaking")) { if (binding.pipeline.equals("pipecat")) media.interrupt(binding.call, expected); else { esl.command("uuid_break " + uuid + " all", false); media(binding, "interrupted", null, null); } return binding.pipeline.equals("pipecat") ? Map.of("result", "stopped", "provider_call_id", uuid, "pipeline", "pipecat") : Map.of("result", "stopped", "provider_call_id", uuid); }
        if (action.equals("transfer")) {
            String target = Support.required(payload, "target_group", 128); if (!target.matches("agent:[1-9][0-9]*")) throw new Support.Failure(403, "transfer requires an authorized agent ID");
            String destination = agent(binding.metadata, Long.parseLong(target.substring(6))); stopMedia(binding); binding.metadata.put("human_target", target);
            esl.command("uuid_break " + uuid + " all", false);
            if (destination.startsWith("sofia/")) esl.command("uuid_setvar " + uuid + " platform_agent_route " + destination, false);
            long agentId = Long.parseLong(target.substring(6)); String extension = template(settings.get("FREESWITCH_AGENT_EXTENSION_TEMPLATE", "agent_{agent_id}"), Map.of("agent_id", agentId, "tenant_id", tenant));
            esl.command("uuid_transfer " + uuid + " " + extension + " XML agent-restricted", false); return Map.of("result", "transferred", "provider_call_id", uuid, "target_group", target, "destination", extension);
        }
        String text = Support.required(payload, "text", 50000), commandId = Support.str(payload, "command_id", "");
        if (!commandId.isEmpty()) {
            if (commandId.length() > 128) throw new Support.Failure(422, "invalid command identity");
            var outgoing = new LinkedHashMap<>(payload); outgoing.put("expected_attempt", row.get("attempt")); outgoing.put("provider_call_id", uuid);
            String digest = Support.hex(Support.sha(Support.canonicalAscii(List.of(action, outgoing))));
            var previous = ledger.transaction(db -> {
                var rows = LocalLedger.query(db, "SELECT * FROM voice_action_commands WHERE id=?", commandId);
                if (!rows.isEmpty()) { var saved = rows.get(0); if (!digest.equals(saved.get("digest"))) throw new Support.Failure(409, "business command identity conflict"); if (saved.get("result") == null) return Map.<String,Object>of("java_pending_reconciliation", true); return Support.object(saved.get("result").toString().getBytes(StandardCharsets.UTF_8)); }
                LocalLedger.execute(db, "INSERT INTO voice_action_commands(id,digest,call_id,attempt) VALUES (?,?,?,?)", commandId, digest, binding.call, row.get("attempt")); return null;
            });
            if (previous != null) {
                if (!Boolean.TRUE.equals(previous.get("java_pending_reconciliation"))) return previous;
                // Query the original media operation outside the SQLite transaction; never replay an unknown synthesis.
                if (binding.pipeline.equals("pipecat") && !"fallback-audio".equals(payload.get("provider"))) {
                    var confirmed = media.lookup(binding.call, commandId, text);
                    if (Boolean.TRUE.equals(confirmed.get("confirmed"))) {
                        var recovered = Map.<String,Object>of("result", "queued", "provider_call_id", uuid, "playback_id", confirmed.get("playback_id"), "pipeline", "pipecat");
                        ledger.transaction(db -> { LocalLedger.execute(db, "UPDATE voice_action_commands SET result=? WHERE id=? AND digest=?", new String(Support.canonical(recovered), StandardCharsets.UTF_8), commandId, digest); return null; });
                        return recovered;
                    }
                }
                throw new Support.Failure(409, "business command outcome unknown; reconcile before retry", Map.of("X-Voice-Outcome", "unknown"));
            }
        }
        if (binding.pipeline.equals("pipecat") && !"fallback-audio".equals(payload.get("provider"))) {
            String playback = media.speak(binding.call, text, expected, commandId);
            Map<String,Object> result = Map.of("result", "queued", "provider_call_id", uuid, "playback_id", playback, "pipeline", "pipecat");
            if (!commandId.isEmpty()) ledger.transaction(db -> { LocalLedger.execute(db, "UPDATE voice_action_commands SET result=? WHERE id=?", new String(Support.canonical(result), StandardCharsets.UTF_8), commandId); return null; });
            return result;
        }
        String uri = payload.get("provider") != null && payload.get("provider").equals("fallback-audio") ? settings.get("FREESWITCH_FALLBACK_AUDIO_PATH", "") : tts(payload);
        if ("fallback-audio".equals(payload.get("provider")) && (!uri.startsWith("/") || !uri.endsWith(".wav") || Arrays.asList(uri.split("/")).contains(".."))) throw new Support.Failure(503, "invalid installed fallback prompt");
        String playback = UUID.randomUUID().toString(); media(binding, "speaking", playback, null); esl.command("uuid_broadcast " + uuid + " " + quote(uri) + " aleg", false);
        Map<String,Object> result = Map.of("result", "playing", "provider_call_id", uuid, "playback_id", playback);
        if (!commandId.isEmpty()) ledger.transaction(db -> { LocalLedger.execute(db, "UPDATE voice_action_commands SET result=? WHERE id=?", new String(Support.canonical(result), StandardCharsets.UTF_8), commandId); return null; });
        return result;
    }
    private String agent(Map<String,Object> metadata, long id) {
        if (id < 1) throw new Support.Failure(400, "approved agent identity required");
        long tenant = Support.integer(metadata, "tenant_id", 0); String extension = template(settings.get("FREESWITCH_AGENT_EXTENSION_TEMPLATE", "agent_{agent_id}"), Map.of("agent_id", id, "tenant_id", tenant));
        if (!extension.matches("[A-Za-z0-9_.@+-]+")) throw new Support.Failure(400, "invalid agent extension");
        var roster = Support.object(settings.get("VOICE_AGENT_REGISTRARS_JSON", "{}").getBytes(StandardCharsets.UTF_8)); Object registrar = roster.get(tenant + ":" + id);
        if (registrar instanceof String value && value.matches("[A-Za-z0-9][A-Za-z0-9.-]*:[0-9]{1,5}")) { int port = Integer.parseInt(value.substring(value.lastIndexOf(':') + 1)); if (port >= 1 && port <= 65535) return "sofia/internal/" + extension + "@" + value; }
        if (!settings.get("VOICE_NODE_ID", "").isEmpty()) throw new Support.Failure(400, "approved agent registrar route is required"); return "user/" + extension;
    }
    static String oneLine(String value) { if (value.contains("\r") || value.contains("\n")) throw new Support.Failure(400, "PBX argument contains newline"); return value.strip(); }
    static String quote(String value) { value = oneLine(value); return value.matches("[^\\s'\"\\\\]+") ? value : "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"; }
    static String template(String template, Map<String,?> values) {
        for (var value : values.entrySet()) template = template.replace("{" + value.getKey() + "}", oneLine(String.valueOf(value.getValue())));
        if (template.contains("{") || template.contains("}")) throw new Support.Failure(503, "unresolved PBX template"); return oneLine(template);
    }
    private static String b64(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private String tts(Map<String,Object> payload) throws Exception {
        String endpoint = settings.get("FREESWITCH_TTS_HTTP_ENDPOINT", "");
        if (!endpoint.isEmpty()) {
            var builder = HttpRequest.newBuilder(Support.endpoint(endpoint)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
            String token = settings.secret("FREESWITCH_TTS_HTTP_TOKEN"); if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
            var response = http.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(Support.json(payload))).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var input = Support.timed(response.body(), 30000)) { if (response.statusCode() / 100 != 2) throw new Support.Failure(503, "TTS service unavailable"); var body = Support.object(Support.read(input, 65536)); String uri = Support.str(body, "media_uri", Support.str(body, "url", "")); if (uri.isEmpty()) throw new Support.Failure(503, "TTS service returned no media URI"); return oneLine(uri); }
        }
        String engine = Support.str(payload, "provider", settings.get("FREESWITCH_TTS_ENGINE", "")), voice = Support.str(payload, "voice", settings.get("FREESWITCH_TTS_VOICE", ""));
        if (engine.isEmpty()) engine = settings.get("FREESWITCH_TTS_ENGINE", ""); if (voice.isEmpty()) voice = settings.get("FREESWITCH_TTS_VOICE", "");
        if (!engine.matches("[A-Za-z0-9_.@+-]+") || !voice.matches("[A-Za-z0-9_.@+-]+")) throw new Support.Failure(503, "invalid native TTS configuration");
        return template(settings.get("FREESWITCH_TTS_URI_TEMPLATE", "speak:{engine}|{voice}|{text}"), Map.of("engine", engine, "voice", voice, "text", oneLine(Support.required(payload, "text", 50000)).replace('|', ' '), "language", Support.str(payload, "language", "zh-CN")));
    }
    private void listen() {
        while (running) {
            try (Socket socket = esl.subscribe(this::enqueue)) { eventSocket = socket; subscribed = true; esl.events(socket, this::enqueue); }
            catch (Exception unavailable) { subscribed = false; }
            finally { eventSocket = null; }
            if (running) try { Thread.sleep((long)(settings.decimal("FREESWITCH_ESL_RECONNECT_SEC", 2, .1, 60) * 1000)); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; }
        }
    }
    private void enqueue(Map<String,Object> event) {
        String uuid = Support.str(event, "Unique-ID", Support.str(event, "Channel-Call-UUID", Support.str(event, "variable_origination_uuid", "")));
        if (!eventSlots.tryAcquire()) { ledger.stopped(true, "ESL event capacity reached"); try { eventSlots.acquire(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("ESL stopped"); } }
        eventWorkers.get(Math.floorMod(uuid.hashCode(), eventWorkers.size())).execute(() -> { try { event(event); } catch (Exception error) { ledger.stopped(true, "ESL event processing failed"); } finally { eventSlots.release(); } });
    }
    void event(Map<String,Object> event) throws Exception {
        String name = Support.str(event, "Event-Name", ""), uuid = Support.str(event, "Unique-ID", Support.str(event, "Channel-Call-UUID", Support.str(event, "variable_origination_uuid", "")));
        if (name.equals("BACKGROUND_JOB")) {
            String job = Support.str(event, "Job-UUID", ""), result = Support.str(event, "_body", Support.str(event, "Body", ""));
            if (job.isEmpty() || !result.startsWith("-ERR")) return;
            var attempts = ledger.read(db -> LocalLedger.query(db, "SELECT * FROM attempts WHERE state!='ended' AND json_extract(result,'$.job_uuid')=?", job));
            if (!attempts.isEmpty()) {
                var row = attempts.get(0); var failed = bindings.computeIfAbsent(row.get("uuid").toString(), key -> { var value = new Binding(row); value.pipeline = pipeline(value.metadata); return value; });
                callbacks.atomic(() -> { status(failed, "failed", event, "PBX_ORIGINATE_REJECTED"); security.finish(failed.uuid, 0L); }); bindings.remove(failed.uuid);
            }
            return;
        }
        var rows = ledger.read(db -> LocalLedger.query(db, "SELECT * FROM attempts WHERE uuid=? AND state!='ended'", uuid)); if (rows.isEmpty()) return;
        Binding binding = bindings.computeIfAbsent(uuid, key -> { Binding restored = new Binding(rows.get(0)); restored.pipeline = pipeline(restored.metadata); return restored; });
        if (Set.of("CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_PROGRESS_MEDIA", "CHANNEL_ANSWER", "CHANNEL_HANGUP", "CHANNEL_HANGUP_COMPLETE").contains(name)) ledger.transaction(db -> { LocalLedger.execute(db, "UPDATE attempts SET observed=1 WHERE uuid=?", uuid); return null; });
        switch(name) {
            case "CHANNEL_CREATE", "CHANNEL_PROGRESS", "CHANNEL_PROGRESS_MEDIA" -> status(binding, "dialing", event, "");
            case "CHANNEL_ANSWER" -> { binding.answeredAt = System.currentTimeMillis(); boolean human = "human_only".equals(binding.metadata.get("mode")) && Support.integer(binding.metadata, "human_agent_id", 0) > 0; status(binding, human ? "agent_answered" : "answered", event, ""); if (!human) scheduleMedia(binding); }
            case "CHANNEL_BRIDGE" -> { if (binding.metadata.containsKey("human_target") || binding.metadata.containsKey("human_agent_id")) { binding.human = true; status(binding, "human_connected", event, ""); scheduleMedia(binding); } }
            case "CHANNEL_UNBRIDGE" -> { if (binding.human) status(binding, "human_disconnected", event, ""); }
            case "DTMF" -> { String digit = Support.str(event, "DTMF-Digit", ""), stamp = stamp(event); if (digit.matches("[0-9*#]") && binding.metadata.containsKey("dtmf_webhook_url")) callbacks.post(binding.metadata.get("dtmf_webhook_url").toString(), Map.of("call_id", binding.call, "kind", "dtmf", "payload", Map.of("digit", digit, "event_id", "fs:" + uuid + ":dtmf:" + stamp, "attempt", binding.metadata.get("attempt")))); }
            case "CHANNEL_EXECUTE_COMPLETE" -> { String app = Support.str(event, "Application", Support.str(event, "variable_current_application", "")); if (Set.of("playback", "speak").contains(app)) { var notice = notices.remove(uuid); if (notice != null) notice.complete(null); media(binding, "listening", null, null); } if (app.equals("bridge") && binding.metadata.containsKey("human_target") && !binding.human) status(binding, "human_unavailable", event, Support.str(event, "Application-Response", "agent did not answer")); }
            case "CUSTOM" -> { String kind = Support.str(event, "Event-Subclass", ""), prefix = "mod_openai_audio_stream::"; if (!binding.stopped && media != null && kind.startsWith(prefix)) { String stamp = Support.str(event, "Event-Date-Timestamp", "0"); media.module(binding.call, kind.substring(prefix.length()), stamp.matches("[0-9]+") ? Long.parseLong(stamp) : 0); } }
            case "CHANNEL_HANGUP" -> stopMedia(binding);
            case "CHANNEL_HANGUP_COMPLETE" -> {
                stopMedia(binding); String bill = Support.str(event, "variable_billsec", "");
                String cause = Support.str(event, "Hangup-Cause", Support.str(event, "variable_hangup_cause", "UNKNOWN"));
                String state = Set.of("USER_BUSY", "CALL_REJECTED").contains(cause) ? "busy" : Set.of("NO_ANSWER", "NO_USER_RESPONSE", "SUBSCRIBER_ABSENT", "ALLOTTED_TIMEOUT").contains(cause) ? "no_answer" : Set.of("NORMAL_CLEARING", "ORIGINATOR_CANCEL").contains(cause) ? "ended" : "failed";
                callbacks.atomic(() -> {
                    status(binding, binding.failure.isEmpty() ? state : "failed", event, binding.failure.isEmpty() ? cause : binding.failure); media(binding, "closed", null, null);
                    try { recording(binding); } catch (Exception error) { throw new Support.Failure(503, "recording completion unavailable; terminal reservation retained"); }
                    security.finish(uuid, bill.matches("[0-9]+") ? Long.parseLong(bill) : null);
                }); bindings.remove(uuid);
            }
            default -> { }
        }
    }
    private static String stamp(Map<String,Object> event) { return Support.str(event, "Event-Date-Timestamp", Support.str(event, "Event-Sequence", UUID.randomUUID().toString())); }
    private void status(Binding binding, String state, Map<String,Object> event, String reason) {
        Map<String,Object> data = new HashMap<>(binding.metadata); data.put("status", state); data.put("telephony_call_id", binding.uuid); data.put("event_id", "fs:" + binding.uuid + ":" + state + ":" + stamp(event)); data.put("voice_ai_pipeline", binding.pipeline);
        String bill = Support.str(event, "variable_billsec", ""); if (bill.matches("[0-9]+")) data.put("billable_duration_sec", Long.parseLong(bill)); if (!reason.isEmpty()) data.put("hangup_reason", reason);
        callbacks.post(binding.statusUrl, Map.of("call_id", binding.call, "kind", "status", "payload", data));
    }
    private void media(Binding binding, String state, String playback, String error) {
        String url = Support.str(binding.metadata, "media_webhook_url", ""); if (url.isEmpty()) return;
        Map<String,Object> data = new LinkedHashMap<>(); data.put("call_id", binding.call); data.put("event_id", "fs:" + binding.uuid + ":media:" + state + ":" + UUID.randomUUID()); data.put("event_sequence", System.currentTimeMillis() * 1000); data.put("state", state); data.put("provider_session_id", binding.uuid); data.put("playback_id", playback); data.put("error_code", error); data.put("codec", "PCMA"); data.put("sample_rate", 8000); data.put("channel_count", 1); data.put("duration_ms", state.equals("closed") && binding.answeredAt != 0 ? Math.max(0, System.currentTimeMillis() - binding.answeredAt) : null); data.put("provider", "freeswitch"); data.put("attempt", binding.metadata.get("attempt")); callbacks.post(url, data);
    }
    private void scheduleMedia(Binding binding) {
        synchronized (binding) {
            if (binding.stopped) return;
            var completion = new CompletableFuture<Void>();
            if (mediaTasks.putIfAbsent(binding.uuid, completion) != null) return;
            completion.whenComplete((ignored, error) -> {
                if (error != null && !binding.stopped) try { termination.execute(() -> startupFailed(binding)); }
                catch (RejectedExecutionException overloaded) { draining = true; }
            });
            runLifecycle(binding, completion, () -> startMedia(binding, completion));
        }
    }
    @FunctionalInterface private interface Startup { void run() throws Exception; }
    private void runLifecycle(Binding binding, CompletableFuture<Void> completion, Startup action) {
        try { lifecycle.execute(() -> { if (!binding.stopped && !completion.isDone()) try { action.run(); } catch (Exception error) { completion.completeExceptionally(error); } }); }
        catch (RejectedExecutionException full) { completion.completeExceptionally(full); }
    }
    private void startupFailed(Binding binding) {
        binding.failure = "MEDIA_STARTUP_FAILED";
        try { stopMedia(binding); esl.command("uuid_kill " + binding.uuid + " NORMAL_TEMPORARY_FAILURE", false); }
        catch (Exception unavailable) { ledger.stopped(true, "failed media startup could not stop PBX"); }
        media(binding, "closed", null, "MEDIA_STARTUP_FAILED");
    }
    private void startMedia(Binding binding, CompletableFuture<Void> completion) throws Exception {
        if (!Boolean.FALSE.equals(binding.metadata.get("recording_enabled")) && binding.recordingPath.isEmpty()) {
            if (Boolean.TRUE.equals(binding.metadata.get("recording_notice"))) {
                String uri = tts(Map.of("call_id", binding.call, "text", Support.str(binding.metadata, "recording_notice_text", "本次通话可能会被录音，用于服务和质量管理。"), "language", Support.str(binding.metadata, "language", "zh-CN")));
                var notice = new CompletableFuture<Void>(); notices.put(binding.uuid, notice);
                synchronized (binding) {
                    if (binding.stopped) { notices.remove(binding.uuid, notice); return; }
                    media(binding, "speaking", "notice:" + binding.uuid, null);
                    try { esl.command("uuid_broadcast " + binding.uuid + " " + quote(uri) + " both", false); }
                    catch (Exception error) { notices.remove(binding.uuid, notice); throw error; }
                }
                notice.orTimeout((long)(settings.decimal("FREESWITCH_PLAYBACK_TIMEOUT_SEC", 30, 1, 300) * 1000), TimeUnit.MILLISECONDS)
                    .whenComplete((ignored, error) -> {
                        notices.remove(binding.uuid, notice);
                        if (error != null) completion.completeExceptionally(error);
                        else runLifecycle(binding, completion, () -> afterNotice(binding, completion));
                    });
                return;
            }
        }
        afterNotice(binding, completion);
    }
    private void afterNotice(Binding binding, CompletableFuture<Void> completion) throws Exception {
        // Serialise commands which can create resources with the stop fence; never wait for an event under this monitor.
        synchronized (binding) {
            if (binding.stopped) return;
            if (!Boolean.FALSE.equals(binding.metadata.get("recording_enabled")) && binding.recordingPath.isEmpty()) {
                String filename = binding.call.replaceAll("[^A-Za-z0-9_.-]", "_") + "-" + binding.uuid + ".wav";
                String path = Path.of(settings.get("FREESWITCH_RECORDING_DIR", "/recordings")).resolve(filename).toString();
                esl.command("uuid_record " + binding.uuid + " start " + quote(path), false); binding.recordingPath = path;
            }
            if (binding.human) { completion.complete(null); return; }
            if (binding.pipeline.equals("pipecat")) {
                try { startNativeMedia(binding, completion); return; }
                catch (Exception unavailable) {
                    if (!settings.flag("PIPECAT_FALLBACK_TO_LEGACY", false)) throw unavailable;
                    // An ambiguous start may have reached the PBX: confirm cleanup before starting another pipeline.
                    if (settings.get("PIPECAT_MEDIA_PROTOCOL", "raw_pcm").equals("voismart")) esl.command("uuid_raw_audio_stream " + binding.uuid + " stop", false);
                    else {
                        String stop = settings.get("FREESWITCH_MEDIA_STOP_COMMAND_TEMPLATE", "");
                        if (stop.isBlank()) throw new IOException("native fallback requires confirmed module cleanup", unavailable);
                        esl.command(template(stop, Map.of("uuid", binding.uuid, "call_id", binding.call)), false);
                    }
                    media.closeSession(binding.call, false); binding.pipeline = "legacy";
                }
            }
            startLegacyMedia(binding); completion.complete(null);
        }
    }
    private void startLegacyMedia(Binding binding) throws Exception {
        String command = settings.get("FREESWITCH_MEDIA_START_COMMAND_TEMPLATE", "");
        if (!command.isEmpty()) esl.command(template(command, Map.of("uuid", binding.uuid, "call_id", binding.call, "speech_webhook_url", Support.str(binding.metadata, "speech_webhook_url", ""), "media_webhook_url", Support.str(binding.metadata, "media_webhook_url", ""), "asr_provider", Support.str(binding.metadata, "asr_provider", ""), "language", Support.str(binding.metadata, "language", "zh-CN"))), false);
        media(binding, "listening", null, null);
    }
    private void stopMedia(Binding binding) throws Exception {
        synchronized (binding) {
            binding.stopped = true;
            var task = mediaTasks.remove(binding.uuid); if (task != null) task.cancel(false);
            var notice = notices.remove(binding.uuid); if (notice != null) notice.cancel(false);
        }
        String stop = settings.get("FREESWITCH_MEDIA_STOP_COMMAND_TEMPLATE", "");
        try { if (binding.pipeline.equals("pipecat") && settings.get("PIPECAT_MEDIA_PROTOCOL", "raw_pcm").equals("voismart")) esl.command("uuid_raw_audio_stream " + binding.uuid + " stop", false); else if (!stop.isEmpty()) esl.command(template(stop, Map.of("uuid", binding.uuid, "call_id", binding.call)), false); }
        finally { if (media != null && binding.pipeline.equals("pipecat")) media.closeSession(binding.call, true); }
    }
    private void recording(Binding binding) throws Exception {
        String target = Support.str(binding.metadata, "recording_webhook_url", ""); if (target.isEmpty() || binding.recordingPath.isEmpty()) return;
        String filename = Path.of(binding.recordingPath).getFileName().toString(); if (!settings.secret("VOICE_RECORDING_CLEANUP_TOKEN").isEmpty()) recordings.complete(filename);
        String url = !settings.get("VOICE_RECORDING_SOURCE_BASE_URL", "").isEmpty() ? recordings.url(filename) : settings.get("FREESWITCH_RECORDING_PUBLIC_BASE_URL", "").replaceAll("/+$", "") + "/" + filename;
        Map<String,Object> data = new LinkedHashMap<>(); data.put("url", url); data.put("recording_id", binding.uuid); data.put("provider", "freeswitch"); data.put("duration_sec", binding.answeredAt == 0 ? null : Math.max(0, (System.currentTimeMillis() - binding.answeredAt) / 1000)); data.put("attempt", binding.metadata.get("attempt"));
        data.put("format", "wav"); data.put("channel_count", settings.flag("FREESWITCH_RECORDING_STEREO", true) ? 2 : 1); data.put("state", "available"); data.remove("provider");
        callbacks.post(target, Map.of("call_id", binding.call, "kind", "recording", "payload", data));
    }
    private void reconcile() {
        try { var rows = ledger.read(db -> LocalLedger.query(db, "SELECT call_id,tenant FROM attempts WHERE state!='ended' AND deadline<=? LIMIT 200", LocalLedger.now())); for (var row : rows) try { post("hangup", Map.of("call_id", row.get("call_id"), "tenant_id", row.get("tenant"))); } catch (Exception unavailable) { } }
        catch (Exception unavailable) { }
        if (media != null) for (var session : media.sessions()) {
            var binding = bindings.values().stream().filter(b -> b.call.equals(session.call) && Support.integer(b.metadata, "attempt", 0) == Support.integer(session.metadata, "attempt", 0)).findFirst().orElse(null);
            if (binding != null && session.terminated.isDone() && !binding.stopped) try { binding.failure = session.error; post("hangup", Map.of("call_id", binding.call, "tenant_id", session.metadata.get("tenant_id"), "expected_attempt", session.metadata.get("attempt"), "provider_call_id", binding.uuid)); } catch (Exception ignored) { }
        }
    }
    @Override public void close() throws Exception {
        running = false; draining = true; if (eventSocket != null) eventSocket.close(); listener.shutdownNow(); maintenance.shutdownNow();
        for (var binding : bindings.values()) try { stopMedia(binding); } catch (Exception ignored) { }
        lifecycle.shutdownNow(); termination.shutdownNow(); for (var worker : eventWorkers) worker.shutdownNow(); if (media != null) media.close(); callbacks.close(); ledger.close();
    }
    private String pipeline(Map<String,Object> metadata) {
        String configured = settings.get("VOICE_AI_PIPELINE", "legacy"), requested = Support.str(metadata, "voice_ai_pipeline", "");
        if (!requested.isEmpty() && !Set.of("legacy", "pipecat").contains(requested)) throw new Support.Failure(422, "invalid call media pipeline");
        if (configured.equals("hybrid")) return requested.isEmpty() ? "legacy" : requested;
        if (!requested.isEmpty() && !requested.equals(configured)) throw new Support.Failure(409, "call media pipeline does not match gateway"); return configured;
    }
    private void localPermit(MediaManager.Session session, String id, String kind, boolean acquire, boolean confirmed) {
        if (!settings.flag("VOICE_QUOTA_ENABLED", false)) return;
        String owner = new String(Support.json(List.of("", "", session.id)), StandardCharsets.UTF_8); VoiceQuota quota = new VoiceQuota(settings, ledger);
        ledger.transaction(db -> { if (acquire) quota.acquire(db, id, kind, session.call, Support.integer(session.metadata, "attempt", 0), owner); else quota.release(db, id, owner, confirmed); return null; });
    }
    private void startNativeMedia(Binding binding, CompletableFuture<Void> completion) throws Exception {
        var session = media.create(binding.call, Support.str(binding.metadata, "speech_webhook_url", ""), Support.str(binding.metadata, "media_webhook_url", ""), binding.metadata);
        boolean voismart = settings.get("PIPECAT_MEDIA_PROTOCOL", "raw_pcm").equals("voismart");
        String command = settings.get("FREESWITCH_PIPECAT_START_COMMAND_TEMPLATE", "");
        if (command.isEmpty() && voismart) command = "uuid_raw_audio_stream {uuid} start {media_ws_url} mono {sample_rate} {sample_rate}";
        if (command.isEmpty()) throw new Support.Failure(503, "native media start command required");
        if (voismart) for (String variable : List.of("STREAM_DISABLE_AUDIOFILES", "STREAM_NO_RECONNECT", "STREAM_SUPPRESS_LOG")) esl.command("uuid_setvar " + binding.uuid + " " + variable + " true", false);
        esl.command(template(command, Map.of("uuid", binding.uuid, "call_id", binding.call, "session_id", session.id, "media_ws_url", media.url(session), "sample_rate", settings.number("PIPECAT_SAMPLE_RATE", 8000, 8000, 48000), "channels", 1, "codec", "pcm_s16le")), false);
        if (voismart) esl.command("uuid_broadcast " + binding.uuid + " silence_stream://-1 aleg", false);
        session.started.orTimeout((long)(settings.decimal("PIPECAT_MEDIA_CONNECT_TIMEOUT_SEC", 15, .1, 300) * 1000), TimeUnit.MILLISECONDS)
            .whenComplete((ignored, error) -> {
                if (error != null || session.terminated.isDone()) completion.completeExceptionally(error == null ? new IOException("media terminated during startup") : error);
                else completion.complete(null);
            });
        session.terminated.thenRunAsync(() -> {
            if (!binding.stopped) try { String exists = esl.command("uuid_exists " + binding.uuid, false); if (!exists.strip().equals("false") && !binding.stopped) { binding.failure = session.error.isEmpty() ? "MEDIA_DISCONNECTED" : session.error; esl.command("uuid_kill " + binding.uuid + " NORMAL_TEMPORARY_FAILURE", false); } }
            catch (Exception unavailable) { ledger.stopped(true, "media termination could not stop PBX"); }
        }, termination).exceptionally(overloaded -> { draining = true; return null; });
    }
}
