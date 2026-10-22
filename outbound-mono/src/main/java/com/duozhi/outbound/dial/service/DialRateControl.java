package com.duozhi.outbound.dial.service;

import com.duozhi.outbound.callback.service.CallbackInbox;
import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.job.service.RealtimeTaskPressure;
import com.duozhi.outbound.voice.controller.VoiceController;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** A single age controller; hard health checks are independent of soft throttling. */
@Service
public class DialRateControl {
    private final Config config;
    private final CallbackInbox inbox;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<VoiceController> voice;
    private final RealtimeTaskPressure pressure;
    private long pressureLoggedAt;
    private volatile long sampledAt;
    private long tokenAt;
    private volatile double fraction;
    private double tokens;
    private ScheduledExecutorService sampler;

    public DialRateControl(
            Config config,
            CallbackInbox inbox,
            JdbcTemplate jdbc,
            ObjectProvider<VoiceController> voice,
            RealtimeTaskPressure pressure) {
        this.config = config;
        this.inbox = inbox;
        this.jdbc = jdbc;
        this.voice = voice;
        this.pressure = pressure;
    }

    boolean adaptive() {
        return "adaptive".equals(config.get("JAVA_DIAL_RATE_CONTROL", "legacy"));
    }

    static double target(double age, boolean healthy) {
        if (!healthy || !Double.isFinite(age) || age < 0 || age >= 5) return 0;
        if (age <= .5) return 1;
        if (age <= 3) return 1 - .7 * (age - .5) / 2.5;
        return .3 * (5 - age) / 2;
    }

    static double advance(double previous, double desired, double elapsed) {
        if (!Double.isFinite(previous) || previous < 0 || previous > 1 || elapsed < 0)
            throw new IllegalArgumentException("invalid rate state");
        return desired <= previous ? desired : Math.min(desired, previous + .1 * elapsed);
    }

    static double recoveryElapsed(long previous, long now) {
        long elapsed = now - previous;
        return previous == 0 || elapsed < 0 || elapsed > 600_000_000L
                ? 0
                : Math.min(.2, elapsed / 1e9);
    }

    double fraction() {
        if (!adaptive()) return 1;
        return sampledAt != 0 && System.nanoTime() - sampledAt <= 600_000_000L ? fraction : 0;
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void startSampler() {
        if (sampler != null || !adaptive() || !config.flag("JAVA_WORKERS_ENABLED", false)) return;
        sampler =
                Executors.newSingleThreadScheduledExecutor(
                        work -> {
                            var thread = new Thread(work, "dial-rate-sampler");
                            thread.setDaemon(true);
                            return thread;
                        });
        sampler.scheduleWithFixedDelay(this::sample, 0, 200, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    synchronized void stopSampler() {
        if (sampler != null) sampler.shutdownNow();
    }

    public void sample() {
        if (!adaptive() || !config.flag("JAVA_WORKERS_ENABLED", false)) return;
        long now = System.nanoTime();
        double elapsed = recoveryElapsed(sampledAt, now);
        if (sampledAt == 0 || now - sampledAt > 600_000_000L) fraction = 0;
        try {
            var receipt = inbox.snapshot();
            boolean healthy =
                    ((Number) receipt.get("live_workers")).longValue()
                                    >= config.number("CALLBACK_INBOX_MIN_WORKERS", 1)
                            && ((Number) receipt.get("dead")).longValue() == 0;
            var pressure =
                    jdbc.queryForMap(
                            "SELECT COALESCE(MAX(pending_count),0) AS"
                                    + " n,COALESCE(MAX(pending_bytes),0) AS b FROM"
                                    + " callbackinboxpartition");
            healthy &=
                    ((Number) pressure.get("n")).longValue()
                                    < config.number("CALLBACK_INBOX_PARTITION_LIMIT", 256)
                            && ((Number) pressure.get("b")).longValue()
                                    < config.number("CALLBACK_INBOX_PARTITION_BYTES", 4194304);
            var gateway = voice.getObject().metricValues();
            healthy &=
                    ((Number) gateway.get("ai_outbound_voice_gateway_").get("ready")).intValue()
                            == 1;
            var state = gateway.get("ai_outbound_voice_security_");
            healthy &= !Boolean.TRUE.equals(state.get("stopped"));
            double age =
                    Math.max(
                            ((Number) receipt.get("oldest_age_sec")).doubleValue(),
                            ((Number) state.get("oldest_callback_age_sec")).doubleValue());
            var realtime = this.pressure.snapshot();
            double desired =
                    Math.min(target(age, healthy), RealtimeTaskPressure.fraction(realtime));
            fraction = advance(fraction, desired, elapsed);
            if (config.flag("JAVA_DIAL_PROFILE", false))
                LoggerFactory.getLogger(DialRateControl.class)
                        .info(
                                "dial_sample target={} fraction={} sample_ms={} callback_age_sec={}"
                                        + " control_age_sec={} ai_age_sec={}",
                                desired,
                                fraction,
                                (System.nanoTime() - now) / 1_000_000.0,
                                age,
                                realtime.get("control_age_sec"),
                                realtime.get("ai_age_sec"));
            if (config.flag("JAVA_DIAL_PROFILE", false)
                    && now - pressureLoggedAt >= 1_000_000_000L) {
                pressureLoggedAt = now;
                LoggerFactory.getLogger(DialRateControl.class)
                        .info(
                                "dial_pressure callback_age_sec={} control_age_sec={} ai_age_sec={}"
                                        + " fraction={}",
                                age,
                                realtime.get("control_age_sec"),
                                realtime.get("ai_age_sec"),
                                fraction);
            }
        } catch (RuntimeException unavailable) {
            fraction = 0;
        }
        // Query time is part of the signal age. A blocked read must neither freshen
        // old health nor earn a large recovery jump after an outage.
        if (System.nanoTime() - now > 600_000_000L) fraction = 0;
        sampledAt = now;
    }

    synchronized boolean admit() {
        if (!adaptive()) return true;
        double current = fraction();
        long now = System.nanoTime();
        int base = config.number("JAVA_DIAL_BASE_CPS", 700);
        if (base < 1 || base > 10000) throw new ApiError(503, "invalid dial base CPS");
        int requested =
                config.number(
                        "JAVA_DIAL_TOKEN_BURST", Math.max(1, Math.min(100, (int) (base * .05))));
        if (requested < 1 || requested > Math.min(100, base))
            throw new ApiError(503, "invalid bounded dial token burst");
        double burst = Math.max(1, requested * current);
        if (tokenAt != 0) tokens = Math.min(burst, tokens + (now - tokenAt) / 1e9 * base * current);
        tokenAt = now;
        if (current == 0) {
            tokens = 0;
            return false;
        }
        if (tokens < 1) return false;
        tokens--;
        return true;
    }

    public boolean healthyForAdmission() {
        return adaptive() ? fraction() > 0 : Boolean.TRUE.equals(inbox.snapshot().get("ready"));
    }
}
