package com.duozhi.outbound.voiceai.controller;

import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.integration.config.UnifiedServiceSettings;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.media.service.NativeMedia.PcmResampler;
import com.duozhi.outbound.system.service.Audit;
import com.duozhi.outbound.system.service.Settings;
import com.duozhi.outbound.voiceai.dao.VoiceAiBenchmarkMapper;
import com.duozhi.outbound.voiceai.service.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/voice-ai")
@AdminPermission("settings:write")
public class VoiceAiController {
    private final com.duozhi.outbound.integration.config.Settings runtime;
    private final VoiceAiCredentials credentials;
    private final VoiceAiBackends backends;
    private final VoiceAiBenchmarkMapper db;
    private final Settings settings;
    private final Store store;
    private final Audit audit;
    private final ObjectMapper json;
    private final Semaphore capacity = new Semaphore(2);

    public VoiceAiController(
            Config config,
            VoiceAiBenchmarkMapper db,
            Settings settings,
            Store store,
            Audit audit,
            ObjectMapper json,
            VoiceAiCredentials credentials) {
        this.credentials = credentials;
        this.runtime = credentials.root(new UnifiedServiceSettings(config, "voice"));
        this.backends = new VoiceAiBackends(runtime);
        this.db = db;
        this.settings = settings;
        this.store = store;
        this.audit = audit;
        this.json = json;
    }

    @GetMapping("/profiles")
    public Object catalog(HttpServletRequest request) {
        try {
            return new VoiceAiProfiles(runtime.voiceAi(Identity.from(request).tenant(), -1))
                    .catalog();
        } catch (Support.Failure error) {
            throw new ApiError(error.status, error.getMessage());
        }
    }

    @GetMapping("/configuration")
    public Object configuration(HttpServletRequest request) {
        return credentials.describe(Identity.from(request).tenant());
    }

    @PutMapping("/configuration")
    public Object configure(@RequestBody
    Map<String, Object> input, HttpServletRequest request) {
        var actor = Identity.from(request);
        if (!(input.get("changes") instanceof Map<?, ?>)) throw new ApiError(400, "缺少配置修改项");
        var changes = Support.map(input.get("changes"));
        Object result = credentials.save(actor.tenant(), changes);
        audit.record(
                actor,
                "update",
                "voice_ai_configuration",
                "tenant-" + actor.tenant(),
                "fields=" + String.join(",", changes.keySet()));
        return result;
    }

    @PostMapping("/openings/prepare")
    public Object prepareOpening(HttpServletRequest request) throws Exception {
        var actor = Identity.from(request);
        var ai = settings.freezeAi(actor.tenant());
        var profile = Support.map(ai.get("_voice_profile"));
        if (profile.isEmpty()) throw new ApiError(400, "请先选择供应商方案并保存");
        if (!capacity.tryAcquire()) throw new ApiError(429, "已有语音测试或准备任务运行");
        try {
            var result =
                    new OpeningAudioCache(runtime)
                            .prepare(
                                    actor.tenant(),
                                    profile,
                                    Support.str(ai, "ai_disclosure_text", ""),
                                    backends);
            audit.record(
                    actor,
                    "create",
                    "voice_opening",
                    result.get("key").toString(),
                    "prepared deterministic AI disclosure");
            return result;
        } catch (Support.Failure invalid) {
            throw new ApiError(invalid.status, invalid.getMessage());
        } catch (Exception failed) {
            throw new ApiError(502, "身份告知音频准备失败，请检查供应商连接与权限");
        } finally {
            capacity.release();
        }
    }

    @GetMapping("/test-sets")
    public Object sets(HttpServletRequest request) {
        return db.sets(Identity.from(request).tenant());
    }

