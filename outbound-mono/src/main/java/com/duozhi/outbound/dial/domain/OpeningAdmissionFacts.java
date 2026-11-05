package com.duozhi.outbound.dial.domain;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.voiceai.service.OpeningAudioCache;
import java.util.*;

/** Immutable prepared audio facts; the locked path only matches identity and freshness. */
public final class OpeningAdmissionFacts implements AutoCloseable {
    public record Presence(boolean registered, long checkedAt) {}

    public record Receipt(String key, String sha256, long checkedAt) {
        public Map<String, Object> metadata() {
            return Map.of("key", key, "sha256", sha256);
        }
    }

    private static final ThreadLocal<OpeningAdmissionFacts> CURRENT = new ThreadLocal<>();
    private final OpeningAdmissionFacts previous;
    private final Map<String, Receipt> receipts;
    private final Map<String, Presence> presence;

    public OpeningAdmissionFacts(Map<String, Receipt> receipts) {
        this(receipts, Map.of());
    }

    public OpeningAdmissionFacts(Map<String, Receipt> receipts, Map<String, Presence> presence) {
        this.receipts = Map.copyOf(receipts);
        this.presence = Map.copyOf(presence);
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    public static boolean registered(long tenant, Object agent) {
        var scope = CURRENT.get();
        var fact = scope == null ? null : scope.presence.get(tenant + ":" + agent);
        return fact != null
                && fact.registered
                && System.nanoTime() - fact.checkedAt <= 5_000_000_000L;
    }

    public static Receipt require(long tenant, Map<String, Object> profile, String text) {
        var scope = CURRENT.get();
        var receipt =
                scope == null
                        ? null
                        : scope.receipts.get(OpeningAudioCache.key(tenant, profile, text));
        if (receipt == null || System.nanoTime() - receipt.checkedAt > 10_000_000_000L)
            throw new ApiError(409, "OPENING_AUDIO_FACTS_STALE");
        return receipt;
    }

    public static Receipt receipt(
            long tenant, Map<String, Object> profile, String text, byte[] pcm) {
        return new Receipt(
                OpeningAudioCache.key(tenant, profile, text),
                Support.hex(Support.sha(pcm)),
                System.nanoTime());
    }

    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
