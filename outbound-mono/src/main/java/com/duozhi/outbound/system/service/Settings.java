package com.duozhi.outbound.system.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.Ascii;
import com.duozhi.outbound.common.support.JsonSupport;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.customer.controller.Contacts;
import com.duozhi.outbound.dial.domain.DialBatchScope;
import com.duozhi.outbound.integration.config.UnifiedServiceSettings;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.voiceai.service.RecognitionOptions;
import com.duozhi.outbound.voiceai.service.VoiceAiCredentials;
import com.duozhi.outbound.voiceai.service.VoiceAiProfiles;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class Settings {
    private final VoiceAiCredentials credentials;
    private final Store store;
    private final Config config;
    private final ObjectMapper mapper;
    private final Map<String, Map<String, Object>> defaults;

    public Settings(Store store, Config config, ObjectMapper mapper, VoiceAiCredentials credentials)
            throws IOException {
        this.credentials = credentials;
        this.store = store;
        this.config = config;
        this.mapper = mapper;
        try (InputStream in = new ClassPathResource("setting-defaults.yaml").getInputStream()) {
            defaults =
                    new ObjectMapper(new YAMLFactory())
                            .readValue(
                                    in, new TypeReference<Map<String, Map<String, Object>>>() {});
        }
        override("capacity", "max_concurrent_calls", "MAX_CONCURRENT_CALLS");
        for (Map.Entry<String, String> entry :
                Values.<String, String>map(
                                "agent_url",
                                "AI_AGENT_URL",
                                "llm_provider",
                                "LLM_PROVIDER",
                                "llm_model",
                                "OPENAI_MODEL")
                        .entrySet()) override("ai", entry.getKey(), entry.getValue());
        for (Map.Entry<String, String> entry :
                Values.<String, String>map(
                                "provider",
                                "SMS_PROVIDER",
                                "endpoint",
                                "SMS_PROVIDER_ENDPOINT",
                                "sender_id",
                                "SMS_SENDER_ID")
                        .entrySet()) override("sms", entry.getKey(), entry.getValue());
        for (String key :
                Values.list(
                        "recording_retention_days",
                        "partial_transcript_retention_hours",
                        "final_transcript_retention_days",
                        "call_sensitive_data_retention_days"))
            override("compliance", key, key.transform(Ascii::upper));
        override("compliance", "allowed_phone_prefixes", "OUTBOUND_ALLOWED_PHONE_PREFIXES");
        override("compliance", "max_calls_per_day", "OUTBOUND_DAILY_CALL_LIMIT");
    }

    private void override(String section, String key, String env) {
        Object old = defaults.get(section).get(key);
        defaults.get(section)
                .put(
                        key,
                        old instanceof Number
                                ? config.number(env, ((Number) old).intValue())
                                : config.get(env, old.toString()));
    }

    public Map<String, Object> defaults(String section) {
        if (!defaults.containsKey(section)) throw new ApiError(404, "setting section not found");
        return new LinkedHashMap<>(defaults.get(section));
    }

    public Map<String, Object> get(long tenant, String section) {
        var batch = DialBatchScope.current();
        String cacheKey = tenant + ":" + section;
        if (batch != null && batch.settings.containsKey(cacheKey))
            return new LinkedHashMap<>(batch.settings.get(cacheKey));
        Map<String, Object> result = defaults(section);
        Map<String, Object> record =
                store.one(
                        "adminsetting",
                        "findBySection",
                        Values.map("tenantId", tenant, "section", section));
        if (record != null)
            try {
                Map<String, Object> saved =
                        mapper.readValue(
                                record.get("data_json").toString(),
                                new TypeReference<Map<String, Object>>() {});
                saved.forEach(
                        (key, value) -> {
                            if (result.containsKey(key)) result.put(key, value);
                        });
            } catch (Exception ignored) {
            }
        if (section.equals("ai")) result.put("agent_url", defaults.get(section).get("agent_url"));
        if (section.equals("sms")) result.put("endpoint", defaults.get(section).get("endpoint"));
        if (batch != null) batch.settings.put(cacheKey, new LinkedHashMap<>(result));
        return result;
    }

    public Map<String, Object> freezeAi(long tenant) {
        return freezeAi(tenant, null);
    }

    public Map<String, Object> freezeAi(long tenant, String selectedBackend) {
        var ai = get(tenant, "ai");
        if (selectedBackend != null) ai.put("voice_backend", selectedBackend);
        if (VoiceAiProfiles.selected(ai)) {
            var profiles =
                    new VoiceAiProfiles(
                            credentials.runtime(
                                    new UnifiedServiceSettings(config, "voice"), tenant, -1));
            var profile =
                    RecognitionOptions.attach(
                            profiles.snapshot(ai.get("voice_backend").toString()),
                            ai.get("customer_asr_options").toString(),
                            false);
            profile.put("config_schema_version", 3);
            profile.put("conversation_history_turns", ai.get("conversation_history_turns"));
            profile.put("conversation_history_chars", ai.get("conversation_history_chars"));
            var turn = new LinkedHashMap<String, Object>();
            for (String key :
                    List.of(
                            "vad_start_ms",
                            "vad_end_ms",
                            "vad_long_end_ms",
                            "vad_max_input_ms",
                            "vad_idle_clear_ms",
                            "vad_rms",
                            "vad_adaptive",
                            "input_turn_controller")) turn.put(key, ai.get(key));
            profile.put("turn_policy", Map.copyOf(turn));
            profile.put(
                    "content_safety",
                    com.duozhi.outbound.voiceai.service.ContentSafety.policy(
                            new UnifiedServiceSettings(config, "voice")));
            if ("realtime".equals(profile.get("route"))) {
                String speechProfile =
                        config.get("VOICE_AI_REALTIME_SPEECH_PROFILE", "aliyun-cascade");
                var speech =
                        RecognitionOptions.attach(
                                profiles.snapshot(speechProfile),
                                ai.get("customer_asr_options").toString(),
                                false);
                if (!"cascade".equals(speech.get("route")))
                    throw new ApiError(400, "确定性播报与分类需要三段式组合");
                speech.put("turn_policy", Map.copyOf(turn));
                profile.put("speech_profile", Map.copyOf(speech));
                // Independent audit options never inherit customer hotwords or filtering.
                var audit =
                        RecognitionOptions.attach(
                                profiles.snapshot(speechProfile),
                                ai.get("output_audit_asr_options").toString(),
                                true);
                profile.put("output_audit_profile", Map.copyOf(audit));
            }
            profile.put("revision", Support.hex(Support.sha(Support.canonical(profile))));
            ai.put("_voice_profile", Collections.unmodifiableMap(profile));
        }
        return ai;
    }

    private void range(Map<String, Object> data, String key, int min, int max) {
        Object value = data.get(key);
        if (!(value instanceof Number)
                || ((Number) value).longValue() < min
                || ((Number) value).longValue() > max) throw new ApiError(400, "invalid " + key);
    }

    public static String endpoint(String value, boolean origin) {
        try {
            if (value.chars().anyMatch(c -> c < 33 || c == 127))
                throw new IllegalArgumentException();
            URI uri = URI.create(value);
            if (!Values.set("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || (!origin && uri.getQuery() != null)) throw new IllegalArgumentException();
            int port =
                    uri.getPort() < 0
                            ? (uri.getScheme().equals("https") ? 443 : 80)
                            : uri.getPort();
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            String host = uri.getHost().transform(Ascii::lower).replaceAll("\\.$", "");
            return uri.getScheme()
                    + "://"
                    + host
                    + ":"
                    + port
                    + (origin ? "" : Objects.toString(uri.getPath(), "").replaceAll("/+$", ""));
        } catch (IllegalArgumentException e) {
            throw new ApiError(422, "invalid service endpoint");
        }
    }

    public Map<String, Object> validate(String section, Map<String, Object> input) {
        Map<String, Object> data = defaults(section);
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (!data.containsKey(entry.getKey()))
                throw new ApiError(400, "unsupported setting fields: " + entry.getKey());
            Object expected = data.get(entry.getKey()), value = entry.getValue();
            boolean same =
                    value != null
                            && (expected instanceof Integer
                                    ? (value instanceof Integer || value instanceof Long)
                                    : expected.getClass().isInstance(value));
            if (!same) throw new ApiError(400, "invalid setting type: " + entry.getKey());
            if (value instanceof String && ((String) value).length() > 4000)
                throw new ApiError(400, "setting value too large: " + entry.getKey());
            data.put(entry.getKey(), value);
        }
        try {
            if (mapper.writeValueAsString(data).length() > 20000)
                throw new ApiError(400, "setting payload too large");
        } catch (JsonProcessingException e) {
            throw new ApiError(400, "invalid settings");
        }
        if (section.equals("capacity")) range(data, "max_concurrent_calls", 1, 100000);
        if (section.equals("compliance")) {
            range(data, "allowed_start_hour", 0, 23);
            range(data, "allowed_end_hour", 0, 23);
            range(data, "max_attempts_per_day", 1, 20);
            range(data, "min_attempt_interval_sec", 0, 604800);
            range(data, "max_calls_per_day", 1, 10000000);
            range(data, "recording_retention_days", 1, 3650);
            range(data, "partial_transcript_retention_hours", 1, 720);
            range(data, "final_transcript_retention_days", 1, 3650);
            range(data, "call_sensitive_data_retention_days", 1, 3650);
            Contacts.timezone(data.get("timezone"));
            for (String prefix : data.get("allowed_phone_prefixes").toString().split(","))
                if (!prefix.trim().isEmpty() && !prefix.trim().matches("[0-9]{1,15}"))
                    throw new ApiError(400, "invalid allowed phone prefixes");
            if (config.production()
                    && (!Boolean.TRUE.equals(data.get("dnc_enforced"))
                            || !Boolean.TRUE.equals(data.get("require_explicit_consent"))
                            || !Boolean.TRUE.equals(
                                    data.get("require_explicit_consent_for_direct_calls"))
                            || data.get("allowed_phone_prefixes").toString().trim().isEmpty()))
                throw new ApiError(
                        400, "production requires consent, DNC and allowed phone prefixes");
        }
        if (section.equals("ai")) {
            if (!Set.of("approved_audio_then_end", "end").contains(data.get("safety_fallback")))
                throw new ApiError(400, "未知审核失败处理方式");
            range(data, "conversation_history_chars", 500, 20000);
            if (!Set.of("observe", "enforce").contains(data.get("input_turn_controller")))
                throw new ApiError(400, "未知话轮控制模式");
            try {
                RecognitionOptions.parse(data.get("customer_asr_options").toString(), false);
                RecognitionOptions.parse(data.get("output_audit_asr_options").toString(), true);
            } catch (RuntimeException invalid) {
                throw new ApiError(400, "识别参数不合法或不支持：" + invalid.getMessage());
            }
            range(data, "vad_start_ms", 20, 1000);
            range(data, "vad_end_ms", 200, 2000);
            range(data, "vad_long_end_ms", JsonSupport.integer(data.get("vad_end_ms"), 400), 3000);
            range(data, "vad_max_input_ms", 1000, 60000);
            range(data, "vad_idle_clear_ms", 500, 10000);
            range(data, "vad_rms", 1, 32767);
            range(data, "answer_classify_timeout_ms", 1500, 3000);
            if (data.get("ai_disclosure_text").toString().isBlank()
                    || data.get("ai_disclosure_text").toString().length() > 500)
                throw new ApiError(400, "AI身份告知不能为空且不超过500字");
            boolean realtimeProfile = false;
            if (VoiceAiProfiles.selected(data)) {
                try {
                    realtimeProfile =
                            "realtime"
                                    .equals(
                                            VoiceAiProfiles.profile(
                                                            data.get("voice_backend").toString())
                                                    .route());
                } catch (Support.Failure invalid) {
                    throw new ApiError(400, "未知语音供应商组合");
                }
            }
            if (Boolean.TRUE.equals(data.get("direct_s2s_enabled"))
                    && (!Set.of("streaming", "sentence_review")
                                    .contains(data.get("realtime_playback_mode"))
                            || !VoiceAiProfiles.selected(data)
                            || !realtimeProfile)) throw new ApiError(400, "按语音端点直接回复只适用于端到端逐句审核播放");
            if (realtimeProfile && "enforce".equals(data.get("input_turn_controller")))
                throw new ApiError(400, "端到端方案暂保留供应商话轮控制，仅支持观察模式");
            if (!Values.set("reviewed", "streaming", "sentence_review")
                    .contains(data.get("realtime_playback_mode")))
                throw new ApiError(400, "未知端到端播放模式");
            // Legacy mode values remain readable, but all S2S output uses the mandatory audio gate.
            if (realtimeProfile) data.put("realtime_playback_mode", "sentence_review");
            if (VoiceAiProfiles.selected(data)) {
                if (!Boolean.TRUE.equals(data.get("external_llm_enabled")))
                    throw new ApiError(400, "启用供应商方案需要允许外部模型调用");
            }

            if (!Values.set("rule", "openai-compatible").contains(data.get("llm_provider")))
                throw new ApiError(400, "unsupported llm provider");
            if (data.get("llm_provider").equals("openai-compatible")
                    && !Boolean.TRUE.equals(data.get("external_llm_enabled")))
                throw new ApiError(400, "external LLM requires explicit tenant approval");
            if (Boolean.TRUE.equals(data.get("enabled"))
                    && data.get("agent_url").toString().trim().isEmpty())
                throw new ApiError(400, "enabled AI requires agent_url");
            range(data, "conversation_history_turns", 1, 50);
            range(data, "max_reply_chars", 20, 2000);
            range(data, "pipecat_canary_percent", 0, 100);
            if (data.get("fallback_reply").toString().trim().isEmpty()
                    || !Values.set("legacy", "pipecat").contains(data.get("voice_ai_pipeline")))
                throw new ApiError(400, "invalid AI policy");
            if (!data.get("agent_url").toString().trim().isEmpty()
                    && !endpoint(data.get("agent_url").toString(), false)
                            .equals(
                                    endpoint(
                                            defaults.get("ai").get("agent_url").toString(), false)))
                throw new ApiError(422, "AI endpoint is managed by the platform");
        }
        if (section.equals("sms")) {
            if (!Values.set("mock", "http").contains(data.get("provider")))
                throw new ApiError(400, "unsupported SMS provider");
            if (Boolean.TRUE.equals(data.get("enabled"))
                    && data.get("provider").equals("http")
                    && data.get("endpoint").toString().trim().isEmpty())
                throw new ApiError(400, "HTTP SMS provider requires endpoint");
            if (!data.get("endpoint").toString().trim().isEmpty()
                    && !endpoint(data.get("endpoint").toString(), false)
                            .equals(
                                    endpoint(
                                            defaults.get("sms").get("endpoint").toString(), false)))
                throw new ApiError(422, "SMS endpoint is managed by the platform");
        }
        if (section.equals("integration")) {
            range(data, "webhook_timeout_sec", 1, 120);
            range(data, "webhook_retry_times", 0, 10);
            range(data, "webhook_retry_backoff_sec", 1, 60);
            String secret = data.get("webhook_secret_ref").toString(),
                    url = data.get("webhook_base_url").toString();
            if (!secret.trim().isEmpty() && !secret.matches("[A-Z0-9_]+"))
                throw new ApiError(400, "invalid webhook secret reference");
            if (Boolean.TRUE.equals(data.get("callback_enabled"))
                    && (url.trim().isEmpty() || (config.production() && secret.trim().isEmpty())))
                throw new ApiError(400, "callback destination and signing reference required");
            if (!url.trim().isEmpty()) {
                String origin = endpoint(url, true);
                boolean allowed =
                        Arrays.stream(
                                        config.get("BUSINESS_CALLBACK_ALLOWED_ORIGINS", "")
                                                .split(","))
                                .filter(s -> !s.trim().isEmpty())
                                .map(s -> endpoint(s.trim(), false))
                                .anyMatch(origin::equals);
                if (!allowed)
                    throw new ApiError(422, "callback origin is not registered by the platform");
                if (config.production() && !url.startsWith("https://"))
                    throw new ApiError(422, "production callbacks require HTTPS");
            }
        }
        return data;
    }
}
