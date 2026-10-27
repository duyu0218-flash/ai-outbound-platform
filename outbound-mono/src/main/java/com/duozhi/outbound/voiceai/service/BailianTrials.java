package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.auth.dao.AuthIdentityMapper;
import com.duozhi.outbound.auth.domain.Identity;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.integration.config.UnifiedServiceSettings;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.system.service.Audit;
import com.duozhi.outbound.voiceai.dao.VoiceTrialMapper;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Bounded browser-only trials. Never creates a call, campaign, dial job or PBX command. */
@Service
public class BailianTrials {
    public final VoiceTrialMapper db;
    private final VoiceAiCredentials credentials;
    private final com.duozhi.outbound.integration.config.Settings root;
    private final Store store;
    private final AuthIdentityMapper identities;
    private final com.duozhi.outbound.system.service.Settings settings;
    private final Transactions tx;
    private final Audit audit;
    private final Map<String, Live> live = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "bailian-trial-expiry");
                        t.setDaemon(true);
                        return t;
                    });

    public BailianTrials(
            Config config,
            VoiceAiCredentials credentials,
            VoiceTrialMapper db,
            Store store,
            AuthIdentityMapper identities,
            com.duozhi.outbound.system.service.Settings settings,
            Transactions tx,
            Audit audit) {
        this.credentials = credentials;
        this.root = new UnifiedServiceSettings(config, "voice");
        this.db = db;
        this.store = store;
        this.identities = identities;
        this.settings = settings;
        this.tx = tx;
        this.audit = audit;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        db.recover();
        timer.scheduleWithFixedDelay(
                () -> {
                    for (var l : live.values())
                        try {
                            l.tick();
                        } catch (Exception e) {
                            try {
                                l.fail("会话已过期、账号已退出或供应商连接中断");
                            } catch (Exception persistenceUnavailable) {
                                // A failed session must not disable expiry for the remaining
                                // sessions.
                            }
                        }
                },
                2,
                2,
                TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        timer.shutdownNow();
        for (var l : live.values()) l.end("interrupted");
    }

    public Map<String, Object> readiness(long tenant) {
        var runtime = credentials.runtime(root, tenant, -1);
        var missing = new ArrayList<String>();
        for (String key :
                List.of(
                        "DASHSCOPE_API_KEY",
                        "VOICE_AI_BAILIAN_WORKSPACE_ID",
                        "VOICE_AI_BAILIAN_APP_ID",
                        "VOICE_AI_BAILIAN_APP_REVISION",
                        "VOICE_AI_BAILIAN_TOOL_API_KEY"))
            if (runtime.secret(key).isBlank()) missing.add(key);
        try {
            new ContentSafety(runtime).requireReady(ContentSafety.policy(runtime));
        } catch (Exception e) {
            missing.add("VOICE_AI_SAFETY_ACCESS_KEY_ID / VOICE_AI_SAFETY_ACCESS_KEY_SECRET");
        }
        return Map.of(
                "configured",
                missing.isEmpty(),
                "missing",
                missing,
                "mode",
                "push2talk",
                "real_calls_enabled",
                false,
                "verification",
                "配置齐全不代表真实供应商已验证",
                "revision",
                runtime.get("VOICE_AI_CONFIG_REVISION", "0"));
    }

    public synchronized Map<String, Object> create(Identity actor, Map<String, Object> body) {
        actor.requireUser();
        String key = text(body, "client_key", 80), script = text(body, "script", 12000);
        long contact = Support.integer(body, "contact_id", 0);
        store.require("contact", actor.tenant(), contact);
        if (!Boolean.TRUE.equals(body.get("consent")))
            throw new ApiError(400, "请确认使用测试客户并授权本次付费供应商试聊");
        String hash =
                Support.hex(
                        Support.sha(Support.json(Map.of("contact", contact, "script", script))));
        var prior = db.byKey(actor.tenant(), key);
        if (prior != null) {
            if (!hash.equals(prior.get("request_hash"))
                    || !actor.userId().toString().equals(prior.get("user_id").toString()))
                throw new ApiError(409, "请求标识已被其他内容使用");
            return Map.of("id", prior.get("id"), "replayed", true, "status", prior.get("status"));
        }
        var ready = readiness(actor.tenant());
        if (!Boolean.TRUE.equals(ready.get("configured")))
            throw new ApiError(503, "百炼试聊缺少配置：" + ready.get("missing"));
        if (live.size() >= 4
                || live.values().stream()
                        .anyMatch(
                                l ->
                                        l.tenant == actor.tenant()
                                                && l.user
                                                        == Long.parseLong(
                                                                actor.userId().toString())))
            throw new ApiError(429, "已有试聊运行，请先结束；平台最多四路试聊");
        var runtime = credentials.runtime(root, actor.tenant(), -1);
        String id = UUID.randomUUID().toString();
        var profile =
                Map.of(
                        "id",
                        "aliyun-multimodal-suite",
                        "mode",
                        "push2talk",
                        "config_revision",
                        runtime.get("VOICE_AI_CONFIG_REVISION", "0"),
                        "app_revision",
                        runtime.get("VOICE_AI_BAILIAN_APP_REVISION", ""),
                        "voice",
                        runtime.get("VOICE_AI_BAILIAN_VOICE", ""),
                        "safety",
                        ContentSafety.policy(runtime),
                        "telephony",
                        false);
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("tenant_id", actor.tenant());
        row.put("user_id", actor.userId());
        row.put("contact_id", contact);
        row.put("client_key", key);
        row.put("request_hash", hash);
        row.put("script_snapshot", script);
        row.put("profile_json", json(profile));
        db.create(row);
        var l = new Live(id, actor, runtime, script, settings.get(actor.tenant(), "ai"));
        live.put(id, l);
        audit.record(actor, "create", "bailian_trial", id, "browser only; no dialing");
        return Map.of("id", id, "ticket", l.ticket, "status", "created", "expires_in", 30);
    }

    public Map<String, Object> get(long tenant, String id) {
        var row = db.get(tenant, id);
        if (row == null) throw new ApiError(404, "试聊不存在");
        var result = new LinkedHashMap<>(row);
        result.put("audio", db.audios(tenant, id));
        return result;
    }

    public Live attach(
            String id, String ticket, Consumer<Map<String, Object>> sink, Runnable close) {
        Live l = live.get(id);
        if (l == null) throw new ApiError(401, "试聊凭证无效或已过期");
        synchronized (l) {
            if (l.attached
                    || System.nanoTime() - l.began > TimeUnit.SECONDS.toNanos(30)
                    || !equal(l.ticket, ticket)) throw new ApiError(401, "试聊凭证无效或已使用");
            l.authorize();
            l.attached = true;
            l.ticket = "";
            l.sink = sink;
            l.transportClose = close;
        }
        return l;
    }

    public void end(Identity actor, String id) {
        get(actor.tenant(), id);
        var l = live.get(id);
        if (l != null && l.tenant == actor.tenant()) l.end("completed");
    }

    public Map<String, Object> tool(String apiKey, Map<String, Object> body) {
        String id = text(body, "trial_id", 36);
        var l = live.get(id);
        if (l == null
                || !equal(l.toolToken, Objects.toString(body.get("trial_token"), ""))
                || !equal(l.runtime.secret("VOICE_AI_BAILIAN_TOOL_API_KEY"), apiKey))
            throw new ApiError(401, "工具凭证无效或会话已结束");
        synchronized (l) {
            l.authorize();
            if (!l.attached || l.closed) throw new ApiError(409, "试聊尚未连接或已结束");
            String action = text(body, "action", 40);
            if (action.equals("business_context"))
                return Map.of(
                        "script",
                        l.script,
                        "user_evidence",
                        l.events.stream().filter(e -> "user_text".equals(e.get("type"))).toList(),
                        "trial_only",
                        true,
                        "instruction",
                        "只登记试聊意向草稿，不承诺真实预约或自动拨号；保存后告知等待人工确认。");
            if (!action.equals("save_intent")) throw new ApiError(400, "不支持的试聊工具动作");
            String key = text(body, "event_id", 80),
                    intent = text(body, "intent", 30),
                    note = text(body, "note", 1000),
                    next = Objects.toString(body.get("next_at"), "");
            if (!Set.of("interested", "declined", "uncertain").contains(intent))
                throw new ApiError(400, "意向须为 interested / declined / uncertain");
            if (!next.isBlank())
                try {
                    if (!java.time.Instant.parse(next).isAfter(java.time.Instant.now()))
                        throw new IllegalArgumentException();
                } catch (Exception e) {
                    throw new ApiError(400, "预约时间须为未来的 UTC ISO 时间");
                }
            long evidence = Support.integer(body, "evidence_seq", -1);
            if (l.events.stream()
                    .noneMatch(
                            e ->
                                    "user_text".equals(e.get("type"))
                                            && Objects.equals(e.get("seq"), evidence)))
                throw new ApiError(400, "需要本次试聊的用户转写证据序号；请调用 business_context 获取");
            var result =
                    Map.<String, Object>of(
                            "event_id",
                            key,
                            "intent",
                            intent,
                            "note",
                            note,
                            "next_at",
                            next,
                            "evidence_seq",
                            evidence,
                            "status",
                            "pending_confirmation");
            var previous = l.tools.get(key);
            if (previous != null) {
                if (!previous.equals(result)) throw new ApiError(409, "事件标识内容冲突");
                return Map.of("saved", true, "replayed", true, "status", "pending_confirmation");
            }
            if (l.tools.size() >= 30) throw new ApiError(429, "试聊结果修改次数已达上限");
            l.result = result;
            l.record("intent_saved", Map.of("result", result));
            l.tools.put(key, result);
            return Map.of(
                    "saved",
                    true,
                    "status",
                    "pending_confirmation",
                    "message",
                    "试聊草稿已保存，等待平台管理员确认，未完成真实预约");
        }
    }

    public static String text(Map<String, Object> body, String key, int max) {
        Object raw = body.get(key);
        if (!(raw instanceof String s) || s.isBlank() || s.length() > max)
            throw new ApiError(400, "字段无效：" + key);
        return s.strip();
    }

    static String json(Object value) {
        return new String(Support.json(value), StandardCharsets.UTF_8);
    }

    static boolean equal(String a, String b) {
        return a != null
                && b != null
                && !a.isBlank()
                && MessageDigest.isEqual(
                        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static String token() {
        return UUID.randomUUID().toString() + UUID.randomUUID();
    }

    public final class Live {
        final String id, script, toolToken = token();
        final long tenant, user, began = System.nanoTime();
        final int tokenVersion;
        final com.duozhi.outbound.integration.config.Settings runtime;
        final Map<String, Object> guard;
        String ticket = token(), status = "created";
        boolean attached, closed;
        long epoch, seq, lastHeartbeat = System.nanoTime(), inputBegan;
        int bytes;
        final List<Map<String, Object>> events = new ArrayList<>();
        final Map<String, Map<String, Object>> tools = new HashMap<>();
        Map<String, Object> result = Map.of();
        Consumer<Map<String, Object>> sink = e -> {};
        Runnable transportClose = () -> {};
        BailianDialog dialog;
        ResponseScope response;
        SentenceSafetyGate gate;
        ByteArrayOutputStream input = new ByteArrayOutputStream(),
                approved = new ByteArrayOutputStream();
        boolean responseDone, playStarted, discardOutput;
        String state = "Starting";
        long inputEnd, firstAudio;

        Live(
                String id,
                Identity actor,
                com.duozhi.outbound.integration.config.Settings runtime,
                String script,
                Map<String, Object> guard) {
            this.id = id;
            tenant = actor.tenant();
            user = Long.parseLong(actor.userId().toString());
            this.runtime = runtime;
            this.script = script;
            this.guard = Map.copyOf(guard);
            tokenVersion = identities.findByIdForJwt(user).getTokenVersion();
        }

        void authorize() {
            var u = identities.findByIdForJwt(user);
            var t = identities.findTenantById(tenant);
            if (t == null
                    || !Boolean.TRUE.equals(t.getEnabled())
                    || u == null
                    || !Boolean.TRUE.equals(u.getEnabled())
                    || !"admin".equals(u.getRole())
                    || u.getTenantId() != tenant
                    || u.getTokenVersion() != tokenVersion
                    || System.nanoTime() - began > TimeUnit.MINUTES.toNanos(5))
                throw new ApiError(401, "试聊已过期或账号授权已失效");
        }

        public void start() {
            try {
                synchronized (this) {
                    authorize();
                    if (closed) throw new IOException();
                    status = "active";
                    record("state", Map.of("state", "Connecting"));
                }
                var d =
                        new BailianDialog(
                                runtime,
                                "trial-" + id,
                                script,
                                Map.of("trial_id", id, "trial_token", toolToken),
                                new BailianDialog.Listener() {
                                    public void event(String name, Map<String, Object> output)
                                            throws Exception {
                                        provider(name, output);
                                    }

                                    public void audio(byte[] pcm) throws Exception {
                                        providerAudio(pcm);
                                    }
                                });
                synchronized (this) {
                    if (closed) {
                        d.close();
                        return;
                    }
                    dialog = d;
                    record("provider_connected", Map.of("task_id", d.taskId()));
                }
            } catch (Exception e) {
                fail("百炼连接失败，请检查应用权限、网络和配置");
            }
        }

        public void command(Map<String, Object> command) throws Exception {
            authorize();
            String type = text(command, "type", 40);
            if (type.equals("close")) {
                end("completed");
                return;
            }
            synchronized (this) {
                if (closed || dialog == null) throw new IOException("trial not connected");
                switch (type) {
                    case "begin" -> {
                        if (gate != null) throw new IOException("playout pending");
                        input.reset();
                        inputBegan = System.nanoTime();
                        dialog.beginSpeech();
                        record("input_started", Map.of());
                    }
                    case "end" -> {
                        dialog.endSpeech();
                        inputEnd = System.nanoTime();
                        archive("input", 16000, input.toByteArray(), "in-" + seq);
                        input.reset();
                        record("input_ended", Map.of());
                    }
                    case "prompt" -> {
                        if (gate != null) throw new IOException("playout pending");
                        String value = text(command, "text", 2000);
                        inputEnd = System.nanoTime();
                        record("user_text", Map.of("text", value, "source", "typed"));
                        dialog.prompt(value);
                    }
                    case "interrupt" -> {
                        discardOutput = true;
                        cancelResponse();
                        archiveInput();
                        if (!dialog.interrupt()) {
                            discardOutput = false;
                            record("state", Map.of("state", "Listening"));
                        }
                        record("interrupted", Map.of());
                    }
                    case "played_start" -> {
                        if (Support.integer(command, "epoch", -1) != epoch
                                || gate == null
                                || playStarted) return;
                        playStarted = true;
                        dialog.command("LocalRespondingStarted", Map.of());
                        record(
                                "playback_started",
                                Map.of("evidence", "browser_reported", "epoch", epoch));
                    }
                    case "played_end" -> {
                        if (Support.integer(command, "epoch", -1) != epoch
                                || gate == null
                                || !responseDone
                                || !playStarted) return;
                        dialog.command("LocalRespondingEnded", Map.of());
                        record(
                                "playback_ended",
                                Map.of("evidence", "browser_reported", "epoch", epoch));
                        gate.close();
                        gate = null;
                        response.close();
                        response = null;
                    }
                    default -> throw new IOException("unsupported trial command");
                }
            }
        }

        public synchronized void audio(byte[] pcm) throws Exception {
            authorize();
            if (closed
                    || dialog == null
                    || input.size() + pcm.length > 960000
                    || System.nanoTime() - inputBegan > TimeUnit.SECONDS.toNanos(30))
                throw new IOException("trial input limit");
            dialog.audio(pcm);
            input.writeBytes(pcm);
        }

        void provider(String name, Map<String, Object> out) throws Exception {
            if (name.equals("RespondingEnded")) {
                SentenceSafetyGate current;
                long owner;
                synchronized (this) {
                    current = gate;
                    owner = epoch;
                }
                if (current == null) return;
                String reviewed;
                try {
                    reviewed = current.finish();
                } catch (Exception e) {
                    synchronized (this) {
                        if (closed || epoch != owner) return;
                    }
                    throw e;
                }
                synchronized (this) {
                    if (closed || owner != epoch) return;
                    responseDone = true;
                    archiveOutput(owner);
                    record(
                            "assistant_text",
                            Map.of("text", reviewed, "epoch", owner, "reviewed", true));
                    record("response_done", Map.of("epoch", owner));
                }
                return;
            }
            synchronized (this) {
                if (closed) return;
                switch (name) {
                    case "DialogStateChanged" -> {
                        state = Support.str(out, "state", "");
                        if (state.equals("Listening")) discardOutput = false;
                        record("state", Map.of("state", state));
                    }
                    case "SpeechContent" -> {
                        String value = Support.str(out, "text", "");
                        if (value.length() > 4000) throw new IOException("transcript limit");
                        if (Boolean.TRUE.equals(out.get("finished")) && !value.isBlank())
                            record("user_text", Map.of("text", value, "source", "asr"));
                        else emit(Map.of("type", "partial", "text", value));
                    }
                    case "SpeechEnded" -> {
                        inputEnd = System.nanoTime();
                        record("provider_input_ended", Map.of());
                    }
                    case "RespondingStarted" -> {
                        if (discardOutput) return;
                        if (gate != null) throw new IOException("overlapping response");
                        epoch++;
                        responseDone = false;
                        playStarted = false;
                        firstAudio = 0;
                        approved.reset();
                        response = new ResponseScope();
                        long owner = epoch;
                        var backend = new VoiceAiBackends(runtime);
                        var speech = new VoiceAiProfiles(runtime).snapshot("aliyun-cascade");
                        var policy = ContentSafety.policy(runtime);
                        var safety = new ContentSafety(runtime);
                        Set<Object> played = new HashSet<>();
                        for (var event : events)
                            if ("playback_ended".equals(event.get("type")))
                                played.add(event.get("epoch"));
                        List<String> history = new ArrayList<>();
                        for (var event : events) {
                            String type = event.get("type").toString();
                            if (type.equals("user_text")
                                    || type.equals("assistant_text")
                                            && played.contains(event.get("epoch")))
                                history.add(type + ": " + event.get("text"));
                        }
                        gate =
                                new SentenceSafetyGate(
                                        response,
                                        policy,
                                        guard,
                                        (listener, scope) ->
                                                backend.outputRecognizer(speech, listener, scope),
                                        (cumulative, scope) ->
                                                safety.review(
                                                        policy,
                                                        ContentSafety.withContext(
                                                                history, cumulative),
                                                        scope),
                                        (pcm, rate) -> {
                                            synchronized (Live.this) {
                                                if (closed || epoch != owner)
                                                    throw new IOException("stale audio");
                                                if (approved.size() + pcm.length > 1440000)
                                                    throw new IOException("audio limit");
                                                approved.writeBytes(pcm);
                                                emit(
                                                        Map.of(
                                                                "type",
                                                                "audio",
                                                                "epoch",
                                                                owner,
                                                                "rate",
                                                                rate,
                                                                "pcm",
                                                                Base64.getEncoder()
                                                                        .encodeToString(pcm)));
                                            }
                                        },
                                        e -> {
                                            synchronized (Live.this) {
                                                if (!closed && epoch == owner) record("safety", e);
                                            }
                                        },
                                        () -> {});
                        record("response_started", Map.of("epoch", owner));
                    }
                    case "RespondingContent" -> {
                        if (out.containsKey("llm_request_id"))
                            record(
                                    "provider_request",
                                    Map.of(
                                            "request_id",
                                            Support.str(out, "llm_request_id", ""),
                                            "round_id",
                                            Support.str(out, "round_id", "")));
                    }
                    default -> {}
                }
            }
        }

        void providerAudio(byte[] pcm) throws Exception {
            SentenceSafetyGate current;
            synchronized (this) {
                if (closed || discardOutput) return;
                current = gate;
                if (current == null) throw new IOException("audio without response");
                if (firstAudio == 0) {
                    firstAudio = System.nanoTime();
                    record(
                            "first_provider_audio",
                            Map.of(
                                    "since_input_end_ms",
                                    inputEnd == 0 ? -1 : (firstAudio - inputEnd) / 1000000));
                }
            }
            current.accept(pcm, 24000);
        }

        void emit(Map<String, Object> e) {
            sink.accept(e);
        }

        synchronized void record(String type, Map<String, Object> data) {
            if (events.size() >= 1200) throw new ApiError(429, "试聊事件数量达到上限");
            var e = new LinkedHashMap<String, Object>(data);
            e.put("type", type);
            e.put("seq", ++seq);
            e.put("elapsed_ms", (System.nanoTime() - began) / 1000000);
            events.add(e);
            db.save(tenant, id, status, json(events), json(result));
            emit(e);
        }

        void archive(String direction, int rate, byte[] pcm, String audio) {
            if (pcm.length == 0) return;
            if (bytes + pcm.length > 12000000) throw new ApiError(429, "试聊录音达到上限");
            db.audio(
                    Map.of(
                            "id",
                            id,
                            "tenant",
                            tenant,
                            "audio",
                            audio,
                            "direction",
                            direction,
                            "rate",
                            rate,
                            "pcm",
                            pcm));
            bytes += pcm.length;
        }

        void archiveInput() {
            archive("input", 16000, input.toByteArray(), "in-" + seq);
            input.reset();
        }

        void archiveOutput(long owner) {
            archive("output", 24000, approved.toByteArray(), "out-" + owner);
            approved.reset();
        }

        synchronized void cancelResponse() {
            long owner = epoch++;

            if (response != null) response.close();
            response = null;
            gate = null;
            responseDone = false;
            archiveOutput(owner);
            emit(Map.of("type", "clear_audio", "epoch", epoch));
        }

        void tick() throws Exception {
            authorize();
            synchronized (this) {
                if (closed) return;
                if (!attached && System.nanoTime() - began > TimeUnit.SECONDS.toNanos(30))
                    throw new IOException();
                if (dialog != null) {
                    dialog.check();
                    if (gate != null && !responseDone) gate.check();
                    if (System.nanoTime() - lastHeartbeat > TimeUnit.SECONDS.toNanos(15)) {
                        dialog.command("HeartBeat", Map.of());
                        lastHeartbeat = System.nanoTime();
                    }
                }
            }
        }

        public synchronized void fail(String message) {
            if (closed) return;
            try {
                record("error", Map.of("message", message));
            } catch (Exception browserOrDatabaseUnavailable) {
                // Resource cleanup cannot depend on the browser or database remaining available.
            } finally {
                end("failed");
            }
        }

        public synchronized void end(String finalStatus) {
            if (closed) return;
            closed = true;
            status = finalStatus;
            try {
                long owner = epoch++;
                if (response != null) response.close();
                response = null;
                gate = null;
                if (dialog != null) dialog.close();
                try {
                    archiveInput();
                    archiveOutput(owner);
                } finally {
                    db.save(tenant, id, status, json(events), json(result));
                }
                try {
                    emit(Map.of("type", "clear_audio", "epoch", epoch));
                    emit(Map.of("type", "closed", "status", status));
                } catch (Exception browserUnavailable) {
                    // Provider is already closed; a disconnected client cannot keep it billed.
                }
            } finally {
                live.remove(id, this);
                transportClose.run();
            }
        }
    }
}
