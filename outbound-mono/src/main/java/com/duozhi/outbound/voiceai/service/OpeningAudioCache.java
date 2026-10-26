package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.support.Support;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Explicitly prepared deterministic disclosure, scoped by tenant, voice and credential revision.
 */
public final class OpeningAudioCache {
    private final Path directory;

    public OpeningAudioCache(Settings settings) {
        directory =
                Path.of(settings.get("VOICE_AI_OPENING_CACHE_DIR", "data/voice-openings"))
                        .toAbsolutePath();
    }

    public static Map<String, Object> speechProfile(Map<String, Object> profile) {
        return "realtime".equals(profile.get("route"))
                ? Support.map(profile.get("speech_profile"))
                : profile;
    }

    public static String key(long tenant, Map<String, Object> profile, String text) {
        var speech = speechProfile(profile);
        var voice = new LinkedHashMap<String, Object>();
        for (String field :
                List.of("vendor", "tts_url", "tts_model", "voice", "credential_scope", "volc_auth"))
            if (speech.containsKey(field)) voice.put(field, speech.get(field));
        return Support.hex(
                Support.sha(
                        Support.canonical(
                                Map.of("tenant", tenant, "speech", voice, "text", text))));
    }

    public byte[] read(long tenant, Map<String, Object> profile, String text) throws IOException {
        Path file = directory.resolve(key(tenant, profile, text) + ".pcm");
        if (!Files.isRegularFile(file)) return null;
        if (Files.size(file) > 1440000) throw new IOException("opening audio too large");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0 || bytes.length % 2 != 0)
            throw new IOException("invalid prepared opening");
        return bytes;
    }

    public Map<String, Object> prepare(
            long tenant, Map<String, Object> profile, String text, VoiceAiBackends backend)
            throws Exception {
        if (text.isBlank() || text.length() > 500)
            throw new IllegalArgumentException("invalid disclosure");
        var pcm = new ByteArrayOutputStream();
        var conversion = new com.duozhi.outbound.media.service.NativeMedia.PcmResampler[1];
        backend.synthesize(
                speechProfile(profile),
                text,
                (bytes, rate) -> {
                    if (conversion[0] == null)
                        conversion[0] =
                                new com.duozhi.outbound.media.service.NativeMedia.PcmResampler(
                                        rate, 24000);
                    byte[] result = conversion[0].convert(bytes);
                    if (pcm.size() + result.length > 1440000)
                        throw new IOException("opening audio too large");
                    pcm.writeBytes(result);
                });
        if (conversion[0] == null) throw new IOException("opening produced no audio");
        pcm.writeBytes(conversion[0].finish());
        Files.createDirectories(directory);
        Path file = directory.resolve(key(tenant, profile, text) + ".pcm");
        Path temporary = Files.createTempFile(directory, "opening-", ".tmp");
        try {
            Files.write(temporary, pcm.toByteArray());
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return Map.of(
                "key",
                key(tenant, profile, text),
                "sample_rate",
                24000,
                "samples",
                pcm.size() / 2,
                "sha256",
                Support.hex(Support.sha(pcm.toByteArray())),
                "prepared",
                true,
                "played",
                false);
    }
}
