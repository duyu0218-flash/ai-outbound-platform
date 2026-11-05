package com.duozhi.outbound.marketing.outcome;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.runtime.ScheduledWork;
import com.duozhi.outbound.marketing.common.MarketingStore;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Classifies newly finished calls. Scans callsession by an (updated_at, id) watermark, which the
 * ix_callsession_updated_at index serves; rows newer than the settle delay are left for the next
 * pass so late transcripts and the call analysis have landed. Off unless MARKETING_WORKERS_ENABLED.
 */
@Component
public class OutcomeWorker {
    @org.springframework.beans.factory.annotation.Autowired
    private ScheduledWork scheduledWork;

    static final String NAME = "call_outcome";
    private final MarketingStore db;
    private final OutcomeService outcomes;
    private final Config config;
    private final TransactionTemplate tx;
    private final AtomicBoolean busy = new AtomicBoolean();

    public OutcomeWorker(
            MarketingStore db,
            OutcomeService outcomes,
            Config config,
            PlatformTransactionManager transactions) {
        this.db = db;
        this.outcomes = outcomes;
        this.config = config;
        this.tx = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${MARKETING_OUTCOME_POLL_MS:15000}")
    public void scheduledTick() {
        scheduledWork.submit(ScheduledWork.Job.MARKETING_OUTCOME, this::tick);
    }

    public void tick() {
        if (!config.flag("MARKETING_WORKERS_ENABLED", false) || !busy.compareAndSet(false, true))
            return;
        try {
            step(
                    config.number("MARKETING_OUTCOME_BATCH", 200),
                    Duration.ofSeconds(config.number("MARKETING_OUTCOME_SETTLE_SEC", 60)));
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(OutcomeWorker.class)
                    .warn("outcome worker failed: {}", e.getClass().getSimpleName());
        } finally {
            busy.set(false);
        }
    }

    /**
     * One pass: up to {@code limit} finished calls after the watermark. The watermark row is locked
     * for the pass so two application instances never scan the same window; each call is classified
     * in its own transaction. Returns the number of calls looked at.
     */
    public int step(int limit, Duration settle) {
        Integer n =
                tx.execute(
                        st -> {
                            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                            db.write(
                                    "INSERT IGNORE INTO mk_outcome_watermark(name,last_updated_at,last_call_id) VALUES(?,?,'')",
                                    NAME,
                                    now.minusHours(
                                            config.number("MARKETING_OUTCOME_LOOKBACK_HOURS", 24)));
                            Map<String, Object> mark =
                                    db.one(
                                            "SELECT last_updated_at,last_call_id FROM mk_outcome_watermark WHERE name=? FOR UPDATE",
                                            NAME);
                            LocalDateTime at = CallSignals.time(mark.get("last_updated_at"));
                            String id = mark.get("last_call_id").toString();
                            // Cross-tenant scan by design: the worker serves every tenant. Each
                            // row is then classified with its own tenant_id bound in every query.
                            List<Map<String, Object>> rows =
                                    db.rows(
                                            "SELECT id,tenant_id,updated_at FROM callsession WHERE updated_at<=? AND (updated_at>? OR (updated_at=? AND id>?)) AND finished_at IS NOT NULL AND status IN ('COMPLETED','FAILED','NO_ANSWER','BUSY','VOICEMAIL') ORDER BY updated_at,id LIMIT ?",
                                            now.minus(settle),
                                            at,
                                            at,
                                            id,
                                            Math.max(1, limit));
                            for (Map<String, Object> r : rows) {
                                long tenant = ((Number) r.get("tenant_id")).longValue();
                                String call = r.get("id").toString();
                                try {
                                    outcomes.classify(tenant, call, OutcomeService.WORKER);
                                } catch (RuntimeException e) {
                                    LoggerFactory.getLogger(OutcomeWorker.class)
                                            .warn(
                                                    "outcome classification failed for {}: {}",
                                                    call,
                                                    e.getClass().getSimpleName());
                                    try {
                                        outcomes.recordFailure(
                                                tenant, call, e.getClass().getSimpleName());
                                    } catch (RuntimeException ignored) {
                                    }
                                }
                            }
                            if (!rows.isEmpty()) {
                                var last = rows.getLast();
                                db.write(
                                        "UPDATE mk_outcome_watermark SET last_updated_at=?,last_call_id=? WHERE name=?",
                                        last.get("updated_at"),
                                        last.get("id"),
                                        NAME);
                            }
                            return rows.size();
                        });
        return n == null ? 0 : n;
    }
}
