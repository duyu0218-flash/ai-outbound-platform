package com.duozhi.outbound.marketing.journey;

import com.duozhi.outbound.marketing.common.MarketingStore;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Time for journeys, frequency control and statistics. Two stored time bases meet here:
 * application-local values (mk_touch_event.occurred_at and every P4 table, written in the JVM zone)
 * and database-session values (mk_dispatch.created_at, filled by CURRENT_TIMESTAMP). Both are
 * converted through instants, so a JVM and a MySQL server in different zones still agree.
 *
 * <p>{@link #fix(Instant)} / {@link #advance(Duration)} / {@link #reset()} exist for integration
 * tests and drills; production always runs on the system clock.
 */
@Component
public class MarketingClock {
    private final MarketingStore db;
    private final AtomicReference<Instant> fixed = new AtomicReference<>();
    private volatile ZoneOffset dbOffset;
    private volatile long dbOffsetCheckedAt;

    public MarketingClock(MarketingStore db) {
        this.db = db;
    }

    public Instant now() {
        Instant f = fixed.get();
        return f != null ? f : Instant.now();
    }

    public LocalDateTime appNow() {
        return app(now());
    }

    public void fix(Instant instant) {
        fixed.set(instant);
    }

    public void advance(Duration duration) {
        fixed.updateAndGet(f -> (f == null ? Instant.now() : f).plus(duration));
    }

    public void reset() {
        fixed.set(null);
    }

    public static ZoneId appZone() {
        return ZoneId.systemDefault();
    }

    public static LocalDateTime app(Instant instant) {
        return LocalDateTime.ofInstant(instant, appZone());
    }

    public static Instant fromApp(LocalDateTime local) {
        return local.atZone(appZone()).toInstant();
    }

    /** The database session zone, re-read every ten minutes. */
    public ZoneOffset dbOffset() {
        long t = System.currentTimeMillis();
        if (dbOffset == null || t - dbOffsetCheckedAt > 600_000) {
            long seconds = db.count("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())");
            // Round to the quarter hour: the two reads above can straddle a second boundary.
            long quarter = Math.round(seconds / 900.0) * 900;
            dbOffset = ZoneOffset.ofTotalSeconds((int) quarter);
            dbOffsetCheckedAt = t;
        }
        return dbOffset;
    }

    public LocalDateTime db(Instant instant) {
        return LocalDateTime.ofInstant(instant, dbOffset());
    }

    public Instant fromDb(LocalDateTime local) {
        return local.toInstant(dbOffset());
    }

    /** Seconds to add to a database-session DATETIME to obtain the application-local value. */
    public long dbToAppSeconds() {
        Instant probe = now();
        return appZone().getRules().getOffset(probe).getTotalSeconds()
                - dbOffset().getTotalSeconds();
    }
}
