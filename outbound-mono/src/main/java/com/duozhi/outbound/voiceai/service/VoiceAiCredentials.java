package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.config.UnifiedServiceSettings;
import com.duozhi.outbound.voiceai.dao.VoiceAiConfigMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-specific append-only encrypted versions. Only masked metadata leaves this service. */
@Service
public class VoiceAiCredentials {
    private final VoiceAiConfigMapper db;
    private final Config config;
    private final ObjectMapper json;
    private static final Set<String> FIELDS = fields();

    public VoiceAiCredentials(VoiceAiConfigMapper db, Config config, ObjectMapper json) {
        this.db = db;
        this.config = config;
        this.json = json;
    }

    private static Set<String> fields() {
        var keys = new LinkedHashSet<String>();
        for (String k : VoiceAiProfiles.DEFAULTS.keySet())
            if (!k.endsWith("_FILE")
                    && (!k.endsWith("_URL") || k.equals("VOICE_AI_ALI_REALTIME_URL"))) keys.add(k);
        return Collections.unmodifiableSet(keys);
    }

    public static boolean secretField(String key) {
        return key.equals("DASHSCOPE_API_KEY")
                || key.endsWith("_API_KEY")
                || key.endsWith("_APP_ID")
                || key.endsWith("_SECRET_ID")
                || key.endsWith("_SECRET_KEY")
                || key.endsWith("_ACCESS_KEY");
    }

    private byte[] master() {
        try {
            byte[] bytes =
                    Base64.getDecoder()
                            .decode(
                                    new UnifiedServiceSettings(config, "voice")
                                            .secret("VOICE_AI_CONFIG_MASTER_KEY"));
            if (bytes.length != 32) throw new IllegalArgumentException();
            return bytes;
        } catch (Exception invalid) {
            throw new ApiError(503, "后台凭据加密密钥未正确配置，请联系平台管理员");
        }
    }

