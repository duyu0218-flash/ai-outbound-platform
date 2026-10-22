package com.duozhi.outbound.capacity.domain;

import java.time.*;
import java.util.*;

/** G0 plan validation; business-day coverage and continuous capacity are separate gates. */
public record CapacityRunPlan(
        Instant warmStart,
        Instant firstStart,
        Instant latestStart,
        long targetSeconds,
        Duration meanOccupancy,
        Duration pendingRequestAllowance,
        List<Window> legalWindows) {
    public record Window(Instant start, Instant end) {
        public Window {
            if (start == null || end == null || !start.isBefore(end))
                throw new IllegalArgumentException("invalid legal window");
        }

        boolean contains(Instant a, Instant b) {
            return !a.isBefore(start) && !b.isAfter(end);
        }
    }

    public CapacityRunPlan {
        Objects.requireNonNull(warmStart);
        Objects.requireNonNull(firstStart);
        Objects.requireNonNull(latestStart);
        Objects.requireNonNull(meanOccupancy);
        Objects.requireNonNull(pendingRequestAllowance);
        legalWindows = List.copyOf(legalWindows);
        if (targetSeconds < 1
                || targetSeconds > 7 * 86400
                || meanOccupancy.isNegative()
                || pendingRequestAllowance.isNegative()
                || latestStart.isBefore(firstStart))
            throw new IllegalArgumentException("invalid capacity plan");
    }

    public long seconds() {
        return Math.max(3600, targetSeconds);
    }

    public List<String> errors() {
        var errors = new ArrayList<String>();
        Duration required =
                meanOccupancy.multipliedBy(2).compareTo(Duration.ofSeconds(30)) > 0
                        ? meanOccupancy.multipliedBy(2)
                        : Duration.ofSeconds(30);
        if (Duration.between(warmStart, firstStart).compareTo(required) < 0)
            errors.add("minimum warmup duration not covered");
        Instant lastCompletion = latestStart.plusSeconds(seconds()).plus(pendingRequestAllowance);
        if (legalWindows.stream().noneMatch(w -> w.contains(warmStart, lastCompletion)))
            errors.add(
                    "no single legal window covers warmup, full capacity and pending completions");
        return List.copyOf(errors);
    }

    public static boolean qualified(boolean continuousPassed, boolean workdayPassed) {
        return continuousPassed && workdayPassed;
    }
}
