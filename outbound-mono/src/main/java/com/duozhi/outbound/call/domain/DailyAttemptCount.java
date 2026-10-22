package com.duozhi.outbound.call.domain;

import java.time.LocalDateTime;
import java.util.Map;

/** The two independent predicates of the legacy SQL, never a total++ cache. */
public record DailyAttemptCount(long legacy, long events) {
    public DailyAttemptCount {
        if (legacy < 0 || events < 0)
            throw new IllegalStateException("negative daily attempt component");
    }

    public long effective() {
        return Math.max(legacy, events);
    }

    public DailyAttemptCount changed(
            Map<String, Object> before,
            Map<String, Object> after,
            String timestamp,
            LocalDateTime day,
            long eventDelta) {
        long delta = contribution(after, timestamp, day) - contribution(before, timestamp, day);
        return new DailyAttemptCount(
                Math.addExact(legacy, delta), Math.addExact(events, eventDelta));
    }

    static long contribution(Map<String, Object> call, String timestamp, LocalDateTime day) {
        Object raw = call.get(timestamp);
        if (raw == null) return 0;
        LocalDateTime time =
                raw instanceof LocalDateTime value
                        ? value
                        : LocalDateTime.parse(raw.toString().replace(' ', 'T'));
        return time.isBefore(day) ? 0 : ((Number) call.getOrDefault("attempts", 0)).longValue();
    }
}