    private Map<String, String> decode(long tenant, Map<String, Object> row) {
        if (row == null) return Map.of();
        try {
            byte[] all = Base64.getDecoder().decode(row.get("payload").toString());
            if (all.length < 29 || all[0] != 1) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(master(), "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(all, 1, 13)));
            cipher.updateAAD((tenant + ":" + row.get("revision")).getBytes(StandardCharsets.UTF_8));
            return json.readValue(
                    cipher.doFinal(Arrays.copyOfRange(all, 13, all.length)),
                    new TypeReference<Map<String, String>>() {});
        } catch (ApiError unavailable) {
            throw unavailable;
        } catch (Exception invalid) {
            throw new ApiError(503, "后台语音配置无法解密，请核对服务端密钥备份");
        }
    }

    private String encode(long tenant, long revision, Map<String, String> data) {
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(master(), "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD((tenant + ":" + revision).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(json.writeValueAsBytes(data));
            byte[] all = new byte[13 + encrypted.length];
            all[0] = 1;
            System.arraycopy(nonce, 0, all, 1, 12);
            System.arraycopy(encrypted, 0, all, 13, encrypted.length);
            return Base64.getEncoder().encodeToString(all);
        } catch (ApiError unavailable) {
            throw unavailable;
        } catch (Exception invalid) {
            throw new ApiError(503, "后台语音配置加密失败");
        }
    }

    public Settings root(Settings base) {
        return new Settings(Map.of()) {
            @Override
            public String get(String key, String fallback) {
                return base.get(key, fallback);
            }

            @Override
            public Settings voiceAi(long tenant, long revision) {
                return runtime(base, tenant, revision);
            }
        };
    }

    public Settings runtime(Settings base, long tenant, long requested) {
        var row =
                requested < 0
                        ? db.latest(tenant)
                        : requested == 0 ? null : db.version(tenant, requested);
        if (requested > 0 && row == null) throw new ApiError(503, "通话引用的语音凭据版本不存在");
        long revision = row == null ? 0 : ((Number) row.get("revision")).longValue();
        var values = decode(tenant, row);
        return new Settings(Map.of()) {
            @Override
            public String get(String key, String fallback) {
                if (key.equals("VOICE_AI_CONFIG_TENANT")) return "" + tenant;
                if (key.equals("VOICE_AI_CONFIG_REVISION")) return "" + revision;
                if (key.endsWith("_FILE") && values.containsKey(key.substring(0, key.length() - 5)))
                    return "";
                return values.containsKey(key) ? values.get(key) : base.get(key, fallback);
            }

            @Override
            public Settings voiceAi(long id, long version) {
                return runtime(base, id, version);
            }
        };
    }

    public Object describe(long tenant) {
        var row = db.latest(tenant);
        var values = decode(tenant, row);
        var runtime = runtime(new UnifiedServiceSettings(config, "voice"), tenant, -1);
        var result = new ArrayList<Map<String, Object>>();
        for (String key : FIELDS) {
            boolean secret = secretField(key);
            String value;
            try {
                value =
                        secret
                                ? runtime.secret(key)
                                : runtime.get(key, VoiceAiProfiles.DEFAULTS.getOrDefault(key, ""));
            } catch (IllegalArgumentException invalid) {
                value = "";
            }
            if (!secret && value.isBlank()) value = VoiceAiProfiles.DEFAULTS.getOrDefault(key, "");
            result.add(
                    Map.of(
                            "key",
                            key,
                            "secret",
                            secret,
                            "configured",
                            !value.isBlank(),
                            "value",
                            secret ? "" : value,
                            "mask",
                            secret && !value.isBlank() ? "••••••••" : "",
                            "source",
                            values.containsKey(key) ? "后台配置" : value.isBlank() ? "未配置" : "服务器配置"));
        }
        boolean writable;
        try {
            master();
            writable = true;
        } catch (ApiError invalid) {
            writable = false;
        }
        return Map.of(
                "revision",
                row == null ? 0 : row.get("revision"),
                "writable",
                writable,
                "fields",
                result);
    }

    @Transactional
    public Object save(long tenant, Map<String, Object> changes) {
        if (changes.isEmpty() || changes.size() > FIELDS.size())
            throw new ApiError(400, "配置项为空或过多");
        master();
        if (db.lock(tenant) == null) throw new ApiError(404, "租户不存在");
        var previous = db.latest(tenant);
        var values = new LinkedHashMap<>(decode(tenant, previous));
        for (var entry : changes.entrySet()) {
            String key = entry.getKey();
            if (!FIELDS.contains(key)
                    || !(entry.getValue() instanceof String raw)
                    || raw.length() > 4096) throw new ApiError(400, "无效的语音配置项");
            String value = raw.strip();
            if (key.equals("VOICE_AI_BAILIAN_TOOL_TARGET")
                    && !value.isEmpty()
                    && !value.matches("[A-Za-z0-9_-]{1,128}"))
                throw new ApiError(400, "业务透传目标 ID 格式无效");
            if (key.equals("VOICE_AI_ALI_REALTIME_URL") && !value.isEmpty()) {
                try {
                    URI uri = URI.create(value);
                    if (!"wss".equals(uri.getScheme())
                            || uri.getUserInfo() != null
                            || uri.getQuery() != null
                            || uri.getFragment() != null
                            || uri.getPort() != -1
                            || !"/api-ws/v1/realtime".equals(uri.getPath())
                            || !uri.getHost()
                                    .matches(
                                            "[a-zA-Z0-9-]+\\.(cn-beijing|ap-southeast-1)\\.maas\\.aliyuncs\\.com"))
                        throw new IllegalArgumentException();
                } catch (Exception invalid) {
                    throw new ApiError(400, "千问实时地址必须是对应业务空间的官方 WSS 地址");
                }
            }
            values.put(key, value);
        }
        long revision = previous == null ? 1 : ((Number) previous.get("revision")).longValue() + 1;
        db.insert(tenant, revision, encode(tenant, revision, values));
        return describe(tenant);
    }
}