    @PostMapping("/test-sets")
    @Transactional
    public Object create(@RequestBody
    Map<String, Object> input, HttpServletRequest request)
            throws Exception {
        Identity actor = Identity.from(request);
        String name = required(input, "name", 120), script;
        if (input.get("script_template_id") != null) {
            var template =
                    store.require(
                            "scripttemplate", actor.tenant(), input.get("script_template_id"));
            if (!Boolean.TRUE.equals(template.get("is_active"))) throw new ApiError(409, "话术已停用");
            script = Objects.toString(template.get("content"), "");
        } else script = required(input, "script", 12000);
        if (script.isBlank() || script.length() > 12000)
            throw new ApiError(400, "话术不能为空且不得超过 12000 字符");
        if (!(input.get("samples") instanceof List<?> samples)
                || samples.isEmpty()
                || samples.size() > 10) throw new ApiError(400, "需要 1—10 条对比样本");
        var normalized = new ArrayList<Map<String, Object>>();
        int bytes = 0;
        for (Object item : samples) {
            var sample = Support.map(item);
            String text = Support.str(sample, "text", ""),
                    audio = Support.str(sample, "audio_wav_base64", "");
            if ((text.isBlank() == audio.isBlank()) || text.length() > 2000)
                throw new ApiError(400, "每条样本须提供文字或 WAV 音频其中一种");
            if (!audio.isBlank()) VoiceAiAudio.wav(audio);
            bytes += audio.length() + text.length();
            if (bytes > 3000000) throw new ApiError(400, "样本总大小超过 3MB");
            normalized.add(
                    Map.of(
                            "id",
                            UUID.randomUUID().toString(),
                            "text",
                            text,
                            "audio_wav_base64",
                            audio));
        }
        String id = UUID.randomUUID().toString(),
                sha = Support.hex(Support.sha(script.getBytes(StandardCharsets.UTF_8)));
        db.createSet(
                Map.of(
                        "id",
                        id,
                        "tenant",
                        actor.tenant(),
                        "name",
                        name,
                        "script",
                        script,
                        "sha",
                        sha,
                        "samples",
                        json.writeValueAsString(normalized)));
        audit.record(actor, "create", "voice_ai_test_set", id, "samples=" + normalized.size());
        return describe(db.set(actor.tenant(), id));
    }

    @GetMapping("/test-sets/{id}")
    public Object get(@PathVariable
    String id, HttpServletRequest request) throws Exception {
        var set = db.set(Identity.from(request).tenant(), id);
        if (set == null) throw new ApiError(404, "对比样本不存在");
        return describe(set);
    }

    private Map<String, Object> describe(Map<String, Object> set) throws Exception {
        var result = new LinkedHashMap<>(set);
        var samples =
                json.readValue(
                        result.remove("samples_json").toString(),
                        new TypeReference<List<Map<String, Object>>>() {});
        result.put(
                "samples",
                samples.stream()
                        .map(
                                s ->
                                        Map.of(
                                                "id",
                                                s.get("id"),
                                                "text",
                                                s.get("text"),
                                                "kind",
                                                Support.str(s, "audio_wav_base64", "").isBlank()
                                                        ? "text"
                                                        : "audio"))
                        .toList());
        return result;
    }

