package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.support.Support;
import java.net.URI;
import java.util.*;

/** Six explicit, immutable provider combinations. Credentials never enter tenant policy JSON. */
public final class VoiceAiProfiles {
    public record Profile(String id, String name, String vendor, String route) {}

    public static final List<Profile> PROFILES =
            List.of(
                    new Profile("aliyun-cascade", "阿里云三段式", "aliyun", "cascade"),
                    new Profile("volcengine-cascade", "火山引擎三段式", "volcengine", "cascade"),
                    new Profile("tencent-cascade", "腾讯云三段式", "tencent", "cascade"),
                    new Profile("volcengine-realtime", "豆包端到端实时语音", "volcengine", "realtime"),
                    new Profile("aliyun-realtime", "千问端到端实时语音", "aliyun", "realtime"),
                    new Profile("baidu-realtime", "百度端到端实时语音", "baidu", "realtime"));
    public static final Map<String, String> DEFAULTS;

    static {
        var d = new LinkedHashMap<String, String>();
        d.put("VOICE_AI_BAILIAN_WORKSPACE_ID", "");
        d.put("VOICE_AI_BAILIAN_APP_ID", "");
        d.put("VOICE_AI_BAILIAN_APP_REVISION", "");
        d.put("VOICE_AI_BAILIAN_TOOL_TARGET", "");
        d.put("VOICE_AI_BAILIAN_VOICE", "");
        d.put("VOICE_AI_BAILIAN_TOOL_API_KEY", "");
        d.put("VOICE_AI_BAILIAN_TOOL_API_KEY_FILE", "");
        d.put("VOICE_AI_BAILIAN_URL", "wss://dashscope.aliyuncs.com/api-ws/v1/inference");
        d.put("VOICE_AI_ALI_ASR_URL", "wss://dashscope.aliyuncs.com/api-ws/v1/inference");
        d.put("VOICE_AI_ALI_TTS_URL", "wss://dashscope.aliyuncs.com/api-ws/v1/inference");
        d.put(
                "VOICE_AI_ALI_LLM_URL",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        d.put("VOICE_AI_ALI_REALTIME_URL", "wss://dashscope.aliyuncs.com/api-ws/v1/realtime");
        d.put("VOICE_AI_ALI_ASR_MODEL", "fun-asr-flash-8k-realtime");
        d.put("VOICE_AI_ALI_LLM_MODEL", "qwen-flash");
        d.put("VOICE_AI_ALI_TTS_MODEL", "cosyvoice-v3-flash");
        d.put("VOICE_AI_ALI_VOICE", "longxiaochun_v3");
        // New workspace-bound Omni models require an operator supplied workspace endpoint.
        d.put("VOICE_AI_ALI_REALTIME_MODEL", "qwen3.8-omni-flash-realtime");
        d.put("VOICE_AI_ALI_REALTIME_VOICE", "Tina");
        d.put("VOICE_AI_VOLC_ASR_URL", "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel");
        d.put("VOICE_AI_VOLC_TTS_URL", "wss://openspeech.bytedance.com/api/v3/tts/bidirection");
        d.put("VOICE_AI_VOLC_LLM_URL", "https://ark.cn-beijing.volces.com/api/v3/chat/completions");
        d.put(
                "VOICE_AI_VOLC_REALTIME_URL",
                "wss://openspeech.bytedance.com/api/v3/realtime/dialogue");
        d.put("VOICE_AI_VOLC_ASR_RESOURCE", "volc.seedasr.sauc.duration");
        d.put("VOICE_AI_VOLC_TTS_RESOURCE", "seed-tts-2.0");
        d.put("VOICE_AI_VOLC_LLM_MODEL", "");
        d.put("VOICE_AI_VOLC_VOICE", "zh_female_vv_uranus_bigtts");
        d.put("VOICE_AI_VOLC_REALTIME_MODEL", "1.2.1.1");
        d.put("VOICE_AI_VOLC_REALTIME_VOICE", "zh_female_vv_jupiter_bigtts");
        d.put("VOICE_AI_TENCENT_ASR_URL", "wss://asr.cloud.tencent.com/asr/v2");
        d.put("VOICE_AI_TENCENT_TTS_URL", "wss://tts.cloud.tencent.com/stream_wsv2");
        d.put("VOICE_AI_TENCENT_LLM_URL", "https://tokenhub.tencentmaas.com/v1/chat/completions");
        d.put("VOICE_AI_TENCENT_ASR_MODEL", "8k_zh");
        d.put("VOICE_AI_TENCENT_LLM_MODEL", "hy3");
        d.put("VOICE_AI_TENCENT_VOICE", "101001");
        d.put("VOICE_AI_BAIDU_REALTIME_URL", "wss://aip.baidubce.com/ws/2.0/speech/v1/realtime");
        d.put("VOICE_AI_BAIDU_REALTIME_MODEL", "qianfan-realtime-flex-v1");
        d.put("VOICE_AI_BAIDU_REALTIME_VOICE", "default");
        for (String key :
                List.of(
                        "DASHSCOPE_API_KEY",
                        "VOICE_AI_VOLC_API_KEY",
                        "VOICE_AI_VOLC_APP_ID",
                        "VOICE_AI_VOLC_ACCESS_KEY",
                        "VOICE_AI_VOLC_LLM_API_KEY",
                        "VOICE_AI_TENCENT_APP_ID",
                        "VOICE_AI_TENCENT_SECRET_ID",
                        "VOICE_AI_TENCENT_SECRET_KEY",
                        "VOICE_AI_TENCENT_LLM_API_KEY",
                        "VOICE_AI_BAIDU_API_KEY")) {
            d.put(key, "");
            d.put(key + "_FILE", "");
        }
        for (String service : List.of("ASR", "TTS", "REALTIME")) {
            for (String suffix : List.of("API_KEY", "APP_ID", "ACCESS_KEY")) {
                String key = "VOICE_AI_VOLC_" + service + "_" + suffix;
                d.put(key, "");
                d.put(key + "_FILE", "");
            }
        }
        DEFAULTS = Collections.unmodifiableMap(d);
    }

    private final Settings settings;

    public VoiceAiProfiles(Settings settings) {
        this.settings = settings;
    }

    public static Profile profile(String id) {
        return PROFILES.stream()
                .filter(p -> p.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new Support.Failure(422, "unknown voice AI profile"));
    }

    public static boolean selected(Map<String, Object> ai) {
        return !Support.str(ai, "voice_backend", "legacy").equals("legacy");
    }

    public static boolean realtime(Map<String, Object> ai) {
        return selected(ai)
                && profile(Support.str(ai, "voice_backend", "legacy")).route.equals("realtime");
    }

    /** References, never secret values. A session fixes both the scheme and the source. */
    public record VolcAuth(String mode, String apiKey, String appId, String accessKey) {
        public List<String> references() {
            return mode.equals("api_key") ? List.of(apiKey) : List.of(appId, accessKey);
        }

        public Map<String, String> snapshot() {
            return mode.equals("api_key")
                    ? Map.of("mode", mode, "api_key", apiKey)
                    : Map.of("mode", mode, "app_id", appId, "access_key", accessKey);
        }
    }

    private static List<String> services(Profile profile) {
        return profile.route.equals("realtime") ? List.of("realtime") : List.of("asr", "tts");
    }

    private boolean referenceConfigured(String key) {
        return !settings.get(key, "").isBlank() || !settings.get(key + "_FILE", "").isBlank();
    }

    private VolcAuth selectVolcAuth(String service) {
        String prefix = "VOICE_AI_VOLC_" + service.toUpperCase(Locale.ROOT) + "_";
        if (referenceConfigured(prefix + "API_KEY"))
            return new VolcAuth("api_key", prefix + "API_KEY", "", "");
        // A partial service-specific pair must fail as that pair, never borrow shared credentials.
        if (referenceConfigured(prefix + "APP_ID") || referenceConfigured(prefix + "ACCESS_KEY"))
            return new VolcAuth("legacy", "", prefix + "APP_ID", prefix + "ACCESS_KEY");
        if (referenceConfigured("VOICE_AI_VOLC_API_KEY"))
            return new VolcAuth("api_key", "VOICE_AI_VOLC_API_KEY", "", "");
        if (referenceConfigured("VOICE_AI_VOLC_APP_ID")
                || referenceConfigured("VOICE_AI_VOLC_ACCESS_KEY"))
            return new VolcAuth("legacy", "", "VOICE_AI_VOLC_APP_ID", "VOICE_AI_VOLC_ACCESS_KEY");
        return new VolcAuth("api_key", prefix + "API_KEY", "", "");
    }

    public VolcAuth volcAuth(Map<String, Object> spec, String service) {
        if (!Set.of("asr", "tts", "realtime").contains(service))
            throw new Support.Failure(503, "invalid voice AI credential service");
        // Snapshots made before this change used the original shared legacy pair.
        if (!spec.containsKey("volc_auth"))
            return new VolcAuth("legacy", "", "VOICE_AI_VOLC_APP_ID", "VOICE_AI_VOLC_ACCESS_KEY");
        var auth = Support.map(Support.map(spec.get("volc_auth")).get(service));
        String mode = Support.str(auth, "mode", ""),
                prefix = "VOICE_AI_VOLC_" + service.toUpperCase(Locale.ROOT) + "_";
        if (mode.equals("api_key")) {
            String ref = Support.str(auth, "api_key", "");
            if (Set.of(prefix + "API_KEY", "VOICE_AI_VOLC_API_KEY").contains(ref))
                return new VolcAuth(mode, ref, "", "");
        } else if (mode.equals("legacy")) {
            String app = Support.str(auth, "app_id", ""),
                    token = Support.str(auth, "access_key", "");
            if ((app.equals(prefix + "APP_ID") && token.equals(prefix + "ACCESS_KEY"))
                    || (app.equals("VOICE_AI_VOLC_APP_ID")
                            && token.equals("VOICE_AI_VOLC_ACCESS_KEY")))
                return new VolcAuth(mode, "", app, token);
        }
        throw new Support.Failure(503, "invalid voice AI credential snapshot");
    }

    private List<String> required(Map<String, Object> spec) {
        var p = profile(Support.str(spec, "id", ""));
        return switch (p.vendor) {
            case "aliyun" ->
                    p.route.equals("realtime")
                            ? List.of("DASHSCOPE_API_KEY", "VOICE_AI_ALI_REALTIME_URL")
                            : List.of("DASHSCOPE_API_KEY");
            case "volcengine" -> {
                var refs = new ArrayList<String>();
                for (String service : services(p))
                    refs.addAll(volcAuth(spec, service).references());
                if (p.route.equals("cascade")) {
                    refs.add("VOICE_AI_VOLC_LLM_API_KEY");
                    refs.add("VOICE_AI_VOLC_LLM_MODEL");
                }
                yield refs.stream().distinct().toList();
            }
            case "tencent" ->
                    List.of(
                            "VOICE_AI_TENCENT_APP_ID",
                            "VOICE_AI_TENCENT_SECRET_ID",
                            "VOICE_AI_TENCENT_SECRET_KEY",
                            "VOICE_AI_TENCENT_LLM_API_KEY");
            default -> List.of("VOICE_AI_BAIDU_API_KEY");
        };
    }

    public List<String> missing(String id) {
        return missing(snapshot(id));
    }

    private List<String> missing(Map<String, Object> spec) {
        var result = new ArrayList<String>();
        for (String key : required(spec)) {
            try {
                if (settings.secret(key).isBlank()) result.add(key);
            } catch (IllegalArgumentException unreadable) {
                result.add(key);
            }
        }
        return result;
    }

    public void requireReady(String id) {
        requireReady(snapshot(id));
    }

    public void requireReady(Map<String, Object> spec) {
        var missing = missing(spec);
        if (!missing.isEmpty())
            throw new Support.Failure(
                    503, "voice AI configuration missing: " + String.join(", ", missing));
    }

    private String value(String key) {
        String value = settings.get(key, "");
        return value.isBlank() ? DEFAULTS.getOrDefault(key, "") : value;
    }

    public Map<String, Object> snapshot(String id) {
        var p = profile(id);
        var result = new LinkedHashMap<String, Object>();
        result.put("id", p.id);
        result.put("name", p.name);
        result.put("vendor", p.vendor);
        result.put("route", p.route);
        String prefix =
                switch (p.vendor) {
                    case "aliyun" -> "ALI";
                    case "volcengine" -> "VOLC";
                    case "tencent" -> "TENCENT";
                    default -> "BAIDU";
                };
        for (String key :
                p.route.equals("cascade")
                        ? List.of(
                                "ASR_URL",
                                "TTS_URL",
                                "LLM_URL",
                                "ASR_MODEL",
                                "LLM_MODEL",
                                "TTS_MODEL",
                                "ASR_RESOURCE",
                                "TTS_RESOURCE",
                                "VOICE")
                        : List.of("REALTIME_URL", "REALTIME_MODEL", "REALTIME_VOICE")) {
            String value = value("VOICE_AI_" + prefix + "_" + key);
            if (key.endsWith("URL")) endpoint(value, key.equals("LLM_URL"));
            result.put(key.toLowerCase(Locale.ROOT), value);
        }
        if (p.vendor.equals("volcengine")) {
            var auth = new LinkedHashMap<String, Object>();
            for (String service : services(p))
                auth.put(service, selectVolcAuth(service).snapshot());
            result.put("volc_auth", Collections.unmodifiableMap(auth));
        }
        long tenant = settings.number("VOICE_AI_CONFIG_TENANT", 0, 0, Long.MAX_VALUE);
        if (tenant > 0)
            result.put(
                    "credential_scope",
                    Map.of(
                            "tenant",
                            tenant,
                            "revision",
                            settings.number("VOICE_AI_CONFIG_REVISION", 0, 0, Long.MAX_VALUE)));
        var approvals =
                Support.object(
                        settings.get("VOICE_AI_APPROVED_QUOTAS_JSON", "{}")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        result.put("approved_quotas", Support.map(approvals.get(p.vendor)));
        result.put("model_tier", settings.get("VOICE_AI_" + prefix + "_MODEL_TIER", "unverified"));
        result.put(
                "capabilities",
                Map.of(
                        "cancel",
                        "unknown",
                        "context_truncate",
                        "unknown",
                        "tool_call",
                        "unknown",
                        "audio_text_alignment",
                        "unknown",
                        "session_resume",
                        "unknown"));
        result.put("revision", Support.hex(Support.sha(Support.json(result))));
        return Collections.unmodifiableMap(result);
    }

    public URI endpoint(String address, boolean http) {
        try {
            URI uri = URI.create(address);
            boolean local =
                    !settings.production()
                            && Set.of("localhost", "127.0.0.1", "::1", "[::1]")
                                    .contains(uri.getHost());
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getQuery() != null
                    || !Set.of(http ? "https" : "wss", local ? (http ? "http" : "ws") : "__none__")
                            .contains(uri.getScheme())) throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException invalid) {
            throw new Support.Failure(503, "invalid platform-managed voice AI endpoint");
        }
    }

    public List<Map<String, Object>> catalog() {
        return PROFILES.stream()
                .map(
                        p -> {
                            var item = new LinkedHashMap<>(snapshot(p.id));
                            item.put("missing", missing(p.id));
                            item.put("configured", missing(p.id).isEmpty());
                            item.put("verification", "未验证真实供应商与电话线路");
                            item.put("credential_refs", required(item));
                            if (p.id.equals("baidu-realtime"))
                                item.put("notice", "Flex 处于公测，需供应商开通权限");
                            return (Map<String, Object>) item;
                        })
                .toList();
    }
}
