package com.duozhi.outbound.dial.domain;

import com.duozhi.outbound.call.domain.DailyAttemptCount;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/** Transaction-local admission facts. Never shared across commits or JVMs. */
public final class DialBatchScope implements AutoCloseable {
    public Long dailyGeneration;
    public boolean journalPrepared;
    public final Map<String, Map<String, Object>> commands = new HashMap<>();
    public final List<Object[]> pendingCommands = new ArrayList<>();
    public final List<Object[]> readmissions = new ArrayList<>();
    private static final ThreadLocal<DialBatchScope> CURRENT = new ThreadLocal<>();
    public final Instant instant = Instant.now();
    public final Map<String, Map<String, Object>> calls = new HashMap<>(),
            tenants = new HashMap<>(),
            settings = new HashMap<>(),
            scenarios = new HashMap<>(),
            states = new HashMap<>(),
            contacts = new HashMap<>(),
            facts = new HashMap<>(),
            campaigns = new HashMap<>();
    public final Map<String, Map<String, Object>> initialPolicies = new HashMap<>(),
            parsedPolicies = new HashMap<>();
    public final Map<Long, List<Map<String, Object>>> lines = new HashMap<>();
    public final Map<String, Long> counts = new HashMap<>();
    public final Map<String, Boolean> hours = new HashMap<>();
    public final Map<String, DailyAttemptCount> dailyCounts = new HashMap<>();
    public final Map<String, LocalDateTime> phoneDayStarts = new HashMap<>();
    public final Set<String> stateKeys = new HashSet<>();
    private final DialBatchScope previous;

    public DialBatchScope() {
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static DialBatchScope current() {
        return CURRENT.get();
    }

    public static String stateKey(Map<String, Object> call) {
        return call.get("id") + ":" + call.get("attempts");
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
