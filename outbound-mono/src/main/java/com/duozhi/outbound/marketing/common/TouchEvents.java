package com.duozhi.outbound.marketing.common;

import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.support.JsonSupport;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The single write path for touch facts. Each (source, code) is stored once; a newly stored fact is
 * published as {@link Recorded} after the surrounding transaction commits, so listeners (journeys,
 * profiles) never see a fact that was rolled back.
 */
@Service
public class TouchEvents {
    private final MarketingStore db;
    private final ApplicationEventPublisher events;
    private final JsonSupport json;

    public TouchEvents(MarketingStore db, ApplicationEventPublisher events, JsonSupport json) {
        this.db = db;
        this.events = events;
        this.json = json;
    }

    public record Touch(
            long tenant,
            String phoneHash,
            Long contactId,
            String channel,
            String code,
            String sourceType,
            String sourceId,
            Attribution.Dispatch dispatch,
            Map<String, Object> detail,
            LocalDateTime occurredAt) {}

    /** Published once per newly stored fact. */
    public record Recorded(String id, Touch touch) {}

    /** Returns the stored id, or null when the same source already reported this code. */
    public String record(Touch t) {
        if (!OutcomeCodes.ALL.contains(t.code())) throw new ApiError(422, "未知触达结果码");
        if (t.phoneHash() == null || t.phoneHash().length() != 64)
            throw new IllegalArgumentException("phone hash required");
        String id = MarketingStore.id("tev");
        var d = t.dispatch();
        int inserted =
                db.write(
                        "INSERT IGNORE INTO mk_touch_event(id,tenant_id,phone_hash,contact_id,channel,code,source_type,source_id,dispatch_id,snapshot_id,journey_id,journey_member_id,detail,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        id,
                        t.tenant(),
                        t.phoneHash(),
                        t.contactId() != null ? t.contactId() : d == null ? null : d.contactId(),
                        t.channel(),
                        t.code(),
                        t.sourceType(),
                        t.sourceId(),
                        d == null ? null : d.id(),
                        d == null ? null : d.snapshotId(),
                        d == null ? null : d.journeyId(),
                        d == null ? null : d.journeyMemberId(),
                        json.json(t.detail() == null ? Map.of() : t.detail()),
                        t.occurredAt() == null ? LocalDateTime.now() : t.occurredAt());
        if (inserted == 0) return null;
        var recorded = new Recorded(id, t);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            events.publishEvent(recorded);
                        }
                    });
        } else {
            events.publishEvent(recorded);
        }
        return id;
    }

    /** Newest first; for the profile timeline and module tests. */
    public List<Map<String, Object>> history(long tenant, String phoneHash, int limit) {
        return db.rows(
                "SELECT id,channel,code,source_type,source_id,snapshot_id,journey_id,detail,occurred_at FROM mk_touch_event WHERE tenant_id=? AND phone_hash=? ORDER BY occurred_at DESC,sequence DESC LIMIT ?",
                tenant,
                phoneHash,
                Math.max(1, Math.min(limit, 500)));
    }
}