    @GetMapping("/test-sets/{id}/runs")
    public Object runs(@PathVariable
    String id, HttpServletRequest request) throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        for (var run : db.runs(Identity.from(request).tenant(), id)) result.add(describeRun(run));
        return result;
    }

    @GetMapping("/runs/{id}/audio/{sample}")
    public ResponseEntity<byte[]> audio(
            @PathVariable
            String id, @PathVariable
            String sample, HttpServletRequest request) {
        if (!id.matches("[a-fA-F0-9-]{36}") || !sample.matches("[a-fA-F0-9-]{36}"))
            throw new ApiError(400, "invalid audio reference");
        String encoded = db.audio(Identity.from(request).tenant(), id, sample);
        if (encoded == null || encoded.equals("null")) throw new ApiError(404, "试听音频不存在");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .header("Cache-Control", "private, no-store")
                .body(Base64.getDecoder().decode(encoded));
    }

    private Map<String, Object> describeRun(Map<String, Object> row) throws Exception {
        var result = new LinkedHashMap<>(row);
        result.put("profile", json.readValue(result.remove("profile_json").toString(), Map.class));
        result.put("results", json.readValue(result.remove("results_json").toString(), List.class));
        result.remove("client_key");
        result.remove("tenant_id");
        return result;
    }

    @PostMapping("/test-sets/{id}/runs")
    public Object run(
            @PathVariable
            String id,
            @RequestBody
            Map<String, Object> input,
            HttpServletRequest request)
            throws Exception {
        Identity actor = Identity.from(request);
        long tenant = actor.tenant();
        var profiles = new VoiceAiProfiles(runtime.voiceAi(tenant, -1));
        String profile = required(input, "profile_id", 40), key = required(input, "request_id", 80);
        if (!key.matches("[a-zA-Z0-9_-]{8,80}")) throw new ApiError(400, "invalid request id");
        try {
            VoiceAiProfiles.profile(profile);
        } catch (Support.Failure invalid) {
            throw new ApiError(422, invalid.getMessage());
        }
        var set = db.set(tenant, id);
        if (set == null) throw new ApiError(404, "对比样本不存在");
        var previous = db.request(tenant, key);
        if (previous != null) {
            if (!id.equals(previous.get("test_set_id"))
                    || !profile.equals(previous.get("profile_id")))
                throw new ApiError(409, "请求编号已用于其他测试");
            return describeRun(previous);
        }
        if (!Boolean.TRUE.equals(settings.get(tenant, "ai").get("external_llm_enabled")))
            throw new ApiError(409, "请先允许外部模型调用，再运行供应商对比");
        if (!capacity.tryAcquire()) throw new ApiError(429, "同时最多运行两组供应商测试，请稍后再试");
        try {
            String runId = UUID.randomUUID().toString();
            var snapshot = profiles.snapshot(profile);
            try {
                db.createRun(
                        Map.of(
                                "id",
                                runId,
                                "tenant",
                                tenant,
                                "set",
                                id,
                                "profile",
                                profile,
                                "key",
                                key,
                                "snapshot",
                                json.writeValueAsString(snapshot)));
            } catch (DuplicateKeyException duplicate) {
                return describeRun(db.request(tenant, key));
            }
            var results = new ArrayList<Map<String, Object>>();
            var audios = new LinkedHashMap<String, String>();
            var samples =
                    json.readValue(
                            set.get("samples_json").toString(),
                            new TypeReference<List<Map<String, Object>>>() {});
            for (var sample : samples) {
                var result = new LinkedHashMap<String, Object>();
                result.put("sample_id", sample.get("id"));
                result.put(
                        "input_kind",
                        Support.str(sample, "audio_wav_base64", "").isBlank() ? "text" : "audio");
                result.put("telephone_verified", false);
                if (!profiles.missing(profile).isEmpty()) {
                    result.put("status", "unconfigured");
                    result.put("error", "缺少配置：" + String.join(", ", profiles.missing(profile)));
                } else
                    try {
                        result.putAll(
                                evaluate(snapshot, set.get("script_snapshot").toString(), sample));
                    } catch (Exception failed) {
                        result.put("status", "failed");
                        result.put("error", "供应商调用未完成；请核对权限、余额、模型与接口配置。未自动重试。");
                    }
                Object audio = result.remove("audio_wav_base64");
                if (audio instanceof String encoded)
                    audios.put(sample.get("id").toString(), encoded);
                result.put("audio_available", audio != null);
                results.add(result);
            }
            String status =
                    results.stream().allMatch(r -> "succeeded".equals(r.get("status")))
                            ? "succeeded"
                            : results.stream().anyMatch(r -> "succeeded".equals(r.get("status")))
                                    ? "partial"
                                    : "failed";
            db.finish(
                    Map.of(
                            "tenant",
                            tenant,
                            "id",
                            runId,
                            "status",
                            status,
                            "results",
                            json.writeValueAsString(results),
                            "audios",
                            json.writeValueAsString(audios)));
            audit.record(
                    actor,
                    "run",
                    "voice_ai_comparison",
                    runId,
                    "profile=" + profile + ";samples=" + samples.size() + ";status=" + status);
            return describeRun(db.request(tenant, key));
        } finally {
            capacity.release();
        }
    }

    private Map<String, Object> evaluate(
            Map<String, Object> spec, String script, Map<String, Object> sample) throws Exception {
        long started = System.nanoTime();
        var transcript = new CompletableFuture<String>();
        boolean audio = !Support.str(sample, "audio_wav_base64", "").isBlank(),
                realtime = "realtime".equals(spec.get("route"));
        var pcm = new ByteArrayOutputStream();
        long[] first = {0}, inputEnd = {started};
        PcmResampler[] conversion = {null};
        VoiceAiBackends.Audio sink =
                (bytes, rate) -> {
                    if (first[0] == 0) first[0] = System.nanoTime();
                    if (conversion[0] == null) conversion[0] = new PcmResampler(rate, 24000);
                    byte[] converted = conversion[0].convert(bytes);
                    if (pcm.size() + converted.length > 1440000)
                        throw new IOException("output limit");
                    pcm.writeBytes(converted);
                };
        String text = Support.str(sample, "text", ""), reply;
        // Text-only samples intentionally omit ASR. Realtime providers receive text via their own
        // session.
        if (!audio && realtime) throw new ApiError(400, "端到端语音对比须使用 WAV 音频样本，不能用文字绕过语音理解");
        if (audio) {
            var input = VoiceAiAudio.wav(Support.str(sample, "audio_wav_base64", ""));
            int targetRate =
                    !realtime && Set.of("aliyun", "tencent").contains(spec.get("vendor"))
                            ? 8000
                            : 16000;
            byte[] bytes = VoiceAiAudio.resample(input.bytes(), input.rate(), targetRate);
            try (var connection =
                    backends.connect(
                            spec,
                            script,
                            new VoiceAiBackends.Listener() {
                                public void transcript(String value, String event) {
                                    transcript.complete(value);
                                }

                                public void speechStarted() {}
                            })) {
                int frame = targetRate / 50 * 2;
                long sending = System.nanoTime();
                for (int offset = 0; offset < bytes.length; offset += frame) {
                    connection.audio(
                            Arrays.copyOfRange(
                                    bytes, offset, Math.min(bytes.length, offset + frame)));
                    long wait =
                            sending
                                    + Math.min(bytes.length, offset + frame)
                                            * 1_000_000_000L
                                            / (targetRate * 2L)
                                    - System.nanoTime();
                    if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
                }
                inputEnd[0] = System.nanoTime();
                // Natural silence lets the same server VAD close the turn on all six backends.
                for (int i = 0; i < 60 && !transcript.isDone(); i++) {
                    connection.audio(new byte[frame]);
                    TimeUnit.MILLISECONDS.sleep(20);
                }
                if (!realtime) connection.finishInput();
                else if (!transcript.isDone()) connection.finishInput();
                text = transcript.get(10, TimeUnit.SECONDS);
                if (realtime) {
                    reply = connection.reply("", true, sink);
                    if (connection.firstAudioNanos() != 0) first[0] = connection.firstAudioNanos();
                } else {
                    reply = backends.generate(spec, script, text, List.of());
                    backends.synthesize(spec, reply, sink);
                }
            }
        } else {
            reply = backends.generate(spec, script, text, List.of());
            backends.synthesize(spec, reply, sink);
        }
        if (conversion[0] != null) pcm.writeBytes(conversion[0].finish());
        if (pcm.size() == 0 || reply.isBlank()) throw new IOException("empty vendor response");
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "succeeded");
        result.put("transcript", text);
        result.put("reply", reply);
        result.put("first_audio_ms", (first[0] - started) / 1_000_000);
        result.put("post_input_first_audio_ms", Math.max(0, (first[0] - inputEnd[0]) / 1_000_000));
        result.put("total_ms", (System.nanoTime() - started) / 1_000_000);
        result.put(
                "audio_wav_base64",
                Base64.getEncoder().encodeToString(VoiceAiAudio.wav(pcm.toByteArray(), 24000)));
        result.put("scope", audio ? "供应商API音频测试；不含电话线路" : "文本模型及合成测试；未测试识别");
        return result;
    }

    private static String required(Map<String, Object> input, String key, int max) {
        Object value = input.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > max)
            throw new ApiError(400, "invalid " + key);
        return text;
    }
}
