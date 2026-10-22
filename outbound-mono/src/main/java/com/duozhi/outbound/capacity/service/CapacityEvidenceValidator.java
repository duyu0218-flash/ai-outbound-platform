package com.duozhi.outbound.capacity.service;

import java.util.*;

/**
 * Offline conservative evidence calculation. All timestamps are integer microseconds. This
 * validates exported facts; it does not generate a passing capacity result.
 */
public final class CapacityEvidenceValidator {
    public static final long SECOND = 1_000_000L;

    /**
     * R500 is a separate real-line operating qualification, never a replacement for the original
     * P650/v5 G3 margin requirement. Callers must label it.
     */
    public enum Profile {
        P650(650),
        R500(500);
        private final int averageMinimum;

        Profile(int averageMinimum) {
            this.averageMinimum = averageMinimum;
        }

        public int averageMinimum() {
            return averageMinimum;
        }
    }

    public record ProfileResult(Profile profile, RateResult rates) {
        public boolean passed() {
            return rates.passed();
        }
    }

    public record Event(String identity, long lo, long hi) {
        public Event {
            if (identity == null || identity.isBlank() || lo > hi)
                throw new IllegalArgumentException("invalid event evidence");
        }
    }

    public record RateResult(
            long[] fixedSeconds,
            long slidingMinimum,
            long worstSlidingStart,
            long provableTotal,
            double average,
            List<String> errors) {
        public boolean passed() {
            return errors.isEmpty();
        }
    }

    private CapacityEvidenceValidator() {}

    public static RateResult rates(List<Event> evidence, long t0, int seconds) {
        return calculateRates(evidence, t0, seconds, Profile.P650);
    }

    public static ProfileResult rates(List<Event> evidence, long t0, int seconds, Profile profile) {
        return new ProfileResult(
                Objects.requireNonNull(profile), calculateRates(evidence, t0, seconds, profile));
    }

    private static RateResult calculateRates(
            List<Event> evidence, long t0, int seconds, Profile profile) {
        if (seconds < 1 || seconds > 7 * 86400)
            throw new IllegalArgumentException("explicit bounded run duration required");
        long t1 = Math.addExact(t0, Math.multiplyExact((long) seconds, SECOND));
        long lastStart = t1 - SECOND;
        long[] fixed = new long[seconds],
                starts = new long[evidence.size()],
                exits = new long[evidence.size()];
        int intervals = 0;
        long total = 0;
        var ids = new HashSet<String>();
        var errors = new ArrayList<String>();
        for (var event : evidence) {
            if (!ids.add(event.identity())) {
                errors.add("duplicate identity: " + event.identity());
                continue;
            }
            if (event.lo() >= t0 && event.hi() < t1) {
                total++;
                int bucket = (int) ((event.lo() - t0) / SECOND);
                if (event.hi() < t0 + (bucket + 1L) * SECOND) fixed[bucket]++;
            }
            // [a,a+W) provably contains [lo,hi] exactly when
            // hi-W+1 <= a <= lo on the integer microsecond grid.
            long first = Math.max(t0, Math.addExact(Math.subtractExact(event.hi(), SECOND), 1));
            long last = Math.min(lastStart, event.lo());
            if (first <= last) {
                starts[intervals] = first;
                exits[intervals] = Math.addExact(last, 1);
                intervals++;
            }
        }
        Arrays.sort(starts, 0, intervals);
        Arrays.sort(exits, 0, intervals);
        long active = 0, min = Long.MAX_VALUE, worst = t0, point = t0;
        int begin = 0, end = 0;
        while (point <= lastStart) {
            while (end < intervals && exits[end] <= point) {
                active--;
                end++;
            }
            while (begin < intervals && starts[begin] <= point) {
                active++;
                begin++;
            }
            if (active < min) {
                min = active;
                worst = point;
            }
            long next =
                    Math.min(
                            begin < intervals ? starts[begin] : Long.MAX_VALUE,
                            end < intervals ? exits[end] : Long.MAX_VALUE);
            if (next > lastStart) break;
            point = next;
        }
        for (int k = 0; k < fixed.length; k++)
            if (fixed[k] < 500) {
                errors.add("fixed second below 500: " + k);
                break;
            }
        if (min < 500) errors.add("sliding second below 500 at " + worst);
        double average = total / (double) seconds;
        if (average < profile.averageMinimum())
            errors.add("whole-window average below " + profile.averageMinimum());
        return new RateResult(fixed, min, worst, total, average, List.copyOf(errors));
    }

