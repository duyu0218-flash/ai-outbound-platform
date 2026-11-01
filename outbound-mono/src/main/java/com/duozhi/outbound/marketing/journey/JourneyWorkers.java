package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.common.config.Config;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background loops of module P4, all gated by MARKETING_WORKERS_ENABLED (default off). Tests call
 * {@link JourneyEngine#step}, {@link ProfileService#catchUp} and {@link FunnelStats#recompute}
 * directly.
 */
@Component
public class JourneyWorkers {
    private static final Logger log = LoggerFactory.getLogger(JourneyWorkers.class);
    private final Config config;
    private final JourneyEngine engine;
    private final ProfileService profiles;
    private final FunnelStats stats;
    private final AtomicBoolean journeys = new AtomicBoolean(),
            catchUp = new AtomicBoolean(),
            aggregate = new AtomicBoolean();

    public JourneyWorkers(
            Config config, JourneyEngine engine, ProfileService profiles, FunnelStats stats) {
        this.config = config;
        this.engine = engine;
        this.profiles = profiles;
        this.stats = stats;
    }

    private boolean enabled() {
        return config.flag("MARKETING_WORKERS_ENABLED", false);
    }

    @Scheduled(fixedDelayString = "${MARKETING_JOURNEY_POLL_MS:5000}")
    public void journeys() {
        if (!enabled() || !journeys.compareAndSet(false, true)) return;
        try {
            int batch = config.number("MARKETING_JOURNEY_BATCH", 200);
            for (int i = 0; i < 20 && engine.step(batch) >= batch; i++) {}
        } catch (RuntimeException e) {
            log.warn("journey worker pass failed: {}", e.toString());
        } finally {
            journeys.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${MARKETING_PROFILE_CATCHUP_MS:60000}")
    public void profiles() {
        if (!enabled() || !catchUp.compareAndSet(false, true)) return;
        try {
            profiles.catchUp(1000);
        } catch (RuntimeException e) {
            log.warn("profile catch-up failed: {}", e.toString());
        } finally {
            catchUp.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${MARKETING_STAT_POLL_MS:300000}")
    public void statistics() {
        if (!enabled() || !aggregate.compareAndSet(false, true)) return;
        try {
            stats.recomputeRecent(config.number("MARKETING_STAT_RECOMPUTE_HOURS", 3));
        } catch (RuntimeException e) {
            log.warn("hourly statistics failed: {}", e.toString());
        } finally {
            aggregate.set(false);
        }
    }
}
