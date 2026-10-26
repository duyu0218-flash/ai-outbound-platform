package com.duozhi.outbound.voiceai.service;

import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.support.Support;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Atomic process-local tuple admission; the monolith remains a single control-plane instance. */
public final class VoiceResources {
    private final Settings settings;
    private final Map<String, Integer> used = new HashMap<>();

    public VoiceResources(Settings settings) {
        this.settings = settings;
    }

    public synchronized AutoCloseable acquire(
            Map<String, Object> profile, long tenant, String... kinds) {
        var requested = new LinkedHashMap<String, Integer>();
        for (String kind : kinds) {
            String vendor = Support.str(profile, "vendor", "unknown");
            if (kind.equals("ai")) {
                requested.put(
                        "ai", (int) settings.number("VOICE_AI_MAX_SESSIONS", 2250, 1, 100000));
                requested.put(
                        "tenant:" + tenant,
                        (int) settings.number("VOICE_AI_TENANT_MAX_SESSIONS", 2250, 1, 100000));
            } else {
                long approved =
                        Support.integer(Support.map(profile.get("approved_quotas")), kind, 0);
                if (approved == 0 && settings.production())
                    throw new Support.Failure(503, "voice quota approval is missing: " + kind);
                int limit = approved > 0 ? (int) Math.max(1, approved * 9 / 10) : 200;
                // Shared provider budgets intentionally stay shared across tenants and key
                // revisions.
                requested.put(vendor + ":" + kind, limit);
            }
        }
        for (var entry : requested.entrySet())
            if (used.getOrDefault(entry.getKey(), 0) >= entry.getValue())
                throw new Support.Failure(429, "voice resource exhausted: " + entry.getKey());
        requested.keySet().forEach(k -> used.merge(k, 1, Integer::sum));
        var released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true))
                synchronized (VoiceResources.this) {
                    requested
                            .keySet()
                            .forEach(
                                    k -> {
                                        int n = used.get(k) - 1;
                                        if (n == 0) used.remove(k);
                                        else used.put(k, n);
                                    });
                }
        };
    }

    public synchronized Map<String, Integer> usage() {
        return Map.copyOf(used);
    }
}