    public record Request(
            String identity, Event plan, Event queued, Event acknowledged, Event pbx) {
        public Request {
            if (identity == null || identity.isBlank() || plan == null)
                throw new IllegalArgumentException("request origin required");
        }
    }

    public record LatencyResult(Map<String, Long> p99Micros, List<String> errors) {
        public boolean passed() {
            return errors.isEmpty();
        }
    }

    public static LatencyResult deadlines(List<Request> requests, boolean realPbx) {
        var errors = new ArrayList<String>();
        var ids = new HashSet<String>();
        var samples = new LinkedHashMap<String, List<Long>>();
        for (String name : List.of("plan_queue", "queue_ack", "plan_ack"))
            samples.put(name, new ArrayList<>());
        if (realPbx) {
            samples.put("queue_pbx", new ArrayList<>());
            samples.put("plan_pbx", new ArrayList<>());
        }
        for (var request : requests) {
            if (!ids.add(request.identity()))
                errors.add("duplicate request: " + request.identity());
            stage(
                    request.identity(),
                    "plan_queue",
                    request.plan(),
                    request.queued(),
                    2 * SECOND,
                    samples,
                    errors);
            stage(
                    request.identity(),
                    "queue_ack",
                    request.queued(),
                    request.acknowledged(),
                    3 * SECOND,
                    samples,
                    errors);
            stage(
                    request.identity(),
                    "plan_ack",
                    request.plan(),
                    request.acknowledged(),
                    5 * SECOND,
                    samples,
                    errors);
            if (realPbx) {
                stage(
                        request.identity(),
                        "queue_pbx",
                        request.queued(),
                        request.pbx(),
                        5 * SECOND,
                        samples,
                        errors);
                stage(
                        request.identity(),
                        "plan_pbx",
                        request.plan(),
                        request.pbx(),
                        7 * SECOND,
                        samples,
                        errors);
            }
        }
        long[] limits =
                realPbx
                        ? new long[] {500_000, SECOND, 1_500_000, 2 * SECOND, 3 * SECOND}
                        : new long[] {500_000, SECOND, 1_500_000};
        var p99 = new LinkedHashMap<String, Long>();
        int index = 0;
        for (var entry : samples.entrySet()) {
            var values = entry.getValue();
            values.sort(Long::compare);
            if (values.isEmpty()) {
                errors.add("missing latency population: " + entry.getKey());
                index++;
                continue;
            }
            long value = values.get((int) Math.ceil(values.size() * .99) - 1);
            p99.put(entry.getKey(), value);
            if (value > limits[index]) errors.add("P99 exceeds limit: " + entry.getKey());
            index++;
        }
        return new LatencyResult(Map.copyOf(p99), List.copyOf(errors));
    }

    private static void stage(
            String id,
            String name,
            Event start,
            Event end,
            long hard,
            Map<String, List<Long>> samples,
            List<String> errors) {
        if (start == null || end == null) {
            errors.add("missing completion: " + id + "/" + name);
            return;
        }
        if (!id.equals(start.identity()) || !id.equals(end.identity()))
            errors.add("mismatched request evidence: " + id + "/" + name);
        if (end.hi() < start.lo()) {
            errors.add("impossible time ordering: " + id + "/" + name);
            return;
        }
        long delay = Math.subtractExact(end.hi(), start.lo());
        samples.get(name).add(delay);
        if (delay > hard) errors.add("hard deadline exceeded: " + id + "/" + name);
    }
}
