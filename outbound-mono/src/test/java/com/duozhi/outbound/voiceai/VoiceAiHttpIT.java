package com.duozhi.outbound.voiceai;

import static org.assertj.core.api.Assertions.*;

import com.duozhi.outbound.auth.dao.*;
import com.duozhi.outbound.auth.domain.*;
import com.duozhi.outbound.auth.service.Crypto;
import com.duozhi.outbound.auth.support.UserRecords;
import com.duozhi.outbound.call.controller.VoiceCallbacks;
import com.duozhi.outbound.common.dao.Store;
import com.duozhi.outbound.common.support.Values;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.system.service.Settings;
import com.duozhi.outbound.voiceai.dao.VoiceAiBenchmarkMapper;
import com.duozhi.outbound.voiceai.dao.VoiceAiConfigMapper;
import com.duozhi.outbound.voiceai.service.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VoiceAiHttpIT {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("VOICE_AI_CONFIG_MASTER_KEY", () -> Base64.getEncoder().encodeToString(new byte[32]));
        p.add("JWT_SECRET", () -> "voice-ai-http-synthetic-secret-over-thirty-two-characters");
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.flyway.url", MYSQL::getJdbcUrl);
        p.add("spring.flyway.user", () -> "root");
        p.add("spring.flyway.password", MYSQL::getPassword);
        p.add("spring.data.redis.host", () -> "127.0.0.1");
        p.add("spring.data.redis.port", () -> 9);
        p.add("REDIS_URL", () -> "");
        p.add("JAVA_WORKERS_ENABLED", () -> "false");
        p.add("JAVA_DISPATCH_ENABLED", () -> "false");
        p.add("spring.ai.model.chat", () -> "none");
        p.add("RATE_LIMIT_BURST", () -> 2000);
        p.add("RATE_LIMIT_RPS", () -> 1000);
        VoiceAiProfiles.DEFAULTS.forEach(
                (key, value) -> {
                    if (key.endsWith("KEY") || key.endsWith("ID") || key.endsWith("_FILE"))
                        p.add(key, () -> "");
                });
    }

    @Autowired
    VoiceAiCredentials credentials;
    @Autowired
    VoiceAiConfigMapper credentialDb;
    @Autowired
    VoiceAiBenchmarkMapper benchmarks;
    @Autowired
    VoiceCallbacks callbacks;
    @Autowired
    TestRestTemplate http;
    @Autowired
    Store store;
    @Autowired
    SysUserMapper users;
    @Autowired
    TenantMapper tenants;
    @Autowired
    Crypto crypto;
    @Autowired
    Settings settings;
    @LocalServerPort
    int port;
    String admin, agent, other;

    String user(long tenant, String role, String username) {
        var u = new SysUser();
        u.setTenantId(Math.toIntExact(tenant));
        u.setUsername(username);
        u.setFullName(username);
        u.setRole(role);
        u.setPasswordHash(crypto.password("SyntheticVoicePass123!"));
        users.insert(u);
        return crypto.token(UserRecords.map(u));
    }

    ResponseEntity<JsonNode> call(HttpMethod method, String path, String token, Object body) {
        var headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
                "/api/v1/admin/voice-ai" + path,
                method,
                new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    @Test
    void tenantSettingsSnapshotsSixResultsPermissionsAndBrowserWork() throws Exception {
        for (long id : List.of(1L, 2L)) {
            var t = new Tenant();
            t.setId(Math.toIntExact(id));
            t.setCode("voice-tenant-" + id);
            t.setName("Voice tenant");
            tenants.insert(t);
        }
        admin = user(1, "admin", "voice-browser-admin");
        agent = user(1, "agent", "voice-browser-agent");
        other = user(2, "admin", "voice-other-admin");
        assertThat(call(HttpMethod.GET, "/profiles", null, null).getStatusCode().value())
                .isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/profiles", agent, null).getStatusCode().value())
                .isEqualTo(403);
        var catalog = call(HttpMethod.GET, "/profiles", admin, null);
        assertThat(catalog.getStatusCode().value()).as(catalog.toString()).isEqualTo(200);
        assertThat(catalog.getBody().size()).isEqualTo(6);
        assertThat(
                        call(
                                        HttpMethod.PUT,
                                        "/configuration",
                                        agent,
                                        Map.of("changes", Map.of("DASHSCOPE_API_KEY", "forbidden")))
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        var configured =
                call(
                        HttpMethod.PUT,
                        "/configuration",
                        admin,
                        Map.of(
                                "changes",
                                Map.of("DASHSCOPE_API_KEY", "private-synthetic-vendor-key")));
        assertThat(configured.getStatusCode().value()).as(configured.toString()).isEqualTo(200);
        assertThat(configured.getBody().toString()).doesNotContain("private-synthetic-vendor-key");
        assertThat(credentialDb.latest(1).get("payload").toString())
                .doesNotContain("private-synthetic-vendor-key");
        var scope =
                new com.duozhi.outbound.integration.config.UnifiedServiceSettings(
                        new com.duozhi.outbound.common.config.Config(
                                new org.springframework.mock.env.MockEnvironment()),
                        "voice");
        var oldRuntime = credentials.runtime(scope, 1, -1);
        long oldRevision = Long.parseLong(oldRuntime.get("VOICE_AI_CONFIG_REVISION", "0"));
        assertThat(credentials.runtime(scope, 2, -1).secret("DASHSCOPE_API_KEY")).isEmpty();
        assertThat(
                        call(
                                        HttpMethod.PUT,
                                        "/configuration",
                                        admin,
                                        Map.of(
                                                "changes",
                                                Map.of(
                                                        "VOICE_AI_ALI_REALTIME_URL",
                                                        "wss://localhost/steal")))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(
                        call(
                                        HttpMethod.PUT,
                                        "/configuration",
                                        admin,
                                        Map.of(
                                                "changes",
                                                Map.of("VOICE_AI_CONFIG_MASTER_KEY", "forbidden")))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(
                        call(
                                        HttpMethod.PUT,
                                        "/configuration",
                                        admin,
                                        Map.of("changes", Map.of("DASHSCOPE_API_KEY", "")))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(credentials.runtime(scope, 1, -1).secret("DASHSCOPE_API_KEY")).isEmpty();
        assertThat(credentials.runtime(scope, 1, oldRevision).secret("DASHSCOPE_API_KEY"))
                .isEqualTo("private-synthetic-vendor-key");
        assertThat(new VoiceAiProfiles(oldRuntime).snapshot("aliyun-cascade").toString())
                .doesNotContain("private-synthetic-vendor-key")
                .contains("credential_scope");
        var headers = new HttpHeaders();
        headers.setBearerAuth(admin);
        var ai = settings.get(1, "ai");
        ai.put("external_llm_enabled", true);
        for (var profile : VoiceAiProfiles.PROFILES) {
            ai.put("voice_backend", profile.id());
            var saved =
                    http.exchange(
                            "/api/v1/admin/settings/ai",
                            HttpMethod.PUT,
                            new HttpEntity<>(Map.of("data", ai), headers),
                            JsonNode.class);
            assertThat(saved.getStatusCode().value()).as(saved.toString()).isEqualTo(200);
            assertThat(settings.freezeAi(1).get("_voice_profile")).isNotNull();
        }
        var frozen = settings.freezeAi(1);
        ai.put("voice_backend", "aliyun-cascade");
        http.exchange(
                "/api/v1/admin/settings/ai",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("data", ai), headers),
                JsonNode.class);
        assertThat(((Map<?, ?>) frozen.get("_voice_profile")).get("id"))
                .isEqualTo("baidu-realtime");
        var voiceCall =
                store.insert(
                        "callsession",
                        Values.map(
                                "tenant_id",
                                1,
                                "phone",
                                "13100000009",
                                "mode",
                                "ai_only",
                                "status",
                                "in_ai",
                                "attempts",
                                1));
        var state =
                store.insert(
                        "conversationstate",
                        Values.map(
                                "tenant_id",
                                1,
                                "call_id",
                                voiceCall.get("id"),
                                "attempt",
                                1,
                                "policy_json",
                                new String(
                                        Support.json(Map.of("_ai", frozen, "_script", "固定话术")),
                                        StandardCharsets.UTF_8)));
        var evidence =
                http.exchange(
                        "/api/v1/calls/" + voiceCall.get("id") + "/voice-ai",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        JsonNode.class);
        assertThat(evidence.getStatusCode().value()).as(evidence.toString()).isEqualTo(200);
        assertThat(evidence.getBody().get(0).path("id").asText()).isEqualTo("baidu-realtime");
        assertThat(evidence.getBody().toString()).doesNotContain("realtime_url");
        int before = ((Number) callbacks.realtime(voiceCall).get("turn_sequence")).intValue();
        var aiReply =
                Values.<String, Object>map(
                        "call_id",
                        voiceCall.get("id"),
                        "attempt",
                        1,
                        "event_id",
                        "realtime-model-reply",
                        "is_final",
                        true,
                        "speaker_role",
                        "ai",
                        "transcript",
                        "这是模型的真实语音回复",
                        "asr_provider",
                        "baidu-realtime");
        var stored = callbacks.handle("telephony_speech", aiReply);
        assertThat(callbacks.handle("telephony_speech", aiReply).get("turn_id"))
                .isEqualTo(stored.get("turn_id"));
        assertThat(callbacks.realtime(voiceCall).get("turn_sequence")).isEqualTo(before);
        assertThat(store.require("conversationstate", 1, state.get("id")).get("generation"))
                .isEqualTo(0);
        var template =
                store.insert(
                        "scripttemplate",
                        Values.map(
                                "tenant_id",
                                1,
                                "name",
                                "统一对比话术",
                                "category",
                                "default",
                                "content",
                                "已批准的原始话术",
                                "is_active",
                                true));
        var created =
                call(
                        HttpMethod.POST,
                        "/test-sets",
                        admin,
                        Map.of(
                                "name",
                                "HTTP统一样本",
                                "script_template_id",
                                template.get("id"),
                                "samples",
                                List.of(Map.of("text", "我想了解服务"))));
        assertThat(created.getStatusCode().value()).as(created.toString()).isEqualTo(200);
        String id = created.getBody().path("id").asText();
        store.update("scripttemplate", 1, template.get("id"), Values.map("content", "之后修改的话术"));
        assertThat(
                        call(HttpMethod.GET, "/test-sets/" + id, admin, null)
                                .getBody()
                                .path("script_snapshot")
                                .asText())
                .isEqualTo("已批准的原始话术");
        assertThat(call(HttpMethod.GET, "/test-sets/" + id, other, null).getStatusCode().value())
                .isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/test-sets", other, null).getBody().size()).isZero();
        assertThat(
                        call(
                                        HttpMethod.POST,
                                        "/test-sets/" + id + "/runs",
                                        admin,
                                        Map.of(
                                                "profile_id",
                                                "invented",
                                                "request_id",
                                                "request-unknown"))
                                .getStatusCode()
                                .value())
                .isEqualTo(422);
        for (var profile : VoiceAiProfiles.PROFILES) {
            String key = "request-" + profile.id();
            var body = Map.of("profile_id", profile.id(), "request_id", key);
            var run = call(HttpMethod.POST, "/test-sets/" + id + "/runs", admin, body);
            assertThat(run.getStatusCode().value()).as(run.toString()).isEqualTo(200);
            assertThat(run.getBody().path("results").get(0).path("status").asText())
                    .isEqualTo("unconfigured");
            assertThat(
                            call(HttpMethod.POST, "/test-sets/" + id + "/runs", admin, body)
                                    .getBody()
                                    .path("id"))
                    .isEqualTo(run.getBody().path("id"));
        }
        assertThat(call(HttpMethod.GET, "/test-sets/" + id + "/runs", admin, null).getBody().size())
                .isEqualTo(6);
        assertThat(call(HttpMethod.GET, "/test-sets/" + id + "/runs", other, null).getBody().size())
                .isZero();
        assertThat(
                        call(
                                        HttpMethod.POST,
                                        "/test-sets",
                                        admin,
                                        Map.of(
                                                "name",
                                                "invalid",
                                                "script",
                                                "话术",
                                                "samples",
                                                List.of(Map.of("audio_wav_base64", "invalid"))))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        String audioRun = UUID.randomUUID().toString(),
                sampleId = created.getBody().path("samples").get(0).path("id").asText();
        byte[] testAudio = VoiceAiAudio.wav(new byte[8000], 8000);
        benchmarks.createRun(
                Map.of(
                        "id",
                        audioRun,
                        "tenant",
                        1,
                        "set",
                        id,
                        "profile",
                        "baidu-realtime",
                        "key",
                        "audio-fixture-request",
                        "snapshot",
                        new String(
                                Support.json(frozen.get("_voice_profile")),
                                StandardCharsets.UTF_8)));
        benchmarks.finish(
                Map.of(
                        "tenant",
                        1,
                        "id",
                        audioRun,
                        "status",
                        "succeeded",
                        "results",
                        new String(
                                Support.json(
                                        List.of(
                                                Map.of(
                                                        "sample_id",
                                                        sampleId,
                                                        "status",
                                                        "succeeded",
                                                        "audio_available",
                                                        true,
                                                        "scope",
                                                        "本机试听夹具，非真实供应商"))),
                                StandardCharsets.UTF_8),
                        "audios",
                        new String(
                                Support.json(
                                        Map.of(
                                                sampleId,
                                                Base64.getEncoder().encodeToString(testAudio))),
                                StandardCharsets.UTF_8)));
        var audioResult =
                http.exchange(
                        "/api/v1/admin/voice-ai/runs/" + audioRun + "/audio/" + sampleId,
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        byte[].class);
        assertThat(audioResult.getStatusCode().value()).isEqualTo(200);
        assertThat(audioResult.getBody()).isEqualTo(testAudio);
        var otherHeaders = new HttpHeaders();
        otherHeaders.setBearerAuth(other);
        assertThat(
                        http.exchange(
                                        "/api/v1/admin/voice-ai/runs/"
                                                + audioRun
                                                + "/audio/"
                                                + sampleId,
                                        HttpMethod.GET,
                                        new HttpEntity<>(otherHeaders),
                                        byte[].class)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        assertThat(
                        call(HttpMethod.GET, "/test-sets/" + id + "/runs", admin, null)
                                .getBody()
                                .toString())
                .doesNotContain("audio_wav_base64");
        ai.put("voice_backend", "legacy");
        http.exchange(
                "/api/v1/admin/settings/ai",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("data", ai), headers),
                JsonNode.class);
        Path wav = Path.of("target/voice-ai-browser.wav").toAbsolutePath();
        Files.write(wav, VoiceAiAudio.wav(new byte[8000], 8000));
        Path log = Path.of("target/voice-ai-browser.log");
        String node =
                System.getenv()
                        .getOrDefault(
                                "NODE_BINARY",
                                System.getProperty("user.home")
                                        + "/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node");
        var process =
                new ProcessBuilder(node, "frontend/tests/voice-ai-browser.mjs")
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
        process.environment().put("MONO_BROWSER_URL", "http://127.0.0.1:" + port);
        process.environment().put("VOICE_AI_TEST_WAV", wav.toString());
        Process running = process.start();
        boolean complete = running.waitFor(240, TimeUnit.SECONDS);
        if (!complete) running.destroyForcibly();
        assertThat(complete).as(Files.readString(log)).isTrue();
        assertThat(running.exitValue()).as(Files.readString(log)).isZero();
    }
}
