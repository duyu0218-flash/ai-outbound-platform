package com.duozhi.outbound;

import java.time.LocalDateTime;
import java.util.Map;

/** The two independent predicates of the legacy SQL, never a total++ cache. */
record DailyAttemptCount(long legacy, long events) {
    DailyAttemptCount {
        if (legacy < 0 || events < 0) throw new IllegalStateException("negative daily attempt component");
    }

    long effective() { return Math.max(legacy, events); }

    DailyAttemptCount changed(Map<String,Object> before, Map<String,Object> after,
                              String timestamp, LocalDateTime day, long eventDelta) {
        long delta = contribution(after, timestamp, day) - contribution(before, timestamp, day);
        return new DailyAttemptCount(Math.addExact(legacy, delta), Math.addExact(events, eventDelta));
    }

    static long contribution(Map<String,Object> call, String timestamp, LocalDateTime day) {
        Object raw = call.get(timestamp);
        if (raw == null) return 0;
        LocalDateTime time = raw instanceof LocalDateTime value ? value
                : LocalDateTime.parse(raw.toString().replace(' ', 'T'));
        return time.isBefore(day) ? 0 : ((Number)call.getOrDefault("attempts", 0)).longValue();
    }
}
